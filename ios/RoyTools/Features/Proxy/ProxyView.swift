import SwiftUI
import UniformTypeIdentifiers

struct ProxyView: View {
    let vpn: VPNController
    @State private var showsSubscription = false
    @State private var showsFilePicker = false
    @State private var address = ""

    var body: some View {
        List {
            Section("代理状态") {
                Toggle("VPN开关", isOn: Binding(
                    get: { vpn.isEnabled },
                    set: { enabled in Task { await vpn.toggle(enabled) } }
                ))
                .disabled(!vpn.canToggle)
                LabeledContent("连接状态", value: vpn.statusText)
                if vpn.busy { ProgressView() }
                if let error = vpn.errorMessage {
                    Text(error).foregroundStyle(Color.red).font(.footnote)
                }
            }

            Section {
                Picker("代理模式", selection: Binding(
                    get: { vpn.mode },
                    set: { mode in Task { await vpn.changeMode(mode) } }
                )) {
                    Text("默认").tag(RoyToolsTunnelMode.subscription)
                    Text("规则").tag(RoyToolsTunnelMode.rule)
                    Text("音乐").tag(RoyToolsTunnelMode.music)
                    Text("AI").tag(RoyToolsTunnelMode.ai)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("代理模式")
            } footer: {
                switch vpn.mode {
                case .subscription: Text("按当前订阅或导入配置的原有规则分流。")
                case .rule: Text("使用仓库发布的分流规则和当前所选节点。")
                case .music: Text("通过当前所选节点访问RoyTools的音乐域名。")
                case .ai: Text("使用仓库发布的AI规则，未匹配流量直连。")
                }
            }
            .disabled(vpn.busy || vpn.isTransitioning)

            Section("配置") {
                LabeledContent("当前配置", value: vpn.profile?.name ?? "未导入")
                Button("导入订阅") { address = ""; showsSubscription = true }
                Button("导入配置文件") { showsFilePicker = true }
                if vpn.profile?.subscription != nil {
                    Button("更新订阅") { Task { await vpn.updateSubscription() } }
                }
            }
            .disabled(vpn.busy || vpn.isTransitioning)

            if !vpn.groups.isEmpty {
                Section("代理节点") {
                    Picker("代理组", selection: Binding(
                        get: { vpn.selectedGroup },
                        set: { group in Task { await vpn.selectGroup(group) } }
                    )) {
                        Text("自动选择").tag(nil as String?)
                        ForEach(vpn.groups) { group in Text(group.name).tag(Optional(group.name)) }
                    }
                    if !vpn.selectedNodes.isEmpty {
                        Picker("节点", selection: Binding(
                            get: { vpn.selectedNode },
                            set: { node in Task { await vpn.selectNode(node) } }
                        )) {
                            Text("自动选择").tag(nil as String?)
                            ForEach(vpn.selectedNodes, id: \.self) { node in Text(node).tag(Optional(node)) }
                        }
                    }
                }
                .disabled(vpn.busy || vpn.isTransitioning || !vpn.isRunning)
            }

            Section {
                LabeledContent("本地规则版本", value: vpn.snapshot?.version ?? "未加载")
                if let version = vpn.runtimeVersion { LabeledContent("生效版本", value: version) }
                Button("检查规则更新") { Task { await vpn.checkRules() } }
                    .disabled(vpn.busy || vpn.isTransitioning)
                if let message = vpn.ruleMessage {
                    Text(message).font(.footnote).foregroundStyle(vpn.warning ? Color.orange : Color.secondary)
                }
            } header: {
                Text("规则更新")
            } footer: {
                Text("规则、音乐和AI共用仓库规则版本。订阅的更新在“配置”中操作。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("代理")
        .alert("导入订阅", isPresented: $showsSubscription) {
            TextField("HTTPS订阅地址", text: $address)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("导入") {
                let input = address
                address = ""
                Task { await vpn.importSubscription(input) }
            }
            Button("取消", role: .cancel) { address = "" }
        }
        .fileImporter(isPresented: $showsFilePicker, allowedContentTypes: [.royToolsYAML, .plainText]) { result in
            if case .success(let url) = result { Task { await vpn.importFile(url) } }
        }
    }
}

private extension UTType {
    static let royToolsYAML = UTType(filenameExtension: "yaml") ?? .plainText
}
