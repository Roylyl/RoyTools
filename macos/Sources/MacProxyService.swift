#if ROYTOOLS_MAC_PROXY
import Foundation

@MainActor
final class MacProxyService {
    private struct Reply: Decodable {
        let id: Int
        let reply: RoyToolsTunnelReply
    }

    @MainActor
    private final class RunningProcess {
        let child: MacProxyProcess
        var received: Set<String> = []
        var replies: [Int: RoyToolsTunnelReply] = [:]
        var pending: Set<Int> = []
        var finished = false

        init(_ child: MacProxyProcess) {
            self.child = child
            Task {
                for await line in child.lines() {
                    if line.hasPrefix("ROYTOOLS_") {
                        received.insert(line)
                    } else if let decoded = try? JSONDecoder().decode(Reply.self, from: Data(line.utf8)),
                              pending.contains(decoded.id) {
                        replies[decoded.id] = decoded.reply
                    }
                }
                await Task.detached { child.waitForExit() }.value
                finished = true
            }
        }
    }

    private let port = 17897
    private var requestID = 0
    private var core: RunningProcess?
    private var settings: RunningProcess?
    private let manager = FileManager.default

    private var home: URL { URL.applicationSupportDirectory.appending(path: "RoyToolsMacProxy", directoryHint: .isDirectory) }
    private var stateFile: URL { home.appending(path: "system-proxy.json") }
    var hasRecovery: Bool { manager.fileExists(atPath: stateFile.path) }
    var isRunning: Bool { core?.finished == false }

    func start(_ configuration: RoyToolsTunnelConfiguration) async throws -> RoyToolsTunnelReply {
        try manager.createDirectory(at: home, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        if hasRecovery { try await restore() }
        try prepareGeoData()
        let input = home.appending(path: "active-config.json")
        try JSONEncoder().encode(configuration).write(to: input, options: .atomic)
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: input.path)
        defer { try? manager.removeItem(at: input) }
        let process = try launch("RoyToolsMacCore", ["-config", input.path, "-data-dir", home.path, "-port", String(port), "-parent-pipe"])
        core = process
        do {
            try await waitFor("ROYTOOLS_PROXY_PORT=\(port)", from: process, timeout: 50, message: "本机Mihomo启动失败，请检查配置或端口。")
            let reply = try await request("status", timeout: 5)
            let writer = try launch("RoyToolsMacSystemProxy", ["--port", String(port), "--state", stateFile.path])
            settings = writer
            try await waitFor("ROYTOOLS_SYSTEM_PROXY=on", from: writer, timeout: 120, message: "系统代理设置失败，请检查macOS授权。")
            return reply
        } catch {
            // Keep recovery data if the system settings have already changed.
            settings?.child.stop()
            process.child.stop()
            throw error
        }
    }

    func stop() async throws {
        if let settings, !settings.finished {
            settings.child.stop()
            try await waitFor("ROYTOOLS_SYSTEM_PROXY=off", from: settings, timeout: 120, message: "系统代理恢复失败，请重试。")
            self.settings = nil
        } else if hasRecovery {
            try await restore()
        }
        core?.child.stop()
        let end = Date().addingTimeInterval(5)
        while let core, !core.finished, Date() < end {
            try await Task.sleep(for: .milliseconds(50))
        }
        core = nil
        settings = nil
    }

    func restore() async throws {
        guard hasRecovery else { return }
        let process = try launch("RoyToolsMacSystemProxy", ["--restore", "--state", stateFile.path])
        defer { process.child.stop() }
        try await waitFor("ROYTOOLS_SYSTEM_PROXY=off", from: process, timeout: 120, message: "无法恢复系统代理，请允许macOS授权。")
    }

    func reload(_ configuration: RoyToolsTunnelConfiguration) async throws -> RoyToolsTunnelReply {
        try await request("reload", payload: JSONEncoder().encode(configuration), timeout: 90)
    }

    func request(_ kind: String, payload: Data? = nil, timeout: TimeInterval) async throws -> RoyToolsTunnelReply {
        guard let core, !core.finished else { throw VPNError.sessionUnavailable }
        requestID += 1
        let id = requestID
        var command: [String: Any] = ["id": id, "kind": kind]
        if let payload { command["payload"] = try JSONSerialization.jsonObject(with: payload) }
        core.pending.insert(id)
        defer {
            core.pending.remove(id)
            core.replies.removeValue(forKey: id)
        }
        var data = try JSONSerialization.data(withJSONObject: command)
        data.append(0x0a)
        try await core.child.send(data)
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if let reply = core.replies[id] {
                guard reply.ok else { throw VPNError.providerRejected(reply.message) }
                return reply
            }
            guard !core.finished else { throw VPNError.sessionUnavailable }
            try await Task.sleep(for: .milliseconds(25))
        }
        throw VPNError.messageTimeout
    }

    private func launch(_ name: String, _ arguments: [String]) throws -> RunningProcess {
        let path = Bundle.main.bundleURL.appending(path: "Contents/Helpers/\(name)")
        return try RunningProcess(MacProxyProcess(executable: path, arguments: arguments))
    }

    private func waitFor(_ marker: String, from process: RunningProcess, timeout: TimeInterval, message: String) async throws {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if process.received.contains(marker) { return }
            if process.finished { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw VPNError.providerRejected(message)
    }

    private func prepareGeoData() throws {
        guard let resources = Bundle.main.resourceURL?.appending(path: "GeoData", directoryHint: .isDirectory) else { return }
        for name in ["geoip.metadb", "geoip.dat", "geosite.dat", "ASN.mmdb"] {
            let source = resources.appending(path: name)
            let destination = home.appending(path: name)
            if manager.fileExists(atPath: source.path), !manager.fileExists(atPath: destination.path) {
                try manager.copyItem(at: source, to: destination)
            }
        }
    }
}
#endif
