package app.magicpad.pad

import kotlin.math.abs
import kotlin.math.floor

/** Finger colours: each pointer gets its own hue, drifting slowly over time. Pure Kotlin. */
object Hue {
    /** Full hue turns per second (one lap every ~40 s). */
    const val CYCLE_PER_SEC = 0.025

    /** Hue offset between pointer ids, so two fingers never look alike. */
    const val POINTER_STEP = 0.21

    /** Teal-ish start, which reads calmer than red. */
    const val BASE = 0.52

    /** Hue in [0, 1) for pointer [id] at [timeSec]. */
    fun forPointer(
        id: Int,
        timeSec: Double,
    ): Float {
        val h = BASE + id * POINTER_STEP + timeSec * CYCLE_PER_SEC
        return (h - floor(h)).toFloat()
    }

    /** HSV (each 0–1) to RGB, written into [out][0..2] without allocating. */
    fun hsvToRgb(
        h: Float,
        s: Float,
        v: Float,
        out: FloatArray,
    ) {
        val hh = (h - floor(h)) * 6f
        val c = v * s
        val x = c * (1f - abs(hh % 2f - 1f))
        val m = v - c
        var r = 0f
        var g = 0f
        var b = 0f
        when (hh.toInt()) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        out[0] = r + m
        out[1] = g + m
        out[2] = b + m
    }
}
