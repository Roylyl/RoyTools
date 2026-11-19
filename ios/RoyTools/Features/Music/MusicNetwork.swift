import CryptoKit
import Foundation
import Network
import Observation

@MainActor
@Observable
final class MusicNetwork {
    static let shared = MusicNetwork()

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

    private static let maxAudioBytes = 128 * 1024 * 1024
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

    func data(for request: URLRequest, limit: Int) async throws -> Data {
        guard let url = request.url else { throw RequestError.invalidResponse }
        return try await Self.read(request, using: session(for: url), limit: limit)
    }

    func localAudioURL(for url: URL) async throws -> URL {
        let directory = URL.cachesDirectory.appending(path: "RoyToolsMusic", directoryHint: .isDirectory)
        let key = SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let destination = directory.appending(path: key + ".mp3")
        if Self.isCachedAudio(destination) { return destination }

        let session = try session(for: url)
        let (downloaded, response) = try await session.download(for: URLRequest(url: url))
        defer { try? FileManager.default.removeItem(at: downloaded) }
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw RequestError.invalidResponse
        }
        guard Self.isCachedAudio(downloaded) else { throw RequestError.sizeLimit }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if Self.isCachedAudio(destination) { return destination }
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

    private nonisolated static func read(_ request: URLRequest, using session: URLSession, limit: Int) async throws -> Data {
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw RequestError.invalidResponse
        }
        guard http.expectedContentLength <= Int64(limit) else { throw RequestError.sizeLimit }
        var data = Data()
        data.reserveCapacity(max(0, Int(http.expectedContentLength)))
        for try await byte in bytes {
            guard data.count < limit else { throw RequestError.sizeLimit }
            data.append(byte)
        }
        return data
    }

    private static func isCachedAudio(_ url: URL) -> Bool {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, let length = values.fileSize else { return false }
        return (1...maxAudioBytes).contains(length)
    }

    private nonisolated static func matches(_ url: URL, domains: [String]) -> Bool {
        guard url.scheme?.lowercased() == "https", let domain = url.host?.lowercased() else { return false }
        let host = domain.hasSuffix(".") ? String(domain.dropLast()) : domain
        return domains.contains { host == $0 || host.hasSuffix("." + $0) }
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
