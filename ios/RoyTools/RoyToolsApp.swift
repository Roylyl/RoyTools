import SwiftUI
import UIKit

@main
@MainActor
struct RoyToolsApp: App {
    private enum Destination: CaseIterable, Identifiable {
        case music, proxy, settings

        var id: Self { self }

        var label: Label<Text, Image> {
            switch self {
            case .music: Label("音乐", image: "TabMusic")
            case .proxy: Label("代理", image: "TabProxy")
            case .settings: Label("设置", image: "TabSettings")
            }
        }
    }

    @AppStorage("appearance") private var appearance = Appearance.system
    @Environment(\.scenePhase) private var scenePhase
    @State private var player = MusicPlayer.shared
    @State private var catalog = MusicCatalogStore.shared
    @State private var vpn = VPNController.shared
    @State private var destination: Destination = .music
    @State private var showsPlayer = false
    @State private var showsWidgetNodes = false
    @State private var showsLaunchTransition = true
    @State private var widgetLinkTask: Task<Void, Never>?

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
                    vpn.setForeground(phase == .active)
                    if phase == .active {
                        RoyToolsDiagnosticLog.record(.app, "应用进入前台")
                    }
                    if phase == .background {
                        showsLaunchTransition = false
                        RoyToolsDiagnosticLog.record(.app, "应用进入后台")
                    }
                }
                .onChange(of: vpn.widgetState) { _, _ in vpn.publishWidgetState() }
                .onOpenURL { url in
                    guard url.scheme == "roytools", url.host == "widget" else { return }
                    widgetLinkTask?.cancel()
                    widgetLinkTask = Task { await handleWidgetLink(Array(url.pathComponents.dropFirst())) }
                }
                .task { await vpn.restoreIfNeeded() }
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
            MusicView(catalog: catalog, player: player)
        case .proxy:
            NavigationStack {
                ProxyView(vpn: vpn)
                    .navigationDestination(isPresented: $showsWidgetNodes) { ProxyNodesView(vpn: vpn) }
            }
        case .settings:
            SettingsView(appearance: $appearance, vpn: vpn)
        }
    }

    private func handleWidgetLink(_ path: [String]) async {
        switch path {
        case ["proxy"], ["proxy", "nodes"]:
            showsPlayer = false
            showsWidgetNodes = false
            destination = .proxy
            await vpn.restoreIfNeeded()
            if !Task.isCancelled, destination == .proxy, path.count == 2, vpn.profile != nil {
                showsWidgetNodes = true
            }
        case ["music"]:
            destination = .music
            await catalog.loadLocalCatalog()
            guard !Task.isCancelled, destination == .music else { return }
            if let tracks = catalog.catalog?.tracks { player.restoreSession(from: tracks) }
            if player.track != nil { showsPlayer = true }
        default:
            break
        }
    }
}
