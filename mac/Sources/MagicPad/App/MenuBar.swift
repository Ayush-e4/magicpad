import AppKit
import ServiceManagement

/// The menu-bar app: runs the Wi-Fi server and shows its state.
final class MenuBarApp: NSObject, NSApplicationDelegate, NSMenuDelegate, ServerUI {
    private let server = Server(cfg: Config.load())
    private var item: NSStatusItem!
    private var trusted = CGPreflightPostEventAccess()
    private var trustTimer: Timer?

    static func run() -> Never {
        let app = NSApplication.shared
        let delegate = MenuBarApp()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
        exit(0)
    }

    func applicationDidFinishLaunching(_ note: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        updateIcon()

        server.ui = self
        server.start()

        if !trusted {
            CGRequestPostEventAccess()
            // Notice when the user grants it, so the menu stops warning.
            trustTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] t in
                guard let self, CGPreflightPostEventAccess() else { return }
                self.trusted = true
                t.invalidate()
                self.updateIcon()
            }
        }
    }

    // MARK: ServerUI

    func phonesChanged() { updateIcon() }

    func askToPair(phone: String, code: String, reply: @escaping (Bool) -> Void) {
        let spaced = "\(code.prefix(3)) \(code.suffix(3))"
        let alert = NSAlert()
        alert.messageText = "Pair “\(phone)” with this Mac?"
        alert.informativeText = """
            Check that your phone shows this code:

            \(spaced)

            Only allow it if the codes match. The phone will be able to control your cursor.
            """
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Deny")
        NSApp.activate(ignoringOtherApps: true)
        // Give up after 60 s, as the protocol says.
        let timeout = Timer(timeInterval: 60, repeats: false) { _ in NSApp.abortModal() }
        RunLoop.main.add(timeout, forMode: .modalPanel)
        let answer = alert.runModal()
        timeout.invalidate()
        reply(answer == .alertFirstButtonReturn)
    }

    // MARK: menu

    private func updateIcon() {
        guard let button = item?.button else { return }
        let name = server.connected.isEmpty ? "rectangle.and.hand.point.up.left"
                                            : "rectangle.and.hand.point.up.left.fill"
        if let img = NSImage(systemSymbolName: name, accessibilityDescription: "MagicPad") {
            img.isTemplate = true
            button.image = img
            button.title = ""
        } else {
            button.title = "MP"
        }
        button.appearsDisabled = !trusted
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let status = server.connected.isEmpty
            ? "Waiting for your phone…"
            : "Connected: " + server.connected.joined(separator: ", ")
        menu.addItem(disabled(status))
        if !trusted {
            menu.addItem(action("⚠️ Allow Accessibility access…", #selector(openAccessibility)))
        }
        menu.addItem(.separator())

        let phones = NSMenuItem(title: "Paired Phones", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        let all = server.store.all
        if all.isEmpty { sub.addItem(disabled("None yet. Open MagicPad on your phone.")) }
        for (id, phone) in all {
            let entry = NSMenuItem(title: phone.name, action: nil, keyEquivalent: "")
            let forget = NSMenu()
            let f = action("Forget This Phone", #selector(forgetPhone(_:)))
            f.representedObject = id
            forget.addItem(f)
            entry.submenu = forget
            sub.addItem(entry)
        }
        phones.submenu = sub
        menu.addItem(phones)

        let speed = NSMenuItem(title: "Pointer Speed", action: nil, keyEquivalent: "")
        let speeds = NSMenu()
        for v in Self.speeds {
            let i = action(v == 1 ? "1× (slowest)" : "\(v.formatted())×", #selector(setSpeed(_:)))
            i.representedObject = v
            i.state = abs(server.cfg.pointerSpeed - v) < 0.01 ? .on : .off
            speeds.addItem(i)
        }
        speed.submenu = speeds
        menu.addItem(speed)

        menu.addItem(hapticsMenu())

        let login = action("Start at Login", #selector(toggleLogin))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(.separator())
        menu.addItem(action("Quit MagicPad", #selector(quit), key: "q"))
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let i = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        i.isEnabled = false
        return i
    }

    private func action(_ title: String, _ sel: Selector, key: String = "") -> NSMenuItem {
        let i = NSMenuItem(title: title, action: sel, keyEquivalent: key)
        i.target = self
        return i
    }

    @objc private func openAccessibility() {
        CGRequestPostEventAccess()
        let url = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        if let u = URL(string: url) { NSWorkspace.shared.open(u) }
    }

    private static let speeds: [Double] = [1, 1.5, 2, 2.5, 3, 4]
    private static let strengths: [(String, Double)] = [("Light", 0.4), ("Medium", 0.7), ("Strong", 1.0)]

    private func hapticsMenu() -> NSMenuItem {
        let item = NSMenuItem(title: "Haptics", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        sub.autoenablesItems = false  // so "Tick While Scrolling" can be greyed out
        let cfg = server.cfg
        let off = action("Off", #selector(setStrength(_:)))
        off.representedObject = 0.0
        off.state = cfg.haptics ? .off : .on
        sub.addItem(off)
        for (name, v) in Self.strengths {
            let i = action(name, #selector(setStrength(_:)))
            i.representedObject = v
            i.state = cfg.haptics && abs(cfg.hapticStrength - v) < 0.01 ? .on : .off
            sub.addItem(i)
        }
        sub.addItem(.separator())
        let ticks = action("Tick While Scrolling", #selector(toggleScrollTicks))
        ticks.state = cfg.scrollTicks ? .on : .off
        ticks.isEnabled = cfg.haptics
        sub.addItem(ticks)
        item.submenu = sub
        return item
    }

    @objc private func setStrength(_ sender: NSMenuItem) {
        guard let v = sender.representedObject as? Double else { return }
        server.update { cfg in
            cfg.haptics = v > 0
            if v > 0 { cfg.hapticStrength = v }
        }
    }

    @objc private func toggleScrollTicks() {
        server.update { $0.scrollTicks.toggle() }
    }

    @objc private func setSpeed(_ sender: NSMenuItem) {
        guard let v = sender.representedObject as? Double else { return }
        server.update { $0.pointerSpeed = v }
    }

    @objc private func forgetPhone(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        server.forget(id)
    }

    @objc private func toggleLogin() {
        let svc = SMAppService.mainApp
        do {
            if svc.status == .enabled { try svc.unregister() } else { try svc.register() }
        } catch {
            let alert = NSAlert(error: error)
            alert.messageText = "Couldn’t change Start at Login"
            NSApp.activate(ignoringOtherApps: true)
            alert.runModal()
        }
    }

    @objc private func quit() { NSApp.terminate(nil) }
}
