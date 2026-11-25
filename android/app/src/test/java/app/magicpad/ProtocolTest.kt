package app.magicpad

import app.magicpad.net.Crypto
import app.magicpad.net.Frame
import app.magicpad.net.TouchPoint
import app.magicpad.net.hexToBytes
import app.magicpad.net.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors computed independently with Python's hmac/hashlib, so the Mac side
 * can check against the same numbers.
 */
class ProtocolTest {
    private val shared = ByteArray(32) { it.toByte() }
    private val salt = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm =
            Crypto.hkdf(
                ikm = ByteArray(22) { 0x0b },
                salt = ByteArray(13) { it.toByte() },
                info = ByteArray(10) { (0xf0 + it).toByte() },
                length = 42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun pairingCodeAndKeys() {
        assertEquals("282211", Crypto.pairingCode(shared, salt))
        val k = Crypto.pairingKey(shared, salt)
        assertEquals("af0db4fa6010d4f61f6b57c1d7755344c12051b7f53e4224974197f43e29cd0a", k.toHex())
        assertEquals(
            "f0d9f8b4361dcd1db01d0dec6c96f7af22ec96720e68492a1e011d203dd7d1db",
            Crypto.sessionKey(shared, k, salt).toHex(),
        )
        val transcript = "PHONEPUB".toByteArray() + "MACPUB".toByteArray() + salt
        assertEquals(
            "c6454458ac978034242bcc1ab9bd5f493ecbc6e4639e8621c8d1c860844251d6",
            Crypto.proof(k, "phone", transcript).toHex(),
        )
        assertEquals(
            "b1019ad5b117dbd9d53b83a7fba69904db8619a962ef9265f2cd133578737d11",
            Crypto.proof(k, "mac", transcript).toHex(),
        )
    }

    @Test
    fun codeIsAlwaysSixDigits() {
        repeat(200) {
            val code = Crypto.pairingCode(Crypto.randomBytes(32), Crypto.randomBytes(32))
            assertTrue(code, code.length == 6 && code.all { it.isDigit() })
        }
    }

    @Test
    fun frameLayoutIsBigEndianWithSixteenByteTag() {
        val bytes =
            Frame.encode(
                sid = 0x01020304,
                seq = 5,
                timeMs = 1000,
                widthMm = 148.0,
                heightMm = 68.0,
                touches = listOf(TouchPoint(id = 1, xMm = 10.5, yMm = 20.25, majorMm = 1.2)),
                sessionKey = ByteArray(32) { 0x11 },
            )
        val body = "4d5001010102030400000005000003e839d01a900101041a07e90c"
        assertEquals(body.length / 2 + Frame.TAG_SIZE, bytes.size)
        assertEquals(body + "96804f0b78ca197d387e1108c2da7165", bytes.toHex())
    }

    @Test
    fun frameClampsValuesAndCapsTouches() {
        val many = List(12) { TouchPoint(it, -5.0, 1000.0, 99.0) }
        val bytes = Frame.encode(0xFFFFFFFFL, 0x1_0000_0001L, -1, 0.0, 0.0, many, ByteArray(32))
        assertEquals(Frame.HEADER_SIZE + 10 * Frame.TOUCH_SIZE + Frame.TAG_SIZE, bytes.size)
        assertEquals("ffffffff", bytes.copyOfRange(4, 8).toHex()) // sid as u32
        assertEquals("00000001", bytes.copyOfRange(8, 12).toHex()) // seq wraps to u32
        assertEquals(10, bytes[20].toInt())
        val first = bytes.copyOfRange(21, 27).toHex()
        assertEquals("000000ffffff", first) // x clamped to 0, y and major to their max
    }

    @Test
    fun ecdhRoundTripGivesTheSameCode() {
        val phone = Crypto.newKeyPair()
        val mac = Crypto.newKeyPair()
        val a = Crypto.sharedSecret(phone, mac.public.encoded)
        val b = Crypto.sharedSecret(mac, phone.public.encoded)
        assertEquals(32, a.size)
        assertEquals(a.toHex(), b.toHex())
        assertEquals(Crypto.pairingCode(a, salt), Crypto.pairingCode(b, salt))
    }

    @Test
    fun hexRoundTrip() {
        val bytes = Crypto.randomBytes(16)
        assertEquals(bytes.toHex(), bytes.toHex().hexToBytes().toHex())
    }

    @Test
    fun reusedHmacMatchesFreshOnEveryFrame() {
        val key = ByteArray(32) { it.toByte() }
        val hmac = Frame.hmacFor(key)
        val touches = listOf(TouchPoint(id = 1, xMm = 12.34, yMm = 5.67, majorMm = 8.0))
        for (seq in 1L..3L) {
            val reused = Frame.encode(7, seq, 1000 + seq, 150.0, 70.0, touches, hmac)
            val fresh = Frame.encode(7, seq, 1000 + seq, 150.0, 70.0, touches, key)
            assertEquals(fresh.toHex(), reused.toHex())
        }
    }
}
