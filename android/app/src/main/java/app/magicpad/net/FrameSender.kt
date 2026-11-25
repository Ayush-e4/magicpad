package app.magicpad.net

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends touch frames to the Mac over UDP (PROTOCOL.md §3) on its own thread:
 * one frame per touch event, a repeat every 50 ms while fingers are down, and
 * the empty frame three times, 10 ms apart, when the last finger lifts.
 */
class FrameSender(
    address: InetAddress,
    port: Int,
    private val sid: Long,
    private val sessionKey: ByteArray,
) : AutoCloseable {
    private val socket = DatagramSocket().apply { connect(address, port) }
    private val thread = HandlerThread("magicpad-udp", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on the sender thread.
    private var seq = 0L
    private var current: List<TouchPoint> = emptyList()
    private var widthMm = 0.0
    private var heightMm = 0.0

    @Volatile private var closed = false

    private val repeat =
        object : Runnable {
            override fun run() {
                if (current.isEmpty()) return
                send(SystemClock.uptimeMillis())
                handler.postDelayed(this, REPEAT_MS)
            }
        }

    /** Call from any thread with every finger that is down right now. */
    fun update(
        touches: List<TouchPoint>,
        widthMm: Double,
        heightMm: Double,
        timeMs: Long,
    ) {
        if (closed) return
        handler.post {
            handler.removeCallbacksAndMessages(null)
            current = touches
            this.widthMm = widthMm
            this.heightMm = heightMm
            send(timeMs)
            if (touches.isNotEmpty()) {
                handler.postDelayed(repeat, REPEAT_MS)
            } else {
                for (i in 1 until LIFT_REPEATS) {
                    handler.postDelayed({ send(SystemClock.uptimeMillis()) }, LIFT_SPACING_MS * i)
                }
            }
        }
    }

    private fun send(timeMs: Long) {
        if (closed) return
        seq += 1
        val bytes = Frame.encode(sid, seq, timeMs, widthMm, heightMm, current, sessionKey)
        try {
            socket.send(DatagramPacket(bytes, bytes.size))
        } catch (_: IOException) {
            // Dropped; the next frame carries the full state anyway.
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        socket.close()
    }

    private companion object {
        const val REPEAT_MS = 50L
        const val LIFT_SPACING_MS = 10L
        const val LIFT_REPEATS = 3
    }
}
