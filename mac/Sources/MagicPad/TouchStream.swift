import Foundation

struct RawContact {
    var id: Int
    var x: Int
    var y: Int
    var major: Int
}

struct TouchHeader {
    var xmax: Int
    var ymax: Int
    var majorMax: Int
    var slots: Int
}

/// Runs magicpad-grab over `adb exec-out` and decodes the Linux multitouch
/// (protocol B) event stream into whole frames of contacts.
final class TouchStream {
    var onHeader: (TouchHeader) -> Void = { _ in }
    var onFrame: ([RawContact], Double) -> Void = { _, _ in }

    private var process: Process?

    private struct Slot { var active = false; var id = 0; var x = 0; var y = 0; var major = 0 }

    // Linux input constants
    private let EV_SYN: UInt16 = 0, EV_ABS: UInt16 = 3, HEARTBEAT: UInt16 = 0xFFFF
    private let SYN_REPORT: UInt16 = 0, SYN_DROPPED: UInt16 = 3
    private let ABS_MT_SLOT: UInt16 = 0x2f, ABS_MT_TOUCH_MAJOR: UInt16 = 0x30
    private let ABS_MT_POSITION_X: UInt16 = 0x35, ABS_MT_POSITION_Y: UInt16 = 0x36
    private let ABS_MT_TRACKING_ID: UInt16 = 0x39
    private let eventSize = 24  // struct input_event on 64-bit Android

    /// Blocks until the stream ends (phone unplugged, helper died, or stop()).
    func run(serial: String, deviceName: String, grab: Bool) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: ADB.path)
        let cmd = "\(Phone.helperRemote) \(deviceName)\(grab ? "" : " --no-grab") 2>/dev/null"
        p.arguments = ["-s", serial, "exec-out", cmd]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch {
            Log.info("could not start adb: \(error)")
            return
        }
        Children.track(p)
        process = p
        readLoop(pipe.fileHandleForReading)
        p.waitUntilExit()
        process = nil
    }

    func stop() { process?.terminate() }

    private func readLoop(_ fh: FileHandle) {
        var buf = [UInt8]()
        var gotHeader = false
        var slots = [Slot](repeating: Slot(), count: 16)
        var cur = 0
        var dropping = false

        while true {
            let chunk = fh.availableData
            if chunk.isEmpty { return }  // EOF
            buf.append(contentsOf: chunk)

            if !gotHeader {
                guard let h = parseHeader(&buf) else { continue }
                gotHeader = true
                if h.slots > slots.count { slots = [Slot](repeating: Slot(), count: h.slots) }
                onHeader(h)
            }

            var off = 0
            while buf.count - off >= eventSize {
                let (sec, usec, type, code, value) = buf.withUnsafeBytes { raw -> (Int64, Int64, UInt16, UInt16, Int32) in
                    (raw.loadUnaligned(fromByteOffset: off, as: Int64.self),
                     raw.loadUnaligned(fromByteOffset: off + 8, as: Int64.self),
                     raw.loadUnaligned(fromByteOffset: off + 16, as: UInt16.self),
                     raw.loadUnaligned(fromByteOffset: off + 18, as: UInt16.self),
                     raw.loadUnaligned(fromByteOffset: off + 20, as: Int32.self))
                }
                off += eventSize
                let v = Int(value)

                if type == EV_SYN {
                    if code == SYN_DROPPED {
                        dropping = true
                    } else if code == SYN_REPORT {
                        if dropping {  // kernel buffer overflowed: start clean
                            dropping = false
                            for i in slots.indices { slots[i].active = false }
                        }
                        let t = Double(sec) + Double(usec) / 1_000_000
                        let frame = slots.filter(\.active).map {
                            RawContact(id: $0.id, x: $0.x, y: $0.y, major: $0.major)
                        }
                        onFrame(frame, t)
                    }
                    continue
                }
                guard type == EV_ABS, !dropping else { continue }  // skips heartbeats too
                switch code {
                case ABS_MT_SLOT: cur = max(0, min(slots.count - 1, v))
                case ABS_MT_TRACKING_ID:
                    if v < 0 { slots[cur].active = false } else { slots[cur].active = true; slots[cur].id = v }
                case ABS_MT_POSITION_X: slots[cur].x = v
                case ABS_MT_POSITION_Y: slots[cur].y = v
                case ABS_MT_TOUCH_MAJOR: slots[cur].major = v
                default: break
                }
            }
            buf.removeFirst(off)
        }
    }

    /// Finds "MPAD" + 5 int32s; anything before the magic is discarded.
    private func parseHeader(_ buf: inout [UInt8]) -> TouchHeader? {
        let magic: [UInt8] = Array("MPAD".utf8)
        guard buf.count >= 24 else { return nil }
        for i in 0...(buf.count - 24) where Array(buf[i..<i + 4]) == magic {
            let ints = buf.withUnsafeBytes { raw in
                (0..<5).map { Int(raw.loadUnaligned(fromByteOffset: i + 4 + $0 * 4, as: Int32.self)) }
            }
            buf.removeFirst(i + 24)
            return TouchHeader(xmax: ints[1], ymax: ints[2], majorMax: ints[3], slots: max(1, ints[4]))
        }
        return nil
    }
}
