package app.magicpad

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticKindTest {
    @Test
    fun parsesEveryWireName() {
        for (kind in HapticKind.entries) assertEquals(kind, HapticKind.parse(kind.wire))
        assertEquals(HapticKind.HEAVY, HapticKind.parse("heavy"))
        assertEquals(HapticKind.CONFIRM, HapticKind.parse("confirm"))
    }

    @Test
    fun missingOrUnknownKindIsClick() {
        assertEquals(HapticKind.CLICK, HapticKind.parse(null))
        assertEquals(HapticKind.CLICK, HapticKind.parse(""))
        assertEquals(HapticKind.CLICK, HapticKind.parse("rumble"))
        assertEquals(HapticKind.CLICK, HapticKind.parse("HEAVY")) // case-sensitive, like the rest of the protocol
    }

    @Test
    fun strengthLevels() {
        assertEquals(7, HapticKind.level(null)) // missing → 0.7
        assertEquals(7, HapticKind.level(Double.NaN)) // not a number → 0.7
        assertEquals(0, HapticKind.level(0.0))
        assertEquals(10, HapticKind.level(1.0))
        assertEquals(5, HapticKind.level(0.5))
        assertEquals(3, HapticKind.level(0.26))
        assertEquals(0, HapticKind.level(-2.0))
        assertEquals(10, HapticKind.level(7.0))
    }
}
