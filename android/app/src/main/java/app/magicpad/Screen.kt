package app.magicpad

/** What the UI shows; owned by [Controller]. */
sealed interface Screen {
    data object Home : Screen

    data class Connecting(
        val macName: String,
    ) : Screen

    data class Pairing(
        val macName: String,
        val code: String,
    ) : Screen

    /** [live] is false while reconnecting. */
    data class Pad(
        val macName: String,
        val live: Boolean,
    ) : Screen

    data class Error(
        val message: String,
    ) : Screen
}
