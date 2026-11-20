import CryptoKit
import Foundation
import ImageIO
import Observation

enum MusicCache {
    enum Kind: String {
        case lyrics, artwork

        var directory: URL {
            URL.cachesDirectory.appending(path: "RoyToolsMusicData/\(rawValue)", directoryHint: .isDirectory)
        }
    }

    static let audioLimitKey = "music.audioLimit"
    static let preloadNextKey = "music.preloadNext"
    static let saveWhileListeningKey = "music.saveWhileListening"
    static let keepScreenOnKey = "music.keepScreenOn"
    static let artworkLimit = 8 * 1024 * 1024
    static let audioDirectory = URL.cachesDirectory.appending(path: "RoyToolsMusic", directoryHint: .isDirectory)

    static var audioLimit: Int64 {
        (UserDefaults.standard.object(forKey: audioLimitKey) as? NSNumber)?.int64Value ?? -1
    }

    static var preloadNext: Bool {
        (UserDefaults.standard.object(forKey: preloadNextKey) as? Bool) ?? true
    }

    static var saveWhileListening: Bool {
        (UserDefaults.standard.object(forKey: saveWhileListeningKey) as? Bool) ?? true
    }

    static func canStoreAudio(bytes: Int64) -> Bool {
        let budget = audioLimit
        if budget < 0 { return true }
        guard bytes > 0, bytes <= budget else { return false }
        return totalBytes(in: audioDirectory) <= budget - bytes
    }

    static func cachedData(for url: URL, kind: Kind) -> Data? {
        let file = kind.directory.appending(path: key(for: url))
        guard let data = try? Data(contentsOf: file, options: [.mappedIfSafe]), isValid(data, kind: kind) else { return nil }
        return data
    }

    static func hasCachedData(for url: URL, kind: Kind) -> Bool {
        cachedData(for: url, kind: kind) != nil
    }

    static func isValid(_ data: Data, kind: Kind) -> Bool {
        switch kind {
        case .lyrics:
            guard data.count <= 512_000, let text = String(data: data, encoding: .utf8),
                  let first = text.first(where: { !$0.isWhitespace && $0 != "\u{FEFF}" }) else { return false }
            return first != "<"
        case .artwork:
            guard !data.isEmpty, data.count <= artworkLimit,
                  let image = CGImageSourceCreateWithData(data as CFData, nil),
                  CGImageSourceGetStatus(image) == .statusComplete, CGImageSourceGetCount(image) > 0 else { return false }
            return CGImageSourceCreateThumbnailAtIndex(image, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 16,
                kCGImageSourceShouldCacheImmediately: true
            ] as CFDictionary) != nil
        }
    }

    @discardableResult
    static func save(_ data: Data, for url: URL, kind: Kind) -> Bool {
        guard isValid(data, kind: kind) else { return false }
        do {
            try FileManager.default.createDirectory(at: kind.directory, withIntermediateDirectories: true)
            try data.write(to: kind.directory.appending(path: key(for: url)), options: [.atomic])
            return true
        } catch {
            return false
        }
    }

    static func key(for url: URL) -> String {
        SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func totalBytes(in directory: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey]
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]
        )) ?? []
        return files.reduce(0) { bytes, url in
            guard let values = try? url.resourceValues(forKeys: keys),
                  values.isRegularFile == true, let size = values.fileSize else { return bytes }
            return bytes + Int64(size)
        }
    }

    static func clear(_ directory: URL) throws {
        if FileManager.default.fileExists(atPath: directory.path()) {
            try FileManager.default.removeItem(at: directory)
        }
    }

    static func sizeLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
            .replacingOccurrences(of: " ", with: "")
    }

    static func transferRateLabel(_ bytesPerSecond: Double) -> String {
        guard bytesPerSecond.isFinite, bytesPerSecond > 0 else { return "—" }
        if bytesPerSecond >= 1_000_000 {
            return (bytesPerSecond / 1_000_000).formatted(.number.precision(.fractionLength(0...2))) + "MB/s"
        }
        if bytesPerSecond >= 1_000 {
            return (bytesPerSecond / 1_000).formatted(.number.precision(.fractionLength(0...2))) + "KB/s"
        }
        return bytesPerSecond.formatted(.number.precision(.significantDigits(1...3))) + "B/s"
    }
}

@MainActor
@Observable
final class MusicDownloads {
    enum Kind: String, CaseIterable, Sendable {
        case songs = "曲目"
        case lyrics = "歌词"
        case artwork = "封面"
    }

    @Observable
    final class Transfer: Identifiable {
        let id = UUID()
        let kind: Kind
        let title: String
        @ObservationIgnored fileprivate let url: URL
        @ObservationIgnored fileprivate var automatic: Bool
        @ObservationIgnored fileprivate var measurement: (bytes: Int64, time: ContinuousClock.Instant)?
        fileprivate(set) var receivedBytes: Int64 = 0
        fileprivate(set) var expectedBytes: Int64?
        fileprivate(set) var isRunning = false
        fileprivate var bytesPerSecond = 0.0
        fileprivate var measuredAt: ContinuousClock.Instant?

        fileprivate init(kind: Kind, title: String, url: URL, automatic: Bool = false) {
            self.kind = kind
            self.title = title
            self.url = url
            self.automatic = automatic
        }

        var fraction: Double? {
            guard let expectedBytes, expectedBytes > 0 else { return nil }
            return min(1, Double(receivedBytes) / Double(expectedBytes))
        }

        var speed: Double {
            guard isRunning, let measuredAt, measuredAt.duration(to: .now) < .seconds(2) else { return 0 }
            return bytesPerSecond
        }
    }

    private struct Source: Hashable {
        let kind: Kind
        let url: URL
    }

    private struct Worker {
        let transfer: Transfer
        let control: MusicNetwork.TransferControl
        let task: Task<Void, Never>
    }

    private struct ResultCounts {
        var succeeded = 0
        var failed = 0
    }

    struct CompletedTransfer {
        let title: String
        let speed: Double
    }

    private enum DownloadError: Error { case unavailable, cacheWriteFailed }

    static let shared = MusicDownloads()
    private static let concurrencyKey = "music.downloadConcurrency"
    private let directory = URL.applicationSupportDirectory.appending(path: "MusicDownloads", directoryHint: .isDirectory)
    private(set) var transfers: [Transfer] = []
    private(set) var results: [Kind: String] = [:]
    private(set) var lastCompleted: [Kind: CompletedTransfer] = [:]
    private(set) var isPaused = false
    private(set) var concurrencyLimit = min(5, max(1, UserDefaults.standard.object(forKey: concurrencyKey) as? Int ?? 3))
    private(set) var revision = 0
    @ObservationIgnored private var workers: [UUID: Worker] = [:]
    @ObservationIgnored private var sources: Set<Source> = []
    @ObservationIgnored private var counts: [Kind: ResultCounts] = [:]

    func contains(_ kind: Kind) -> Bool {
        transfers.contains { $0.kind == kind }
    }

    func start(_ tracks: [MusicTrack], includingAssets: Bool = false) {
        let kinds = includingAssets ? Kind.allCases : [.songs]
        let items = tracks.filter { $0.audioURL != nil }.flatMap { track in
            kinds.compactMap { makeTransfer(for: track, kind: $0) }
        }
        let requested = Set(items.lazy.filter { $0.kind == .songs }.map(\.url))
        for transfer in transfers where transfer.automatic && requested.contains(transfer.url) {
            transfer.automatic = false
        }
        enqueue(items, kinds: kinds)
    }

    func savePlayingTrack(_ track: MusicTrack) {
        guard MusicCache.saveWhileListening, let url = track.audioURL, localURL(for: url) == nil,
              !sources.contains(Source(kind: .songs, url: url)) else { return }
        enqueue([Transfer(kind: .songs, title: track.title, url: url, automatic: true)], kinds: [.songs])
    }

    func cancelAutomaticSave(for source: URL) {
        guard let transfer = transfers.first(where: { $0.automatic && $0.url == source }) else { return }
        removeTransfer(transfer.id)
    }

    func promotePlaybackFile(_ file: URL, for source: URL) throws -> URL {
        let saved = try storeSong(file, for: source, retainingOriginal: false)
        if let transfer = transfers.first(where: { $0.kind == .songs && $0.url == source }) {
            workers[transfer.id]?.task.cancel()
            finish(transfer.id, succeeded: true)
        }
        return saved
    }

    func startAssets(_ kind: MusicCache.Kind, tracks: [MusicTrack]) {
        let category: Kind = kind == .lyrics ? .lyrics : .artwork
        enqueue(tracks.compactMap { makeTransfer(for: $0, kind: category) }, kinds: [category])
    }

    private func makeTransfer(for track: MusicTrack, kind: Kind) -> Transfer? {
        switch kind {
        case .songs:
            track.audioURL.map { Transfer(kind: kind, title: track.title, url: $0) }
        case .lyrics:
            track.lyricsURL.map { Transfer(kind: kind, title: track.title, url: $0) }
        case .artwork:
            track.coverURL.map { Transfer(kind: kind, title: track.album.isEmpty ? track.title : track.album, url: $0) }
        }
    }

    private func enqueue(_ candidates: [Transfer], kinds: [Kind]) {
        var seen = Set<Source>()
        let pending = candidates.filter { item in
            let source = Source(kind: item.kind, url: item.url)
            return seen.insert(source).inserted && !sources.contains(source) && !isSaved(item)
        }
        let pendingKinds = Set(pending.map(\.kind))
        for kind in kinds {
            if pendingKinds.contains(kind) {
                results[kind] = nil
            } else if !contains(kind) {
                results[kind] = candidates.contains { $0.kind == kind } ? "所选项目已下载" : "暂无可下载资源"
            }
        }
        guard !pending.isEmpty else { return }
        sources.formUnion(pending.map { Source(kind: $0.kind, url: $0.url) })
        transfers.append(contentsOf: pending)
        schedule()
    }

    func setConcurrencyLimit(_ limit: Int) {
        let limit = min(5, max(1, limit))
        guard limit != concurrencyLimit else { return }
        concurrencyLimit = limit
        UserDefaults.standard.set(limit, forKey: Self.concurrencyKey)
        schedule()
    }

    func setPaused(_ paused: Bool) {
        guard isPaused != paused, !transfers.isEmpty else { return }
        isPaused = paused
        schedule()
    }

    func prioritize(_ id: UUID) {
        guard let index = transfers.firstIndex(where: { $0.id == id && !$0.isRunning }) else { return }
        let firstWaiting = transfers.firstIndex { !$0.isRunning } ?? index
        guard index != firstWaiting else { return }
        let item = transfers.remove(at: index)
        transfers.insert(item, at: firstWaiting)
        schedule()
    }

    func removeTransfer(_ id: UUID) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        workers.removeValue(forKey: id)?.task.cancel()
        removeFromQueue(at: index)
        schedule()
    }

    func clearQueue() {
        workers.values.forEach { $0.task.cancel() }
        workers.removeAll()
        transfers.removeAll()
        sources.removeAll()
        counts.removeAll()
        results.removeAll()
        lastCompleted.removeAll()
        isPaused = false
        revision += 1
    }

    private func schedule() {
        let selected = isPaused ? [] : Array(transfers.prefix(concurrencyLimit))
        let active = Set(selected.map(\.id))
        for (id, worker) in workers where !active.contains(id) {
            setRunning(false, worker: worker)
        }
        for item in selected {
            if let worker = workers[item.id] { setRunning(true, worker: worker) }
            else { startTransfer(item) }
        }
    }

    private func setRunning(_ running: Bool, worker: Worker) {
        let item = worker.transfer
        guard item.isRunning != running else { return }
        item.isRunning = running
        item.measuredAt = nil
        item.measurement = (item.receivedBytes, .now)
        worker.control.setPaused(!running)
    }

    private func startTransfer(_ item: Transfer) {
        let id = item.id
        let control = MusicNetwork.TransferControl()
        item.isRunning = true
        item.measurement = (item.receivedBytes, .now)
        let task = Task { [weak self, weak item] in
            guard let self, let item, !Task.isCancelled else { return }
            let (updates, continuation) = AsyncStream<(MusicNetwork.TransferProgress, ContinuousClock.Instant)>
                .makeStream(bufferingPolicy: .bufferingNewest(1))
            let consumeUpdates = Task {
                for await (progress, time) in updates {
                    self.update(item, progress: progress, at: time)
                }
            }
            let progress: @Sendable (MusicNetwork.TransferProgress) -> Void = { update in
                continuation.yield((update, .now))
            }
            let succeeded: Bool
            do {
                try await download(item, control: control, progress: progress)
                succeeded = true
            } catch {
                succeeded = false
            }
            // The session reports its final byte count before resuming the download.
            continuation.finish()
            await consumeUpdates.value
            if !Task.isCancelled {
                finish(id, succeeded: succeeded, speed: item.bytesPerSecond)
            }
        }
        workers[id] = Worker(transfer: item, control: control, task: task)
    }

    private func update(_ item: Transfer, progress: MusicNetwork.TransferProgress, at time: ContinuousClock.Instant) {
        guard workers[item.id] != nil, progress.receivedBytes >= item.receivedBytes else { return }
        item.receivedBytes = progress.receivedBytes
        item.expectedBytes = progress.totalBytes
        guard item.isRunning, let measurement = item.measurement, progress.receivedBytes > measurement.bytes else { return }
        let elapsed = measurement.time.duration(to: time)
        guard elapsed > .zero else { return }
        let duration = elapsed.components
        let seconds = Double(duration.seconds) + Double(duration.attoseconds) / 1e18
        item.bytesPerSecond = Double(progress.receivedBytes - measurement.bytes) / seconds
        item.measuredAt = time
        item.measurement = (progress.receivedBytes, time)
    }

    private func finish(_ id: UUID, succeeded: Bool, speed: Double = 0) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        let item = transfers[index]
        let kind = item.kind
        workers[id] = nil
        if succeeded {
            counts[kind, default: ResultCounts()].succeeded += 1
            if speed.isFinite, speed > 0 {
                lastCompleted[kind] = CompletedTransfer(title: item.title, speed: speed)
            }
        } else {
            counts[kind, default: ResultCounts()].failed += 1
        }
        removeFromQueue(at: index)
        schedule()
    }

    private func removeFromQueue(at index: Int) {
        let item = transfers.remove(at: index)
        sources.remove(Source(kind: item.kind, url: item.url))
        if !contains(item.kind) {
            if let result = counts.removeValue(forKey: item.kind) {
                let saved = "已下载\(result.succeeded)项"
                results[item.kind] = result.failed == 0 ? saved : "\(saved)，\(result.failed)项不可用或下载失败"
            }
            if item.kind != .songs { revision += 1 }
        }
        if transfers.isEmpty { isPaused = false }
    }

    private func isSaved(_ item: Transfer) -> Bool {
        switch item.kind {
        case .songs: localURL(for: item.url) != nil
        case .lyrics: MusicCache.hasCachedData(for: item.url, kind: .lyrics)
        case .artwork: MusicCache.hasCachedData(for: item.url, kind: .artwork)
        }
    }

    func localURL(for source: URL) -> URL? {
        let url = destination(for: source)
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let size = values.fileSize, size > 0 else { return nil }
        return url
    }

    func remove(_ url: URL) throws {
        try MusicCache.clear(destination(for: url))
        revision += 1
    }

    func removeAll() throws {
        clearQueue()
        try MusicCache.clear(directory)
        revision += 1
    }

    private func destination(for url: URL) -> URL {
        directory.appending(path: MusicCache.key(for: url) + ".mp3")
    }

    private func download(_ item: Transfer, control: MusicNetwork.TransferControl,
                          progress: @escaping @Sendable (MusicNetwork.TransferProgress) -> Void) async throws {
        guard !isSaved(item) else { return }
        let source = item.url
        let network = MusicNetwork.shared
        switch item.kind {
        case .songs:
            try await downloadSong(item, control: control, progress: progress)
        case .lyrics:
            guard let data = try await network.lyrics(for: source, control: control, progress: progress), !data.isEmpty else {
                throw DownloadError.unavailable
            }
            try Task.checkCancellation()
            guard MusicCache.hasCachedData(for: source, kind: .lyrics) || MusicCache.save(data, for: source, kind: .lyrics) else {
                throw DownloadError.cacheWriteFailed
            }
        case .artwork:
            let data = try await network.data(for: URLRequest(url: source), limit: MusicCache.artworkLimit,
                                              control: control, progress: progress)
            try Task.checkCancellation()
            guard MusicCache.save(data, for: source, kind: .artwork) else { throw DownloadError.cacheWriteFailed }
        }
    }

    private func downloadSong(_ item: Transfer, control: MusicNetwork.TransferControl,
                              progress: @escaping @Sendable (MusicNetwork.TransferProgress) -> Void) async throws {
        let network = MusicNetwork.shared
        let source = item.url
        let cached = network.cachedAudioURL(for: source)
        let input: URL
        if let cached { input = cached }
        else { input = try await network.downloadAudio(for: source, control: control, progress: progress) }
        defer {
            if cached == nil { network.releaseTemporaryAudio(input) }
        }
        try Task.checkCancellation()
        if item.automatic && !MusicCache.saveWhileListening { throw CancellationError() }
        _ = try storeSong(input, for: source, retainingOriginal: cached != nil && !item.automatic)
    }

    private func storeSong(_ input: URL, for source: URL, retainingOriginal: Bool) throws -> URL {
        if let saved = localURL(for: source) {
            if !retainingOriginal, saved != input {
                try MusicCache.clear(input)
                revision += 1
            }
            return saved
        }
        var folder = directory
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var properties = URLResourceValues()
        properties.isExcludedFromBackup = true
        try folder.setResourceValues(properties)
        let target = destination(for: source)
        if retainingOriginal {
            let staging = folder.appending(path: ".\(UUID()).mp3")
            defer { try? FileManager.default.removeItem(at: staging) }
            try FileManager.default.copyItem(at: input, to: staging)
            try FileManager.default.moveItem(at: staging, to: target)
        } else {
            try FileManager.default.moveItem(at: input, to: target)
        }
        revision += 1
        return target
    }
}
