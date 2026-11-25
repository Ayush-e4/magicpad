package app.magicpad

import app.magicpad.pad.Shortcut
import app.magicpad.settings.PadSettings
import app.magicpad.settings.StripPlace
import app.magicpad.settings.StripSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShortcutTest {
    @Test
    fun stripIsOffOnTheLeftMiddleByDefault() {
        val s = PadSettings()
        assertFalse(s.shortcuts)
        assertEquals(StripSide.LEFT, s.stripSide)
        assertEquals(StripPlace.MIDDLE, s.stripPlace)
    }

    @Test
    fun unknownSavedPositionsFallBackToDefaults() {
        assertEquals(StripSide.LEFT, StripSide.fromKey("diagonal"))
        assertEquals(StripPlace.MIDDLE, StripPlace.fromKey(null))
        assertEquals(StripSide.RIGHT, StripSide.fromKey("right"))
        assertEquals(StripPlace.BOTTOM, StripPlace.fromKey("bottom"))
    }

    /** These strings are the protocol: the Mac matches them exactly (PROTOCOL.md §2). */
    @Test
    fun actionNamesMatchTheProtocol() {
        assertEquals(
            listOf("media.previous", "media.playpause", "media.next", "screenshot.screen"),
            Shortcut.entries.map { it.wire },
        )
    }
}
