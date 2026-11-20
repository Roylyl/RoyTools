import Darwin
import Network
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ProxyView: View {
    let vpn: VPNController
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        List {
            Section("代理状态") {
                Toggle(isOn: Binding(
                    get: { vpn.isEnabled },
                    set: { enabled in Task { await vpn.toggle(enabled) } }
                )) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("VPN开关")
                        Text(vpn.statusText)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
                .disabled(!vpn.canToggle)

                trafficRates
                if let traffic = vpn.traffic {
                    LabeledContent("会话代理流量", value: Self.byteCount(
                        Double(traffic.uploaded) + Double(traffic.downloaded)
                    ))
                }
                if vpn.busy || vpn.isTransitioning {
                    ProgressView(vpn.isTransitioning ? vpn.statusText : "正在处理…")
                }
                if let message = vpn.errorMessage ?? vpn.serviceIssue {
                    ProxyMessage(text: message, systemImage: "exclamationmark.circle")
                }
                if vpn.profile == nil {
                    Text("导入订阅或配置文件后即可开启VPN。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                Group {
                    if dynamicTypeSize.isAccessibilitySize {
                        modePicker.pickerStyle(.menu)
                    } else if UIDevice.current.userInterfaceIdiom == .pad {
                        ViewThatFits(in: .horizontal) {
                            modePicker.pickerStyle(.segmented).frame(minWidth: 320)
                            modePicker.pickerStyle(.menu)
                        }
                    } else {
                        modePicker.pickerStyle(.segmented)
                    }
                }
                .disabled(vpn.busy || vpn.isTransitioning)
            } header: {
                Text("代理模式")
            } footer: {
                switch vpn.mode {
                case .subscription: Text("按当前订阅或配置的原规则分流。")
                case .rule: Text("使用仓库发布的分流规则和所选节点。")
                case .music: Text("仅代理RoyTools中匹配音乐域名的请求。")
                case .ai: Text("使用仓库发布的AI规则，未匹配流量直连。")
                case .global: Text("进入隧道的流量统一走所选代理节点。")
                case .direct: Text("进入隧道的流量直接连接目的地址。")
                }
            }

            Section("配置与连接") {
                NavigationLink {
                    ProxyConfigurationView(vpn: vpn)
                } label: {
                    LabeledContent {
                        Text(vpn.profile?.name ?? "未导入")
                            .lineLimit(1)
                    } label: {
                        Label("配置", systemImage: "doc.text")
                    }
                }

                NavigationLink {
                    ProxyNodesView(vpn: vpn)
                } label: {
                    LabeledContent {
                        Text(vpn.isRunning ? vpn.selectedNode ?? vpn.selectedGroup ?? "等待连接返回节点" : "连接后可选")
                            .lineLimit(1)
                    } label: {
                        Label("代理节点", systemImage: "network")
                    }
                }
                .disabled(vpn.profile == nil)

                NavigationLink {
                    ProxyLANSharingView(vpn: vpn)
                } label: {
                    LabeledContent {
                        Text(vpn.lanStatusText)
                    } label: {
                        Label("局域网共享", systemImage: "wifi")
                    }
                }
            }

            Section("规则更新") {
                LabeledContent("本地版本", value: vpn.snapshot?.version ?? "未加载")
                if let version = vpn.runtimeVersion { LabeledContent("生效版本", value: version) }
                Button {
                    Task { await vpn.checkRules() }
                } label: {
                    Label("检查规则更新", systemImage: "arrow.clockwise")
                }
                .disabled(vpn.busy || vpn.isTransitioning)
                if let message = vpn.ruleMessage {
                    ProxyMessage(text: message, systemImage: vpn.warning ? "exclamationmark.circle" : "checkmark.circle")
                }
            }
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth()
        .navigationTitle("代理")
    }

    private var modePicker: some View {
        Picker("代理模式", selection: Binding(
            get: { vpn.mode },
            set: { mode in Task { await vpn.changeMode(mode) } }
        )) {
            Text("默认").tag(RoyToolsTunnelMode.subscription)
            Text("规则").tag(RoyToolsTunnelMode.rule)
            Text("音乐").tag(RoyToolsTunnelMode.music)
            Text("AI").tag(RoyToolsTunnelMode.ai)
            Text("全局").tag(RoyToolsTunnelMode.global)
            Text("直连").tag(RoyToolsTunnelMode.direct)
        }
    }

    private var trafficRates: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(spacing: 16))
        return layout {
            rate("上传速度", symbol: "arrow.up", value: vpn.traffic?.uploadRate)
            rate("下载速度", symbol: "arrow.down", value: vpn.traffic?.downloadRate)
        }
        .padding(.vertical, 4)
    }

    private func rate(_ title: String, symbol: String, value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: symbol)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(value.map { Self.byteCount($0) + "/s" } ?? "—")
                .font(.title3.weight(.semibold).monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private static func byteCount(_ bytes: Double) -> String {
        let formatting = ByteCountFormatter()
        formatting.countStyle = .decimal
        formatting.allowsNonnumericFormatting = false
        let count = bytes >= Double(Int64.max) ? Int64.max : Int64(bytes.rounded(.down))
        return formatting.string(fromByteCount: count)
    }
}

private struct ProxyLANSharingView: View {
    private struct LANAddress: Hashable, Identifiable {
        enum Source: String {
            case wifi = "Wi-Fi地址"
            case hotspot = "个人热点地址"
            case local = "局域网地址"
        }

        let interface: String
        let ip: String
        let source: Source
        var id: String { interface + ":" + ip }
    }

    private static let pathQueue = DispatchQueue(label: "com.roylyl.tools.lan-interfaces")
    let vpn: VPNController
    @State private var showsPortEditor = false
    @State private var portText = ""
    @State private var addresses: [LANAddress] = []
    @State private var copiedEndpoint: String?

    private var validPort: Int? {
        guard let port = Int(portText.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1...65535).contains(port) else { return nil }
        return port
    }

    var body: some View {
        List {
            Section {
                Toggle("允许来自局域网的连接", isOn: Binding(
                    get: { vpn.lanSharingEnabled },
                    set: { enabled in Task { await vpn.setLANSharing(enabled) } }
                ))
                Button {
                    portText = String(vpn.lanPort)
                    showsPortEditor = true
                } label: {
                    LabeledContent("固定共享端口", value: String(vpn.lanPort))
                }
            } footer: {
                Text("共享端口在切换配置和更新订阅后保持原值。")
            }
            .disabled(!vpn.canEditLANSharing)

            Section {
                LabeledContent("监听状态", value: vpn.lanStatusText)
                if vpn.lanSharingEnabled && !vpn.isRunning {
                    Text("代理启动后开始监听。")
                        .foregroundStyle(.secondary)
                }
                if !addresses.contains(where: { $0.source == .wifi }) {
                    Text("暂未识别到Wi-Fi地址。请让iPhone连接Wi-Fi。")
                        .foregroundStyle(.secondary)
                }
                if !addresses.contains(where: { $0.source == .hotspot }) {
                    Text("暂未识别到个人热点地址。请在iPhone上开启个人热点，并让另一台设备连入。")
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("连接信息")
            } footer: {
                Text("在另一台设备手动设置HTTP或SOCKS代理，选用与它处于同一Wi-Fi或热点的地址，填入监听端口。")
                if vpn.mode == .music {
                    Text("音乐档中的共享连接直连。")
                }
            }

            let port = vpn.runtimeLANPort ?? vpn.lanPort
            ForEach(addresses) { address in
                Section {
                    endpointRow("HTTP", endpoint: "http://\(address.ip):\(port)")
                    endpointRow("SOCKS5", endpoint: "socks5://\(address.ip):\(port)")
                } header: {
                    Text("\(address.source.rawValue) · \(address.ip)")
                }
            }

            if vpn.busy { ProgressView("正在应用共享设置…") }
            if let message = vpn.errorMessage {
                ProxyMessage(text: message, systemImage: "exclamationmark.circle")
            }
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth()
        .navigationTitle("局域网共享")
        .alert("固定共享端口", isPresented: $showsPortEditor) {
            TextField("端口", text: $portText)
                .keyboardType(.numberPad)
            Button("保存") {
                if let validPort { Task { await vpn.setLANPort(validPort) } }
            }
            .disabled(validPort == nil)
            Button("取消", role: .cancel) {}
        } message: {
            Text("请输入1到65535之间的端口号。")
        }
        .task {
            let monitor = NWPathMonitor()
            monitor.start(queue: Self.pathQueue)
            defer { monitor.cancel() }
            while !Task.isCancelled {
                let wifi = Set(monitor.currentPath.availableInterfaces.filter { $0.type == .wifi }.map(\.name))
                addresses = Self.localAddresses(wifi: wifi)
                do { try await Task.sleep(for: .seconds(5)) }
                catch { return }
            }
        }
    }

    private func endpointRow(_ label: String, endpoint: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(label).font(.subheadline).foregroundStyle(.secondary)
                Text(verbatim: endpoint).font(.callout.monospaced())
            }
            Spacer()
            Button {
                UIPasteboard.general.string = endpoint
                copiedEndpoint = endpoint
            } label: {
                Image(systemName: copiedEndpoint == endpoint ? "checkmark" : "doc.on.doc")
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(copiedEndpoint == endpoint ? "已复制\(label)地址" : "复制\(label)地址")
        }
    }

    private static func localAddresses(wifi: Set<String>) -> [LANAddress] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let interfaces else { return [] }
        defer { freeifaddrs(interfaces) }

        var addresses = Set<LANAddress>()
        var cursor: UnsafeMutablePointer<ifaddrs>? = interfaces
        while let current = cursor {
            let interface = current.pointee
            cursor = interface.ifa_next
            guard let namePointer = interface.ifa_name,
                  let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_BROADCAST) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK | IFF_POINTOPOINT) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(NI_MAXHOST),
                           nil, 0, NI_NUMERICHOST) == 0 {
                let name = String(cString: namePointer)
                let source: LANAddress.Source
                if name.hasPrefix("bridge") || name.hasPrefix("ap") { source = .hotspot }
                else if wifi.contains(name) { source = .wifi }
                else { source = .local }
                addresses.insert(LANAddress(interface: name, ip: String(cString: host), source: source))
            }
        }
        return addresses.sorted { ($0.source.rawValue, $0.ip, $0.interface) < ($1.source.rawValue, $1.ip, $1.interface) }
    }
}

private struct ProxyConfigurationView: View {
    let vpn: VPNController
    @State private var showsSubscription = false
    @State private var showsFilePicker = false
    @State private var fileIssue: String?
    @State private var address = ""
    @State private var editingProfile: VPNProfile?
    @State private var pendingDeletion: VPNProfile?

    var body: some View {
        List {
            Section("当前配置") {
                LabeledContent("名称", value: vpn.profile?.name ?? "未导入")
                if let profile = vpn.profile {
                    LabeledContent("来源", value: profile.subscription == nil ? "本地文件" : "订阅")
                }
            }

            if !vpn.savedProfiles.isEmpty {
                Section("已保存配置") {
                    ForEach(vpn.savedProfiles) { saved in
                        savedRow(saved)
                    }
                }
                .disabled(vpn.busy || vpn.isTransitioning)
            }

            Section("管理配置") {
                Button {
                    address = ""
                    showsSubscription = true
                } label: {
                    Label("导入订阅", systemImage: "link")
                }
                Button {
                    showsFilePicker = true
                } label: {
                    Label("导入配置文件", systemImage: "doc.badge.plus")
                }
                if vpn.savedProfiles.contains(where: { $0.subscription != nil }) {
                    Button {
                        importConfiguration { await vpn.updateAllSubscriptions() }
                    } label: {
                        Label("更新全部订阅", systemImage: "arrow.clockwise")
                    }
                }
            }
            .disabled(vpn.busy || vpn.isTransitioning)

            if let message = fileIssue ?? vpn.errorMessage {
                ProxyMessage(text: message, systemImage: "exclamationmark.circle")
            }
        }
        .listStyle(.insetGrouped)
        .iPadContentWidth()
        .overlay(alignment: .center) {
            if vpn.busy { ProxyConfigurationProgress() }
        }
        .navigationTitle("配置")
        .alert("导入订阅", isPresented: $showsSubscription) {
            TextField("HTTPS订阅地址", text: $address)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("导入") {
                let input = address
                address = ""
                importConfiguration { await vpn.importSubscription(input) }
            }
            .disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("取消", role: .cancel) { address = "" }
        }
        .alert("删除配置？", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        ), presenting: pendingDeletion) { saved in
            Button("删除", role: .destructive) {
                importConfiguration { await vpn.deleteProfile(saved) }
            }
            Button("取消", role: .cancel) {}
        } message: { saved in
            if vpn.profile?.id == saved.id && vpn.isEnabled {
                Text("删除“\(saved.name)”将断开当前VPN。")
            } else {
                Text("将删除“\(saved.name)”的本地配置。")
            }
        }
        .sheet(item: $editingProfile) { saved in
            if #available(iOS 18, *), UIDevice.current.userInterfaceIdiom == .pad {
                ProxyConfigurationEditor(vpn: vpn, profile: saved)
                    .presentationSizing(.form)
            } else {
                ProxyConfigurationEditor(vpn: vpn, profile: saved)
            }
        }
        .fileImporter(isPresented: $showsFilePicker, allowedContentTypes: UTType.configurationDocuments) { result in
            switch result {
            case .success(let url):
                importConfiguration { await vpn.importFile(url) }
            case .failure(let error):
                if (error as NSError).code != NSUserCancelledError {
                    fileIssue = "无法打开配置文件。"
                }
            }
        }
    }

    private func savedRow(_ saved: VPNProfile) -> some View {
        let selected = vpn.profile?.id == saved.id
        return Button {
            if !selected { importConfiguration { await vpn.selectProfile(saved) } }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(saved.name).foregroundStyle(.primary)
                    Text(saved.subscription == nil ? "本地文件" : "订阅")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button("删除", systemImage: "trash", role: .destructive) { pendingDeletion = saved }
            Button("编辑", systemImage: "pencil") { editingProfile = saved }
                .tint(.accentColor)
        }
        .swipeActions(edge: .leading) {
            if saved.subscription != nil {
                Button("更新", systemImage: "arrow.clockwise") {
                    importConfiguration { await vpn.updateSubscription(saved) }
                }
                .tint(.accentColor)
            }
        }
    }

    private func importConfiguration(_ action: @escaping () async -> Void) {
        fileIssue = nil
        Task { await action() }
    }
}

private struct ProxyConfigurationEditor: View {
    @Environment(\.dismiss) private var dismiss
    let vpn: VPNController
    let profile: VPNProfile
    @State private var name: String
    @State private var address: String
    @State private var replacementFile: URL?
    @State private var showsFilePicker = false
    @State private var issue: String?

    init(vpn: VPNController, profile: VPNProfile) {
        self.vpn = vpn
        self.profile = profile
        _name = State(initialValue: profile.name)
        _address = State(initialValue: profile.subscription?.absoluteString ?? "")
    }

    private var isSubscription: Bool { profile.subscription != nil }

    var body: some View {
        NavigationStack {
            Form {
                Section("名称") {
                    TextField("配置名称", text: $name)
                }
                if isSubscription {
                    Section("订阅地址") {
                        TextField("HTTPS订阅地址", text: $address)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } else {
                    Section("配置文件") {
                        Button("更换文件", systemImage: "doc.badge.plus") { showsFilePicker = true }
                        if let replacementFile {
                            LabeledContent("新文件", value: replacementFile.lastPathComponent)
                        }
                    }
                }
                if let issue {
                    ProxyMessage(text: issue, systemImage: "exclamationmark.circle")
                }
            }
            .disabled(vpn.busy)
            .overlay(alignment: .center) { if vpn.busy { ProxyConfigurationProgress() } }
            .navigationTitle("编辑配置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.disabled(vpn.busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save)
                        .disabled(vpn.busy || vpn.isTransitioning ||
                                  name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                  (isSubscription && address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                }
            }
        }
        .interactiveDismissDisabled(vpn.busy)
        .fileImporter(isPresented: $showsFilePicker, allowedContentTypes: UTType.configurationDocuments) { result in
            switch result {
            case .success(let url):
                replacementFile = url
                issue = nil
            case .failure(let error):
                if (error as NSError).code != NSUserCancelledError { issue = "无法打开配置文件。" }
            }
        }
    }

    private func save() {
        issue = nil
        Task {
            if await vpn.editProfile(profile, name: name, subscription: isSubscription ? address : nil,
                                     replacementFile: replacementFile) {
                dismiss()
            } else {
                issue = vpn.errorMessage
            }
        }
    }
}

private struct ProxyConfigurationProgress: View {
    var body: some View {
        VStack(spacing: 10) {
            ProgressView().accessibilityHidden(true)
            Text("正在处理配置…")
                .font(.subheadline)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 16)
        .background(.regularMaterial, in: .rect(cornerRadius: 18))
        .padding(.horizontal, 24)
        .allowsHitTesting(false)
    }
}

struct ProxyMessage: View {
    let text: String
    let systemImage: String

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

private extension UTType {
    static let configurationDocuments: [UTType] = [
        .yaml, UTType(filenameExtension: "yaml") ?? .yaml,
        UTType(filenameExtension: "yml") ?? .yaml, .plainText,
    ]
}
