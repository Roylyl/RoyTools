import Foundation
import Observation

@MainActor
final class MusicWidgetPlayback {
    static let shared = MusicWidgetPlayback()
    let player = MusicPlayer()

    private enum Action: String { case previous, play, pause, next }

    private enum PlaybackError: LocalizedError {
        case unavailable
        case audio(String)

        var errorDescription: String? {
            switch self {
            case .unavailable: "没有可播放的歌曲"
            case .audio(let message): message
            }
        }
    }

    private struct ArtworkRequest: Equatable {
        let trackID: String
        let url: URL
    }

    private var artworkRequest: ArtworkRequest?
    private var artworkTask: Task<Void, Never>?

    private init() {
        observePlayer()
        observeRouting()
    }

    static func perform(_ command: String) async throws {
        guard let action = Action(rawValue: command) else { return }
        try await shared.perform(action)
    }

    private func perform(_ action: Action) async throws {
        let vpn = VPNController.shared
        if action != .pause, vpn.mode == .music, !MusicNetwork.shared.isProxyReady {
            await vpn.restoreIfNeeded()
        }
        try Task.checkCancellation()

        let catalog = MusicCatalogStore.shared
        if player.track == nil {
            await catalog.loadLocalCatalog()
            try Task.checkCancellation()
            if let tracks = catalog.catalog?.tracks { player.restoreSession(from: tracks) }
        }

        switch action {
        case .play:
            if player.track == nil {
                let tracks = catalog.catalog?.tracks.filter { $0.audioURL != nil } ?? []
                guard !tracks.isEmpty else { throw PlaybackError.unavailable }
                player.play(0, in: tracks)
            } else if !player.isPlaying && !player.isPreparing {
                player.resume()
            }
        case .pause:
            player.pause()
        case .previous:
            player.previous()
        case .next:
            player.next()
        }

        let snapshot = makeSnapshot()
        if snapshot.trackID == nil {
            RoyToolsWidgetData.saveMusic(snapshot)
        } else {
            publish(snapshot)
        }
        if action != .pause {
            // A proxied track may need to finish downloading before AVPlayer can start.
            await player.waitForAudioPreparation()
            try Task.checkCancellation()
            publish(makeSnapshot())
            if let message = player.errorMessage { throw PlaybackError.audio(message) }
        }
    }

    private func makeSnapshot() -> WidgetMusicState {
        WidgetMusicState(trackID: player.track?.id, title: player.track?.title, artist: player.track?.artist,
                         playing: player.isPlaying || player.isPreparing,
                         canPrevious: player.canPlayPrevious, canNext: player.canPlayNext)
    }

    private func observePlayer() {
        let (snapshot, playingAudio) = withObservationTracking {
            (makeSnapshot(), player.isPlaying && !player.isBuffering)
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in self?.observePlayer() }
        }
        VPNController.shared.setBackgroundPlaybackActive(playingAudio)
        publish(snapshot)
    }

    private func observeRouting() {
        withObservationTracking {
            _ = MusicNetwork.shared.generation
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                self?.player.updateRouting()
                self?.observeRouting()
            }
        }
    }

    private func publish(_ snapshot: WidgetMusicState) {
        guard let trackID = snapshot.trackID else { return }
        RoyToolsWidgetData.saveMusic(snapshot)
        let request = player.track?.coverURL.map { ArtworkRequest(trackID: trackID, url: $0) }
        guard request != artworkRequest else { return }
        artworkTask?.cancel()
        artworkRequest = request
        artworkTask = nil
        if let request {
            artworkTask = Task { [weak self] in await self?.updateArtwork(for: request) }
        }
    }

    private func updateArtwork(for request: ArtworkRequest) async {
        let url = request.url
        var bytes = MusicCache.cachedData(for: url, kind: .artwork)
        if bytes == nil {
            let request = URLRequest(url: url, timeoutInterval: 20)
            bytes = try? await MusicNetwork.shared.data(for: request, limit: MusicCache.artworkLimit)
            if let bytes, !Task.isCancelled { MusicCache.save(bytes, for: url, kind: .artwork) }
        }
        guard !Task.isCancelled, artworkRequest == request, player.track?.id == request.trackID else { return }
        guard let bytes, let image = MusicCover.thumbnail(bytes, pixels: 108) else {
            artworkRequest = nil
            return
        }
        if let cover = MusicCover.thumbnail(bytes, pixels: 512) {
            player.setSystemArtwork(cover, for: url)
        }
        var snapshot = makeSnapshot()
        snapshot.artwork = image.jpegData(compressionQuality: 0.8)
        RoyToolsWidgetData.saveMusic(snapshot)
    }
}
