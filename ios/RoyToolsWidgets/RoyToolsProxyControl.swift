import SwiftUI
import WidgetKit

@available(iOS 18.0, *)
struct RoyToolsProxyControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: RoyToolsWidgetData.proxyControlKind, provider: Provider()) { connected in
            ControlWidgetToggle("RoyTools代理", isOn: connected, action: ProxyControlIntent()) { enabled in
                Label(enabled ? "已开启" : "已关闭", systemImage: "power")
            }
        }
        .displayName("RoyTools代理开关")
        .description("开启或关闭已授权的RoyTools VPN。")
    }

    private struct Provider: ControlValueProvider {
        let previewValue = false

        func currentValue() async throws -> Bool { RoyToolsWidgetData.loadProxy().connected }
    }
}
