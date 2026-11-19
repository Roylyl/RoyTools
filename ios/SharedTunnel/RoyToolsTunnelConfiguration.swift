import Foundation

enum RoyToolsTunnelMode: String, Codable, CaseIterable, Sendable {
    case subscription = "default"
    case rule, music, ai
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
}

enum RoyToolsTunnelCommand: String, Codable, Sendable {
    case reload, counters, status
}

struct RoyToolsProxyGroup: Codable, Equatable, Sendable, Identifiable {
    let name: String
    let nodes: [String]
    let selected: String?
    var id: String { name }
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
    var selectedGroup: String?
    var selectedNode: String?
    var groups: [RoyToolsProxyGroup]?
}

enum RoyToolsTunnelStorage {
    static let appGroup = "group.com.roylyl.tools"
    static let providerBundleIdentifier = "com.roylyl.tools.packettunnel"
    static let activateRequestedOption = "useRequestedConfiguration"
    private static let maximumBytes = 24 * 1024 * 1024

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

    static func engineDirectory() throws -> URL {
        let url = try settingsDirectory().appendingPathComponent("Engine", isDirectory: true)
        try FileManager.default.createDirectory(
            at: url, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return url
    }

    private static func settingsDirectory() throws -> URL {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw StorageError.missingAppGroup
        }
        var url = container.appendingPathComponent("VPN", isDirectory: true)
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
