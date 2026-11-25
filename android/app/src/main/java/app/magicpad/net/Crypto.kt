package app.magicpad.net

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The cryptography of PROTOCOL.md §2. Pure JVM, so it runs in unit tests. */
object Crypto {
    const val INFO_CODE = "magicpad v1 code"
    const val INFO_PAIR = "magicpad v1 pair"
    const val INFO_SESSION = "magicpad v1 session"

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** A fresh P-256 key pair; its public key encodes as DER SubjectPublicKeyInfo. */
    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** ECDH shared secret: the raw 32-byte X coordinate, left-padded with zeros. */
    fun sharedSecret(
        own: KeyPair,
        otherPublicDer: ByteArray,
    ): ByteArray {
        val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherPublicDer))
        val secret =
            KeyAgreement.getInstance("ECDH").run {
                init(own.private)
                doPhase(other, true)
                generateSecret()
            }
        if (secret.size == 32) return secret
        require(secret.size < 32) { "unexpected ECDH secret length ${secret.size}" }
        return ByteArray(32 - secret.size) + secret
    }

    fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** HKDF-SHA256 (RFC 5869): extract with [salt], expand to [length] bytes. */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length $length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - filled)
            block.copyInto(out, filled, 0, n)
            filled += n
            counter++
        }
        return out
    }

    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: String,
        length: Int,
    ): ByteArray = hkdf(ikm, salt, info.toByteArray(Charsets.UTF_8), length)

    /** The 6-digit pairing code both screens show, e.g. "042917". */
    fun pairingCode(
        shared: ByteArray,
        salt: ByteArray,
    ): String {
        val b = hkdf(shared, salt, INFO_CODE, 4)
        val u32 =
            ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        return (u32 % 1_000_000).toString().padStart(6, '0')
    }

    fun pairingKey(
        shared: ByteArray,
        salt: ByteArray,
    ): ByteArray = hkdf(shared, salt, INFO_PAIR, 32)

    fun sessionKey(
        shared: ByteArray,
        pairingKey: ByteArray,
        salt: ByteArray,
    ): ByteArray = hkdf(shared + pairingKey, salt, INFO_SESSION, 32)

    /** HMAC(K, label ‖ transcript), label = "phone" or "mac". */
    fun proof(
        pairingKey: ByteArray,
        label: String,
        transcript: ByteArray,
    ): ByteArray = hmac(pairingKey, label.toByteArray(Charsets.UTF_8), transcript)

    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
