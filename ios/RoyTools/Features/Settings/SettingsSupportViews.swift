import SwiftUI
import UIKit

struct BackgroundSettingsView: View {
    let vpn: VPNController

    var body: some View {
        List {
            Section {
                LabeledContent("代理", value: vpn.statusText)
                if let issue = vpn.serviceIssue {
                    Text(issue).font(.subheadline).foregroundStyle(.secondary)
                }
                LabeledContent("音乐", value: "支持后台播放")
            } header: {
                Text("后台运行状态")
            } footer: {
                Text("代理连接由iOS管理。音乐开始播放后，可以锁屏或切换到其他App。")
            }

            Section {
                if let appSettings = URL(string: UIApplication.openSettingsURLString) {
                    Link(destination: appSettings) {
                        Label("打开系统设置", systemImage: "gearshape")
                    }
                }
            } header: {
                Text("系统设置")
            } footer: {
                Text("在系统设置的“通用→VPN与设备管理”中可以查看VPN配置。")
            }
        }
        .iPadContentWidth()
        .navigationTitle("后台运行设置")
    }
}

struct ShortcutSettingsView: View {
    enum Kind {
        case controlCenter, homeWidget

        var title: LocalizedStringKey {
            switch self {
            case .controlCenter: "添加控制中心代理开关"
            case .homeWidget: "添加桌面小组件"
            }
        }

        var steps: [String] {
            switch self {
            case .controlCenter:
                ["从屏幕右上角下滑，打开控制中心。",
                 "长按空白处，点按“添加控制”。",
                 "在控制图库中找到RoyTools，选择代理开关。"]
            case .homeWidget:
                ["长按主屏幕空白处。",
                 "点按“编辑→添加小组件”，或点按左上角“+”。",
                 "在小组件图库中选择RoyTools，再点按“添加小组件”。"]
            }
        }
    }

    let kind: Kind

    private var supportsControlCenter: Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    var body: some View {
        List {
            if kind == .controlCenter && !supportsControlCenter {
                Text("控制中心代理开关适用于iOS18及更新版本。")
                    .foregroundStyle(.secondary)
            } else {
                Section("添加方法") {
                    ForEach(Array(kind.steps.enumerated()), id: \.offset) { number, step in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text("\(number + 1).").monospacedDigit().foregroundStyle(.secondary)
                            Text(step)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .iPadContentWidth()
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AppUpdateSettingsView: View {
    private enum CheckResult {
        case current(String)
        case available(String, URL)
        case noIOSBuild
        case failed
    }

    private struct Release: Decodable {
        struct Asset: Decodable { let name: String }

        let tag: String
        let url: URL
        let draft: Bool
        let prerelease: Bool
        let assets: [Asset]

        enum CodingKeys: String, CodingKey {
            case tag = "tag_name"
            case url = "html_url"
            case draft, prerelease, assets
        }

        var hasIOSBuild: Bool { assets.contains { $0.name.lowercased().hasSuffix(".ipa") } }
    }

    private struct Version: Comparable {
        let components: [Int]

        init?(_ label: String) {
            guard let match = label.range(of: #"\d+(?:\.\d+){1,2}"#, options: .regularExpression) else {
                return nil
            }
            let parts = label[match].split(separator: ".").compactMap { Int($0) }
            guard (2...3).contains(parts.count) else { return nil }
            components = parts.count == 2 ? parts + [0] : parts
        }

        var label: String { components.map(String.init).joined(separator: ".") }

        static func < (lhs: Self, rhs: Self) -> Bool {
            lhs.components.lexicographicallyPrecedes(rhs.components)
        }
    }

    private static let latestReleases = URL(string: "https://api.github.com/repos/Roylyl/RoyTools/releases?per_page=50")!
    private static let releasesPage = URL(string: "https://github.com/Roylyl/RoyTools/releases")!

    private var installedVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    @State private var isChecking = false
    @State private var result: CheckResult?
    @State private var checkTask: Task<Void, Never>?

    var body: some View {
        List {
            Section {
                LabeledContent("当前版本", value: installedVersion)
                Button {
                    guard !isChecking else { return }
                    isChecking = true
                    result = nil
                    checkTask = Task { await checkForUpdates() }
                } label: {
                    HStack {
                        Label("检查更新", systemImage: "arrow.clockwise")
                        Spacer()
                        if isChecking { ProgressView() }
                    }
                }
                .disabled(isChecking)
                if let result { updateStatus(result) }
            } footer: {
                Text("从RoyTools的GitHub发行页查询iOS安装包。")
            }

            Section {
                Link(destination: Self.releasesPage) {
                    Label("查看发行页", systemImage: "arrow.up.right")
                }
            }
        }
        .iPadContentWidth()
        .navigationTitle("应用更新")
        .onDisappear { checkTask?.cancel() }
    }

    @ViewBuilder
    private func updateStatus(_ result: CheckResult) -> some View {
        switch result {
        case .current(let latest):
            Label("已是最新版本（\(latest)）", systemImage: "checkmark.circle")
                .foregroundStyle(.secondary)
        case .available(let version, let url):
            Link(destination: url) { Label("查看iOS\(version)更新", systemImage: "arrow.up.right") }
        case .noIOSBuild:
            Text("发行页暂无iOS安装包。")
                .foregroundStyle(.secondary)
        case .failed:
            Text("暂时无法检查更新，请稍后重试。")
                .foregroundStyle(.secondary)
        }
    }

    private func checkForUpdates() async {
        defer { isChecking = false }

        do {
            var request = URLRequest(url: Self.latestReleases, timeoutInterval: 15)
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            try Task.checkCancellation()
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else {
                result = .failed
                return
            }
            let releases = try JSONDecoder().decode([Release].self, from: data)
            let iOSBuilds = releases.filter { !$0.draft && !$0.prerelease && $0.hasIOSBuild }
            let buildsWithVersions = iOSBuilds.compactMap { release -> (Version, URL)? in
                Version(release.tag).map { ($0, release.url) }
            }
            if let (latest, url) = buildsWithVersions.max(by: { $0.0 < $1.0 }) {
                guard let installed = Version(installedVersion) else { result = .failed; return }
                result = latest > installed ? .available(latest.label, url) : .current(installedVersion)
            } else {
                result = iOSBuilds.isEmpty ? .noIOSBuild : .failed
            }
        } catch {
            if !Task.isCancelled { result = .failed }
        }
    }
}

struct OpenSourceLicensesView: View {
    var body: some View {
        List {
            NavigationLink("RoyTools项目许可") { LicenseTextView(title: "RoyTools项目许可", resource: "LICENSE") }
            NavigationLink("仓库第三方声明") { LicenseTextView(title: "仓库第三方声明", resource: "NOTICE") }
            Link(destination: URL(string: "https://github.com/Roylyl/RoyTools/blob/main/THIRD_PARTY.md")!) {
                Label("组件与数据来源", systemImage: "arrow.up.right")
            }
        }
        .iPadContentWidth()
        .navigationTitle("开源许可")
    }
}

private struct LicenseTextView: View {
    let title: String
    let resource: String
    @State private var license: String?

    var body: some View {
        ScrollView {
            if let license {
                Text(license)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            } else {
                ProgressView().padding()
            }
        }
        .iPadContentWidth(860)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            license = Bundle.main.url(forResource: resource, withExtension: nil)
                .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "无法读取本地许可文本。"
        }
    }
}
