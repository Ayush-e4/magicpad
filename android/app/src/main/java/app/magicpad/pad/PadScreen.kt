package app.magicpad.pad

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.magicpad.Controller
import app.magicpad.R
import app.magicpad.settings.PadBackground
import app.magicpad.settings.PadSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The live pad: the chosen background at the bottom, the transparent [PadView] on top
 * (it takes every touch), and the faint status label above both.
 */
@Composable
fun PadScreen(
    macName: String,
    live: Boolean,
    controller: Controller,
    settings: PadSettings,
    photo: File,
    photoVersion: Long,
) {
    val context = LocalContext.current
    val glSupported = remember { PadGl.supported(context) }
    val background = settings.background.takeIf { !it.usesGl || glSupported } ?: PadBackground.BLACK
    val pointers = remember { PointerBuffer() }
    val gl = remember(background) { if (background.usesGl) PadGl(context, background, pointers) else null }
    if (gl != null) FollowLifecycle(gl)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            gl != null -> AndroidView(factory = { gl.view }, modifier = Modifier.fillMaxSize())
            background == PadBackground.PHOTO && photoVersion != 0L -> PhotoLayer(photo, photoVersion, settings.photoDim)
        }
        key(gl) {
            AndroidView(
                factory = { ctx ->
                    if (gl == null) {
                        PadView(ctx, controller::onTouches)
                    } else {
                        PadView(ctx, controller::onTouches, pointers, Runnable { gl.renderer.kick() })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            if (live) macName else "Reconnecting to $macName…",
            color = Color.White.copy(alpha = if (live) LABEL_ALPHA else 0.7f),
            style = TextStyle(fontSize = 12.sp, shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), blurRadius = 6f)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
        // Above PadView: Compose hit-tests it first, so touches that start here never reach the Mac.
        SettingsGear(controller::openSettings, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
    }
}

/** A faint gear (20 dp icon, 48 dp target) with a soft shadow so it reads on any background. */
@Composable
private fun SettingsGear(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .systemGestureExclusion()
            .clickable(role = Role.Button, onClickLabel = "Settings", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val gear = painterResource(R.drawable.ic_settings)
        Icon(
            gear,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp).offset(y = 1.dp).blur(3.dp, BlurredEdgeTreatment.Unbounded),
        )
        Icon(gear, contentDescription = "Settings", tint = Color.White.copy(alpha = GEAR_ALPHA), modifier = Modifier.size(20.dp))
    }
}

/** Resumes and pauses the GL thread (and the Ambient ticker) with the activity. */
@Composable
private fun FollowLifecycle(gl: PadGl) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(gl, owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> gl.onResume()
                    Lifecycle.Event.ON_PAUSE -> gl.onPause()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            gl.onPause()
        }
    }
}

/** The saved photo, cropped to fill, under a black overlay of [dim] opacity. Static: nothing per frame. */
@Composable
private fun PhotoLayer(
    file: File,
    version: Long,
    dim: Float,
) {
    val image by produceState<ImageBitmap?>(null, version) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    val bitmap = image ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
}

private const val LABEL_ALPHA = 0.35f
private const val GEAR_ALPHA = 0.3f
