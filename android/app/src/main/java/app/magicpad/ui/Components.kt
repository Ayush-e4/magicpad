package app.magicpad.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.magicpad.R

/** The app icon as a small rounded tile: the trackpad glyph on the brand gradient. */
@Composable
fun BrandMark(size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brand.gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_trackpad), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/** A filled pill button on the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Brand.gradient)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A titled group of settings on a rounded card. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

/** Rings that grow and fade out from the centre, like a radar: "looking for something". */
@Composable
fun SearchPulse(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit = {},
) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val maxR = size.minDimension / 2
            for (i in 0 until RINGS) {
                val p = (t + i.toFloat() / RINGS) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.5f), radius = maxR * (0.3f + 0.7f * p), style = Stroke(2.dp.toPx()))
            }
        }
        content()
    }
}

private const val PULSE_MS = 2400
private const val RINGS = 3
