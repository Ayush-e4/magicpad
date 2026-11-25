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
import androidx.compose.material3.Surface
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.magicpad.pad.PadGl
import app.magicpad.pad.PadScreen
import app.magicpad.settings.SettingsModel
import app.magicpad.settings.SettingsScreen
import app.magicpad.ui.MagicPadTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var controller: Controller
    private lateinit var settings: SettingsModel
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = Controller(this, lifecycleScope)
        settings = SettingsModel(this)
        val glSupported = PadGl.supported(this)
        setContent {
            MagicPadTheme {
                Surface {
                    val screen = controller.screen
                    val inSettings = screen is Screen.Settings
                    BackHandler(enabled = inSettings) { controller.closeSettings() }
                    BackHandler(enabled = screen != Screen.Home && !inSettings) { controller.leave() }
                    when (screen) {
                        is Screen.Settings -> SettingsScreen(settings, glSupported, controller::closeSettings)
                        Screen.Home ->
                            HomeScreen(
                                controller.macs,
                                controller.lastMacId,
                                controller::isPaired,
                                controller.homeNote,
                                controller.onWifi,
                                controller::pick,
                                controller::openSettings,
                            )
                        is Screen.Connecting -> ConnectingScreen(screen.macName, controller::leave, controller::openSettings)
                        is Screen.Pairing -> PairingScreen(screen.macName, screen.code, controller::leave)
                        is Screen.Pad ->
                            PadScreen(
                                screen.macName,
                                screen.live,
                                screen.trouble,
                                controller,
                                settings.value,
                                settings.photoFile,
                                settings.photoVersion,
                            )
                        is Screen.Error -> ErrorScreen(screen.problem, controller::retry, controller::leave)
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
        controller.onOpened(direct = consumeTrackpadIntent())
    }

    override fun onStart() {
        super.onStart()
        controller.onStart(direct = consumeTrackpadIntent())
    }

    /** True once for an open by the tile or shortcut; later starts count as normal opens. */
    private fun consumeTrackpadIntent(): Boolean {
        val direct = intent?.action == ACTION_TRACKPAD
        if (direct) intent.action = null
        return direct
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    /**
     * On the pad: landscape, fullscreen, dimmed, screen kept on, low-latency Wi-Fi, and the
     * display's highest refresh rate. Many phones scan the touchscreen faster at a higher
     * refresh rate, and a mostly still pad would otherwise drop to the lowest one.
     */
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
                screenBrightness =
                    if (on) settings.value.effectiveBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                preferredDisplayModeId = if (on) fastestModeId() else 0
            }
        setWifiLock(on)
    }

    /** The display mode with the highest refresh rate at the current resolution, or 0 (no preference). */
    private fun fastestModeId(): Int {
        val d =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION") // Activity.display replaces it on API 30+.
                windowManager.defaultDisplay
            } ?: return 0
        val now = d.mode
        return d.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.modeId ?: 0
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
        private const val ACTION_TRACKPAD = "app.magicpad.action.TRACKPAD"

        /** Opens the app and auto-connects to the last Mac (also used by shortcuts.xml). */
        fun trackpadIntent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_TRACKPAD)
    }
}
