import Foundation

struct VPNProfile: Codable, Equatable, Identifiable, Sendable {
    let id: UUID
    let name: String
    let yaml: String
    let subscription: URL?
    private let fileName: String?

    private static let maximumBytes = 2 * 1024 * 1024

    private enum CodingKeys: String, CodingKey {
        case id, name, yaml, subscription, fileName
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try values.decode(String.self, forKey: .name)
        yaml = try values.decode(String.self, forKey: .yaml)
        subscription = try values.decodeIfPresent(URL.self, forKey: .subscription)
        fileName = try values.decodeIfPresent(String.self, forKey: .fileName)
    }

    private init(id: UUID = UUID(), name: String, yaml: String, subscription: URL?, fileName: String? = nil) {
        self.id = id
        self.name = name
        self.yaml = yaml
        self.subscription = subscription
        self.fileName = fileName
    }

    func edited(name: String, content: VPNProfile? = nil) -> VPNProfile {
        let source = content ?? self
        return VPNProfile(id: id, name: name, yaml: source.yaml, subscription: source.subscription,
                          fileName: source.subscription == nil ? source.fileName ?? source.name : nil)
    }

    func identified(as id: UUID) -> VPNProfile {
        VPNProfile(id: id, name: name, yaml: yaml, subscription: subscription, fileName: fileName)
    }

    static func fetch(_ address: String) async throws -> VPNProfile {
        let input = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: input), validSubscriptionURL(url), let host = url.host else {
            throw VPNError.invalidSubscription
        }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        let session = URLSession(configuration: config, delegate: SubscriptionRedirects(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let client = version.map { "RoyTools/\($0)" } ?? "RoyTools"
        request.setValue("\(client) Clash.Meta", forHTTPHeaderField: "User-Agent")
        request.setValue("application/yaml, text/yaml, text/plain, */*;q=0.5", forHTTPHeaderField: "Accept")
        do {
            let (stream, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse, let finalURL = response.url,
                  validSubscriptionURL(finalURL) else { throw VPNError.subscriptionDownload }
            guard (200..<300).contains(response.statusCode) else {
                throw VPNError.subscriptionHTTPStatus(response.statusCode)
            }
            guard response.expectedContentLength <= Int64(maximumBytes) else { throw VPNError.profileTooLarge }
            var data = Data()
            data.reserveCapacity(max(0, Int(response.expectedContentLength)))
            for try await byte in stream {
                guard data.count < maximumBytes else { throw VPNError.profileTooLarge }
                data.append(byte)
            }
            return VPNProfile(name: host, yaml: try yamlText(data), subscription: url)
        } catch let error as VPNError {
            throw error
        } catch {
            try Task.checkCancellation()
            throw VPNError.subscriptionDownload
        }
    }

    private static func validSubscriptionURL(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && url.host?.isEmpty == false && url.user == nil && url.password == nil
    }

    private final class SubscriptionRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
            completionHandler(request.url.map(VPNProfile.validSubscriptionURL) == true ? request : nil)
        }
    }

    static func importFile(_ url: URL) throws -> VPNProfile {
        let hasAccess = url.startAccessingSecurityScopedResource()
        defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
        var contents: Result<Data, Error>?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { coordinatedURL in
            contents = Result { try readFile(coordinatedURL) }
        }
        guard let contents, coordinationError == nil else { throw VPNError.profileFileUnavailable }
        let data: Data
        do { data = try contents.get() }
        catch let error as VPNError { throw error }
        catch { throw VPNError.profileFileUnavailable }
        let name = url.deletingPathExtension().lastPathComponent
        return VPNProfile(name: name, yaml: try yamlText(data), subscription: nil, fileName: name)
    }

    private static func readFile(_ url: URL) throws -> Data {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var data = Data()
        while let chunk = try file.read(upToCount: min(64 * 1024, maximumBytes - data.count + 1)), !chunk.isEmpty {
            data.append(chunk)
            guard data.count <= maximumBytes else { throw VPNError.profileTooLarge }
        }
        return data
    }

    private static func yamlText(_ data: Data) throws -> String {
        guard (1...maximumBytes).contains(data.count),
              let text = String(data: data, encoding: .utf8) else { throw VPNError.invalidProfile }
        let body = text.first == "\u{FEFF}" ? text.dropFirst() : text[...]
        let lines = body.split(whereSeparator: \.isNewline).lazy
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix("%") && $0 != "---" }
        guard let first = lines.first, !first.hasPrefix("<"), !first.hasPrefix("["), !text.contains("\0") else {
            throw VPNError.invalidProfile
        }
        let start = first.hasPrefix("--- ") ? String(first.dropFirst(4)) : first
        // Mihomo performs full validation when activating the profile. Filter obvious provider
        // error pages, line-based node links and other documents before replacing a saved profile.
        if start.hasPrefix("{") {
            if let json = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any],
               !Set(json.keys).isDisjoint(with: ["error", "message", "success"]),
               Set(json.keys).isDisjoint(with: ["proxies", "proxy-groups", "proxy-providers", "rules", "mode", "dns"]) {
                throw VPNError.invalidProfile
            }
        } else {
            let mappingKey = #"^(?:[A-Za-z0-9_-]+|"[^"]+"|'[^']+')\s*:(?:\s|$)"#
            guard start.range(of: mappingKey, options: .regularExpression) != nil
                    || ((start.hasPrefix("&") || start.hasPrefix("!")) && lines.contains(where: {
                        $0.range(of: mappingKey, options: .regularExpression) != nil
                    })) else { throw VPNError.invalidProfile }
            let errorKey = #"^(?:error|message|success)\s*:"#
            let profileKeys = #"^(?:proxies|proxy-groups|proxy-providers|rule-providers|rules|mode|dns)\s*:"#
            if start.range(of: errorKey, options: [.regularExpression, .caseInsensitive]) != nil,
               !lines.contains(where: { $0.range(of: profileKeys, options: .regularExpression) != nil }) {
                throw VPNError.invalidProfile
            }
        }
        return String(body)
    }

    private static var file: URL {
        URL.applicationSupportDirectory.appending(path: "Proxy/selected-profile.json")
    }

    private static var libraryFile: URL {
        file.deletingLastPathComponent().appending(path: "saved-profiles.json")
    }

    func isSameSource(as other: VPNProfile) -> Bool {
        if let subscription { return subscription == other.subscription }
        return other.subscription == nil && (fileName ?? name) == (other.fileName ?? other.name) && yaml == other.yaml
    }

    static func loadLibrary() throws -> [VPNProfile] {
        guard FileManager.default.fileExists(atPath: libraryFile.path) else { return [] }
        return try JSONDecoder().decode([VPNProfile].self, from: Data(contentsOf: libraryFile))
    }

    static func saveLibrary(_ profiles: [VPNProfile]) throws {
        try write(profiles, to: libraryFile)
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
        try write(profile, to: Self.file)
    }

    private static func write<T: Encodable>(_ value: T, to file: URL) throws {
        var directory = file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        try JSONEncoder().encode(value).write(to: file,
                                             options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

enum VPNError: LocalizedError {
    case invalidSubscription, subscriptionDownload, subscriptionHTTPStatus(Int)
    case invalidProfile, profileTooLarge, profileFileUnavailable, missingProfile, rulesUnavailable
    case sessionUnavailable, transition, messageTimeout, providerRejected(String?)
    case invalidProfileName, duplicateProfileSource

    var errorDescription: String? {
        switch self {
        case .invalidSubscription: "请输入有效的HTTPS订阅地址"
        case .subscriptionDownload: "无法下载订阅，请检查地址和网络"
        case .subscriptionHTTPStatus(let code): "订阅服务器返回HTTP\(code)"
        case .invalidProfile: "请选择包含Clash/Mihomo配置的YAML文件或订阅"
        case .profileTooLarge: "配置文件超过2MB"
        case .profileFileUnavailable: "无法读取配置文件，请确认文件已下载"
        case .invalidProfileName: "请输入配置名称"
        case .duplicateProfileSource: "已经保存过此配置来源"
        case .missingProfile: "请先导入订阅或配置文件"
        case .rulesUnavailable: "本地规则无法读取，请检查规则更新"
        case .sessionUnavailable: "无法连接VPN扩展"
        case .transition: "VPN正在切换状态，请稍候"
        case .messageTimeout: "VPN扩展未及时返回结果，请刷新连接状态"
        case .providerRejected(let message): message ?? "代理核心未能应用配置"
        }
    }
}
