import Foundation
import Observation

struct MusicTrack: Decodable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let artist: String
    let album: String
    let src: String
    let cover: String
    let trackNumber: Int?
    let discNumber: Int?
    let duration: Double?
    let albumArtist: String?
    let albumYear: Int?
    let releaseEdition: String?
    let audioRevision: String?
    let coverRevision: String?
    let lyricRevision: String?

    var audioURL: URL? { MusicCatalogStore.resourceURL(src, revision: audioRevision) }
    var coverURL: URL? { MusicCatalogStore.resourceURL(cover, revision: coverRevision) }
    var lyricsURL: URL? { MusicCatalogStore.resourceURL(String(src.dropLast(4)) + ".lrc", revision: lyricRevision) }

    var shareURL: URL {
        var address = URLComponents()
        address.scheme = "https"
        address.host = "roylyl.github.io"
        address.path = "/music/"
        address.queryItems = [URLQueryItem(name: "track", value: id), URLQueryItem(name: "full", value: "1")]
        return address.url ?? URL(string: "https://roylyl.github.io/music/")!
    }

    var artists: [String] {
        artist.replacingOccurrences(of: #"\s+(?:&|／|/)\s+|[、;；]"#, with: "\n", options: .regularExpression)
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    var collectionArtist: String {
        if let albumArtist, !albumArtist.isEmpty { return albumArtist }
        return src.split(separator: "/").dropFirst().first.map(String.init) ?? artist
    }

    var albumCategory: String { src.split(separator: "/").first.map(String.init) ?? "" }

    var albumID: String {
        [albumCategory, collectionArtist, album, albumYear.map(String.init) ?? "", releaseEdition ?? ""]
            .joined(separator: "\u{0}")
    }

    func matchesArtist(_ name: String) -> Bool {
        collectionArtist == name || artists.contains(name)
    }
}

struct MusicCatalog: Decodable, Sendable {
    struct AudioProperties: Decodable, Sendable {
        let codec: String?
        let sampleRate: Int?
        let bitDepth: Int?
        let channels: Int?
        let fileSize: Int64?
        let bitRate: Int?
    }

    struct Playlist: Decodable, Identifiable, Sendable {
        let id: String
        let name: String
        let entries: [String]
    }

    struct AppleMusic: Decodable, Sendable {
        struct Entry: Decodable, Sendable {
            let trackId: String?
            let title: String?
            let artist: String?
            let album: String?
            let duration: Double?
            let sourceUrl: String?
            let sourceStatus: String?

            var listeningURL: URL? {
                guard let sourceUrl, let url = URL(string: sourceUrl),
                      url.scheme == "https", url.host != nil else { return nil }
                return url
            }
        }

        let entries: [String: Entry]
        let favorites: [String]
        let playlists: [Playlist]
        let updatedAt: String?
    }

    let version: String
    let schemaVersion: Int?
    let tracks: [MusicTrack]
    let appleMusic: AppleMusic
    let audioProperties: [String: AudioProperties]?

    var favoriteTrackIDs: Set<String> {
        Set(appleMusic.favorites.compactMap { appleMusic.entries[$0]?.trackId })
    }

    func playlistTracks(_ entries: [String]) -> [MusicTrack] {
        let tracksByID = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        return entries.compactMap { appleMusic.entries[$0]?.trackId }.compactMap { tracksByID[$0] }
    }
}

@MainActor
@Observable
final class MusicCatalogStore {
    static let shared = MusicCatalogStore()

    private struct Manifest: Decodable {
        let schemaVersion: Int
        let version: String
        let catalog: String
    }

    private enum LoadError: LocalizedError {
        case invalidCatalog, versionMismatch

        var errorDescription: String? {
            switch self {
            case .invalidCatalog: "曲库格式无效"
            case .versionMismatch: "曲库正在发布，请稍后重试"
            }
        }
    }

    private nonisolated static let root = URL(string: "https://raw.githubusercontent.com/Roylyl/Music/main/")!
    private nonisolated static let maximumBytes = 24_000_000
    private static let supportDirectory = URL.applicationSupportDirectory.appending(path: "Music")
    private var cacheURL: URL { Self.supportDirectory.appending(path: "catalog.json") }

    private(set) var catalog: MusicCatalog?
    private(set) var status = ""
    private var isLoadingLocal = true
    private(set) var isRefreshing = false
    @ObservationIgnored private lazy var localCatalogTask: Task<MusicCatalog?, Never> = {
        let cachedURL = cacheURL
        let bundledURL = Bundle.main.url(forResource: "catalog", withExtension: "json")
        return Task.detached(priority: .userInitiated) {
            Self.readCatalog(at: cachedURL) ?? bundledURL.flatMap { Self.readCatalog(at: $0) }
        }
    }()

    var isLoading: Bool { isLoadingLocal || isRefreshing }

    func loadLocalCatalog() async {
        guard catalog == nil else { return }
        let local = await localCatalogTask.value
        if catalog == nil { catalog = local }
        isLoadingLocal = false
    }

    func checkForUpdates() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        await loadLocalCatalog()
        do {
            try Task.checkCancellation()
            let data = try await fetch(Self.root.appending(path: "catalog-version.json"), limit: 64_000)
            let manifest = try JSONDecoder().decode(Manifest.self, from: data)
            guard manifest.schemaVersion == 1, manifest.catalog == "catalog.json",
                  manifest.version.range(of: #"^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$"#, options: .regularExpression) != nil,
                  let address = Self.resourceURL(manifest.catalog, revision: manifest.version)
            else { throw LoadError.invalidCatalog }
            if let catalog, !Self.isNewer(manifest.version, than: catalog.version) {
                status = "已是最新曲库"
                return
            }
            let payload = try await fetch(address, limit: Self.maximumBytes)
            let updated = try await Task.detached(priority: .utility) { try Self.decodeCatalog(payload) }.value
            try Task.checkCancellation()
            guard updated.version == manifest.version else { throw LoadError.versionMismatch }
            try FileManager.default.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
            try payload.write(to: cacheURL, options: .atomic)
            catalog = updated
            status = "曲库已更新"
        } catch is CancellationError {
            return
        } catch {
            status = catalog == nil ? error.localizedDescription : "暂时无法同步，正在使用本地曲库"
        }
    }

    private func fetch(_ url: URL, limit: Int) async throws -> Data {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        return try await MusicNetwork.shared.data(for: request, limit: limit)
    }

    private nonisolated static func readCatalog(at url: URL) -> MusicCatalog? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return try? decodeCatalog(data)
    }

    private nonisolated static func isNewer(_ incoming: String, than installed: String) -> Bool {
        func parts(_ version: String) -> [Int] { version.split(separator: ".").compactMap { Int($0) } }
        return parts(installed).lexicographicallyPrecedes(parts(incoming))
    }

    private nonisolated static func decodeCatalog(_ data: Data) throws -> MusicCatalog {
        guard data.count <= maximumBytes else { throw LoadError.invalidCatalog }
        let catalog = try JSONDecoder().decode(MusicCatalog.self, from: data)
        guard catalog.schemaVersion == nil || catalog.schemaVersion == 1,
              (1...100_000).contains(catalog.tracks.count) else { throw LoadError.invalidCatalog }
        var ids = Set<String>()
        for track in catalog.tracks {
            guard !track.id.isEmpty, ids.insert(track.id).inserted, !track.title.isEmpty,
                  track.src.lowercased().hasSuffix(".mp3"), track.audioURL != nil, track.coverURL != nil
            else { throw LoadError.invalidCatalog }
        }
        return catalog
    }

    nonisolated static func resourceURL(_ path: String, revision: String?) -> URL? {
        guard !path.isEmpty, path.count <= 2048,
              !path.contains("\\"), !path.contains("?"), !path.contains("#"), !path.contains(":"),
              !path.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              path.range(of: #"%(?:2f|5c|2e|00)"#, options: [.regularExpression, .caseInsensitive]) == nil
        else { return nil }
        let segments = path.split(separator: "/", omittingEmptySubsequences: false)
        guard segments.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { return nil }
        let url = segments.reduce(root) { $0.appending(path: String($1), directoryHint: .notDirectory) }
        guard let revision, !revision.isEmpty else { return url }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "v", value: revision)]
        return components?.url
    }
}
