package app.magicpad.pad.gl

import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_HALF_FLOAT
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_NEAREST
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA16F
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glUniform3f
import android.opengl.GLES30.glViewport
import app.magicpad.pad.Hue
import app.magicpad.pad.PointerBuffer
import kotlin.math.roundToInt

/**
 * Fingers stir coloured dye through a GPU stable-fluids simulation: splat, curl, vorticity
 * confinement, divergence, Jacobi pressure, gradient subtract, then advect velocity and dye.
 * Needs half-float render targets (checked by the caller).
 */
class FluidEffect : Effect {
    private val quad = Gl.Quad()
    private val splat = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.SPLAT)
    private val advection = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.ADVECTION)
    private val curlProgram = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.CURL)
    private val vorticity = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.VORTICITY)
    private val divergenceProgram = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.DIVERGENCE)
    private val scale = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.SCALE)
    private val pressureProgram = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.PRESSURE)
    private val gradient = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.GRADIENT_SUBTRACT)
    private val display = Gl.Program(FluidShaders.BASE_VERTEX, FluidShaders.DISPLAY)

    private var ready = false
    private var width = 1
    private var height = 1
    private lateinit var velocity: Gl.DoubleTarget
    private lateinit var dye: Gl.DoubleTarget
    private lateinit var pressure: Gl.DoubleTarget
    private lateinit var divergence: Gl.Target
    private lateinit var curl: Gl.Target

    private val trails = PointerTrails()
    private val rgb = FloatArray(3)

    override fun resize(
        width: Int,
        height: Int,
    ) {
        if (ready && width == this.width && height == this.height) return
        this.width = width
        this.height = height
        ready = true
        val (sw, sh) = gridSize(SIM_RESOLUTION)
        val (dw, dh) = gridSize(DYE_RESOLUTION)
        velocity = Gl.DoubleTarget { target(sw, sh) }
        pressure = Gl.DoubleTarget { target(sw, sh) }
        divergence = target(sw, sh, GL_NEAREST)
        curl = target(sw, sh, GL_NEAREST)
        dye = Gl.DoubleTarget { target(dw, dh) }
    }

    override fun draw(
        pointers: PointerBuffer.Snapshot,
        dt: Float,
        time: Double,
    ) {
        if (!ready) return
        glDisable(GL_BLEND)
        addSplats(pointers, time)
        step(dt)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        display.use()
        glUniform2f(display.u("texelSize"), 1f / width, 1f / height)
        display.texture("uTexture", 0, dye.read.texture)
        glUniform1f(display.u("exposure"), EXPOSURE)
        quad.draw()
    }

    private fun addSplats(
        pointers: PointerBuffer.Snapshot,
        time: Double,
    ) {
        val aspect = width.toFloat() / height
        trails.forEach(pointers) { id, fx, fy, x, y, isNew ->
            if (isNew) return@forEach
            var dx = (x - fx) / width
            val dy = (fy - y) / height // GL's y points up.
            if (aspect < 1f) dx *= aspect
            if (dx == 0f && dy == 0f) return@forEach
            Hue.hsvToRgb(Hue.forPointer(id, time), SATURATION, 1f, rgb)
            splatAt(x / width, 1f - y / height, dx * SPLAT_FORCE, dy * SPLAT_FORCE, aspect)
        }
    }

    private fun splatAt(
        u: Float,
        v: Float,
        forceX: Float,
        forceY: Float,
        aspect: Float,
    ) {
        val radius = SPLAT_RADIUS * if (aspect > 1f) aspect else 1f
        splat.use()
        glUniform1f(splat.u("aspectRatio"), aspect)
        glUniform2f(splat.u("point"), u, v)
        glUniform1f(splat.u("radius"), radius)

        glUniform2f(splat.u("texelSize"), velocity.read.texelX, velocity.read.texelY)
        splat.texture("uTarget", 0, velocity.read.texture)
        glUniform3f(splat.u("color"), forceX, forceY, 0f)
        render(velocity.write)
        velocity.swap()

        glUniform2f(splat.u("texelSize"), dye.read.texelX, dye.read.texelY)
        splat.texture("uTarget", 0, dye.read.texture)
        glUniform3f(splat.u("color"), rgb[0] * DYE_INTENSITY, rgb[1] * DYE_INTENSITY, rgb[2] * DYE_INTENSITY)
        render(dye.write)
        dye.swap()
    }

    private fun step(dt: Float) {
        val tx = velocity.read.texelX
        val ty = velocity.read.texelY

        curlProgram.use()
        glUniform2f(curlProgram.u("texelSize"), tx, ty)
        curlProgram.texture("uVelocity", 0, velocity.read.texture)
        render(curl)

        vorticity.use()
        glUniform2f(vorticity.u("texelSize"), tx, ty)
        vorticity.texture("uVelocity", 0, velocity.read.texture)
        vorticity.texture("uCurl", 1, curl.texture)
        glUniform1f(vorticity.u("curl"), CURL)
        glUniform1f(vorticity.u("dt"), dt)
        render(velocity.write)
        velocity.swap()

        divergenceProgram.use()
        glUniform2f(divergenceProgram.u("texelSize"), tx, ty)
        divergenceProgram.texture("uVelocity", 0, velocity.read.texture)
        render(divergence)

        scale.use()
        glUniform2f(scale.u("texelSize"), tx, ty)
        scale.texture("uTexture", 0, pressure.read.texture)
        glUniform1f(scale.u("value"), PRESSURE_CARRY)
        render(pressure.write)
        pressure.swap()

        pressureProgram.use()
        glUniform2f(pressureProgram.u("texelSize"), tx, ty)
        pressureProgram.texture("uDivergence", 1, divergence.texture)
        repeat(PRESSURE_ITERATIONS) {
            pressureProgram.texture("uPressure", 0, pressure.read.texture)
            render(pressure.write)
            pressure.swap()
        }

        gradient.use()
        glUniform2f(gradient.u("texelSize"), tx, ty)
        gradient.texture("uPressure", 0, pressure.read.texture)
        gradient.texture("uVelocity", 1, velocity.read.texture)
        render(velocity.write)
        velocity.swap()

        advection.use()
        glUniform2f(advection.u("texelSize"), tx, ty)
        glUniform2f(advection.u("simTexel"), tx, ty)
        glUniform1f(advection.u("dt"), dt)
        advection.texture("uVelocity", 0, velocity.read.texture)
        advection.texture("uSource", 1, velocity.read.texture)
        glUniform1f(advection.u("dissipation"), VELOCITY_DISSIPATION)
        render(velocity.write)
        velocity.swap()

        glUniform2f(advection.u("texelSize"), dye.read.texelX, dye.read.texelY)
        advection.texture("uVelocity", 0, velocity.read.texture)
        advection.texture("uSource", 1, dye.read.texture)
        glUniform1f(advection.u("dissipation"), DYE_DISSIPATION)
        render(dye.write)
        dye.swap()
    }

    private fun render(target: Gl.Target) {
        target.bind()
        quad.draw()
    }

    /** Grid size with [resolution] cells on the short side, matching the view's aspect. */
    private fun gridSize(resolution: Int): Pair<Int, Int> {
        val aspect = width.toFloat() / height
        return if (aspect >= 1f) {
            (resolution * aspect).roundToInt() to resolution
        } else {
            resolution to (resolution / aspect).roundToInt()
        }
    }

    private fun target(
        w: Int,
        h: Int,
        filter: Int = GL_LINEAR,
    ) = Gl.Target(w, h, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, filter)

    private companion object {
        const val SIM_RESOLUTION = 128
        const val DYE_RESOLUTION = 512
        const val PRESSURE_ITERATIONS = 20
        const val PRESSURE_CARRY = 0.8f
        const val CURL = 28f
        const val VELOCITY_DISSIPATION = 0.25f
        const val DYE_DISSIPATION = 0.9f
        const val SPLAT_RADIUS = 0.004f
        const val SPLAT_FORCE = 6000f
        const val SATURATION = 1f
        const val DYE_INTENSITY = 0.35f
        const val EXPOSURE = 1.8f
    }
}
