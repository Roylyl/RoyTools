import SwiftUI
import UIKit

struct MusicView: View {
    let catalog: MusicCatalogStore
    let player: MusicPlayer

    var body: some View {
        NavigationStack {
            List {
                if let current = catalog.catalog {
                    NavigationLink {
                        TrackList(title: "全部歌曲", tracks: current.tracks, player: player)
                    } label: {
                        Label("全部歌曲", systemImage: "music.note.list")
                    }
                    NavigationLink {
                        AlbumsView(tracks: current.tracks, player: player)
                    } label: {
                        Label("专辑", systemImage: "square.stack")
                    }
                    NavigationLink {
                        PlaylistsView(catalog: current, player: player)
                    } label: {
                        Label("歌单", systemImage: "list.bullet")
                    }
                } else {
                    ContentUnavailableView {
                        Label("暂无曲库", systemImage: "music.note")
                    } description: {
                        if catalog.isLoading { Text("正在加载曲库") }
                        else { Text("联网后可同步歌曲、专辑和歌单。") }
                    }
                    .listRowBackground(Color.clear)
                }
                Section {
                    Button {
                        Task { await catalog.refresh() }
                    } label: {
                        HStack {
                            Label("检查曲库更新", systemImage: "arrow.clockwise")
                            Spacer()
                            if catalog.isLoading { ProgressView() }
                        }
                    }
                    .disabled(catalog.isLoading)
                } footer: {
                    VStack(alignment: .leading) {
                        if let version = catalog.catalog?.version { Text("曲库版本\(version)") }
                        if !catalog.status.isEmpty { Text(catalog.status) }
                    }
                }
            }
            .navigationTitle("音乐")
            .task { await catalog.load() }
            .refreshable { await catalog.refresh() }
        }
    }
}

private struct TrackList: View {
    let title: String
    let tracks: [MusicTrack]
    let player: MusicPlayer
    @State private var search = ""

    private var filtered: [MusicTrack] {
        guard !search.isEmpty else { return tracks }
        return tracks.filter {
            $0.title.localizedCaseInsensitiveContains(search) ||
            $0.artist.localizedCaseInsensitiveContains(search) ||
            $0.album.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        let current = filtered
        List {
            ForEach(Array(current.enumerated()), id: \.offset) { _, track in
                Button {
                    player.play(track, in: current)
                } label: {
                    HStack(spacing: 12) {
                        MusicCover(url: track.coverURL, edge: 42)
                        VStack(alignment: .leading) {
                            Text(track.title).lineLimit(1)
                            Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        if player.track?.id == track.id {
                            Image(systemName: "waveform").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .navigationTitle(title)
        .searchable(text: $search, prompt: "搜索歌曲、歌手或专辑")
        .overlay {
            if current.isEmpty, !search.isEmpty { ContentUnavailableView.search(text: search) }
        }
    }
}

private struct AlbumsView: View {
    struct Album: Identifiable {
        let id: String
        let first: MusicTrack
        let tracks: [MusicTrack]
    }

    let tracks: [MusicTrack]
    let player: MusicPlayer

    private var albums: [Album] {
        Dictionary(grouping: tracks) { $0.artist + "\u{0}" + $0.album }
            .compactMap { id, tracks in
                guard let first = tracks.first else { return nil }
                let ordered = tracks.sorted {
                    ($0.discNumber ?? 0, $0.trackNumber ?? 0) < ($1.discNumber ?? 0, $1.trackNumber ?? 0)
                }
                return Album(id: id, first: first, tracks: ordered)
            }
            .sorted { $0.first.album.localizedStandardCompare($1.first.album) == .orderedAscending }
    }

    var body: some View {
        List(albums) { album in
            NavigationLink {
                TrackList(title: album.first.album, tracks: album.tracks, player: player)
            } label: {
                HStack(spacing: 12) {
                    MusicCover(url: album.first.coverURL, edge: 48)
                    VStack(alignment: .leading) {
                        Text(album.first.album)
                        Text(album.first.artist).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("专辑")
    }
}

private struct PlaylistsView: View {
    let catalog: MusicCatalog
    let player: MusicPlayer

    var body: some View {
        List {
            playlist(title: "我喜欢", icon: "heart", items: catalog.appleMusic.favorites)
            ForEach(catalog.appleMusic.playlists) { entry in
                playlist(title: entry.name, icon: "music.note.list", items: entry.entries)
            }
        }
        .navigationTitle("歌单")
    }

    private func playlist(title: String, icon: String, items: [String]) -> some View {
        let tracks = catalog.playlistTracks(items)
        return NavigationLink {
            TrackList(title: title, tracks: tracks, player: player)
        } label: {
            HStack {
                Label(title, systemImage: icon)
                Spacer()
                Text("\(tracks.count)首").foregroundStyle(.secondary)
            }
        }
    }
}

@MainActor
struct MusicCover: View {
    let url: URL?
    let edge: CGFloat
    @State private var image: UIImage?
    private let network = MusicNetwork.shared

    private static let cachedImages: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.countLimit = 64
        return cache
    }()

    private struct Request: Equatable {
        let url: URL?
        let generation: Int
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Rectangle().fill(.quaternary)
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
        }
        .frame(width: edge, height: edge)
        .clipShape(.rect(cornerRadius: edge * 0.16))
        .accessibilityHidden(true)
        .task(id: Request(url: url, generation: network.generation)) {
            await load()
        }
    }

    private func load() async {
        guard let url else { image = nil; return }
        let key = url as NSURL
        if let cached = Self.cachedImages.object(forKey: key) {
            image = cached
            return
        }
        image = nil
        let request = URLRequest(url: url, timeoutInterval: 20)
        guard let data = try? await network.data(for: request, limit: 8 * 1024 * 1024),
              !Task.isCancelled, let loaded = UIImage(data: data) else { return }
        Self.cachedImages.setObject(loaded, forKey: key)
        image = loaded
    }
}
