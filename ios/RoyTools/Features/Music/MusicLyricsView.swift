import Foundation
import SwiftUI

struct MusicLyricsView: View {
    private enum Result {
        case loading, missing, failure
        case lines([MusicLyricLine])
    }

    private struct Request: Equatable {
        let trackID: String
        let url: URL?
        let routingGeneration: Int
        let attempt: Int
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let track: MusicTrack
    let player: MusicPlayer
    @State private var result = Result.loading
    @State private var attempt = 0
    @State private var followsPlayback = true
    @State private var isManualScrolling = false
    @GestureState private var isDragging = false
    private let network = MusicNetwork.shared

    var body: some View {
        Group {
            switch result {
            case .loading:
                ProgressView("正在加载歌词")
            case .missing:
                ContentUnavailableView("暂无歌词", systemImage: "quote.bubble")
            case .failure:
                ContentUnavailableView {
                    Label("歌词加载失败", systemImage: "quote.bubble")
                } actions: {
                    Button("重试") { attempt += 1 }
                        .buttonStyle(.bordered)
                }
            case .lines(let lines):
                lyricSheet(lines)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: Request(trackID: track.id, url: track.lyricsURL,
                          routingGeneration: network.generation, attempt: attempt)) {
            await loadLyrics()
        }
    }

    private func lyricSheet(_ lines: [MusicLyricLine]) -> some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            let position = player.position
            let active = lines.lastIndex { $0.time.map { $0 <= position } == true }
            ScrollViewReader { reader in
                observeManualScroll(ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                            lyric(line, highlighted: index == active)
                                .id(index)
                        }
                        if !lines.contains(where: { $0.time != nil }) {
                            Text("无时间轴")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 12)
                })
                .onChange(of: isManualScrolling) { _, scrolling in
                    if scrolling { followsPlayback = false }
                }
                .task(id: isManualScrolling) {
                    guard !isManualScrolling, !followsPlayback else { return }
                    do { try await Task.sleep(for: .seconds(8)) }
                    catch { return }
                    if !Task.isCancelled { followsPlayback = true }
                }
                .onChange(of: active, initial: true) { _, index in
                    if followsPlayback, let index { follow(index, in: reader) }
                }
                .onChange(of: followsPlayback) { _, follows in
                    if follows, let active { follow(active, in: reader) }
                }
                .safeAreaInset(edge: .bottom, alignment: .trailing) {
                    if !followsPlayback, active != nil {
                        Button {
                            followsPlayback = true
                        } label: {
                            Label("跟随播放", systemImage: "location")
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func observeManualScroll<Content: View>(_ scroll: Content) -> some View {
        if #available(iOS 18.0, *) {
            scroll.onScrollPhaseChange { _, phase in
                isManualScrolling = phase == .tracking || phase == .interacting || phase == .decelerating
            }
        } else {
            scroll
                .simultaneousGesture(DragGesture(minimumDistance: 12).updating($isDragging) { _, active, _ in
                    active = true
                })
                .onChange(of: isDragging) { _, dragging in isManualScrolling = dragging }
        }
    }

    private func lyric(_ line: MusicLyricLine, highlighted: Bool) -> some View {
        Text(line.text)
            .font(.title3.weight(highlighted ? .bold : .medium))
            .foregroundStyle(highlighted ? .primary : .secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    private func follow(_ index: Int, in reader: ScrollViewProxy) {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
            reader.scrollTo(index, anchor: .center)
        }
    }

    private func loadLyrics() async {
        result = .loading
        followsPlayback = true
        isManualScrolling = false
        guard let address = track.lyricsURL else { result = .missing; return }
        do {
            guard let data = try await network.lyrics(for: address) else {
                if !Task.isCancelled { result = .missing }
                return
            }
            try Task.checkCancellation()
            guard let source = String(data: data, encoding: .utf8) else {
                result = .failure
                return
            }
            let parsed = MusicLyricLine.parse(source, title: track.title, artist: track.artist)
            result = parsed.isEmpty ? .missing : .lines(parsed)
        } catch {
            if !Task.isCancelled { result = .failure }
        }
    }
}

private struct MusicLyricLine {
    let text: String
    let time: Double?

    static func parse(_ source: String, title: String, artist: String) -> [Self] {
        let timestamp = #/\[(\d+):([0-5]\d)(?:\.(\d{1,3}))?\]/#
        let offsetTag = #/(?i)\[offset:\s*([+-]?\d+)\s*\]/#
        let metadata = #/(?i)^(?:\[(?:ti|ar|al|au|length|by|re|tool|ve|offset|id|#):[^\]]*\]\s*)+$/#
        let credit = #/(?i)^(?:词|曲|词曲|编曲|制作|配唱制作|监制|演唱|和声|演奏|吉他|木吉他|电吉他|低音吉他|贝斯|鼓|打击乐|键盘|钢琴|风琴|合成器|口琴|小提琴|中提琴|大提琴|弦乐|弦乐编写|长笛|萨克斯|小号|长号|指挥|编程|童声合唱|录音|录音助理|录音棚|录音环境|混音|母带|乐器\d*|词 Lyricist|曲 Composer|管弦乐编写|音乐总监|管弦乐|Program工程操作|Program制作|Program录音|混音\/母带工作室)\s*[:：]\s*\S/#
        let offset = source.firstMatch(of: offsetTag).flatMap { Double($0.1) } ?? 0
        var rows: [Self] = []
        var credits: [Self] = []
        var seenCredits: Set<String> = []
        for raw in source.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces.union(CharacterSet(charactersIn: "\u{FEFF}")))
            guard line.wholeMatch(of: metadata) == nil else { continue }
            let text = line.replacing(timestamp, with: "").trimmingCharacters(in: .whitespaces)
            guard !text.isEmpty, !isBareWebURL(text) else { continue }
            let timed = line.matches(of: timestamp).compactMap { tag -> Self? in
                guard let minutes = Double(tag.1), let seconds = Double(tag.2) else { return nil }
                let fraction = tag.3.flatMap { Double("0." + $0) } ?? 0
                let time = minutes * 60 + seconds + fraction - offset / 1000
                guard time.isFinite else { return nil }
                return Self(text: text, time: time)
            }
            if rows.isEmpty, !title.isEmpty, !artist.isEmpty, text == "\(title) - \(artist)",
               timed.allSatisfy({ ($0.time ?? .infinity) + offset / 1000 <= 3 }) { continue }
            if text.firstMatch(of: credit) != nil {
                if seenCredits.insert(text).inserted { credits.append(Self(text: text, time: nil)) }
            } else if timed.isEmpty {
                rows.append(Self(text: text, time: nil))
            } else {
                rows.append(contentsOf: timed)
            }
        }
        var merged: [Self] = []
        for line in rows.sorted(by: { ($0.time ?? .infinity) < ($1.time ?? .infinity) }) {
            if let time = line.time, let previous = merged.last, previous.time == time {
                merged[merged.count - 1] = Self(text: previous.text + "\n" + line.text, time: time)
            } else {
                merged.append(line)
            }
        }
        return credits + merged
    }

    private static func isBareWebURL(_ text: String) -> Bool {
        guard !text.contains(where: \.isWhitespace), let url = URL(string: text),
              let host = url.host, !host.isEmpty, let scheme = url.scheme?.lowercased() else { return false }
        return scheme == "http" || scheme == "https"
    }
}
