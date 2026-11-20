import AppIntents
import Foundation

enum WidgetPlaybackAction: String, AppEnum {
    case previous, play, pause, next

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "播放操作"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .previous: "上一首", .play: "播放", .pause: "暂停", .next: "下一首",
    ]
}

struct WidgetPlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "控制RoyTools音乐"
    static let openAppWhenRun = false
    static let isDiscoverable = false
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
#if compiler(>=6.4)
    @available(iOS 27.0, *)
    static let allowedExecutionTargets: IntentExecutionTargets = .main
#endif

    @Parameter(title: "播放操作") var action: WidgetPlaybackAction

    init() {}

    init(_ action: WidgetPlaybackAction) {
        self.action = action
    }

    func perform() async throws -> some IntentResult {
#if ROYTOOLS_WIDGET_EXTENSION
        try WidgetActionUnavailable.requireAppProcess()
#else
        try await MusicWidgetPlayback.perform(action.rawValue)
#endif
        return .result()
    }
}

extension RoyToolsTunnelMode: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "代理模式"
    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .subscription: "默认", .rule: "规则", .music: "音乐",
        .ai: "AI", .global: "全局", .direct: "直连",
    ]
}

struct WidgetProxyPowerIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "开启或关闭RoyTools代理"
    static let openAppWhenRun = false
    static let isDiscoverable = false
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
#if compiler(>=6.4)
    @available(iOS 27.0, *)
    static let allowedExecutionTargets: IntentExecutionTargets = .main
#endif

    @Parameter(title: "开启代理") var enabled: Bool

    init() {}

    init(enabled: Bool) {
        self.enabled = enabled
    }

    func perform() async throws -> some IntentResult {
#if ROYTOOLS_WIDGET_EXTENSION
        try WidgetActionUnavailable.requireAppProcess()
#else
        try await VPNController.shared.applyWidgetPower(enabled)
#endif
        return .result()
    }
}

struct WidgetProxyModeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "切换RoyTools代理模式"
    static let openAppWhenRun = false
    static let isDiscoverable = false
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
#if compiler(>=6.4)
    @available(iOS 27.0, *)
    static let allowedExecutionTargets: IntentExecutionTargets = .main
#endif

    @Parameter(title: "代理模式") var mode: RoyToolsTunnelMode

    init() {}

    init(_ mode: RoyToolsTunnelMode) {
        self.mode = mode
    }

    func perform() async throws -> some IntentResult {
#if ROYTOOLS_WIDGET_EXTENSION
        try WidgetActionUnavailable.requireAppProcess()
#else
        try await VPNController.shared.applyWidgetMode(mode)
#endif
        return .result()
    }
}

@available(iOS 18.0, *)
struct ProxyControlIntent: SetValueIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "RoyTools代理开关"
    static let openAppWhenRun = false
    static let isDiscoverable = false
    @available(iOS 26.0, *)
    static let supportedModes: IntentModes = .background
#if compiler(>=6.4)
    @available(iOS 27.0, *)
    static let allowedExecutionTargets: IntentExecutionTargets = .main
#endif

    @Parameter(title: "开启代理") var value: Bool

    func perform() async throws -> some IntentResult {
        try await WidgetProxyPowerIntent(enabled: value).perform()
    }
}

#if ROYTOOLS_WIDGET_EXTENSION
private enum WidgetActionUnavailable: LocalizedError {
    case appProcessRequired

    static func requireAppProcess() throws { throw Self.appProcessRequired }

    var errorDescription: String? { "暂时无法执行此操作。" }
}
#endif
