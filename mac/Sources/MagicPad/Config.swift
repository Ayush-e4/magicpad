import Foundation

enum Orientation: String, Codable {
    case landscape          // phone top on the LEFT (camera left)
    case landscapeFlipped   // phone top on the RIGHT
    case portrait
}

/// All tunables. Any key can be overridden in ~/.magicpad/config.json;
/// missing keys keep these defaults.
struct Config: Codable {
    var orientation: Orientation = .landscape

    // Pointer: gain is points of cursor travel per mm of finger travel,
    // blended from minGain (slow) to maxGain (fast) by finger speed.
    var pointerSpeed: Double = 2.0
    var minGain: Double = 2.5
    var maxGain: Double = 16.0
    var accelVelocity: Double = 320  // mm/s at which maxGain is reached

    // Scrolling
    var scrollSpeed: Double = 1.0
    var naturalScroll: Bool? = nil   // nil = follow the macOS setting
    var momentum: Bool = true

    // Clicking
    var tapToClick: Bool = true
    var tapMaxTime: Double = 0.22    // s; longer touches are not taps
    var tapSlopMm: Double = 1.5      // max finger travel for a tap
    var tapDragWindow: Double = 0.30 // s between tap and touch for tap-drag
    var smartZoom: Bool = true       // two-finger double-tap
    var threeFingerDrag: Bool? = nil // nil = follow the macOS setting

    // System gestures
    var swipeDistanceMm: Double = 60 // finger travel for a full Spaces swipe
    var pinchDistanceMm: Double = 35
    var gestureMode: String = "native" // "native" (follows fingers) | "keys"

    // Phone
    var haptics: Bool = true
    var hapticStrength: Double = 0.7  // Wi-Fi app: 0.0–1.0
    var scrollTicks: Bool = false     // light tick every few lines of scrolling
    var hapticMs: Int = 14
    var hapticAmplitude: Int = 180  // 1-255
    var dimScreen: Bool = true
    var palmMajor: Int = 0          // raw TOUCH_MAJOR above which = palm; 0 = off
    var deviceName: String = "sec_touchscreen"
    var serial: String? = nil       // pick a specific phone; nil = first one

    static let dir = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(".magicpad")
    static let url = dir.appendingPathComponent("config.json")

    static func load() -> Config {
        let defaults = Config()
        guard let data = try? Data(contentsOf: url),
              let user = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let baseData = try? JSONEncoder().encode(defaults),
              var base = (try? JSONSerialization.jsonObject(with: baseData)) as? [String: Any]
        else { return defaults }
        base.merge(user) { _, new in new }
        guard let merged = try? JSONSerialization.data(withJSONObject: base),
              let cfg = try? JSONDecoder().decode(Config.self, from: merged)
        else {
            Log.info("config.json is invalid, using defaults")
            return defaults
        }
        return cfg
    }

    func save() {
        do {
            try FileManager.default.createDirectory(at: Config.dir, withIntermediateDirectories: true)
            let enc = JSONEncoder()
            enc.outputFormatting = [.prettyPrinted, .sortedKeys]
            try enc.encode(self).write(to: Config.url, options: .atomic)
        } catch {
            Log.info("could not save config: \(error.localizedDescription)")
        }
    }

    /// Writes the default config (only if none exists) and returns its path.
    static func writeDefaultIfMissing() -> String {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            let enc = JSONEncoder()
            enc.outputFormatting = [.prettyPrinted, .sortedKeys]
            try? enc.encode(Config()).write(to: url)
        }
        return url.path
    }

    // Resolved values that may follow the macOS trackpad settings.
    var resolvedNaturalScroll: Bool {
        if let v = naturalScroll { return v }
        let g = UserDefaults.standard.persistentDomain(forName: UserDefaults.globalDomain)
        return (g?["com.apple.swipescrolldirection"] as? Bool) ?? true
    }

    var resolvedThreeFingerDrag: Bool {
        if let v = threeFingerDrag { return v }
        return UserDefaults(suiteName: "com.apple.AppleMultitouchTrackpad")?
            .bool(forKey: "TrackpadThreeFingerDrag") ?? false
    }
}

enum Log {
    static var debug = false
    static func info(_ s: String) {
        let ts = ISO8601DateFormatter().string(from: Date())
        FileHandle.standardError.write("[\(ts)] \(s)\n".data(using: .utf8)!)
    }
    static func dbg(_ s: @autoclosure () -> String) {
        if debug { FileHandle.standardError.write("· \(s())\n".data(using: .utf8)!) }
    }
}
