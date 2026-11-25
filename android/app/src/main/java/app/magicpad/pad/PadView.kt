package app.magicpad.pad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import app.magicpad.R
import app.magicpad.net.TouchPoint

/**
 * The touch surface, transparent and on top of any background. Every touch event becomes
 * the full list of fingers down, in millimetres, handed to [onTouches] (which sends it to
 * the Mac) first. Only then, for an animated background, the pixel positions are copied
 * into [pointers] (preallocated, no allocation) and [onPointers] wakes the renderer; the
 * drawing itself happens on the GL thread and never delays the send.
 */
@SuppressLint("ViewConstructor") // Only created from code.
class PadView(
    context: Context,
    private val onTouches: (touches: List<TouchPoint>, widthMm: Double, heightMm: Double, timeMs: Long) -> Unit,
    private val pointers: PointerBuffer? = null,
    private val onPointers: Runnable? = null,
) : View(context) {
    private val metrics = resources.displayMetrics
    private val mmPerPxX get() = MM_PER_INCH / metrics.xdpi
    private val mmPerPxY get() = MM_PER_INCH / metrics.ydpi
    private val leftEdge = Rect()
    private val rightEdge = Rect()
    private val edges = listOf(leftEdge, rightEdge)
    private val ids = IntArray(PointerBuffer.MAX_POINTERS)
    private val xs = FloatArray(PointerBuffer.MAX_POINTERS)
    private val ys = FloatArray(PointerBuffer.MAX_POINTERS)

    init {
        isFocusable = true
        contentDescription = context.getString(R.string.app_name)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep Android's edge Back gesture from stealing swipes near the sides.
            // The system honours at most 200 dp of height per edge.
            val w = width
            val h = height
            val edge = (EDGE_DP * metrics.density).toInt()
            val tall = minOf(h, (MAX_EXCLUSION_DP * metrics.density).toInt())
            val top0 = (h - tall) / 2
            leftEdge.set(0, top0, edge, top0 + tall)
            // Leave room in the right edge's 200 dp budget for the Settings gear's own exclusion.
            val rightTall = maxOf(0, tall - (GEAR_RESERVE_DP * metrics.density).toInt())
            val rightTop = (h - rightTall) / 2
            rightEdge.set(w - edge, rightTop, w, rightTop + rightTall)
            systemGestureExclusionRects = edges
        }
    }

    @SuppressLint("ClickableViewAccessibility") // A trackpad: taps become clicks on the Mac.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestUnbufferedDispatch(event)
        val action = event.actionMasked
        val lifting =
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val touches =
            if (action == MotionEvent.ACTION_CANCEL) {
                emptyList()
            } else {
                (0 until event.pointerCount).filter { it != lifting }.map { i ->
                    TouchPoint(
                        id = event.getPointerId(i).coerceIn(0, 255),
                        xMm = event.getX(i) * mmPerPxX,
                        yMm = event.getY(i) * mmPerPxY,
                        majorMm = event.getTouchMajor(i) * mmPerPxX,
                    )
                }
            }
        onTouches(touches, width * mmPerPxX, height * mmPerPxY, event.eventTime)
        if (pointers != null) publishPointers(event, action, lifting)
        return true
    }

    private fun publishPointers(
        event: MotionEvent,
        action: Int,
        lifting: Int,
    ) {
        var n = 0
        if (action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (i == lifting || n == ids.size) continue
                ids[n] = event.getPointerId(i)
                xs[n] = event.getX(i)
                ys[n] = event.getY(i)
                n++
            }
        }
        pointers?.publish(n, ids, xs, ys, event.eventTime)
        onPointers?.run()
    }

    private companion object {
        const val MM_PER_INCH = 25.4
        const val EDGE_DP = 48
        const val MAX_EXCLUSION_DP = 200
        const val GEAR_RESERVE_DP = 56
    }
}
