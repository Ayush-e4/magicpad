package app.magicpad.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.magicpad.ui.Brand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pad backgrounds as tiles with small moving previews, three to a row. The previews
 * are cheap Compose drawings that suggest each look; the real ones are drawn with GL.
 */
@Composable
fun BackgroundPicker(
    selected: PadBackground,
    photo: ImageBitmap?,
    glSupported: Boolean,
    onPick: (PadBackground) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in PadBackground.entries.chunked(PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (bg in row) {
                    Tile(bg, bg == selected, enabled = glSupported || !bg.usesGl, photo, Modifier.weight(1f)) { onPick(bg) }
                }
                repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(
    bg: PadBackground,
    selected: Boolean,
    enabled: Boolean,
    photo: ImageBitmap?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TILE_RATIO)
                .clip(shape)
                .background(Color.Black)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                ),
        ) {
            when (bg) {
                PadBackground.BLACK -> Unit
                PadBackground.PHOTO -> PhotoPreview(photo)
                PadBackground.FLUID -> FluidPreview()
                PadBackground.GLOW -> GlowPreview()
                PadBackground.AMBIENT -> AmbientPreview()
            }
        }
        Text(
            bg.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PhotoPreview(photo: ImageBitmap?) {
    if (photo != null) {
        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 0→1 forever, over [periodMs]. */
@Composable
private fun loop(periodMs: Int): Float {
    val t by rememberInfiniteTransition(label = "preview").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "preview",
    )
    return t
}

/** Three soft blobs circling each other. */
@Composable
private fun FluidPreview() {
    val t = loop(FLUID_MS)
    val colors = listOf(Brand.Indigo, Brand.Violet, Color(0xFF29C5F6))
    Canvas(Modifier.fillMaxSize()) {
        colors.forEachIndexed { i, c ->
            val a = 2 * PI * (t + i / 3.0)
            val center = Offset(size.width * (0.5f + 0.28f * cos(a).toFloat()), size.height * (0.5f + 0.3f * sin(2 * a).toFloat()))
            val r = size.minDimension * 0.75f
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.85f), Color.Transparent), center, r), r, center)
        }
    }
}

/** A glowing fingertip tracing a loop. */
@Composable
private fun GlowPreview() {
    val t = loop(GLOW_MS)
    Canvas(Modifier.fillMaxSize()) {
        val a = 2 * PI * t
        val center = Offset(size.width * (0.5f + 0.32f * sin(a).toFloat()), size.height * (0.5f + 0.25f * sin(2 * a).toFloat()))
        val r = size.minDimension * 0.45f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB9A2FF), Brand.Violet.copy(alpha = 0.4f), Color.Transparent), center, r), r, center)
    }
}

/** A diagonal gradient whose colours drift. */
@Composable
private fun AmbientPreview() {
    val t = loop(AMBIENT_MS)
    val wave = (sin(2 * PI * t).toFloat() + 1) / 2
    val a = lerp(Brand.Indigo, Color(0xFF1C8FB8), wave)
    val b = lerp(Brand.Violet, Color(0xFFE0569B), 1 - wave)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a.copy(alpha = 0.7f), b.copy(alpha = 0.7f)))))
}

private const val PER_ROW = 3
private const val TILE_RATIO = 1.6f
private const val DISABLED_ALPHA = 0.4f
private const val FLUID_MS = 6000
private const val GLOW_MS = 4000
private const val AMBIENT_MS = 8000
