import AVFoundation
import Foundation
import Observation

@MainActor
@Observable
final class MusicPlayer {
    private(set) var track: MusicTrack?
    private(set) var isPlaying = false
    private(set) var isPreparing = false
    private(set) var errorMessage: String?

    private let player = AVPlayer()
    @ObservationIgnored private var queue: [MusicTrack] = []
    @ObservationIgnored private var currentIndex: Int?
    @ObservationIgnored private var statusObserver: NSKeyValueObservation?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var preparation: Task<Void, Never>?

    var position: Double {
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, seconds) : 0
    }

    var duration: Double {
        let seconds = player.currentItem?.duration.seconds ?? 0
        if seconds.isFinite, seconds > 0 { return seconds }
        return max(0, track?.duration ?? 0)
    }

    func play(_ selection: MusicTrack, in tracks: [MusicTrack]) {
        queue = tracks
        currentIndex = tracks.firstIndex { $0.id == selection.id }
        playTrack(selection)
    }

    func toggle() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else if isPreparing {
            preparation?.cancel()
            preparation = nil
            isPreparing = false
        } else if let track {
            guard let item = player.currentItem, item.status != .failed else {
                playTrack(track)
                return
            }
            if duration > 0, position >= duration - 0.1 { seek(to: 0) }
            beginPlayback()
        }
    }

    func seek(to position: Double) {
        guard position.isFinite else { return }
        player.seek(to: CMTime(seconds: max(0, position), preferredTimescale: 600))
    }

    func previous() {
        if position > 3 { seek(to: 0); return }
        step(by: -1)
    }

    func next() { step(by: 1) }

    private func step(by offset: Int) {
        guard let index = currentIndex, queue.indices.contains(index + offset) else { return }
        currentIndex = index + offset
        playTrack(queue[index + offset])
    }

    private func playTrack(_ selection: MusicTrack) {
        guard let url = selection.audioURL else { return }
        preparation?.cancel()
        preparation = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        track = selection
        isPlaying = false
        isPreparing = false
        errorMessage = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        statusObserver = nil

        let network = MusicNetwork.shared
        guard network.requiresProxy(for: url) else {
            playItem(at: url)
            return
        }
        isPreparing = true
        preparation = Task { [weak self] in
            do {
                let local = try await network.localAudioURL(for: url)
                try Task.checkCancellation()
                guard let self else { return }
                self.preparation = nil
                self.isPreparing = false
                self.playItem(at: local)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.preparation = nil
                self.isPreparing = false
                self.errorMessage = error.localizedDescription
            }
        }
    }

    private func playItem(at url: URL) {
        let item = AVPlayerItem(url: url)
        statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === item else { return }
                self.isPlaying = false
                self.errorMessage = "暂时无法播放这首歌"
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === item else { return }
                self.isPlaying = false
                self.next()
            }
        }
        player.replaceCurrentItem(with: item)
        beginPlayback()
    }

    private func beginPlayback() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            player.play()
            errorMessage = nil
            isPlaying = true
        } catch {
            errorMessage = "无法开始音频播放"
            isPlaying = false
        }
    }
}
