package app.magicpad.pad.gl

import app.magicpad.pad.PointerBuffer

/** One animated background. Created, resized and drawn on the GL thread only. */
interface Effect {
    fun resize(
        width: Int,
        height: Int,
    )

    /** Draws one frame to the default framebuffer. [dt] is capped; [time] is seconds since start. */
    fun draw(
        pointers: PointerBuffer.Snapshot,
        dt: Float,
        time: Double,
    )
}

/**
 * Tracks each pointer's position in the previous frame (by pointer id), so effects can
 * react to how far a finger moved. Positions are in pixels of the pad view.
 */
class PointerTrails {
    private val known = BooleanArray(ID_SLOTS)
    private val px = FloatArray(ID_SLOTS)
    private val py = FloatArray(ID_SLOTS)
    private val seen = BooleanArray(ID_SLOTS)

    /** Calls [each] for every finger with its previous and current position. */
    inline fun forEach(
        p: PointerBuffer.Snapshot,
        each: (id: Int, fromX: Float, fromY: Float, x: Float, y: Float, isNew: Boolean) -> Unit,
    ) {
        begin()
        for (i in 0 until p.count) {
            val id = p.ids[i] and (ID_SLOTS - 1)
            val isNew = !wasKnown(id)
            val fx = if (isNew) p.xs[i] else prevX(id)
            val fy = if (isNew) p.ys[i] else prevY(id)
            each(id, fx, fy, p.xs[i], p.ys[i], isNew)
            remember(id, p.xs[i], p.ys[i])
        }
        end()
    }

    fun begin() = seen.fill(false)

    fun wasKnown(id: Int) = known[id]

    fun prevX(id: Int) = px[id]

    fun prevY(id: Int) = py[id]

    fun remember(
        id: Int,
        x: Float,
        y: Float,
    ) {
        known[id] = true
        seen[id] = true
        px[id] = x
        py[id] = y
    }

    /** Forgets fingers that were not in this frame. */
    fun end() {
        for (i in 0 until ID_SLOTS) if (!seen[i]) known[i] = false
    }

    companion object {
        const val ID_SLOTS = 32
    }
}
