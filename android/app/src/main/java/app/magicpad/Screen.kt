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

    /** Settings, and where Back goes afterwards. */
    data class Settings(
        val back: SettingsReturn,
    ) : Screen
}

sealed interface SettingsReturn {
    data object Home : SettingsReturn

    /** Back to the pad: reconnect to [mac] (never pairs). */
    data class Pad(
        val mac: MacService,
    ) : SettingsReturn

    /** Back to connecting: to [mac], or (null) auto-connect to the last Mac again. */
    data class Connect(
        val mac: MacService?,
    ) : SettingsReturn
}
