import CryptoKit
import Foundation

private struct RoutingJSONKey: CodingKey {
    let stringValue: String
    var intValue: Int? { nil }

    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

private extension Decoder {
    func requireKeys(_ expected: Set<String>, error: RoutingRuleError) throws {
        let keys = try container(keyedBy: RoutingJSONKey.self).allKeys
        guard Set(keys.map(\.stringValue)) == expected else { throw error }
    }
}

struct MusicRoutingPolicy: Decodable, Sendable {
    let domainSuffixes: [String]

    static let bundled = MusicRoutingPolicy(domainSuffixes: [
        "roylyl.github.io", "raw.githubusercontent.com"
    ])

    private enum CodingKeys: String, CodingKey { case domainSuffixes }

    private init(domainSuffixes: [String]) {
        self.domainSuffixes = domainSuffixes
    }

    init(from decoder: Decoder) throws {
        try decoder.requireKeys(["domainSuffixes"], error: .invalidMusicPolicy)
        let values = try decoder.container(keyedBy: CodingKeys.self)
        domainSuffixes = try values.decode([String].self, forKey: .domainSuffixes)
        try validate()
    }

    func matches(host: String) -> Bool {
        let normalized = host.lowercased()
        let name = normalized.hasSuffix(".") ? String(normalized.dropLast()) : normalized
        return domainSuffixes.contains { name == $0 || name.hasSuffix("." + $0) }
    }

    private func validate() throws {
        guard (1...32).contains(domainSuffixes.count),
              Set(domainSuffixes).count == domainSuffixes.count else {
            throw RoutingRuleError.invalidMusicPolicy
        }
        let labelPattern = #"\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\z"#
        let finalPattern = #"\A(?:[a-z]+|xn--[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\z"#
        for domain in domainSuffixes {
            let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
            guard domain.utf8.count <= 253, labels.count >= 2,
                  labels.allSatisfy({ String($0).range(of: labelPattern, options: .regularExpression) != nil }),
                  let finalLabel = labels.last,
                  String(finalLabel).range(of: finalPattern, options: .regularExpression) != nil else {
                throw RoutingRuleError.invalidMusicPolicy
            }
        }
    }
}

struct RoutingRuleSnapshot: Sendable {
    let version: String
    let notes: String
    let music: MusicRoutingPolicy
    let ai: RoutingRuleSection
    let configuration: RoutingRuleSection
}

struct RoutingRuleSection: Decodable, Sendable {
    let rules: [String]
    let providers: [String: String]

    private enum CodingKeys: String, CodingKey { case rules, providers }

    init(from decoder: Decoder) throws {
        try decoder.requireKeys(["rules", "providers"], error: .invalidBundle)
        let values = try decoder.container(keyedBy: CodingKeys.self)
        rules = try values.decode([String].self, forKey: .rules)
        providers = try values.decode([String: String].self, forKey: .providers)
    }

    func validate(prefix: String) throws {
        let validTypes: Set<String> = [
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-WILDCARD", "IP-CIDR",
            "IP-CIDR6", "GEOIP", "GEOSITE", "RULE-SET", "MATCH", "PROCESS-NAME",
            "DST-PORT", "SRC-IP-CIDR", "SRC-PORT", "NETWORK"
        ]
        guard (1...10_000).contains(rules.count), providers.count <= 100,
              rules.last?.hasPrefix("MATCH,") == true else {
            throw RoutingRuleError.invalidBundle
        }
        for (name, content) in providers {
            guard name.range(of: #"\Adirect-[0-9]{2}\.txt\z"#, options: .regularExpression) != nil,
                  content.utf16.count <= 8_000_000,
                  !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  Self.isValidProvider(content) else {
                throw RoutingRuleError.invalidBundle
            }
        }
        for (index, rule) in rules.enumerated() {
            let fields = rule.split(separator: ",", omittingEmptySubsequences: false)
            guard rule.utf16.count <= 4096, !rule.utf8.contains(10), !rule.utf8.contains(13),
                  fields.count >= 2, fields.allSatisfy({ !String($0).trimmingCharacters(in: .whitespaces).isEmpty }),
                  let type = fields.first, validTypes.contains(String(type)) else {
                throw RoutingRuleError.invalidBundle
            }
            if type == "MATCH" {
                guard index == rules.count - 1, fields.count == 2 else {
                    throw RoutingRuleError.invalidBundle
                }
            } else if type == "RULE-SET" {
                guard fields.count == 3, fields[1].hasPrefix(prefix + "direct-"),
                      providers[String(fields[1].dropFirst(prefix.count)) + ".txt"] != nil else {
                    throw RoutingRuleError.invalidBundle
                }
            }
        }
    }

    private static func isValidProvider(_ content: String) -> Bool {
        var hasDomain = false
        var trailingSpace = false
        for byte in content.utf8 {
            switch byte {
            case 10, 13:
                hasDomain = false
                trailingSpace = false
            case 9, 32:
                trailingSpace = hasDomain
            case 42, 43, 45, 46, 48...57, 65...90, 95, 97...122:
                if trailingSpace { return false }
                hasDomain = true
            default:
                return false
            }
        }
        return true
    }
}

actor RoutingRuleStore {
    enum UpdateResult: Sendable {
        case installed(RoutingRuleSnapshot)
        case current(RoutingRuleSnapshot)
    }

    private struct Manifest: Decodable {
        let version: String
        let bytes: Int
        let sha256: String
        let notes: String?
        let music: MusicRoutingPolicy?

        private enum CodingKeys: String, CodingKey { case version, bytes, sha256, notes, music }

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            version = try values.decode(String.self, forKey: .version)
            bytes = try values.decode(Int.self, forKey: .bytes)
            sha256 = try values.decode(String.self, forKey: .sha256)
            notes = try values.decodeIfPresent(String.self, forKey: .notes)
            music = values.contains(.music) ? try values.decode(MusicRoutingPolicy.self, forKey: .music) : nil
        }

        func validate() throws {
            guard RoutingRuleStore.versionParts(version) != nil,
                  (1...RoutingRuleStore.maxBundleBytes).contains(bytes),
                  sha256.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil else {
                throw RoutingRuleError.invalidManifest
            }
        }
    }

    private struct BundleBody: Decodable {
        let version: String
        let ai: RoutingRuleSection
        let rule: RoutingRuleSection

        private enum CodingKeys: String, CodingKey { case version, ai, rule }

        init(from decoder: Decoder) throws {
            try decoder.requireKeys(["version", "ai", "rule"], error: .invalidBundle)
            let values = try decoder.container(keyedBy: CodingKeys.self)
            version = try values.decode(String.self, forKey: .version)
            ai = try values.decode(RoutingRuleSection.self, forKey: .ai)
            rule = try values.decode(RoutingRuleSection.self, forKey: .rule)
        }
    }

    private struct SavedBundle: Codable {
        let manifest: Data
        let rules: Data
    }

    private static let baseURL = URL(string: "https://raw.githubusercontent.com/Roylyl/RoyTools/main/")!
    private static let manifestName = "routing-version.json"
    private static let rulesName = "routing-rules.json"
    private static let maxManifestBytes = 16_384
    private static let maxBundleBytes = 16 * 1024 * 1024

    private let session: URLSession
    private var cached: RoutingRuleSnapshot?
    private var didLoad = false

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 120
        session = URLSession(configuration: config)
    }

    func current() -> RoutingRuleSnapshot? {
        if didLoad { return cached }
        didLoad = true
        let bundled = try? loadBundled()
        let saved = try? loadSaved()
        if let saved, bundled.map({ !Self.isNewer($0.version, than: saved.version) }) ?? true {
            cached = saved
        } else {
            cached = bundled
        }
        return cached
    }

    func update() async throws -> UpdateResult {
        let manifestData = try await fetch(Self.manifestName, limit: Self.maxManifestBytes)
        let manifest = try Self.parseManifest(manifestData)
        if let current = current(), !Self.isNewer(manifest.version, than: current.version) {
            return .current(current)
        }
        let rules = try await fetch(Self.rulesName, limit: manifest.bytes)
        let snapshot = try Self.validate(rules, against: manifest)
        try Task.checkCancellation()
        if let current = current(), !Self.isNewer(manifest.version, than: current.version) {
            return .current(current)
        }
        let saved = SavedBundle(manifest: manifestData, rules: rules)
        try JSONEncoder().encode(saved).write(to: cacheFile(createDirectory: true), options: .atomic)
        cached = snapshot
        return .installed(snapshot)
    }

    private func cacheFile(createDirectory: Bool = false) throws -> URL {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: createDirectory
        )
        return directory.appendingPathComponent("routing-snapshot.json")
    }

    private func loadSaved() throws -> RoutingRuleSnapshot {
        let recordData = try Data(contentsOf: cacheFile(), options: .mappedIfSafe)
        guard recordData.count <= Self.maxBundleBytes * 2 else { throw RoutingRuleError.invalidBundle }
        let record = try JSONDecoder().decode(SavedBundle.self, from: recordData)
        return try Self.validate(record.rules, against: Self.parseManifest(record.manifest))
    }

    private func loadBundled() throws -> RoutingRuleSnapshot {
        guard let manifestURL = Bundle.main.url(forResource: "routing-version", withExtension: "json"),
              let rulesURL = Bundle.main.url(forResource: "routing-rules", withExtension: "json") else {
            throw RoutingRuleError.invalidBundle
        }
        let manifest = try Self.parseManifest(Data(contentsOf: manifestURL))
        return try Self.validate(Data(contentsOf: rulesURL, options: .mappedIfSafe), against: manifest)
    }

    private static func parseManifest(_ data: Data) throws -> Manifest {
        guard (1...maxManifestBytes).contains(data.count) else { throw RoutingRuleError.invalidManifest }
        let manifest = try JSONDecoder().decode(Manifest.self, from: data)
        try manifest.validate()
        return manifest
    }

    private static func validate(_ rules: Data, against manifest: Manifest) throws -> RoutingRuleSnapshot {
        guard rules.count == manifest.bytes else { throw RoutingRuleError.integrity }
        let hash = SHA256.hash(data: rules).map { String(format: "%02x", $0) }.joined()
        guard hash == manifest.sha256 else { throw RoutingRuleError.integrity }
        let decoded = try JSONDecoder().decode(BundleBody.self, from: rules)
        guard decoded.version == manifest.version, decoded.ai.rules.last == "MATCH,DIRECT" else {
            throw RoutingRuleError.invalidBundle
        }
        try decoded.ai.validate(prefix: "roytools-ai-")
        try decoded.rule.validate(prefix: "roytools-rule-")
        return RoutingRuleSnapshot(
            version: manifest.version, notes: String((manifest.notes ?? "").prefix(2000)),
            music: manifest.music ?? .bundled, ai: decoded.ai, configuration: decoded.rule
        )
    }

    private func fetch(_ name: String, limit: Int) async throws -> Data {
        let url = Self.baseURL.appendingPathComponent(name)
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "check", value: String(Int64(Date().timeIntervalSince1970 * 1000)))]
        var request = URLRequest(url: components.url!, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("RoyTools-iOS-RoutingUpdater", forHTTPHeaderField: "User-Agent")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse,
              http.url?.scheme == "https", http.url?.host == url.host, http.url?.path == url.path else {
            throw RoutingRuleError.invalidManifest
        }
        guard http.statusCode == 200 else { throw RoutingRuleError.server(http.statusCode) }
        guard response.expectedContentLength <= Int64(limit) else { throw RoutingRuleError.integrity }
        var data = Data()
        data.reserveCapacity(min(limit, max(0, Int(response.expectedContentLength))))
        for try await byte in bytes {
            guard data.count < limit else { throw RoutingRuleError.integrity }
            data.append(byte)
        }
        return data
    }

    private static func isNewer(_ version: String, than previous: String) -> Bool {
        guard let current = versionParts(previous), let next = versionParts(version) else { return false }
        return current.lexicographicallyPrecedes(next)
    }

    private static func versionParts(_ version: String) -> [Int]? {
        let parts = version.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let numbers = parts.compactMap { part -> Int? in
            guard (1...6).contains(part.utf8.count), part.count == 1 || part.first != "0",
                  part.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
            return Int(part)
        }
        return numbers.count == 3 ? numbers : nil
    }
}

enum RoutingRuleError: LocalizedError {
    case invalidManifest, invalidMusicPolicy, invalidBundle, integrity, server(Int)

    var errorDescription: String? {
        switch self {
        case .invalidManifest: "规则版本清单无效"
        case .invalidMusicPolicy: "音乐域名策略无效"
        case .invalidBundle: "AI或代理规则包格式无效"
        case .integrity: "规则包与版本清单不一致，请稍后重试"
        case .server(404): "GitHub仓库尚未发布规则文件"
        case .server(let code): "GitHub请求失败（HTTP\(code)）"
        }
    }
}
