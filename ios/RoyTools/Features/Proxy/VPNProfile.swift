import Foundation

struct VPNProfile: Codable, Sendable {
    let name: String
    let yaml: String
    let subscription: URL?

    private static let maximumBytes = 2 * 1024 * 1024

    static func fetch(_ address: String) async throws -> VPNProfile {
        let input = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: input), url.scheme?.lowercased() == "https",
              let host = url.host, url.user == nil, url.password == nil else {
            throw VPNError.invalidSubscription
        }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("RoyTools/2.2.0 Clash.Meta", forHTTPHeaderField: "User-Agent")
        let (stream, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
              response.url?.scheme == "https" else { throw VPNError.subscriptionDownload }
        var data = Data()
        for try await byte in stream {
            guard data.count < maximumBytes else { throw VPNError.invalidProfile }
            data.append(byte)
        }
        return VPNProfile(name: host, yaml: try yamlText(data), subscription: url)
    }

    static func importFile(_ url: URL) throws -> VPNProfile {
        let hasAccess = url.startAccessingSecurityScopedResource()
        defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: maximumBytes + 1) ?? Data()
        return VPNProfile(name: url.deletingPathExtension().lastPathComponent,
                          yaml: try yamlText(data), subscription: nil)
    }

    private static func yamlText(_ data: Data) throws -> String {
        guard (1...maximumBytes).contains(data.count),
              let text = String(data: data, encoding: .utf8) else { throw VPNError.invalidProfile }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("<"), !text.contains("\0") else {
            throw VPNError.invalidProfile
        }
        return text
    }

    private static var file: URL {
        URL.applicationSupportDirectory.appending(path: "Proxy/selected-profile.json")
    }

    static func load() throws -> VPNProfile? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(VPNProfile.self, from: Data(contentsOf: file))
    }

    static func save(_ profile: VPNProfile?) throws {
        guard let profile else {
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
            return
        }
        var directory = Self.file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        try JSONEncoder().encode(profile).write(to: Self.file,
                                              options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

enum VPNError: LocalizedError {
    case invalidSubscription, subscriptionDownload, invalidProfile, missingProfile, rulesUnavailable
    case sessionUnavailable, transition, messageTimeout, providerRejected(String?)

    var errorDescription: String? {
        switch self {
        case .invalidSubscription: "请输入有效的HTTPS订阅地址"
        case .subscriptionDownload: "无法下载订阅，请检查地址和网络"
        case .invalidProfile: "请选择有效的Clash/Mihomo YAML配置（最大2MB）"
        case .missingProfile: "请先导入订阅或配置文件"
        case .rulesUnavailable: "本地规则无法读取，请检查规则更新"
        case .sessionUnavailable: "无法连接VPN扩展"
        case .transition: "VPN正在切换状态，请稍候"
        case .messageTimeout: "VPN扩展未及时返回结果，请刷新连接状态"
        case .providerRejected(let message): message ?? "代理核心未能应用配置"
        }
    }
}
