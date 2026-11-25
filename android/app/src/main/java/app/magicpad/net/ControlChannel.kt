package app.magicpad.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The TCP control channel: u16 big-endian length-prefixed UTF-8 JSON (PROTOCOL.md §2). */
class ControlChannel(
    host: java.net.InetAddress,
    port: Int,
) : AutoCloseable {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: DataOutputStream
    private val writeLock = Any()

    val remoteAddress: java.net.InetAddress get() = socket.inetAddress

    init {
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            input = DataInputStream(socket.getInputStream().buffered())
            output = DataOutputStream(socket.getOutputStream())
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    /** Read timeout for the next [receive], in ms. */
    fun setTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    fun send(message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "control message too large" }
        synchronized(writeLock) {
            output.writeShort(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    fun send(type: String) = send(JSONObject().put("t", type))

    /** Blocks for the next message; throws on timeout or a closed socket. */
    fun receive(): JSONObject {
        val length = input.readUnsignedShort()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4000
    }
}
