import Foundation
import WidgetKit

struct WidgetProxyState: Codable, Equatable, Sendable {
    var node: String? = nil
    var profile: String? = nil
    var mode: RoyToolsTunnelMode = .subscription
    var connected: Bool = false
    var ready: Bool = false
}

struct WidgetMusicState: Codable, Equatable, Sendable {
    var trackID: String? = nil
    var title: String? = nil
    var artist: String? = nil
    var playing: Bool = false
    var canPrevious: Bool = false
    var canNext: Bool = false
    var artwork: Data? = nil
}

enum RoyToolsWidgetData {
    static let kind = "com.roylyl.tools.home-widget"
    static let proxyControlKind = "com.roylyl.tools.proxy-control"

    static func loadProxy() -> WidgetProxyState { read(WidgetProxyState.self, file: "widget-proxy.json") ?? .init() }
    static func loadMusic() -> WidgetMusicState { read(WidgetMusicState.self, file: "widget-music.json") ?? .init() }

    static func saveProxy(_ state: WidgetProxyState) {
        let previous = loadProxy()
        guard state != previous else { return }
        if write(state, file: "widget-proxy.json"), state.connected != previous.connected {
            if #available(iOS 18.0, *) { ControlCenter.shared.reloadControls(ofKind: proxyControlKind) }
        }
    }

    static func saveMusic(_ snapshot: WidgetMusicState) {
        let previous = loadMusic()
        var state = snapshot
        if state.artwork == nil, state.trackID == previous.trackID { state.artwork = previous.artwork }
        if state != previous { write(state, file: "widget-music.json") }
    }

    static func link(_ path: String) -> URL { URL(string: "roytools://widget/\(path)")! }

    private static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: RoyToolsTunnelStorage.appGroup)
    }

    private static func read<Value: Decodable>(_ type: Value.Type, file: String) -> Value? {
        guard let directory, let data = try? Data(contentsOf: directory.appending(path: file)) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    @discardableResult
    private static func write<Value: Encodable>(_ state: Value, file: String) -> Bool {
        guard let directory, let data = try? JSONEncoder().encode(state),
              (try? data.write(to: directory.appending(path: file), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil else { return false }
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
        return true
    }
}
