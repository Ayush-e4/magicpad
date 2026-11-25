package app.magicpad.net

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.os.HandlerCompat
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends touch frames to the Mac over UDP (PROTOCOL.md §3) on its own thread:
 * one frame per touch event, a repeat every 50 ms while fingers are down, and
 * the empty frame three times, 10 ms apart, when the last finger lifts.
 *
 * Packets are marked DSCP EF (expedited forwarding), which Wi-Fi maps to the voice
 * queue (WMM AC_VO): the phone's radio sends them ahead of other traffic.
 */
class FrameSender(
    address: InetAddress,
    port: Int,
    private val sid: Long,
    private val sessionKey: ByteArray,
) : AutoCloseable {
    private val socket =
        DatagramSocket().apply {
            try {
                trafficClass = DSCP_EF
            } catch (_: IOException) {
                // Some networks or ROMs refuse it; frames still go out at normal priority.
            }
            connect(address, port)
        }
    private val thread = HandlerThread("magicpad-udp", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val hmac = Frame.hmacFor(sessionKey) // only used on the sender thread

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
            // Cancel only the timed repeats, never queued updates: dropping a queued
            // update could drop a lift and leave a finger stuck down on the Mac.
            handler.removeCallbacks(repeat)
            handler.removeCallbacksAndMessages(LIFT)
            current = touches
            this.widthMm = widthMm
            this.heightMm = heightMm
            send(timeMs)
            if (touches.isNotEmpty()) {
                handler.postDelayed(repeat, REPEAT_MS)
            } else {
                for (i in 1 until LIFT_REPEATS) {
                    HandlerCompat.postDelayed(handler, { send(SystemClock.uptimeMillis()) }, LIFT, LIFT_SPACING_MS * i)
                }
            }
        }
    }

    private fun send(timeMs: Long) {
        if (closed) return
        seq += 1
        val bytes = Frame.encode(sid, seq, timeMs, widthMm, heightMm, current, hmac)
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
        /** DSCP 46 (EF) in the top six bits of the IP TOS byte. */
        const val DSCP_EF = 0xB8
        val LIFT = Any()
        const val REPEAT_MS = 50L
        const val LIFT_SPACING_MS = 10L
        const val LIFT_REPEATS = 3
    }
}
