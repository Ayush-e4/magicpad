package app.magicpad

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.magicpad.ui.Brand
import app.magicpad.ui.BrandMark
import app.magicpad.ui.GradientButton
import app.magicpad.ui.SearchPulse

@Composable
fun HomeScreen(
    macs: List<MacService>,
    lastId: String?,
    isPaired: (MacService) -> Boolean,
    note: String?,
    onWifi: Boolean,
    onPick: (MacService) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Header(onSettings)
        if (note != null) NoteCard(note)
        Spacer(Modifier.height(16.dp))
        if (macs.isEmpty() && !onWifi) {
            NoWifi()
        } else if (macs.isEmpty()) {
            Searching()
        } else {
            Text(
                "MACS NEARBY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AutoConnectRule.lastUsedFirst(macs, lastId), key = { it.serviceName }) { mac ->
                    MacCard(mac, isLast = mac.id == lastId, paired = isPaired(mac)) { onPick(mac) }
                }
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("MagicPad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Your phone is your Mac's trackpad",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
    }
}

@Composable
private fun NoteCard(note: String) {
    Text(
        note,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
    )
}

/** One Mac nearby: icon with an online dot, name, tags, and a Connect button. */
@Composable
private fun MacCard(
    mac: MacService,
    isLast: Boolean,
    paired: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "Connect", onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_mac), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Brand.Online),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(mac.serviceName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    if (paired) "Paired" else "New · confirm a code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isLast) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Last used")
                }
            }
        }
        GradientButton("Connect", onClick)
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Searching can't work off Wi-Fi: say so and offer the way to fix it. */
@Composable
private fun NoWifi() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_wifi_off), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text("You're not on Wi-Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "MagicPad finds your Mac over Wi-Fi. Join the same network as your Mac and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        GradientButton("Open Wi-Fi settings", { openWifiSettings(context) })
    }
}

private fun openWifiSettings(context: Context) {
    val panel =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
    try {
        context.startActivity(Intent(panel))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }
}

/** No Mac found yet: the radar, then the three setup steps. */
@Composable
private fun Searching() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        SearchPulse(Modifier.size(180.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_mac), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text("Looking for your Mac…", style = MaterialTheme.typography.titleMedium)
        Text(
            "Make sure MagicPad is running on your Mac.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        SetupSteps()
    }
}

@Composable
private fun SetupSteps() {
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("First time?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Step(1, "Get the Mac app and open it. It lives in the menu bar.")
        Step(2, "Put your phone and Mac on the same Wi-Fi.")
        Step(3, "Tap your Mac here and check the code matches on both screens.")
        TextButton(onClick = { uri.openUri(RELEASES_URL) }) { Text("Get the Mac app") }
    }
}

@Composable
private fun Step(
    n: Int,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val RELEASES_URL = "https://github.com/Ayush-e4/magicpad/releases/latest"
