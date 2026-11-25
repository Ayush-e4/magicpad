import Foundation

/// A contact in pad space: millimetres, origin top-left as the user holds
/// the phone, y pointing down.
struct Touch {
    let id: Int
    let x: Double
    let y: Double
    let major: Int
}

struct PadGeometry {
    let header: TouchHeader
    let panelX: Double  // mm along raw X (portrait short edge)
    let panelY: Double  // mm along raw Y (portrait long edge)
    let orientation: Orientation

    var size: (w: Double, h: Double) {
        orientation == .portrait ? (panelX, panelY) : (panelY, panelX)
    }

    func map(_ c: RawContact) -> Touch {
        let mx = Double(c.x) / Double(max(1, header.xmax)) * panelX
        let my = Double(c.y) / Double(max(1, header.ymax)) * panelY
        switch orientation {
        case .portrait:         return Touch(id: c.id, x: mx, y: my, major: c.major)
        case .landscape:        return Touch(id: c.id, x: my, y: panelX - mx, major: c.major)
        case .landscapeFlipped: return Touch(id: c.id, x: panelY - my, y: mx, major: c.major)
        }
    }
}

/// Summary of a set of fingers, used to tell scroll / pinch / rotate apart.
struct Cluster {
    let count: Int
    let cx: Double
    let cy: Double
    let spread: Double  // 2 fingers: distance between them; 3+: mean distance to centroid
    let angle: Double   // 2 fingers: radians of the line joining them

    init(_ t: [Touch]) {
        let s = t.sorted { $0.id < $1.id }
        count = s.count
        let n = Double(max(1, s.count))
        cx = s.reduce(0) { $0 + $1.x } / n
        cy = s.reduce(0) { $0 + $1.y } / n
        if s.count == 2 {
            spread = hypot(s[1].x - s[0].x, s[1].y - s[0].y)
            angle = atan2(s[1].y - s[0].y, s[1].x - s[0].x)
        } else {
            let (x, y) = (cx, cy)
            spread = s.reduce(0) { $0 + hypot($1.x - x, $1.y - y) } / n
            angle = 0
        }
    }
}

/// Smallest signed difference between two angles, in radians.
func angleDelta(_ a: Double, _ b: Double) -> Double {
    var d = a - b
    while d > .pi { d -= 2 * .pi }
    while d < -.pi { d += 2 * .pi }
    return d
}

func smoothstep(_ x: Double) -> Double {
    let t = min(1, max(0, x))
    return t * t * (3 - 2 * t)
}
