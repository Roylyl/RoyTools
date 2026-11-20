import Darwin
import Foundation
import Security
import SystemConfiguration

private typealias Settings = [String: Any]

private struct ProxyError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

private struct Options {
    let state: URL
    let port: UInt16?

    init(_ arguments: [String]) throws {
        let port: UInt16?
        let statePath: String
        switch arguments.count {
        case 3 where arguments[0] == "--restore" && arguments[1] == "--state":
            port = nil
            statePath = arguments[2]
        case 4 where arguments[0] == "--port" && arguments[2] == "--state":
            guard let value = UInt16(arguments[1]), value != 0 else {
                throw ProxyError("Invalid proxy port")
            }
            port = value
            statePath = arguments[3]
        default:
            throw ProxyError("Invalid arguments")
        }
        guard statePath.hasPrefix("/") else { throw ProxyError("Invalid recovery file path") }
        self.port = port
        state = URL(fileURLWithPath: statePath)
    }
}

private struct ProxyProtocol {
    let enabled: String
    let host: String
    let port: String
    let user: String

    var keys: [String] { [enabled, host, port, user] }
}

private let protocols = [
    ProxyProtocol(enabled: kSCPropNetProxiesHTTPEnable as String, host: kSCPropNetProxiesHTTPProxy as String,
                  port: kSCPropNetProxiesHTTPPort as String, user: "HTTPUser"),
    ProxyProtocol(enabled: kSCPropNetProxiesHTTPSEnable as String, host: kSCPropNetProxiesHTTPSProxy as String,
                  port: kSCPropNetProxiesHTTPSPort as String, user: "HTTPSUser"),
    ProxyProtocol(enabled: kSCPropNetProxiesSOCKSEnable as String, host: kSCPropNetProxiesSOCKSProxy as String,
                  port: kSCPropNetProxiesSOCKSPort as String, user: "SOCKSUser"),
]
private let pac = kSCPropNetProxiesProxyAutoConfigEnable as String
private let discovery = kSCPropNetProxiesProxyAutoDiscoveryEnable as String
private let pacSources = [
    kSCPropNetProxiesProxyAutoConfigURLString as String,
    kSCPropNetProxiesProxyAutoConfigJavaScript as String,
]
private let managedKeys = protocols.flatMap(\.keys) + [pac, discovery]

private func desiredSettings(port: UInt16) -> Settings {
    var values: Settings = [pac: 0, discovery: 0]
    for proxy in protocols {
        values[proxy.enabled] = 1
        values[proxy.host] = "127.0.0.1"
        values[proxy.port] = Int(port)
    }
    return values
}

private func sameValue(_ first: Any?, _ second: Any?) -> Bool {
    guard let first else { return second == nil }
    guard let second else { return false }
    return (first as? NSObject)?.isEqual(second) == true
}

private struct Snapshot {
    let serviceID: String
    let port: UInt16
    let hadProtocol: Bool
    let original: Settings

    static func read(from file: URL) throws -> Snapshot {
        let fileHandle = try FileHandle(forReadingFrom: file)
        defer { try? fileHandle.close() }
        let limit = 1 << 20
        guard let data = try fileHandle.read(upToCount: limit + 1), data.count <= limit,
              let stored = try PropertyListSerialization.propertyList(from: data, format: nil) as? Settings,
              stored["version"] as? Int == 1,
              let serviceID = stored["serviceID"] as? String, !serviceID.isEmpty,
              let portValue = stored["port"] as? Int, let port = UInt16(exactly: portValue), port > 0,
              let hadProtocol = stored["hadProtocol"] as? Bool,
              let original = stored["original"] as? Settings else {
            throw ProxyError("Invalid proxy recovery data")
        }
        return Snapshot(serviceID: serviceID, port: port, hadProtocol: hadProtocol, original: original)
    }

    func write(to file: URL) throws {
        let stored: Settings = [
            "version": 1, "serviceID": serviceID, "port": Int(port),
            "hadProtocol": hadProtocol, "original": original,
        ]
        let data = try PropertyListSerialization.data(fromPropertyList: stored, format: .binary, options: 0)
        guard data.count <= 1 << 20 else { throw ProxyError("Proxy recovery data is too large") }
        try data.write(to: file, options: .atomic)
    }

    func restoredSettings(_ current: Settings) -> Settings? {
        let applied = desiredSettings(port: port)
        var restored = current
        for proxy in protocols {
            // The address, port and switch constitute one endpoint. Keep a replacement endpoint intact.
            guard proxy.keys.allSatisfy({ sameValue(current[$0], applied[$0]) }) else { continue }
            for key in proxy.keys { restored[key] = original[key] }
        }
        if sameValue(current[pac], applied[pac]),
           pacSources.allSatisfy({ sameValue(current[$0], original[$0]) }) {
            restored[pac] = original[pac]
        }
        if sameValue(current[discovery], applied[discovery]) { restored[discovery] = original[discovery] }
        let unchanged = NSDictionary(dictionary: restored).isEqual(to: current)
        return unchanged ? nil : restored
    }
}

private func withAuthorization<Result>(_ operation: (SCPreferences) throws -> Result) throws -> Result {
    var reference: AuthorizationRef?
    guard AuthorizationCreate(nil, nil, [], &reference) == errAuthorizationSuccess,
          let reference else { throw ProxyError("Could not create network authorization") }
    defer { AuthorizationFree(reference, []) }
    guard let preferences = SCPreferencesCreateWithAuthorization(nil, "RoyTools" as CFString, nil, reference) else {
        throw ProxyError("Could not open authorized network preferences")
    }
    return try operation(preferences)
}

private func withNetworkLock(_ preferences: SCPreferences, _ operation: () throws -> Void) throws {
    SCPreferencesSynchronize(preferences)
    guard SCPreferencesLock(preferences, true) else {
        throw ProxyError("Could not lock network preferences. Authorization may be required.")
    }
    defer { SCPreferencesUnlock(preferences) }
    try operation()
}

private func withStateLock(_ state: URL, _ operation: () throws -> Void) throws {
    try FileManager.default.createDirectory(at: state.deletingLastPathComponent(), withIntermediateDirectories: true)
    let descriptor = open(state.path + ".lock", O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0o600)
    guard descriptor >= 0 else { throw ProxyError("Could not open the proxy session lock") }
    defer { close(descriptor) }
    guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
        throw ProxyError("Another proxy session is already active")
    }
    try operation()
}

private func currentNetworkService(_ preferences: SCPreferences) -> (String, SCNetworkService)? {
    guard let store = SCDynamicStoreCreate(nil, "RoyTools" as CFString, nil, nil) else { return nil }
    for family in ["IPv4", "IPv6"] {
        let key = "State:/Network/Global/\(family)" as CFString
        guard let network = SCDynamicStoreCopyValue(store, key) as? Settings,
              let id = network[kSCDynamicStorePropNetPrimaryService as String] as? String,
              let service = SCNetworkServiceCopy(preferences, id as CFString),
              SCNetworkServiceGetEnabled(service) else { continue }
        return (id, service)
    }
    return nil
}

private func saveNetworkChanges(_ preferences: SCPreferences) throws {
    guard SCPreferencesCommitChanges(preferences) else {
        throw ProxyError("Could not save system proxy settings")
    }
    guard SCPreferencesApplyChanges(preferences) else {
        throw ProxyError("Could not apply system proxy settings")
    }
}

private func activate(port: UInt16, state: URL, preferences: SCPreferences) throws {
    try withNetworkLock(preferences) {
        guard let (id, service) = currentNetworkService(preferences) else {
            throw ProxyError("Could not find the active network service")
        }
        let protocolType = kSCNetworkProtocolTypeProxies
        let existing = SCNetworkServiceCopyProtocol(service, protocolType)
        let configuration = existing.flatMap { SCNetworkProtocolGetConfiguration($0) as? Settings } ?? [:]
        let fields = managedKeys + pacSources
        let previous = configuration.filter { fields.contains($0.key) }
        try Snapshot(serviceID: id, port: port, hadProtocol: existing != nil, original: previous).write(to: state)

        var protocolReference = existing
        if protocolReference == nil, SCNetworkServiceAddProtocolType(service, protocolType) {
            protocolReference = SCNetworkServiceCopyProtocol(service, protocolType)
        }
        guard let protocolReference else { throw ProxyError("Could not prepare network proxy settings") }
        var updated = configuration
        let applied = desiredSettings(port: port)
        for key in managedKeys { updated[key] = applied[key] }
        guard SCNetworkProtocolSetConfiguration(protocolReference, updated as CFDictionary) else {
            throw ProxyError("Could not set system proxy settings")
        }
        try saveNetworkChanges(preferences)
    }
}

private func restore(state: URL, preferences: SCPreferences) throws {
    let snapshot = try Snapshot.read(from: state)
    try withNetworkLock(preferences) {
        guard let service = SCNetworkServiceCopy(preferences, snapshot.serviceID as CFString) else {
            throw ProxyError("The network service for recovery is unavailable")
        }
        let protocolType = kSCNetworkProtocolTypeProxies
        if let proxy = SCNetworkServiceCopyProtocol(service, protocolType) {
            let current = SCNetworkProtocolGetConfiguration(proxy) as? Settings ?? [:]
            if let restored = snapshot.restoredSettings(current) {
                let success = !snapshot.hadProtocol && restored.isEmpty
                    ? SCNetworkServiceRemoveProtocolType(service, protocolType)
                    : SCNetworkProtocolSetConfiguration(proxy, restored as CFDictionary)
                guard success else { throw ProxyError("Could not restore system proxy settings") }
                try saveNetworkChanges(preferences)
                return
            }
        }
        guard SCPreferencesApplyChanges(preferences) else {
            throw ProxyError("Could not apply recovered network settings")
        }
    }
    try FileManager.default.removeItem(at: state)
}

private func report(_ status: String) throws {
    try FileHandle.standardOutput.write(contentsOf: Data("ROYTOOLS_SYSTEM_PROXY=\(status)\n".utf8))
}

private func run(_ options: Options) throws {
    try withStateLock(options.state) {
        if let port = options.port {
            guard !FileManager.default.fileExists(atPath: options.state.path) else {
                throw ProxyError("A prior proxy session requires recovery")
            }
            try withAuthorization { preferences in
                do {
                    try activate(port: port, state: options.state, preferences: preferences)
                    try report("on")
                    while let data = try FileHandle.standardInput.read(upToCount: 4096), !data.isEmpty {}
                } catch {
                    if FileManager.default.fileExists(atPath: options.state.path) {
                        try? restore(state: options.state, preferences: preferences)
                    }
                    throw error
                }
                try restore(state: options.state, preferences: preferences)
            }
        } else if FileManager.default.fileExists(atPath: options.state.path) {
            try withAuthorization { try restore(state: options.state, preferences: $0) }
        }
        try report("off")
    }
}

umask(0o077)
signal(SIGPIPE, SIG_IGN)
do {
    try run(Options(Array(CommandLine.arguments.dropFirst())))
} catch {
    let message = (error as? ProxyError)?.message ?? "System proxy operation failed"
    FileHandle.standardError.write(Data("RoyToolsMacSystemProxy: \(message)\n".utf8))
    exit(1)
}
