import SwiftUI
import UIKit

struct AboutRoyToolsView: View {
    let vpn: VPNController
    @State private var showsClearConfirmation = false
    @State private var exportedLog: ExportedLog?
    @State private var issue: String?
    @State private var busy = false

    var body: some View {
        List {
            Section("版本") {
                LabeledContent("应用版本", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                LabeledContent("代理核心", value: "Mihomo")
                if let version = vpn.snapshot?.version {
                    LabeledContent("规则版本", value: version)
                }
            }
            Section("运行日志") {
                ForEach(RoyToolsDiagnosticLog.Module.allCases) { module in
                    HStack {
                        NavigationLink {
                            DiagnosticLogView(module: module)
                        } label: {
                            Text(module.title)
                        }
                        Button {
                            Task { await exportLog(module) }
                        } label: {
                            Label("导出\(module.title)", systemImage: "square.and.arrow.up")
                                .labelStyle(.iconOnly)
                        }
                        .buttonStyle(.borderless)
                        .disabled(busy)
                    }
                }
                Button("清空所有日志", systemImage: "trash", role: .destructive) { showsClearConfirmation = true }
                    .disabled(busy)
            }
            Section("开源") {
                Link(destination: URL(string: "https://github.com/Roylyl/RoyTools")!) {
                    Label("开源项目", systemImage: "chevron.left.forwardslash.chevron.right")
                }
                NavigationLink {
                    OpenSourceLicensesView()
                } label: {
                    Label("开源许可", systemImage: "doc.text")
                }
            }
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth()
        .navigationTitle("关于RoyTools")
        .sheet(item: $exportedLog) { DiagnosticShareSheet(file: $0.url) }
        .alert("清空所有日志？", isPresented: $showsClearConfirmation) {
            Button("取消", role: .cancel) {}
            Button("清空", role: .destructive) { Task { await clearLogs() } }
        } message: {
            Text("清空音乐、代理和应用日志。新的运行日志会继续记录。")
        }
        .alert("日志操作失败", isPresented: Binding(get: { issue != nil }, set: { if !$0 { issue = nil } })) {
            Button("好", role: .cancel) {}
        } message: {
            Text(issue ?? "请稍后重试。")
        }
    }

    private func exportLog(_ module: RoyToolsDiagnosticLog.Module) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do { exportedLog = ExportedLog(url: try await RoyToolsDiagnosticLog.export(module)) }
        catch { issue = "无法创建日志文件。" }
    }

    private func clearLogs() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do { try await RoyToolsDiagnosticLog.clearAll() }
        catch { issue = "无法清空日志。" }
    }
}

private struct DiagnosticLogView: View {
    let module: RoyToolsDiagnosticLog.Module
    @State private var entries: [RoyToolsDiagnosticLog.Entry]?
    @State private var exportedLog: ExportedLog?
    @State private var issue: String?
    @State private var exporting = false

    var body: some View {
        Group {
            if let entries {
                List(entries) { entry in
                    Text(verbatim: entry.line)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                }
                .listStyle(.plain)
                .iPadContentWidth(980)
                .refreshable { await load() }
                .overlay {
                    if entries.isEmpty {
                        ContentUnavailableView("暂无日志", systemImage: "doc.text")
                            .allowsHitTesting(false)
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(module.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("导出日志", systemImage: "square.and.arrow.up") {
                    Task { await exportLog() }
                }
                .labelStyle(.iconOnly)
                .disabled(exporting)
            }
        }
        .task { await load() }
        .sheet(item: $exportedLog) { DiagnosticShareSheet(file: $0.url) }
        .alert("日志操作失败", isPresented: Binding(get: { issue != nil }, set: { if !$0 { issue = nil } })) {
            Button("好", role: .cancel) {}
        } message: {
            Text(issue ?? "请稍后重试。")
        }
    }

    private func load() async {
        do { entries = try await RoyToolsDiagnosticLog.read(module) }
        catch {
            entries = []
            issue = "无法读取日志。"
        }
    }

    private func exportLog() async {
        guard !exporting else { return }
        exporting = true
        defer { exporting = false }
        do { exportedLog = ExportedLog(url: try await RoyToolsDiagnosticLog.export(module)) }
        catch { issue = "无法创建日志文件。" }
    }
}

private struct ExportedLog: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct DiagnosticShareSheet: UIViewControllerRepresentable {
    let file: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [file], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
