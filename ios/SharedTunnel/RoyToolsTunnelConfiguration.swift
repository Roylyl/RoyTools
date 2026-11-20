import Foundation

enum RoyToolsTunnelMode: String, Codable, CaseIterable, Sendable {
    case subscription = "default"
    case rule, music, ai, global, direct

    var usesRepositoryRules: Bool {
        self == .rule || self == .music || self == .ai
    }

    func routingSummary(selected: String?) -> String? {
        switch self {
        case .subscription: "按订阅规则"
        case .rule, .music, .ai: "按规则分流"
        case .global: selected ?? "自动选择节点"
        case .direct: "DIRECT"
        }
    }
}

struct RoyToolsTunnelConfiguration: Codable, Equatable, Sendable {
    let mode: RoyToolsTunnelMode
    let subscriptionYAML: String
    let selectedGroup: String?
    let selectedNode: String?
    let rulesVersion: String?
    let rules: [String]
    let providers: [String: String]
    let musicDomains: [String]
    var lanPort: Int? = nil
    var profileID: UUID? = nil
    var groupSelections: [String: String]? = nil
}

enum RoyToolsTunnelCommand: String, Codable, Sendable {
    case reload, counters, status
}

struct RoyToolsProxyGroup: Codable, Equatable, Sendable, Identifiable {
    let name: String
    let nodes: [String]
    let selected: String?
    var selectable: Bool = true
    var id: String { name }
}

struct RoyToolsProxyLatencyRequest: Codable, Sendable {
    static let maximumNodes = 12
    static let maximumBytes = 64 * 1024
    let group: String?
    let nodes: [String]
}

struct RoyToolsProxyLatency: Codable, Equatable, Sendable {
    let node: String
    let milliseconds: Int?
}

struct RoyToolsTunnelReply: Codable, Sendable {
    let ok: Bool
    var message: String?
    var mode: RoyToolsTunnelMode?
    var rulesVersion: String?
    var uploaded: UInt64?
    var downloaded: UInt64?
    var musicProxyPort: Int?
    var musicProxyUsername: String?
    var musicProxyPassword: String?
    var lanProxyPort: Int?
    var selectedGroup: String?
    var selectedNode: String?
    var groups: [RoyToolsProxyGroup]?
    var proxyNodes: [String]?
    var globalChoices: [String]?
    var proxyLatencies: [RoyToolsProxyLatency]?
}

struct ProxyPayloadSample {
    let uploaded: UInt64
    let downloaded: UInt64
    let date: Date
    var wifi: UInt64 = 0
    var cellular: UInt64 = 0
}

struct DailyProxyTraffic: Codable, Equatable, Sendable {
    private let day: DateComponents
    private(set) var bytes: UInt64
    private var wifiBytes: UInt64?
    private var cellularBytes: UInt64?

    init(date: Date, bytes: UInt64 = 0, calendar: Calendar = .autoupdatingCurrent) {
        day = calendar.dateComponents([.era, .year, .month, .day], from: date)
        self.bytes = bytes
    }

    func bytes(on date: Date, calendar: Calendar = .autoupdatingCurrent) -> UInt64 {
        isOnDay(date, calendar: calendar) ? bytes : 0
    }

    func breakdown(on date: Date, calendar: Calendar = .autoupdatingCurrent) -> (wifi: UInt64, cellular: UInt64, unclassified: UInt64) {
        guard isOnDay(date, calendar: calendar) else { return (0, 0, 0) }
        let wifi = wifiBytes ?? 0
        let cellular = cellularBytes ?? 0
        return (wifi, cellular, bytes - min(bytes, Self.adding(wifi, cellular)))
    }

    private func isOnDay(_ date: Date, calendar: Calendar) -> Bool {
        let localDay = calendar.dateComponents([.era, .year, .month, .day], from: date)
        return localDay == day
    }

    func recording(_ sample: ProxyPayloadSample, since previous: ProxyPayloadSample,
                   calendar: Calendar = .autoupdatingCurrent) -> DailyProxyTraffic {
        var result = DailyProxyTraffic(date: sample.date, bytes: bytes(on: sample.date, calendar: calendar),
                                       calendar: calendar)
        // A delta crossing local midnight cannot be split between days without packet timestamps.
        guard calendar.isDate(previous.date, inSameDayAs: sample.date) else { return result }
        let up = sample.uploaded >= previous.uploaded ? sample.uploaded - previous.uploaded : sample.uploaded
        let down = sample.downloaded >= previous.downloaded ? sample.downloaded - previous.downloaded : sample.downloaded
        result.bytes = Self.adding(Self.adding(result.bytes, up), down)
        let sameDay = isOnDay(sample.date, calendar: calendar)
        let wifi = sample.wifi >= previous.wifi ? sample.wifi - previous.wifi : sample.wifi
        let cellular = sample.cellular >= previous.cellular ? sample.cellular - previous.cellular : sample.cellular
        result.wifiBytes = Self.adding(sameDay ? wifiBytes ?? 0 : 0, wifi)
        result.cellularBytes = Self.adding(sameDay ? cellularBytes ?? 0 : 0, cellular)
        return result
    }

    private static func adding(_ lhs: UInt64, _ rhs: UInt64) -> UInt64 {
        let (sum, overflow) = lhs.addingReportingOverflow(rhs)
        return overflow ? .max : sum
    }
}

enum RoyToolsTunnelStorage {
    static let appGroup = "group.com.roylyl.tools"
    static let providerBundleIdentifier = "com.roylyl.tools.packettunnel"
    static let activateRequestedOption = "useRequestedConfiguration"
    private static let maximumBytes = 24 * 1024 * 1024
    private static let proxyTrafficFileName = "daily-proxy-traffic.json"

    static func saveRequested(_ configuration: RoyToolsTunnelConfiguration) throws {
        try save(configuration, name: "requested.json")
    }

    static func loadRequested() throws -> RoyToolsTunnelConfiguration {
        try load(name: "requested.json")
    }

    static func saveApplied(_ configuration: RoyToolsTunnelConfiguration) throws {
        try save(configuration, name: "applied.json")
    }

    static func loadApplied() throws -> RoyToolsTunnelConfiguration {
        try load(name: "applied.json")
    }

    static func removeActivationConfigurations() throws {
        guard let directory = try? sharedVPNDirectory() else { return }
        for name in ["applied.json", "requested.json"] {
            let url = directory.appendingPathComponent(name)
            do { try FileManager.default.removeItem(at: url) }
            catch CocoaError.fileNoSuchFile {}
        }
    }

    static func loadDailyProxyTraffic() throws -> DailyProxyTraffic? {
        let url = try sharedVPNDirectory().appendingPathComponent(proxyTrafficFileName)
        do {
            return try JSONDecoder().decode(DailyProxyTraffic.self, from: Data(contentsOf: url))
        } catch CocoaError.fileReadNoSuchFile {
            return nil
        }
    }

    static func saveDailyProxyTraffic(_ record: DailyProxyTraffic) throws {
        let url = try sharedVPNDirectory().appendingPathComponent(proxyTrafficFileName)
        try JSONEncoder().encode(record).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func engineDirectory() throws -> URL {
        let url = try settingsDirectory().appendingPathComponent("Engine", isDirectory: true)
        try FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return url
    }

    private static func sharedVPNDirectory() throws -> URL {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw StorageError.missingAppGroup
        }
        return container.appendingPathComponent("VPN", isDirectory: true)
    }

    private static func settingsDirectory() throws -> URL {
        var url = try sharedVPNDirectory()
        try FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        try url.setResourceValues(resourceValues)
        return url
    }

    private static func save(_ configuration: RoyToolsTunnelConfiguration, name: String) throws {
        let data = try JSONEncoder().encode(configuration)
        guard data.count <= maximumBytes else { throw StorageError.configurationTooLarge }
        let url = try settingsDirectory().appendingPathComponent(name)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func load(name: String) throws -> RoyToolsTunnelConfiguration {
        let url = try settingsDirectory().appendingPathComponent(name)
        guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              (1...maximumBytes).contains(size) else { throw StorageError.configurationTooLarge }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        guard (1...maximumBytes).contains(data.count) else { throw StorageError.configurationTooLarge }
        return try JSONDecoder().decode(RoyToolsTunnelConfiguration.self, from: data)
    }

    private enum StorageError: LocalizedError {
        case missingAppGroup, configurationTooLarge

        var errorDescription: String? {
            switch self {
            case .missingAppGroup: "VPN共享目录不可用，请检查App Group签名"
            case .configurationTooLarge: "VPN配置大小无效"
            }
        }
    }
}
