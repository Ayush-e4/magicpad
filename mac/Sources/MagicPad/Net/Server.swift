import Foundation
import Network

/// What the server needs from the UI (the menu-bar app). Main queue only.
protocol ServerUI: AnyObject {
    func askToPair(phone: String, code: String, reply: @escaping (Bool) -> Void)
    func phonesChanged()
}

/// Advertises the Mac over Bonjour and runs one Session per phone.
final class Server: SessionDelegate {
    let store = PairingStore()
    weak var ui: ServerUI?

    private(set) var cfg: Config
    private let queue = DispatchQueue(label: "magicpad.net")
    private var tcp: NWListener?
    private lazy var frames = FrameReceiver { [weak self] sid in self?.liveSession(sid) }

    private let lock = NSLock()
    private var live: [UInt32: Session] = [:]  // ready sessions, by sid
    private var pending: [ObjectIdentifier: Session] = [:]  // all sessions, keeps them alive

    /// Names of the phones connected right now (main queue).
    private(set) var connected: [String] = [] {
        didSet { holdLowLatency(!connected.isEmpty) }
    }

    /// Held while any phone is connected (main queue). A menu-bar app with no windows is
    /// a prime App Nap target: macOS would lower its priority and coalesce its timers,
    /// which shows up as a sticky first touch and uneven momentum scrolling.
    private var latencyActivity: NSObjectProtocol?

    init(cfg: Config) {
        self.cfg = cfg
        store.onChange = { [weak self] in self?.ui?.phonesChanged() }
    }

    func start() {
        do {
            try frames.start()
            try startTCP()
        } catch {
            Log.info("could not start the network listener: \(error); retrying in 3 s")
            queue.asyncAfter(deadline: .now() + 3) { self.start() }
        }
    }

    private func startTCP() throws {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        params.includePeerToPeer = false
        let l = try NWListener(using: params)
        let txt = NWTXTRecord(["v": "1", "id": store.macID])
        l.service = NWListener.Service(name: nil, type: "_magicpad._tcp", domain: nil, txtRecord: txt.data)
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                Log.info("listening on port \(l.port?.rawValue ?? 0), advertising _magicpad._tcp")
            case .failed(let err):
                Log.info("listener failed: \(err); restarting")
                l.cancel()
                self?.queue.asyncAfter(deadline: .now() + 2) { try? self?.startTCP() }
            default: break
            }
        }
        l.start(queue: queue)
        tcp = l
    }

    private func accept(_ conn: NWConnection) {
        let session = Session(channel: ControlChannel(conn, queue: queue), store: store, cfg: cfg,
                              queue: queue, udpPort: { [weak self] in self?.frames.port ?? 0 })
        session.delegate = self
        lock.lock(); pending[ObjectIdentifier(session)] = session; lock.unlock()
        session.start()
    }

    private func liveSession(_ sid: UInt32) -> Session? {
        lock.lock(); defer { lock.unlock() }
        return live[sid]
    }

    /// Changes settings, saves them, and applies them to connected phones.
    func update(_ change: (inout Config) -> Void) {
        change(&cfg)
        cfg.save()
        lock.lock()
        let sessions = Array(pending.values)
        lock.unlock()
        sessions.forEach { $0.apply(cfg) }
    }

    /// Drops a paired phone and disconnects it if it's connected.
    func forget(_ device: String) {
        store.forget(device)
        lock.lock()
        let sessions = pending.values.filter { $0.device == device }
        lock.unlock()
        sessions.forEach { $0.close() }
    }

    private func holdLowLatency(_ on: Bool) {
        if on, latencyActivity == nil {
            latencyActivity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .latencyCritical], reason: "Phone trackpad connected")
        } else if !on, let a = latencyActivity {
            ProcessInfo.processInfo.endActivity(a)
            latencyActivity = nil
        }
    }

    // MARK: SessionDelegate (main queue)

    func session(_ s: Session, askToPair phone: String, code: String, reply: @escaping (Bool) -> Void) {
        guard let ui else { return reply(false) }
        ui.askToPair(phone: phone, code: code, reply: reply)
    }

    func sessionDidBecomeReady(_ s: Session) {
        lock.lock(); live[s.sid] = s; lock.unlock()
        connected.append(s.phoneName)
        ui?.phonesChanged()
    }

    func sessionDidEnd(_ s: Session) {
        lock.lock()
        let wasLive = live.removeValue(forKey: s.sid) != nil
        pending[ObjectIdentifier(s)] = nil
        lock.unlock()
        if wasLive, let i = connected.firstIndex(of: s.phoneName) { connected.remove(at: i) }
        ui?.phonesChanged()
    }
}
