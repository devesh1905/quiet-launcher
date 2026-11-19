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

    // ---- Water and glass (Quiet Underwater). Rate-limited per feel and overall, and silent when
    // the system "touch feedback" setting is off; nothing in classic Quiet plays these.
    /** A ripple starting under the finger: the softest low tick. */
    Drip,
    /** Touching or tapping glass: a light, bright clink (click with a short ring after it). */
    Clink,
    /** Glass drops merging, a drop landing in the water: a heavier, rounder thud. */
    Plunk,
    /** Dragging glass across the waterline: a soft rising texture. */
    Rise,
    /** Glass splitting apart, a bubble popping. */
    Pop,
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
        if (feel.ordinal >= Feel.Drip.ordinal) { playWater(v, feel); return }
        if (feel == Feel.Tick) {
            // A fast scrub would otherwise blur into a buzz.
            val now = SystemClock.uptimeMillis()
            if (now - lastTickMs < 28) return
            lastTickMs = now
        }
        runCatching { v.vibrate(effect(feel)) }
    }

    fun effect(feel: Feel): VibrationEffect = if (feel.ordinal >= Feel.Drip.ordinal) waterEffect(feel) else when (feel) {
        Feel.Tick -> predefined(EFFECT_TICK) ?: pulse(12, 120)
        Feel.Click -> predefined(EFFECT_CLICK) ?: pulse(20, 200)
        Feel.Heavy -> predefined(EFFECT_HEAVY) ?: pulse(32, 255)
        Feel.Double -> predefined(EFFECT_DOUBLE) ?: wave(longArrayOf(0, 22, 80, 22), intArrayOf(0, 220, 0, 220))
        Feel.Thud -> predefined(EFFECT_THUD) ?: wave(longArrayOf(0, 60), intArrayOf(0, 110))
        Feel.Heart -> wave(longArrayOf(0, 26, 110, 34), intArrayOf(0, 255, 0, 150))
        Feel.Swell -> swell()
        else -> pulse(12, 120)
    }

    // ---------------------------------------------------------------- water and glass

    private val appCtx = ctx.applicationContext
    private val lastWaterMs = LongArray(5)
    private var lastAnyWaterMs = 0L
    private var systemOnCheckedMs = -10_000L
    private var systemOn = true

    /** The phone's own "touch feedback" switch (re-read at most every 2 s). */
    private fun systemHapticsOn(now: Long): Boolean {
        if (now - systemOnCheckedMs > 2000L) {
            systemOnCheckedMs = now
            systemOn = runCatching {
                android.provider.Settings.System.getInt(appCtx.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            }.getOrDefault(true)
        }
        return systemOn
    }

    private fun playWater(v: Vibrator, feel: Feel) {
        val now = SystemClock.uptimeMillis()
        if (!systemHapticsOn(now)) return
        val slot = feel.ordinal - Feel.Drip.ordinal
        // Per feel: a drag over water must never turn into a buzz.
        val gap = when (feel) { Feel.Drip -> 110L; Feel.Clink -> 60L; Feel.Plunk -> 140L; Feel.Rise -> 420L; else -> 80L }
        if (now - lastWaterMs[slot] < gap) return
        // Overall: two water feels never stack inside 30 ms (a heavier one may still replace a drip).
        if (now - lastAnyWaterMs < 30L && feel == Feel.Drip) return
        lastWaterMs[slot] = now; lastAnyWaterMs = now
        runCatching { v.vibrate(waterEffect(feel)) }
    }

    /**
     * A [Feel.Clink] whose strength follows [intensity] (0..1): glass knocking on glass, light for a
     * graze, full for a hard hit. Same gates and rate limit as the water feels (underwater only).
     */
    fun clink(intensity: Float) {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        val now = SystemClock.uptimeMillis()
        if (!systemHapticsOn(now)) return
        val slot = Feel.Clink.ordinal - Feel.Drip.ordinal
        if (now - lastWaterMs[slot] < 60L) return
        lastWaterMs[slot] = now; lastAnyWaterMs = now
        val k = intensity.coerceIn(0f, 1f)
        val e = compose(Triple(PRIM_CLICK, 0.18f + 0.62f * k, 0), Triple(PRIM_TICK, 0.1f + 0.3f * k, 30))
            ?: if (k < 0.45f) (predefined(EFFECT_TICK) ?: pulse(8, 90)) else (predefined(EFFECT_CLICK) ?: pulse(14, 170))
        runCatching { v.vibrate(e) }
    }

    // Composition primitives this motor really plays (checked once, API 30+).
    private val prims: Set<Int> by lazy {
        val v = vib ?: return@lazy emptySet()
        if (Build.VERSION.SDK_INT < 31) return@lazy emptySet()
        val ids = intArrayOf(PRIM_CLICK, PRIM_TICK, PRIM_LOW_TICK, PRIM_THUD, PRIM_SLOW_RISE, PRIM_QUICK_RISE, PRIM_QUICK_FALL)
        val r = runCatching { v.arePrimitivesSupported(*ids) }.getOrNull() ?: return@lazy emptySet()
        ids.filterIndexed { i, _ -> r[i] }.toSet()
    }

    /** A composition of (primitive, scale, delay ms) triples, or null if any primitive is missing. */
    private fun compose(vararg p: Triple<Int, Float, Int>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 31 || p.any { it.first !in prims }) return null
        return runCatching {
            val c = VibrationEffect.startComposition()
            p.forEach { c.addPrimitive(it.first, it.second, it.third) }
            c.compose()
        }.getOrNull()
    }

    fun waterEffect(feel: Feel): VibrationEffect {
        return when (feel) {
            Feel.Drip -> compose(Triple(PRIM_LOW_TICK, 0.55f, 0))
                ?: compose(Triple(PRIM_TICK, 0.35f, 0)) ?: predefined(EFFECT_TICK) ?: pulse(8, 60)
            Feel.Clink -> compose(Triple(PRIM_CLICK, 0.55f, 0), Triple(PRIM_TICK, 0.32f, 34))
                ?: predefined(EFFECT_CLICK) ?: pulse(14, 170)
            Feel.Plunk -> compose(Triple(PRIM_THUD, 0.85f, 0), Triple(PRIM_LOW_TICK, 0.35f, 70))
                ?: predefined(EFFECT_THUD) ?: predefined(EFFECT_HEAVY) ?: wave(longArrayOf(0, 40), intArrayOf(0, 200))
            Feel.Rise -> compose(Triple(PRIM_SLOW_RISE, 0.32f, 0))
                ?: compose(Triple(PRIM_QUICK_RISE, 0.25f, 0)) ?: rise()
            Feel.Pop -> compose(Triple(PRIM_CLICK, 0.9f, 0), Triple(PRIM_QUICK_FALL, 0.45f, 12))
                ?: predefined(EFFECT_POP) ?: pulse(16, 230)
            else -> effect(feel)
        }
    }

    /** Fallback texture: a 240 ms soft ramp up in 20 ms steps. */
    private fun rise(): VibrationEffect {
        if (!amp) return VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        val n = 12
        return VibrationEffect.createWaveform(LongArray(n) { 20 }, IntArray(n) { i -> 18 + (i * 7) }, -1)
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
        private const val PRIM_CLICK = 1        // VibrationEffect.Composition.PRIMITIVE_*
        private const val PRIM_SLOW_RISE = 5
        private const val PRIM_QUICK_RISE = 4
        private const val PRIM_QUICK_FALL = 6
        private const val PRIM_TICK = 7
        private const val PRIM_THUD = 2
        private const val PRIM_LOW_TICK = 8

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
