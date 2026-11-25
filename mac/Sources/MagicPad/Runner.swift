import CoreGraphics
import Foundation

/// Waits for the phone, runs a trackpad session, cleans up, repeats.
final class Runner {
    private let cfg: Config
    private let grab: Bool
    private let queue = DispatchQueue(label: "magicpad.engine", qos: .userInteractive)
    private var stream: TouchStream?
    private var phone: Phone?
    private var haptics: Haptics?
    private var engine: GestureEngine?
    private var stopping = false

    init(cfg: Config, grab: Bool) {
        self.cfg = cfg
        self.grab = grab
    }

    func start() {
        checkAccessibility()
        installSignalHandlers()
        Thread { self.loop() }.start()
    }

    private func loop() {
        var announcedWaiting = false
        while !stopping {
            guard let serial = ADB.connectedDevice(wanted: cfg.serial) else {
                if !announcedWaiting { Log.info("waiting for phone (USB debugging on, cable plugged in)…") }
                announcedWaiting = true
                Thread.sleep(forTimeInterval: 2)
                continue
            }
            announcedWaiting = false
            session(serial: serial)
            if !stopping { Thread.sleep(forTimeInterval: 1.5) }
        }
    }

    private func session(serial: String) {
        let phone = Phone(serial: serial)
        self.phone = phone
        Log.info("phone connected: \(phone.model) (\(serial))")
        guard phone.pushHelper() else {
            Thread.sleep(forTimeInterval: 3)
            return
        }
        let panel = phone.panelSizeMM()
        phone.prepare(dim: cfg.dimScreen)
        guard !stopping else { return }

        let poster = EventPoster()
        let engine = GestureEngine(cfg: cfg, poster: poster, queue: queue)
        if cfg.haptics {
            haptics = Haptics(serial: serial, ms: cfg.hapticMs, amplitude: cfg.hapticAmplitude)
            engine.haptics = haptics
        }
        self.engine = engine

        let stream = TouchStream()
        self.stream = stream
        var geometry: PadGeometry?
        stream.onHeader = { [cfg] h in
            let g = PadGeometry(header: h, panelX: panel.x, panelY: panel.y, orientation: cfg.orientation)
            geometry = g
            Log.info(String(format: "trackpad live: %.0f × %.0f mm, %@, %d fingers max",
                            g.size.w, g.size.h, cfg.orientation.rawValue, h.slots))
        }
        stream.onFrame = { [queue] raw, t in
            guard let g = geometry else { return }
            let touches = raw.map(g.map)
            Log.dbg(touches.map { String(format: "#%d(%.1f,%.1f m%d)", $0.id, $0.x, $0.y, $0.major) }
                .joined(separator: " "))
            queue.async { engine.handle(touches, t: t) }
        }
        stream.run(serial: serial, deviceName: cfg.deviceName, grab: grab)  // blocks

        queue.sync { engine.cancelAll() }
        haptics?.stop()
        haptics = nil
        self.stream = nil
        self.engine = nil
        phone.restore()
        Log.info("session ended")
    }

    private func checkAccessibility() {
        if CGPreflightPostEventAccess() { return }
        CGRequestPostEventAccess()
        Log.info("""
            ⚠️  magicpad needs Accessibility permission to move the cursor.
               System Settings → Privacy & Security → Accessibility → enable the app
               running magicpad (your terminal, or ~/.magicpad/bin/magicpad for the
               background agent), then restart magicpad.
            """)
    }

    private var signalSources: [DispatchSourceSignal] = []

    private func installSignalHandlers() {
        for sig in [SIGINT, SIGTERM] {
            signal(sig, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            src.setEventHandler { [weak self] in self?.shutdown() }
            src.resume()
            signalSources.append(src)
        }
    }

    private func shutdown() {
        guard !stopping else { exit(0) }
        stopping = true
        Log.info("stopping…")
        queue.sync { engine?.cancelAll() }
        stream?.stop()
        haptics?.stop()
        phone?.restore()
        Children.killAll()
        exit(0)
    }
}
