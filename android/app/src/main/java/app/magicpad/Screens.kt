package app.magicpad

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    macs: List<MacService>,
    lastId: String?,
    isPaired: (MacService) -> Boolean,
    note: String?,
    onPick: (MacService) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MagicPad", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
        }
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
                items(AutoConnectRule.lastUsedFirst(macs, lastId), key = { it.serviceName }) { mac ->
                    MacRow(mac, isLast = mac.id == lastId, paired = isPaired(mac)) { onPick(mac) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One Mac in the list: name, "Last used" tag, and whether tapping it will ask for a code. */
@Composable
private fun MacRow(
    mac: MacService,
    isLast: Boolean,
    paired: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mac.serviceName, style = MaterialTheme.typography.titleMedium)
            if (isLast) {
                Text(
                    "Last used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            if (paired) "Paired" else "Not paired — you'll confirm a code",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConnectingScreen(
    macName: String,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
) = Centered {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text("Connecting to $macName…")
    Row {
        TextButton(onClick = onCancel) { Text("Cancel") }
        TextButton(onClick = onSettings) { Text("Settings") }
    }
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
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
