import CryptoKit
import Foundation

protocol SessionDelegate: AnyObject {
    /// Ask the user to approve a new phone. Called on the main queue.
    func session(_ s: Session, askToPair phone: String, code: String, reply: @escaping (Bool) -> Void)
    func sessionDidBecomeReady(_ s: Session)
    func sessionDidEnd(_ s: Session)
}

/// One phone connection: handshake → (pairing) → authentication → touches.
/// Follows PROTOCOL.md §2. Control messages run on `queue`; touch frames and
/// the gesture engine run on `engineQueue`.
final class Session: HapticOutput {
    private enum State { case hello, pairing, auth, ready, closed }

    let sid = UInt32.random(in: 1...UInt32.max)
    private(set) var phoneName = "phone"
    private(set) var device = ""
    private(set) var sessionKey = Data()

    weak var delegate: SessionDelegate?
    private let channel: ControlChannel
    private let store: PairingStore
    private var cfg: Config
    private let udpPort: () -> UInt16
    private let macAccess: () -> Bool
    private let queue: DispatchQueue
    let engineQueue = DispatchQueue(label: "magicpad.engine", qos: .userInteractive)

    private var state = State.hello
    private var handshake: Handshake?
    private var pairingKey: Data?
    private var lastHeard = Date()
    private var timer: DispatchSourceTimer?

    // Touch-frame state (engineQueue only)
    private var engine: GestureEngine?
    private var lastSeq: UInt32 = 0
    private var lastRawT: UInt32?
    private var clock = 0.0
    private var fingersDown = false
    private var lastFrameAt = Date()
    private var watchdog: DispatchSourceTimer?

    init(channel: ControlChannel, store: PairingStore, cfg: Config,
         queue: DispatchQueue, udpPort: @escaping () -> UInt16, macAccess: @escaping () -> Bool) {
        self.channel = channel
        self.store = store
        self.cfg = cfg
        self.queue = queue
        self.udpPort = udpPort
        self.macAccess = macAccess
        channel.onMessage = { [weak self] in self?.handle($0) }
        channel.onClose = { [weak self] in self?.ended() }
    }

    func start() {
        channel.start()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 2, repeating: 2)
        t.setEventHandler { [weak self] in self?.keepAlive() }
        t.resume()
        timer = t
    }

    func close() { queue.async { self.channel.close() } }

    /// New settings from the menu; takes effect on the next touch.
    func apply(_ c: Config) {
        engineQueue.async {
            self.cfg = c
            self.engine?.cfg = c
        }
    }

    // MARK: control messages

    private func handle(_ m: ControlChannel.Message) {
        lastHeard = Date()
        switch (state, m["t"] as? String) {
        case (_, "ping"): break
        case (_, "bye"): channel.close()
        case (.hello, "hello"): hello(m)
        case (.pairing, "pair"): pair()
        case (.pairing, "auth"), (.auth, "auth"): auth(m)
        case (.ready, "action"): action(m)
        case (.ready, _): break  // a newer phone's message this Mac doesn't know: ignore it
        default:
            Log.info("unexpected \(m["t"] ?? "?") from \(phoneName), closing")
            channel.close()
        }
    }

    /// Tells the phone whether this Mac may move the pointer (its Accessibility switch).
    func sendAccess(_ allowed: Bool) {
        queue.async {
            guard self.state == .ready else { return }
            self.channel.send(["t": "status", "access": allowed])
        }
    }

    /// A shortcut button on the phone. Unknown actions are ignored.
    private func action(_ m: ControlChannel.Message) {
        guard let name = m["a"] as? String, let a = ShortcutAction(rawValue: name) else {
            return Log.info("unknown action \(m["a"] ?? "?") from \(phoneName)")
        }
        DispatchQueue.main.async { a.perform() }
    }

    private func hello(_ m: ControlChannel.Message) {
        guard (m["v"] as? Int) == 1,
              let device = m["device"] as? String, device.count == 32,
              let pubB64 = m["pub"] as? String, let phonePub = Data(base64Encoded: pubB64),
              let nonceB64 = m["nonce"] as? String, let phoneNonce = Data(base64Encoded: nonceB64),
              phoneNonce.count == 16,
              let theirKey = try? P256.KeyAgreement.PublicKey(derRepresentation: phonePub)
        else { return channel.sendAndClose(["t": "denied", "reason": "bad hello"]) }

        self.device = device
        phoneName = String((m["name"] as? String ?? "phone").prefix(60))
        let mine = P256.KeyAgreement.PrivateKey()
        let macPub = mine.publicKey.derRepresentation
        let macNonce = MPCrypto.random(16)
        guard let secret = try? mine.sharedSecretFromKeyAgreement(with: theirKey) else {
            return channel.sendAndClose(["t": "denied", "reason": "bad key"])
        }
        let shared = secret.withUnsafeBytes { Data($0) }
        let salt = phoneNonce + macNonce
        handshake = Handshake(shared: shared, salt: salt, transcript: phonePub + macPub + salt)
        pairingKey = store.key(for: device)
        state = .pairing  // phone answers with "pair" or "auth"
        channel.send([
            "t": "hello", "v": 1, "id": store.macID, "name": Host.current().localizedName ?? "Mac",
            "pub": macPub.base64EncodedString(), "nonce": macNonce.base64EncodedString(),
            "known": pairingKey != nil,
        ])
    }

    private func pair() {
        guard let hs = handshake else { return channel.close() }
        state = .auth
        Log.info("pairing request from \(phoneName), code \(hs.code)")
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.delegate?.session(self, askToPair: self.phoneName, code: hs.code) { allowed in
                self.queue.async { self.pairingAnswered(allowed, hs) }
            }
        }
    }

    private func pairingAnswered(_ allowed: Bool, _ hs: Handshake) {
        guard state == .auth else { return }
        guard allowed else { return channel.sendAndClose(["t": "denied", "reason": "declined"]) }
        let k = hs.pairingKey()
        pairingKey = k
        store.add(device, name: phoneName, key: k)
        channel.send(["t": "paired"])
    }

    private func auth(_ m: ControlChannel.Message) {
        guard let hs = handshake, let k = pairingKey,
              let proofB64 = m["proof"] as? String, let proof = Data(base64Encoded: proofB64),
              MPCrypto.equal(proof, hs.proof("phone", pairing: k))
        else { return channel.sendAndClose(["t": "denied", "reason": "not paired"]) }
        sessionKey = hs.sessionKey(pairing: k)
        state = .ready
        engineQueue.sync { startEngine() }
        channel.send([
            "t": "ready", "proof": hs.proof("mac", pairing: k).base64EncodedString(),
            "udp": Int(udpPort()), "sid": Int(sid),
        ])
        // So the phone can explain a pointer that doesn't move.
        channel.send(["t": "status", "access": macAccess()])
        Log.info("\(phoneName) connected")
        DispatchQueue.main.async { self.delegate?.sessionDidBecomeReady(self) }
    }

    private func keepAlive() {
        if Date().timeIntervalSince(lastHeard) > 6 {
            Log.info("\(phoneName) stopped responding")
            return channel.close()
        }
        channel.send(["t": "ping"])
    }

    private func ended() {
        guard state != .closed else { return }
        let wasReady = state == .ready
        state = .closed
        timer?.cancel()
        engineQueue.async {
            self.watchdog?.cancel()
            self.engine?.cancelAll()
            self.engine = nil
        }
        if wasReady { Log.info("\(phoneName) disconnected") }
        DispatchQueue.main.async { self.delegate?.sessionDidEnd(self) }
    }

    // MARK: haptics (called by the engine)

    /// Called by the engine on engineQueue, where `cfg` lives.
    func play(_ kind: HapticKind) {
        let msg: ControlChannel.Message = ["t": "buzz", "k": kind.rawValue, "s": cfg.hapticStrength]
        queue.async { self.channel.send(msg) }
    }

    // MARK: touch frames (engineQueue)

    private func startEngine() {
        let engine = GestureEngine(cfg: cfg, poster: EventPoster(), queue: engineQueue)
        engine.haptics = self
        self.engine = engine
        let w = DispatchSource.makeTimerSource(queue: engineQueue)
        w.schedule(deadline: .now() + 0.1, repeating: 0.1)
        w.setEventHandler { [weak self] in self?.checkStalled() }
        w.resume()
        watchdog = w
    }

    /// A verified frame from the UDP receiver. Runs on engineQueue.
    func frame(seq: UInt32, rawT: UInt32, touches: [Touch]) {
        guard let engine, seq > lastSeq else { return }
        lastSeq = seq
        if let last = lastRawT {
            let d = rawT &- last
            if d < 0x8000_0000 { clock += Double(d) / 1000 }
        }
        lastRawT = rawT
        lastFrameAt = Date()
        fingersDown = !touches.isEmpty
        engine.handle(touches, t: clock)
    }

    /// Fingers down but frames stopped (Wi-Fi hiccup): treat as lifted.
    private func checkStalled() {
        guard fingersDown, Date().timeIntervalSince(lastFrameAt) > 0.4 else { return }
        fingersDown = false
        clock += 0.4
        engine?.handle([], t: clock)
    }
}
