package app.quiet.launcher.designsystem

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * One vocabulary of touch feel for the whole app, played straight on the vibrator. Some phones
 * (HyperOS among them) drop several view haptic constants, such as the text-handle tick, so the
 * scrubbers felt nothing; the predefined effects and amplitude waveforms here are what the motor
 * really plays. Each feel falls back to a short amplitude pulse when an effect is missing.
 */
enum class Feel {
    /** Scrubbing past a letter or a step: the lightest crisp tick. */
    Tick,
    /** A tap that did something: launch, letter recognised. */
    Click,
    /** Long press, a menu opening. */
    Heavy,
    /** Something switched on or saved. */
    Double,
    /** Not understood, or refused: a dull knock. */
    Thud,
    /** A heartbeat (lub-dub), for favourites. */
    Heart,
    /** A slow rise and fall, long enough to count: the haptic clock's hour. */
    Swell,
}

class Haptics private constructor(ctx: Context) {
    private val vib: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    private val amp = vib?.hasAmplitudeControl() == true
    private var lastTickMs = 0L

    // Which predefined effects this motor plays (checked once).
    private val supported: Set<Int> by lazy {
        val v = vib ?: return@lazy emptySet()
        if (Build.VERSION.SDK_INT < 30) return@lazy setOf(EFFECT_CLICK, EFFECT_TICK, EFFECT_HEAVY, EFFECT_DOUBLE)
        val ids = intArrayOf(EFFECT_CLICK, EFFECT_DOUBLE, EFFECT_TICK, EFFECT_THUD, EFFECT_POP, EFFECT_HEAVY)
        val r = runCatching { v.areEffectsSupported(*ids) }.getOrNull() ?: return@lazy emptySet()
        ids.filterIndexed { i, _ -> r[i] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES }.toSet()
    }

    fun play(feel: Feel) {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        if (feel == Feel.Tick) {
            // A fast scrub would otherwise blur into a buzz.
            val now = SystemClock.uptimeMillis()
            if (now - lastTickMs < 28) return
            lastTickMs = now
        }
        runCatching { v.vibrate(effect(feel)) }
    }

    fun effect(feel: Feel): VibrationEffect = when (feel) {
        Feel.Tick -> predefined(EFFECT_TICK) ?: pulse(12, 120)
        Feel.Click -> predefined(EFFECT_CLICK) ?: pulse(20, 200)
        Feel.Heavy -> predefined(EFFECT_HEAVY) ?: pulse(32, 255)
        Feel.Double -> predefined(EFFECT_DOUBLE) ?: wave(longArrayOf(0, 22, 80, 22), intArrayOf(0, 220, 0, 220))
        Feel.Thud -> predefined(EFFECT_THUD) ?: wave(longArrayOf(0, 60), intArrayOf(0, 110))
        Feel.Heart -> wave(longArrayOf(0, 26, 110, 34), intArrayOf(0, 255, 0, 150))
        Feel.Swell -> swell()
    }

    @SuppressLint("WrongConstant")
    private fun predefined(id: Int): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 29 || id !in supported) return null
        return runCatching { VibrationEffect.createPredefined(id) }.getOrNull()
    }

    private fun pulse(ms: Long, a: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (amp) a else VibrationEffect.DEFAULT_AMPLITUDE)

    private fun wave(t: LongArray, a: IntArray): VibrationEffect =
        if (amp) VibrationEffect.createWaveform(t, a, -1)
        else VibrationEffect.createWaveform(t, -1)

    /** 380 ms: rises to full and falls away, in 20 ms amplitude steps. Plain pulse without amplitude control. */
    private fun swell(): VibrationEffect {
        if (!amp) return VibrationEffect.createOneShot(SWELL_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        val steps = (SWELL_MS / 20).toInt()
        val t = LongArray(steps) { 20 }
        val a = IntArray(steps) { i ->
            val x = (i + 0.5f) / steps
            // Quick rise, held peak, long fall.
            val e = if (x < 0.3f) x / 0.3f else 1f - ((x - 0.3f) / 0.7f).let { it * it }
            (40 + 215 * e.coerceIn(0f, 1f)).toInt()
        }
        return VibrationEffect.createWaveform(t, a, -1)
    }

    companion object {
        const val SWELL_MS = 380L
        // VibrationEffect.EFFECT_* (THUD and POP are not public constants but are played by many motors).
        private const val EFFECT_CLICK = 0
        private const val EFFECT_DOUBLE = 1
        private const val EFFECT_TICK = 2
        private const val EFFECT_THUD = 3
        private const val EFFECT_POP = 4
        private const val EFFECT_HEAVY = 5

        @Volatile private var instance: Haptics? = null
        fun get(ctx: Context): Haptics = instance ?: synchronized(this) {
            instance ?: Haptics(ctx.applicationContext).also { instance = it }
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val ctx = LocalContext.current
    return remember { Haptics.get(ctx) }
}
