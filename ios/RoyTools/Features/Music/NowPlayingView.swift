import Foundation
import SwiftUI
import UIKit

struct MiniPlayerView: View {
    let player: MusicPlayer
    let showPlayer: () -> Void
    @State private var showsQueue = false
    @State private var swipeCount = 0
    @GestureState private var metadataOffset: CGFloat = 0

    @ViewBuilder
    var body: some View {
        if #available(iOS 26.0, *) {
            MiniPlayerPlacement { controls(compact: $0) }
        } else {
            controls(compact: false)
        }
    }

    @ViewBuilder
    private func controls(compact: Bool) -> some View {
        if let track = player.track {
            HStack(spacing: compact ? 6 : 12) {
                Button(action: showPlayer) {
                    HStack(spacing: 9) {
                        MusicCover(url: track.coverURL, edge: compact ? 27 : 34)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            if !compact {
                                Text([track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: 44)
                    .offset(x: metadataOffset)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("打开正在播放")
                .accessibilityValue(track.artist)
                .highPriorityGesture(metadataSwipe)
                playControl(compact: compact)
                if !compact {
                    Button { showsQueue = true } label: {
                        Label("播放队列", systemImage: "list.bullet")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.leading, 12)
            .padding(.trailing, compact ? 2 : 12)
            .contentShape(Rectangle())
            .sensoryFeedback(.selection, trigger: swipeCount)
            .sheet(isPresented: $showsQueue) {
                MusicQueueView(player: player)
            }
        }
    }

    private func playControl(compact: Bool) -> some View {
        Button {
            player.toggle()
        } label: {
            Image(systemName: player.isPlaying || player.isPreparing ? "pause.fill" : "play.fill")
                .font(compact ? .subheadline : .title3)
                .foregroundStyle(.primary)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(player.isPreparing ? "取消准备播放" : (player.isPlaying ? "暂停" : "播放"))
    }

    private var metadataSwipe: some Gesture {
        DragGesture(minimumDistance: 12)
            .updating($metadataOffset) { drag, offset, _ in
                let dx = drag.translation.width
                guard abs(dx) > abs(drag.translation.height) * 1.3 else { return }
                offset = min(18, max(-18, dx * 0.22))
            }
            .onEnded { drag in
                let dx = drag.translation.width
                guard abs(dx) >= 36, abs(dx) > abs(drag.translation.height) * 1.3 else { return }
                if dx < 0, player.canPlayNext {
                    player.next()
                    swipeCount += 1
                } else if dx > 0, player.canPlayPrevious {
                    player.previous()
                    swipeCount += 1
                }
            }
    }
}

@available(iOS 26.0, *)
private struct MiniPlayerPlacement<Content: View>: View {
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    let content: (Bool) -> Content

    var body: some View { content(placement == .inline) }
}

struct NowPlayingView: View {
    private enum Pane: String, CaseIterable {
        case cover = "封面"
        case lyrics = "歌词"
    }

    private enum Detail: String, Identifiable {
        case queue, sleep, audio
        var id: Self { self }
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let player: MusicPlayer
    let catalog: MusicCatalogStore
    @ScaledMetric(relativeTo: .body) private var paneWidth = 176.0
    @AppStorage(MusicCache.keepScreenOnKey) private var keepScreenOn = true
    @AppStorage("music.nowPlayingPane") private var pane = Pane.cover
    @State private var scrubPosition: Double?
    @State private var detail: Detail?
    @State private var destination: MusicLibraryDestination?
    @State private var favoriteNotice = 0
    @State private var previousIdleTimer: Bool?

    private var favorite: Bool {
        guard let id = player.track?.id else { return false }
        return catalog.catalog?.favoriteTrackIDs.contains(id) == true
    }

    private var usesAdaptiveLayout: Bool {
        #if targetEnvironment(macCatalyst)
        if pane == .lyrics { return true }
        #endif
        return UIDevice.current.userInterfaceIdiom == .pad
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) { playerContent }
            .navigationTitle("正在播放")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("退出全屏播放", systemImage: "chevron.down") { dismiss() }
                        .labelStyle(.iconOnly)
                }
                if let track = player.track {
                    ToolbarItem(placement: .topBarTrailing) {
                        moreMenu(for: track)
                    }
                }
            }
            .sheet(item: $detail) { sheet in
                switch sheet {
                case .queue:
                    MusicQueueView(player: player)
                case .sleep:
                    MusicSleepTimerView(player: player)
                        .presentationDetents([.medium, .large])
                case .audio:
                    if let id = player.track?.id, let audio = catalog.catalog?.audioProperties?[id] {
                        MusicAudioInfoView(audio: audio)
                            .presentationDetents([.medium, .large])
                    }
                }
            }
            .navigationDestination(item: $destination) {
                if let current = catalog.catalog {
                    MusicLibraryDestinationView(destination: $0, catalog: current, player: player)
                }
            }
            .onChange(of: player.playbackID) { _, _ in scrubPosition = nil }
            .onAppear {
                if previousIdleTimer == nil {
                    previousIdleTimer = UIApplication.shared.isIdleTimerDisabled
                }
                updateIdleTimer()
            }
            .onDisappear {
                if let previousIdleTimer { UIApplication.shared.isIdleTimerDisabled = previousIdleTimer }
                previousIdleTimer = nil
            }
            .onChange(of: keepScreenOn) { _, _ in updateIdleTimer() }
        }
    }

    @ViewBuilder
    private var playerContent: some View {
        if let track = player.track {
            if usesAdaptiveLayout {
                GeometryReader { geometry in
                    let size = geometry.size
                    if pane == .lyrics, size.width >= 680, size.height >= 390,
                       !dynamicTypeSize.isAccessibilitySize {
                        wideLyricsLayout(for: track, availableSize: size)
                    } else {
                        playerLayout(for: track, availableSize: size)
                    }
                }
            } else {
                playerLayout(for: track)
            }
        }
    }

    private func wideLyricsLayout(for track: MusicTrack, availableSize: CGSize) -> some View {
        let inset: CGFloat = availableSize.width >= 900 ? 40 : 28
        let contentWidth = min(920, availableSize.width - inset * 2)

        return VStack(spacing: 12) {
            paneSelector.padding(.top, 6)

            GeometryReader { canvas in
                let compact = canvas.size.height < 450
                let edge = min(336, contentWidth * 0.38, max(0, canvas.size.height - 154))

                HStack(alignment: .top, spacing: compact ? 20 : 28) {
                    VStack(alignment: .leading, spacing: 14) {
                        MusicCover(url: track.coverURL, edge: nil)
                            .frame(width: edge, height: edge)
                        trackDetails(track, albumFirst: true)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(width: edge)
                    .modifier(MusicFavoriteNotice(trigger: favoriteNotice))

                    VStack(spacing: 12) {
                        MusicLyricsView(track: track, player: player, compactStatus: true)
                            .padding(.horizontal, 16)
                            .frame(height: edge)
                            .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 20))
                            .clipShape(RoundedRectangle(cornerRadius: 20))
                        controlPanel(for: track, compact: compact, bottomClearance: compact ? 12 : 18)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity)
                }
                .frame(width: contentWidth)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func playerLayout(for track: MusicTrack, availableSize: CGSize? = nil) -> some View {
        let width = availableSize?.width ?? 0
        let height = availableSize?.height ?? .infinity
        let expanded = width >= 600
        let split = width >= 820 && width > height && !dynamicTypeSize.isAccessibilitySize
        let compact = verticalSizeClass == .compact || height < 560
        let layout = split ? AnyLayout(HStackLayout(spacing: 40)) : AnyLayout(VStackLayout(spacing: 0))
        let columnWidth: CGFloat? = split ? min(408, width * 0.38) : nil
        let contentWidth: CGFloat = split ? 1060 : (expanded ? 600 : 440)
        let coverEdge: CGFloat = split ? 510 : (expanded ? 456 : 286)

        return VStack(spacing: 0) {
            paneSelector.padding(.top, 6)

            layout {
                playerPane(for: track, maximumCoverEdge: coverEdge)
                    .padding(.vertical, compact ? 6 : 16)
                    .modifier(MusicFavoriteNotice(trigger: favoriteNotice))

                controlPanel(for: track, compact: compact, bottomClearance: compact ? 12 : (split ? 30 : 40))
                    .frame(width: columnWidth)
            }
        }
        .frame(maxWidth: contentWidth)
        .padding(.horizontal, expanded ? 32 : 20)
        .frame(maxWidth: .infinity)
    }

    private func controlPanel(for track: MusicTrack, compact: Bool, bottomClearance: CGFloat) -> some View {
        VStack(spacing: compact ? 6 : 12) {
            if pane == .cover { trackDetails(track) }
            playbackError
            MusicQueueFeedback(player: player)
            timeline
            playbackControls.frame(maxWidth: 460)
        }
        .padding(.bottom, bottomClearance)
        .overlay(alignment: .bottom) {
            playbackLoadingNotice(compact: compact)
                .padding(.top, compact ? 0 : 3)
                .frame(height: bottomClearance, alignment: .top)
                .allowsHitTesting(false)
        }
    }

    private var paneSelector: some View {
        Picker("显示内容", selection: $pane) {
            ForEach(Pane.allCases, id: \.self) { option in
                Text(option.rawValue).tag(option)
            }
        }
        .pickerStyle(.segmented)
        .frame(maxWidth: paneWidth)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func playerPane(for track: MusicTrack, maximumCoverEdge: CGFloat) -> some View {
        if pane == .cover {
            GeometryReader { geometry in
                let edge = min(maximumCoverEdge, geometry.size.width, geometry.size.height)
                if edge > 0 {
                    MusicCover(url: track.coverURL, edge: nil)
                        .frame(width: edge, height: edge)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                trackDetails(track, showsThumbnail: true)
                MusicLyricsView(track: track, player: player)
            }
        }
    }

    private func trackDetails(_ track: MusicTrack, showsThumbnail: Bool = false, albumFirst: Bool = false) -> some View {
        HStack(alignment: albumFirst ? .top : .center, spacing: 12) {
            if showsThumbnail {
                MusicCover(url: track.coverURL, edge: 56)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: showsThumbnail ? 2 : 4) {
                Text(track.title)
                    .font(showsThumbnail ? .headline : .title2.bold())
                    .lineLimit(showsThumbnail ? 1 : 2)
                if albumFirst { trackAlbum(track, small: showsThumbnail) }
                Text(track.artist)
                    .font(showsThumbnail ? .subheadline : .body).foregroundStyle(.secondary).lineLimit(1)
                if !albumFirst { trackAlbum(track, small: showsThumbnail) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button { favoriteNotice += 1 } label: {
                extraLabel("Apple Music收藏", symbol: favorite ? "heart.fill" : "heart")
                    .foregroundStyle(favorite ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityValue(favorite ? "已喜欢" : "未喜欢")
        }
    }

    @ViewBuilder
    private func trackAlbum(_ track: MusicTrack, small: Bool) -> some View {
        if !track.album.isEmpty {
            Button(track.album) { destination = .album(track.albumID) }
                .font(small ? .caption : .subheadline)
                .lineLimit(1).buttonStyle(.plain).foregroundStyle(.secondary)
        }
    }

    private var isWaitingForPlayback: Bool { player.isPreparing || player.isBuffering }

    @ViewBuilder
    private func playbackLoadingNotice(compact: Bool) -> some View {
        if isWaitingForPlayback {
            HStack(spacing: 5) {
                ProgressView()
                    .controlSize(compact ? .mini : .small)
                    .accessibilityHidden(true)
                Text(player.isPreparing ? "正在准备播放" : "正在缓冲")
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .font(compact ? .caption2 : .footnote)
            .foregroundStyle(.secondary)
            .dynamicTypeSize(...(compact ? .medium : .accessibility1))
        }
    }

    @ViewBuilder
    private var playbackError: some View {
        if !isWaitingForPlayback, let error = player.errorMessage {
            HStack {
                Text(error).foregroundStyle(.secondary).lineLimit(2)
                Spacer(minLength: 8)
                Button("重试", action: player.toggle)
            }
            .font(.footnote)
        }
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = keepScreenOn || previousIdleTimer == true
    }

    private func moreMenu(for track: MusicTrack) -> some View {
        Menu {
            if catalog.catalog?.audioProperties?[track.id] != nil {
                Button("歌曲详情", systemImage: "waveform") { detail = .audio }
            }
            ShareLink(item: track.shareURL) { Label("分享", systemImage: "square.and.arrow.up") }
            Button("下载", systemImage: "arrow.down.circle") { MusicDownloads.shared.start([track]) }
            Button("定时关闭", systemImage: "timer") { detail = .sleep }
            Button("查看专辑", systemImage: "rectangle.stack") { destination = .album(track.albumID) }
            if track.artists.count == 1, let artist = track.artists.first {
                Button("查看歌手", systemImage: "person") { destination = .artist(artist) }
            } else if !track.artists.isEmpty {
                Menu("查看歌手", systemImage: "person") {
                    ForEach(track.artists, id: \.self) { artist in
                        Button(artist) { destination = .artist(artist) }
                    }
                }
            }
            if player.hasPendingInsertions {
                Button("清空待插队", systemImage: "trash", role: .destructive) { player.clearInsertions() }
            }
        } label: {
            Label("更多播放选项", systemImage: "ellipsis")
                .labelStyle(.iconOnly)
        }
    }

    private var playbackControls: some View {
        HStack(spacing: 0) {
            Button { player.cycleOrder() } label: {
                extraLabel("播放顺序", symbol: player.order.symbol)
            }
            .buttonStyle(.plain)
            .accessibilityValue(player.order.title)
            .frame(maxWidth: .infinity)

            transport("backward.end.fill", label: "上一首", action: player.previous)
                .disabled(!player.canPlayPrevious)
                .frame(maxWidth: .infinity)

            transport(player.isPlaying || player.isPreparing ? "pause.fill" : "play.fill",
                      label: player.isPreparing ? "取消准备播放" : (player.isPlaying ? "暂停" : "播放"),
                      primary: true, action: player.toggle)
                .frame(maxWidth: .infinity)

            transport("forward.end.fill", label: "下一首", action: player.next)
                .disabled(!player.canPlayNext)
                .frame(maxWidth: .infinity)

            Button { detail = .queue } label: {
                extraLabel("播放队列", symbol: "list.bullet")
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity)
        }
    }

    private func extraLabel(_ title: String, symbol: String) -> some View {
        Label(title, systemImage: symbol)
            .labelStyle(.iconOnly)
            .font(.title3)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }

    private var timeline: some View {
        let playbackID = player.playbackID
        return TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let duration = max(1, player.duration)
            let position = min(duration, scrubPosition ?? player.position)
            VStack(spacing: 6) {
                Slider(value: Binding(get: { min(duration, scrubPosition ?? player.position) }, set: { value in
                    guard player.playbackID == playbackID else { return }
                    if scrubPosition != nil { scrubPosition = value }
                    else { player.seek(to: value) }
                }),
                       in: 0...duration, onEditingChanged: { editing in
                    guard player.playbackID == playbackID else { return }
                    if editing {
                        scrubPosition = min(duration, player.position)
                    } else if let scrubPosition {
                        player.seek(to: min(duration, scrubPosition))
                        self.scrubPosition = nil
                    }
                })
                .id(playbackID)
                .disabled(player.duration <= 0)
                .accessibilityLabel("播放进度")
                HStack {
                    Text(musicTime(position))
                    Spacer()
                    Text(musicTime(player.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }

    private func transport(_ icon: String, label: String, primary: Bool = false,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(primary ? .system(size: 34, weight: .semibold) : .title3)
                .frame(minWidth: 44, minHeight: 52)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

}

private struct MusicQueueView: View {
    @Environment(\.dismiss) private var dismiss
    let player: MusicPlayer

    var body: some View {
        NavigationStack {
            ScrollViewReader { scroll in
                List {
                    ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, entry in
                        let track = entry.track
                        let pending = player.currentIndex.map { index > $0 } ?? false
                        Button {
                            if let currentIndex = player.queue.firstIndex(where: { $0.id == entry.id }) {
                                player.playQueuedTrack(at: currentIndex)
                            }
                        } label: {
                            HStack(spacing: 12) {
                                MusicCover(url: track.coverURL, edge: 38)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(track.title).lineLimit(1)
                                    Text([track.artist, track.album].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer()
                                if index == player.currentIndex {
                                    Image(systemName: "waveform").foregroundStyle(Color.accentColor)
                                        .accessibilityLabel("当前歌曲")
                                } else if entry.inserted {
                                    Text("插队待播").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .id(entry.id)
                        .swipeActions(edge: .trailing) {
                            if pending {
                                Button("删除待播", systemImage: "trash", role: .destructive) {
                                    player.removePending(entry.id)
                                }
                            }
                        }
                        .contextMenu {
                            ShareLink(item: track.shareURL) { Label("分享歌曲", systemImage: "square.and.arrow.up") }
                            Button("下载", systemImage: "arrow.down.circle") { MusicDownloads.shared.start([track]) }
                            if pending {
                                Button(entry.inserted ? "取消此次插队" : "删除待播", systemImage: "trash", role: .destructive) {
                                    player.removePending(entry.id)
                                }
                            }
                        }
                    }
                }
                .onAppear {
                    if let index = player.currentIndex, player.queue.indices.contains(index) {
                        scroll.scrollTo(player.queue[index].id, anchor: .center)
                    }
                }
            }
            .navigationTitle("播放队列")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) { MusicQueueFeedback(player: player) }
            .toolbar {
                if player.hasPendingInsertions {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("清空待插队", systemImage: "trash") { player.clearInsertions() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationContentInteraction(.scrolls)
    }
}

private struct MusicSleepTimerView: View {
    @Environment(\.dismiss) private var dismiss
    let player: MusicPlayer
    @State private var custom = ""

    private var customMinutes: Int? {
        guard let value = Int(custom), (1...1440).contains(value) else { return nil }
        return value
    }

    var body: some View {
        NavigationStack {
            Form {
                if let deadline = player.sleepDeadline {
                    Section("进行中") {
                        LabeledContent("剩余时间") { Text(deadline, style: .timer).monospacedDigit() }
                        Button("取消定时", role: .destructive) { player.cancelSleep() }
                    }
                }
                Section {
                    ForEach([30, 60, 120, 180], id: \.self) { minutes in
                        Button(minutes < 60 ? "\(minutes)分钟" : "\(minutes / 60)小时") { schedule(minutes) }
                    }
                } header: {
                    Text("定时暂停")
                } footer: {
                    Text("到时暂停播放，保留当前进度。")
                }
                Section("自定义") {
                    TextField("分钟（1–1440）", text: $custom).keyboardType(.numberPad)
                    Button("开启定时") {
                        if let customMinutes { schedule(customMinutes) }
                    }
                    .disabled(customMinutes == nil)
                }
            }
            .navigationTitle("定时关闭")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
    }

    private func schedule(_ minutes: Int) {
        player.scheduleSleep(minutes: minutes)
        dismiss()
    }
}

private struct MusicAudioInfoView: View {
    @Environment(\.dismiss) private var dismiss
    let audio: MusicCatalog.AudioProperties

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let codec = audio.codec {
                        LabeledContent("编码", value: codec.uppercased())
                    }
                    if let rate = audio.sampleRate {
                        let value = (Double(rate) / 1000).formatted(.number.precision(.fractionLength(0...1)))
                        LabeledContent("采样率", value: "\(value)kHz")
                    }
                    if let depth = audio.bitDepth { LabeledContent("位深", value: "\(depth)bit") }
                    if let channels = audio.channels { LabeledContent("声道", value: "\(channels)") }
                    if let bitrate = audio.bitRate { LabeledContent("比特率", value: "\(bitrate / 1000)kbps") }
                    if let size = audio.fileSize { LabeledContent("文件大小", value: size.formatted(.byteCount(style: .file))) }
                } footer: {
                    Text("参数描述音源文件，蓝牙传输规格取决于设备。")
                }
            }
            .navigationTitle("音频信息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
    }
}
