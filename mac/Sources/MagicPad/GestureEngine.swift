import AppKit
import CoreGraphics
import Foundation

/// Turns frames of touches into macOS input. A "session" runs from the first
/// finger landing to the last finger lifting. Within a session the engine
/// waits (undecided) until the fingers have moved enough to tell what the
/// gesture is, then locks into that mode until the fingers change.
final class GestureEngine {
    private enum Mode: Equatable {
        case idle, undecided, ignore
        case pointer, tapDrag, threeDrag
        case scroll, zoom, rotate
        case swipe(DockSwipe), dockPinch
    }

    var cfg: Config  // replaced live when settings change
    private let poster: EventPoster
    private let sys = SystemGestures()
    private let momentum: Momentum
    private let queue: DispatchQueue
    private let natural: Bool
    private let threeDrag: Bool
    var haptics: HapticOutput?

    // Session state (times are on the phone's clock)
    private var mode: Mode = .idle
    private var active = false
    private var sessionStart = 0.0
    private var maxFingers = 0
    private var moved = false
    private var tapDragArmed = false
    private var starts: [Int: Touch] = [:]
    private var prevIDs: Set<Int> = []
    private var prevT = 0.0
    private var ref: Cluster?   // where the current set of fingers started
    private var prev: Cluster?  // previous frame
    private var palms: Set<Int> = []

    // Per-gesture accumulators
    private var pointerVel = 0.0
    private var scrollVel = (x: 0.0, y: 0.0)
    private var lastScrollMove = 0.0
    private var swipeOffset = 0.0
    private var swipeVel = 0.0
    private var scrollTickTravel = 0.0

    // Tap history
    private var lastTapTime = -10.0
    private var clickCount = 0
    private var lastTwoTapTime = -10.0
    private var pendingRightClick: DispatchWorkItem?

    // Gesture directions: flip a sign if that gesture runs backwards.
    // Spaces swipes can be tested with `magicpad probe spaces-left|right`.
    private let horizontalSwipeSign = -1.0
    private let verticalSwipeSign = -1.0
    private let pinchSign = 1.0

    init(cfg: Config, poster: EventPoster, queue: DispatchQueue) {
        self.cfg = cfg
        self.poster = poster
        self.queue = queue
        momentum = Momentum(poster: poster, queue: queue)
        natural = cfg.resolvedNaturalScroll
        threeDrag = cfg.resolvedThreeFingerDrag
        Log.info("natural scroll: \(natural), three-finger drag: \(threeDrag)")
    }

    // MARK: frame input

    func handle(_ all: [Touch], t: Double) {
        if cfg.palmMajor > 0 {
            for c in all where c.major >= cfg.palmMajor { palms.insert(c.id) }
        }
        let touches = all.filter { !palms.contains($0.id) }
        if all.isEmpty { palms.removeAll() }
        let n = touches.count

        if !active {
            guard n > 0 else { return }
            begin(t: t, n: n)
        }
        if n == 0 { end(t: t); return }

        for c in touches where starts[c.id] == nil { starts[c.id] = c }
        maxFingers = max(maxFingers, n)
        if !moved {
            moved = touches.contains { c in
                starts[c.id].map { hypot(c.x - $0.x, c.y - $0.y) > cfg.tapSlopMm } ?? false
            }
        }

        let cluster = Cluster(touches)
        let ids = Set(touches.map(\.id))
        if ids != prevIDs {
            fingersChanged(n: n)
            ref = cluster
            prev = cluster
        } else if let p = prev {
            update(cluster, p, dt: max(1.0 / 240, t - prevT), t: t)
            prev = cluster
        }
        prevIDs = ids
        prevT = t
    }

    /// Called when the stream drops mid-gesture: release anything held.
    func cancelAll() {
        if active { end(t: prevT) }
        momentum.stop()
        poster.leftUp()
    }

    // MARK: session lifecycle

    private func begin(t: Double, n: Int) {
        momentum.stop()
        poster.sync()
        active = true
        mode = .undecided
        sessionStart = t
        maxFingers = 0
        moved = false
        starts = [:]
        prevIDs = []
        ref = nil
        prev = nil
        pointerVel = 0
        tapDragArmed = cfg.tapToClick && n == 1 && t - lastTapTime < cfg.tapDragWindow
    }

    private func end(t: Double) {
        let quick = t - sessionStart <= cfg.tapMaxTime
        switch mode {
        case .undecided, .pointer:
            if cfg.tapToClick && quick && !moved { tap(fingers: maxFingers, t: t) }
        case .tapDrag, .threeDrag:
            poster.leftUp()
        case .scroll:
            finishScroll(momentum: true)
        case .zoom, .rotate:
            finishPinchRotate()
        case .swipe(.horizontal):
            sys.dockSwipe(.horizontal, progress: swipeOffset, phase: .ended, velocity: swipeVel)
            if abs(swipeOffset) > 0.35 { buzz(.confirm) }  // far enough that macOS switches
        case .swipe, .dockPinch, .idle, .ignore:
            break
        }
        Log.dbg("end \(mode) fingers=\(maxFingers) moved=\(moved) dur=\(String(format: "%.3f", t - sessionStart))")
        active = false
        mode = .idle
    }

    private func fingersChanged(n: Int) {
        switch mode {
        case .pointer:
            mode = .undecided  // a second finger joined: maybe a scroll
        case .scroll:
            finishScroll(momentum: n < 2)
            mode = n < 2 ? .ignore : .undecided
        case .zoom, .rotate:
            finishPinchRotate()
            mode = n < 2 ? .ignore : .undecided
        default:
            break  // drags and swipes carry on with whatever fingers remain
        }
    }

    // MARK: deciding what the fingers are doing

    private func decide(_ c: Cluster, t: Double) {
        guard let r = ref else { return }
        let travel = hypot(c.cx - r.cx, c.cy - r.cy)
        let spreadChange = abs(c.spread - r.spread)

        switch c.count {
        case 1:
            guard travel > 0.25 else { return }
            if tapDragArmed && t - sessionStart < 0.5 {
                poster.leftDown()
                mode = .tapDrag
                buzz(.drag)
            } else {
                mode = .pointer
            }
        case 2:
            let arc = abs(angleDelta(c.angle, r.angle)) * c.spread / 2
            guard max(travel, spreadChange, arc) > 1.2 else { return }
            if travel >= spreadChange && travel >= arc {
                mode = .scroll
                scrollVel = (0, 0)
                scrollTickTravel = 0
                poster.scroll(dx: 0, dy: 0, phase: 1)
            } else if spreadChange >= arc {
                mode = .zoom
                sys.magnify(0, phase: .began, at: poster.cursor)
            } else {
                mode = .rotate
                sys.rotate(0, phase: .began, at: poster.cursor)
            }
        case 3 where threeDrag:
            guard travel > 0.8 else { return }
            poster.leftDown()
            mode = .threeDrag
            buzz(.drag)
        default:
            if c.count >= 4 && spreadChange > 4 && spreadChange > travel {
                startDockGesture(.pinch, c, r)
            } else if c.count <= 4 && travel > 3 {
                let horizontal = abs(c.cx - r.cx) > abs(c.cy - r.cy)
                startDockGesture(horizontal ? .horizontal : .vertical, c, r)
            }
        }
    }

    private func startDockGesture(_ kind: DockSwipe, _ c: Cluster, _ r: Cluster) {
        if cfg.gestureMode == "keys" {
            sendGestureKey(kind, dx: c.cx - r.cx, dy: c.cy - r.cy)
            mode = .ignore
            return
        }
        swipeOffset = 0
        swipeVel = 0
        mode = kind == .pinch ? .dockPinch : .swipe(kind)
        if kind == .horizontal { sys.dockSwipe(kind, progress: 0, phase: .began) }
    }

    /// Fallback that uses the default Mission Control keyboard shortcuts.
    private func sendGestureKey(_ kind: DockSwipe, dx: Double, dy: Double) {
        let ctrl: CGEventFlags = [.maskControl, .maskSecondaryFn]
        switch kind {
        case .horizontal: poster.key(dx < 0 ? 124 : 123, flags: ctrl)  // ⌃→ / ⌃←
        case .vertical: poster.key(dy < 0 ? 126 : 125, flags: ctrl)    // ⌃↑ / ⌃↓
        case .pinch: break
        }
    }

    // MARK: per-frame updates

    private func update(_ c: Cluster, _ p: Cluster, dt: Double, t: Double) {
        let dx = c.cx - p.cx, dy = c.cy - p.cy
        switch mode {
        case .undecided:
            decide(c, t: t)
        case .pointer, .tapDrag, .threeDrag:
            movePointer(dx, dy, dt: dt)
        case .scroll:
            scroll(dx, dy, dt: dt, t: t)
        case .zoom:
            if p.spread > 1 { sys.magnify(c.spread / p.spread - 1, phase: .changed, at: poster.cursor) }
        case .rotate:
            sys.rotate(-angleDelta(c.angle, p.angle) * 180 / .pi, phase: .changed, at: poster.cursor)
        case .swipe(let kind):
            let sign = kind == .horizontal ? horizontalSwipeSign : verticalSwipeSign
            let d = (kind == .horizontal ? dx : dy) * sign / cfg.swipeDistanceMm
            advanceDock(kind, d, dt: dt)
        case .dockPinch:
            advanceDock(.pinch, (c.spread - p.spread) * pinchSign / cfg.pinchDistanceMm, dt: dt)
        case .idle, .ignore:
            break
        }
    }

    private func advanceDock(_ kind: DockSwipe, _ d: Double, dt: Double) {
        swipeOffset += d
        swipeVel = 0.6 * swipeVel + 0.4 * (d / dt)
        if kind == .horizontal {
            sys.dockSwipe(kind, progress: swipeOffset, phase: .changed)
            return
        }
        // Vertical swipes and pinches fire once, part-way through the gesture.
        let threshold = kind == .vertical ? 0.25 : 0.3  // ≈15 mm swipe / ≈10 mm pinch
        guard abs(swipeOffset) >= threshold else { return }
        let action: DockAction = kind == .vertical
            ? (swipeOffset > 0 ? .missionControl : .appExpose)
            : (swipeOffset < 0 ? .apps : .showDesktop)
        Log.dbg("dock action \(action)")
        DispatchQueue.main.async { Dock.perform(action) }
        buzz(.confirm)
        mode = .ignore
    }

    private func movePointer(_ dx: Double, _ dy: Double, dt: Double) {
        pointerVel = 0.5 * pointerVel + 0.5 * hypot(dx, dy) / dt  // mm/s, smoothed
        let gain = (cfg.minGain + (cfg.maxGain - cfg.minGain) * smoothstep(pointerVel / cfg.accelVelocity))
            * cfg.pointerSpeed
        poster.move(dx: dx * gain, dy: dy * gain)
    }

    private func scroll(_ dx: Double, _ dy: Double, dt: Double, t: Double) {
        let speed = hypot(dx, dy) / dt
        let gain = (6 + 10 * smoothstep(speed / 400)) * cfg.scrollSpeed * (natural ? 1 : -1)
        let px = dx * gain, py = dy * gain
        poster.scroll(dx: px, dy: py, phase: 2)
        scrollVel.x = 0.5 * scrollVel.x + 0.5 * px / dt
        scrollVel.y = 0.5 * scrollVel.y + 0.5 * py / dt
        if hypot(dx, dy) > 0.02 { lastScrollMove = t }
        if cfg.scrollTicks {
            scrollTickTravel += hypot(px, py)
            if scrollTickTravel >= 60 {  // about three lines of text
                scrollTickTravel = 0
                buzz(.tick)
            }
        }
    }

    private func finishScroll(momentum withMomentum: Bool) {
        poster.scroll(dx: 0, dy: 0, phase: 4)
        if withMomentum && cfg.momentum && prevT - lastScrollMove < 0.05 {
            momentum.start(vx: scrollVel.x, vy: scrollVel.y)
        }
    }

    private func finishPinchRotate() {
        if mode == .zoom { sys.magnify(0, phase: .ended, at: poster.cursor) }
        if mode == .rotate { sys.rotate(0, phase: .ended, at: poster.cursor) }
    }

    // MARK: taps

    private func tap(fingers: Int, t: Double) {
        switch fingers {
        case 1:
            clickCount = t - lastTapTime < NSEvent.doubleClickInterval ? min(clickCount + 1, 3) : 1
            poster.click(.left, clickState: Int64(clickCount))
            buzz(.click)
            lastTapTime = t
        case 2:
            guard cfg.smartZoom else { poster.click(.right); buzz(.heavy); return }
            // Hold the right-click briefly so a second two-finger tap can
            // turn it into Smart Zoom instead (what macOS does too).
            if let pending = pendingRightClick, t - lastTwoTapTime < 0.35 {
                pending.cancel()
                pendingRightClick = nil
                sys.smartZoom(at: poster.cursor)
                buzz(.confirm)
            } else {
                let work = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.pendingRightClick = nil
                    self.poster.click(.right)
                    self.buzz(.heavy)
                }
                pendingRightClick = work
                queue.asyncAfter(deadline: .now() + 0.25, execute: work)
            }
            lastTwoTapTime = t
        case 3:
            poster.key(2, flags: [.maskControl, .maskCommand])  // Look Up (⌃⌘D)
            buzz(.confirm)
        default:
            break
        }
    }

    private func buzz(_ kind: HapticKind) {
        if cfg.haptics { haptics?.play(kind) }
    }
}

/// The haptic kinds of PROTOCOL.md (`buzz` → `k`).
enum HapticKind: String {
    case click, heavy, drag, confirm, tick
}

/// Where the engine sends haptics: adb over USB, or the Wi-Fi session.
protocol HapticOutput: AnyObject {
    func play(_ kind: HapticKind)
}
