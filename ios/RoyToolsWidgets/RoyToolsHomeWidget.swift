import SwiftUI
import UIKit
import WidgetKit

struct RoyToolsHomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: RoyToolsWidgetData.kind, provider: HomeWidgetProvider()) { entry in
            HomeWidgetCard(entry: entry)
        }
        .configurationDisplayName("RoyTools")
        .description("查看代理节点和今日用量，切换代理和控制音乐播放。")
        .supportedFamilies([.systemMedium])
    }
}

private struct HomeWidgetEntry: TimelineEntry {
    let date: Date
    var proxy = WidgetProxyState()
    var music = WidgetMusicState()
    var usage: DailyProxyTraffic?
}

private struct HomeWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> HomeWidgetEntry {
        HomeWidgetEntry(date: Date(), proxy: WidgetProxyState(node: "当前代理节点"), music: .init(title: "音乐"))
    }

    func getSnapshot(in context: Context, completion: @escaping (HomeWidgetEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : entry(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HomeWidgetEntry>) -> Void) {
        let now = Date()
        let current = entry(at: now)
        let refresh = now.addingTimeInterval(20 * 60)
        let midnight = Calendar.autoupdatingCurrent.dateInterval(of: .day, for: now)?.end ?? refresh
        let entries = midnight <= refresh ? [current, HomeWidgetEntry(date: midnight, proxy: current.proxy,
                                                                        music: current.music, usage: current.usage)] : [current]
        completion(Timeline(entries: entries, policy: .after(min(refresh, midnight))))
    }

    private func entry(at date: Date) -> HomeWidgetEntry {
        let usage: DailyProxyTraffic?
        do { usage = try RoyToolsTunnelStorage.loadDailyProxyTraffic() ?? .init(date: date) }
        catch { usage = nil }
        return HomeWidgetEntry(date: date, proxy: RoyToolsWidgetData.loadProxy(), music: RoyToolsWidgetData.loadMusic(), usage: usage)
    }
}

private struct HomeWidgetCard: View {
    let entry: HomeWidgetEntry

    private var usage: (wifi: UInt64, cellular: UInt64, unclassified: UInt64)? { entry.usage?.breakdown(on: entry.date) }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            header
            modeSelector
            Divider().accessibilityHidden(true)
            musicRow
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(for: .widget) { Color(uiColor: .secondarySystemBackground) }
        .widgetURL(RoyToolsWidgetData.link("proxy"))
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Link(destination: RoyToolsWidgetData.link("proxy/nodes")) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 6, height: 6)
                            .accessibilityHidden(true)
                        Text(entry.proxy.connected ? (entry.proxy.node ?? "正在读取节点…") : "代理未开启")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .accessibilityHint(entry.proxy.profile.map { Text(verbatim: $0) } ?? Text("打开代理节点"))

                ViewThatFits(in: .horizontal) {
                    usageRow(showsNames: true)
                    usageRow(showsNames: false).minimumScaleFactor(0.8)
                }
                .font(.caption2)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(intent: WidgetProxyPowerIntent(enabled: !entry.proxy.connected)) {
                Image(systemName: "power")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(entry.proxy.connected ? Color.white : Color.primary)
                    .frame(width: 36, height: 36)
                    .background(entry.proxy.connected ? Color.accentColor : Color.primary.opacity(0.07), in: Circle())
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(entry.proxy.connected ? "关闭代理" : "开启代理")
        }
    }

    private var statusColor: Color {
        if entry.proxy.ready { return .green }
        return entry.proxy.connected ? .orange : Color(uiColor: .tertiaryLabel)
    }

    private func usageRow(showsNames: Bool) -> some View {
        HStack(spacing: 10) {
            usageLabel("Wi-Fi", icon: "wifi", value: usage?.wifi, showsName: showsNames)
            usageLabel("移动数据", icon: "cellularbars", value: usage?.cellular, showsName: showsNames)
            if let unclassified = usage?.unclassified, unclassified > 0 {
                usageLabel("未分类", icon: "arrow.up.arrow.down", value: unclassified, showsName: showsNames)
            }
        }
    }

    private func usageLabel(_ name: String, icon: String, value: UInt64?, showsName: Bool) -> some View {
        let formatted = value.map(Self.bytes)
        return HStack(spacing: 3) {
            Image(systemName: icon)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            if showsName { Text(name).foregroundStyle(.secondary) }
            Text(formatted ?? "—").monospacedDigit()
        }
        .accessibilityLabel("今日\(name)代理用量\(formatted ?? "未知")")
    }

    private var modeSelector: some View {
        HStack(spacing: 2) {
            modeShortcut("默认", .subscription)
            modeShortcut("规则", .rule)
            modeShortcut("音乐", .music)
            modeShortcut("AI", .ai)
            modeShortcut("全局", .global)
            modeShortcut("直连", .direct)
        }
        .padding(2)
        .background(Color.primary.opacity(0.055), in: Capsule())
    }

    @ViewBuilder
    private func modeShortcut(_ title: LocalizedStringKey, _ mode: RoyToolsTunnelMode) -> some View {
        let selected = entry.proxy.mode == mode
        let label = Text(title)
            .font(.caption.weight(selected ? .semibold : .medium))
            .foregroundStyle(selected && entry.proxy.ready ? Color.accentColor : Color.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .frame(maxWidth: .infinity, minHeight: 25)
            .background(selected ? Color(uiColor: .systemBackground) : Color.clear, in: Capsule())
            .contentShape(Rectangle())
            .accessibilityAddTraits(selected ? .isSelected : [])
        if entry.proxy.ready {
            Button(intent: WidgetProxyModeIntent(mode)) { label }
                .buttonStyle(.plain)
        } else {
            label.opacity(selected ? 0.9 : 0.65)
        }
    }

    private var musicRow: some View {
        HStack(spacing: 2) {
            Link(destination: RoyToolsWidgetData.link("music")) {
                HStack(spacing: 8) {
                    artwork
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.music.title ?? "未播放音乐")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                        if let artist = entry.music.artist {
                            Text(artist).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .accessibilityHint("打开播放页")
            .padding(.trailing, 4)
            mediaButton("上一首", icon: "backward.end.fill", action: .previous, enabled: entry.music.canPrevious)
            mediaButton(entry.music.playing ? "暂停" : "播放", icon: entry.music.playing ? "pause.fill" : "play.fill",
                        action: entry.music.playing ? .pause : .play, enabled: true, primary: true)
            mediaButton("下一首", icon: "forward.end.fill", action: .next, enabled: entry.music.canNext)
        }
    }

    @ViewBuilder private var artwork: some View {
        if let data = entry.music.artwork, let image = UIImage(data: data) {
            Image(uiImage: image).resizable().scaledToFill()
                .frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityHidden(true)
        } else {
            Image(systemName: "music.note").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                .frame(width: 36, height: 36)
                .background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private func mediaButton(_ title: String, icon: String, action: WidgetPlaybackAction, enabled: Bool,
                             primary: Bool = false) -> some View {
        let symbol = Image(systemName: icon)
            .font(.system(size: primary ? 15 : 13, weight: .semibold))
            .foregroundStyle(primary ? Color.white : Color.primary)
            .frame(width: 34, height: 34)
            .background(primary ? Color.accentColor : Color.clear, in: Circle())
            .frame(width: 36, height: 36)
            .contentShape(Rectangle())
            .accessibilityLabel(title)
        if enabled {
            Button(intent: WidgetPlaybackIntent(action)) { symbol }.buttonStyle(.plain)
        } else {
            symbol.opacity(0.45).accessibilityHidden(true)
        }
    }

    private static func bytes(_ value: UInt64) -> String {
        guard value > 0 else { return "0B" }
        let formatter = ByteCountFormatter()
        formatter.countStyle = .decimal
        formatter.allowedUnits = [.useBytes, .useKB, .useMB, .useGB, .useTB]
        formatter.includesActualByteCount = false
        return formatter.string(fromByteCount: Int64(clamping: value)).replacingOccurrences(of: " ", with: "")
    }
}
