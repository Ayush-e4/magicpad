package app.magicpad.pad.gl

import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FUNC_ADD
import android.opengl.GLES30.GL_FUNC_REVERSE_SUBTRACT
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_ONE
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_SRC_ALPHA
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.GL_ZERO
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBlendEquation
import android.opengl.GLES30.glBlendFunc
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glEnable
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glUniform3f
import android.opengl.GLES30.glUniform4f
import android.opengl.GLES30.glViewport
import app.magicpad.pad.Hue
import app.magicpad.pad.PointerBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Soft additive glow along each finger's path, drawn into a half-resolution texture
 * that fades every frame. Plain RGBA8, so it works on any GLES 3 device.
 */
class GlowEffect : Effect {
    private val quad = Gl.Quad()
    private val solid = Gl.Program(EffectShaders.QUAD_VERTEX, EffectShaders.SOLID)
    private val dot = Gl.Program(EffectShaders.DOT_VERTEX, EffectShaders.DOT)
    private val show = Gl.Program(EffectShaders.QUAD_VERTEX, EffectShaders.TEXTURE)
    private val trails = PointerTrails()
    private val rgb = FloatArray(3)

    private var width = 1
    private var height = 1
    private var accum: Gl.Target? = null

    override fun resize(
        width: Int,
        height: Int,
    ) {
        if (accum != null && width == this.width && height == this.height) return
        this.width = width
        this.height = height
        accum = Gl.Target(maxOf(1, width / 2), maxOf(1, height / 2), GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, GL_LINEAR)
    }

    override fun draw(
        pointers: PointerBuffer.Snapshot,
        dt: Float,
        time: Double,
    ) {
        val target = accum ?: return
        target.bind()
        glEnable(GL_BLEND)
        fade(dt)
        glBlendEquation(GL_FUNC_ADD)
        glBlendFunc(GL_ONE, GL_ONE)
        dots(pointers, time)
        glDisable(GL_BLEND)

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        show.use()
        show.texture("uTexture", 0, target.texture)
        glUniform1f(show.u("dim"), DISPLAY_DIM)
        quad.draw()
    }

    /** Multiplies the texture by a time-based factor, then subtracts one 8-bit step so it reaches black. */
    private fun fade(dt: Float) {
        solid.use()
        glBlendEquation(GL_FUNC_ADD)
        glBlendFunc(GL_ZERO, GL_SRC_ALPHA)
        glUniform4f(solid.u("color"), 0f, 0f, 0f, exp(-FADE_PER_SEC * dt))
        quad.draw()
        glBlendEquation(GL_FUNC_REVERSE_SUBTRACT)
        glBlendFunc(GL_ONE, GL_ONE)
        val step = 1f / 255f
        glUniform4f(solid.u("color"), step, step, step, 0f)
        quad.draw()
    }

    private fun dots(
        pointers: PointerBuffer.Snapshot,
        time: Double,
    ) {
        dot.use()
        val radiusPx = RADIUS_FRACTION * min(width, height)
        glUniform2f(dot.u("radius"), radiusPx * 2f / width, radiusPx * 2f / height)
        trails.forEach(pointers) { id, fx, fy, x, y, isNew ->
            Hue.hsvToRgb(Hue.forPointer(id, time), SATURATION, 1f, rgb)
            glUniform3f(dot.u("color"), rgb[0] * INTENSITY, rgb[1] * INTENSITY, rgb[2] * INTENSITY)
            val distance = hypot(x - fx, y - fy)
            val steps = if (isNew) 1 else ceil(distance / (radiusPx * SPACING)).toInt().coerceIn(1, MAX_STEPS)
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val px = fx + (x - fx) * t
                val py = fy + (y - fy) * t
                glUniform2f(dot.u("center"), px / width * 2f - 1f, 1f - py / height * 2f)
                quad.draw()
            }
        }
    }

    private companion object {
        const val FADE_PER_SEC = 2.4f
        const val RADIUS_FRACTION = 0.05f
        const val SPACING = 0.35f
        const val MAX_STEPS = 24
        const val SATURATION = 0.65f
        const val INTENSITY = 0.09f
        const val DISPLAY_DIM = 0.85f
    }
}
