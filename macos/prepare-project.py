#!/usr/bin/env python3
"""Prepare the generated Mac Catalyst project without changing the iOS source."""

import plistlib
import re
import shutil
import sys
from pathlib import Path


def replace(text, old, new, *, count=1):
    if text.count(old) != count:
        raise ValueError(f"Unexpected iOS source for {old!r}")
    return text.replace(old, new)


def substitute(text, pattern, new):
    text, count = re.subn(pattern, new, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise ValueError(f"Unexpected iOS source for {pattern!r}")
    return text


def prepare_project(ios):
    project = ios / "RoyTools.xcodeproj/project.pbxproj"
    text = project.read_text()
    for line in (
        "A40000000000000000000001 /* Embed App Extensions */,",
        "A20000000000000000000005 /* PBXTargetDependency */,",
        "A20000000000000000000009 /* PBXTargetDependency */,",
    ):
        text = replace(text, f"\t\t\t\t{line}\n", "")
    text = replace(text, "\t\t\t\tCODE_SIGN_ENTITLEMENTS = Config/App.entitlements;\n", "", count=2)
    text = replace(
        text,
        "PRODUCT_BUNDLE_IDENTIFIER = com.roylyl.tools;",
        "PRODUCT_BUNDLE_IDENTIFIER = com.roylyl.tools.mac;\n"
        "\t\t\t\tSUPPORTS_MACCATALYST = YES;\n"
        "\t\t\t\tDERIVE_MACCATALYST_PRODUCT_BUNDLE_IDENTIFIER = NO;",
        count=2,
    )
    project.write_text(text)

    info = ios / "Config/App-Info.plist"
    values = plistlib.loads(info.read_bytes())
    values.pop("CFBundleURLTypes", None)
    values["LSApplicationCategoryType"] = "public.app-category.music"
    info.write_bytes(plistlib.dumps(values, sort_keys=False))


def prepare_settings(app, proxy_enabled):
    settings = app / "Features/Settings/SettingsView.swift"
    text = replace(settings.read_text(), "    let vpn: VPNController\n", "")
    text = substitute(text, r'                Section\("后台运行"\).*?(?=                Section\("应用信息"\))', "")
    text = replace(text, "AboutRoyToolsView(vpn: vpn)", "AboutRoyToolsView()")
    settings.write_text(text)

    about = app / "Features/Settings/AboutRoyToolsView.swift"
    text = replace(about.read_text(), "    let vpn: VPNController\n", "")
    core_label = '                LabeledContent("代理核心", value: "Mihomo")\n' if proxy_enabled else ""
    text = substitute(text, r'                LabeledContent\("代理核心".*?                }\n', core_label)
    if not proxy_enabled:
        text = replace(text, "RoyToolsDiagnosticLog.Module.allCases", "RoyToolsDiagnosticLog.Module.allCases.filter { $0 != .proxy }")
        text = replace(text, "清空音乐、代理和应用日志。", "清空音乐和应用日志。")
    about.write_text(text)

    updates = app / "Features/Settings/SettingsSupportViews.swift"
    text = updates.read_text()
    for old, new in (
        ("noIOSBuild", "noMacBuild"),
        ("hasIOSBuild", "hasMacBuild"),
        ("iOSBuilds", "macBuilds"),
        ('hasSuffix(".ipa")', 'hasSuffix(".dmg")'),
        ("查询iOS安装包", "查询Mac安装包"),
        ("查看iOS", "查看Mac"),
        ("暂无iOS安装包", "暂无Mac安装包"),
    ):
        text = text.replace(old, new)
    updates.write_text(text)


def prepare_diagnostics(ios):
    file = ios / "SharedTunnel/RoyToolsDiagnosticLog.swift"
    text = file.read_text()
    text = substitute(
        text,
        r'        guard let group = manager\.containerURL\(forSecurityApplicationGroupIdentifier:.*?(?=        var settings = URLResourceValues\(\))',
        '        var directory = try manager.url(for: .applicationSupportDirectory, in: .userDomainMask,\n'
        '                                        appropriateFor: nil, create: true)\n'
        '            .appending(path: "RoyTools/Diagnostics", directoryHint: .isDirectory)\n'
        '        try manager.createDirectory(at: directory, withIntermediateDirectories: true)\n',
    )
    file.write_text(text)


def prepare_playback(app):
    file = app / "Features/Music/MusicWidgetPlayback.swift"
    text = file.read_text()
    text = substitute(text, r"        let vpn = VPNController\.shared\n.*?(?=        try Task\.checkCancellation\(\))", "")
    text = replace(text, "        VPNController.shared.setBackgroundPlaybackActive(playingAudio)\n", "")
    text = replace(
        text,
        "        let (snapshot, playingAudio) = withObservationTracking {\n"
        "            (makeSnapshot(), player.isPlaying && !player.isBuffering)",
        "        let snapshot = withObservationTracking {\n"
        "            makeSnapshot()",
    )
    text = replace(
        text,
        "        let snapshot = makeSnapshot()\n"
        "        if snapshot.trackID == nil {\n"
        "            RoyToolsWidgetData.saveMusic(snapshot)\n"
        "        } else {\n"
        "            publish(snapshot)\n"
        "        }",
        "        publish(makeSnapshot())",
    )
    text = replace(text, "        RoyToolsWidgetData.saveMusic(snapshot)\n", "", count=2)
    text = replace(text, "        guard let bytes, let image = MusicCover.thumbnail(bytes, pixels: 108) else {", "        guard let bytes else {")
    text = replace(text, "        var snapshot = makeSnapshot()\n        snapshot.artwork = image.jpegData(compressionQuality: 0.8)\n", "")
    file.write_text(text)


def prepare_mac_music_navigation(app):
    file = app / "Features/Music/MusicView.swift"
    text = replace(file.read_text(), "struct MusicView: View {\n    let catalog: MusicCatalogStore\n    let player: MusicPlayer\n",
                   "struct MusicView: View {\n    let catalog: MusicCatalogStore\n    let player: MusicPlayer\n"
                   "    let macFavoritesRequest: Int\n    @State private var macShowsFavorites = false\n")
    text = replace(text, '                .navigationTitle("音乐")\n', '''                .navigationTitle("音乐")
                .onChange(of: macFavoritesRequest, initial: true) { _, request in
                    if request > 0 { macShowsFavorites = true }
                }
                .navigationDestination(isPresented: $macShowsFavorites) {
                    if let current = catalog.catalog {
                        PlaylistsView(catalog: current, player: player).macFavorites()
                    } else {
                        ProgressView("正在加载曲库")
                    }
                }
''')
    text = replace(text,
                   "    private func playlist(title: String, icon: String, items: [String]) -> some View {\n",
                   '''    func macFavorites() -> some View {
        playlistScreen(title: "我喜欢", items: catalog.appleMusic.favorites).screen
    }

    private func playlistScreen(title: String, items: [String]) -> (screen: TrackList, count: Int) {
''')
    text = replace(text, r'''        return NavigationLink {
            TrackList(title: title, tracks: tracks, player: player, catalog: catalog,
                      playlist: MusicPlaylistDetail(unavailable: unavailable, updatedAt: updated))
        } label: {
            LabeledContent {
                Text("\(tracks.count + unavailable.count)首")
''', r'''        return (TrackList(title: title, tracks: tracks, player: player, catalog: catalog,
                          playlist: MusicPlaylistDetail(unavailable: unavailable, updatedAt: updated)),
                tracks.count + unavailable.count)
    }

    private func playlist(title: String, icon: String, items: [String]) -> some View {
        let playlist = playlistScreen(title: title, items: items)
        return NavigationLink {
            playlist.screen
        } label: {
            LabeledContent {
                Text("\(playlist.count)首")
''')
    file.write_text(text)


def swift_method(text, name, implementation):
    return substitute(text, rf"(?m)^    (?:private )?func {name}\([^\n]*\n.*?^    }}\n", implementation)


def prepare_proxy(ios, app):
    controller = app / "Features/Proxy/VPNController.swift"
    text = controller.read_text()
    text = replace(text, "    static let shared = VPNController()\n",
                   "    static let shared = VPNController()\n"
                   "    @ObservationIgnored private let macProxy = MacProxyService()\n"
                   "    var macProxyRecoveryPending: Bool { !isEnabled && macProxy.hasRecovery }\n\n"
                   "    func restoreMacProxySettings() async {\n"
                   "        await perform(\"恢复系统代理\") { try await macProxy.restore() }\n"
                   "    }\n")
    text = replace(text, "    @ObservationIgnored private var manager: NETunnelProviderManager?\n", "")
    text = replace(text, "    @ObservationIgnored private var events: Task<Void, Never>?\n", "")
    text = substitute(text, r"(?<=snapshot = await rules.current\(\)\n)        do \{\n.*?        observeStatus\(\)\n",
                      "        status = .disconnected\n        restored = true\n")
    text = swift_method(text, "enabledManager", "")
    text = swift_method(text, "observeStatus", "")
    text = replace(text, "        let next = manager?.connection.status ?? .invalid\n", "        let next = status\n")
    text = replace(text, "                stopTunnel()\n", "")
    text = replace(text, "            if profile?.id == saved.id {\n",
                   "            if profile?.id == saved.id {\n"
                   "                if isRunning { try await stopTunnel() }\n")
    text = swift_method(text, "setPower", """    private func setPower(_ on: Bool, mayRegisterVPN: Bool) async throws {
        guard on else { try await stopTunnel(); return }
        guard let profile else { throw VPNError.missingProfile }
        let config = try configuration(profile: profile, mode: mode, group: selectedGroup, node: selectedNode)
        status = .connecting
        if mode == .music {
            startingMusicDomains = config.musicDomains
            MusicNetwork.shared.expectProxy(domains: config.musicDomains)
        }
        do {
            let reply = try await macProxy.start(config)
            try? RoyToolsTunnelStorage.saveApplied(config)
            status = .connected
            accept(reply, fallbackMode: config.mode)
        } catch {
            status = .disconnected
            refreshStatus()
            throw error
        }
    }
""")
    text = swift_method(text, "stopTunnel", """    private func stopTunnel() async throws {
        status = .disconnecting
        do {
            try await macProxy.stop()
            status = .disconnected
        } catch {
            status = macProxy.isRunning ? .connected : .disconnected
            refreshStatus()
            throw error
        }
        refreshStatus()
    }
""")
    text = replace(text, "        try RoyToolsTunnelStorage.saveRequested(config)\n"
                         "        let reply = try await request(.reload, timeout: 90)\n",
                   "        let reply = try await macProxy.reload(config)\n"
                   "        try? RoyToolsTunnelStorage.saveApplied(config)\n")
    text = replace(text, "    private func pollCounters() async {\n        guard isRunning else { return }\n",
                   "    private func pollCounters() async {\n        guard isRunning, !busy else { return }\n"
                   "        if !macProxy.isRunning {\n"
                   "            await perform(\"本机代理退出\") { try await stopTunnel() }\n"
                   "            return\n"
                   "        }\n")
    text = swift_method(text, "providerReply", """    private func providerReply(_ message: Data, timeout: TimeInterval) async throws -> RoyToolsTunnelReply {
        if let command = try? JSONDecoder().decode(RoyToolsTunnelCommand.self, from: message) {
            return try await macProxy.request(command.rawValue, timeout: timeout)
        }
        return try await macProxy.request("latencies", payload: message, timeout: timeout)
    }
""")
    controller.write_text(text.replace("VPN连接", "代理连接").replace("开启VPN", "开启系统代理")
                               .replace("关闭VPN", "关闭系统代理").replace("VPN扩展", "本机代理"))

    configuration = ios / "SharedTunnel/RoyToolsTunnelConfiguration.swift"
    text = substitute(configuration.read_text(), r"(?m)^    private static func sharedVPNDirectory\(\)[^\n]*\n.*?^    }\n",
                      '    private static func sharedVPNDirectory() throws -> URL {\n'
                      '        URL.applicationSupportDirectory.appending(path: "RoyToolsMacProxy/Session", directoryHint: .isDirectory)\n'
                      '    }\n')
    configuration.write_text(text)

    view = app / "Features/Proxy/ProxyView.swift"
    text = view.read_text().replace("VPN开关", "系统代理").replace("开启VPN", "开启系统代理")
    text = text.replace("进入隧道的流量", "交给RoyTools的流量").replace("请让iPhone连接Wi-Fi。", "请确认Mac已连接Wi-Fi。")
    text = text.replace("请在iPhone上开启个人热点，并让另一台设备连入。",
                        "使用Mac的互联网共享时，请先让另一台设备连入。")
    text = text.replace("个人热点地址", "互联网共享地址").replace("Wi-Fi或热点", "Wi-Fi或共享网络")
    text = replace(text, "                trafficRates\n", """
                if vpn.macProxyRecoveryPending {
                    Button("恢复系统代理") { Task { await vpn.restoreMacProxySettings() } }
                        .disabled(vpn.busy)
                }
                trafficRates
""")
    view.write_text(text)
    nodes = app / "Features/Proxy/ProxyNodesView.swift"
    nodes.write_text(nodes.read_text().replace("连接VPN后", "开启系统代理后"))


def main():
    ios = Path(sys.argv[1])
    proxy_enabled = len(sys.argv) > 2 and sys.argv[2] == "proxy"
    sources = Path(__file__).resolve().parent / "Sources"
    app = ios / "RoyTools"
    prepare_project(ios)
    prepare_settings(app, proxy_enabled)
    prepare_diagnostics(ios)
    prepare_playback(app)
    prepare_mac_music_navigation(app)
    if proxy_enabled:
        prepare_proxy(ios, app)
    shutil.copyfile(sources / "RoyToolsApp.swift", app / "RoyToolsApp.swift")
    shutil.copytree(sources, app / "MacPlatform", ignore=shutil.ignore_patterns("RoyToolsApp.swift"))


if __name__ == "__main__":
    main()
