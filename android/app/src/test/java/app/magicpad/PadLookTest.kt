package app.magicpad

import app.magicpad.pad.Hue
import app.magicpad.pad.PointerBuffer
import app.magicpad.settings.PadBackground
import app.magicpad.settings.PadSettings
import app.magicpad.settings.PhotoSizing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PadLookTest {
    @Test
    fun settingsDefaults() {
        val s = PadSettings()
        assertEquals(PadBackground.BLACK, s.background)
        assertEquals(0.40f, s.photoDim)
        assertNull(s.brightness)
        assertEquals(0.05f, s.effectiveBrightness)
    }

    @Test
    fun brightnessFollowsBackgroundUntilTheSliderMoves() {
        val fluid = PadSettings().withBackground(PadBackground.FLUID)
        assertEquals(0.40f, fluid.effectiveBrightness)
        assertEquals(0.05f, fluid.withBackground(PadBackground.BLACK).effectiveBrightness)

        val moved = fluid.withBrightness(0.7f)
        assertEquals(0.7f, moved.effectiveBrightness)
        assertEquals(0.7f, moved.withBackground(PadBackground.BLACK).effectiveBrightness)
        assertEquals(0.7f, moved.withBackground(PadBackground.PHOTO).effectiveBrightness)

        // Moved on Black first: picking a background later keeps the user's choice.
        val darkFirst = PadSettings().withBrightness(0.12f).withBackground(PadBackground.AMBIENT)
        assertEquals(0.12f, darkFirst.effectiveBrightness)
    }

    @Test
    fun settingsClamp() {
        assertEquals(0.01f, PadSettings().withBrightness(0f).effectiveBrightness)
        assertEquals(1f, PadSettings().withBrightness(3f).effectiveBrightness)
        assertEquals(0.8f, PadSettings().withPhotoDim(0.95f).photoDim)
        assertEquals(0f, PadSettings().withPhotoDim(-1f).photoDim)
    }

    @Test
    fun backgroundKeysRoundTrip() {
        for (bg in PadBackground.entries) assertEquals(bg, PadBackground.fromKey(bg.key))
        assertEquals(PadBackground.BLACK, PadBackground.fromKey(null))
        assertEquals(PadBackground.BLACK, PadBackground.fromKey("lava"))
        assertTrue(PadBackground.FLUID.usesGl && PadBackground.GLOW.usesGl && PadBackground.AMBIENT.usesGl)
        assertFalse(PadBackground.BLACK.usesGl || PadBackground.PHOTO.usesGl)
    }

    @Test
    fun pointerBufferCopiesAndIsolates() {
        val buffer = PointerBuffer()
        val ids = intArrayOf(3, 7)
        val xs = floatArrayOf(10f, 20f)
        val ys = floatArrayOf(30f, 40f)
        buffer.publish(2, ids, xs, ys, timeMs = 1234)
        xs[0] = 999f // The writer reusing its scratch arrays must not change what was published.

        val snap = PointerBuffer.Snapshot()
        buffer.snapshot(snap)
        assertEquals(2, snap.count)
        assertEquals(1234L, snap.lastEventMs)
        assertArrayEquals(intArrayOf(3, 7), snap.ids.copyOf(2))
        assertArrayEquals(floatArrayOf(10f, 20f), snap.xs.copyOf(2), 0f)
        assertArrayEquals(floatArrayOf(30f, 40f), snap.ys.copyOf(2), 0f)
        assertEquals(buffer.version(), snap.version)

        buffer.publish(0, ids, xs, ys, timeMs = 2000)
        assertTrue(buffer.version() != snap.version)
        buffer.snapshot(snap)
        assertEquals(0, snap.count)
    }

    @Test
    fun pointerBufferCapsAtCapacity() {
        val n = 14
        val buffer = PointerBuffer()
        buffer.publish(n, IntArray(n) { it }, FloatArray(n) { it.toFloat() }, FloatArray(n), 1)
        val snap = PointerBuffer.Snapshot()
        buffer.snapshot(snap)
        assertEquals(PointerBuffer.MAX_POINTERS, snap.count)
        assertEquals(9, snap.ids[9])
    }

    @Test
    fun huesWrapDriftSlowlyAndDifferPerPointer() {
        for (id in 0..20) {
            for (t in listOf(0.0, 1.5, 100.0, 12345.6)) {
                val h = Hue.forPointer(id, t)
                assertTrue("hue $h", h >= 0f && h < 1f)
            }
        }
        val drift = abs(Hue.forPointer(0, 1.0) - Hue.forPointer(0, 0.0))
        assertTrue("drift $drift per second", drift < 0.05f)
        assertTrue(abs(Hue.forPointer(1, 5.0) - Hue.forPointer(0, 5.0)) > 0.1f)
    }

    @Test
    fun hsvToRgbPrimaries() {
        val out = FloatArray(3)
        Hue.hsvToRgb(0f, 1f, 1f, out)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), out, 1e-5f)
        Hue.hsvToRgb(1f / 3f, 1f, 1f, out)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), out, 1e-5f)
        Hue.hsvToRgb(2f / 3f, 1f, 1f, out)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), out, 1e-5f)
        Hue.hsvToRgb(0.5f, 0f, 0.6f, out)
        assertArrayEquals(floatArrayOf(0.6f, 0.6f, 0.6f), out, 1e-5f)
    }

    @Test
    fun photoCoverScale() {
        // 4000×3000 landscape photo on a 2400×1080 pad: shrink until it just covers.
        assertEquals(0.6f, PhotoSizing.coverScale(4000, 3000, 2400, 1080), 1e-5f)
        // Portrait photo: its width must still span the pad's long side.
        assertEquals(0.8f, PhotoSizing.coverScale(3000, 4000, 2400, 1080), 1e-5f)
        // Never upscales.
        assertEquals(1f, PhotoSizing.coverScale(800, 600, 2400, 1080), 0f)
        assertEquals(1f, PhotoSizing.coverScale(0, 0, 2400, 1080), 0f)
    }
}
