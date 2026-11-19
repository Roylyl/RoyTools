import SwiftUI

@main
@MainActor
struct RoyToolsApp: App {
    @AppStorage("appearance") private var appearance = Appearance.system
    @Environment(\.scenePhase) private var scenePhase
    @State private var player = MusicPlayer()
    @State private var catalog = MusicCatalogStore()
    @State private var vpn = VPNController()
    @State private var showsPlayer = false

    var body: some Scene {
        WindowGroup {
            content
                .preferredColorScheme(appearance.colorScheme)
                .sheet(isPresented: $showsPlayer) {
                    NowPlayingView(player: player)
                }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    IslandModel.shared.setForeground(phase == .active)
                    vpn.setForeground(phase == .active)
                }
                .task { await vpn.restore() }
        }
    }

    @ViewBuilder
    private var content: some View {
        if #available(iOS 26.0, *) {
            tabs.tabViewBottomAccessory {
                if player.track != nil {
                    MiniPlayerView(player: player) { showsPlayer = true }
                }
            }
        } else {
            tabs.safeAreaInset(edge: .bottom, spacing: 0) {
                if player.track != nil {
                    MiniPlayerView(player: player) { showsPlayer = true }
                        .background(.bar)
                }
            }
        }
    }

    private var tabs: some View {
        TabView {
            MusicView(catalog: catalog, player: player)
                .tabItem { Label("音乐", systemImage: "music.note") }
            NavigationStack { ProxyView(vpn: vpn) }
                .tabItem { Label("代理", systemImage: "globe") }
            NavigationStack { IslandView() }
                .tabItem { Label("灵动岛", systemImage: "capsule") }
            SettingsView(appearance: $appearance)
                .tabItem { Label("设置", systemImage: "gearshape") }
        }
    }
}
