package app.quiet.launcher.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp

/**
 * How fast the phone is charging, from what the battery actually takes in (current x battery
 * voltage), not from the plug type: a 90 W charger on a hot phone or near full can be trickling.
 *
 * - [amps] reads `BATTERY_PROPERTY_CURRENT_NOW` (µA on most phones, mA on some).
 * - [plugWatts] reads the charger's advertised max (`max_charging_current` x `max_charging_voltage`,
 *   hidden extras; often a 5 V / 1.2 A placeholder, so only a hint before the first measurement).
 * - [tierOf] maps watts to a [Tier] with no memory; [Meter] smooths samples and adds hysteresis and
 *   a dwell so the label never flickers between tiers. [rememberChargeTier] runs a Meter in Compose.
 *
 * Thresholds: under 7.5 W slow, under 18 W normal, under 30 W fast, 30 W and up rapid.
 */
object ChargeRate {
    enum class Tier(val label: String) {
        /** Not plugged in. */
        NONE(""),
        SLOW("Slow charging"),
        NORMAL("Charging"),
        FAST("Fast charging"),
        RAPID("Charging rapidly"),
        /** Plugged in and full. */
        FULL("Charged");

        val charging: Boolean get() = this == SLOW || this == NORMAL || this == FAST || this == RAPID
    }

    /** Lower watt bound of SLOW, NORMAL, FAST, RAPID. */
    private val BOUNDS = floatArrayOf(0f, 7.5f, 18f, 30f)
    private val TIERS = arrayOf(Tier.SLOW, Tier.NORMAL, Tier.FAST, Tier.RAPID)

    /** Typical Li-ion cell voltage when the battery doesn't report one. */
    const val DEFAULT_VOLTS = 3.9f

    /** Instantaneous current magnitude in amps from CURRENT_NOW's raw value; NaN if unknown. */
    fun amps(raw: Int): Float {
        if (raw == Int.MIN_VALUE || raw == 0) return Float.NaN
        val a = abs(raw)
        return if (a < 20_000) a / 1000f else a / 1_000_000f
    }

    /** Reads CURRENT_NOW and returns amps (NaN if the phone doesn't report it). */
    fun readAmps(ctx: Context): Float {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return Float.NaN
        return amps(runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0))
    }

    /** The charger's advertised max watts from a BATTERY_CHANGED intent; 0 if it doesn't say. */
    fun plugWatts(i: Intent): Float {
        val ua = i.getIntExtra("max_charging_current", 0)
        val uv = i.getIntExtra("max_charging_voltage", 0)
        return if (ua > 0) ua / 1e6f * (if (uv > 0) uv / 1e6f else 5f) else 0f
    }

    /** Battery voltage (V) from a BATTERY_CHANGED intent, or [DEFAULT_VOLTS]. */
    fun batteryVolts(i: Intent): Float {
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return if (mv in 2500..20000) mv / 1000f else DEFAULT_VOLTS
    }

    /** Watts flowing into the battery. */
    fun watts(amps: Float, volts: Float = DEFAULT_VOLTS): Float = if (amps.isNaN()) Float.NaN else amps * volts

    /** The tier for [watts] with no smoothing or hysteresis. */
    fun tierOf(watts: Float): Tier {
        if (watts.isNaN()) return Tier.NORMAL
        for (i in TIERS.indices.reversed()) if (watts >= BOUNDS[i]) return TIERS[i]
        return Tier.SLOW
    }

    /**
     * Smoothed tier from a stream of samples. Exponential smoothing (time constant [tauS]), then
     * hysteresis (to go up a tier the smoothed watts must clear the bound by [band], to go down they
     * must fall [band] below it) and a dwell (the new tier must hold for [dwellMs]). Near full the
     * charger tapers on purpose, so from [taperLevel] up it never reports SLOW.
     */
    class Meter(
        private val tauS: Float = 6f,
        private val band: Float = 0.12f,
        private val dwellMs: Long = 5000L,
        private val taperLevel: Float = 0.9f,
    ) {
        var smoothed = Float.NaN
            private set
        var tier = Tier.NORMAL
            private set
        private var lastAt = 0L
        private var candidate = Tier.NORMAL
        private var candidateSince = 0L
        private var started = false

        fun reset() { smoothed = Float.NaN; lastAt = 0L; started = false; tier = Tier.NORMAL; candidate = Tier.NORMAL }

        /** Feeds one sample (watts into the battery; NaN = unknown) at [nowMs]; returns the tier. */
        fun update(watts: Float, level: Float, nowMs: Long = SystemClock.uptimeMillis()): Tier {
            if (!watts.isNaN() && watts >= 0f) {
                smoothed = if (smoothed.isNaN()) watts else {
                    val dt = ((nowMs - lastAt).coerceIn(0L, 60_000L)) / 1000f
                    val k = 1f - exp(-dt / tauS)
                    smoothed + (watts - smoothed) * k
                }
                lastAt = nowMs
            }
            val w = smoothed
            var want = if (w.isNaN()) tier else withBand(w)
            if (want == Tier.SLOW && level >= taperLevel) want = Tier.NORMAL
            if (!started) {
                // The first real reading sets the tier at once (no flicker from a default).
                if (!w.isNaN()) { tier = want; started = true }
                candidate = tier; candidateSince = nowMs
                return tier
            }
            if (want == tier) { candidate = tier; candidateSince = nowMs; return tier }
            if (want != candidate) { candidate = want; candidateSince = nowMs }
            if (nowMs - candidateSince >= dwellMs) tier = candidate
            return tier
        }

        private fun withBand(w: Float): Tier {
            val cur = TIERS.indexOf(tier).let { if (it < 0) TIERS.indexOf(tierOf(w)) else it }
            var i = cur
            // Up while the next bound is cleared with margin; down while below this bound by margin.
            while (i < TIERS.size - 1 && w >= BOUNDS[i + 1] * (1f + band)) i++
            while (i > 0 && w < BOUNDS[i] * (1f - band)) i--
            return TIERS[i]
        }
    }
}

/**
 * The charge tier, live: [ChargeRate.Tier.NONE] while [plugged] is false; while plugged, CURRENT_NOW
 * is sampled (every second for the first few, then every 2 s) through a [ChargeRate.Meter], with the
 * battery voltage, status (FULL -> Charged) and the charger's advertised watts (a first guess) from
 * BATTERY_CHANGED. Nothing runs while unplugged or out of the composition.
 */
@Composable
fun rememberChargeTier(plugged: Boolean, level: Float): State<ChargeRate.Tier> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(if (plugged) ChargeRate.Tier.NORMAL else ChargeRate.Tier.NONE) }
    val meter = remember { ChargeRate.Meter() }
    val levelNow = rememberUpdatedState(level)
    val volts = remember { floatArrayOf(ChargeRate.DEFAULT_VOLTS, 0f, 0f) }   // volts, plug watts, full (1/0)
    DisposableEffect(plugged) {
        if (!plugged) {
            meter.reset(); state.value = ChargeRate.Tier.NONE
            return@DisposableEffect onDispose { }
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                volts[0] = ChargeRate.batteryVolts(i)
                volts[1] = ChargeRate.plugWatts(i)
                volts[2] = if (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL) 1f else 0f
                if (volts[2] > 0f) state.value = ChargeRate.Tier.FULL
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { rx.onReceive(ctx, it) }
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    LaunchedEffect(plugged) {
        if (!plugged) return@LaunchedEffect
        var n = 0
        while (true) {
            val level = levelNow.value
            val full = volts[2] > 0f || level >= 0.999f
            if (full) state.value = ChargeRate.Tier.FULL
            else {
                var w = ChargeRate.watts(ChargeRate.readAmps(ctx), volts[0])
                // No measurement yet (or none on this phone): the charger's advertised watts, if any.
                if (w.isNaN() && volts[1] > 0f) w = volts[1]
                state.value = meter.update(w, level)
            }
            delay(if (n++ < 4) 1000L else 2000L)
        }
    }
    return state
}
