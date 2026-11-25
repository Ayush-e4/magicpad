import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

/// Trackpad-only gestures (pinch zoom, rotate, smart zoom, Spaces / Mission
/// Control swipes) have no public API. These are synthesised with the
/// private CGEvent fields that the real trackpad driver uses, the same
/// technique Mac Mouse Fix uses. Field numbers are reverse-engineered.
enum GesturePhase: Int64 {
    case began = 1, changed = 2, ended = 4, cancelled = 8
}

enum DockSwipe: Int64 {
    case horizontal = 1  // switch Spaces
    case vertical = 2    // Mission Control (up) / App Exposé (down)
    case pinch = 3       // Launchpad-or-Apps (in) / Show Desktop (out)
}

final class SystemGestures {
    // Private event types (CGSEventType) and HID event types.
    private let kCGSEventGesture: Int64 = 29
    private let kCGSEventDockControl: Int64 = 30
    private let hidRotation: Int64 = 5
    private let hidZoom: Int64 = 8
    private let hidZoomToggle: Int64 = 22  // smart zoom
    private let hidDockSwipe: Int64 = 23

    private func f(_ n: UInt32) -> CGEventField { CGEventField(rawValue: n)! }

    private func gestureEvent(_ hidType: Int64, at location: CGPoint) -> CGEvent? {
        guard let e = CGEvent(source: nil) else { return nil }
        e.setIntegerValueField(f(55), value: kCGSEventGesture)
        e.setIntegerValueField(f(110), value: hidType)
        e.location = location
        return e
    }

    /// `delta` is the change in magnification (e.g. 0.02 = 2% bigger).
    func magnify(_ delta: Double, phase: GesturePhase, at p: CGPoint) {
        guard let e = gestureEvent(hidZoom, at: p) else { return }
        e.setIntegerValueField(f(132), value: phase.rawValue)
        e.setDoubleValueField(f(113), value: delta)
        e.post(tap: .cghidEventTap)
    }

    /// `degrees` positive = counter-clockwise.
    func rotate(_ degrees: Double, phase: GesturePhase, at p: CGPoint) {
        guard let e = gestureEvent(hidRotation, at: p) else { return }
        e.setIntegerValueField(f(132), value: phase.rawValue)
        e.setDoubleValueField(f(114), value: degrees)
        e.post(tap: .cghidEventTap)
    }

    func smartZoom(at p: CGPoint) {
        gestureEvent(hidZoomToggle, at: p)?.post(tap: .cghidEventTap)
    }

    /// Dock swipes follow the fingers: `progress` is the accumulated offset
    /// (1.0 ≈ one full Space). `velocity` is only read on ended/cancelled.
    func dockSwipe(_ kind: DockSwipe, progress: Double, phase: GesturePhase, velocity: Double = 0) {
        guard let e = CGEvent(source: nil), let e2 = CGEvent(source: nil) else { return }
        e.setIntegerValueField(f(55), value: kCGSEventDockControl)
        e.setIntegerValueField(f(110), value: hidDockSwipe)
        e.setIntegerValueField(f(132), value: phase.rawValue)
        e.setIntegerValueField(f(134), value: phase.rawValue)
        e.setDoubleValueField(f(124), value: progress)
        e.setIntegerValueField(f(135), value: Int64(Float(progress).bitPattern))
        e.setIntegerValueField(f(41), value: 33231)
        e.setIntegerValueField(f(123), value: kind.rawValue)
        e.setIntegerValueField(f(165), value: kind.rawValue)
        if phase == .ended || phase == .cancelled {
            e.setDoubleValueField(f(129), value: velocity)
            e.setDoubleValueField(f(130), value: velocity)
            e.setIntegerValueField(f(136), value: phase == .cancelled ? 1 : 0)
        }
        e2.setIntegerValueField(f(55), value: kCGSEventGesture)
        e2.setIntegerValueField(f(41), value: 33231)
        e.post(tap: .cgSessionEventTap)
        e2.post(tap: .cgSessionEventTap)
    }
}

/// One-shot Dock actions. On macOS 26 the vertical and pinch dock-swipe events
/// are ignored (only horizontal Spaces swipes still work), so these go through
/// the Dock's own notification API instead, and Apps.app for the old Launchpad.
enum DockAction { case missionControl, appExpose, showDesktop, apps }

enum Dock {
    private typealias Send = @convention(c) (CFString, Int32) -> Void
    private static let send: Send? = {
        let h = dlopen("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", RTLD_NOW)
        return dlsym(h, "CoreDockSendNotification").map { unsafeBitCast($0, to: Send.self) }
    }()

    /// Which overview the Dock is showing right now, read from its AX tree
    /// ("Mission Control" / "App Expose" group), or nil.
    static func openOverview() -> String? {
        guard let pid = NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.dock").first?
            .processIdentifier else { return nil }
        var children: CFTypeRef?
        let app = AXUIElementCreateApplication(pid)
        guard AXUIElementCopyAttributeValue(app, kAXChildrenAttribute as CFString, &children) == .success,
              let list = children as? [AXUIElement] else { return nil }
        for el in list {
            var title: CFTypeRef?
            AXUIElementCopyAttributeValue(el, kAXTitleAttribute as CFString, &title)
            if let t = title as? String, t == "Mission Control" || t == "App Expose" { return t }
        }
        return nil
    }

    static func perform(_ action: DockAction) {
        switch action {
        case .missionControl, .appExpose:
            // Each notification toggles. Like the trackpad, the opposite swipe
            // closes whatever overview is open instead of switching to the other.
            if let open = openOverview() {
                let closes = (action == .missionControl) == (open == "App Expose")
                if closes { send?("com.apple.expose.awake" as CFString, 0) }
                return
            }
            let name = action == .missionControl ? "com.apple.expose.awake" : "com.apple.expose.front.awake"
            send?(name as CFString, 0)
        case .showDesktop:
            send?("com.apple.showdesktop.awake" as CFString, 0)
        case .apps:
            let apps = ["/System/Applications/Apps.app", "/System/Applications/Launchpad.app"]
            if let path = apps.first(where: { FileManager.default.fileExists(atPath: $0) }) {
                NSWorkspace.shared.open(URL(fileURLWithPath: path))
            }
        }
    }
}

/// Coasting scroll after a flick, like the real trackpad.
final class Momentum {
    private let poster: EventPoster
    private let queue: DispatchQueue
    private var timer: DispatchSourceTimer?
    private var vx = 0.0, vy = 0.0  // px/s
    private var started = false
    private let hz = 120.0
    private let decayPerMs = 0.9972

    init(poster: EventPoster, queue: DispatchQueue) {
        self.poster = poster
        self.queue = queue
    }

    var running: Bool { timer != nil }

    func start(vx: Double, vy: Double) {
        stop()
        guard hypot(vx, vy) > 150 else { return }
        self.vx = vx
        self.vy = vy
        started = false
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now(), repeating: 1 / hz, leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in self?.tick() }
        timer = t
        t.resume()
    }

    private func tick() {
        let dt = 1 / hz
        let k = pow(decayPerMs, dt * 1000)
        vx *= k
        vy *= k
        if hypot(vx, vy) < 12 { stop(); return }
        poster.scroll(dx: vx * dt, dy: vy * dt, momentum: started ? 2 : 1)
        started = true
    }

    /// Ends the coast (also called when a new touch lands, to "catch" it).
    func stop() {
        guard let t = timer else { return }
        t.cancel()
        timer = nil
        if started { poster.scroll(dx: 0, dy: 0, momentum: 3) }
        started = false
    }
}
