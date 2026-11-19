import Foundation
import SwiftUI

struct MiniPlayerView: View {
    let player: MusicPlayer
    let showPlayer: () -> Void

    var body: some View {
        if let track = player.track {
            HStack(spacing: 12) {
                Button(action: showPlayer) {
                    HStack(spacing: 9) {
                        MusicCover(url: track.coverURL, edge: 34)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("打开正在播放")
                Button {
                    player.toggle()
                } label: {
                    Image(systemName: player.isPlaying || player.isPreparing ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(player.isPreparing ? "取消准备播放" : (player.isPlaying ? "暂停" : "播放"))
            }
            .padding(.horizontal, 12)
        }
    }
}

struct NowPlayingView: View {
    @Environment(\.dismiss) private var dismiss
    let player: MusicPlayer
    @State private var scrubPosition: Double?

    var body: some View {
        NavigationStack {
            ScrollView {
                if let track = player.track {
                    VStack(spacing: 26) {
                        ViewThatFits(in: .horizontal) {
                            MusicCover(url: track.coverURL, edge: 300)
                            MusicCover(url: track.coverURL, edge: 220)
                        }
                        VStack(spacing: 6) {
                            Text(track.title).font(.title2.bold())
                            Text(track.artist).foregroundStyle(.secondary)
                        }
                        .multilineTextAlignment(.center)
                        timeline
                        HStack(spacing: 29) {
                            transport("backward.fill", label: "上一首", action: player.previous)
                            transport(player.isPlaying || player.isPreparing ? "pause.fill" : "play.fill",
                                      label: player.isPreparing ? "取消准备播放" : (player.isPlaying ? "暂停" : "播放"),
                                      action: player.toggle)
                            transport("forward.fill", label: "下一首", action: player.next)
                        }
                        if player.isPreparing {
                            ProgressView("正在准备播放")
                        } else if let error = player.errorMessage {
                            Text(error).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: 440)
                    .frame(maxWidth: .infinity)
                    .padding(24)
                }
            }
            .navigationTitle("正在播放")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onChange(of: player.track?.id) { _, _ in scrubPosition = nil }
        }
    }

    private var timeline: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let duration = max(1, player.duration)
            let position = min(duration, scrubPosition ?? player.position)
            VStack(spacing: 6) {
                Slider(value: Binding(get: { position }, set: { scrubPosition = $0 }),
                       in: 0...duration, onEditingChanged: { editing in
                    if editing {
                        scrubPosition = player.position
                    } else if let scrubPosition {
                        player.seek(to: min(duration, scrubPosition))
                        self.scrubPosition = nil
                    }
                })
                .disabled(player.duration <= 0)
                .accessibilityLabel("播放进度")
                HStack {
                    Text(time(position))
                    Spacer()
                    Text(time(player.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }

    private func transport(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.title2)
                .frame(minWidth: 44, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }

    private func time(_ seconds: Double) -> String {
        let total = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
