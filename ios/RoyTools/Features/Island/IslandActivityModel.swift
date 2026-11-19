import ActivityKit
import Combine
import Darwin
import Foundation
import QuartzCore
import UIKit

@MainActor
final class IslandModel: ObservableObject {
    typealias SpeedMode = RoyToolsActivityAttributes.SpeedMode
    typealias Reading = RoyToolsActivityAttributes.ContentState

    static let shared = IslandModel()

    @Published private(set) var enabled: Bool
    @Published private(set) var speedMode: SpeedMode
    @Published private(set) var showRefreshRate: Bool
    @Published private(set) var reading: Reading
    @Published private(set) var activityIssue: String?

    private let preferences = UserDefaults.standard
    private let display = DisplayCadenceSampler()
    private var foreground = false
    private var sampleTask: Task<Void, Never>?
    private var publishTask: Task<Void, Never>?
    private var publishPending = false
    private var mayStartActivity = false
    private var lastInterfaces: (totals: [String: InterfaceCounters], time: TimeInterval)?
    private var lastProxy: (totals: ByteCounters, time: TimeInterval)?
    private var proxySpeed: ByteRates?

    private init() {
        let enabled = UserDefaults.standard.bool(forKey: "island.enabled")
        let mode = UserDefaults.standard.string(forKey: "island.speedMode").flatMap(SpeedMode.init(rawValue:)) ?? .off
        let refresh = UserDefaults.standard.bool(forKey: "island.showRefreshRate")
        self.enabled = enabled
        self.speedMode = mode
        self.showRefreshRate = refresh
        self.reading = Reading(speedMode: mode, showRefreshRate: refresh,
                               uploadBytesPerSecond: nil, downloadBytesPerSecond: nil,
                               refreshRate: nil, sampling: false)
    }

    func setEnabled(_ enabled: Bool) {
        self.enabled = enabled
        preferences.set(enabled, forKey: "island.enabled")
        reschedule(allowNewActivity: enabled)
    }

    func setSpeed(_ mode: SpeedMode, on: Bool) {
        if on { speedMode = mode }
        else if speedMode == mode { speedMode = .off }
        preferences.set(speedMode.rawValue, forKey: "island.speedMode")
        reschedule(allowNewActivity: true)
    }

    func setShowsRefreshRate(_ show: Bool) {
        showRefreshRate = show
        preferences.set(show, forKey: "island.showRefreshRate")
        reschedule(allowNewActivity: true)
    }

    func setForeground(_ active: Bool) {
        guard foreground != active else { return }
        foreground = active
        reschedule(allowNewActivity: active)
    }

    // The engine supplies actual proxied bytes, including periodic unchanged samples when idle.
    func updateProxyTotals(uploaded: UInt64, downloaded: UInt64) {
        let now = ProcessInfo.processInfo.systemUptime
        let totals = ByteCounters(uploaded: uploaded, downloaded: downloaded)
        if let lastProxy {
            let elapsed = now - lastProxy.time
            if elapsed > 0, elapsed < 5,
               uploaded >= lastProxy.totals.uploaded, downloaded >= lastProxy.totals.downloaded {
                proxySpeed = ByteRates(upload: Double(uploaded - lastProxy.totals.uploaded) / elapsed,
                                      download: Double(downloaded - lastProxy.totals.downloaded) / elapsed)
            } else {
                proxySpeed = nil
            }
        }
        lastProxy = (totals, now)
    }

    func clearProxyTotals() {
        lastProxy = nil
        proxySpeed = nil
    }

    private var hasContent: Bool { speedMode != .off || showRefreshRate }

    private func reschedule(allowNewActivity: Bool) {
        sampleTask?.cancel()
        sampleTask = nil
        lastInterfaces = nil
        display.stop()
        mayStartActivity = allowNewActivity
        activityIssue = nil
        if foreground && enabled && hasContent {
            if showRefreshRate { display.start() }
            captureReading()
            sampleTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(1)) }
                    catch { return }
                    guard !Task.isCancelled, let self else { return }
                    self.captureReading()
                }
            }
        } else {
            captureReading()
        }
    }

    private func captureReading() {
        let sampling = foreground && enabled && hasContent
        let speed: ByteRates?
        switch (sampling, speedMode) {
        case (true, .global):
            speed = readInterfaceSpeed()
        case (true, .proxy):
            let now = ProcessInfo.processInfo.systemUptime
            speed = lastProxy.map { now - $0.time < 5 } == true ? proxySpeed : nil
        default:
            speed = nil
        }
        reading = Reading(speedMode: speedMode, showRefreshRate: showRefreshRate,
                          uploadBytesPerSecond: speed?.upload, downloadBytesPerSecond: speed?.download,
                          refreshRate: sampling && showRefreshRate ? display.refreshRate : nil, sampling: sampling)
        publish()
    }

    private func readInterfaceSpeed() -> ByteRates? {
        let now = ProcessInfo.processInfo.systemUptime
        guard let totals = InterfaceCounters.read() else {
            lastInterfaces = nil
            return nil
        }
        let previous = lastInterfaces
        lastInterfaces = (totals, now)
        guard let previous, now > previous.time else { return nil }
        var uploaded = 0.0
        var downloaded = 0.0
        var found = false
        for (name, current) in totals {
            guard let last = previous.totals[name] else { continue }
            uploaded += InterfaceCounters.delta(current.uploaded, last.uploaded)
            downloaded += InterfaceCounters.delta(current.downloaded, last.downloaded)
            found = true
        }
        let elapsed = now - previous.time
        return found ? ByteRates(upload: uploaded / elapsed, download: downloaded / elapsed) : nil
    }

    private func publish() {
        publishPending = true
        guard publishTask == nil else { return }
        publishTask = Task { @MainActor [weak self] in
            guard let self else { return }
            while self.publishPending {
                self.publishPending = false
                await self.publishLatest()
            }
            self.publishTask = nil
        }
    }

    private func publishLatest() async {
        let activities = Activity<RoyToolsActivityAttributes>.activities
        guard enabled, hasContent else {
            for activity in activities { await activity.end(nil, dismissalPolicy: .immediate) }
            return
        }
        let content = ActivityContent(state: reading, staleDate: reading.sampling ? Date().addingTimeInterval(4) : Date())
        if let running = activities.first(where: { $0.activityState == .active || $0.activityState == .stale }) {
            await running.update(content)
            return
        }
        guard foreground, mayStartActivity else { return }
        mayStartActivity = false
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            activityIssue = "请允许RoyTools使用实时活动。"
            return
        }
        do {
            _ = try Activity.request(attributes: RoyToolsActivityAttributes(), content: content, pushType: nil)
        } catch {
            activityIssue = "实时活动启动失败，请在支持实时活动的设备上重试。"
        }
    }
}

private struct ByteCounters {
    let uploaded: UInt64
    let downloaded: UInt64
}

private struct ByteRates {
    let upload: Double
    let download: Double
}

private struct InterfaceCounters {
    let uploaded: UInt32
    let downloaded: UInt32

    static func read() -> [String: InterfaceCounters]? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(first) }
        var counters: [String: InterfaceCounters] = [:]
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            let entry = current.pointee
            cursor = entry.ifa_next
            guard let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_LINK),
                  entry.ifa_flags & UInt32(IFF_UP) != 0,
                  let interfaceName = entry.ifa_name, let data = entry.ifa_data else { continue }
            let name = String(cString: interfaceName)
            // Skip loopback and virtual tunnels so the same packets are not counted twice.
            guard name.hasPrefix("en") || name.hasPrefix("pdp_ip") else { continue }
            let stats = data.assumingMemoryBound(to: if_data.self).pointee
            counters[name] = InterfaceCounters(uploaded: stats.ifi_obytes, downloaded: stats.ifi_ibytes)
        }
        return counters.isEmpty ? nil : counters
    }

    static func delta(_ current: UInt32, _ previous: UInt32) -> Double {
        if current >= previous { return Double(current - previous) }
        // Counters are 32-bit on Darwin. Count a wrap, but ignore ordinary interface resets.
        if previous > 0xC000_0000, current < 0x4000_0000 { return Double(current &- previous) }
        return 0
    }
}

@MainActor
private final class DisplayCadenceSampler: NSObject {
    private var link: CADisplayLink?
    private(set) var refreshRate: Int?

    func start() {
        let link = CADisplayLink(target: self, selector: #selector(sample(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
        refreshRate = nil
    }

    @objc private func sample(_ displayLink: CADisplayLink) {
        let interval = displayLink.targetTimestamp - displayLink.timestamp
        guard interval.isFinite, interval > 0 else { return }
        let hertz = 1 / interval
        guard hertz >= 1, hertz <= 1000 else { return }
        refreshRate = Int(hertz.rounded())
    }
}
