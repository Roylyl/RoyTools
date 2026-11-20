import SwiftUI
import WidgetKit

@main
struct RoyToolsWidgetBundle: WidgetBundle {
    var body: some Widget {
        RoyToolsHomeWidget()
        if #available(iOS 18.0, *) { RoyToolsProxyControl() }
    }
}
