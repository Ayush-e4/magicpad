package app.magicpad.pad

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import app.magicpad.settings.PadBackground

/**
 * The GLSurfaceView for an animated background, its renderer, and (for Ambient) the
 * ~30 fps ticker. [onResume]/[onPause] follow the activity.
 */
class PadGl(
    context: Context,
    kind: PadBackground,
    pointers: PointerBuffer,
) {
    val renderer = PadRenderer(kind, pointers)
    val view: GLSurfaceView =
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    private val ticker = if (kind == PadBackground.AMBIENT) AmbientTicker(view) else null

    init {
        renderer.view = view
    }

    fun onResume() {
        view.onResume()
        ticker?.start()
    }

    fun onPause() {
        ticker?.stop()
        view.onPause()
    }

    companion object {
        /** Whether this device can run the animated backgrounds at all. */
        fun supported(context: Context): Boolean {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            return am.deviceConfigurationInfo.reqGlEsVersion >= GLES_3
        }

        private const val GLES_3 = 0x30000
    }
}

/** Calls requestRender (thread-safe) about 30 times a second on its own thread. */
class AmbientTicker(
    private val view: GLSurfaceView,
) {
    @Volatile private var thread: Thread? = null

    // GLSurfaceView.requestRender is documented as callable from any thread.
    @SuppressLint("ThreadConstraint")
    fun start() {
        if (thread != null) return
        thread =
            Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        view.requestRender()
                        Thread.sleep(FRAME_MS)
                    }
                } catch (_: InterruptedException) {
                    // Stopped.
                }
            }, "magicpad-ambient").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
