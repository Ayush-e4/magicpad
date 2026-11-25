import Foundation

/// Every long-lived child process, so shutdown can kill them all. Otherwise an
/// orphaned `adb exec-out` keeps the phone's touchscreen grabbed.
enum Children {
    private static let lock = NSLock()
    private static var procs: [Process] = []
    private static var closed = false

    static func track(_ p: Process) {
        lock.lock(); defer { lock.unlock() }
        if closed { p.terminate(); return }  // started during shutdown
        procs.removeAll { !$0.isRunning }
        procs.append(p)
    }

    static func killAll() {
        lock.lock(); defer { lock.unlock() }
        closed = true
        for p in procs where p.isRunning { p.terminate() }
        procs.removeAll()
    }
}

/// Thin wrapper around the `adb` binary.
enum ADB {
    static let path: String = {
        let env = ProcessInfo.processInfo.environment
        let candidates = [
            env["ADB"],
            env["ANDROID_HOME"].map { "\($0)/platform-tools/adb" },
            "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
        ].compactMap { $0 }
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) } ?? "adb"
    }()

    @discardableResult
    static func run(_ args: [String], serial: String? = nil) -> (status: Int32, out: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = (serial.map { ["-s", $0] } ?? []) + args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, "") }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (p.terminationStatus, String(decoding: data, as: UTF8.self))
    }

    @discardableResult
    static func shell(_ cmd: String, serial: String) -> String {
        run(["shell", cmd], serial: serial).out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Serial of the first device in the "device" state (optionally a specific one).
    static func connectedDevice(wanted: String?) -> String? {
        for line in run(["devices"]).out.split(separator: "\n").dropFirst() {
            let parts = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
            guard parts.count >= 2, parts[1] == "device" else { continue }
            let serial = String(parts[0])
            if wanted == nil || wanted == serial { return serial }
        }
        return nil
    }
}

/// Prepares the phone for a session and puts it back afterwards.
final class Phone {
    let serial: String
    static let helperRemote = "/data/local/tmp/magicpad-grab"
    private static let stateURL = Config.dir.appendingPathComponent("phone-state.json")

    private struct Saved: Codable { var brightness: String; var mode: String; var stayOn: String }

    init(serial: String) { self.serial = serial }

    var model: String { ADB.shell("getprop ro.product.model", serial: serial) }

    /// Pushes magicpad-grab (found next to this executable) to the phone.
    func pushHelper() -> Bool {
        let exe = Bundle.main.executableURL?.resolvingSymlinksInPath() ?? URL(fileURLWithPath: CommandLine.arguments[0])
        let local = exe.deletingLastPathComponent().appendingPathComponent("magicpad-grab").path
        guard FileManager.default.fileExists(atPath: local) else {
            Log.info("magicpad-grab not found next to \(exe.path); run `make` first")
            return false
        }
        guard ADB.run(["push", local, Phone.helperRemote], serial: serial).status == 0 else { return false }
        ADB.shell("chmod 755 \(Phone.helperRemote)", serial: serial)
        return true
    }

    /// Physical size of the touch panel in mm along the raw X and Y axes.
    func panelSizeMM() -> (x: Double, y: Double) {
        let size = ADB.shell("wm size", serial: serial)       // "Physical size: 1080x2340"
        let dpi = ADB.shell("dumpsys display | grep -m1 -oE 'xDpi=[0-9.]+, yDpi=[0-9.]+'", serial: serial)
        let nums = { (s: String) in
            s.components(separatedBy: CharacterSet(charactersIn: "0123456789.").inverted)
                .compactMap { Double($0) }.filter { $0 > 0 }
        }
        let px = nums(size.components(separatedBy: "\n").first ?? "")
        let d = nums(dpi)
        guard px.count >= 2, d.count >= 2 else { return (68.0, 148.0) }  // S21 FE fallback
        return (px[0] / d[0] * 25.4, px[1] / d[1] * 25.4)
    }

    // prepare() runs on the session thread and restore() may run on the main
    // thread during Ctrl-C, so they're serialised; once restored, never re-dim.
    private let lock = NSLock()
    private var closed = false

    func prepare(dim: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return }
        if loadSaved() == nil {  // keep the original values from an unclean exit
            let saved = Saved(
                brightness: ADB.shell("settings get system screen_brightness", serial: serial),
                mode: ADB.shell("settings get system screen_brightness_mode", serial: serial),
                stayOn: ADB.shell("settings get global stay_on_while_plugged_in", serial: serial))
            try? FileManager.default.createDirectory(at: Config.dir, withIntermediateDirectories: true)
            try? JSONEncoder().encode(saved).write(to: Phone.stateURL)
        }
        var cmd = "input keyevent KEYCODE_WAKEUP; svc power stayon usb"
        if dim { cmd += "; settings put system screen_brightness_mode 0; settings put system screen_brightness 1" }
        ADB.shell(cmd, serial: serial)
    }

    func restore() {
        lock.lock()
        defer { lock.unlock() }
        closed = true
        guard let s = loadSaved() else { return }
        let cmd = "settings put system screen_brightness \(s.brightness); "
            + "settings put system screen_brightness_mode \(s.mode); "
            + "settings put global stay_on_while_plugged_in \(s.stayOn)"
        if ADB.run(["shell", cmd], serial: serial).status == 0 {
            try? FileManager.default.removeItem(at: Phone.stateURL)
        }
    }

    private func loadSaved() -> Saved? {
        guard let d = try? Data(contentsOf: Phone.stateURL) else { return nil }
        return try? JSONDecoder().decode(Saved.self, from: d)
    }
}

/// Fires short vibrations through one long-lived `adb shell`, so each buzz
/// costs a pipe write instead of a new adb connection.
final class Haptics: HapticOutput {
    private var process: Process?
    private var input: FileHandle?
    private let line: Data

    init(serial: String, ms: Int, amplitude: Int) {
        let amp = max(1, min(255, amplitude))
        line = "cmd vibrator_manager synced -f oneshot -a \(ms) \(amp) >/dev/null 2>&1 &\n".data(using: .utf8)!
        let p = Process()
        p.executableURL = URL(fileURLWithPath: ADB.path)
        p.arguments = ["-s", serial, "shell"]
        let pipe = Pipe()
        p.standardInput = pipe
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        if (try? p.run()) != nil {
            Children.track(p)
            process = p
            input = pipe.fileHandleForWriting
        }
    }

    func play(_ kind: HapticKind) {
        guard kind != .tick else { return }  // too frequent for adb shell
        guard let input, process?.isRunning == true else { return }
        try? input.write(contentsOf: line)
    }

    func stop() {
        try? input?.close()
        process?.terminate()
    }
}
