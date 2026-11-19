import SwiftUI

@MainActor
struct IslandView: View {
    @ObservedObject private var model = IslandModel.shared

    var body: some View {
        Form {
            Section {
                Toggle("总开关", systemImage: "power", isOn: Binding(
                    get: { model.enabled }, set: { model.setEnabled($0) }
                ))
                Toggle("全局速度", systemImage: "network", isOn: speedBinding(.global))
                    .disabled(!model.enabled)
                Toggle("代理速度", systemImage: "arrow.triangle.swap", isOn: speedBinding(.proxy))
                    .disabled(!model.enabled)
                Toggle("屏幕刷新率", systemImage: "display", isOn: Binding(
                    get: { model.showRefreshRate }, set: { model.setShowsRefreshRate($0) }
                ))
                .disabled(!model.enabled)
            } footer: {
                Text("全局速度和代理速度只能选一种。屏幕刷新率显示RoyTools前台画面的更新频率。退出前台后，实时活动由iOS调度。")
            }

            if model.enabled, model.speedMode != .off || model.showRefreshRate {
                Section("当前读数") {
                    if model.speedMode != .off {
                        LabeledContent("上行", value: model.reading.rateText(model.reading.uploadBytesPerSecond))
                        LabeledContent("下行", value: model.reading.rateText(model.reading.downloadBytesPerSecond))
                        if model.speedMode == .proxy, model.reading.uploadBytesPerSecond == nil {
                            Text("等待代理流量统计")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if model.showRefreshRate {
                        LabeledContent("屏幕刷新率", value: model.reading.refreshRate.map { "\($0) Hz" } ?? "—")
                    }
                    if let issue = model.activityIssue {
                        Label(issue, systemImage: "info.circle")
                            .foregroundStyle(.secondary)
                    }
                }
                .monospacedDigit()
            }
        }
        .navigationTitle("灵动岛")
    }

    private func speedBinding(_ mode: IslandModel.SpeedMode) -> Binding<Bool> {
        Binding(get: { model.speedMode == mode }, set: { model.setSpeed(mode, on: $0) })
    }
}
