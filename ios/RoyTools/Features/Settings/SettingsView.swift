import SwiftUI

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: Self { self }

    var title: String {
        switch self {
        case .system: "跟随系统"
        case .light: "浅色"
        case .dark: "深色"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct SettingsView: View {
    @Binding var appearance: Appearance
    let vpn: VPNController

    var body: some View {
        NavigationStack {
            Form {
                Section("外观") {
                    Picker("主题", selection: $appearance) {
                        ForEach(Appearance.allCases) { option in
                            Text(option.title).tag(option)
                        }
                    }
                }
                Section("后台运行") {
                    NavigationLink {
                        BackgroundSettingsView(vpn: vpn)
                    } label: {
                        Label("后台运行设置", systemImage: "gearshape.2")
                    }
                }
                Section("快捷控制") {
                    if #available(iOS 18.0, *) {
                        NavigationLink {
                            ShortcutSettingsView(kind: .controlCenter)
                        } label: {
                            Label("添加控制中心代理开关", systemImage: "switch.2")
                        }
                    }
                    NavigationLink {
                        ShortcutSettingsView(kind: .homeWidget)
                    } label: {
                        Label("添加桌面小组件", systemImage: "square.grid.2x2")
                    }
                }
                Section("应用信息") {
                    NavigationLink {
                        AppUpdateSettingsView()
                    } label: {
                        Label("应用更新", systemImage: "arrow.down.circle")
                    }
                    NavigationLink {
                        AboutRoyToolsView(vpn: vpn)
                    } label: {
                        Label("关于RoyTools", systemImage: "info.circle")
                    }
                }
            }
            .iPadContentWidth()
            .navigationTitle("设置")
        }
    }
}
