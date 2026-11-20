import SwiftUI
import UIKit

@main
@MainActor
struct RoyToolsApp: App {
    private enum Destination: CaseIterable, Identifiable {
        case music
        #if ROYTOOLS_MAC_PROXY
        case proxy
        #endif
        case settings

        var id: Self { self }

        var label: Label<Text, Image> {
            switch self {
            case .music: Label("音乐", image: "TabMusic")
            #if ROYTOOLS_MAC_PROXY
            case .proxy: Label("代理", image: "TabProxy")
            #endif
            case .settings: Label("设置", image: "TabSettings")
            }
        }
    }

    @AppStorage("appearance") private var appearance = Appearance.system
    @Environment(\.scenePhase) private var scenePhase
    @State private var player = MusicPlayer.shared
    @State private var catalog = MusicCatalogStore.shared
    #if ROYTOOLS_MAC_PROXY
    @State private var proxy = VPNController.shared
    #endif
    @State private var destination: Destination = .music
    @State private var showsPlayer = false
    @State private var showsLaunchTransition = true
    @State private var favoritesRequest = 0
    @State private var quitting = false

    private var onPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    init() {
        RoyToolsDiagnosticLog.record(.app, "RoyTools启动")
    }

    var body: some Scene {
        WindowGroup {
            LaunchTransition(isPresented: $showsLaunchTransition) { content }
                .preferredColorScheme(appearance.colorScheme)
                .fullScreenCover(isPresented: $showsPlayer) {
                    NowPlayingView(player: player, catalog: catalog)
                }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    #if ROYTOOLS_MAC_PROXY
                    proxy.setForeground(phase == .active)
                    #endif
                    if phase == .active {
                        RoyToolsDiagnosticLog.record(.app, "应用进入前台")
                    }
                    if phase == .background {
                        showsLaunchTransition = false
                        RoyToolsDiagnosticLog.record(.app, "应用进入后台")
                    }
                }
                .onChange(of: menuState, initial: true) { _, state in
                    MacMenuBridge.install()
                    MacMenuBridge.publish(state)
                }
                .onReceive(NotificationCenter.default.publisher(for: MacMenuBridge.action)) { notice in
                    guard let action = notice.userInfo?["action"] as? String else { return }
                    Task { await handleMenu(action) }
                }
                #if ROYTOOLS_MAC_PROXY
                .task { await proxy.restoreIfNeeded() }
                #endif
        }
    }

    private var menuState: MacMenuBridge.State {
        var state = MacMenuBridge.State(playing: player.isPlaying || player.isPreparing)
        #if ROYTOOLS_MAC_PROXY
        state.proxyOn = proxy.isEnabled
        state.proxyBusy = proxy.busy || proxy.isTransitioning || quitting
        state.canProxy = true
        #endif
        return state
    }

    private func handleMenu(_ action: String) async {
        switch action {
        case "toggleProxy":
            #if ROYTOOLS_MAC_PROXY
            await proxy.restoreIfNeeded()
            if proxy.profile != nil || proxy.isEnabled {
                await proxy.toggle(!proxy.isEnabled)
                if proxy.errorMessage != nil {
                    destination = .proxy
                    MacMenuBridge.show()
                }
            } else {
                showsPlayer = false
                destination = .proxy
                MacMenuBridge.show()
            }
            #endif
        case "togglePlayback":
            if player.track != nil {
                player.toggle()
            } else {
                destination = .music
                MacMenuBridge.show()
            }
        case "favorites":
            showsPlayer = false
            destination = .music
            favoritesRequest += 1
        case "quit":
            guard !quitting else { return }
            quitting = true
            defer { quitting = false }
            #if ROYTOOLS_MAC_PROXY
            if proxy.isEnabled {
                await proxy.toggle(false)
            } else if proxy.macProxyRecoveryPending {
                await proxy.restoreMacProxySettings()
            }
            if proxy.isEnabled || proxy.macProxyRecoveryPending {
                showsPlayer = false
                destination = .proxy
                MacMenuBridge.show()
                return
            }
            #endif
            player.saveSession()
            MacMenuBridge.terminate()
        default:
            break
        }
    }

    @ViewBuilder
    private var content: some View {
        if #available(iOS 26.1, *) {
            adaptiveTabs
                .tabBarMinimizeBehavior(onPad ? .never : .onScrollDown)
                .tabViewBottomAccessory(isEnabled: player.track != nil) { miniPlayer }
        } else if #available(iOS 26.0, *) {
            adaptiveTabs
                .tabBarMinimizeBehavior(onPad ? .never : .onScrollDown)
                .tabViewBottomAccessory { miniPlayer }
        } else {
            adaptiveTabs.safeAreaInset(edge: .bottom, spacing: 0) {
                miniPlayer.frame(maxWidth: .infinity).background(.bar)
            }
        }
    }

    @ViewBuilder
    private var adaptiveTabs: some View {
        if #available(iOS 18.0, *), onPad {
            tabs.tabViewStyle(.sidebarAdaptable)
        } else {
            tabs
        }
    }

    @ViewBuilder
    private var miniPlayer: some View {
        if player.track != nil {
            MiniPlayerView(player: player) { showsPlayer = true }
                .frame(maxWidth: onPad ? 680 : .infinity)
        }
    }

    @ViewBuilder
    private var tabs: some View {
        if #available(iOS 18.0, *) {
            TabView(selection: $destination) {
                ForEach(Destination.allCases) { tab in
                    Tab(value: tab) {
                        screen(for: tab)
                    } label: {
                        tab.label
                    }
                }
            }
        } else {
            TabView(selection: $destination) {
                ForEach(Destination.allCases) { tab in
                    screen(for: tab)
                        .tabItem { tab.label }
                        .tag(tab)
                }
            }
        }
    }

    @ViewBuilder
    private func screen(for tab: Destination) -> some View {
        switch tab {
        case .music:
            MusicView(catalog: catalog, player: player, macFavoritesRequest: favoritesRequest)
        #if ROYTOOLS_MAC_PROXY
        case .proxy:
            NavigationStack { ProxyView(vpn: proxy) }
        #endif
        case .settings:
            SettingsView(appearance: $appearance)
        }
    }
}
