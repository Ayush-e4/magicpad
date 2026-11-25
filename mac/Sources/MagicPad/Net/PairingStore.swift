import Foundation

/// Phones this Mac has paired with, plus this Mac's own id.
/// Stored in ~/.magicpad/paired.json, readable only by the user.
final class PairingStore {
    struct Phone: Codable {
        var name: String
        var key: Data  // pairing key K (base64 in JSON)
        var added: Date
    }

    private let url = Config.dir.appendingPathComponent("paired.json")
    private let idURL = Config.dir.appendingPathComponent("mac-id")
    private let lock = NSLock()
    private var phones: [String: Phone] = [:]
    let macID: String

    /// Called on the main queue whenever the list changes.
    var onChange: () -> Void = {}

    init() {
        let fm = FileManager.default
        try? fm.createDirectory(at: Config.dir, withIntermediateDirectories: true)
        if let id = try? String(contentsOf: idURL, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines), id.count == 32 {
            macID = id
        } else {
            macID = MPCrypto.random(16).hex
            try? macID.write(to: idURL, atomically: true, encoding: .utf8)
        }
        if let data = try? Data(contentsOf: url) {
            do {
                phones = try JSONDecoder().decode([String: Phone].self, from: data)
            } catch {
                Log.info("paired.json is unreadable, starting with no paired phones: \(error)")
            }
        }
    }

    func key(for device: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return phones[device]?.key
    }

    var all: [(id: String, phone: Phone)] {
        lock.lock(); defer { lock.unlock() }
        return phones.map { ($0.key, $0.value) }.sorted { $0.phone.added < $1.phone.added }
    }

    func add(_ device: String, name: String, key: Data) {
        update { $0[device] = Phone(name: name, key: key, added: Date()) }
    }

    func forget(_ device: String) {
        update { $0[device] = nil }
    }

    private func update(_ change: (inout [String: Phone]) -> Void) {
        lock.lock()
        change(&phones)
        let snapshot = phones
        lock.unlock()
        save(snapshot)
        DispatchQueue.main.async { self.onChange() }
    }

    private func save(_ snapshot: [String: Phone]) {
        do {
            let data = try JSONEncoder().encode(snapshot)
            try data.write(to: url, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch {
            Log.info("could not save paired phones: \(error.localizedDescription)")
        }
    }
}
