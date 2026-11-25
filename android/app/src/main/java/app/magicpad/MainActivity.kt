package app.magicpad

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var controller: Controller
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = Controller(this, lifecycleScope)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    val screen = controller.screen
                    BackHandler(enabled = screen != Screen.Home) { controller.leave() }
                    when (screen) {
                        Screen.Home -> HomeScreen(controller.macs, controller.homeNote, controller::pick)
                        is Screen.Connecting -> ConnectingScreen(screen.macName, controller::leave)
                        is Screen.Pairing -> PairingScreen(screen.macName, screen.code, controller::leave)
                        is Screen.Pad -> PadScreen(screen.macName, screen.live, controller)
                        is Screen.Error -> ErrorScreen(screen.message, controller::leave)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    snapshotFlow { controller.screen is Screen.Pad }.collect { setPadMode(it) }
                } finally {
                    setPadMode(false)
                }
            }
        }
    }

    /** The launcher, the Quick Settings tile or the shortcut opened the app while it was running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.onOpened()
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    /** On the pad: landscape, fullscreen, dimmed, screen kept on, low-latency Wi-Fi. */
    @SuppressLint("SourceLockedOrientationActivity") // The pad is a landscape trackpad by design.
    private fun setPadMode(on: Boolean) {
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.attributes =
            window.attributes.apply {
                screenBrightness = if (on) PAD_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        setWifiLock(on)
    }

    private fun setWifiLock(on: Boolean) {
        if (!on) {
            wifiLock?.takeIf { it.isHeld }?.release()
            wifiLock = null
            return
        }
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") // Replaced by FULL_LOW_LATENCY on API 29+.
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
        wifiLock =
            wifi.createWifiLock(mode, "magicpad:pad").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    companion object {
        private const val PAD_BRIGHTNESS = 0.05f
        private const val ACTION_TRACKPAD = "app.magicpad.action.TRACKPAD"

        /** Opens the app and auto-connects to the last Mac (also used by shortcuts.xml). */
        fun trackpadIntent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_TRACKPAD)
    }
}
