import Darwin
import Foundation

@MainActor
enum MacMenuBridge {
    static let action = Notification.Name("com.roylyl.tools.mac.menu.action")
    private static let prefix = "com.roylyl.tools.mac.menu."
    private static var library: UnsafeMutableRawPointer?

    struct State: Equatable {
        var proxyOn = false
        var proxyBusy = false
        var canProxy = false
        var playing = false
    }

    static func install() {
        guard library == nil,
              let plugins = Bundle.main.builtInPlugInsURL else { return }
        let executable = plugins.appending(path: "RoyToolsMenuBar.bundle/Contents/MacOS/RoyToolsMenuBar")
        guard let handle = dlopen(executable.path, RTLD_NOW | RTLD_LOCAL) else {
            RoyToolsDiagnosticLog.record(.app, "读取Mac菜单组件", level: .error, outcome: .failed)
            return
        }
        guard let entry = dlsym(handle, "roytools_install_mac_menu") else {
            dlclose(handle)
            return
        }
        library = handle
        typealias Install = @convention(c) () -> Void
        unsafeBitCast(entry, to: Install.self)()
    }

    static func publish(_ state: State) {
        NotificationCenter.default.post(name: Notification.Name(prefix + "state"), object: nil, userInfo: [
            "proxyOn": state.proxyOn,
            "proxyBusy": state.proxyBusy,
            "canProxy": state.canProxy,
            "playing": state.playing,
        ])
    }

    static func show() {
        NotificationCenter.default.post(name: Notification.Name(prefix + "show"), object: nil)
    }

    static func terminate() {
        NotificationCenter.default.post(name: Notification.Name(prefix + "terminate"), object: nil)
    }
}
