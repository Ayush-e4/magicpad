package app.magicpad.pad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.magicpad.settings.StripPlace
import app.magicpad.settings.StripSide

/**
 * The shortcut buttons, in a column on one side of the pad. Drawn above PadView, so
 * Compose hit-tests them first: a touch that starts on a button never reaches the Mac
 * as a trackpad touch. Place it with `Modifier.align(stripAlignment(side, place))`.
 */
@Composable
fun ShortcutStrip(
    side: StripSide,
    place: StripPlace,
    onShortcut: (Shortcut) -> Unit,
    modifier: Modifier = Modifier,
    /** False while reconnecting: dimmed, and taps do nothing. */
    enabled: Boolean = true,
) {
    // Top right is where the Settings gear lives: start below it.
    val top = if (side == StripSide.RIGHT && place == StripPlace.TOP) GEAR_CLEARANCE else EDGE_GAP
    Column(
        modifier
            .padding(start = EDGE_GAP, end = EDGE_GAP, top = top, bottom = EDGE_GAP)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.Black.copy(alpha = STRIP_ALPHA))
            .padding(4.dp),
    ) {
        for (s in Shortcut.entries) ShortcutButton(s, enabled, onShortcut)
    }
}

/** Where the strip sits on the pad: a side, then top / middle / bottom of it. */
fun stripAlignment(
    side: StripSide,
    place: StripPlace,
): Alignment =
    when (side) {
        StripSide.LEFT ->
            when (place) {
                StripPlace.TOP -> Alignment.TopStart
                StripPlace.MIDDLE -> Alignment.CenterStart
                StripPlace.BOTTOM -> Alignment.BottomStart
            }
        StripSide.RIGHT ->
            when (place) {
                StripPlace.TOP -> Alignment.TopEnd
                StripPlace.MIDDLE -> Alignment.CenterEnd
                StripPlace.BOTTOM -> Alignment.BottomEnd
            }
    }

@Composable
private fun ShortcutButton(
    shortcut: Shortcut,
    enabled: Boolean,
    onShortcut: (Shortcut) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "press")
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(press, ripple(), enabled = enabled, role = Role.Button, onClickLabel = shortcut.label) { onShortcut(shortcut) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(shortcut.icon),
            contentDescription = shortcut.label,
            tint = Color.White.copy(alpha = ICON_ALPHA),
            modifier = Modifier.size(22.dp),
        )
    }
}

private val EDGE_GAP = 8.dp
private val GEAR_CLEARANCE = 56.dp
private const val STRIP_ALPHA = 0.35f
private const val ICON_ALPHA = 0.85f
private const val PRESSED_SCALE = 0.85f
private const val DISABLED_ALPHA = 0.35f
