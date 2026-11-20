import SwiftUI

@MainActor
struct MusicSettingsView: View {
    let catalog: MusicCatalogStore
    let player: MusicPlayer

    @AppStorage(MusicCache.audioLimitKey) private var audioLimit = -1
    @AppStorage(MusicCache.preloadNextKey) private var preloadNext = true
    @AppStorage(MusicCache.saveWhileListeningKey) private var saveWhileListening = true
    @AppStorage(MusicCache.keepScreenOnKey) private var keepScreenOn = true
    @State private var downloads = MusicDownloads.shared
    @State private var audioBytes: Int64 = 0
    @State private var lyricBytes: Int64 = 0
    @State private var coverBytes: Int64 = 0
    @State private var pendingCacheAction: CacheAction?
    @State private var cleanupStatus: String?

    private enum Cleanup: String {
        case downloads = "删除所有下载与缓存"
        case audio = "清理音乐缓存"
        case lyrics = "清理歌词缓存"
        case artwork = "清理专辑封面缓存"

        var detail: String {
            switch self {
            case .downloads: "将停止当前下载，并删除本机保存的歌曲、歌词与封面。"
            case .audio: "播放时缓存的歌曲将被清理。"
            case .lyrics: "下次查看歌词时将重新获取。"
            case .artwork: "下次浏览封面时将重新获取。"
            }
        }
    }

    private enum CacheAction {
        case cleanup(Cleanup)
        case downloadLyrics
        case downloadArtwork

        var title: String {
            switch self {
            case .cleanup(let cleanup): cleanup.rawValue + "？"
            case .downloadLyrics: "下载所有歌词？"
            case .downloadArtwork: "下载所有封面？"
            }
        }

        var detail: String {
            switch self {
            case .cleanup(let cleanup): cleanup.detail
            case .downloadLyrics: "缺少的歌词会加入下载队列，下载后可离线查看。"
            case .downloadArtwork: "缺少的专辑封面会加入下载队列，下载后可离线查看。"
            }
        }

        var confirmTitle: String {
            switch self {
            case .cleanup(let cleanup): cleanup == .downloads ? "确认删除" : "确认清理"
            case .downloadLyrics, .downloadArtwork: "确认下载"
            }
        }

        var role: ButtonRole? {
            if case .cleanup = self { return .destructive }
            return nil
        }
    }

    private static let limits = [-1, 0, 256_000_000, 512_000_000, 1_000_000_000, 2_000_000_000, 4_000_000_000, 8_000_000_000]

    private var tracks: [MusicTrack] { catalog.catalog?.tracks ?? [] }

    var body: some View {
        Form {
            Section {
                Toggle("全屏播放时保持亮屏", isOn: $keepScreenOn)
                Toggle("预加载下一首", isOn: $preloadNext)
                Toggle("边听边存", isOn: $saveWhileListening)
            } header: {
                Text("播放")
            } footer: {
                Text("边听边存会把已完整缓存的当前曲目移入「已下载曲目」。")
            }
            Section("下载与缓存") {
                NavigationLink {
                    ActiveMusicDownloadsView(downloads: downloads)
                } label: {
                    let pending = downloads.transfers.count
                    if pending == 0 {
                        Text("正在下载")
                    } else {
                        LabeledContent("正在下载", value: downloads.isPaused ? "已暂停" : "\(pending)项")
                    }
                }
                NavigationLink("已下载曲目") {
                    DownloadedMusicView(tracks: tracks, player: player)
                }
                NavigationLink {
                    cacheSettings
                } label: {
                    LabeledContent("管理缓存", value: MusicCache.sizeLabel(audioBytes + lyricBytes + coverBytes))
                }
            }
            Section("曲库") {
                LabeledContent("当前版本", value: catalog.catalog?.version ?? "—")
                if catalog.isLoading {
                    ProgressView(catalog.isRefreshing ? "正在检查更新" : "正在加载曲库")
                } else {
                    Button("检查曲库更新") { Task { await catalog.checkForUpdates() } }.tint(.primary)
                    if !catalog.status.isEmpty {
                        Text(catalog.status).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .iPadContentWidth(680)
        .navigationTitle("音乐设置")
        .task { updateSizes() }
        .onChange(of: downloads.revision) { _, _ in updateSizes() }
        .onChange(of: saveWhileListening) { _, _ in player.updateSaveWhileListening() }
    }

    private var cacheSettings: some View {
        Form {
            Section {
                Picker("缓存上限", selection: $audioLimit) {
                    ForEach(Self.limits, id: \.self) { bytes in
                        Text(limitLabel(bytes)).tag(bytes)
                    }
                }
                .tint(.primary)
                LabeledContent("已缓存", value: MusicCache.sizeLabel(audioBytes))
                cleanupButton(.audio)
            } header: {
                Text("音乐缓存")
            } footer: {
                Text("完整缓存的歌曲可离线播放。达到上限后暂停新增缓存。")
            }
            Section("歌词") {
                LabeledContent("已缓存", value: MusicCache.sizeLabel(lyricBytes))
                Button("下载所有歌词") { pendingCacheAction = .downloadLyrics }
                    .tint(.primary)
                    .disabled(tracks.isEmpty)
                cleanupButton(.lyrics)
            }
            Section("专辑封面") {
                LabeledContent("已缓存", value: MusicCache.sizeLabel(coverBytes))
                Button("下载所有封面") { pendingCacheAction = .downloadArtwork }
                    .tint(.primary)
                    .disabled(tracks.isEmpty)
                cleanupButton(.artwork)
            }
            Section {
                cleanupButton(.downloads)
            } footer: {
                if let cleanupStatus { Text(cleanupStatus) }
            }
        }
        .iPadContentWidth(680)
        .navigationTitle("管理缓存")
        .navigationBarTitleDisplayMode(.inline)
        .alert(pendingCacheAction?.title ?? "", isPresented: Binding(
            get: { pendingCacheAction != nil }, set: { if !$0 { pendingCacheAction = nil } }
        ), presenting: pendingCacheAction) { action in
            Button("取消", role: .cancel) {}
            Button(action.confirmTitle, role: action.role) { perform(action) }
        } message: { action in
            Text(action.detail)
        }
    }

    private func perform(_ action: CacheAction) {
        switch action {
        case .cleanup(let cleanup): clear(cleanup)
        case .downloadLyrics: downloads.startAssets(.lyrics, tracks: tracks)
        case .downloadArtwork: downloads.startAssets(.artwork, tracks: tracks)
        }
    }

    private func cleanupButton(_ cleanup: Cleanup) -> some View {
        let active: Bool
        switch cleanup {
        case .downloads: active = false
        case .audio: active = downloads.contains(.songs)
        case .lyrics: active = downloads.contains(.lyrics)
        case .artwork: active = downloads.contains(.artwork)
        }
        return Button(cleanup.rawValue) { pendingCacheAction = .cleanup(cleanup) }
            .tint(.primary)
            .disabled(active)
    }

    private func limitLabel(_ bytes: Int) -> String {
        switch bytes {
        case -1: "无限"
        case 0: "关闭"
        case 256_000_000: "256MB"
        case 512_000_000: "512MB"
        default: "\(bytes / 1_000_000_000)GB"
        }
    }

    private func updateSizes() {
        audioBytes = MusicCache.totalBytes(in: MusicCache.audioDirectory)
        lyricBytes = MusicCache.totalBytes(in: MusicCache.Kind.lyrics.directory)
        coverBytes = MusicCache.totalBytes(in: MusicCache.Kind.artwork.directory)
    }

    private func clear(_ cleanup: Cleanup) {
        do {
            if cleanup == .downloads { try downloads.removeAll() }
            if cleanup == .downloads || cleanup == .audio { try MusicCache.clear(MusicCache.audioDirectory) }
            if cleanup == .downloads || cleanup == .lyrics { try MusicCache.clear(MusicCache.Kind.lyrics.directory) }
            if cleanup == .downloads || cleanup == .artwork {
                try MusicCache.clear(MusicCache.Kind.artwork.directory)
                MusicCover.clearMemoryCache()
            }
            cleanupStatus = "已清理"
        } catch {
            cleanupStatus = "清理失败，请重试"
        }
        updateSizes()
    }
}

@MainActor
private struct ActiveMusicDownloadsView: View {
    let downloads: MusicDownloads
    @State private var confirmsClear = false

    var body: some View {
        List {
            Section {
                Picker("同时下载数", selection: Binding(
                    get: { downloads.concurrencyLimit },
                    set: { downloads.setConcurrencyLimit($0) }
                )) {
                    ForEach(1...5, id: \.self) { limit in
                        Text("\(limit)项").tag(limit)
                    }
                }
                .tint(.primary)
                Button(downloads.isPaused ? "继续所有下载" : "暂停所有下载") {
                    downloads.setPaused(!downloads.isPaused)
                }
                .tint(.primary)
                .disabled(downloads.transfers.isEmpty)
                Button("清空所有下载") { confirmsClear = true }
                    .tint(.primary)
                    .disabled(downloads.transfers.isEmpty && !hasResults)
            }
            if downloads.transfers.isEmpty {
                Section { Text("暂无正在下载的项目").foregroundStyle(.secondary) }
            } else {
                Section("下载队列") {
                    ForEach(downloads.transfers) { transfer in
                        transferRow(transfer)
                            .swipeActions(edge: .leading, allowsFullSwipe: false) {
                                if !transfer.isRunning {
                                    Button {
                                        downloads.prioritize(transfer.id)
                                    } label: {
                                        Label("优先下载", systemImage: "arrow.up.to.line")
                                    }
                                    .tint(.accentColor)
                                }
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    downloads.removeTransfer(transfer.id)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                    }
                }
            }
            if hasResults {
                Section("下载结果") {
                    ForEach(MusicDownloads.Kind.allCases, id: \.self) { kind in
                        let result = downloads.results[kind]
                        let completed = downloads.lastCompleted[kind]
                        if result != nil || completed != nil {
                            LabeledContent(kind.rawValue) {
                                VStack(alignment: .trailing, spacing: 4) {
                                    if let result { Text(result) }
                                    if let completed {
                                        let rate = MusicCache.transferRateLabel(completed.speed)
                                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                                            Text(completed.title).lineLimit(1)
                                            Text(rate).monospacedDigit().fixedSize()
                                        }
                                        .font(.caption)
                                        .accessibilityElement(children: .ignore)
                                        .accessibilityLabel("最近完成\(completed.title)，下载速度\(rate)")
                                    }
                                }
                                .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }
            }
        }
        .iPadContentWidth(760)
        .navigationTitle("正在下载")
        .navigationBarTitleDisplayMode(.inline)
        .alert("清空所有下载？", isPresented: $confirmsClear) {
            Button("取消", role: .cancel) {}
            Button("清空下载队列", role: .destructive) { downloads.clearQueue() }
        } message: {
            Text("会取消正在下载和排队的项目。已下载的文件会保留。")
        }
    }

    private var hasResults: Bool {
        !downloads.results.isEmpty || !downloads.lastCompleted.isEmpty
    }

    private func transferRow(_ transfer: MusicDownloads.Transfer) -> some View {
        let status = downloads.isPaused ? "已暂停" : transfer.isRunning ? "正在下载" : "排队中"
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(transfer.title)
                    .lineLimit(2)
                Spacer(minLength: 0)
                Group {
                    if transfer.isRunning && !downloads.isPaused {
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Text(MusicCache.transferRateLabel(transfer.speed))
                        }
                    } else {
                        Text("—")
                    }
                }
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
                .fixedSize()
            }
            if let fraction = transfer.fraction {
                ProgressView(value: fraction)
                    .accessibilityLabel("下载进度")
            } else if transfer.isRunning && !downloads.isPaused {
                ProgressView()
                    .progressViewStyle(.linear)
                    .accessibilityLabel("正在下载，文件大小未知")
            } else {
                ProgressView(value: 0)
                    .accessibilityLabel("等待下载")
            }
            HStack {
                Text("\(transfer.kind.rawValue) · \(status)")
                Spacer(minLength: 8)
                if let expected = transfer.expectedBytes {
                    Text("\(MusicCache.sizeLabel(transfer.receivedBytes))/\(MusicCache.sizeLabel(expected))")
                } else if transfer.receivedBytes > 0 {
                    Text("已接收\(MusicCache.sizeLabel(transfer.receivedBytes))")
                }
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

@MainActor
private struct DownloadedMusicView: View {
    let tracks: [MusicTrack]
    let player: MusicPlayer
    @State private var downloads = MusicDownloads.shared
    @State private var cleanupError = false
    @State private var showsDownloadConfirmation = false

    var body: some View {
        let _ = downloads.revision
        let saved = tracks.filter { track in
            track.audioURL.flatMap { downloads.localURL(for: $0) } != nil
        }
        List {
            Section {
                Button("下载全部曲目") { showsDownloadConfirmation = true }
                    .tint(.primary)
                    .disabled(tracks.isEmpty)
            }
            if saved.isEmpty {
                Section {
                    ContentUnavailableView("暂无下载", systemImage: "arrow.down.to.line")
                        .listRowBackground(Color.clear)
                }
            } else {
                ForEach(Array(saved.enumerated()), id: \.element.id) { index, track in
                    Button {
                        player.play(index, in: saved)
                    } label: {
                        HStack(spacing: 12) {
                            MusicCover(url: track.coverURL, edge: 40)
                            VStack(alignment: .leading) {
                                Text(track.title)
                                Text(track.artist).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button("删除", role: .destructive) {
                            guard let url = track.audioURL else { return }
                            do { try downloads.remove(url) }
                            catch { cleanupError = true }
                        }
                    }
                }
            }
        }
        .iPadContentWidth(760)
        .navigationTitle("已下载曲目")
        .alert("下载全部曲目？", isPresented: $showsDownloadConfirmation) {
            Button("取消", role: .cancel) {}
            Button("确认下载") { downloads.start(tracks, includingAssets: true) }
        } message: {
            Text("将下载缺少的曲目、对应歌词和专辑封面，供离线使用。")
        }
        .alert("删除失败，请重试", isPresented: $cleanupError) {}
    }
}
