import Darwin
import Foundation
import Network
import NetworkExtension
import RoyToolsCore

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private static let mtu: Int32 = 1500
    private static let packetCapacity = 65536
    private static let geoDataFiles = ["geoip.metadb", "geoip.dat", "geosite.dat", "ASN.mmdb"]
    private let control = DispatchQueue(label: "com.roylyl.tools.vpn.control")
    private let output = DispatchQueue(label: "com.roylyl.tools.vpn.output", qos: .userInitiated)
    private let latencyTests = DispatchQueue(label: "com.roylyl.tools.vpn.latency", qos: .userInitiated, attributes: .concurrent)
    private let packetSession = PacketSession()

    // Lifecycle callbacks and app messages are serialized on control.
    private var sessionID: UUID?
    private var active: RoyToolsTunnelConfiguration?
    private var inputPending = false
    private var startReply: ((Error?) -> Void)?
    private var reloadReply: ((Data?) -> Void)?
    private var pendingLatencyTests = 0
    private var trafficTimer: DispatchSourceTimer?
    private var trafficPath: NWPathMonitor?
    private var previousPayloadSample: ProxyPayloadSample?
    private var dailyProxyTraffic: DailyProxyTraffic?
    private var dailyTrafficNeedsSave = false
    private var lastDailyTrafficWrite: TimeInterval = 0

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        control.async {
            guard self.sessionID == nil else {
                completionHandler(ProviderError.busy)
                return
            }
            self.startReply = completionHandler
            RoyToolsDiagnosticLog.record(.proxy, "启动VPN隧道", source: .tunnel, outcome: .started)
            let id = UUID()
            do {
                let configuration: RoyToolsTunnelConfiguration
                if (options?[RoyToolsTunnelStorage.activateRequestedOption] as? NSNumber)?.boolValue == true {
                    configuration = try RoyToolsTunnelStorage.loadRequested()
                } else if let applied = try? RoyToolsTunnelStorage.loadApplied() {
                    configuration = applied
                } else {
                    configuration = try RoyToolsTunnelStorage.loadRequested()
                }
                let home = try RoyToolsTunnelStorage.engineDirectory()
                try Self.seedGeoData(into: home)
                let json = try JSONEncoder().encode(configuration)
                self.startTrafficPathMonitor()
                let startingPayload = Self.proxyPayloadSample()
                let result = json.withUnsafeBytes { data in
                    RTCoreStart(home.path, data.bindMemory(to: UInt8.self).baseAddress, Int32(data.count), Self.mtu)
                }
                guard result == 0 else {
                    RoyToolsDiagnosticLog.record(.proxy, "启动Mihomo核心", level: .error, source: .tunnel,
                                                 outcome: .failed, code: Int(result), mode: configuration.mode)
                    throw ProviderError.core(Self.engineError())
                }
                self.sessionID = id
                self.previousPayloadSample = startingPayload
                self.applyNetworkSettings(configuration.mode) { error in
                    guard self.sessionID == id else { return }
                    do {
                        if let error { throw error }
                        try RoyToolsTunnelStorage.saveApplied(configuration)
                        self.active = configuration
                        self.packetSession.store(id)
                        self.readInput()
                        self.startOutput(id)
                        self.startTrafficTimer(id)
                        self.publishWidgetProxy(mode: configuration.mode)
                        self.finishStart(nil)
                    } catch {
                        self.stopCore()
                        self.finishStart(error)
                    }
                }
            } catch {
                self.stopTrafficPathMonitor()
                self.finishStart(error)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        control.async {
            self.stopCore()
            RoyToolsDiagnosticLog.record(.proxy, "停止VPN隧道", source: .tunnel, outcome: .succeeded, code: reason.rawValue)
            self.finishStart(ProviderError.stopped)
            self.finishReload(.failure(ProviderError.stopped))
            RoyToolsDiagnosticLog.flush()
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        control.async {
            let reply: (Data?) -> Void = completionHandler ?? { _ in }
            if messageData.first == UInt8(ascii: "{"),
               messageData.count <= RoyToolsProxyLatencyRequest.maximumBytes,
               let request = try? JSONDecoder().decode(RoyToolsProxyLatencyRequest.self, from: messageData) {
                self.testLatencies(request, data: messageData, reply: reply)
                return
            }
            guard messageData.count <= 128,
                  let command = try? JSONDecoder().decode(RoyToolsTunnelCommand.self, from: messageData) else {
                reply(Self.encode(.failure(ProviderError.invalidCommand)))
                return
            }
            guard let active = self.active, let id = self.sessionID else {
                reply(Self.encode(.failure(ProviderError.stopped)))
                return
            }
            switch command {
            case .status:
                if self.reloadReply == nil {
                    reply(Self.encode(.success(self.status(active, details: true))))
                } else {
                    reply(Self.encode(.failure(ProviderError.busy)))
                }
            case .counters:
                reply(Self.encode(.success(self.status(active, details: false))))
            case .reload:
                guard self.reloadReply == nil else {
                    reply(Self.encode(.failure(ProviderError.busy)))
                    return
                }
                self.reloadReply = reply
                RoyToolsDiagnosticLog.record(.proxy, "热重载配置", source: .tunnel, outcome: .started)
                self.reload(id: id, previous: active)
            }
        }
    }

    private func testLatencies(_ request: RoyToolsProxyLatencyRequest, data: Data, reply: @escaping (Data?) -> Void) {
        guard let active, let id = sessionID else {
            reply(Self.encode(.failure(ProviderError.stopped)))
            return
        }
        guard reloadReply == nil, pendingLatencyTests < 3 else {
            reply(Self.encode(.failure(ProviderError.busy)))
            return
        }
        guard (1...RoyToolsProxyLatencyRequest.maximumNodes).contains(request.nodes.count) else {
            reply(Self.encode(.failure(ProviderError.invalidCommand)))
            return
        }
        pendingLatencyTests += 1
        latencyTests.async {
            let results = Self.measureLatencies(request, data: data)
            self.control.async {
                self.pendingLatencyTests -= 1
                guard self.sessionID == id, self.active == active, self.reloadReply == nil else {
                    reply(Self.encode(.failure(ProviderError.busy)))
                    return
                }
                reply(Self.encode(results))
            }
        }
    }

    private static func measureLatencies(_ request: RoyToolsProxyLatencyRequest, data: Data) -> Result<RoyToolsTunnelReply, Error> {
        var delays = [Int32](repeating: -1, count: request.nodes.count)
        let result = data.withUnsafeBytes { bytes in
            delays.withUnsafeMutableBufferPointer { output in
                RTCoreTestProxyLatencies(bytes.bindMemory(to: UInt8.self).baseAddress, Int32(bytes.count),
                                         output.baseAddress, Int32(output.count))
            }
        }
        guard Int(result) == request.nodes.count else { return .failure(ProviderError.invalidCommand) }
        let results = zip(request.nodes, delays).map { node, delay in
            RoyToolsProxyLatency(node: node, milliseconds: delay < 0 ? nil : Int(delay))
        }
        return .success(RoyToolsTunnelReply(ok: true, proxyLatencies: results))
    }

    private func reload(id: UUID, previous: RoyToolsTunnelConfiguration) {
        do {
            let requested = try RoyToolsTunnelStorage.loadRequested()
            if requested == previous {
                finishReload(.success(status(previous, details: true)))
                return
            }
            do {
                checkpointProxyTraffic(Self.proxyPayloadSample(), flush: true)
                try Self.reloadCore(requested)
            } catch {
                // The core stages validation and new listeners before committing a configuration.
                finishReload(.failure(error))
                return
            }
            let changesRoutes = (previous.mode == .music) != (requested.mode == .music)
            let finish: (Error?) -> Void = { error in
                guard self.sessionID == id else { return }
                do {
                    if let error { throw error }
                    try RoyToolsTunnelStorage.saveApplied(requested)
                    self.active = requested
                    self.publishWidgetProxy(mode: requested.mode)
                    self.finishReload(.success(self.status(requested, details: true)))
                } catch {
                    self.rollback(previous: previous, needsNetworkSettings: changesRoutes, id: id, originalError: error)
                }
            }
            if changesRoutes { applyNetworkSettings(requested.mode, reply: finish) }
            else { finish(nil) }
        } catch {
            finishReload(.failure(error))
        }
    }

    private func rollback(previous: RoyToolsTunnelConfiguration, needsNetworkSettings: Bool, id: UUID, originalError: Error) {
        do {
            try Self.reloadCore(previous)
        } catch {
            stopAfterFailure(error)
            return
        }
        if needsNetworkSettings {
            applyNetworkSettings(previous.mode) { error in
                guard self.sessionID == id else { return }
                if let error { self.stopAfterFailure(error) }
                else { self.finishReload(.failure(originalError)) }
            }
        } else {
            finishReload(.failure(originalError))
        }
    }

    private func applyNetworkSettings(_ mode: RoyToolsTunnelMode, reply: @escaping (Error?) -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = NSNumber(value: Self.mtu)
        let ipv4 = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.255.252"])
        if mode == .music {
            // Music uses an authenticated loopback proxy explicitly selected by RoyTools.
            ipv4.includedRoutes = [NEIPv4Route(destinationAddress: "198.18.0.0", subnetMask: "255.255.255.252")]
        } else {
            ipv4.includedRoutes = [NEIPv4Route.default()]
            let ipv6 = NEIPv6Settings(addresses: ["fd00:726f:7974::1"], networkPrefixLengths: [64])
            ipv6.includedRoutes = [NEIPv6Route.default()]
            settings.ipv6Settings = ipv6
            let dns = NEDNSSettings(servers: ["198.18.0.2"])
            dns.matchDomains = [""]
            settings.dnsSettings = dns
        }
        settings.ipv4Settings = ipv4
        setTunnelNetworkSettings(settings) { error in
            self.control.async { reply(error) }
        }
    }

    private func readInput() {
        guard !inputPending, let id = sessionID, active != nil else { return }
        inputPending = true
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self else { return }
            self.control.async {
                self.inputPending = false
                if self.sessionID == id, self.active != nil {
                    for packet in packets where (1...Self.packetCapacity).contains(packet.count) {
                        packet.withUnsafeBytes { data in
                            _ = RTCoreWritePacket(data.bindMemory(to: UInt8.self).baseAddress, Int32(data.count))
                        }
                    }
                }
                self.readInput()
            }
        }
    }

    private func startOutput(_ id: UUID) {
        output.async { [weak self] in
            guard let self else { return }
            var buffer = [UInt8](repeating: 0, count: Self.packetCapacity)
            while self.packetSession.matches(id) {
                var packets: [Data] = []
                var protocols: [NSNumber] = []
                var closed = false
                for index in 0..<32 {
                    let count = buffer.withUnsafeMutableBufferPointer { pointer in
                        RTCoreReadPacket(pointer.baseAddress, Int32(pointer.count), index == 0 ? -1 : 0)
                    }
                    if count == -1 {
                        closed = true
                        break
                    }
                    if count == 0 { break }
                    guard (1...buffer.count).contains(Int(count)) else { continue }
                    let family: Int32
                    switch buffer[0] >> 4 {
                    case 4: family = AF_INET
                    case 6: family = AF_INET6
                    default: continue
                    }
                    packets.append(Data(buffer.prefix(Int(count))))
                    protocols.append(NSNumber(value: family))
                }
                guard self.packetSession.matches(id) else { return }
                if !packets.isEmpty {
                    _ = self.packetFlow.writePackets(packets, withProtocols: protocols)
                }
                if closed {
                    self.control.async {
                        guard self.sessionID == id else { return }
                        self.stopAfterFailure(ProviderError.core("VPN数据通道已关闭"))
                    }
                    return
                }
            }
        }
    }

    private func status(_ active: RoyToolsTunnelConfiguration, details: Bool) -> RoyToolsTunnelReply {
        var reply = RoyToolsTunnelReply(
            ok: true, mode: active.mode, rulesVersion: active.rulesVersion,
            uploaded: UInt64(max(0, RTCoreUploaded())), downloaded: UInt64(max(0, RTCoreDownloaded()))
        )
        if details, let state = Self.engineState() {
            reply.musicProxyPort = state.musicProxy?.port
            reply.musicProxyUsername = state.musicProxy?.username
            reply.musicProxyPassword = state.musicProxy?.password
            reply.lanProxyPort = state.lanProxy?.port
            reply.selectedGroup = state.selectedGroup
            reply.selectedNode = state.selectedNode
            reply.groups = state.groups?.map { .init(name: $0.name, nodes: $0.nodes, selected: $0.current,
                                                    selectable: $0.selectable) }
            reply.proxyNodes = state.proxyNodes
            reply.globalChoices = state.globalChoices
        }
        return reply
    }

    private func stopCore() {
        let wasActive = active != nil
        packetSession.store(nil)
        trafficTimer?.cancel()
        trafficTimer = nil
        if sessionID != nil {
            checkpointProxyTraffic(Self.proxyPayloadSample(), flush: true)
            _ = RTCoreStop()
            // Stop wakes the reader. Wait for it to leave before a new session
            // can make another packet device available to the C bridge.
            output.sync {}
            checkpointProxyTraffic(Self.proxyPayloadSample(), flush: true)
        }
        previousPayloadSample = nil
        stopTrafficPathMonitor()
        sessionID = nil
        active = nil
        if wasActive { publishWidgetProxy(mode: nil) }
    }

    private func publishWidgetProxy(mode: RoyToolsTunnelMode?) {
        var state = RoyToolsWidgetData.loadProxy()
        state.connected = mode != nil
        if let mode {
            let engine = Self.engineState()
            state.node = mode.routingSummary(selected: engine?.selectedNode ?? engine?.selectedGroup)
            state.mode = mode
            state.ready = true
        } else {
            state.ready = false
        }
        RoyToolsWidgetData.saveProxy(state)
    }

    private func startTrafficTimer(_ id: UUID) {
        let timer = DispatchSource.makeTimerSource(queue: control)
        timer.schedule(deadline: .now(), repeating: .seconds(1), leeway: .milliseconds(150))
        timer.setEventHandler { [weak self] in
            guard let self, self.sessionID == id else { return }
            self.checkpointProxyTraffic(Self.proxyPayloadSample())
        }
        trafficTimer = timer
        timer.resume()
    }

    private func checkpointProxyTraffic(_ sample: ProxyPayloadSample, flush: Bool = false) {
        guard let previousPayloadSample else { return }
        do {
            let previousRecord = try dailyProxyTraffic ?? RoyToolsTunnelStorage.loadDailyProxyTraffic()
            let currentRecord = (previousRecord ?? DailyProxyTraffic(date: previousPayloadSample.date))
                .recording(sample, since: previousPayloadSample)
            self.previousPayloadSample = sample
            dailyProxyTraffic = currentRecord
            dailyTrafficNeedsSave = dailyTrafficNeedsSave || previousRecord != currentRecord
            let uptime = ProcessInfo.processInfo.systemUptime
            if dailyTrafficNeedsSave, flush || uptime - lastDailyTrafficWrite >= 5 {
                try RoyToolsTunnelStorage.saveDailyProxyTraffic(currentRecord)
                dailyTrafficNeedsSave = false
                lastDailyTrafficWrite = uptime
            }
        } catch {
            // Retry on the next sample without dropping in-memory counters or interrupting the VPN.
        }
    }

    private static func proxyPayloadSample() -> ProxyPayloadSample {
        var wifi: Int64 = 0
        var cellular: Int64 = 0
        RTCoreProxyUsage(&wifi, &cellular)
        return ProxyPayloadSample(uploaded: UInt64(max(0, RTCoreUploaded())),
                                  downloaded: UInt64(max(0, RTCoreDownloaded())), date: Date(),
                                  wifi: UInt64(max(0, wifi)), cellular: UInt64(max(0, cellular)))
    }

    private func startTrafficPathMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self, weak monitor] path in
            guard let self, let monitor, self.trafficPath === monitor else { return }
            let wifi = path.usesInterfaceType(.wifi)
            let cellular = path.usesInterfaceType(.cellular)
            let network: Int32 = path.status == .satisfied && wifi != cellular ? (wifi ? 1 : 2) : 0
            RTCoreSetTrafficNetwork(network)
        }
        trafficPath = monitor
        monitor.start(queue: control)
    }

    private func stopTrafficPathMonitor() {
        trafficPath?.cancel()
        trafficPath = nil
        RTCoreSetTrafficNetwork(0)
    }

    private func stopAfterFailure(_ error: Error) {
        RoyToolsDiagnosticLog.record(.proxy, "因故障关闭VPN隧道", level: .error, source: .tunnel, error: error)
        stopCore()
        finishReload(.failure(error))
        cancelTunnelWithError(error)
    }

    private func finishStart(_ error: Error?) {
        guard let reply = startReply else { return }
        startReply = nil
        if let error {
            RoyToolsDiagnosticLog.record(.proxy, "启动VPN隧道", level: .error, source: .tunnel, outcome: .failed, error: error)
        } else {
            RoyToolsDiagnosticLog.record(.proxy, "启动VPN隧道", source: .tunnel, outcome: .succeeded, mode: active?.mode)
        }
        reply(error)
    }

    private func finishReload(_ result: Result<RoyToolsTunnelReply, Error>) {
        guard let reply = reloadReply else { return }
        reloadReply = nil
        switch result {
        case .success(let value):
            RoyToolsDiagnosticLog.record(.proxy, "热重载配置", source: .tunnel, outcome: .succeeded, mode: value.mode)
        case .failure(let error):
            RoyToolsDiagnosticLog.record(.proxy, "热重载配置", level: .error, source: .tunnel, outcome: .failed, error: error)
        }
        reply(Self.encode(result))
    }

    private static func encode(_ result: Result<RoyToolsTunnelReply, Error>) -> Data? {
        let reply: RoyToolsTunnelReply
        switch result {
        case .success(let value): reply = value
        case .failure(let error): reply = RoyToolsTunnelReply(ok: false, message: error.localizedDescription)
        }
        return try? JSONEncoder().encode(reply)
    }

    private static func reloadCore(_ configuration: RoyToolsTunnelConfiguration) throws {
        let json = try JSONEncoder().encode(configuration)
        let result = json.withUnsafeBytes { data in
            RTCoreReload(data.bindMemory(to: UInt8.self).baseAddress, Int32(data.count))
        }
        guard result == 0 else {
            RoyToolsDiagnosticLog.record(.proxy, "重载Mihomo核心", level: .error, source: .tunnel,
                                         outcome: .failed, code: Int(result), mode: configuration.mode)
            throw ProviderError.core(engineError())
        }
    }

    private static func seedGeoData(into engineDirectory: URL) throws {
        let files = FileManager.default
        for name in geoDataFiles {
            let destination = engineDirectory.appendingPathComponent(name)
            if files.fileExists(atPath: destination.path) { continue }
            guard let resourceDirectory = Bundle.main.resourceURL else {
                throw ProviderError.missingGeoData(name)
            }
            let source = resourceDirectory.appendingPathComponent("GeoData", isDirectory: true).appendingPathComponent(name)
            guard files.fileExists(atPath: source.path) else { throw ProviderError.missingGeoData(name) }
            let temporary = engineDirectory.appendingPathComponent(".\(UUID().uuidString).geo.tmp")
            defer { try? files.removeItem(at: temporary) }
            try files.copyItem(at: source, to: temporary)
            try files.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: temporary.path
            )
            try files.moveItem(at: temporary, to: destination)
        }
    }

    private static func engineError() -> String {
        var buffer = [CChar](repeating: 0, count: 1024)
        let count = buffer.withUnsafeMutableBufferPointer {
            RTCoreLastError($0.baseAddress, Int32($0.count))
        }
        guard count > 0 else { return "代理核心启动或配置加载失败" }
        return String(decoding: buffer.prefix(min(Int(count), buffer.count - 1)).map(UInt8.init(bitPattern:)), as: UTF8.self)
    }

    private static func engineState() -> EngineState? {
        // There is no node or credential payload in counters-only messages.
        let length = Int(RTCoreState(nil, 0))
        guard (1...(512 * 1024)).contains(length) else { return nil }
        var buffer = [CChar](repeating: 0, count: length + 1)
        let count = buffer.withUnsafeMutableBufferPointer {
            RTCoreState($0.baseAddress, Int32($0.count))
        }
        guard count > 0, Int(count) < buffer.count else { return nil }
        let data = Data(buffer.prefix(Int(count)).map(UInt8.init(bitPattern:)))
        return try? JSONDecoder().decode(EngineState.self, from: data)
    }

    private struct EngineState: Decodable {
        struct Group: Decodable {
            let name: String
            let nodes: [String]
            let current: String?
            let selectable: Bool
        }

        struct MusicProxy: Decodable {
            let port: Int
            let username: String
            let password: String
        }

        struct LANProxy: Decodable {
            let port: Int
        }

        let groups: [Group]?
        let proxyNodes: [String]?
        let globalChoices: [String]?
        let musicProxy: MusicProxy?
        let lanProxy: LANProxy?
        let selectedGroup: String?
        let selectedNode: String?
    }

    private enum ProviderError: LocalizedError {
        case busy, stopped, invalidCommand, core(String), missingGeoData(String)

        var errorDescription: String? {
            switch self {
            case .busy: "VPN正在处理配置，请稍后再试"
            case .stopped: "VPN未运行"
            case .invalidCommand: "无效的VPN请求"
            case .core(let message): message
            case .missingGeoData(let name): "VPN扩展缺少地理规则资源\(name)"
            }
        }
    }
}

private final class PacketSession {
    private let lock = NSLock()
    private var id: UUID?

    func store(_ id: UUID?) {
        lock.lock()
        self.id = id
        lock.unlock()
    }

    func matches(_ id: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return self.id == id
    }
}
