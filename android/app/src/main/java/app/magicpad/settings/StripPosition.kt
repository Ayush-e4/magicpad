package app.magicpad.settings

/** Which side of the pad the shortcut buttons sit on. */
enum class StripSide(
    val key: String,
    val label: String,
) {
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    ;

    companion object {
        fun fromKey(key: String?): StripSide = entries.firstOrNull { it.key == key } ?: LEFT
    }
}

/** Where along that side: top and bottom put the buttons in a corner. */
enum class StripPlace(
    val key: String,
    val label: String,
) {
    TOP("top", "Top"),
    MIDDLE("middle", "Middle"),
    BOTTOM("bottom", "Bottom"),
    ;

    companion object {
        fun fromKey(key: String?): StripPlace = entries.firstOrNull { it.key == key } ?: MIDDLE
    }
}
