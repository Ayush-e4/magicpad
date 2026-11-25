import Foundation

/// Installs magicpad as a per-user background agent: it starts at login,
/// sits idle until the phone is plugged in, and restarts if it crashes.
enum LaunchAgent {
    static let label = "com.magicpad.agent"
    static let binDir = Config.dir.appendingPathComponent("bin")
    static let plist = URL(fileURLWithPath: NSHomeDirectory())
        .appendingPathComponent("Library/LaunchAgents/\(label).plist")
    static let logPath = Config.dir.appendingPathComponent("magicpad.log").path

    static func install() {
        let fm = FileManager.default
        let exe = Bundle.main.executableURL!.resolvingSymlinksInPath()
        let srcDir = exe.deletingLastPathComponent()
        try? fm.createDirectory(at: binDir, withIntermediateDirectories: true)
        for name in ["magicpad", "magicpad-grab"] {
            let dst = binDir.appendingPathComponent(name)
            try? fm.removeItem(at: dst)
            do {
                try fm.copyItem(at: srcDir.appendingPathComponent(name), to: dst)
            } catch {
                print("could not copy \(name): \(error.localizedDescription) (run `make` first)")
                return
            }
        }
        _ = Config.writeDefaultIfMissing()

        let dict: [String: Any] = [
            "Label": label,
            "ProgramArguments": [binDir.appendingPathComponent("magicpad").path, "run"],
            "RunAtLoad": true,
            "KeepAlive": true,
            "ProcessType": "Interactive",
            "StandardOutPath": logPath,
            "StandardErrorPath": logPath,
        ]
        try? fm.createDirectory(at: plist.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? PropertyListSerialization.data(fromPropertyList: dict, format: .xml, options: 0),
              (try? data.write(to: plist)) != nil
        else {
            print("could not write \(plist.path)")
            return
        }
        launchctl(["bootout", "gui/\(getuid())/\(label)"])
        launchctl(["bootstrap", "gui/\(getuid())", plist.path])
        print("""
            ✅ installed — magicpad now runs in the background and starts at login.
               log:    \(logPath)
               config: \(Config.url.path)

            One-time step: System Settings → Privacy & Security → Accessibility →
            click +, press ⌘⇧G, paste \(binDir.path)/magicpad, and enable it.
            (Re-do this after every reinstall: macOS ties the permission to the exact binary.)
            """)
    }

    static func uninstall() {
        launchctl(["bootout", "gui/\(getuid())/\(label)"])
        try? FileManager.default.removeItem(at: plist)
        print("removed the background agent (settings in \(Config.dir.path) kept)")
    }

    private static func launchctl(_ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        p.arguments = args
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
    }
}
