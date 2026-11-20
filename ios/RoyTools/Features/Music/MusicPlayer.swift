import AVFoundation
import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class MusicPlayer {
    static var shared: MusicPlayer { MusicWidgetPlayback.shared.player }

    enum PlaybackOrder: String {
        case repeatAll, shuffle, repeatOne

        var title: String {
            switch self {
            case .repeatAll: "列表循环"
            case .shuffle: "随机播放"
            case .repeatOne: "单曲循环"
            }
        }

        var symbol: String {
            switch self {
            case .repeatAll: "repeat"
            case .shuffle: "shuffle"
            case .repeatOne: "repeat.1"
            }
        }
    }

    struct QueueEntry: Identifiable {
        let id = UUID()
        let track: MusicTrack
        var originalPosition = 0
        var inserted = false
        var playedInsertion = false
    }

    private struct SavedSession: Codable {
        struct Entry: Codable {
            let trackID: String
            let originalPosition: Int
            let inserted: Bool
            let playedInsertion: Bool
        }

        let queue: [Entry]
        let currentIndex: Int
        let position: Double
    }

    private struct PendingSeek: Sendable {
        let id = UUID()
        let position: Double
    }

    private static let sessionKey = "music.playbackSession"
    private static let sleepKey = "music.sleepDeadline"
    private(set) var track: MusicTrack?
    private(set) var playbackID = UUID()
    private(set) var isPlaying = false
    private(set) var isPreparing = false
    private(set) var isBuffering = false
    private(set) var errorMessage: String?
    private(set) var queue: [QueueEntry] = []
    private(set) var currentIndex: Int?
    private(set) var sleepDeadline: Date?
    private(set) var queueUndoMessage: String?
    private(set) var order = PlaybackOrder(rawValue: UserDefaults.standard.string(forKey: "music.playbackOrder") ?? "")
        ?? .repeatAll

    @ObservationIgnored private var player: AVPlayer?
    @ObservationIgnored private var statusObserver: NSKeyValueObservation?
    @ObservationIgnored private var playbackObserver: NSKeyValueObservation?
    @ObservationIgnored private var itemObservers: [NSObjectProtocol] = []
    @ObservationIgnored private var progressObserver: Any?
    @ObservationIgnored private var preparation: Task<Void, Never>?
    @ObservationIgnored private var preloading: Task<Void, Never>?
    @ObservationIgnored private var preloadSource: URL?
    @ObservationIgnored private var preloadIsFetching = false
    @ObservationIgnored private var systemPlayback: MusicSystemPlayback?
    @ObservationIgnored private var pendingArtwork: (url: URL, image: UIImage)?
    @ObservationIgnored private var sleepTimer: Task<Void, Never>?
    @ObservationIgnored private var queueUndoTimer: Task<Void, Never>?
    @ObservationIgnored private var queueBeforeChange: [QueueEntry]?
    @ObservationIgnored private var didRestoreSession = false
    @ObservationIgnored private var pendingSeek: PendingSeek?
    @ObservationIgnored private var preparedAudioURL: URL?

    private func playerForPlayback() -> AVPlayer {
        if let player { return player }
        let player = AVPlayer()
        progressObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isPlaying else { return }
                self.saveSession()
            }
        }
        self.player = player
        return player
    }

    deinit {
        if let progressObserver { player?.removeTimeObserver(progressObserver) }
        itemObservers.forEach(NotificationCenter.default.removeObserver)
        preparation?.cancel()
        preloading?.cancel()
        sleepTimer?.cancel()
        queueUndoTimer?.cancel()
    }

    var position: Double {
        if let pendingSeek { return pendingSeek.position }
        guard let player, player.currentItem != nil else { return 0 }
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, seconds) : 0
    }

    var duration: Double {
        let seconds = player?.currentItem?.duration.seconds ?? 0
        if seconds.isFinite, seconds > 0 { return seconds }
        return max(0, track?.duration ?? 0)
    }

    var canPlayNext: Bool { currentIndex != nil && !queue.isEmpty }
    var canPlayPrevious: Bool {
        guard let currentIndex else { return false }
        return order != .shuffle || currentIndex > 0
    }

    var hasPendingInsertions: Bool {
        guard let currentIndex else { return false }
        return queue.dropFirst(currentIndex + 1).contains(where: \.inserted)
    }

    func play(_ index: Int, in tracks: [MusicTrack]) {
        guard tracks.indices.contains(index) else { return }
        let entries = tracks.enumerated().map { QueueEntry(track: $0.element, originalPosition: $0.offset) }
        if order == .shuffle {
            queue = [entries[index]] + entries.enumerated().filter { $0.offset != index }.map(\.element).shuffled()
            playQueuedTrack(at: 0)
        } else {
            queue = entries
            playQueuedTrack(at: index)
        }
    }

    func playCollection(_ tracks: [MusicTrack], shuffled: Bool = false) {
        guard !tracks.isEmpty else { return }
        setOrder(shuffled ? .shuffle : .repeatAll)
        play(shuffled ? Int.random(in: tracks.indices) : 0, in: tracks)
    }

    func playQueuedTrack(at index: Int) {
        guard queue.indices.contains(index) else { return }
        clearQueueUndo()
        let track = queue[index].track
        selectQueueIndex(index)
        playTrack(track)
    }

    private func selectQueueIndex(_ index: Int) {
        if queue[index].inserted {
            queue[index].inserted = false
            queue[index].playedInsertion = true
        }

        let pending = queue.filter(\.inserted)
        guard !pending.isEmpty else {
            currentIndex = index
            return
        }
        // Pending insertions follow the selected song even when it was picked out of order.
        let selectedIndex = index - queue.prefix(index).filter(\.inserted).count
        var reordered = queue.filter { !$0.inserted }
        reordered.insert(contentsOf: pending, at: selectedIndex + 1)
        queue = reordered
        currentIndex = selectedIndex
    }

    func insertNext(_ track: MusicTrack) {
        guard let currentIndex else {
            queue = [QueueEntry(track: track)]
            currentIndex = 0
            playTrack(track, autoplay: false)
            return
        }
        var index = currentIndex + 1
        while queue.indices.contains(index), queue[index].inserted { index += 1 }
        rememberQueueChange("已添加到下一首：\(track.title)")
        queue.insert(QueueEntry(track: track, inserted: true), at: index)
        queueDidChange()
    }

    func removePending(_ id: QueueEntry.ID) {
        guard let currentIndex, let index = queue.firstIndex(where: { $0.id == id }),
              index > currentIndex else { return }
        rememberQueueChange(queue[index].inserted ? "已取消此次插队" : "已删除待播曲目")
        queue.remove(at: index)
        queueDidChange()
    }

    func clearInsertions() {
        guard let currentIndex, hasPendingInsertions else { return }
        rememberQueueChange("已清空待插队")
        queue = Array(queue.prefix(currentIndex + 1)) + queue.dropFirst(currentIndex + 1).filter { !$0.inserted }
        queueDidChange()
    }

    func undoQueueChange() {
        guard let previous = queueBeforeChange else { return }
        queue = previous
        clearQueueUndo()
        queueDidChange()
    }

    private func queueDidChange() {
        saveSession()
        systemPlayback?.update()
        if isPlaying { queuePreload() }
    }

    private func rememberQueueChange(_ message: String) {
        clearQueueUndo()
        queueBeforeChange = queue
        queueUndoMessage = message
        queueUndoTimer = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(5)) }
            catch { return }
            self?.clearQueueUndo()
        }
    }

    private func clearQueueUndo() {
        queueUndoTimer?.cancel()
        queueUndoTimer = nil
        queueBeforeChange = nil
        queueUndoMessage = nil
    }

    func cycleOrder() {
        switch order {
        case .repeatAll: setOrder(.shuffle)
        case .shuffle: setOrder(.repeatOne)
        case .repeatOne: setOrder(.repeatAll)
        }
    }

    private func setOrder(_ next: PlaybackOrder) {
        if order != next, let currentIndex {
            clearQueueUndo()
            let current = queue[currentIndex]
            let pending = queue.dropFirst(currentIndex + 1).filter(\.inserted)
            let base = queue.filter { !$0.inserted && !$0.playedInsertion }
            if next == .shuffle {
                queue = [current] + pending + base.filter { $0.id != current.id }.shuffled()
                self.currentIndex = 0
            } else if order == .shuffle {
                let ordered = base.sorted { $0.originalPosition < $1.originalPosition }
                if let index = ordered.firstIndex(where: { $0.id == current.id }) {
                    queue = Array(ordered.prefix(index + 1)) + pending + ordered.dropFirst(index + 1)
                    self.currentIndex = index
                } else {
                    queue = [current] + pending + ordered
                    self.currentIndex = 0
                }
            }
        }
        order = next
        UserDefaults.standard.set(next.rawValue, forKey: "music.playbackOrder")
        queueDidChange()
    }

    func scheduleSleep(minutes: Int) {
        guard (1...1440).contains(minutes) else { return }
        setSleepDeadline(.now.addingTimeInterval(Double(minutes) * 60))
    }

    private func setSleepDeadline(_ deadline: Date) {
        cancelSleep()
        guard deadline > .now else { return }
        sleepDeadline = deadline
        UserDefaults.standard.set(deadline, forKey: Self.sleepKey)
        sleepTimer = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(max(0, deadline.timeIntervalSinceNow))) }
            catch { return }
            guard let self, !Task.isCancelled else { return }
            if self.isPlaying || self.isPreparing { self.toggle() }
            self.cancelSleep()
        }
    }

    func cancelSleep() {
        sleepTimer?.cancel()
        sleepTimer = nil
        sleepDeadline = nil
        UserDefaults.standard.removeObject(forKey: Self.sleepKey)
    }

    func toggle() {
        if isPlaying || isPreparing { pause() } else { resume() }
    }

    func pause() {
        systemPlayback?.clearInterruptedTrack()
        if isPlaying {
            player?.pause()
            RoyToolsDiagnosticLog.record(.music, "暂停播放")
            isPlaying = false
            isBuffering = false
            cancelPreload()
        } else if isPreparing {
            preparation?.cancel()
            RoyToolsDiagnosticLog.record(.music, "准备音频", outcome: .cancelled)
            preparation = nil
            isPreparing = false
        }
        saveSession()
        systemPlayback?.update()
    }

    func resume() {
        guard !isPlaying, !isPreparing, let track else { return }
        guard errorMessage == nil, let item = player?.currentItem, item.status != .failed else {
            playTrack(track, from: position)
            return
        }
        if duration > 0, position >= duration - 0.1 { seek(to: 0) }
        beginPlayback()
    }

    var systemPlaybackRate: Float {
        guard isPlaying, let player, player.timeControlStatus == .playing else { return 0 }
        return player.rate
    }

    func setSystemArtwork(_ image: UIImage, for url: URL) {
        guard track?.coverURL == url else { return }
        if let systemPlayback {
            systemPlayback.setArtwork(image, for: url)
        } else {
            pendingArtwork = (url, image)
        }
    }

    func waitForAudioPreparation() async {
        await preparation?.value
    }

    func updateSaveWhileListening() {
        guard let track, let source = track.audioURL else { return }
        if !MusicCache.saveWhileListening {
            MusicDownloads.shared.cancelAutomaticSave(for: source)
            return
        }
        guard isPlaying, let asset = player?.currentItem?.asset as? AVURLAsset, !asset.url.isFileURL else { return }
        MusicDownloads.shared.savePlayingTrack(track)
    }

    func seek(to position: Double) {
        guard position.isFinite else { return }
        let length = duration
        let target = max(0, length > 0 ? min(length, position) : position)
        let seek = PendingSeek(position: target)
        pendingSeek = seek
        saveSession(position: target)
        systemPlayback?.update()
        guard let player, let item = player.currentItem else { return }
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600)) { [weak self, weak item] _ in
            Task { @MainActor [weak self, weak item] in
                guard let self, let item, self.player?.currentItem === item,
                      self.pendingSeek?.id == seek.id, item.status != .failed, self.errorMessage == nil else { return }
                self.pendingSeek = nil
                self.saveSession()
                self.systemPlayback?.update()
            }
        }
    }

    func updateRouting() {
        let network = MusicNetwork.shared
        guard let track, let url = track.audioURL else { return }
        if isPreparing, network.isProxyReady || !network.requiresProxy(for: url) {
            playTrack(track, from: position)
            return
        }
        guard network.isProxyReady, network.requiresProxy(for: url), let asset = player?.currentItem?.asset as? AVURLAsset,
              !asset.url.isFileURL else { return }
        playTrack(track, from: position, autoplay: isPlaying)
    }

    func previous() {
        step(by: -1)
    }

    func next() { step(by: 1) }

    private func step(by offset: Int) {
        guard let index = currentIndex, !queue.isEmpty else { return }
        let destination = index + offset
        if queue.indices.contains(destination) {
            playQueuedTrack(at: destination)
        } else if order == .shuffle, offset < 0 {
            return
        } else {
            let previousEntry = queue[index]
            queue.removeAll(where: \.playedInsertion)
            guard !queue.isEmpty else { return }
            if order == .shuffle, let previous = queue.firstIndex(where: { $0.id == previousEntry.id }) {
                let hold = queue.remove(at: previous)
                queue.shuffle()
                queue.append(hold)
            } else if order == .shuffle {
                queue.shuffle()
            }
            if offset < 0 {
                playQueuedTrack(at: queue.index(before: queue.endIndex))
                return
            }
            playQueuedTrack(at: 0)
        }
    }

    private func playTrack(_ selection: MusicTrack, from startTime: Double = 0, autoplay: Bool = true,
                           prepareLocalFile: Bool = false) {
        guard let url = selection.audioURL else { return }
        if let previous = track?.audioURL { MusicDownloads.shared.cancelAutomaticSave(for: previous) }
        preparation?.cancel()
        preparation = nil
        let network = MusicNetwork.shared
        // A proxy needs a local file. Let an active fetch finish instead of starting the same download again.
        let needsLocal = prepareLocalFile || network.requiresProxy(for: url)
        let incomingPreload = (autoplay && needsLocal && preloadIsFetching && preloadSource == url) ? preloading : nil
        if incomingPreload == nil { preloading?.cancel() }
        preloading = nil
        preloadSource = nil
        preloadIsFetching = false
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        if let preparedAudioURL { MusicNetwork.shared.releaseTemporaryAudio(preparedAudioURL) }
        preparedAudioURL = nil
        pendingSeek = startTime > 0 ? PendingSeek(position: startTime) : nil
        playbackID = UUID()
        track = selection
        isPlaying = false
        isPreparing = false
        isBuffering = false
        errorMessage = nil
        itemObservers.forEach(NotificationCenter.default.removeObserver)
        itemObservers.removeAll()
        statusObserver = nil
        playbackObserver = nil
        if autoplay, systemPlayback == nil { systemPlayback = MusicSystemPlayback(player: self) }
        if let pendingArtwork, pendingArtwork.url == selection.coverURL {
            systemPlayback?.setArtwork(pendingArtwork.image, for: pendingArtwork.url)
        }
        if systemPlayback != nil || pendingArtwork?.url != selection.coverURL { pendingArtwork = nil }
        saveSession(position: startTime)
        systemPlayback?.update()
        guard autoplay else { return }

        let downloaded = MusicDownloads.shared.localURL(for: url)
        if let cached = network.cachedAudioURL(for: url), MusicCache.saveWhileListening {
            playItem(at: savedPlaybackURL(cached, for: url, fallback: downloaded))
            return
        }
        if let local = downloaded ?? network.cachedAudioURL(for: url) {
            playItem(at: local)
            return
        }
        guard needsLocal else {
            playItem(at: url)
            return
        }
        isPreparing = true
        let selectionID = playbackID
        var handoff = UIBackgroundTaskIdentifier.invalid
        func endHandoff() {
            guard handoff != .invalid else { return }
            UIApplication.shared.endBackgroundTask(handoff)
            handoff = .invalid
        }
        handoff = UIApplication.shared.beginBackgroundTask(withName: "准备播放音频") { [weak self] in
            if self?.playbackID == selectionID { self?.pause() }
            endHandoff()
        }
        preparation = Task { [weak self] in
            defer { endHandoff() }
            do {
                if let incomingPreload {
                    await withTaskCancellationHandler {
                        await incomingPreload.value
                    } onCancel: {
                        incomingPreload.cancel()
                    }
                }
                try Task.checkCancellation()
                let local = try await network.localAudioURL(for: url)
                guard let self, !Task.isCancelled else {
                    network.releaseTemporaryAudio(local)
                    return
                }
                self.preparation = nil
                self.isPreparing = false
                let playbackFile = self.savedPlaybackURL(local, for: url)
                self.preparedAudioURL = playbackFile
                self.playItem(at: playbackFile)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.preparation = nil
                self.isPreparing = false
                self.errorMessage = error.localizedDescription
                self.systemPlayback?.update()
                RoyToolsDiagnosticLog.record(.music, "准备本地播放文件", level: .error, outcome: .failed, error: error)
            }
        }
    }

    private func savedPlaybackURL(_ file: URL, for source: URL, fallback: URL? = nil) -> URL {
        guard MusicCache.saveWhileListening else { return fallback ?? file }
        do {
            return try MusicDownloads.shared.promotePlaybackFile(file, for: source)
        } catch {
            RoyToolsDiagnosticLog.record(.music, "保存正在播放的曲目", level: .warning, outcome: .failed, error: error)
            return fallback ?? file
        }
    }

    private func playItem(at url: URL) {
        let player = playerForPlayback()
        let item = AVPlayerItem(url: url)
        statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor [weak self] in
                guard let self, self.player?.currentItem === item else { return }
                if item.status == .failed { self.playbackFailed(for: item, error: item.error) }
                else if item.status == .readyToPlay { self.systemPlayback?.update() }
            }
        }
        let endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.player?.currentItem === item, self.isPlaying else { return }
                self.isPlaying = false
                self.isBuffering = false
                let followsInsertion = self.currentIndex.map { self.queue[$0].playedInsertion } ?? false
                if self.order == .repeatOne && !self.hasPendingInsertions && !followsInsertion {
                    self.seek(to: 0)
                    self.beginPlayback()
                } else {
                    self.next()
                }
            }
        }
        let failureObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            Task { @MainActor [weak self] in
                self?.playbackFailed(for: item, error: error ?? item.error)
            }
        }
        itemObservers = [endObserver, failureObserver]
        playbackObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in
                guard let self, let player = self.player, player.currentItem === item else { return }
                self.isBuffering = self.isPlaying && player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                self.systemPlayback?.update()
            }
        }
        player.replaceCurrentItem(with: item)
        if let pendingSeek { seek(to: pendingSeek.position) }
        beginPlayback()
    }

    private func playbackFailed(for item: AVPlayerItem, error: Error?) {
        guard player?.currentItem === item, errorMessage == nil else { return }
        if isPlaying, let track, let asset = item.asset as? AVURLAsset, !asset.url.isFileURL,
           let failure = error as? NSError, failure.domain == AVFoundationErrorDomain,
           failure.code == AVError.serverIncorrectlyConfigured.rawValue {
            // Some servers allow a full download but do not support AVPlayer's byte-range requests.
            playTrack(track, from: position, prepareLocalFile: true)
            return
        }
        player?.pause()
        isPlaying = false
        isBuffering = false
        cancelPreload()
        if let source = track?.audioURL { MusicDownloads.shared.cancelAutomaticSave(for: source) }
        errorMessage = "暂时无法播放这首歌"
        saveSession()
        systemPlayback?.update()
        RoyToolsDiagnosticLog.record(.music, "播放音频", level: .error, outcome: .failed, error: error)
    }

    private func beginPlayback() {
        guard let player else { return }
        defer { systemPlayback?.update() }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            player.play()
            RoyToolsDiagnosticLog.record(.music, "音频会话已激活并请求播放")
            errorMessage = nil
            isPlaying = true
            isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            updateSaveWhileListening()
            queuePreload()
        } catch {
            errorMessage = "无法开始音频播放"
            RoyToolsDiagnosticLog.record(.music, "激活音频会话", level: .error, outcome: .failed, error: error)
            isPlaying = false
            isBuffering = false
        }
    }

    private func queuePreload() {
        guard MusicCache.preloadNext, MusicCache.audioLimit != 0, let currentIndex,
              queue.indices.contains(currentIndex + 1) || (order == .repeatAll && queue.count > 1) else {
            cancelPreload()
            return
        }
        let next = queue[queue.indices.contains(currentIndex + 1) ? currentIndex + 1 : 0]
        guard (order != .repeatOne || next.inserted), let source = next.track.audioURL,
              source != track?.audioURL, MusicDownloads.shared.localURL(for: source) == nil else {
            cancelPreload()
            return
        }
        guard preloadSource != source else { return }
        cancelPreload()
        preloadSource = source
        preloading = Task { [weak self] in
            do {
                try await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled, self?.preloadSource == source else { return }
                self?.preloadIsFetching = true
                try await MusicNetwork.shared.preloadAudio(for: source)
            } catch {
                // A preload failure leaves the existing playback untouched.
            }
        }
    }

    private func cancelPreload() {
        preloading?.cancel()
        preloading = nil
        preloadSource = nil
        preloadIsFetching = false
    }

    func restoreSession(from tracks: [MusicTrack]) {
        guard !didRestoreSession else { return }
        didRestoreSession = true
        let defaults = UserDefaults.standard
        if let deadline = defaults.object(forKey: Self.sleepKey) as? Date { setSleepDeadline(deadline) }
        guard track == nil, let data = defaults.data(forKey: Self.sessionKey),
              let saved = try? JSONDecoder().decode(SavedSession.self, from: data),
              saved.queue.indices.contains(saved.currentIndex) else { return }
        let knownTracks = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        var restored: [QueueEntry] = []
        var selection: Int?
        for (index, entry) in saved.queue.enumerated() {
            guard let known = knownTracks[entry.trackID] else { continue }
            if index == saved.currentIndex { selection = restored.count }
            restored.append(QueueEntry(track: known, originalPosition: entry.originalPosition,
                                        inserted: entry.inserted, playedInsertion: entry.playedInsertion))
        }
        guard let selection else { return }
        queue = restored
        selectQueueIndex(selection)
        let position = saved.position.isFinite ? max(0, saved.position) : 0
        playTrack(restored[selection].track, from: position, autoplay: false)
    }

    func saveSession(position: Double? = nil) {
        guard let currentIndex, queue.indices.contains(currentIndex) else { return }
        let entries = queue.map {
            SavedSession.Entry(trackID: $0.track.id, originalPosition: $0.originalPosition,
                               inserted: $0.inserted, playedInsertion: $0.playedInsertion)
        }
        let saved = SavedSession(queue: entries, currentIndex: currentIndex, position: position ?? self.position)
        if let data = try? JSONEncoder().encode(saved) {
            UserDefaults.standard.set(data, forKey: Self.sessionKey)
        }
    }
}
