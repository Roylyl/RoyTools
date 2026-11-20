import Foundation
import NetworkExtension
import Observation

struct ProxyTraffic {
    let uploaded: UInt64
    let downloaded: UInt64
    let uploadRate: Double?
    let downloadRate: Double?
}

@MainActor
@Observable
final class VPNController {
    static let shared = VPNController()

    private(set) var status: NEVPNStatus = .invalid
    private(set) var profile: VPNProfile?
    private(set) var savedProfiles: [VPNProfile] = []
    private(set) var snapshot: RoutingRuleSnapshot?
    private(set) var groups: [RoyToolsProxyGroup] = []
    private(set) var proxyNodes: [String] = []
    private(set) var mode = RoyToolsTunnelMode.subscription
    private(set) var selectedGroup: String?
    private(set) var globalChoices: [String] = []
    private(set) var selectedNode: String?
    private(set) var runtimeVersion: String?
    private(set) var lanSharingEnabled = false
    private(set) var lanPort = 7890
    private(set) var runtimeLANPort: Int?
    private(set) var traffic: ProxyTraffic?
    private(set) var busy = false
    private(set) var errorMessage: String?
    private(set) var serviceIssue: String?
    private(set) var ruleMessage: String?
    private(set) var warning = false
    private(set) var checkingRules = false

    @ObservationIgnored private let rules = RoutingRuleStore()
    @ObservationIgnored private var manager: NETunnelProviderManager?
    @ObservationIgnored private var events: Task<Void, Never>?
    @ObservationIgnored private var sampling: Task<Void, Never>?
    @ObservationIgnored private var foreground = false
    @ObservationIgnored private var backgroundPlaybackActive = false
    @ObservationIgnored private var initialized = false
    @ObservationIgnored private var restored = false
    @ObservationIgnored private var restoreTask: Task<Void, Never>?
    @ObservationIgnored private var libraryNeedsWrite = false
    @ObservationIgnored private var didSynchronizeSession = false
    @ObservationIgnored private var startingMusicDomains: [String]?
    @ObservationIgnored private var lastTrafficSample: TimeInterval?
    @ObservationIgnored private var proxyConfigurationRevision = 0

    var isRunning: Bool { status == .connected || status == .reasserting }
    var isTransitioning: Bool { status == .connecting || status == .disconnecting }
    var isEnabled: Bool { isRunning || status == .connecting }
    var canToggle: Bool { !busy && status != .disconnecting && (profile != nil || isEnabled) }
    var canEditLANSharing: Bool { !busy && !isTransitioning }
    var widgetState: WidgetProxyState {
        WidgetProxyState(node: routingSummary, profile: profile?.name, mode: mode,
                                connected: isEnabled, ready: isRunning)
    }

    var routingSummary: String? {
        mode.routingSummary(selected: selectedNode ?? selectedGroup)
    }

    var defaultManualSelections: [String] {
        let selections = savedGroupSelections
        var summary = groups.compactMap { group -> String? in
            guard group.selectable, let node = selections[group.name], group.selected == node else { return nil }
            return "\(group.name) · \(node)"
        }
        if let outbound = selections["GLOBAL"], globalChoices.contains(outbound) {
            summary.insert(outbound, at: 0)
        }
        return summary
    }

    var hasSavedGroupSelections: Bool { !savedGroupSelections.isEmpty }

    var defaultGlobalChoice: String? {
        guard let choice = savedGroupSelections["GLOBAL"], globalChoices.contains(choice) else { return nil }
        return choice
    }

    private var savedGroupSelections: [String: String] {
        guard let profile else { return [:] }
        return UserDefaults.standard.dictionary(forKey: Self.groupSelectionsKey(profile.id)) as? [String: String] ?? [:]
    }

    func publishWidgetState() {
        guard restored, serviceIssue == nil else { return }
        RoyToolsWidgetData.saveProxy(widgetState)
    }

    var lanStatusText: String {
        if runtimeLANPort != nil { return "已监听" }
        if !lanSharingEnabled { return "关闭" }
        return isRunning ? "未监听" : "待连接"
    }

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
        lanSharingEnabled = preferences.bool(forKey: "vpn.lanSharingEnabled")
        let savedLANPort = preferences.integer(forKey: "vpn.lanPort")
        if (1...65535).contains(savedLANPort) { lanPort = savedLANPort }
        if mode == .music { MusicNetwork.shared.expectProxy(domains: MusicRoutingPolicy.bundled.domainSuffixes) }
    }

    func restoreIfNeeded() async {
        guard !restored else { return }
        if let restoreTask {
            await restoreTask.value
            return
        }
        let task = Task { await restore() }
        restoreTask = task
        await task.value
        restoreTask = nil
    }

    private func restore() async {
        if !initialized {
            initialized = true
            do { profile = try VPNProfile.load() }
            catch {
                errorMessage = "本地代理配置无法读取"
                RoyToolsDiagnosticLog.record(.proxy, "读取本地代理配置", level: .error, outcome: .failed, error: error)
            }
            do { savedProfiles = try VPNProfile.loadLibrary() }
            catch {
                errorMessage = "已保存配置列表无法读取"
                RoyToolsDiagnosticLog.record(.proxy, "读取已保存的配置列表", level: .error, outcome: .failed, error: error)
            }
            if let profile {
                if let saved = savedProfiles.first(where: { $0.isSameSource(as: profile) }), saved.id != profile.id {
                    self.profile = profile.identified(as: saved.id)
                    libraryNeedsWrite = true
                }
                if let selected = self.profile {
                    remember(selected)
                    if libraryNeedsWrite {
                        do {
                            try VPNProfile.save(selected)
                            try VPNProfile.saveLibrary(savedProfiles)
                            libraryNeedsWrite = false
                        } catch {
                            errorMessage = "无法保存已迁移的配置列表"
                            RoyToolsDiagnosticLog.record(.proxy, "保存旧版配置列表", level: .error, outcome: .failed, error: error)
                        }
                    }
                }
            }
        }
        snapshot = await rules.current()
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier
                    == RoyToolsTunnelStorage.providerBundleIdentifier
            }
            serviceIssue = nil
            refreshStatus()
            if isRunning { await synchronizeTunnel() }
            restored = true
            RoyToolsDiagnosticLog.record(.proxy, "读取系统VPN配置", outcome: .succeeded, code: status.rawValue)
            publishWidgetState()
        } catch {
            serviceIssue = Self.describeVPNError(error)
            RoyToolsDiagnosticLog.record(.proxy, "读取系统VPN配置", level: .error, outcome: .failed, error: error)
            refreshStatus()
        }
        observeStatus()
    }

    func setForeground(_ value: Bool) {
        foreground = value
        updateSampling()
    }

    func setBackgroundPlaybackActive(_ value: Bool) {
        backgroundPlaybackActive = value
        updateSampling()
    }

    private func updateSampling() {
        guard foreground || backgroundPlaybackActive else {
            sampling?.cancel()
            sampling = nil
            clearTraffic()
            return
        }
        guard sampling == nil else { return }
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
        await perform(on ? "请求开启VPN" : "请求关闭VPN") { try await setPower(on, mayRegisterVPN: true) }
    }

    func applyWidgetPower(_ on: Bool) async throws {
        try await performWidgetAction(on ? "桌面控件请求开启VPN" : "桌面控件请求关闭VPN") {
            guard on != isEnabled else { return }
            guard status != .disconnecting else { throw VPNError.transition }
            try await setPower(on, mayRegisterVPN: false)
        }
    }

    func applyWidgetMode(_ next: RoyToolsTunnelMode) async throws {
        try await performWidgetAction("桌面控件请求切换代理模式") {
            guard isRunning else { throw VPNError.providerRejected("请先开启代理") }
            guard next != mode else { return }
            guard profile != nil else { throw VPNError.missingProfile }
            try await setMode(next)
        }
    }

    private func performWidgetAction(_ event: StaticString, _ action: () async throws -> Void) async throws {
        await restoreIfNeeded()
        if let serviceIssue {
            RoyToolsDiagnosticLog.record(.proxy, event, level: .warning, outcome: .failed)
            throw VPNError.providerRejected(serviceIssue)
        }
        guard !busy else { throw VPNError.transition }
        defer { publishWidgetState() }
        let succeeded = await perform(event) {
            refreshStatus()
            try await action()
        }
        if !succeeded {
            if let errorMessage { throw VPNError.providerRejected(errorMessage) }
            throw CancellationError()
        }
    }

    private func setPower(_ on: Bool, mayRegisterVPN: Bool) async throws {
        guard on else {
            stopTunnel()
            return
        }
        guard let profile else { throw VPNError.missingProfile }
        let config = try configuration(profile: profile, mode: mode, group: selectedGroup, node: selectedNode)
        let manager: NETunnelProviderManager
        if mayRegisterVPN {
            manager = try await enabledManager()
        } else {
            guard let installed = self.manager, installed.isEnabled else {
                throw VPNError.providerRejected("请先在RoyTools内授权VPN")
            }
            manager = installed
        }
        guard let session = manager.connection as? NETunnelProviderSession else { throw VPNError.sessionUnavailable }
        try RoyToolsTunnelStorage.saveRequested(config)
        try session.startTunnel(options: [RoyToolsTunnelStorage.activateRequestedOption: NSNumber(value: true)])
        refreshStatus()
        if !isRunning && status != .disconnecting { status = .connecting }
        if mode == .music {
            startingMusicDomains = config.musicDomains
            MusicNetwork.shared.expectProxy(domains: config.musicDomains)
        }
    }

    func importSubscription(_ address: String) async {
        await perform("导入代理订阅") {
            let profile = try await VPNProfile.fetch(address)
            try await install(profile)
        }
    }

    func importFile(_ url: URL) async {
        await perform("导入本地代理配置") {
            try await install(loadFile(url))
        }
    }

    func updateSubscription() async {
        if let profile { await updateSubscription(profile) }
    }

    func updateSubscription(_ saved: VPNProfile) async {
        await perform("更新单个订阅") {
            guard let current = savedProfiles.first(where: { $0.id == saved.id }),
                  let url = current.subscription else { return }
            let fetched = try await VPNProfile.fetch(url.absoluteString)
            try await commitEdit(current.edited(name: current.name, content: fetched))
        }
    }

    func updateAllSubscriptions() async {
        await perform("更新全部订阅") {
            let subscriptions = savedProfiles.filter { $0.subscription != nil }
            var downloads: [URL: Result<VPNProfile, Error>] = [:]
            var failures: [String] = []
            var succeeded = 0
            for saved in subscriptions {
                guard let url = saved.subscription else { continue }
                do {
                    if downloads[url] == nil {
                        do { downloads[url] = .success(try await VPNProfile.fetch(url.absoluteString)) }
                        catch is CancellationError { throw CancellationError() }
                        catch { downloads[url] = .failure(error) }
                    }
                    guard let download = downloads[url] else { continue }
                    let fetched = try download.get()
                    try await commitEdit(saved.edited(name: saved.name, content: fetched))
                    succeeded += 1
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    failures.append("\(saved.name)（\(Self.describeVPNError(error))）")
                }
            }
            if !failures.isEmpty {
                throw VPNError.providerRejected("已更新\(succeeded)份订阅，\(failures.count)份失败\n\(failures.joined(separator: "\n"))")
            }
        }
    }

    @discardableResult
    func editProfile(_ saved: VPNProfile, name: String, subscription: String? = nil,
                     replacementFile: URL? = nil) async -> Bool {
        await perform("编辑已保存配置") {
            guard let current = savedProfiles.first(where: { $0.id == saved.id }) else {
                throw VPNError.missingProfile
            }
            let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { throw VPNError.invalidProfileName }
            let content: VPNProfile?
            if let oldAddress = current.subscription {
                let address = subscription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? oldAddress.absoluteString
                if URL(string: address) == oldAddress {
                    content = nil
                } else {
                    if let url = URL(string: address),
                       savedProfiles.contains(where: { $0.id != current.id && $0.subscription == url }) {
                        throw VPNError.duplicateProfileSource
                    }
                    content = try await VPNProfile.fetch(address)
                }
            } else if let replacementFile {
                content = try await loadFile(replacementFile)
            } else {
                content = nil
            }
            let edited = current.edited(name: title, content: content)
            if content != nil, savedProfiles.contains(where: { $0.id != current.id && $0.isSameSource(as: edited) }) {
                throw VPNError.duplicateProfileSource
            }
            try await commitEdit(edited)
        }
    }

    func deleteProfile(_ saved: VPNProfile) async {
        await perform("删除已保存配置") {
            guard !isTransitioning else { throw VPNError.transition }
            let remaining = savedProfiles.filter { $0.id != saved.id }
            guard remaining.count != savedProfiles.count else { return }
            if profile?.id == saved.id {
                try saveProfileLibrary(remaining, changingCurrentTo: nil)
                do { try RoyToolsTunnelStorage.removeActivationConfigurations() }
                catch {
                    try restoreProfileLibrary(current: profile)
                    throw error
                }
                stopTunnel()
                profile = nil
                selectedGroup = nil
                selectedNode = nil
                groups = []
                proxyNodes = []
                saveSelection()
            } else {
                try VPNProfile.saveLibrary(remaining)
            }
            savedProfiles = remaining
            UserDefaults.standard.removeObject(forKey: Self.groupSelectionsKey(saved.id))
            libraryNeedsWrite = false
        }
    }

    func selectProfile(_ saved: VPNProfile) async {
        guard profile?.id != saved.id,
              let current = savedProfiles.first(where: { $0.id == saved.id }) else { return }
        await perform("切换已保存配置") { try await install(current) }
    }

    private func loadFile(_ url: URL) async throws -> VPNProfile {
        let result = try await Task.detached(priority: .userInitiated) {
            try VPNProfile.importFile(url)
        }.value
        try Task.checkCancellation()
        return result
    }

    private func stopTunnel() {
        manager?.connection.stopVPNTunnel()
        if isEnabled { status = .disconnecting }
        startingMusicDomains = nil
        MusicNetwork.shared.reset()
    }

    func changeMode(_ next: RoyToolsTunnelMode) async {
        guard next != mode else { return }
        await perform("切换代理模式") { try await setMode(next) }
    }

    private func setMode(_ next: RoyToolsTunnelMode) async throws {
        if let profile {
            try await applyIfRunning(configuration(profile: profile, mode: next, group: selectedGroup, node: selectedNode))
        }
        mode = next
        saveSelection()
        RoyToolsDiagnosticLog.record(.proxy, "保存代理模式", mode: next)
    }

    func selectGroup(_ group: String?) async {
        if mode == .subscription {
            guard let group, globalChoices.contains(group), defaultGlobalChoice != group else { return }
            await updateGroupSelections("选择订阅全局出口") { $0["GLOBAL"] = group }
            return
        }
        await changeSelection(group: group, node: nil)
    }

    func restoreSubscriptionSelections() async {
        guard mode == .subscription else { return }
        await updateGroupSelections("恢复服务商默认") { $0.removeAll() }
    }

    func selectNode(_ node: String?) async {
        if let node, let selectedGroup, isRunning {
            await selectGroupMember(node, in: selectedGroup)
            return
        }
        await changeSelection(group: selectedGroup, node: node)
    }

    func selectGroupMember(_ node: String, in group: String) async {
        guard isRunning, let candidate = groups.first(where: { $0.name == group }),
              candidate.selectable, candidate.nodes.contains(node), candidate.selected != node else { return }
        await updateGroupSelections("选择代理组内节点") { $0[group] = node }
    }

    private func updateGroupSelections(_ event: StaticString, change: (inout [String: String]) -> Void) async {
        await perform(event) {
            guard let profile else { throw VPNError.missingProfile }
            guard isRunning else { throw VPNError.sessionUnavailable }
            var config = try configuration(profile: profile, mode: mode, group: selectedGroup, node: selectedNode)
            var selections = config.groupSelections ?? [:]
            change(&selections)
            config.groupSelections = selections
            try await applyIfRunning(config)
            UserDefaults.standard.set(selections, forKey: Self.groupSelectionsKey(profile.id))
        }
    }

    func testProxyLatencies(in group: String?, nodes: [String],
                            onResults: (([RoyToolsProxyLatency]) -> Void)? = nil) async throws -> [RoyToolsProxyLatency] {
        guard isRunning else { throw VPNError.sessionUnavailable }
        guard !busy else { throw VPNError.transition }
        let revision = proxyConfigurationRevision
        var results: [RoyToolsProxyLatency] = []
        let batchSize = RoyToolsProxyLatencyRequest.maximumNodes
        for start in stride(from: 0, to: nodes.count, by: batchSize) {
            try Task.checkCancellation()
            guard revision == proxyConfigurationRevision else { throw CancellationError() }
            let batch = Array(nodes[start..<min(start + batchSize, nodes.count)])
            let query = RoyToolsProxyLatencyRequest(group: group, nodes: batch)
            let message = try JSONEncoder().encode(query)
            guard message.count <= RoyToolsProxyLatencyRequest.maximumBytes else {
                throw VPNError.providerRejected("节点名称过长，无法测速")
            }
            let reply: RoyToolsTunnelReply
            do {
                reply = try await providerReply(message, timeout: 12)
            } catch {
                try Task.checkCancellation()
                guard revision == proxyConfigurationRevision else { throw CancellationError() }
                throw error
            }
            try Task.checkCancellation()
            guard revision == proxyConfigurationRevision else { throw CancellationError() }
            guard let measured = reply.proxyLatencies, measured.map(\.node) == batch else {
                throw VPNError.providerRejected("VPN扩展未返回有效测速结果")
            }
            results.append(contentsOf: measured)
            onResults?(measured)
        }
        return results
    }

    func setLANSharing(_ enabled: Bool) async {
        await changeLANSharing(enabled: enabled, port: lanPort)
    }

    func setLANPort(_ port: Int) async {
        guard (1...65535).contains(port) else { return }
        await changeLANSharing(enabled: lanSharingEnabled, port: port)
    }

    private func changeLANSharing(enabled: Bool, port: Int) async {
        guard canEditLANSharing, enabled != lanSharingEnabled || port != lanPort else { return }
        await perform("修改局域网共享设置") {
            if isRunning {
                guard let profile else { throw VPNError.missingProfile }
                var config = try configuration(profile: profile, mode: mode, group: selectedGroup, node: selectedNode)
                config.lanPort = enabled ? port : nil
                try await applyIfRunning(config)
            }
            lanSharingEnabled = enabled
            lanPort = port
            let preferences = UserDefaults.standard
            preferences.set(enabled, forKey: "vpn.lanSharingEnabled")
            preferences.set(port, forKey: "vpn.lanPort")
        }
    }

    func checkRules() async {
        guard !busy else { return }
        checkingRules = true
        defer { checkingRules = false }
        ruleMessage = nil
        await perform {
            RoyToolsDiagnosticLog.record(.proxy, "检查规则更新", outcome: .started)
            warning = false
            do {
                let result = try await rules.update()
                switch result {
                case .installed(let next):
                    snapshot = next
                    RoyToolsDiagnosticLog.record(.proxy, "保存新版规则", outcome: .succeeded)
                    do {
                        if isRunning, mode.usesRepositoryRules, let profile {
                            try await applyIfRunning(configuration(profile: profile, mode: mode,
                                                                   group: selectedGroup, node: selectedNode))
                        }
                        ruleMessage = "规则已更新至\(next.version)"
                    } catch {
                        warning = true
                        ruleMessage = "已保存\(next.version)，VPN未确认加载。\(error.localizedDescription)"
                        RoyToolsDiagnosticLog.record(.proxy, "热更新代理规则", level: .error, outcome: .failed, error: error)
                    }
                case .current(let current):
                    snapshot = current
                    RoyToolsDiagnosticLog.record(.proxy, "规则已是最新版本")
                    if isRunning, mode.usesRepositoryRules, runtimeVersion != current.version, let profile {
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
                RoyToolsDiagnosticLog.record(.proxy, "检查规则更新", level: .error, outcome: .failed, error: error)
            }
        }
    }

    private func install(_ incoming: VPNProfile) async throws {
        let prior = savedProfiles.first { $0.isSameSource(as: incoming) }
        let next = prior.map { $0.id == incoming.id ? incoming : $0.edited(name: $0.name, content: incoming) } ?? incoming
        let keepSelection = next.id == profile?.id
        let group = keepSelection ? selectedGroup : nil
        let node = keepSelection ? selectedNode : nil
        let configuration = try configuration(profile: next, mode: mode, group: group, node: node)
        var library = savedProfiles
        if let index = library.firstIndex(where: { $0.id == next.id }) {
            library[index] = next
        } else {
            library.append(next)
        }
        try saveProfileLibrary(library, changingCurrentTo: next)
        do {
            try await applyIfRunning(configuration)
        } catch {
            try restoreProfileLibrary(current: profile)
            throw error
        }
        profile = next
        savedProfiles = library
        libraryNeedsWrite = false
        if !isRunning { selectedGroup = group; selectedNode = node }
        saveSelection()
    }

    private func remember(_ profile: VPNProfile) {
        if let index = savedProfiles.firstIndex(where: { $0.id == profile.id || $0.isSameSource(as: profile) }) {
            guard savedProfiles[index] != profile else { return }
            savedProfiles[index] = profile
        } else {
            savedProfiles.append(profile)
        }
        libraryNeedsWrite = true
    }

    private func commitEdit(_ edited: VPNProfile) async throws {
        guard let index = savedProfiles.firstIndex(where: { $0.id == edited.id }) else {
            throw VPNError.missingProfile
        }
        guard savedProfiles[index] != edited else { return }
        let previous = profile
        let editingCurrent = previous?.id == edited.id
        var next = savedProfiles
        next[index] = edited
        if editingCurrent {
            guard !isTransitioning else { throw VPNError.transition }
            let updatedConfiguration = previous?.yaml != edited.yaml && isRunning
                ? try configuration(profile: edited, mode: mode, group: selectedGroup, node: selectedNode)
                : nil
            try saveProfileLibrary(next, changingCurrentTo: edited)
            if let updatedConfiguration {
                do { try await applyIfRunning(updatedConfiguration) }
                catch {
                    try restoreProfileLibrary(current: previous)
                    throw error
                }
            }
            profile = edited
        } else {
            try VPNProfile.saveLibrary(next)
        }
        savedProfiles = next
        libraryNeedsWrite = false
    }

    private func saveProfileLibrary(_ next: [VPNProfile], changingCurrentTo current: VPNProfile?) throws {
        try VPNProfile.saveLibrary(next)
        do { try VPNProfile.save(current) }
        catch {
            try restoreProfileLibrary(current: profile)
            throw error
        }
    }

    private func restoreProfileLibrary(current: VPNProfile?) throws {
        do {
            try VPNProfile.saveLibrary(savedProfiles)
            try VPNProfile.save(current)
        } catch {
            libraryNeedsWrite = true
            throw VPNError.providerRejected("无法恢复上一份配置，请重新检查配置列表")
        }
    }

    private func changeSelection(group: String?, node: String?) async {
        await perform("切换代理节点或代理组") {
            guard let profile else { throw VPNError.missingProfile }
            try await applyIfRunning(configuration(profile: profile, mode: mode, group: group, node: node))
            if !isRunning { selectedGroup = group; selectedNode = node }
            saveSelection()
        }
    }

    private func configuration(profile: VPNProfile, mode: RoyToolsTunnelMode, group: String?, node: String?) throws -> RoyToolsTunnelConfiguration {
        if mode.usesRepositoryRules, snapshot == nil { throw VPNError.rulesUnavailable }
        let section: RoutingRuleSection?
        switch mode {
        case .rule: section = snapshot?.configuration
        case .ai: section = snapshot?.ai
        case .music, .subscription, .global, .direct: section = nil
        }
        return RoyToolsTunnelConfiguration(
            mode: mode, subscriptionYAML: profile.yaml, selectedGroup: group, selectedNode: node,
            rulesVersion: mode.usesRepositoryRules ? snapshot?.version : nil, rules: section?.rules ?? [],
            providers: section?.providers ?? [:], musicDomains: mode == .music ? snapshot?.music.domainSuffixes ?? [] : [],
            lanPort: lanSharingEnabled ? lanPort : nil, profileID: profile.id,
            groupSelections: UserDefaults.standard.dictionary(forKey: Self.groupSelectionsKey(profile.id)) as? [String: String] ?? [:]
        )
    }

    private static func groupSelectionsKey(_ profileID: UUID) -> String {
        "vpn.groupSelections.\(profileID.uuidString)"
    }

    private func applyIfRunning(_ config: RoyToolsTunnelConfiguration) async throws {
        if isTransitioning { throw VPNError.transition }
        guard isRunning else { return }
        proxyConfigurationRevision &+= 1
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
        serviceIssue = nil
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
                        RoyToolsDiagnosticLog.record(.proxy, "连接中断", level: .warning, outcome: .failed, error: error)
                        Task { @MainActor in
                            if self?.status == .disconnected { self?.errorMessage = reason }
                        }
                    }
                }
                if !busy { publishWidgetState() }
            }
        }
    }

    private func refreshStatus() {
        let next = manager?.connection.status ?? .invalid
        if status != next {
            proxyConfigurationRevision &+= 1
            RoyToolsDiagnosticLog.record(.proxy, "系统VPN状态变化", code: next.rawValue)
        }
        status = next
        if !isRunning {
            didSynchronizeSession = false
            runtimeVersion = nil
            runtimeLANPort = nil
            if status == .connecting && mode == .music {
                MusicNetwork.shared.expectProxy(domains: startingMusicDomains ?? musicDomains(for: nil))
            } else {
                startingMusicDomains = nil
                MusicNetwork.shared.reset()
            }
            clearTraffic()
        } else if mode == .music && !MusicNetwork.shared.isProxyReady {
            MusicNetwork.shared.expectProxy(domains: startingMusicDomains ?? musicDomains(for: runtimeVersion))
        }
    }

    private func synchronizeTunnel() async {
        let revision = proxyConfigurationRevision
        if let reply = try? await request(.status, timeout: 5), revision == proxyConfigurationRevision,
           isRunning, !busy, !Task.isCancelled {
            accept(reply, fallbackMode: mode)
        }
    }

    private func pollCounters() async {
        guard isRunning else { return }
        if let lastTrafficSample, ProcessInfo.processInfo.systemUptime - lastTrafficSample >= 5 {
            clearTraffic()
        }
        guard !busy else { return }
        let revision = proxyConfigurationRevision
        if let reply = try? await request(.counters, timeout: 3), revision == proxyConfigurationRevision,
           isRunning, !busy, !Task.isCancelled,
           let uploaded = reply.uploaded, let downloaded = reply.downloaded {
            updateTraffic(uploaded: uploaded, downloaded: downloaded)
        }
        guard !Task.isCancelled else { return }
        if !didSynchronizeSession || (mode == .music && !MusicNetwork.shared.isProxyReady) {
            await synchronizeTunnel()
        }
    }

    private func updateTraffic(uploaded: UInt64, downloaded: UInt64) {
        let now = ProcessInfo.processInfo.systemUptime
        var uploadRate: Double?
        var downloadRate: Double?
        if let traffic, let lastTrafficSample {
            let elapsed = now - lastTrafficSample
            if elapsed > 0, elapsed < 5, uploaded >= traffic.uploaded, downloaded >= traffic.downloaded {
                uploadRate = Double(uploaded - traffic.uploaded) / elapsed
                downloadRate = Double(downloaded - traffic.downloaded) / elapsed
            }
        }
        lastTrafficSample = now
        traffic = ProxyTraffic(uploaded: uploaded, downloaded: downloaded,
                               uploadRate: uploadRate, downloadRate: downloadRate)
    }

    private func clearTraffic() {
        guard lastTrafficSample != nil else { return }
        lastTrafficSample = nil
        traffic = nil
    }

    private func accept(_ reply: RoyToolsTunnelReply, fallbackMode: RoyToolsTunnelMode) {
        let effectiveMode = reply.mode ?? fallbackMode
        startingMusicDomains = nil
        mode = effectiveMode
        runtimeVersion = reply.rulesVersion
        runtimeLANPort = reply.lanProxyPort
        if let groups = reply.groups {
            self.groups = groups
            proxyNodes = reply.proxyNodes ?? []
            globalChoices = reply.globalChoices ?? []
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

    @discardableResult
    private func perform(_ event: StaticString? = nil, _ action: () async throws -> Void) async -> Bool {
        guard !busy else { return false }
        busy = true
        errorMessage = nil
        defer { busy = false }
        if let event { RoyToolsDiagnosticLog.record(.proxy, event, outcome: .started) }
        do {
            try await action()
            if let event { RoyToolsDiagnosticLog.record(.proxy, event, outcome: .succeeded) }
            return true
        } catch is CancellationError {
            if let event { RoyToolsDiagnosticLog.record(.proxy, event, outcome: .cancelled) }
            return false
        } catch {
            errorMessage = Self.describeVPNError(error)
            if let event { RoyToolsDiagnosticLog.record(.proxy, event, level: .error, outcome: .failed, error: error) }
            return false
        }
    }

    private static func describeVPNError(_ error: Error) -> String {
        #if targetEnvironment(simulator)
        let failure = error as NSError
        if (failure.domain == "NEConfigurationErrorDomain" && failure.code == 11)
            || (failure.domain == NEVPNErrorDomain && failure.localizedDescription == "IPC failed") {
            return "当前模拟器无法连接系统VPN服务。"
        }
        #endif
        return error.localizedDescription
    }

    private func request(_ command: RoyToolsTunnelCommand, timeout: TimeInterval) async throws -> RoyToolsTunnelReply {
        try await providerReply(JSONEncoder().encode(command), timeout: timeout)
    }

    private func providerReply(_ message: Data, timeout: TimeInterval) async throws -> RoyToolsTunnelReply {
        guard let session = manager?.connection as? NETunnelProviderSession else { throw VPNError.sessionUnavailable }
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
