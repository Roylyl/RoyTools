import Foundation
import Network
import Observation

@MainActor
@Observable
final class MusicNetwork {
    static let shared = MusicNetwork()

    struct TransferProgress: Sendable {
        let receivedBytes: Int64
        let totalBytes: Int64?
    }

    final class TransferControl: @unchecked Sendable {
        private let lock = NSLock()
        private var task: URLSessionTask?
        private var paused = false
        private var cancelled = false

        func setPaused(_ value: Bool) {
            lock.withLock {
                guard paused != value, !cancelled else { return }
                paused = value
                if value { task?.suspend() }
                else { task?.resume() }
            }
        }

        fileprivate func run<Value: Sendable>(
            _ makeTask: (CheckedContinuation<Value, Error>) -> URLSessionTask
        ) async throws -> Value {
            defer { lock.withLock { task = nil } }
            return try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    let transfer = makeTask(continuation)
                    lock.withLock {
                        task = transfer
                        if cancelled { transfer.cancel() }
                        else if !paused { transfer.resume() }
                    }
                }
            } onCancel: {
                self.lock.withLock {
                    self.cancelled = true
                    self.task?.cancel()
                }
            }
        }
    }

    private struct Proxy: Equatable {
        let domains: [String]
        let port: Int
        let username: String
        let password: String
    }

    private enum Route: Equatable {
        case system
        case waiting([String])
        case proxy(Proxy)

        var domains: [String] {
            switch self {
            case .system: []
            case .waiting(let domains): domains
            case .proxy(let proxy): proxy.domains
            }
        }
    }

    private enum RequestError: LocalizedError {
        case proxyUnavailable, invalidResponse, sizeLimit

        var errorDescription: String? {
            switch self {
            case .proxyUnavailable: "音乐代理尚未就绪，请稍后重试"
            case .invalidResponse: "无法读取音乐资源"
            case .sizeLimit: "音乐资源超过大小限制"
            }
        }
    }

    private nonisolated static let maxAudioBytes = 128 * 1024 * 1024
    private nonisolated static let temporaryAudioDirectory = URL.temporaryDirectory.appending(
        path: "RoyToolsLiveAudio", directoryHint: .isDirectory
    )
    @ObservationIgnored private var route = Route.system
    @ObservationIgnored private var proxySession: URLSession?
    private(set) var generation = 0
    private(set) var isProxyReady = false

    private init() {}

    func reset() {
        select(.system)
    }

    func expectProxy(domains: [String]) {
        select(.waiting(domains))
    }

    func configure(domains: [String], port: Int?, username: String?, password: String?) {
        guard let port, (1...65535).contains(port),
              let username, !username.isEmpty,
              let password, !password.isEmpty else {
            expectProxy(domains: domains)
            return
        }
        select(.proxy(Proxy(domains: domains, port: port, username: username, password: password)))
    }

    func requiresProxy(for url: URL) -> Bool {
        Self.matches(url, domains: route.domains)
    }

    func data(for request: URLRequest, limit: Int, control: TransferControl? = nil,
              progress: (@Sendable (TransferProgress) -> Void)? = nil) async throws -> Data {
        guard let url = request.url else { throw RequestError.invalidResponse }
        guard let data = try await Self.read(request, using: session(for: url), limit: limit,
                                             control: control, progress: progress) else {
            throw RequestError.invalidResponse
        }
        return data
    }

    func lyrics(for url: URL, control: TransferControl? = nil,
                progress: (@Sendable (TransferProgress) -> Void)? = nil) async throws -> Data? {
        if let saved = MusicCache.cachedData(for: url, kind: .lyrics) { return saved }
        let request = URLRequest(url: url, timeoutInterval: 15)
        guard let data = try await Self.read(request, using: session(for: url), limit: 512_000,
                                             allowsMissing: true, control: control, progress: progress) else {
            return nil
        }
        try Task.checkCancellation()
        guard MusicCache.isValid(data, kind: .lyrics) else {
            throw RequestError.invalidResponse
        }
        MusicCache.save(data, for: url, kind: .lyrics)
        return data
    }

    func cachedAudioURL(for url: URL) -> URL? {
        let destination = Self.audioCacheURL(for: url)
        return Self.audioSize(destination) == nil ? nil : destination
    }

    func localAudioURL(for url: URL) async throws -> URL {
        if let cached = cachedAudioURL(for: url) { return cached }
        let downloaded = try await downloadAudio(for: url)
        do {
            try Task.checkCancellation()
            if let cached = try? storeAudioIfAllowed(downloaded, for: url) {
                releaseTemporaryAudio(downloaded)
                return cached
            }
            return downloaded
        } catch {
            releaseTemporaryAudio(downloaded)
            throw error
        }
    }

    func downloadAudio(for url: URL, control: TransferControl? = nil,
                       progress: (@Sendable (TransferProgress) -> Void)? = nil) async throws -> URL {
        let session = try session(for: url)
        let request = URLRequest(url: url)
        if control != nil || progress != nil {
            let payload = try await Self.transfer(.audio, request: request, session: session,
                                                  control: control, progress: progress)
            guard case .audio(let temporary) = payload else { throw RequestError.invalidResponse }
            if Task.isCancelled {
                releaseTemporaryAudio(temporary)
                throw CancellationError()
            }
            return temporary
        }
        let (downloaded, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: downloaded) }
        try Task.checkCancellation()
        return try Self.stageAudio(downloaded, response: response)
    }

    private nonisolated static func stageAudio(_ downloaded: URL, response: URLResponse?) throws -> URL {
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw RequestError.invalidResponse
        }
        guard Self.audioSize(downloaded) != nil else { throw RequestError.sizeLimit }
        try FileManager.default.createDirectory(at: Self.temporaryAudioDirectory, withIntermediateDirectories: true)
        let temporary = Self.temporaryAudioDirectory.appending(path: UUID().uuidString + ".mp3")
        try FileManager.default.moveItem(at: downloaded, to: temporary)
        return temporary
    }

    func preloadAudio(for url: URL) async throws {
        guard MusicCache.preloadNext, MusicCache.audioLimit != 0, cachedAudioURL(for: url) == nil else { return }
        let downloaded = try await downloadAudio(for: url)
        defer { releaseTemporaryAudio(downloaded) }
        try Task.checkCancellation()
        guard MusicCache.preloadNext else { return }
        _ = try storeAudioIfAllowed(downloaded, for: url)
    }

    func releaseTemporaryAudio(_ url: URL) {
        guard url.deletingLastPathComponent().standardizedFileURL.path() ==
                Self.temporaryAudioDirectory.standardizedFileURL.path() else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private func storeAudioIfAllowed(_ downloaded: URL, for url: URL) throws -> URL? {
        if let cached = cachedAudioURL(for: url) { return cached }
        guard let size = Self.audioSize(downloaded), MusicCache.canStoreAudio(bytes: size) else { return nil }
        let destination = Self.audioCacheURL(for: url)
        try FileManager.default.createDirectory(at: MusicCache.audioDirectory, withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: downloaded, to: destination)
        return destination
    }

    private func select(_ next: Route) {
        guard next != route else { return }
        proxySession?.invalidateAndCancel()
        proxySession = nil
        route = next
        switch next {
        case .system, .waiting:
            isProxyReady = false
        case .proxy(let proxy):
            guard let port = NWEndpoint.Port(rawValue: UInt16(proxy.port)) else {
                isProxyReady = false
                generation += 1
                return
            }
            let endpoint = NWEndpoint.hostPort(host: .ipv4(IPv4Address.loopback), port: port)
            var endpointConfiguration = ProxyConfiguration(httpCONNECTProxy: endpoint, tlsOptions: nil)
            endpointConfiguration.applyCredential(username: proxy.username, password: proxy.password)
            endpointConfiguration.allowFailover = false
            let configuration = URLSessionConfiguration.ephemeral
            configuration.proxyConfigurations = [endpointConfiguration]
            configuration.timeoutIntervalForRequest = 20
            configuration.timeoutIntervalForResource = 180
            proxySession = URLSession(
                configuration: configuration,
                delegate: RedirectGuard(domains: proxy.domains),
                delegateQueue: nil
            )
            isProxyReady = true
        }
        generation += 1
    }

    private func session(for url: URL) throws -> URLSession {
        guard requiresProxy(for: url) else { return .shared }
        guard let proxySession else { throw RequestError.proxyUnavailable }
        return proxySession
    }

    private nonisolated static func read(
        _ request: URLRequest, using session: URLSession, limit: Int, allowsMissing: Bool = false,
        control: TransferControl? = nil, progress: (@Sendable (TransferProgress) -> Void)? = nil
    ) async throws -> Data? {
        if control != nil || progress != nil {
            let payload = try await transfer(.data(limit: limit, allowsMissing: allowsMissing), request: request,
                                             session: session, control: control, progress: progress)
            try Task.checkCancellation()
            guard case .data(let value) = payload else { throw RequestError.invalidResponse }
            return value
        }
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw RequestError.invalidResponse }
        if allowsMissing, http.statusCode == 404 || http.statusCode == 410 { return nil }
        guard http.statusCode == 200 else { throw RequestError.invalidResponse }
        guard http.expectedContentLength <= Int64(limit) else { throw RequestError.sizeLimit }
        var data = Data()
        data.reserveCapacity(max(0, Int(http.expectedContentLength)))
        for try await byte in bytes {
            guard data.count < limit else { throw RequestError.sizeLimit }
            data.append(byte)
        }
        return data
    }

    private nonisolated static func transfer(
        _ operation: TransferDelegate.Operation, request: URLRequest, session: URLSession,
        control: TransferControl?, progress: (@Sendable (TransferProgress) -> Void)?
    ) async throws -> TransferDelegate.Payload {
        try await (control ?? TransferControl()).run { continuation in
            let delegate = TransferDelegate(operation: operation, continuation: continuation, progress: progress)
            let task: URLSessionTask
            switch operation {
            case .audio: task = session.downloadTask(with: request)
            case .data: task = session.dataTask(with: request)
            }
            task.delegate = delegate
            return task
        }
    }

    private static func audioCacheURL(for url: URL) -> URL {
        MusicCache.audioDirectory.appending(path: MusicCache.key(for: url) + ".mp3")
    }

    private nonisolated static func audioSize(_ url: URL) -> Int64? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let length = values.fileSize,
              (1...maxAudioBytes).contains(length) else { return nil }
        return Int64(length)
    }

    private nonisolated static func matches(_ url: URL, domains: [String]) -> Bool {
        guard url.scheme?.lowercased() == "https", let domain = url.host?.lowercased() else { return false }
        let host = domain.hasSuffix(".") ? String(domain.dropLast()) : domain
        return domains.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    // A raw URLSession task delivers both transfer callbacks and native suspend/resume.
    // Its callbacks are serialized by the owning session's delegate queue.
    private final class TransferDelegate: NSObject, URLSessionDataDelegate, URLSessionDownloadDelegate, @unchecked Sendable {
        enum Operation {
            case audio
            case data(limit: Int, allowsMissing: Bool)
        }

        enum Payload: Sendable {
            case audio(URL)
            case data(Data?)
        }

        private let operation: Operation
        private let continuation: CheckedContinuation<Payload, Error>
        private let progress: (@Sendable (TransferProgress) -> Void)?
        private var result: Result<Payload, Error>?
        private var data = Data()
        private var expectedSize: Int64 = -1
        private var acceptedData = false
        private var lastUpdate: ContinuousClock.Instant?

        init(operation: Operation, continuation: CheckedContinuation<Payload, Error>,
             progress: (@Sendable (TransferProgress) -> Void)?) {
            self.operation = operation
            self.continuation = continuation
            self.progress = progress
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive response: URLResponse,
                        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
            guard case .data(let limit, let allowsMissing) = operation, let http = response as? HTTPURLResponse else {
                result = .failure(RequestError.invalidResponse)
                completionHandler(.cancel)
                return
            }
            if allowsMissing, http.statusCode == 404 || http.statusCode == 410 {
                result = .success(.data(nil))
                completionHandler(.cancel)
                return
            }
            guard http.statusCode == 200 else {
                result = .failure(RequestError.invalidResponse)
                completionHandler(.cancel)
                return
            }
            guard http.expectedContentLength <= Int64(limit) else {
                result = .failure(RequestError.sizeLimit)
                completionHandler(.cancel)
                return
            }
            acceptedData = true
            let encoding = http.value(forHTTPHeaderField: "Content-Encoding")?.lowercased() ?? "identity"
            if encoding == "identity" { expectedSize = http.expectedContentLength }
            data.reserveCapacity(max(0, Int(expectedSize)))
            report(0, expected: expectedSize)
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive incoming: Data) {
            guard case .data(let limit, _) = operation, result == nil else { return }
            guard incoming.count <= limit - data.count else {
                result = .failure(RequestError.sizeLimit)
                dataTask.cancel()
                return
            }
            data.append(incoming)
            report(Int64(data.count), expected: expectedSize)
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                        totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
            guard (downloadTask.response as? HTTPURLResponse)?.statusCode == 200 else { return }
            report(totalBytesWritten, expected: totalBytesExpectedToWrite)
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                        didFinishDownloadingTo location: URL) {
            result = Result { .audio(try MusicNetwork.stageAudio(location, response: downloadTask.response)) }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            if let result {
                if case .success(.audio(let temporary)) = result {
                    if let error {
                        try? FileManager.default.removeItem(at: temporary)
                        continuation.resume(throwing: error)
                        return
                    }
                    if let size = MusicNetwork.audioSize(temporary) { report(size, expected: size, final: true) }
                }
                continuation.resume(with: result)
            } else if let error {
                continuation.resume(throwing: error)
            } else if acceptedData {
                let size = Int64(data.count)
                report(size, expected: size, final: true)
                continuation.resume(returning: .data(data))
            } else {
                continuation.resume(throwing: RequestError.invalidResponse)
            }
        }

        private func report(_ bytes: Int64, expected: Int64, final: Bool = false) {
            guard let progress else { return }
            let now = ContinuousClock.now
            if !final, let lastUpdate, now - lastUpdate < .milliseconds(250) { return }
            lastUpdate = now
            let total = expected > 0 && expected >= bytes ? expected : nil
            progress(TransferProgress(receivedBytes: bytes, totalBytes: total))
        }
    }

    private final class RedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        let domains: [String]

        init(domains: [String]) {
            self.domains = domains
        }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping @Sendable (URLRequest?) -> Void
        ) {
            let allowed = request.url.map { MusicNetwork.matches($0, domains: domains) } == true
            completionHandler(allowed ? request : nil)
        }
    }
}
