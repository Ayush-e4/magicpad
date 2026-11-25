package app.magicpad.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.magicpad.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    model: SettingsModel,
    glSupported: Boolean,
    onBack: () -> Unit,
) {
    val s = model.value
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = s.autoConnect, role = Role.Switch) { on -> model.update { it.withAutoConnect(on) } }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Connect automatically")
                Text(
                    "Skips the list when only your last-used Mac is around",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.autoConnect, onCheckedChange = null)
        }
        Spacer(Modifier.height(8.dp))
        Text("Pad background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.selectableGroup()) {
            for (bg in PadBackground.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.background == bg, role = Role.RadioButton) {
                            model.update { it.withBackground(bg) }
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.background == bg, onClick = null)
                    Text(bg.label, Modifier.padding(start = 12.dp))
                }
            }
        }
        if (!glSupported && s.background.usesGl) {
            Note("Animated backgrounds need OpenGL ES 3, which this phone lacks, so the pad will be black.")
        }
        if (s.background == PadBackground.PHOTO) PhotoSection(model)
        Spacer(Modifier.height(16.dp))
        LabeledSlider(
            label = "Screen brightness on the pad",
            value = s.effectiveBrightness,
            range = PadSettings.MIN_BRIGHTNESS..PadSettings.MAX_BRIGHTNESS,
            onChange = { v -> model.update { it.withBrightness(v) } },
        )
    }
}

@Composable
private fun PhotoSection(model: SettingsModel) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { failed = !model.importPhoto(uri) }
        }
    val thumb = rememberThumbnail(model.photoFile, model.photoVersion)
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (thumb != null) {
                Image(thumb, contentDescription = "Pad photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) {
            Text("Choose photo…")
        }
    }
    if (failed) Note("That photo couldn't be read. Try another one.")
    LabeledSlider(
        label = "Photo dimming",
        value = model.value.photoDim,
        range = 0f..PadSettings.MAX_PHOTO_DIM,
        onChange = { v -> model.update { it.withPhotoDim(v) } },
    )
}

/** A small decode of the saved photo, reloaded when [version] changes. */
@Composable
private fun rememberThumbnail(
    file: File,
    version: Long,
): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, version) {
        value =
            if (version == 0L) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = THUMB_SAMPLE })?.asImageBitmap()
                }
            }
    }
    return bitmap
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            Text(label, Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val state = remember(range) { SliderState(value, 0, range) }
        // Follow outside changes too (the brightness default moves with the background).
        LaunchedEffect(value) { if (state.value != value) state.value = value }
        Slider(
            state = state,
            onValueChange = {
                state.value = it
                onChange(it)
            },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private const val THUMB_SAMPLE = 8
