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
                Section("关于RoyTools") {
                    LabeledContent("应用版本", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                    if let repository = URL(string: "https://github.com/Roylyl/RoyTools") {
                        Link(destination: repository) {
                            Label("开源项目", systemImage: "chevron.left.forwardslash.chevron.right")
                        }
                    }
                }
            }
            .navigationTitle("设置")
        }
    }
}
