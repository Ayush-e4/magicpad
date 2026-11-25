package app.magicpad

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * Plays the Mac's `buzz` messages. Every (kind, strength level) effect is built
 * once up front, so [play] is a lookup plus one vibrate call, safe from any thread.
 * Best available: composition primitives (API 30+), then one-shots scaled by
 * amplitude (so strength works), then predefined effects (API 29).
 */
class Haptics(
    context: Context,
) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") // VibratorManager needs API 31.
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private val supported: Set<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) supportedPrimitives() else emptySet()

    /** effects[kind.ordinal][level]; null = silent (level 0). */
    private val effects: Array<Array<VibrationEffect?>> =
        Array(HapticKind.entries.size) { k ->
            Array(HapticKind.LEVELS + 1) { level ->
                if (level == 0) null else build(HapticKind.entries[k], level.toFloat() / HapticKind.LEVELS)
            }
        }

    init {
        logCapabilities()
    }

    fun play(
        kind: HapticKind,
        strength: Double?,
    ) {
        val effect = effects[kind.ordinal][HapticKind.level(strength)] ?: return
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }

    private fun build(
        kind: HapticKind,
        s: Float,
    ): VibrationEffect =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) composed(kind, s) else null)
            ?: when {
                // Scaled one-shots honour the strength setting; predefined effects can't.
                vibrator.hasAmplitudeControl() -> oneShot(kind, s)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> predefined(kind)
                else -> oneShot(kind, s)
            }

    /** A composition, or null if this phone lacks the primitives for [kind]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun composed(
        kind: HapticKind,
        s: Float,
    ): VibrationEffect? {
        val c = VibrationEffect.startComposition()
        when (kind) {
            HapticKind.CLICK -> c.add(Composition.PRIMITIVE_CLICK, s) ?: return null
            HapticKind.HEAVY ->
                c.add(thud(), s) ?: c.add(Composition.PRIMITIVE_CLICK, minOf(1f, s * 1.4f)) ?: return null
            HapticKind.DRAG -> c.add(Composition.PRIMITIVE_QUICK_RISE, s * 0.7f) ?: c.add(lowTick(), s * 0.7f) ?: return null
            HapticKind.CONFIRM -> {
                c.add(Composition.PRIMITIVE_CLICK, s) ?: return null
                c.add(Composition.PRIMITIVE_CLICK, s * 0.6f, CONFIRM_GAP_MS)
            }
            HapticKind.TICK -> c.add(Composition.PRIMITIVE_TICK, s * 0.5f) ?: c.add(lowTick(), s * 0.5f) ?: return null
        }
        return c.compose()
    }

    /** Adds [primitive] if supported; returns null (adding nothing) otherwise. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun Composition.add(
        primitive: Int?,
        scale: Float,
        delayMs: Int = 0,
    ): Composition? {
        if (primitive == null || primitive !in supported) return null
        return addPrimitive(primitive, scale.coerceIn(0f, 1f), delayMs)
    }

    private fun thud(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Composition.PRIMITIVE_THUD else null

    private fun lowTick(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Composition.PRIMITIVE_LOW_TICK else null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun predefined(kind: HapticKind): VibrationEffect =
        VibrationEffect.createPredefined(
            when (kind) {
                HapticKind.CLICK -> VibrationEffect.EFFECT_CLICK
                HapticKind.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
                HapticKind.DRAG, HapticKind.TICK -> VibrationEffect.EFFECT_TICK
                HapticKind.CONFIRM -> VibrationEffect.EFFECT_DOUBLE_CLICK
            },
        )

    private fun oneShot(
        kind: HapticKind,
        s: Float,
    ): VibrationEffect {
        fun amp(f: Float): Int =
            if (vibrator.hasAmplitudeControl()) (255 * s * f).roundToInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        return when (kind) {
            HapticKind.CLICK -> VibrationEffect.createOneShot(14, amp(1f))
            HapticKind.HEAVY -> VibrationEffect.createOneShot(24, amp(1.4f))
            HapticKind.DRAG -> VibrationEffect.createOneShot(10, amp(0.7f))
            HapticKind.TICK -> VibrationEffect.createOneShot(6, amp(0.5f))
            HapticKind.CONFIRM ->
                VibrationEffect.createWaveform(longArrayOf(0, 14, 60, 14), intArrayOf(0, amp(1f), 0, amp(0.6f)), -1)
        }
    }

    @SuppressLint("WrongConstant") // PRIMITIVES holds only Composition constants.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun supportedPrimitives(): Set<Int> {
        val all = PRIMITIVES.values.toIntArray()
        val ok = vibrator.arePrimitivesSupported(*all)
        return all.filterIndexed { i, _ -> ok[i] }.toSet()
    }

    private fun logCapabilities() {
        if (logged) return
        logged = true
        val names = PRIMITIVES.entries.joinToString(" ") { (name, id) -> "$name=${if (id in supported) "yes" else "no"}" }
        Log.i(
            TAG,
            "haptics: api=${Build.VERSION.SDK_INT} amplitudeControl=${vibrator.hasAmplitudeControl()} " +
                "primitives: ${names.ifEmpty { "n/a" }}",
        )
    }

    private companion object {
        const val TAG = "MagicPad"
        const val CONFIRM_GAP_MS = 60

        @Volatile var logged = false

        /** Every primitive this class can use, by name, for logging and the support check. */
        val PRIMITIVES: Map<String, Int> =
            buildMap {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put("click", Composition.PRIMITIVE_CLICK)
                    put("tick", Composition.PRIMITIVE_TICK)
                    put("quick_rise", Composition.PRIMITIVE_QUICK_RISE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    put("thud", Composition.PRIMITIVE_THUD)
                    put("low_tick", Composition.PRIMITIVE_LOW_TICK)
                }
            }
    }
}
