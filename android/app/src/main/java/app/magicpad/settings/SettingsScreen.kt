package app.magicpad.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.magicpad.R
import app.magicpad.ui.SectionCard
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
    val thumb = rememberThumbnail(model.photoFile, model.photoVersion)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        SectionCard("Connection") {
            SwitchRow("Connect automatically", "Skips the list when only your last-used Mac is around", s.autoConnect) { on ->
                model.update { it.withAutoConnect(on) }
            }
        }
        SectionCard("Pad look") {
            Text(
                "Background",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp),
            )
            BackgroundPicker(s.background, thumb, glSupported) { bg -> model.update { it.withBackground(bg) } }
            if (!glSupported) {
                Note("Animated backgrounds need OpenGL ES 3, which this phone lacks.")
            }
            if (s.background == PadBackground.PHOTO) PhotoSection(model, thumb)
            Spacer(Modifier.height(16.dp))
            LabeledSlider(
                label = "Screen brightness on the pad",
                value = s.effectiveBrightness,
                range = PadSettings.MIN_BRIGHTNESS..PadSettings.MAX_BRIGHTNESS,
                onChange = { v -> model.update { it.withBrightness(v) } },
            )
        }
        SectionCard("Shortcut buttons") {
            SwitchRow("Show on the pad", "Previous, play/pause, next and screenshot", s.shortcuts) { on ->
                model.update { it.withShortcuts(on) }
            }
            if (s.shortcuts) {
                Text(
                    "Tap where you want them",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                StripPositionPicker(s.stripSide, s.stripPlace) { side, place ->
                    model.update { it.withStripSide(side).withStripPlace(place) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A title, a description, and a switch that never crowd each other. */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PhotoSection(
    model: SettingsModel,
    thumb: ImageBitmap?,
) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { failed = !model.importPhoto(uri) }
        }
    OutlinedButton(
        onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(if (thumb == null) "Choose photo…" else "Change photo…")
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
    Column(Modifier.padding(horizontal = 16.dp)) {
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private const val THUMB_SAMPLE = 8
