package app.magicpad.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.magicpad.pad.stripAlignment

/**
 * A small landscape phone with six spots (left/right × top/middle/bottom). Tap one to put
 * the shortcut buttons there; the chosen spot shows a mini strip.
 */
@Composable
fun StripPositionPicker(
    side: StripSide,
    place: StripPlace,
    onPick: (StripSide, StripPlace) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(PHONE_RATIO)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                .padding(10.dp)
                .selectableGroup(),
        ) {
            for (s in StripSide.entries) {
                for (p in StripPlace.entries) {
                    val chosen = s == side && p == place
                    Box(
                        Modifier
                            .align(stripAlignment(s, p))
                            .size(width = 40.dp, height = SPOT_HEIGHT)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(s, p) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) MiniStrip() else Spot()
                    }
                }
            }
        }
        Text(
            "${side.label} · ${place.label}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MiniStrip() {
    Column(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(4) { Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary)) }
    }
}

@Composable
private fun Spot() {
    Box(Modifier.size(14.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

private const val PHONE_RATIO = 2.1f

/** Three spots stack on each side without overlapping, even at the drawing's 320 dp width. */
private val SPOT_HEIGHT = 38.dp
