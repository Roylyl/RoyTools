import Foundation
import NetworkExtension
import Observation

@MainActor
@Observable
final class VPNController {
    private(set) var status: NEVPNStatus = .invalid
    private(set) var profile: VPNProfile?
    private(set) var snapshot: RoutingRuleSnapshot?
    private(set) var groups: [RoyToolsProxyGroup] = []
    private(set) var mode = RoyToolsTunnelMode.subscription
    private(set) var selectedGroup: String?
    private(set) var selectedNode: String?
    private(set) var runtimeVersion: String?
    private(set) var busy = false
    private(set) var errorMessage: String?
    private(set) var ruleMessage: String?
    private(set) var warning = false

    @ObservationIgnored private let rules = RoutingRuleStore()
    @ObservationIgnored private var manager: NETunnelProviderManager?
    @ObservationIgnored private var events: Task<Void, Never>?
    @ObservationIgnored private var sampling: Task<Void, Never>?
    @ObservationIgnored private var initialized = false
    @ObservationIgnored private var didSynchronizeSession = false

    var isRunning: Bool { status == .connected || status == .reasserting }
    var isTransitioning: Bool { status == .connecting || status == .disconnecting }
    var isEnabled: Bool { isRunning || status == .connecting }
    var canToggle: Bool { !busy && status != .disconnecting && (profile != nil || isEnabled) }

    var statusText: String {
        switch status {
        case .connected: "已连接"
        case .connecting: "正在连接"
        case .reasserting: "正在恢复连接"
        case .disconnecting: "正在断开"
        default: "未连接"
        }
    }

    var selectedNodes: [String] { groups.first(where: { $0.name == selectedGroup })?.nodes ?? [] }

    init() {
        let preferences = UserDefaults.standard
        mode = RoyToolsTunnelMode(rawValue: preferences.string(forKey: "vpn.mode") ?? "") ?? .subscription
        selectedGroup = preferences.string(forKey: "vpn.group")
        selectedNode = preferences.string(forKey: "vpn.node")
        if mode == .music { MusicNetwork.shared.expectProxy(domains: MusicRoutingPolicy.bundled.domainSuffixes) }
    }

    func restore() async {
        if !initialized {
            initialized = true
            do { profile = try VPNProfile.load() }
            catch { errorMessage = "本地代理配置无法读取" }
        }
        snapshot = await rules.current()
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier
                    == RoyToolsTunnelStorage.providerBundleIdentifier
            }
            refreshStatus()
            if isRunning { await synchronizeTunnel() }
        } catch {
            errorMessage = error.localizedDescription
            refreshStatus()
        }
        observeStatus()
    }

    func setForeground(_ value: Bool) {
        sampling?.cancel()
        sampling = nil
        if !value {
            IslandModel.shared.clearProxyTotals()
            return
        }
        sampling = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard self != nil else { return }
                await self?.pollCounters()
                do { try await Task.sleep(for: .seconds(1)) }
                catch { return }
            }
        }
    }

    func toggle(_ on: Bool) async {
        await perform {
            if on {
                guard let profile else { throw VPNError.missingProfile }
                let config = try configuration(profile: profile, mode: mode, group: selectedGroup, node: selectedNode)
                let manager = try await enabledManager()
                try RoyToolsTunnelStorage.saveRequested(config)
                guard let session = manager.connection as? NETunnelProviderSession else { throw VPNError.sessionUnavailable }
                try session.startTunnel(options: [RoyToolsTunnelStorage.activateRequestedOption: NSNumber(value: true)])
                refreshStatus()
                if !isRunning && status != .disconnecting { status = .connecting }
                if mode == .music { MusicNetwork.shared.expectProxy(domains: musicDomains(for: runtimeVersion)) }
            } else {
                manager?.connection.stopVPNTunnel()
                if isEnabled { status = .disconnecting }
                MusicNetwork.shared.reset()
            }
        }
    }

    func importSubscription(_ address: String) async {
        await perform {
            let profile = try await VPNProfile.fetch(address)
            try await install(profile, keepSelection: false)
        }
    }

    func importFile(_ url: URL) async {
        await perform {
            let profile = try VPNProfile.importFile(url)
            try await install(profile, keepSelection: false)
        }
    }

    func updateSubscription() async {
        guard let url = profile?.subscription else { return }
        await perform {
            let refreshed = try await VPNProfile.fetch(url.absoluteString)
            try await install(refreshed, keepSelection: true)
        }
    }

    func changeMode(_ next: RoyToolsTunnelMode) async {
        guard next != mode else { return }
        await perform {
            if let profile {
                try await applyIfRunning(configuration(profile: profile, mode: next, group: selectedGroup, node: selectedNode))
            }
            mode = next
            saveSelection()
        }
    }

    func selectGroup(_ group: String?) async {
        await changeSelection(group: group, node: nil)
    }

    func selectNode(_ node: String?) async {
        await changeSelection(group: selectedGroup, node: node)
    }

    func checkRules() async {
        guard !busy else { return }
        ruleMessage = nil
        await perform {
            warning = false
            do {
                let result = try await rules.update()
                switch result {
                case .installed(let next):
                    snapshot = next
                    do {
                        if isRunning, mode != .subscription, let profile {
                            try await applyIfRunning(configuration(profile: profile, mode: mode,
                                                                   group: selectedGroup, node: selectedNode))
                        }
                        ruleMessage = "规则已更新至\(next.version)"
                    } catch {
                        warning = true
                        ruleMessage = "已保存\(next.version)，VPN未确认加载。\(error.localizedDescription)"
                    }
                case .current(let current):
                    snapshot = current
                    if isRunning, mode != .subscription, runtimeVersion != current.version, let profile {
                        try await applyIfRunning(configuration(profile: profile, mode: mode,
                                                               group: selectedGroup, node: selectedNode))
                        ruleMessage = "已应用规则\(current.version)"
                    } else {
                        ruleMessage = "目前已是最新版本\(current.version)"
                    }
                }
            } catch {
                warning = true
                ruleMessage = error.localizedDescription
            }
        }
    }

    private func install(_ next: VPNProfile, keepSelection: Bool) async throws {
        let group = keepSelection ? selectedGroup : nil
        let node = keepSelection ? selectedNode : nil
        let configuration = try configuration(profile: next, mode: mode, group: group, node: node)
        try VPNProfile.save(next)
        do {
            try await applyIfRunning(configuration)
        } catch {
            do { try VPNProfile.save(profile) }
            catch {
                profile = next
                throw VPNError.providerRejected("新配置已保存，VPN未确认应用")
            }
            throw error
        }
        profile = next
        if !isRunning { selectedGroup = group; selectedNode = node }
        saveSelection()
    }

    private func changeSelection(group: String?, node: String?) async {
        await perform {
            guard let profile else { throw VPNError.missingProfile }
            try await applyIfRunning(configuration(profile: profile, mode: mode, group: group, node: node))
            if !isRunning { selectedGroup = group; selectedNode = node }
            saveSelection()
        }
    }

    private func configuration(profile: VPNProfile, mode: RoyToolsTunnelMode, group: String?, node: String?) throws -> RoyToolsTunnelConfiguration {
        if mode != .subscription, snapshot == nil { throw VPNError.rulesUnavailable }
        let section: RoutingRuleSection?
        switch mode {
        case .rule: section = snapshot?.configuration
        case .ai: section = snapshot?.ai
        case .music, .subscription: section = nil
        }
        return RoyToolsTunnelConfiguration(
            mode: mode, subscriptionYAML: profile.yaml, selectedGroup: group, selectedNode: node,
            rulesVersion: mode == .subscription ? nil : snapshot?.version, rules: section?.rules ?? [],
            providers: section?.providers ?? [:], musicDomains: mode == .music ? snapshot?.music.domainSuffixes ?? [] : []
        )
    }

    private func applyIfRunning(_ config: RoyToolsTunnelConfiguration) async throws {
        if isTransitioning { throw VPNError.transition }
        guard isRunning else { return }
        try RoyToolsTunnelStorage.saveRequested(config)
        let reply = try await request(.reload, timeout: 90)
        accept(reply, fallbackMode: config.mode)
    }

    private func enabledManager() async throws -> NETunnelProviderManager {
        if let manager, manager.isEnabled { return manager }
        let manager = self.manager ?? NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = RoyToolsTunnelStorage.providerBundleIdentifier
        proto.serverAddress = "RoyTools"
        manager.protocolConfiguration = proto
        manager.localizedDescription = "RoyTools VPN"
        manager.isEnabled = true
        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()
        self.manager = manager
        return manager
    }

    private func observeStatus() {
        guard events == nil else { return }
        events = Task { @MainActor [weak self] in
            for await _ in NotificationCenter.default.notifications(named: .NEVPNStatusDidChange) {
                guard let self else { return }
                let previous = status
                refreshStatus()
                if isRunning, !busy {
                    await synchronizeTunnel()
                } else if status == .disconnected, previous == .connecting || previous == .reasserting {
                    manager?.connection.fetchLastDisconnectError { [weak self] error in
                        let reason = error?.localizedDescription ?? "VPN连接失败，请检查配置后重试"
                        Task { @MainActor in
                            if self?.status == .disconnected { self?.errorMessage = reason }
                        }
                    }
                }
            }
        }
    }

    private func refreshStatus() {
        status = manager?.connection.status ?? .invalid
        if !isRunning {
            didSynchronizeSession = false
            runtimeVersion = nil
            if status == .connecting && mode == .music {
                MusicNetwork.shared.expectProxy(domains: musicDomains(for: nil))
            } else {
                MusicNetwork.shared.reset()
            }
            IslandModel.shared.clearProxyTotals()
        } else if mode == .music && !MusicNetwork.shared.isProxyReady {
            MusicNetwork.shared.expectProxy(domains: musicDomains(for: runtimeVersion))
        }
    }

    private func synchronizeTunnel() async {
        if let reply = try? await request(.status, timeout: 5), isRunning, !busy {
            accept(reply, fallbackMode: mode)
        }
    }

    private func pollCounters() async {
        guard isRunning, !busy else { return }
        if let reply = try? await request(.counters, timeout: 3), isRunning, !busy,
           let uploaded = reply.uploaded, let downloaded = reply.downloaded {
            IslandModel.shared.updateProxyTotals(uploaded: uploaded, downloaded: downloaded)
        }
        if !didSynchronizeSession || (mode == .music && !MusicNetwork.shared.isProxyReady) {
            await synchronizeTunnel()
        }
    }

    private func accept(_ reply: RoyToolsTunnelReply, fallbackMode: RoyToolsTunnelMode) {
        let effectiveMode = reply.mode ?? fallbackMode
        mode = effectiveMode
        runtimeVersion = reply.rulesVersion
        if let groups = reply.groups {
            self.groups = groups
            selectedGroup = reply.selectedGroup
            selectedNode = reply.selectedNode
            didSynchronizeSession = true
        }
        saveSelection()
        if effectiveMode == .music {
            MusicNetwork.shared.configure(
                domains: musicDomains(for: reply.rulesVersion),
                port: reply.musicProxyPort, username: reply.musicProxyUsername, password: reply.musicProxyPassword
            )
        } else {
            MusicNetwork.shared.reset()
        }
    }

    private func musicDomains(for version: String?) -> [String] {
        if let version, snapshot?.version == version { return snapshot?.music.domainSuffixes ?? [] }
        if let applied = try? RoyToolsTunnelStorage.loadApplied(), applied.mode == .music { return applied.musicDomains }
        return snapshot?.music.domainSuffixes ?? []
    }

    private func saveSelection() {
        let prefs = UserDefaults.standard
        prefs.set(mode.rawValue, forKey: "vpn.mode")
        prefs.set(selectedGroup, forKey: "vpn.group")
        prefs.set(selectedNode, forKey: "vpn.node")
    }

    private func perform(_ action: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        errorMessage = nil
        defer { busy = false }
        do { try await action() }
        catch is CancellationError { }
        catch { errorMessage = error.localizedDescription }
    }

    private func request(_ command: RoyToolsTunnelCommand, timeout: TimeInterval) async throws -> RoyToolsTunnelReply {
        guard let session = manager?.connection as? NETunnelProviderSession else { throw VPNError.sessionUnavailable }
        let message = try JSONEncoder().encode(command)
        let data: Data = try await withCheckedThrowingContinuation { continuation in
            let result = VPNReplyContinuation(continuation)
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                result.complete(.failure(VPNError.messageTimeout))
            }
            do {
                try session.sendProviderMessage(message) { data in
                    if let data { result.complete(.success(data)) }
                    else { result.complete(.failure(VPNError.sessionUnavailable)) }
                }
            } catch { result.complete(.failure(error)) }
        }
        let reply = try JSONDecoder().decode(RoyToolsTunnelReply.self, from: data)
        guard reply.ok else { throw VPNError.providerRejected(reply.message) }
        return reply
    }
}

private final class VPNReplyContinuation: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: CheckedContinuation<Data, Error>?

    init(_ continuation: CheckedContinuation<Data, Error>) { pending = continuation }

    func complete(_ result: Result<Data, Error>) {
        lock.lock()
        let continuation = pending
        pending = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}
