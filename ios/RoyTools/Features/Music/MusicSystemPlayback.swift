import AVFoundation
import MediaPlayer
import UIKit

@MainActor
final class MusicSystemPlayback {
    private weak var player: MusicPlayer?
    private let commands = MPRemoteCommandCenter.shared()
    private let infoCenter = MPNowPlayingInfoCenter.default()
    private var targets: [(MPRemoteCommand, Any)] = []
    private var observers: [NSObjectProtocol] = []
    private var artwork: MPMediaItemArtwork?
    private var artworkURL: URL?
    private var interruptedTrack: UUID?

    init(player: MusicPlayer) {
        self.player = player
        bind(commands.playCommand) { $0.resume() }
        bind(commands.pauseCommand) { $0.pause() }
        bind(commands.togglePlayPauseCommand) { $0.toggle() }
        bind(commands.previousTrackCommand) { $0.previous() }
        bind(commands.nextTrackCommand) { $0.next() }
        let seek = commands.changePlaybackPositionCommand
        let target = seek.addTarget { @Sendable [weak player] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent,
                  event.positionTime.isFinite else { return .commandFailed }
            let time = event.positionTime
            Task { @MainActor in player?.seek(to: time) }
            return .success
        }
        targets.append((seek, target))
        let session = AVAudioSession.sharedInstance()
        observers.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: .main
        ) { [weak self] note in
            guard let value = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: value) else { return }
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            MainActor.assumeIsolated { self?.handleInterruption(type, options: .init(rawValue: options)) }
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: session, queue: .main
        ) { [weak player] note in
            guard let value = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: value) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { player?.pause() }
        })
    }

    deinit {
        for (command, target) in targets { command.removeTarget(target) }
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    private func bind(_ command: MPRemoteCommand, action: @escaping @MainActor @Sendable (MusicPlayer) -> Void) {
        let target = command.addTarget { @Sendable [weak player] _ in
            Task { @MainActor in if let player { action(player) } }
            return .success
        }
        targets.append((command, target))
    }

    func clearInterruptedTrack() { interruptedTrack = nil }

    private func handleInterruption(_ type: AVAudioSession.InterruptionType, options: AVAudioSession.InterruptionOptions) {
        guard let player else { return }
        if type == .began, player.isPlaying || player.isPreparing {
            player.pause()
            interruptedTrack = player.playbackID
        } else if type == .ended {
            let shouldResume = interruptedTrack == player.playbackID && options.contains(.shouldResume)
            interruptedTrack = nil
            if shouldResume { player.resume() }
        }
    }

    func setArtwork(_ image: UIImage, for url: URL) {
        artworkURL = url
        artwork = MPMediaItemArtwork(boundsSize: image.size) { @Sendable size in
            image.preparingThumbnail(of: size) ?? image
        }
        update()
    }

    func update() {
        guard let player, let track = player.track else { return }
        if artworkURL != track.coverURL {
            artwork = nil
            artworkURL = nil
        }
        let duration = player.duration
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPNowPlayingInfoPropertyExternalContentIdentifier: track.id,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.position,
            MPNowPlayingInfoPropertyPlaybackRate: player.systemPlaybackRate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyPlaybackQueueCount: player.queue.count
        ]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let index = player.currentIndex { info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = index }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        commands.playCommand.isEnabled = true
        commands.pauseCommand.isEnabled = true
        commands.togglePlayPauseCommand.isEnabled = true
        commands.previousTrackCommand.isEnabled = player.canPlayPrevious
        commands.nextTrackCommand.isEnabled = player.canPlayNext
        commands.changePlaybackPositionCommand.isEnabled = duration > 0
        infoCenter.nowPlayingInfo = info
    }
}
