import AppKit
import Darwin

@_cdecl("roytools_install_mac_menu")
public func roytools_install_mac_menu() {
    if Thread.isMainThread {
        MainActor.assumeIsolated { MacMenuBar.install() }
    } else {
        DispatchQueue.main.async { MacMenuBar.install() }
    }
}

@MainActor
private final class MacMenuBar: NSObject {
    private static let namespace = "com.roylyl.tools.mac.menu"
    private static let actions = Notification.Name(namespace + ".action")
    private static let state = Notification.Name(namespace + ".state")
    private static let show = Notification.Name(namespace + ".show")
    private static let terminate = Notification.Name(namespace + ".terminate")
    private static var installed: MacMenuBar?

    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let proxyItem = NSMenuItem(title: "启动代理", action: #selector(toggleProxy), keyEquivalent: "")
    private let playbackItem = NSMenuItem(title: "播放", action: #selector(togglePlayback), keyEquivalent: "")
    private var observers: [NSObjectProtocol] = []
    private weak var primaryWindow: NSWindow?

    static func install() {
        guard installed == nil else { return }
        installed = MacMenuBar()
    }

    private override init() {
        super.init()
        if let button = statusItem.button {
            if let path = Bundle.main.builtInPlugInsURL?
                .appending(path: "RoyToolsMenuBar.bundle/Contents/Resources/MenuWordmark.svg"),
               let wordmark = NSImage(contentsOf: path) {
                wordmark.size = NSSize(width: 22, height: 22 * 223 / 260)
                wordmark.isTemplate = false
                button.image = wordmark
            } else {
                button.title = "RoyTools"
            }
            button.toolTip = "RoyTools"
            button.setAccessibilityLabel("RoyTools")
        }

        let menu = NSMenu(title: "RoyTools")
        menu.autoenablesItems = false
        proxyItem.isEnabled = false
        for item in [
            proxyItem,
            playbackItem,
            NSMenuItem(title: "打开收藏歌单", action: #selector(openFavorites), keyEquivalent: ""),
            NSMenuItem(title: "显示RoyTools", action: #selector(showApplication), keyEquivalent: ""),
            NSMenuItem(title: "退出RoyTools", action: #selector(requestQuit), keyEquivalent: "")
        ] {
            item.target = self
            menu.addItem(item)
        }
        statusItem.menu = menu

        observe(Self.state) { [weak self] notification in
            let proxyOn = notification.userInfo?["proxyOn"] as? Bool ?? false
            let busy = notification.userInfo?["proxyBusy"] as? Bool ?? false
            let canProxy = notification.userInfo?["canProxy"] as? Bool ?? false
            let playing = notification.userInfo?["playing"] as? Bool ?? false
            self?.proxyItem.title = proxyOn ? "关闭代理" : "启动代理"
            self?.proxyItem.isEnabled = !busy && (proxyOn || canProxy)
            self?.playbackItem.title = playing ? "暂停" : "播放"
        }
        observe(Self.show) { [weak self] _ in self?.showApplication() }
        observe(Self.terminate) { _ in
            // The Catalyst host has already saved playback and restored proxy settings.
            // AppKit can block its main run loop while terminating a Catalyst process.
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(350)) {
                Darwin._exit(EXIT_SUCCESS)
            }
            NSApp.terminate(nil)
        }
        observe(NSWindow.didBecomeMainNotification) { [weak self] notification in
            self?.installCloseButton(on: notification.object as? NSWindow)
        }
        observe(NSWindow.didBecomeKeyNotification) { [weak self] notification in
            self?.installCloseButton(on: notification.object as? NSWindow)
        }
        installCloseButton(on: NSApp.mainWindow)
    }

    private func observe(_ name: Notification.Name, handle: @escaping @MainActor (Notification) -> Void) {
        observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { notification in
            MainActor.assumeIsolated { handle(notification) }
        })
    }

    private func installCloseButton(on candidate: NSWindow?) {
        if let window = primaryWindow,
           NSApp.windows.contains(where: { $0 === window }) {
            setCloseAction(on: window)
            return
        }
        let window = ([NSApp.mainWindow, candidate].compactMap { $0 } + NSApp.orderedWindows).first {
            $0.level == .normal && $0.canBecomeMain && $0.parent == nil && !$0.isSheet && !($0 is NSPanel)
                && $0.styleMask.contains([.titled, .closable])
        }
        guard let window else { return }
        primaryWindow = window
        setCloseAction(on: window)
    }

    private func setCloseAction(on window: NSWindow) {
        guard let button = window.standardWindowButton(.closeButton) else { return }
        button.target = self
        button.action = #selector(hidePrimaryWindow)
    }

    @objc private func hidePrimaryWindow() {
        if let window = primaryWindow,
           UserDefaults(suiteName: "com.apple.dock")?.bool(forKey: "minimize-to-application") == true,
           window.styleMask.contains(.miniaturizable),
           !window.styleMask.contains(.fullScreen) {
            window.miniaturize(nil)
        } else {
            NSApp.hide(nil)
        }
    }

    @objc private func showApplication() {
        if NSApp.isHidden { NSApp.unhide(nil) }
        installCloseButton(on: NSApp.mainWindow)
        if let window = primaryWindow {
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
        }
        NSApp.activate()
    }

    private func post(_ action: String) {
        NotificationCenter.default.post(name: Self.actions, object: nil, userInfo: ["action": action])
    }

    @objc private func toggleProxy() { post("toggleProxy") }
    @objc private func togglePlayback() { post("togglePlayback") }
    @objc private func openFavorites() {
        showApplication()
        post("favorites")
    }
    @objc private func requestQuit() { post("quit") }
}
