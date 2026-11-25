package app.magicpad.pad

import androidx.annotation.DrawableRes
import app.magicpad.R

/** A shortcut button and the action the Mac runs for it (`wire`, PROTOCOL.md §2 `action`). */
enum class Shortcut(
    val wire: String,
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    PREVIOUS("media.previous", "Previous track", R.drawable.ic_previous),
    PLAY_PAUSE("media.playpause", "Play or pause", R.drawable.ic_play_pause),
    NEXT("media.next", "Next track", R.drawable.ic_next),
    SCREENSHOT("screenshot.screen", "Screenshot", R.drawable.ic_screenshot),
}
