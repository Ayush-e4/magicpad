package app.magicpad.pad.gl

import android.opengl.GLES30.GL_ACTIVE_UNIFORMS
import android.opengl.GLES30.GL_ARRAY_BUFFER
import android.opengl.GLES30.GL_CLAMP_TO_EDGE
import android.opengl.GLES30.GL_COLOR_ATTACHMENT0
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_COMPILE_STATUS
import android.opengl.GLES30.GL_EXTENSIONS
import android.opengl.GLES30.GL_FLOAT
import android.opengl.GLES30.GL_FRAGMENT_SHADER
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FRAMEBUFFER_COMPLETE
import android.opengl.GLES30.GL_LINK_STATUS
import android.opengl.GLES30.GL_NO_ERROR
import android.opengl.GLES30.GL_STATIC_DRAW
import android.opengl.GLES30.GL_TEXTURE0
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES30.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES30.GL_TEXTURE_WRAP_S
import android.opengl.GLES30.GL_TEXTURE_WRAP_T
import android.opengl.GLES30.GL_TRIANGLE_STRIP
import android.opengl.GLES30.GL_VERTEX_SHADER
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glAttachShader
import android.opengl.GLES30.glBindBuffer
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glBufferData
import android.opengl.GLES30.glCheckFramebufferStatus
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glCompileShader
import android.opengl.GLES30.glCreateProgram
import android.opengl.GLES30.glCreateShader
import android.opengl.GLES30.glDeleteShader
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glEnableVertexAttribArray
import android.opengl.GLES30.glFramebufferTexture2D
import android.opengl.GLES30.glGenBuffers
import android.opengl.GLES30.glGenFramebuffers
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGetActiveUniform
import android.opengl.GLES30.glGetError
import android.opengl.GLES30.glGetProgramInfoLog
import android.opengl.GLES30.glGetProgramiv
import android.opengl.GLES30.glGetShaderInfoLog
import android.opengl.GLES30.glGetShaderiv
import android.opengl.GLES30.glGetString
import android.opengl.GLES30.glGetUniformLocation
import android.opengl.GLES30.glLinkProgram
import android.opengl.GLES30.glShaderSource
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glTexParameteri
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUseProgram
import android.opengl.GLES30.glVertexAttribPointer
import android.opengl.GLES30.glViewport
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small GLES 3 helpers. Everything here runs on the GL thread. */
object Gl {
    const val TAG = "MagicPad"

    /** A linked program with its uniform locations looked up once. */
    class Program(
        vertex: String,
        fragment: String,
    ) {
        val id: Int = link(compile(GL_VERTEX_SHADER, vertex), compile(GL_FRAGMENT_SHADER, fragment))
        private val uniforms = HashMap<String, Int>()

        init {
            val n = IntArray(1)
            glGetProgramiv(id, GL_ACTIVE_UNIFORMS, n, 0)
            val size = IntArray(1)
            val type = IntArray(1)
            for (i in 0 until n[0]) {
                val name = glGetActiveUniform(id, i, size, 0, type, 0).substringBefore('[')
                uniforms[name] = glGetUniformLocation(id, name)
            }
        }

        fun use() = glUseProgram(id)

        /** Location of [name], or -1 (ignored by glUniform*) if the compiler removed it. */
        fun u(name: String): Int = uniforms[name] ?: -1

        /** Binds [texture] to [unit] and points sampler [name] at it. */
        fun texture(
            name: String,
            unit: Int,
            texture: Int,
        ) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, texture)
            glUniform1i(u(name), unit)
        }
    }

    /** A texture plus the framebuffer that renders into it. */
    class Target(
        val width: Int,
        val height: Int,
        internalFormat: Int,
        format: Int,
        type: Int,
        filter: Int,
    ) {
        val texture: Int = gen { glGenTextures(1, it, 0) }
        val fbo: Int = gen { glGenFramebuffers(1, it, 0) }
        val texelX = 1f / width
        val texelY = 1f / height

        init {
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
            val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
            check(status == GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: 0x${status.toString(16)}" }
            glViewport(0, 0, width, height)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
        }

        fun bind() {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glViewport(0, 0, width, height)
        }
    }

    /** Ping-pong pair: read from [read], render into [write], then [swap]. */
    class DoubleTarget(
        factory: () -> Target,
    ) {
        var read = factory()
            private set
        var write = factory()
            private set
        val width get() = read.width
        val height get() = read.height

        fun swap() {
            val t = read
            read = write
            write = t
        }
    }

    /** A fullscreen quad (-1..1) as a 4-vertex triangle strip in attribute 0. */
    class Quad {
        private val vbo: Int =
            gen { glGenBuffers(1, it, 0) }.also { id ->
                val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val buf = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v)
                buf.position(0)
                glBindBuffer(GL_ARRAY_BUFFER, id)
                glBufferData(GL_ARRAY_BUFFER, v.size * 4, buf, GL_STATIC_DRAW)
            }

        fun draw() {
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /** Whether half-float textures can be rendered into (needed by the fluid simulation). */
    fun halfFloatRenderable(): Boolean {
        val ext = glGetString(GL_EXTENSIONS) ?: return false
        return "EXT_color_buffer_half_float" in ext || "EXT_color_buffer_float" in ext
    }

    /** Logs any pending GL error; returns true if there was one. */
    fun logErrors(where: String): Boolean {
        var any = false
        while (true) {
            val e = glGetError()
            if (e == GL_NO_ERROR) return any
            Log.e(TAG, "GL error 0x${e.toString(16)} at $where")
            any = true
        }
    }

    private inline fun gen(block: (IntArray) -> Unit): Int = IntArray(1).also(block)[0]

    private fun compile(
        type: Int,
        source: String,
    ): Int {
        val s = glCreateShader(type)
        glShaderSource(s, source)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            glDeleteShader(s)
            error("shader compile failed: $log")
        }
        return s
    }

    private fun link(
        vs: Int,
        fs: Int,
    ): Int {
        val p = glCreateProgram()
        glAttachShader(p, vs)
        glAttachShader(p, fs)
        glLinkProgram(p)
        glDeleteShader(vs)
        glDeleteShader(fs)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: ${glGetProgramInfoLog(p)}" }
        return p
    }
}
