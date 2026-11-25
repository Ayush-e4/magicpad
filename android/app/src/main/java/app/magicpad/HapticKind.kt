package app.magicpad

import kotlin.math.roundToInt

/** The haptic kinds of PROTOCOL.md §2 "After ready". Pure Kotlin, so it runs in unit tests. */
enum class HapticKind(
    val wire: String,
) {
    CLICK("click"),
    HEAVY("heavy"),
    DRAG("drag"),
    CONFIRM("confirm"),
    TICK("tick"),
    ;

    companion object {
        const val DEFAULT_STRENGTH = 0.7

        /** Strength is quantised to 0..[LEVELS] so every effect can be built ahead of time. */
        const val LEVELS = 10

        /** Missing or unknown kinds play as [CLICK]. */
        fun parse(k: String?): HapticKind = entries.firstOrNull { it.wire == k } ?: CLICK

        /** 0.0–1.0 → 0..[LEVELS]; missing or not a number → [DEFAULT_STRENGTH]. */
        fun level(s: Double?): Int {
            val v = if (s == null || s.isNaN()) DEFAULT_STRENGTH else s.coerceIn(0.0, 1.0)
            return (v * LEVELS).roundToInt()
        }
    }
}
