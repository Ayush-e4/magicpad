package app.magicpad.pad

import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glViewport
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import app.magicpad.pad.gl.AmbientEffect
import app.magicpad.pad.gl.Effect
import app.magicpad.pad.gl.FluidEffect
import app.magicpad.pad.gl.Gl
import app.magicpad.pad.gl.GlowEffect
import app.magicpad.settings.PadBackground
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws one animated background on the GL thread.
 *
 * Fluid and Glow render continuously only while fingers are down and for [IDLE_AFTER_MS]
 * after the last touch, then switch to RENDERMODE_WHEN_DIRTY. The UI thread wakes them
 * with [kick] (a volatile read, plus a short lock only when actually waking). Ambient is
 * always WHEN_DIRTY and is paced by [AmbientTicker].
 */
class PadRenderer(
    private val kind: PadBackground,
    private val pointers: PointerBuffer,
) : GLSurfaceView.Renderer {
    lateinit var view: GLSurfaceView

    private val snapshot = PointerBuffer.Snapshot()
    private val modeLock = Any()
    private var effect: Effect? = null
    private var width = 1
    private var height = 1
    private var startNs = 0L
    private var lastNs = 0L

    @Volatile private var active = false

    @Volatile private var resetClock = false

    /** UI thread, after each touch event: make sure frames are being drawn. */
    fun kick() {
        if (active || kind == PadBackground.AMBIENT) return
        synchronized(modeLock) {
            if (!active) {
                active = true
                resetClock = true // Don't integrate the idle gap as one huge time step.
                view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        // A new context: everything from an old one is gone, so build it all again.
        effect =
            try {
                createEffect()
            } catch (e: IllegalStateException) {
                Log.e(Gl.TAG, "background $kind unavailable, showing black", e)
                null
            }
        // onSurfaceChanged always follows; resize now only if the size is already known.
        if (width > 1 && height > 1) effect?.resize(width, height)
        if (startNs == 0L) startNs = System.nanoTime()
        lastNs = 0L
        Gl.logErrors("create $kind")
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        try {
            effect?.resize(width, height)
        } catch (e: IllegalStateException) {
            Log.e(Gl.TAG, "background $kind resize failed, showing black", e)
            effect = null
        }
        Gl.logErrors("resize $kind")
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (resetClock) {
            resetClock = false
            lastNs = 0L
        }
        val dt = if (lastNs == 0L) DEFAULT_DT else ((now - lastNs) / 1e9f).coerceIn(0f, MAX_DT)
        lastNs = now
        pointers.snapshot(snapshot)
        val e = effect
        if (e == null) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, width, height)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
        } else {
            e.draw(snapshot, dt, (now - startNs) / 1e9)
        }
        maybeIdle()
    }

    /** Stops continuous rendering once no finger has touched the pad for a while. */
    private fun maybeIdle() {
        if (!active || snapshot.count > 0) return
        if (SystemClock.uptimeMillis() - snapshot.lastEventMs < IDLE_AFTER_MS) return
        synchronized(modeLock) {
            active = false
            // A touch published after our snapshot either sees active == false and wakes us
            // again (it waits for this lock), or it was published before we cleared active,
            // in which case the version has moved on and we stay awake.
            if (pointers.version() != snapshot.version) {
                active = true
            } else {
                view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        }
    }

    private fun createEffect(): Effect =
        when (kind) {
            PadBackground.FLUID ->
                if (Gl.halfFloatRenderable()) {
                    FluidEffect()
                } else {
                    Log.w(Gl.TAG, "no half-float render targets; using Finger glow instead of Fluid")
                    GlowEffect()
                }
            PadBackground.GLOW -> GlowEffect()
            PadBackground.AMBIENT -> AmbientEffect()
            PadBackground.BLACK, PadBackground.PHOTO -> error("$kind is not a GL background")
        }

    private companion object {
        const val IDLE_AFTER_MS = 6000L
        const val DEFAULT_DT = 1f / 60f
        const val MAX_DT = 1f / 30f
    }
}
