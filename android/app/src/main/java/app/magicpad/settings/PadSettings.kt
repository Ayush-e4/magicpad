package app.magicpad.settings

/** What is drawn behind the pad. Pure Kotlin, so it runs in unit tests. */
enum class PadBackground(
    val key: String,
    val label: String,
) {
    BLACK("black", "Black"),
    PHOTO("photo", "Your photo"),
    FLUID("fluid", "Fluid"),
    GLOW("glow", "Finger glow"),
    AMBIENT("ambient", "Ambient gradient"),
    ;

    /** Drawn with OpenGL ES 3 on its own thread. */
    val usesGl: Boolean get() = this == FLUID || this == GLOW || this == AMBIENT

    companion object {
        fun fromKey(key: String?): PadBackground = entries.firstOrNull { it.key == key } ?: BLACK
    }
}

/**
 * Pad look settings. [brightness] is null until the user moves the slider; until then the
 * pad uses [defaultBrightness] for the chosen background (dim for Black, brighter otherwise).
 */
data class PadSettings(
    val background: PadBackground = PadBackground.BLACK,
    val photoDim: Float = DEFAULT_PHOTO_DIM,
    val brightness: Float? = null,
) {
    /** Window screenBrightness to use on the pad, 0.01–1. */
    val effectiveBrightness: Float get() = brightness ?: defaultBrightness(background)

    fun withBackground(bg: PadBackground) = copy(background = bg)

    /** The user moved the slider: from now on it no longer follows the background. */
    fun withBrightness(value: Float) = copy(brightness = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS))

    fun withPhotoDim(value: Float) = copy(photoDim = value.coerceIn(0f, MAX_PHOTO_DIM))

    companion object {
        const val MIN_BRIGHTNESS = 0.01f
        const val MAX_BRIGHTNESS = 1f
        const val BLACK_BRIGHTNESS = 0.05f
        const val BACKGROUND_BRIGHTNESS = 0.40f
        const val DEFAULT_PHOTO_DIM = 0.40f
        const val MAX_PHOTO_DIM = 0.80f

        fun defaultBrightness(bg: PadBackground): Float = if (bg == PadBackground.BLACK) BLACK_BRIGHTNESS else BACKGROUND_BRIGHTNESS
    }
}
