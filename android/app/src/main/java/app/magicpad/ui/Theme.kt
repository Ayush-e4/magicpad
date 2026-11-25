package app.magicpad.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** MagicPad's blue-violet, the same hues as the Fluid background and the app icon. */
object Brand {
    val Indigo = Color(0xFF5B6CFF)
    val Violet = Color(0xFFA25BFF)
    val Online = Color(0xFF3DDC84)

    val gradient: Brush get() = Brush.linearGradient(listOf(Indigo, Violet))
}

private val Dark =
    darkColorScheme(
        primary = Color(0xFFB4BBFF),
        onPrimary = Color(0xFF151A63),
        primaryContainer = Color(0xFF2E3591),
        onPrimaryContainer = Color(0xFFE0E0FF),
        secondary = Color(0xFFD5B8FF),
        onSecondary = Color(0xFF3B1470),
        background = Color(0xFF0D0E13),
        onBackground = Color(0xFFE6E6EE),
        surface = Color(0xFF0D0E13),
        onSurface = Color(0xFFE6E6EE),
        surfaceVariant = Color(0xFF242634),
        onSurfaceVariant = Color(0xFFA9ACBF),
        surfaceContainerLowest = Color(0xFF0A0B0F),
        surfaceContainerLow = Color(0xFF14151C),
        surfaceContainer = Color(0xFF181A22),
        surfaceContainerHigh = Color(0xFF20222C),
        surfaceContainerHighest = Color(0xFF2A2C38),
        outline = Color(0xFF454860),
        outlineVariant = Color(0xFF2E3040),
        error = Color(0xFFFFB4AB),
    )

private val Light =
    lightColorScheme(
        primary = Color(0xFF4A56D6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E0FF),
        onPrimaryContainer = Color(0xFF0E1260),
        secondary = Color(0xFF7A45D1),
        onSecondary = Color.White,
        background = Color(0xFFF7F7FC),
        onBackground = Color(0xFF15161C),
        surface = Color(0xFFF7F7FC),
        onSurface = Color(0xFF15161C),
        surfaceVariant = Color(0xFFE3E3EE),
        onSurfaceVariant = Color(0xFF5A5C6E),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF1F1F8),
        surfaceContainer = Color(0xFFECECF5),
        surfaceContainerHigh = Color(0xFFE6E6F0),
        surfaceContainerHighest = Color(0xFFE0E0EB),
        outline = Color(0xFF8C8EA3),
        outlineVariant = Color(0xFFCFD0DE),
    )

/** Follows the system's dark mode, with MagicPad's own colours either way. */
@Composable
fun MagicPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
