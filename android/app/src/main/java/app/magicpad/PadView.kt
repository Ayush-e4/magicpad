package app.magicpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import app.magicpad.net.TouchPoint

/**
 * The touch surface. Every touch event becomes the full list of fingers down,
 * in millimetres, handed to [onTouches] together with the pad size.
 */
@SuppressLint("ViewConstructor") // Only created from code.
class PadView(
    context: Context,
    private val onTouches: (touches: List<TouchPoint>, widthMm: Double, heightMm: Double, timeMs: Long) -> Unit,
) : View(context) {
    private val metrics = resources.displayMetrics
    private val mmPerPxX get() = MM_PER_INCH / metrics.xdpi
    private val mmPerPxY get() = MM_PER_INCH / metrics.ydpi
    private val leftEdge = Rect()
    private val rightEdge = Rect()
    private val edges = listOf(leftEdge, rightEdge)

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
            rightEdge.set(w - edge, top0, w, top0 + tall)
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
        return true
    }

    private companion object {
        const val MM_PER_INCH = 25.4
        const val EDGE_DP = 48
        const val MAX_EXCLUSION_DP = 200
    }
}
