import SwiftUI
import UIKit

extension View {
    func iPadContentWidth(_ maximum: CGFloat = 760) -> some View {
        modifier(IPadContentWidth(maximum: maximum))
    }
}

private struct IPadContentWidth: ViewModifier {
    let maximum: CGFloat

    @ViewBuilder
    func body(content: Content) -> some View {
        if UIDevice.current.userInterfaceIdiom == .pad {
            GeometryReader { geometry in
                content.safeAreaPadding(.horizontal, max(0, (geometry.size.width - maximum) / 2))
            }
        } else {
            content
        }
    }
}
