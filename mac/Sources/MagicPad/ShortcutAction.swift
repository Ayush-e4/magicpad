import AppKit

/// What the phone's shortcut buttons can do (PROTOCOL.md §2, `action`).
enum ShortcutAction: String {
    case playPause = "media.playpause"
    case next = "media.next"
    case previous = "media.previous"
    case screenshot = "screenshot.screen"

    func perform() {
        switch self {
        case .playPause: Self.mediaKey(Self.keyPlay)
        case .next: Self.mediaKey(Self.keyNext)
        case .previous: Self.mediaKey(Self.keyPrevious)
        case .screenshot: Self.keys(0x14, [.maskCommand, .maskShift])  // ⌘⇧3, saved to the Desktop
        }
    }

    // NX_KEYTYPE_* from IOKit's ev_keymap.h: the media keys on an Apple keyboard.
    private static let keyPlay: Int32 = 16
    private static let keyNext: Int32 = 17
    private static let keyPrevious: Int32 = 18

    private static let source = CGEventSource(stateID: .hidSystemState)

    /// Media keys are "system defined" events (subtype 8), not ordinary key presses.
    /// They go to whatever app is playing, like the keyboard's own keys.
    private static func mediaKey(_ key: Int32) {
        for down in [true, false] {
            let state = down ? 0xA : 0xB
            let event = NSEvent.otherEvent(
                with: .systemDefined, location: .zero,
                modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                timestamp: 0, windowNumber: 0, context: nil, subtype: 8,
                data1: Int(key) << 16 | state << 8, data2: -1)
            event?.cgEvent?.post(tap: .cghidEventTap)
        }
    }

    private static func keys(_ code: CGKeyCode, _ flags: CGEventFlags) {
        for down in [true, false] {
            guard let e = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else { continue }
            e.flags = flags
            e.post(tap: .cghidEventTap)
        }
    }
}
