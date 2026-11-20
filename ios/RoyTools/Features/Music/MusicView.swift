import ImageIO
import SwiftUI
import UIKit

struct MusicView: View {
    let catalog: MusicCatalogStore
    let player: MusicPlayer
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        NavigationStack {
            home
                .navigationTitle("音乐")
                .task { await catalog.loadLocalCatalog() }
                .onChange(of: catalog.catalog?.version, initial: true) { _, _ in
                    if let current = catalog.catalog { player.restoreSession(from: current.tracks) }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                    player.saveSession()
                }
        }
    }

    @ViewBuilder
    private var home: some View {
        if UIDevice.current.userInterfaceIdiom == .pad, let current = catalog.catalog {
            GeometryReader { geometry in
                let columns = geometry.size.width >= 620 && !dynamicTypeSize.isAccessibilitySize ? 2 : 1
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 16), count: columns),
                              spacing: 16) {
                        songsEntry(current, tile: true)
                        albumsEntry(current, tile: true)
                        playlistsEntry(current, tile: true)
                        settingsEntry(tile: true)
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 20)
                    .frame(maxWidth: 820)
                    .frame(maxWidth: .infinity)
                }
            }
            .background(Color(uiColor: .systemGroupedBackground))
        } else {
            List {
                if let current = catalog.catalog {
                    Section { songsEntry(current) }
                    Section { albumsEntry(current) }
                    Section { playlistsEntry(current) }
                } else {
                    Section {
                        if catalog.isLoading {
                            ProgressView(catalog.isRefreshing ? "正在检查更新" : "正在加载曲库")
                        } else {
                            ContentUnavailableView("暂无曲库", systemImage: "music.note")
                                .listRowBackground(Color.clear)
                            Button("检查曲库更新") { Task { await catalog.checkForUpdates() } }
                        }
                    } footer: {
                        if !catalog.status.isEmpty { Text(catalog.status) }
                    }
                }
                Section { settingsEntry() }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(11)
            .iPadContentWidth(680)
        }
    }

    private func songsEntry(_ catalog: MusicCatalog, tile: Bool = false) -> some View {
        homeEntry("全部歌曲", icon: "music.note.list", detail: "搜索歌曲、歌手和专辑", tile: tile) {
            TrackList(title: "全部歌曲", tracks: catalog.tracks, player: player, catalog: catalog)
        }
    }

    private func albumsEntry(_ catalog: MusicCatalog, tile: Bool = false) -> some View {
        homeEntry("专辑", icon: "opticaldisc", detail: "按专辑浏览私人乐库", tile: tile) {
            AlbumsView(catalog: catalog, player: player)
        }
    }

    private func playlistsEntry(_ catalog: MusicCatalog, tile: Bool = false) -> some View {
        homeEntry("歌单", icon: "list.bullet", detail: "收藏与云端歌单", tile: tile) {
            PlaylistsView(catalog: catalog, player: player)
        }
    }

    private func settingsEntry(tile: Bool = false) -> some View {
        homeEntry("设置", icon: "slider.horizontal.3", detail: "缓存与预加载", tile: tile) {
            MusicSettingsView(catalog: catalog, player: player)
        }
    }

    @ViewBuilder
    private func homeEntry<Destination: View>(
        _ title: String, icon: String, detail: String, tile: Bool,
        @ViewBuilder destination: () -> Destination
    ) -> some View {
        let link = NavigationLink {
            destination()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 42, height: 42)
                    .background(Color.accentColor.opacity(0.10), in: .rect(cornerRadius: 12))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
                if tile {
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
            }
            .padding(.vertical, tile ? 16 : 9)
            .padding(.horizontal, tile ? 18 : 0)
            .frame(minHeight: tile ? 92 : nil)
            .background {
                if tile {
                    RoundedRectangle(cornerRadius: 22)
                        .fill(Color(uiColor: .secondarySystemGroupedBackground))
                }
            }
            .contentShape(.rect(cornerRadius: tile ? 22 : 0))
        }
        if tile { link.buttonStyle(.plain) }
        else { link }
    }
}

enum MusicLibraryDestination: Hashable {
    case album(String)
    case artist(String)
}

struct MusicLibraryDestinationView: View {
    let destination: MusicLibraryDestination
    let catalog: MusicCatalog
    let player: MusicPlayer

    var body: some View {
        switch destination {
        case .album(let id):
            if let album = MusicAlbum.collection(catalog.tracks).first(where: { $0.id == id }) {
                TrackList(title: album.title, tracks: album.tracks, player: player, catalog: catalog, album: album)
            } else {
                ContentUnavailableView("暂无专辑", systemImage: "rectangle.stack")
            }
        case .artist(let artist):
            AlbumsView(catalog: catalog, player: player, artist: artist)
        }
    }
}

struct TrackList: View {
    let title: String
    let tracks: [MusicTrack]
    let player: MusicPlayer
    let catalog: MusicCatalog
    var album: MusicAlbum?
    var playlist: MusicPlaylistDetail?
    @State private var search = ""
    @State private var filters: CollectionFilters
    @State private var destination: MusicLibraryDestination?
    @State private var showsDownload = false
    @State private var trackDownload: MusicTrack?
    @State private var showsCollectionDownload = false
    @State private var locateCurrent = false
    @State private var favoriteNotice = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(title: String, tracks: [MusicTrack], player: MusicPlayer, catalog: MusicCatalog,
         album: MusicAlbum? = nil, playlist: MusicPlaylistDetail? = nil) {
        self.title = title
        self.tracks = tracks
        self.player = player
        self.catalog = catalog
        self.album = album
        self.playlist = playlist
        _filters = State(initialValue: CollectionFilters(sort: album == nil && playlist == nil ? .release : .original))
    }

    private var artists: [String] { CollectionFilters.artists(in: tracks) }

    var body: some View {
        let current = filters.visibleTracks(tracks, search: search)
        let favorites = catalog.favoriteTrackIDs
        let unavailable = playlist?.unavailable.filter { item in
            filters.matches(query: search, parts: [item.entry.title ?? "", item.entry.artist ?? "", item.entry.album ?? ""])
                && (filters.artist.isEmpty || item.entry.artist == filters.artist)
        } ?? []
        ScrollViewReader { scroll in
            List {
                if let album {
                    Section {
                        albumHeader(album, visible: current)
                            .listRowInsets(.init(top: 12, leading: 14, bottom: 12, trailing: 14))
                    }
                } else if let playlist {
                    Section {
                        playlistHeader(playlist, visible: current)
                            .listRowInsets(.init(top: 12, leading: 14, bottom: 12, trailing: 14))
                    }
                }

                Section {
                    CollectionFilterHeader(search: $search, filters: $filters, artists: album == nil ? artists : [],
                                           showsSelectors: album == nil, allowsOriginal: playlist != nil,
                                           count: album == nil ? "\(current.count + unavailable.count)首歌曲" : nil)
                        .id("top")
                        .listRowInsets(.init(top: album == nil ? 8 : 4, leading: 0,
                                            bottom: album == nil ? 6 : 4, trailing: 0))
                        .listRowBackground(Color.clear)
                }
                Section {
                    if current.isEmpty && unavailable.isEmpty {
                        if filters.artist.isEmpty && search.isEmpty {
                            ContentUnavailableView("暂无歌曲", systemImage: "music.note.list")
                        } else {
                            ContentUnavailableView.search(text: search)
                        }
                    }
                    ForEach(Array(current.enumerated()), id: \.offset) { index, track in
                        trackRow(track, number: index + 1, favorite: favorites.contains(track.id)) {
                            player.play(index, in: current)
                        }
                        .id(index)
                    }
                    ForEach(unavailable) { item in
                        unavailableRow(item.entry)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(8)
            .contentMargins(.top, album != nil || playlist != nil ? 8 : nil, for: .scrollContent)
            .iPadContentWidth(840)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) { MusicQueueFeedback(player: player) }
            .toolbar {
                if playlist != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        playlistLocationMenu(current, scroll: scroll)
                    }
                }
            }
            .onChange(of: current.map(\.id)) { _, ids in
                if locateCurrent, let index = ids.firstIndex(where: { $0 == player.track?.id }) {
                    jump(to: index, in: scroll)
                    locateCurrent = false
                }
            }
        }
        .navigationDestination(item: $destination) {
            MusicLibraryDestinationView(destination: $0, catalog: catalog, player: player)
        }
        .alert("下载歌曲？", isPresented: $showsDownload, presenting: trackDownload) { track in
            Button("取消", role: .cancel) {}
            Button("确认下载") { MusicDownloads.shared.start([track]) }
        } message: { track in
            Text(track.title)
        }
        .alert("下载全部曲目？", isPresented: $showsCollectionDownload) {
            Button("取消", role: .cancel) {}
            Button("确认下载") { MusicDownloads.shared.start(tracks, includingAssets: true) }
        } message: {
            Text("将下载缺少的曲目、对应歌词和专辑封面。")
        }
        .modifier(MusicFavoriteNotice(trigger: favoriteNotice))
    }

    private func trackRow(_ track: MusicTrack, number: Int, favorite: Bool, play: @escaping () -> Void) -> some View {
        HStack(spacing: 9) {
            Button(action: play) {
                HStack(spacing: 8) {
                    Group {
                        if player.track?.id == track.id {
                            Image(systemName: "waveform").foregroundStyle(Color.accentColor)
                                .accessibilityLabel("当前歌曲")
                        } else {
                            Text(number < 10 ? "0\(number)" : "\(number)")
                                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    .frame(minWidth: 23)
                    MusicCover(url: track.coverURL, edge: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(subtitle(for: track)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                player.insertNext(track)
            } label: {
                Label("下一首播放", systemImage: "text.badge.plus")
                    .labelStyle(.iconOnly)
                    .frame(width: 37, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("下一首播放：\(track.title)")

            Button {
                favoriteNotice += 1
            } label: {
                Label(favorite ? "Apple Music已喜欢" : "Apple Music未喜欢",
                      systemImage: favorite ? "heart.fill" : "heart")
                    .labelStyle(.iconOnly)
                    .foregroundStyle(favorite ? Color.accentColor : Color.secondary)
                    .frame(width: 37, height: 44)
            }
            .buttonStyle(.borderless)

            Menu {
                ShareLink(item: track.shareURL) { Label("分享歌曲", systemImage: "square.and.arrow.up") }
                Button {
                    trackDownload = track
                    showsDownload = true
                } label: { Label("下载", systemImage: "arrow.down.circle") }
                Button {
                    destination = .album(track.albumID)
                } label: { Label("查看专辑", systemImage: "rectangle.stack") }
                artistMenu(track)
            } label: {
                Label("更多", systemImage: "ellipsis").labelStyle(.iconOnly)
                    .frame(width: 37, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("\(track.title)的更多选项")
        }
    }

    @ViewBuilder
    private func artistMenu(_ track: MusicTrack) -> some View {
        if track.artists.count == 1, let artist = track.artists.first {
            Button { destination = .artist(artist) } label: { Label("查看歌手", systemImage: "person") }
        } else if !track.artists.isEmpty {
            Menu("查看歌手", systemImage: "person") {
                ForEach(track.artists, id: \.self) { artist in
                    Button(artist) { destination = .artist(artist) }
                }
            }
        }
    }

    private func subtitle(for track: MusicTrack) -> String {
        [track.artist, track.duration.map(musicTime) ?? ""]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func albumHeader(_ album: MusicAlbum, visible: [MusicTrack]) -> some View {
        let summary = HStack(spacing: 14) {
            MusicCover(url: album.cover, edge: 90)
            VStack(alignment: .leading, spacing: 4) {
                Text(album.title).font(.title3.bold())
                    .fixedSize(horizontal: false, vertical: true)
                Button(album.artist) { destination = .artist(album.artist) }
                    .buttonStyle(.plain).foregroundStyle(Color.accentColor).font(.subheadline)
                let year = album.year.flatMap { $0 > 0 ? String($0) : nil }
                Text([year, "\(album.tracks.count)首"].compactMap { $0 }.joined(separator: " · "))
                    .foregroundStyle(.secondary).font(.footnote)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        let actions = collectionActions {
            MusicActionButton(primary: true, fillsWidth: true, title: "播放专辑", symbol: "play.fill") {
                player.playCollection(visible)
            }
            .disabled(visible.isEmpty)
            downloadAll
        }
        let stacked = VStack(alignment: .leading, spacing: 12) {
            summary
            actions
        }
        return Group {
            if UIDevice.current.userInterfaceIdiom == .pad && !dynamicTypeSize.isAccessibilitySize {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 20) {
                        summary.frame(minWidth: 270)
                        actions.frame(width: 260)
                    }
                    stacked
                }
            } else {
                stacked
            }
        }
    }

    private var downloadAll: some View {
        MusicActionButton(fillsWidth: true, title: "下载全部", symbol: "arrow.down.to.line") {
            showsCollectionDownload = true
        }
        .disabled(tracks.isEmpty)
    }

    private func playlistHeader(_ playlist: MusicPlaylistDetail, visible: [MusicTrack]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(tracks.count + playlist.unavailable.count)首歌曲")
                .font(.subheadline).foregroundStyle(.secondary)
            collectionActions {
                MusicActionButton(primary: true, fillsWidth: true, title: "顺序播放", symbol: "play.fill") {
                    player.playCollection(visible)
                }
                .disabled(visible.isEmpty)
                MusicActionButton(fillsWidth: true, title: "随机播放", symbol: "shuffle") {
                    player.playCollection(visible, shuffled: true)
                }
                .disabled(visible.isEmpty)
                downloadAll
            }
            .frame(maxWidth: UIDevice.current.userInterfaceIdiom == .pad
                               && !dynamicTypeSize.isAccessibilitySize ? 480 : nil, alignment: .leading)
            if let updated = playlist.updatedAt {
                Text("更新于\(updated.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func collectionActions<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout { content() }
    }

    private func unavailableRow(_ entry: MusicCatalog.AppleMusic.Entry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.title ?? "未命名歌曲").foregroundStyle(.secondary)
            if let artist = entry.artist { Text(artist).font(.subheadline).foregroundStyle(.secondary) }
            if let address = entry.listeningURL {
                Link(destination: address) {
                    Label(entry.sourceStatus == "purchasable" ? "待购入" : "外部收听", systemImage: "arrow.up.right")
                }
                .font(.caption)
            } else {
                Text("待补音源").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func playlistLocationMenu(_ current: [MusicTrack], scroll: ScrollViewProxy) -> some View {
        Menu("快速定位", systemImage: "location") {
            Button("回到顶部", systemImage: "arrow.up.to.line") { jump(to: "top", in: scroll) }
            if let id = player.track?.id, tracks.contains(where: { $0.id == id }) {
                Button("定位当前曲目", systemImage: "waveform") {
                    if let index = current.firstIndex(where: { $0.id == id }) {
                        jump(to: index, in: scroll)
                    } else {
                        locateCurrent = true
                        search = ""
                        filters.artist = ""
                    }
                }
            }
        }
    }

    private func jump<ID: Hashable>(to id: ID, in scroll: ScrollViewProxy) {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { scroll.scrollTo(id, anchor: .center) }
    }
}

struct MusicAlbum: Identifiable {
    let id: String
    let title: String
    let artist: String
    let year: Int?
    let cover: URL?
    let tracks: [MusicTrack]
    let category: String

    var details: String {
        let yearText = year.flatMap { $0 > 0 ? String($0) : nil }
        return [yearText, "\(tracks.count)首", category].compactMap { $0 }.joined(separator: " · ")
    }

    static func collection(_ tracks: [MusicTrack]) -> [Self] {
        let groups = Dictionary(grouping: tracks, by: \.albumID)
        var seen: Set<String> = []
        return tracks.compactMap { track in
            let id = track.albumID
            guard seen.insert(id).inserted, let group = groups[id] else { return nil }
            let ordered = group.sorted {
                ($0.discNumber ?? 0, $0.trackNumber ?? 0) < ($1.discNumber ?? 0, $1.trackNumber ?? 0)
            }
            let category: String
            switch track.albumCategory {
            case "live": category = "现场录音"
            case "collections": category = "精选合集"
            default: category = "专辑/单曲"
            }
            return Self(id: id, title: track.album, artist: track.collectionArtist, year: track.albumYear,
                        cover: ordered.first?.coverURL, tracks: ordered, category: category)
        }
    }
}

private struct AlbumsView: View {
    let catalog: MusicCatalog
    let player: MusicPlayer
    let artist: String?
    @State private var search = ""
    @State private var filters: CollectionFilters
    @State private var albumDownload: MusicAlbum?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(catalog: MusicCatalog, player: MusicPlayer, artist: String? = nil) {
        self.catalog = catalog
        self.player = player
        self.artist = artist
        _filters = State(initialValue: CollectionFilters(artist: artist ?? "", sort: .release))
    }

    var body: some View {
        let albums = MusicAlbum.collection(catalog.tracks)
        let visible = filters.visibleAlbums(albums, search: search)
        let minimumTile: CGFloat = UIDevice.current.userInterfaceIdiom == .pad
            ? (dynamicTypeSize.isAccessibilitySize ? 235 : 178)
            : 142
        ScrollView {
            VStack(spacing: 12) {
                CollectionFilterHeader(search: $search, filters: $filters, artists: CollectionFilters.artists(in: catalog.tracks),
                                       defaultArtist: artist ?? "", count: "\(visible.count)张专辑")
                if visible.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: minimumTile), spacing: 16)], spacing: 20) {
                    ForEach(visible) { album in albumTile(album) }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
        .iPadContentWidth(1080)
        .navigationTitle(artist ?? "专辑")
        .navigationBarTitleDisplayMode(.inline)
        .alert("下载专辑？", isPresented: Binding(
            get: { albumDownload != nil }, set: { if !$0 { albumDownload = nil } }
        ), presenting: albumDownload) { album in
            Button("取消", role: .cancel) {}
            Button("确认下载") { MusicDownloads.shared.start(album.tracks, includingAssets: true) }
        } message: { album in
            Text("将下载《\(album.title)》中缺少的曲目、歌词和封面。")
        }
    }

    private func albumTile(_ album: MusicAlbum) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack(alignment: .bottomTrailing) {
                albumLink(album) {
                    MusicCover(url: album.cover, edge: nil)
                }
                let play = Button {
                    player.playCollection(album.tracks)
                } label: {
                    Image(systemName: "play.fill").frame(minWidth: 24, minHeight: 24)
                }
                .buttonBorderShape(.circle)
                .accessibilityLabel("播放专辑\(album.title)")
                Group {
                    if #available(iOS 26.0, *) { play.buttonStyle(.glassProminent) }
                    else { play.buttonStyle(.borderedProminent) }
                }
                .padding(8)
            }
            albumLink(album) {
                Text(album.title).font(.headline).lineLimit(1)
            }
            Text(album.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            Text(album.details).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func albumLink<Label: View>(_ album: MusicAlbum, @ViewBuilder label: () -> Label) -> some View {
        NavigationLink {
            TrackList(title: album.title, tracks: album.tracks, player: player, catalog: catalog, album: album)
        } label: {
            label()
        }
        .buttonStyle(.plain)
        .accessibilityLabel("查看专辑\(album.title)")
        .contextMenu {
            Button("播放专辑", systemImage: "play.fill") { player.playCollection(album.tracks) }
            Button("下载全部", systemImage: "arrow.down.circle") { albumDownload = album }
        }
    }
}

struct MusicPlaylistDetail {
    struct Unavailable: Identifiable {
        let id: Int
        let entry: MusicCatalog.AppleMusic.Entry
    }

    let unavailable: [Unavailable]
    let updatedAt: Date?
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
        .listStyle(.insetGrouped)
        .iPadContentWidth(760)
        .navigationTitle("歌单")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func playlist(title: String, icon: String, items: [String]) -> some View {
        let tracks = catalog.playlistTracks(items)
        let knownIDs = Set(catalog.tracks.map(\.id))
        let unavailable = items.enumerated().compactMap { index, id -> MusicPlaylistDetail.Unavailable? in
            guard let entry = catalog.appleMusic.entries[id],
                  entry.trackId.map({ !knownIDs.contains($0) }) ?? true else { return nil }
            return .init(id: index, entry: entry)
        }
        let updated = catalog.appleMusic.updatedAt.flatMap(Self.appleDate)
        return NavigationLink {
            TrackList(title: title, tracks: tracks, player: player, catalog: catalog,
                      playlist: MusicPlaylistDetail(unavailable: unavailable, updatedAt: updated))
        } label: {
            LabeledContent {
                Text("\(tracks.count + unavailable.count)首")
            } label: {
                Label(title, systemImage: icon)
            }
            .padding(.vertical, 7)
        }
    }

    private static func appleDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }
}

private struct CollectionFilters {
    enum Sort: String, CaseIterable {
        case original = "默认排序"
        case release = "发行时间"
        case artist = "歌手"
        case name = "名称"
    }

    var artist = ""
    var sort = Sort.original
    var secondary: Sort?
    var descending = false

    func matches(query: String, parts: [String]) -> Bool {
        let query = normalized(query.trimmingCharacters(in: .whitespacesAndNewlines))
        return query.isEmpty || normalized(parts.joined(separator: " ")).contains(query)
    }

    func visibleTracks(_ tracks: [MusicTrack], search: String) -> [MusicTrack] {
        let matching = tracks.filter {
            (artist.isEmpty || $0.matchesArtist(artist))
                && matches(query: search, parts: [$0.title, $0.artist, $0.collectionArtist, $0.album])
        }
        return ordered(matching) { ($0.title, $0.artist, $0.albumYear) }
    }

    func visibleAlbums(_ albums: [MusicAlbum], search: String) -> [MusicAlbum] {
        let matching = albums.filter { album in
            album.tracks.contains {
                (artist.isEmpty || $0.matchesArtist(artist))
                    && matches(query: search, parts: [$0.title, $0.artist, album.artist, album.title])
            }
        }
        return ordered(matching) { ($0.title, $0.artist, $0.year) }
    }

    private func ordered<Item>(_ items: [Item], fields: (Item) -> (name: String, artist: String, year: Int?)) -> [Item] {
        guard sort != .original else { return items }
        return items.sorted { lhs, rhs in
            let first = fields(lhs)
            let last = fields(rhs)
            func compare(_ key: Sort) -> ComparisonResult {
                let result: ComparisonResult
                switch key {
                case .original: return .orderedSame
                case .release:
                    let firstYear = first.year ?? 0
                    let lastYear = last.year ?? 0
                    if firstYear == 0 || lastYear == 0 {
                        if firstYear == lastYear { return .orderedSame }
                        return firstYear == 0 ? .orderedDescending : .orderedAscending
                    }
                    result = NSNumber(value: firstYear).compare(NSNumber(value: lastYear))
                case .artist: result = Self.compareText(first.artist, last.artist)
                case .name: result = Self.compareText(first.name, last.name)
                }
                if descending && result != .orderedSame {
                    return result == .orderedAscending ? .orderedDescending : .orderedAscending
                }
                return result
            }
            let primary = compare(sort)
            let result = primary == .orderedSame ? secondary.map(compare) ?? .orderedSame : primary
            return result == .orderedAscending
        }
    }

    static func artists(in tracks: [MusicTrack]) -> [String] {
        Set(tracks.flatMap { $0.artists + [$0.collectionArtist] }).filter { !$0.isEmpty }
            .sorted { compareText($0, $1) == .orderedAscending }
    }

    private func normalized(_ text: String) -> String {
        text.precomposedStringWithCompatibilityMapping.lowercased()
    }

    private static func compareText(_ lhs: String, _ rhs: String) -> ComparisonResult {
        lhs.compare(rhs, options: .numeric, locale: Locale(identifier: "zh-CN"))
    }
}

private struct CollectionFilterHeader: View {
    @Binding var search: String
    @Binding var filters: CollectionFilters
    let artists: [String]
    var showsSelectors = true
    var allowsOriginal = false
    var defaultArtist = ""
    let count: String?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    clear
                    searchField
                }
                VStack(alignment: .leading, spacing: 8) {
                    clear
                    searchField
                }
            }
            if showsSelectors {
                filterSelectors
            }
            if let count {
                Text(count)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .onChange(of: filters.sort) { _, sort in
            if sort == .original || filters.secondary == sort { filters.secondary = nil }
            if sort == .original { filters.descending = false }
        }
    }

    @ViewBuilder
    private var filterSelectors: some View {
        if UIDevice.current.userInterfaceIdiom == .pad && !dynamicTypeSize.isAccessibilitySize {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    artistSelector.frame(minWidth: 132)
                    directionSelector.frame(minWidth: 104)
                    primarySelector.frame(minWidth: 132)
                    secondarySelector.frame(minWidth: 132)
                }
                stackedSelectors
            }
        } else {
            stackedSelectors
        }
    }

    private var stackedSelectors: some View {
        VStack(spacing: 13) {
            HStack(spacing: 10) {
                artistSelector
                directionSelector
            }
            HStack(spacing: 10) {
                primarySelector
                secondarySelector
            }
        }
    }

    private var artistSelector: some View {
        selector("歌手", value: filters.artist.isEmpty ? "全部歌手" : filters.artist,
                 selection: $filters.artist, disabled: !defaultArtist.isEmpty) {
            Text("全部歌手").tag("")
            ForEach(artists, id: \.self) { Text($0).tag($0) }
        }
    }

    private var directionSelector: some View {
        selector("方向", value: filters.descending ? "降序" : "升序",
                 selection: $filters.descending, disabled: filters.sort == .original) {
            Text("升序").tag(false)
            Text("降序").tag(true)
        }
    }

    private var primarySelector: some View {
        selector("第一顺序", value: filters.sort.rawValue, selection: $filters.sort) {
            ForEach(CollectionFilters.Sort.allCases.filter { allowsOriginal || $0 != .original }, id: \.self) {
                Text($0.rawValue).tag($0)
            }
        }
    }

    private var secondarySelector: some View {
        selector("第二顺序", value: filters.secondary?.rawValue ?? "不设置",
                 selection: $filters.secondary, disabled: filters.sort == .original) {
            Text("不设置").tag(nil as CollectionFilters.Sort?)
            ForEach(CollectionFilters.Sort.allCases.filter { $0 != .original && $0 != filters.sort }, id: \.self) {
                Text($0.rawValue).tag(Optional($0))
            }
        }
    }

    private var clear: some View {
        Group {
            if #available(iOS 26.0, *) { clearButton.buttonStyle(.glassProminent) }
            else { clearButton.buttonStyle(.borderedProminent) }
        }
        .fixedSize()
        .disabled(search.isEmpty && filters.artist == defaultArtist)
    }

    private var clearButton: some View {
        Button {
            search = ""
            filters.artist = defaultArtist
        } label: {
            Text("清空筛选")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .frame(minHeight: 30)
        }
        .controlSize(.regular)
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
            TextField("搜索歌曲、歌手、专辑", text: $search)
                .textFieldStyle(.plain)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .font(.subheadline)
            if !search.isEmpty {
                Button("清空搜索", systemImage: "xmark.circle.fill") { search = "" }
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
                    .buttonStyle(.borderless)
            }
        }
        .padding(.horizontal, 11)
        .frame(minHeight: 40)
        .modifier(FilterSurface())
    }

    private func selector<Value: Hashable, Options: View>(
        _ title: String, value: String, selection: Binding<Value>, disabled: Bool = false,
        @ViewBuilder options: () -> Options
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary).accessibilityHidden(true)
            Menu {
                Picker(title, selection: selection, content: options)
            } label: {
                HStack(spacing: 5) {
                    Text(verbatim: value)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 13)
                .frame(maxWidth: .infinity, minHeight: 40)
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .tint(.primary)
            .modifier(FilterSurface())
            .accessibilityLabel(title)
            .accessibilityValue(value)
            .disabled(disabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct FilterSurface: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: .capsule)
        } else {
            content.background(.background, in: .capsule)
        }
    }
}

struct MusicActionButton: View {
    var primary = false
    var fillsWidth = false
    let title: String
    let symbol: String
    let action: () -> Void

    private var control: some View {
        Button(action: action) {
            if fillsWidth {
                Label(title, systemImage: symbol)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(primary ? Color.white : Color.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, minHeight: 28)
            } else {
                Label(title, systemImage: symbol)
            }
        }
        .controlSize(fillsWidth ? .small : .regular)
    }

    @ViewBuilder
    var body: some View {
        if #available(iOS 26.0, *) {
            if primary { control.buttonStyle(.glassProminent) }
            else { control.buttonStyle(.glass) }
        } else {
            if primary { control.buttonStyle(.borderedProminent) }
            else { control.buttonStyle(.bordered) }
        }
    }
}

struct MusicFavoriteNotice: ViewModifier {
    let trigger: Int
    @State private var visible = false
    @State private var dismissal: Task<Void, Never>?
    private let message = "你不能点击，这个按钮仅限Roylyl操作"

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if visible {
                    Text(message)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 15)
                        .padding(.vertical, 11)
                        .modifier(FilterSurface())
                        .padding()
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .onChange(of: trigger) { _, _ in
                dismissal?.cancel()
                visible = true
                UIAccessibility.post(notification: .announcement, argument: message)
                dismissal = Task {
                    do { try await Task.sleep(for: .milliseconds(2500)) }
                    catch { return }
                    visible = false
                }
            }
            .onDisappear {
                dismissal?.cancel()
                visible = false
            }
    }
}

struct MusicQueueFeedback: View {
    let player: MusicPlayer

    var body: some View {
        if let message = player.queueUndoMessage {
            HStack(spacing: 9) {
                Text(message).font(.subheadline).lineLimit(2)
                Spacer(minLength: 10)
                Button("撤销", action: player.undoQueueChange).buttonStyle(.borderless)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .modifier(FilterSurface())
            .padding(.horizontal)
            .padding(.bottom, 6)
        }
    }
}

func musicTime(_ seconds: Double) -> String {
    let total = Int(max(0, min(seconds.isFinite ? seconds : 0, 359_999)).rounded(.down))
    return String(format: "%d:%02d", total / 60, total % 60)
}

@MainActor
struct MusicCover: View {
    let url: URL?
    let edge: CGFloat?
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?
    private let network = MusicNetwork.shared

    private static let cachedImages: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 64
        cache.totalCostLimit = 36 * 1024 * 1024
        return cache
    }()
    private static var cacheRevision = 0

    static func clearMemoryCache() {
        cacheRevision += 1
        cachedImages.removeAllObjects()
    }

    private struct Request: Equatable {
        let url: URL?
        let generation: Int
        let pixels: Int
    }

    var body: some View {
        let pixels = Int((edge ?? 320) * displayScale)
        Rectangle()
            .fill(.quaternary)
            .aspectRatio(1, contentMode: .fit)
            .frame(width: edge, height: edge)
            .overlay {
                if let image {
                    GeometryReader { geometry in
                        Image(uiImage: image).resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    }
                } else {
                    Image(systemName: "music.note").foregroundStyle(.secondary)
                }
            }
            .clipShape(.rect(cornerRadius: min(20, (edge ?? 100) * 0.16)))
            .accessibilityHidden(true)
            .task(id: Request(url: url, generation: network.generation, pixels: pixels)) {
                await load(pixels: pixels)
            }
    }

    private func load(pixels: Int) async {
        guard let url else { image = nil; return }
        let key = "\(pixels):\(url.absoluteString)" as NSString
        if let cached = Self.cachedImages.object(forKey: key) {
            image = cached
            return
        }
        if let data = MusicCache.cachedData(for: url, kind: .artwork), let cached = Self.thumbnail(data, pixels: pixels) {
            Self.remember(cached, for: key)
            image = cached
            return
        }
        let revision = Self.cacheRevision
        image = nil
        let request = URLRequest(url: url, timeoutInterval: 20)
        guard let data = try? await network.data(for: request, limit: MusicCache.artworkLimit),
              !Task.isCancelled, let loaded = Self.thumbnail(data, pixels: pixels) else { return }
        if revision == Self.cacheRevision {
            MusicCache.save(data, for: url, kind: .artwork)
            Self.remember(loaded, for: key)
        }
        image = loaded
    }

    private static func remember(_ image: UIImage, for key: NSString) {
        let bytes = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0
        cachedImages.setObject(image, forKey: key, cost: bytes)
    }

    static func thumbnail(_ data: Data, pixels: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [
            kCGImageSourceShouldCache: false
        ] as CFDictionary),
              let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: max(1, pixels)
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: bitmap)
    }
}
