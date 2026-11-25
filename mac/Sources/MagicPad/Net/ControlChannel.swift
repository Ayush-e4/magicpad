import Foundation
import Network

/// The TCP control connection: JSON messages, each prefixed by a u16
/// big-endian byte length (PROTOCOL.md §2).
final class ControlChannel {
    typealias Message = [String: Any]

    private let conn: NWConnection
    private let queue: DispatchQueue
    private var closed = false
    var onMessage: (Message) -> Void = { _ in }
    var onClose: () -> Void = {}

    var remoteHost: NWEndpoint.Host? {
        if case let .hostPort(host, _) = conn.endpoint { return host }
        return nil
    }

    init(_ conn: NWConnection, queue: DispatchQueue) {
        self.conn = conn
        self.queue = queue
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.readLength()
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        conn.start(queue: queue)
    }

    func send(_ msg: Message) {
        guard !closed, let body = try? JSONSerialization.data(withJSONObject: msg),
              body.count <= Int(UInt16.max) else { return }
        var frame = Data([UInt8(body.count >> 8), UInt8(body.count & 0xFF)])
        frame.append(body)
        conn.send(content: frame, completion: .contentProcessed { [weak self] err in
            if err != nil { self?.close() }
        })
    }

    /// Sends a final message, then closes once it has gone out.
    func sendAndClose(_ msg: Message) {
        send(msg)
        conn.send(content: nil, contentContext: .finalMessage, isComplete: true,
                  completion: .contentProcessed { [weak self] _ in self?.close() })
    }

    func close() {
        guard !closed else { return }
        closed = true
        conn.cancel()
        onClose()
    }

    private func readLength() {
        conn.receive(minimumIncompleteLength: 2, maximumLength: 2) { [weak self] data, _, done, err in
            guard let self, !self.closed else { return }
            guard let data, data.count == 2, err == nil else { return self.close() }
            let n = Int(data[data.startIndex]) << 8 | Int(data[data.startIndex + 1])
            if n == 0 { return done ? self.close() : self.readLength() }
            self.readBody(n)
        }
    }

    private func readBody(_ n: Int) {
        conn.receive(minimumIncompleteLength: n, maximumLength: n) { [weak self] data, _, _, err in
            guard let self, !self.closed else { return }
            guard let data, data.count == n, err == nil,
                  let msg = (try? JSONSerialization.jsonObject(with: data)) as? Message
            else { return self.close() }
            self.onMessage(msg)
            self.readLength()
        }
    }
}
