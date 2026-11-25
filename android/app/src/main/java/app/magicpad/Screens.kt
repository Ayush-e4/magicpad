package app.magicpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.magicpad.ui.Brand
import app.magicpad.ui.GradientButton
import app.magicpad.ui.SearchPulse

@Composable
fun ConnectingScreen(
    macName: String,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
) = Centered {
    SearchPulse(Modifier.size(180.dp)) { MacBadge() }
    Text("Connecting to $macName…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
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
    MacBadge()
    Spacer(Modifier.height(16.dp))
    Text("Pair with $macName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        "Check that your Mac shows the same code, then click Allow there.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
    )
    CodeDigits(code)
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

/** One box per digit, in groups of three. */
@Composable
private fun CodeDigits(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, digit ->
            if (i > 0 && i % 3 == 0) Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.5.dp, Brand.gradient, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$digit", fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** What went wrong, what to check, and the way forward. */
@Composable
fun ErrorScreen(
    problem: Problem,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) = Centered {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
    Spacer(Modifier.height(16.dp))
    Text(problem.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(
        problem.help,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    if (problem.canRetry) GradientButton("Try again", onRetry)
    TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back to Macs") }
}

@Composable
private fun MacBadge() {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_mac), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
