import CryptoKit
import Foundation

/// The key schedule from PROTOCOL.md §2. Every derivation uses HKDF-SHA256
/// with `salt = phoneNonce ‖ macNonce`.
struct Handshake {
    let shared: Data       // ECDH output (raw X coordinate, 32 bytes)
    let salt: Data         // phoneNonce ‖ macNonce
    let transcript: Data   // phonePub ‖ macPub ‖ salt (DER bytes)

    /// The 6-digit comparison code, e.g. "042917".
    var code: String {
        let b = MPCrypto.hkdf(shared, salt: salt, info: "magicpad v1 code", count: 4)
        let v = b.reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%06u", v % 1_000_000)
    }

    func pairingKey() -> Data {
        MPCrypto.hkdf(shared, salt: salt, info: "magicpad v1 pair", count: 32)
    }

    func sessionKey(pairing k: Data) -> Data {
        MPCrypto.hkdf(shared + k, salt: salt, info: "magicpad v1 session", count: 32)
    }

    func proof(_ who: String, pairing k: Data) -> Data {
        MPCrypto.hmac(k, Data(who.utf8) + transcript)
    }
}

enum MPCrypto {
    static func hkdf(_ ikm: Data, salt: Data, info: String, count: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                                         info: Data(info.utf8), outputByteCount: count)
        return key.withUnsafeBytes { Data($0) }
    }

    static func hmac(_ key: Data, _ message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }

    static func random(_ count: Int) -> Data {
        var b = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &b)
        return Data(b)
    }

    /// Constant-time comparison, so a forged tag can't be found byte by byte.
    static func equal(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
