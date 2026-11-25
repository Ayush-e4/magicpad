package app.magicpad

import app.magicpad.net.FailKind

/**
 * Something that went wrong, told the way a person would: what happened ([title]) and
 * what to check or do next ([help]). Pure Kotlin, so it runs in unit tests.
 */
data class Problem(
    val title: String,
    val help: String,
    /** Whether "Try again" makes sense (it reconnects, pairing if needed). */
    val canRetry: Boolean = true,
) {
    companion object {
        fun denied(mac: String) =
            Problem(
                "$mac didn't allow this phone",
                "Tap Try again, then click Allow in the dialog that appears on your Mac.",
            )

        fun of(
            kind: FailKind,
            mac: String,
        ): Problem =
            when (kind) {
                FailKind.UNREACHABLE ->
                    Problem(
                        "Can't reach $mac",
                        "Make sure MagicPad is running on your Mac (its icon is in the menu bar), " +
                            "your Mac is awake, and both are on the same Wi-Fi.",
                    )
                FailKind.NO_RESPONSE ->
                    Problem(
                        "$mac stopped responding",
                        "Your Mac may have gone to sleep or left the Wi-Fi. Wake it up and try again.",
                    )
                FailKind.LOST ->
                    Problem(
                        "Lost the connection to $mac",
                        "The Wi-Fi dropped or MagicPad was closed on the Mac. Check both, then try again.",
                    )
                FailKind.PAIRING_TIMEOUT ->
                    Problem(
                        "No answer from $mac",
                        "Nobody answered the pairing request on the Mac. Tap Try again and click Allow on " +
                            "your Mac within a minute.",
                    )
                FailKind.INCOMPATIBLE ->
                    Problem(
                        "$mac has a different version of MagicPad",
                        "Update MagicPad on both your phone and your Mac to the latest release.",
                        canRetry = false,
                    )
                FailKind.SECURITY ->
                    Problem(
                        "Couldn't connect securely to $mac",
                        "Try again. If it keeps happening, remove this phone from Paired Phones in the " +
                            "Mac's MagicPad menu, then pair again.",
                    )
            }
    }
}
