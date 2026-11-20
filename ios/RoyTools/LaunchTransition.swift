import SwiftUI

struct LaunchTransition<Content: View>: View {
    @Binding var isPresented: Bool
    @ViewBuilder var content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var wordmarkOpacity = 1.0
    @State private var coverOpacity = 1.0
    @State private var revealRadius: CGFloat = 0
    @State private var revealOpacity = 0.0
    @State private var contentOffset: CGFloat = 20

    private let background = Color("LaunchBackground")

    var body: some View {
        content
            .offset(y: isPresented && !reduceMotion ? contentOffset : 0)
            .background {
                if isPresented { background.ignoresSafeArea() }
            }
            .overlay {
                if isPresented {
                    ZStack {
                        background
                        Image("LaunchWordmark")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 288, height: 288)
                            .opacity(wordmarkOpacity)
                        LaunchReveal(radius: revealRadius, opacity: revealOpacity)
                            .blendMode(.destinationOut)
                    }
                    .compositingGroup()
                    .opacity(coverOpacity)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                    .task { animateLaunch() }
                }
            }
    }

    private func animateLaunch() {
        guard isPresented, !Task.isCancelled else { return }

        if reduceMotion {
            withAnimation(.easeOut(duration: 0.12)) { coverOpacity = 0 } completion: { isPresented = false }
            return
        }

        // Keep the Android reveal direction while starting as soon as the app can draw.
        let duration = 0.22
        withAnimation(.timingCurve(0.15, 0, 1, 1, duration: 0.11)) { wordmarkOpacity = 0 }
        withAnimation(.timingCurve(0, 0, 0, 1, duration: duration)) { contentOffset = 0 }
        withAnimation(.timingCurve(0, 0, 0.4, 1, duration: duration)) {
            revealOpacity = 1
            revealRadius = 1
        } completion: {
            isPresented = false
        }
    }
}

private struct LaunchReveal: View, Animatable {
    var radius: CGFloat
    var opacity: Double

    var animatableData: AnimatablePair<CGFloat, Double> {
        get { .init(radius, opacity) }
        set { (radius, opacity) = (newValue.first, newValue.second) }
    }

    var body: some View {
        Canvas { context, size in
            guard radius > 0, opacity > 0 else { return }
            let bounds = CGRect(origin: .zero, size: size)
            let center = CGPoint(x: bounds.midX, y: bounds.minY)
            let solidEdge = 0.8
            let endRadius = radius * hypot(center.x, bounds.height) / solidEdge
            let gradient = Gradient(stops: [
                .init(color: .white.opacity(opacity), location: 0),
                .init(color: .white.opacity(opacity), location: solidEdge),
                .init(color: .clear, location: 1)
            ])
            context.fill(Path(bounds), with: .radialGradient(gradient, center: center,
                                                            startRadius: 0, endRadius: endRadius))
        }
    }
}
