import Darwin
import Foundation

enum RoyToolsDiagnosticLog {
    enum Module: String, CaseIterable, Identifiable, Sendable {
        case music, proxy, app

        var id: Self { self }
        var title: String {
            switch self {
            case .music: "音乐"
            case .proxy: "代理"
            case .app: "应用"
            }
        }
    }

    enum Level: String, Codable, Sendable {
        case info = "INFO"
        case warning = "WARN"
        case error = "ERROR"
    }

    enum Source: String, Codable, Sendable {
        case app = "应用"
        case tunnel = "隧道"
    }

    enum Outcome: String, Sendable {
        case started = "开始"
        case succeeded = "成功"
        case failed = "失败"
        case cancelled = "取消"
    }

    struct Entry: Codable, Identifiable, Sendable {
        let id: UUID
        let date: Date
        let level: Level
        let source: Source
        let message: String

        var line: String {
            let timestamp = date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
            return "\(timestamp) [\(level.rawValue)] [\(source.rawValue)] \(message)"
        }
    }

    private enum LogError: LocalizedError {
        case unavailable, unreadable

        var errorDescription: String? {
            switch self {
            case .unavailable: "日志存储暂不可用"
            case .unreadable: "无法读取日志记录"
            }
        }
    }

    private struct ModuleFile: Codable {
        var clearedAt: Date?
        var entries: [Entry] = []
    }

    private static let maximumEntries = 400
    private static let maximumFileBytes = 512 * 1024
    private static let work = DispatchQueue(label: "com.roylyl.tools.diagnostic-log", qos: .utility)

    /// Only accept literal event names and structured numeric error information.
    static func record(_ module: Module, _ message: StaticString, level: Level = .info,
                       source: Source = .app, outcome: Outcome? = nil, error: Error? = nil,
                       code: Int? = nil, mode: RoyToolsTunnelMode? = nil) {
        let details = [String(describing: message), outcome?.rawValue, error.map(errorIdentifier),
                       code.map { "code \($0)" }, mode.map { "模式 \($0.rawValue)" }]
            .compactMap { $0 }.joined(separator: " · ")
        let entry = Entry(id: UUID(), date: .now, level: level, source: source,
                          message: String(details.prefix(180)))
        work.async {
            try? withStore { directory in
                let url = logFile(module, at: directory)
                // A damaged file should not prevent collection of subsequent diagnostics.
                var file = (try? load(at: url)) ?? ModuleFile()
                guard file.clearedAt.map({ entry.date >= $0 }) != false else { return }
                file.entries.append(entry)
                file.entries.sort { $0.date < $1.date }
                file.entries = Array(file.entries.suffix(maximumEntries))
                try save(file, at: url)
            }
        }
    }

    static func flush() {
        work.sync {}
    }

    static func read(_ module: Module) async throws -> [Entry] {
        try await perform { try withStore { try load(at: logFile(module, at: $0)).entries } }
    }

    static func export(_ module: Module) async throws -> URL {
        try await perform {
            let entries = try withStore { try load(at: logFile(module, at: $0)).entries }
            let text = (["RoyTools\(module.title)日志"] + entries.map(\.line)).joined(separator: "\n") + "\n"
            let directory = FileManager.default.temporaryDirectory.appending(path: "RoyToolsLogExports", directoryHint: .isDirectory)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let stamp = Date.now.formatted(.iso8601).replacingOccurrences(of: ":", with: "-")
            let url = directory.appending(path: "RoyTools-\(module.title)日志-\(stamp)-\(UUID().uuidString.prefix(8)).txt")
            try Data(text.utf8).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return url
        }
    }

    static func clear(_ module: Module) async throws {
        try await clear([module])
    }

    static func clearAll() async throws {
        try await clear(Module.allCases)
    }

    private static func clear(_ modules: [Module]) async throws {
        try await perform {
            try withStore { directory in
                let empty = ModuleFile(clearedAt: .now)
                for module in modules { try save(empty, at: logFile(module, at: directory)) }
            }
        }
    }

    private static func save(_ file: ModuleFile, at url: URL) throws {
        try JSONEncoder().encode(file).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func perform<Value: Sendable>(_ action: @escaping @Sendable () throws -> Value) async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            work.async { continuation.resume(with: Result(catching: action)) }
        }
    }

    private static func logFile(_ module: Module, at directory: URL) -> URL {
        directory.appending(path: "\(module.rawValue).json")
    }

    private static func load(at url: URL) throws -> ModuleFile {
        do {
            let fileSize = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize
            guard let fileSize, fileSize <= maximumFileBytes else { throw LogError.unreadable }
            return try JSONDecoder().decode(ModuleFile.self, from: Data(contentsOf: url))
        } catch CocoaError.fileNoSuchFile {
            return ModuleFile()
        } catch CocoaError.fileReadNoSuchFile {
            return ModuleFile()
        }
    }

    private static func withStore<Value>(_ action: (URL) throws -> Value) throws -> Value {
        let manager = FileManager.default
        guard let group = manager.containerURL(forSecurityApplicationGroupIdentifier: RoyToolsTunnelStorage.appGroup) else {
            throw LogError.unavailable
        }
        var directory = group.appending(path: "Diagnostics", directoryHint: .isDirectory)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true,
                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var settings = URLResourceValues()
        settings.isExcludedFromBackup = true
        try? directory.setResourceValues(settings)

        // The app and packet tunnel share the store. Lock a separate file so atomic replacement stays safe.
        let descriptor = open(directory.appending(path: "store.lock").path, O_RDWR | O_CREAT, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw LogError.unavailable }
        defer { close(descriptor) }
        for _ in 0..<20 {
            if flock(descriptor, LOCK_EX | LOCK_NB) == 0 { return try action(directory) }
            guard errno == EWOULDBLOCK || errno == EINTR else { throw LogError.unavailable }
            usleep(5_000)
        }
        throw LogError.unavailable
    }

    private static func errorIdentifier(_ error: Error) -> String {
        let nsError = error as NSError
        let domain: String
        switch nsError.domain {
        case NSURLErrorDomain, NSCocoaErrorDomain, NSPOSIXErrorDomain,
             "NEVPNErrorDomain", "NEConfigurationErrorDomain", "NETunnelProviderErrorDomain":
            domain = nsError.domain
        default:
            domain = "RoyTools"
        }
        return "\(domain) \(nsError.code)"
    }
}
