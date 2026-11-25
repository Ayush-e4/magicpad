package app.magicpad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HomeScreen(
    macs: List<MacService>,
    note: String?,
    onPick: (MacService) -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Text("MagicPad", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Open MagicPad on your Mac. Both devices need to be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        if (macs.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("Searching for your Mac on this Wi-Fi…")
            }
        } else {
            LazyColumn {
                items(macs, key = { it.serviceName }) { mac ->
                    Text(
                        mac.serviceName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(mac) }.padding(vertical = 16.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ConnectingScreen(
    macName: String,
    onCancel: () -> Unit,
) = Centered {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text("Connecting to $macName…")
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
fun PairingScreen(
    macName: String,
    code: String,
    onCancel: () -> Unit,
) = Centered {
    Text("Pair with $macName", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        code.chunked(3).joinToString(" "),
        fontSize = 56.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))
    Text("Confirm this code on your Mac", color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
fun ErrorScreen(
    message: String,
    onOk: () -> Unit,
) = Centered {
    Text(message, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onOk) { Text("OK") }
}

@Composable
fun PadScreen(
    macName: String,
    live: Boolean,
    controller: Controller,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> PadView(context, controller::onTouches) },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            if (live) macName else "Reconnecting to $macName…",
            color = Color.White.copy(alpha = if (live) 0.25f else 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
