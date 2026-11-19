import Foundation
import Observation

struct MusicTrack: Decodable, Identifiable, Hashable {
    let id: String
    let title: String
    let artist: String
    let album: String
    let src: String
    let cover: String
    let trackNumber: Int?
    let discNumber: Int?
    let duration: Double?
    let audioRevision: String?
    let coverRevision: String?

    var audioURL: URL? { MusicCatalogStore.resourceURL(src, revision: audioRevision) }
    var coverURL: URL? { MusicCatalogStore.resourceURL(cover, revision: coverRevision) }
}

struct MusicCatalog: Decodable {
    struct Playlist: Decodable, Identifiable {
        let id: String
        let name: String
        let entries: [String]
    }

    struct AppleMusic: Decodable {
        struct Entry: Decodable {
            let trackId: String?
        }

        let entries: [String: Entry]
        let favorites: [String]
        let playlists: [Playlist]
    }

    let version: String
    let schemaVersion: Int?
    let tracks: [MusicTrack]
    let appleMusic: AppleMusic

    func playlistTracks(_ entries: [String]) -> [MusicTrack] {
        let tracksByID = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        return entries.compactMap { appleMusic.entries[$0]?.trackId }.compactMap { tracksByID[$0] }
    }
}

@MainActor
@Observable
final class MusicCatalogStore {
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
    private static let maximumBytes = 24_000_000
    private static let supportDirectory = URL.applicationSupportDirectory.appending(path: "Music")
    private var cacheURL: URL { Self.supportDirectory.appending(path: "catalog.json") }

    private(set) var catalog: MusicCatalog?
    private(set) var isLoading = false
    private(set) var status = ""
    private var hasLoaded = false

    func load() async {
        guard !hasLoaded else { return }
        hasLoaded = true
        if let data = try? Data(contentsOf: cacheURL) {
            catalog = try? Self.decodeCatalog(data)
        }
        await refresh()
    }

    func refresh() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let data = try await fetch(Self.root.appending(path: "catalog-version.json"), limit: 64_000)
            let manifest = try JSONDecoder().decode(Manifest.self, from: data)
            guard manifest.schemaVersion == 1, manifest.catalog == "catalog.json",
                  manifest.version.range(of: #"^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$"#, options: .regularExpression) != nil,
                  let address = Self.resourceURL(manifest.catalog, revision: manifest.version)
            else { throw LoadError.invalidCatalog }
            if manifest.version == catalog?.version {
                status = "已是最新曲库"
                return
            }
            let payload = try await fetch(address, limit: Self.maximumBytes)
            let updated = try Self.decodeCatalog(payload)
            guard updated.version == manifest.version else { throw LoadError.versionMismatch }
            try FileManager.default.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
            try payload.write(to: cacheURL, options: .atomic)
            catalog = updated
            status = "曲库已更新"
        } catch is CancellationError {
            if catalog == nil { hasLoaded = false }
        } catch {
            status = catalog == nil ? error.localizedDescription : "暂时无法同步，正在使用本地曲库"
        }
    }

    private func fetch(_ url: URL, limit: Int) async throws -> Data {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        return try await MusicNetwork.shared.data(for: request, limit: limit)
    }

    private static func decodeCatalog(_ data: Data) throws -> MusicCatalog {
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
