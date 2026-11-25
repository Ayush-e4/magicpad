import Foundation
import Network

/// Receives UDP touch frames (PROTOCOL.md §3), verifies them, and hands
/// them to the session they belong to.
final class FrameReceiver {
    private let queue = DispatchQueue(label: "magicpad.udp", qos: .userInteractive)
    private var listener: NWListener?
    private let lookup: (UInt32) -> Session?
    private(set) var port: UInt16 = 0

    private static let header = 21
    private static let tagSize = 16

    init(lookup: @escaping (UInt32) -> Session?) {
        self.lookup = lookup
    }

    func start() throws {
        let l = try NWListener(using: .udp)
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { [weak self, weak l] state in
            switch state {
            case .ready: self?.port = l?.port?.rawValue ?? 0
            case .failed(let err): Log.info("UDP listener failed: \(err)")
            default: break
            }
        }
        l.start(queue: queue)
        listener = l
    }

    private func accept(_ c: NWConnection) {
        c.start(queue: queue)
        receive(c)
    }

    private func receive(_ c: NWConnection) {
        c.receiveMessage { [weak self] data, _, _, err in
            if let data { self?.packet(data) }
            if err == nil { self?.receive(c) } else { c.cancel() }
        }
    }

    private func packet(_ d: Data) {
        let b = [UInt8](d)
        guard b.count >= Self.header + Self.tagSize,
              b[0] == 0x4D, b[1] == 0x50, b[2] == 1, b[3] == 1 else { return }
        let n = Int(b[20])
        guard n <= 10, b.count == Self.header + 6 * n + Self.tagSize else { return }
        guard let session = lookup(be32(b, 4)), !session.sessionKey.isEmpty else { return }

        let body = Data(b[0 ..< b.count - Self.tagSize])
        let tag = Data(b[(b.count - Self.tagSize)...])
        let expected = MPCrypto.hmac(session.sessionKey, body).prefix(Self.tagSize)
        guard MPCrypto.equal(Data(expected), tag) else { return }

        var touches: [Touch] = []
        touches.reserveCapacity(n)
        for i in 0 ..< n {
            let o = Self.header + 6 * i
            touches.append(Touch(id: Int(b[o]),
                                 x: Double(be16(b, o + 1)) / 100,
                                 y: Double(be16(b, o + 3)) / 100,
                                 major: Int(b[o + 5])))
        }
        let seq = be32(b, 8), t = be32(b, 12)
        session.engineQueue.async { session.frame(seq: seq, rawT: t, touches: touches) }
    }

    private func be16(_ b: [UInt8], _ i: Int) -> UInt16 { UInt16(b[i]) << 8 | UInt16(b[i + 1]) }

    private func be32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) << 24 | UInt32(b[i + 1]) << 16 | UInt32(b[i + 2]) << 8 | UInt32(b[i + 3])
    }
}
