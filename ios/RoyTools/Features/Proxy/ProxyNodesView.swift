import Observation
import SwiftUI

struct ProxyNodesView: View {
    let vpn: VPNController
    @State private var search = ""
    @State private var tests = ProxyLatencyTests()

    var body: some View {
        List {
            if vpn.isRunning {
                Section("代理选择") {
                    LabeledContent("当前选择", value: vpn.selectedGroup ?? "等待节点")
                }
                if !vpn.groups.isEmpty {
                    Section("代理组") {
                        ForEach(vpn.groups.filter { matchesProxySearch($0.name, search) }) { group in
                            NavigationLink {
                                ProxyGroupView(vpn: vpn, name: group.name, tests: tests)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(group.name)
                                        if let selected = group.selected {
                                            Text(selected).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    if vpn.selectedGroup == group.name {
                                        Image(systemName: "checkmark").accessibilityLabel("当前代理组")
                                    }
                                }
                            }
                        }
                    }
                }
                if !vpn.proxyNodes.isEmpty {
                    ProxyNodeSection(vpn: vpn, group: nil, search: search, tests: tests)
                } else if vpn.groups.isEmpty {
                    Text("当前配置没有可用的代理节点。")
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("连接VPN后可选择代理节点和测试延迟。")
                    .foregroundStyle(.secondary)
            }
            ProxySelectionStatus(vpn: vpn, tests: tests)
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth(860)
        .searchable(text: $search, prompt: "搜索代理组和节点")
        .navigationTitle("代理节点")
        .onChange(of: vpn.profile) { _, _ in tests.clear() }
        .onChange(of: vpn.isRunning) { _, running in if !running { tests.clear() } }
    }
}

private struct ProxyGroupView: View {
    let vpn: VPNController
    let name: String
    let tests: ProxyLatencyTests
    @State private var search = ""

    private var group: RoyToolsProxyGroup? { vpn.groups.first(where: { $0.name == name }) }

    var body: some View {
        List {
            if vpn.isRunning, let group {
                Section("代理选择") {
                    if vpn.selectedGroup == name {
                        Label("当前选用的代理组", systemImage: "checkmark")
                    } else {
                        Button("选用此代理组") { Task { await vpn.selectGroup(name) } }
                            .disabled(vpn.busy || vpn.isTransitioning)
                    }
                    if !group.selectable {
                        Text("此组由配置自动选择节点。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                ProxyNodeSection(vpn: vpn, group: group, search: search, tests: tests)
            } else {
                Text("当前连接中没有此代理组。")
                    .foregroundStyle(.secondary)
            }
            ProxySelectionStatus(vpn: vpn, tests: tests)
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth(860)
        .searchable(text: $search, prompt: "搜索组内节点")
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ProxyNodeSection: View {
    let vpn: VPNController
    let group: RoyToolsProxyGroup?
    let search: String
    let tests: ProxyLatencyTests

    private var canTest: Bool { !tests.isTesting && !vpn.busy && !vpn.isTransitioning }

    var body: some View {
        let allNodes = group?.nodes ?? vpn.proxyNodes
        let visibleNodes = allNodes.filter { matchesProxySearch($0, search) }
        Section(group == nil ? "节点" : "组内选择") {
            if !allNodes.isEmpty {
                Button("批量测试延迟", systemImage: "waveform.path") { measure(allNodes) }
                    .disabled(!canTest)
            }
            if visibleNodes.isEmpty {
                Text(allNodes.isEmpty ? "暂无可用节点" : "没有匹配的节点")
                    .foregroundStyle(.secondary)
            }
            ForEach(visibleNodes, id: \.self) { node in
                row(node)
            }
        }
    }

    private func row(_ node: String) -> some View {
        let selected = group.map { $0.selected == node } ?? (vpn.selectedGroup == node)
        return Group {
            if group?.selectable != false {
                Button {
                    guard !selected else { return }
                    Task {
                        if let group { await vpn.selectGroupMember(node, in: group.name) }
                        else { await vpn.selectGroup(node) }
                    }
                } label: {
                    nodeLabel(node, selected: selected)
                }
                .buttonStyle(.plain)
            } else {
                nodeLabel(node, selected: selected)
            }
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .disabled(vpn.busy || vpn.isTransitioning)
        .swipeActions(edge: .leading) {
            if canTest {
                Button("测试", systemImage: "waveform.path") { measure([node]) }
                    .tint(.accentColor)
            }
        }
    }

    private func nodeLabel(_ node: String, selected: Bool) -> some View {
        HStack(spacing: 8) {
            Text(node).foregroundStyle(.primary)
            Spacer()
            switch tests.result(for: node, in: group?.name) {
            case .testing:
                ProgressView().controlSize(.small).accessibilityLabel("正在测试延迟")
            case .measured(let milliseconds):
                Text("\(milliseconds)ms").monospacedDigit().foregroundStyle(.secondary)
            case .failed:
                Text("失败").foregroundStyle(.secondary)
            case nil:
                EmptyView()
            }
            if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
        }
        .contentShape(Rectangle())
    }

    private func measure(_ nodes: [String]) {
        Task { await tests.measure(nodes, in: group?.name, vpn: vpn) }
    }
}

private struct ProxySelectionStatus: View {
    let vpn: VPNController
    let tests: ProxyLatencyTests

    var body: some View {
        if vpn.busy { ProgressView("正在应用选择…") }
        if let message = tests.errorMessage ?? vpn.errorMessage ?? vpn.serviceIssue {
            ProxyMessage(text: message, systemImage: "exclamationmark.circle")
        }
    }
}

@MainActor @Observable
private final class ProxyLatencyTests {
    enum Result: Equatable { case testing, measured(Int), failed }

    private struct Node: Hashable {
        let name: String
        let group: String?
    }

    private var values: [Node: Result] = [:]
    private var generation = 0
    private(set) var isTesting = false
    private(set) var errorMessage: String?

    func result(for node: String, in group: String?) -> Result? { values[Node(name: node, group: group)] }

    func clear() {
        generation += 1
        isTesting = false
        values.removeAll()
        errorMessage = nil
    }

    func measure(_ nodes: [String], in group: String?, vpn: VPNController) async {
        guard vpn.isRunning, !isTesting, !nodes.isEmpty else { return }
        let generation = self.generation
        isTesting = true
        errorMessage = nil
        for node in nodes { values[Node(name: node, group: group)] = .testing }
        defer { if self.generation == generation { isTesting = false } }
        var pendingResult: Result? = .failed
        do {
            _ = try await vpn.testProxyLatencies(in: group, nodes: nodes) { results in
                guard self.generation == generation else { return }
                for result in results {
                    self.values[Node(name: result.node, group: group)] = result.milliseconds.map(Result.measured) ?? .failed
                }
            }
        } catch is CancellationError {
            pendingResult = nil
        } catch {
            if self.generation == generation { errorMessage = error.localizedDescription }
        }
        guard self.generation == generation else { return }
        for node in nodes {
            let key = Node(name: node, group: group)
            if values[key] == .testing { values[key] = pendingResult }
        }
    }
}

private func matchesProxySearch(_ name: String, _ search: String) -> Bool {
    let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
    return query.isEmpty || name.localizedStandardContains(query)
}
