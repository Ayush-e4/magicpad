package app.magicpad.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One finger on the pad, in millimetres from the top-left corner. */
data class TouchPoint(
    val id: Int,
    val xMm: Double,
    val yMm: Double,
    val majorMm: Double,
)

/** Encodes the UDP touch frame of PROTOCOL.md §3. Pure JVM, so it runs in unit tests. */
object Frame {
    const val MAGIC_0: Byte = 0x4D
    const val MAGIC_1: Byte = 0x50
    const val VERSION: Byte = 1
    const val TYPE_FRAME: Byte = 1
    const val HEADER_SIZE = 21
    const val TOUCH_SIZE = 6
    const val TAG_SIZE = 16
    const val MAX_TOUCHES = 10

    /** A keyed HMAC-SHA256 to reuse for every frame of a session (one thread only). */
    fun hmacFor(sessionKey: ByteArray): Mac =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sessionKey, "HmacSHA256")) }

    fun encode(
        sid: Long,
        seq: Long,
        timeMs: Long,
        widthMm: Double,
        heightMm: Double,
        touches: List<TouchPoint>,
        sessionKey: ByteArray,
    ): ByteArray = encode(sid, seq, timeMs, widthMm, heightMm, touches, hmacFor(sessionKey))

    /** Same as above, with a reused [hmac] so no crypto provider lookup per frame. */
    fun encode(
        sid: Long,
        seq: Long,
        timeMs: Long,
        widthMm: Double,
        heightMm: Double,
        touches: List<TouchPoint>,
        hmac: Mac,
    ): ByteArray {
        val list = touches.take(MAX_TOUCHES)
        val bodySize = HEADER_SIZE + TOUCH_SIZE * list.size
        val out = ByteArray(bodySize + TAG_SIZE)
        ByteBuffer.wrap(out, 0, bodySize).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_0)
            put(MAGIC_1)
            put(VERSION)
            put(TYPE_FRAME)
            putInt(sid.toInt())
            putInt(seq.toInt())
            putInt(timeMs.toInt())
            putShort(u16(widthMm * 100))
            putShort(u16(heightMm * 100))
            put(list.size.toByte())
            for (t in list) {
                put(t.id.coerceIn(0, 255).toByte())
                putShort(u16(t.xMm * 100))
                putShort(u16(t.yMm * 100))
                put(u8(t.majorMm * 10))
            }
        }
        hmac.update(out, 0, bodySize)
        System.arraycopy(hmac.doFinal(), 0, out, bodySize, TAG_SIZE) // doFinal resets for the next frame
        return out
    }

    private fun u16(v: Double): Short = Math.round(v).coerceIn(0L, 0xFFFFL).toInt().toShort()

    private fun u8(v: Double): Byte = Math.round(v).coerceIn(0L, 0xFFL).toInt().toByte()
}
