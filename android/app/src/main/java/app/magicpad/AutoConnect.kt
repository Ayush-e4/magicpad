package app.magicpad

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the app was opened: from the launcher ([SMART]) or the tile/shortcut ([DIRECT]). */
enum class AutoMode { SMART, DIRECT }

sealed interface AutoDecision {
    data class Connect(
        val id: String,
    ) : AutoDecision

    data object ShowList : AutoDecision

    data object KeepWaiting : AutoDecision
}

/** When to skip the list on open. Pure Kotlin, so it runs in unit tests. */
object AutoConnectRule {
    /** Launcher: watch discovery this long before trusting that only the last Mac is around. */
    const val SMART_WINDOW_MS = 1500L

    /** Upper bound for waiting on the last Mac (single-Mac case, and the tile/shortcut). */
    const val MAX_WAIT_MS = 4000L

    /**
     * SMART: connect only if the setting is on and, after [SMART_WINDOW_MS], the last-used Mac
     * is the only Mac found; any other Mac means the list. If nothing has shown up yet, wait up
     * to [MAX_WAIT_MS] for the last Mac alone.
     * DIRECT: connect to the last-used Mac as soon as it is found (others don't matter); the list
     * after [MAX_WAIT_MS].
     */
    fun decide(
        found: Set<String>,
        lastId: String?,
        enabled: Boolean,
        mode: AutoMode,
        elapsedMs: Long,
    ): AutoDecision {
        if (lastId == null) return AutoDecision.ShowList
        if (mode == AutoMode.DIRECT) {
            return when {
                lastId in found -> AutoDecision.Connect(lastId)
                elapsedMs >= MAX_WAIT_MS -> AutoDecision.ShowList
                else -> AutoDecision.KeepWaiting
            }
        }
        if (!enabled) return AutoDecision.ShowList
        if (found.any { it != lastId }) return AutoDecision.ShowList // Another Mac: let the user choose.
        return when {
            elapsedMs < SMART_WINDOW_MS -> AutoDecision.KeepWaiting
            lastId in found -> AutoDecision.Connect(lastId)
            elapsedMs < MAX_WAIT_MS -> AutoDecision.KeepWaiting
            else -> AutoDecision.ShowList
        }
    }

    /** Home list order: the last-used Mac first, the rest as discovered. */
    fun lastUsedFirst(
        macs: List<MacService>,
        lastId: String?,
    ): List<MacService> = macs.sortedByDescending { it.id == lastId }
}

/**
 * Runs [AutoConnectRule] after an open: on every discovery change ([evaluate]) and on a short
 * tick, until it decides. Main thread only.
 */
class AutoConnector(
    private val scope: CoroutineScope,
    private val foundIds: () -> Set<String>,
    private val decided: (AutoDecision, AutoMode) -> Unit,
) {
    /** The armed mode, or null when not waiting. */
    var mode: AutoMode? = null
        private set
    private var lastId: String? = null
    private var enabled = true
    private var startMs = 0L
    private var ticker: Job? = null

    fun arm(
        mode: AutoMode,
        lastId: String?,
        enabled: Boolean,
    ) {
        disarm()
        this.mode = mode
        this.lastId = lastId
        this.enabled = enabled
        startMs = SystemClock.uptimeMillis()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    evaluate()
                }
            }
        evaluate()
    }

    fun evaluate() {
        val m = mode ?: return
        val d = AutoConnectRule.decide(foundIds(), lastId, enabled, m, SystemClock.uptimeMillis() - startMs)
        if (d == AutoDecision.KeepWaiting) return
        disarm()
        decided(d, m)
    }

    fun disarm() {
        mode = null
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
