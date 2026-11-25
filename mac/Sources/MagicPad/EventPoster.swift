import CoreGraphics
import Foundation

/// Posts ordinary mouse, keyboard and scroll events into macOS.
final class EventPoster {
    private let src = CGEventSource(stateID: .hidSystemState)
    private(set) var cursor = CGPoint.zero
    private(set) var leftHeld = false
    private var displays: [CGRect] = []
    private var scrollRemainder = (x: 0.0, y: 0.0)

    /// Re-reads the real cursor position and the display layout.
    func sync() {
        if !leftHeld, let loc = CGEvent(source: nil)?.location { cursor = loc }
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &ids, &count)
        displays = ids.map { CGDisplayBounds($0) }
    }

    // MARK: pointer

    func move(dx: Double, dy: Double) {
        let target = clamp(CGPoint(x: cursor.x + dx, y: cursor.y + dy))
        let type: CGEventType = leftHeld ? .leftMouseDragged : .mouseMoved
        guard let e = CGEvent(mouseEventSource: src, mouseType: type,
                              mouseCursorPosition: target, mouseButton: .left) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64((target.x - cursor.x).rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64((target.y - cursor.y).rounded()))
        e.post(tap: .cghidEventTap)
        cursor = target
    }

    func leftDown(clickState: Int64 = 1) {
        mouse(.leftMouseDown, .left, clickState)
        leftHeld = true
    }

    func leftUp(clickState: Int64 = 1) {
        guard leftHeld else { return }
        mouse(.leftMouseUp, .left, clickState)
        leftHeld = false
    }

    func click(_ button: CGMouseButton, clickState: Int64 = 1) {
        switch button {
        case .right:
            mouse(.rightMouseDown, .right, clickState)
            mouse(.rightMouseUp, .right, clickState)
        case .left:
            mouse(.leftMouseDown, .left, clickState)
            mouse(.leftMouseUp, .left, clickState)
        default:
            mouse(.otherMouseDown, button, clickState)
            mouse(.otherMouseUp, button, clickState)
        }
    }

    private func mouse(_ type: CGEventType, _ button: CGMouseButton, _ clickState: Int64) {
        guard let e = CGEvent(mouseEventSource: src, mouseType: type,
                              mouseCursorPosition: cursor, mouseButton: button) else { return }
        e.setIntegerValueField(.mouseEventClickState, value: clickState)
        e.post(tap: .cghidEventTap)
    }

    // MARK: keyboard

    func key(_ code: CGKeyCode, flags: CGEventFlags) {
        for down in [true, false] {
            guard let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down) else { continue }
            e.flags = flags
            e.post(tap: .cghidEventTap)
        }
    }

    // MARK: scrolling

    /// Trackpad-style continuous scroll. Phases follow NSEvent:
    /// scrollPhase 1 began, 2 changed, 4 ended, 128 mayBegin;
    /// momentumPhase 1 begin, 2 continue, 3 end.
    func scroll(dx: Double, dy: Double, phase: Int64 = 0, momentum: Int64 = 0) {
        scrollRemainder.x += dx
        scrollRemainder.y += dy
        let ix = Int32(scrollRemainder.x.rounded(.towardZero))
        let iy = Int32(scrollRemainder.y.rounded(.towardZero))
        scrollRemainder.x -= Double(ix)
        scrollRemainder.y -= Double(iy)
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .pixel,
                              wheelCount: 2, wheel1: iy, wheel2: ix, wheel3: 0) else { return }
        e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        e.setIntegerValueField(.scrollWheelEventPointDeltaAxis1, value: Int64(iy))
        e.setIntegerValueField(.scrollWheelEventPointDeltaAxis2, value: Int64(ix))
        e.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis1, value: dy)
        e.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis2, value: dx)
        e.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
        e.setIntegerValueField(.scrollWheelEventMomentumPhase, value: momentum)
        e.location = cursor
        e.post(tap: .cghidEventTap)
    }

    // MARK: helpers

    private func clamp(_ p: CGPoint) -> CGPoint {
        if displays.isEmpty { sync() }
        if displays.contains(where: { $0.contains(p) }) { return p }
        let r = displays.first(where: { $0.contains(cursor) }) ?? displays.first ?? .zero
        return CGPoint(x: min(max(p.x, r.minX), r.maxX - 1), y: min(max(p.y, r.minY), r.maxY - 1))
    }
}
