package app.magicpad.pad.gl

import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glViewport
import app.magicpad.pad.PointerBuffer

/** A slow multi-colour gradient driven only by time; it ignores touches. */
class AmbientEffect : Effect {
    private val quad = Gl.Quad()
    private val program = Gl.Program(EffectShaders.QUAD_VERTEX, EffectShaders.AMBIENT)
    private var width = 1
    private var height = 1

    override fun resize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
    }

    override fun draw(
        pointers: PointerBuffer.Snapshot,
        dt: Float,
        time: Double,
    ) {
        glDisable(GL_BLEND)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        program.use()
        // Wrapped so the float keeps enough precision for smooth motion over long sessions.
        glUniform1f(program.u("time"), (time % TIME_WRAP_SEC).toFloat())
        glUniform2f(program.u("resolution"), width.toFloat(), height.toFloat())
        glUniform1f(program.u("dim"), DIM)
        quad.draw()
    }

    private companion object {
        const val DIM = 0.8f

        /** About 2.9 h; the gradient may jump once when it wraps, which is far too rare to notice. */
        const val TIME_WRAP_SEC = 10_471.976
    }
}
