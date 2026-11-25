package app.magicpad.net

import app.magicpad.HapticKind
import app.magicpad.Store
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Why a session ended. [Failed] sessions are worth retrying; [Denied] ones are not. */
sealed interface SessionEnd {
    data object Closed : SessionEnd

    data object Denied : SessionEnd

    /** The Mac doesn't know this phone (or its key changed) and pairing wasn't allowed. */
    data object NeedsPairing : SessionEnd

    data class Failed(
        val reason: String,
    ) : SessionEnd
}

interface SessionListener {
    fun onPairing(code: String)

    fun onReady(
        macName: String,
        sender: FrameSender,
    )

    fun onBuzz(
        kind: HapticKind,
        strength: Double?,
    )
}

/**
 * One connection to a Mac: handshake, optional pairing, auth, then the live control loop.
 * With [allowPairing] false (auto-connect, reconnects) it ends with [SessionEnd.NeedsPairing]
 * instead of showing a pairing code.
 */
class Session(
    private val host: InetAddress,
    private val port: Int,
    private val store: Store,
    private val deviceName: String,
    private val allowPairing: Boolean,
    private val listener: SessionListener,
) {
    private class NeedsPairing : Exception()

    @Volatile private var channel: ControlChannel? = null

    @Volatile private var closed = false

    @Volatile private var live = false

    /** Sends shortcut actions off the caller's (UI) thread, in order. */
    private val actions =
        Executors.newSingleThreadExecutor { r -> Thread(r, "magicpad-action").apply { isDaemon = true } }

    /** Blocks until the session ends. Call on a background thread. */
    fun run(): SessionEnd {
        val ch =
            try {
                ControlChannel(host, port)
            } catch (_: IOException) {
                return SessionEnd.Failed("Can't reach the Mac")
            }
        channel = ch
        var sender: FrameSender? = null
        var pinger: Thread? = null
        return try {
            if (closed) return SessionEnd.Closed
            val ready = handshake(ch) ?: return SessionEnd.Denied
            sender = FrameSender(ch.remoteAddress, ready.udpPort, ready.sid, ready.sessionKey)
            listener.onReady(ready.macName, sender)
            ch.setTimeout(LIVE_TIMEOUT_MS)
            live = true
            pinger = startPinger(ch)
            liveLoop(ch)
        } catch (_: NeedsPairing) {
            SessionEnd.NeedsPairing
        } catch (_: SocketTimeoutException) {
            if (closed) SessionEnd.Closed else SessionEnd.Failed("The Mac stopped responding")
        } catch (e: IOException) {
            if (closed) SessionEnd.Closed else SessionEnd.Failed("Connection lost (${e.message})")
        } catch (e: JSONException) {
            SessionEnd.Failed("Unexpected message from the Mac (${e.message})")
        } catch (e: GeneralSecurityException) {
            SessionEnd.Failed("Secure handshake failed (${e.message})")
        } catch (e: IllegalArgumentException) {
            SessionEnd.Failed("Unexpected message from the Mac (${e.message})")
        } finally {
            live = false
            actions.shutdownNow()
            pinger?.interrupt()
            sender?.close()
            ch.close()
        }
    }

    /** Asks the Mac to run a shortcut ([wire] is its `a`). Any thread; dropped if not connected. */
    fun sendAction(wire: String) {
        val ch = channel ?: return
        if (!live) return
        try {
            actions.execute {
                try {
                    ch.send(JSONObject().put("t", "action").put("a", wire))
                } catch (_: IOException) {
                    // The reader sees the broken socket and ends the session.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Session just ended.
        }
    }

    /** Ends the session from any background thread, saying goodbye first. */
    fun close() {
        closed = true
        channel?.let { ch ->
            try {
                ch.send("bye")
            } catch (_: IOException) {
                // Already gone.
            }
            ch.close()
        }
    }

    private class Ready(
        val macName: String,
        val udpPort: Int,
        val sid: Long,
        val sessionKey: ByteArray,
    )

    /** PROTOCOL.md §2 steps 1–6. Returns null if the Mac said no. */
    private fun handshake(ch: ControlChannel): Ready? {
        ch.setTimeout(HANDSHAKE_TIMEOUT_MS)
        val keyPair = Crypto.newKeyPair()
        val pub = keyPair.public.encoded
        val nonce = Crypto.randomBytes(16)
        ch.send(
            JSONObject()
                .put("t", "hello")
                .put("v", 1)
                .put("device", store.deviceId)
                .put("name", deviceName)
                .put("pub", b64(pub))
                .put("nonce", b64(nonce)),
        )
        val hello = next(ch)
        require(hello.getString("t") == "hello") { "expected hello" }
        val macId = hello.getString("id")
        val macName = hello.optString("name", "Mac")
        val macPub = unb64(hello.getString("pub"))
        val macNonce = unb64(hello.getString("nonce"))
        require(macNonce.size == 16) { "bad nonce" }
        val shared = Crypto.sharedSecret(keyPair, macPub)
        val salt = nonce + macNonce

        var key = store.pairingKey(macId)
        if (key != null && !hello.optBoolean("known", false)) {
            store.forget(macId) // The Mac forgot us: pair again.
            key = null
        }
        if (key == null) {
            if (!allowPairing) throw NeedsPairing()
            ch.send("pair")
            listener.onPairing(Crypto.pairingCode(shared, salt))
            ch.setTimeout(PAIRING_TIMEOUT_MS)
            when (next(ch).getString("t")) {
                "paired" -> Unit
                "denied" -> return null
                else -> throw IllegalArgumentException("expected paired")
            }
            key = Crypto.pairingKey(shared, salt)
            store.savePairingKey(macId, key)
            ch.setTimeout(HANDSHAKE_TIMEOUT_MS)
        }

        val transcript = pub + macPub + salt
        ch.send(JSONObject().put("t", "auth").put("proof", b64(Crypto.proof(key, "phone", transcript))))
        val reply = next(ch)
        when (reply.getString("t")) {
            "ready" -> Unit
            "denied" -> {
                // Our key no longer matches the Mac's: drop it so the next try pairs again.
                store.forget(macId)
                throw NeedsPairing()
            }
            else -> throw IllegalArgumentException("expected ready")
        }
        val macProof = unb64(reply.getString("proof"))
        if (!Crypto.constantTimeEquals(macProof, Crypto.proof(key, "mac", transcript))) {
            throw GeneralSecurityException("the Mac's proof is wrong")
        }
        return Ready(
            macName = macName,
            udpPort = reply.getInt("udp"),
            sid = reply.getLong("sid") and 0xFFFFFFFFL,
            sessionKey = Crypto.sessionKey(shared, key, salt),
        )
    }

    private fun liveLoop(ch: ControlChannel): SessionEnd {
        while (true) {
            val msg = ch.receive()
            when (msg.optString("t")) {
                "buzz" -> listener.onBuzz(buzzKind(msg), buzzStrength(msg))
                "bye" -> return SessionEnd.Closed
                "denied" -> return SessionEnd.Denied
                else -> Unit // ping or unknown: any message resets the timeout.
            }
        }
    }

    private fun buzzKind(msg: JSONObject): HapticKind = HapticKind.parse(if (msg.has("k")) msg.optString("k") else null)

    /** Null when missing; NaN (not a number) is mapped to the default by [HapticKind.level]. */
    private fun buzzStrength(msg: JSONObject): Double? = if (msg.has("s")) msg.optDouble("s") else null

    private fun startPinger(ch: ControlChannel): Thread =
        Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(PING_INTERVAL_MS)
                    ch.send("ping")
                }
            } catch (_: InterruptedException) {
                // Session over.
            } catch (_: IOException) {
                // The reader sees the broken socket and ends the session.
            }
        }, "magicpad-ping").apply {
            isDaemon = true
            start()
        }

    /** The next message, skipping pings. */
    private fun next(ch: ControlChannel): JSONObject {
        while (true) {
            val msg = ch.receive()
            if (msg.optString("t") != "ping") return msg
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val PAIRING_TIMEOUT_MS = 70_000
        const val LIVE_TIMEOUT_MS = 6_000
        const val PING_INTERVAL_MS = 2_000L
    }
}
