import ActivityKit
import Foundation

struct RoyToolsActivityAttributes: ActivityAttributes {
    enum SpeedMode: String, Codable, Hashable, Sendable {
        case off
        case global
        case proxy
    }

    struct ContentState: Codable, Hashable, Sendable {
        let speedMode: SpeedMode
        let showRefreshRate: Bool
        let uploadBytesPerSecond: Double?
        let downloadBytesPerSecond: Double?
        let refreshRate: Int?
        let sampling: Bool

        func rateText(_ rate: Double?, compact: Bool = false) -> String {
            guard sampling, let rate, rate.isFinite, rate >= 0 else { return "—" }
            let units: [(Double, String, String)] = [
                (1_000_000_000, "G", "GB/s"),
                (1_000_000, "M", "MB/s"),
                (1_000, "K", "KB/s")
            ]
            if let (divisor, abbreviated, full) = units.first(where: { rate >= $0.0 }) {
                let value = rate / divisor
                let number = value.formatted(.number.precision(.fractionLength(value < 100 ? 1 : 0)))
                return compact ? number + abbreviated : number + " " + full
            }
            let number = Int(rate.rounded()).formatted()
            return compact ? number + "B" : number + " B/s"
        }
    }
}
