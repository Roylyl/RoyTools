import ActivityKit
import SwiftUI
import WidgetKit

@main
struct RoyToolsLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        RoyToolsIslandWidget()
    }
}

struct RoyToolsIslandWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RoyToolsActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                Label("RoyTools", systemImage: "waveform.path")
                    .font(.headline)
                if context.isStale || !context.state.sampling {
                    Text("打开RoyTools以继续采样")
                        .foregroundStyle(.secondary)
                } else {
                    HStack(spacing: 16) {
                        if context.state.speedMode != .off {
                            Label(context.state.rateText(context.state.uploadBytesPerSecond), systemImage: "arrow.up")
                            Label(context.state.rateText(context.state.downloadBytesPerSecond), systemImage: "arrow.down")
                        }
                        if context.state.showRefreshRate {
                            Text(refreshText(context.state))
                        }
                    }
                    .monospacedDigit()
                }
            }
            .padding()
        } dynamicIsland: { context in
            let state = context.state
            let live = !context.isStale && state.sampling
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    if state.speedMode != .off {
                        Label(live ? state.rateText(state.uploadBytesPerSecond) : "—", systemImage: "arrow.up")
                    } else {
                        Label("RoyTools", systemImage: "waveform.path")
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if state.speedMode != .off {
                        Label(live ? state.rateText(state.downloadBytesPerSecond) : "—", systemImage: "arrow.down")
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if live {
                        HStack {
                            if state.speedMode != .off {
                                Text(state.speedMode == .proxy ? "代理速度" : "全局速度")
                            }
                            if state.showRefreshRate { Text(refreshText(state)) }
                        }
                        .monospacedDigit()
                    } else {
                        Text("打开RoyTools以继续采样")
                            .foregroundStyle(.secondary)
                    }
                }
            } compactLeading: {
                if state.speedMode != .off {
                    compactRate("↑", value: live ? state.rateText(state.uploadBytesPerSecond, compact: true) : "—",
                                description: "上行")
                } else {
                    Image(systemName: "display")
                }
            } compactTrailing: {
                if state.speedMode != .off {
                    compactRate("↓", value: live ? state.rateText(state.downloadBytesPerSecond, compact: true) : "—",
                                description: "下行")
                } else {
                    Text(live ? refreshText(state) : "—")
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            } minimal: {
                Image(systemName: state.speedMode == .off ? "display" : "arrow.up.arrow.down")
            }
        }
    }

    private func compactRate(_ arrow: String, value: String, description: String) -> some View {
        Text(arrow + value)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .accessibilityLabel(Text(description + value))
    }

    private func refreshText(_ state: RoyToolsActivityAttributes.ContentState) -> String {
        state.refreshRate.map { "\($0) Hz" } ?? "—"
    }
}
