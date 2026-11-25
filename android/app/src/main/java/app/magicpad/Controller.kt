package app.magicpad

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.magicpad.net.FrameSender
import app.magicpad.net.Session
import app.magicpad.net.SessionEnd
import app.magicpad.net.SessionListener
import app.magicpad.net.TouchPoint
import app.magicpad.pad.Shortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns discovery and the connection to one Mac; the UI renders [screen]. Main thread only.
 *
 * Auto-connect ([AutoConnectRule]): from the launcher, only when the last Mac that reached
 * `ready` is the only Mac around; from the tile/shortcut, straight to that Mac. It never
 * pairs; a Mac that needs pairing sends the user back to the list with a note.
 */
class Controller(
    context: Context,
    private val scope: CoroutineScope,
) {
    var screen: Screen by mutableStateOf(Screen.Home)
        private set
    var macs: List<MacService> by mutableStateOf(emptyList())
        private set

    /** A short note under the Home title, e.g. why auto-connect stopped. */
    var homeNote: String? by mutableStateOf(null)
        private set

    private val appContext = context.applicationContext
    private val store = Store(context)
    private val haptics = Haptics(context)
    private val discovery =
        Discovery(context) { found ->
            macs = found
            auto.evaluate()
        }
    private val wifi = WifiWatcher(context) { discovery.restart() }

    /** False on Home while the phone isn't on any Wi-Fi network. */
    val onWifi: Boolean get() = wifi.connected

    /** False when the Mac says it isn't allowed to move the pointer (Accessibility is off there). */
    var macAccess: Boolean by mutableStateOf(true)
        private set

    private val deviceName =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    @Volatile private var sender: FrameSender? = null

    @Volatile private var session: Session? = null
    private var job: Job? = null
    private var target: MacService? = null
    private var resumeOnStart = false
    private val auto = AutoConnector(scope, { macs.mapTo(HashSet()) { it.id } }, ::onAutoDecision)

    /** [direct]: opened by the tile or shortcut (connect straight to the last Mac). */
    fun onStart(direct: Boolean) {
        wifi.start()
        discovery.start()
        val mac = target
        if (resumeOnStart && mac != null) {
            resumeOnStart = false
            connect(mac, allowPairing = false, auto = false)
        } else {
            armAutoConnect(direct)
        }
    }

    /** The app was opened again (launcher, tile or shortcut) while it was already running. */
    fun onOpened(direct: Boolean) = armAutoConnect(direct)

    /** Whether this phone has a pairing key for [mac] (looked up by its discovery id). */
    fun isPaired(mac: MacService): Boolean = store.pairingKey(mac.id) != null

    val lastMacId: String? get() = store.lastMacId

    fun onStop() {
        wifi.stop()
        discovery.stop()
        val current = screen
        if (current is Screen.Pad) {
            // Come back to the pad (reconnecting) when the app returns.
            resumeOnStart = true
            dropConnection()
            show(current.copy(live = false))
        } else if (job != null || auto.mode != null) {
            leave()
        }
    }

    /** The user tapped a Mac in the list. */
    fun pick(mac: MacService) {
        homeNote = null
        disarmAutoConnect()
        connect(mac, allowPairing = true, auto = false)
    }

    /** "Try again" on an error: reconnect to the same Mac, pairing if it asks. */
    fun retry() {
        val mac = (screen as? Screen.Error)?.mac ?: return leave()
        connect(mac, allowPairing = true, auto = false)
    }

    /** Back to the list of Macs (Cancel, Back). Also stops auto-connect until the app is next opened. */
    fun leave() {
        disarmAutoConnect()
        dropConnection()
        target = null
        resumeOnStart = false
        show(Screen.Home)
    }

    /** Opens Settings, dropping any pad session or connect attempt until Settings is closed. */
    fun openSettings() {
        val mac = target
        val back =
            when (screen) {
                is Screen.Pad -> mac?.let { SettingsReturn.Pad(it) } ?: SettingsReturn.Home
                is Screen.Connecting -> SettingsReturn.Connect(mac)
                else -> SettingsReturn.Home
            }
        disarmAutoConnect()
        dropConnection()
        resumeOnStart = false
        show(Screen.Settings(back))
    }

    /** Back from Settings to wherever it was opened from. */
    fun closeSettings() {
        val back = (screen as? Screen.Settings)?.back ?: return
        when (back) {
            SettingsReturn.Home -> show(Screen.Home)
            is SettingsReturn.Pad -> {
                show(Screen.Pad(back.mac.serviceName, live = false))
                connect(back.mac, allowPairing = false, auto = false) // The pad's reconnect path.
            }
            is SettingsReturn.Connect -> {
                val mac = back.mac
                if (mac != null) {
                    connect(mac, allowPairing = false, auto = true)
                } else {
                    show(Screen.Home)
                    armAutoConnect(direct = true) // It was waiting for the last Mac.
                }
            }
        }
    }

    fun onTouches(
        touches: List<TouchPoint>,
        widthMm: Double,
        heightMm: Double,
        timeMs: Long,
    ) {
        sender?.update(touches, widthMm, heightMm, timeMs)
    }

    /** A shortcut button on the pad: buzz right away, then ask the Mac to run it. */
    fun runShortcut(shortcut: Shortcut) {
        haptics.play(HapticKind.CLICK, null)
        session?.sendAction(shortcut.wire)
    }

    /** SMART (launcher) or DIRECT (tile/shortcut); a DIRECT request overrides a pending SMART one. */
    private fun armAutoConnect(direct: Boolean) {
        // Only from Home/Error: never while Settings is open (e.g. back from the photo picker).
        if (job != null || (screen != Screen.Home && screen !is Screen.Error)) return
        val pending = auto.mode
        if (pending == AutoMode.DIRECT || (pending == AutoMode.SMART && !direct)) return
        if (direct) {
            show(Screen.Connecting(store.lastMacName ?: "your Mac"))
        } else if (screen is Screen.Error) {
            show(Screen.Home)
        }
        auto.arm(if (direct) AutoMode.DIRECT else AutoMode.SMART, store.lastMacId, store.loadPadSettings().autoConnect)
    }

    private fun onAutoDecision(
        decision: AutoDecision,
        mode: AutoMode,
    ) {
        when (decision) {
            is AutoDecision.Connect -> {
                val mac = discovery.find(decision.id)
                if (mac != null) {
                    connect(mac, allowPairing = false, auto = true)
                } else if (mode == AutoMode.DIRECT) {
                    show(Screen.Home)
                }
            }
            AutoDecision.ShowList ->
                if (mode == AutoMode.DIRECT) {
                    store.lastMacName?.let { homeNote = "Couldn't find $it on this Wi-Fi." }
                    show(Screen.Home)
                }
            AutoDecision.KeepWaiting -> Unit
        }
    }

    private fun disarmAutoConnect() = auto.disarm()

    private fun connect(
        mac: MacService,
        allowPairing: Boolean,
        auto: Boolean,
    ) {
        dropConnection()
        target = mac
        macAccess = true
        val onPad = screen is Screen.Pad
        show(if (onPad) Screen.Pad(mac.serviceName, live = false) else Screen.Connecting(mac.serviceName))
        job =
            scope.launch {
                var first = true
                var failingSince = 0L // when reconnecting on the pad started failing
                while (isActive) {
                    val m = discovery.find(mac.id) ?: mac
                    val s = Session(m.host, m.port, store, deviceName, allowPairing && first, listener)
                    first = false
                    session = s
                    val end = withContext(Dispatchers.IO) { s.run() }
                    session = null
                    sender = null
                    if (!isActive) break
                    if (s.reachedReady) failingSince = 0L
                    val padName = (screen as? Screen.Pad)?.macName
                    when (end) {
                        SessionEnd.Closed -> show(Screen.Home)
                        SessionEnd.NeedsPairing -> backToList("${mac.serviceName} needs to pair again. Tap it to pair.")
                        SessionEnd.Denied -> show(Screen.Error(Problem.denied(mac.serviceName), mac))
                        is SessionEnd.Failed -> {
                            val problem = Problem.of(end.kind, mac.serviceName)
                            when {
                                padName != null -> {
                                    // Keep retrying on the pad; after a while, say why and what to check.
                                    val now = System.currentTimeMillis()
                                    if (failingSince == 0L) failingSince = now
                                    val trouble = problem.takeIf { now - failingSince >= TROUBLE_AFTER_MS }
                                    show(Screen.Pad(padName, live = false, trouble = trouble))
                                    delay(RETRY_DELAY_MS)
                                    continue
                                }
                                auto -> backToList("${problem.title}. ${problem.help}")
                                else -> show(Screen.Error(problem, mac))
                            }
                        }
                    }
                    break
                }
                if (job === coroutineContext[Job]) job = null
            }
    }

    private fun backToList(note: String) {
        homeNote = note
        target = null
        show(Screen.Home)
    }

    private fun show(next: Screen) {
        screen = next
        PadState.set(appContext, next is Screen.Pad && next.live)
    }

    private fun dropConnection() {
        job?.cancel()
        job = null
        sender = null
        val s = session ?: return
        session = null
        // close() writes to the socket, which is not allowed on the main thread.
        Thread({ s.close() }, "magicpad-close").start()
    }

    private val listener =
        object : SessionListener {
            override fun onPairing(code: String) {
                scope.launch { show(Screen.Pairing(target?.serviceName ?: "your Mac", code)) }
            }

            override fun onReady(
                macName: String,
                sender: FrameSender,
            ) {
                this@Controller.sender = sender
                scope.launch {
                    target?.let { store.saveLastMac(it.id, it.serviceName) }
                    homeNote = null
                    show(Screen.Pad(macName, live = true))
                }
            }

            // Called on the network thread; Haptics.play is a lookup plus one vibrate call.
            override fun onBuzz(
                kind: HapticKind,
                strength: Double?,
            ) = haptics.play(kind, strength)

            override fun onMacAccess(allowed: Boolean) {
                scope.launch { macAccess = allowed }
            }
        }

    private companion object {
        const val RETRY_DELAY_MS = 1000L
        const val TROUBLE_AFTER_MS = 8000L
    }
}
