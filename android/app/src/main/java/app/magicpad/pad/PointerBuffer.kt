package app.magicpad.pad

/**
 * The latest finger positions, handed from the UI thread (writer) to the GL thread (reader).
 * Both sides copy into preallocated arrays under a lock held only for that copy (a few
 * dozen values), so a touch event never waits on drawing and nothing allocates per event.
 * Pure Kotlin, so it runs in unit tests.
 */
class PointerBuffer(
    val capacity: Int = MAX_POINTERS,
) {
    private val ids = IntArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var count = 0
    private var lastEventMs = 0L
    private var version = 0L

    /** Called on the UI thread with the fingers down right now (pixels in the pad view). */
    fun publish(
        n: Int,
        srcIds: IntArray,
        srcX: FloatArray,
        srcY: FloatArray,
        timeMs: Long,
    ) {
        val c = n.coerceIn(0, capacity)
        synchronized(this) {
            System.arraycopy(srcIds, 0, ids, 0, c)
            System.arraycopy(srcX, 0, xs, 0, c)
            System.arraycopy(srcY, 0, ys, 0, c)
            count = c
            lastEventMs = timeMs
            version++
        }
    }

    /** Copies the latest state into [into] (GL thread). */
    fun snapshot(into: Snapshot) {
        synchronized(this) {
            val c = minOf(count, into.capacity)
            System.arraycopy(ids, 0, into.ids, 0, c)
            System.arraycopy(xs, 0, into.xs, 0, c)
            System.arraycopy(ys, 0, into.ys, 0, c)
            into.count = c
            into.lastEventMs = lastEventMs
            into.version = version
        }
    }

    /** The current version, to tell whether anything was published since a snapshot. */
    fun version(): Long = synchronized(this) { version }

    /** Reader-side copy, allocated once per renderer. */
    class Snapshot(
        val capacity: Int = MAX_POINTERS,
    ) {
        val ids = IntArray(capacity)
        val xs = FloatArray(capacity)
        val ys = FloatArray(capacity)
        var count = 0
        var lastEventMs = 0L
        var version = 0L
    }

    companion object {
        const val MAX_POINTERS = 10
    }
}
