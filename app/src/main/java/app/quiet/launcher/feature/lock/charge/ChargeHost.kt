package app.quiet.launcher.feature.lock.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.quiet.launcher.core.model.ChargeAnim
import app.quiet.launcher.feature.player.tonePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The charging host for the live lock scene; null everywhere else (picker cards, wallpaper render). */
val LocalCharge = staticCompositionLocalOf<ChargeHost?> { null }

/**
 * Everything the charging animations share: the inputs (plug, level, charge rate, tilt, touch), the
 * layout they hang off (scene, clock, orb, cover), the four simulations, and the frame clock's
 * battery rules. Drawing reads [tick]; [shown] says which animation is on screen (OFF = nothing).
 */
@Stable
class ChargeHost internal constructor() {
    /** Bumped once per stepped frame; the layers that draw the animations read it. */
    var tick by mutableLongStateOf(0L)
        internal set
    /** Which animation is on screen now. */
    var shown by mutableStateOf(ChargeAnim.OFF)
        internal set

    internal var style = ChargeAnim.OFF
    internal var still = false

    /** Show-off Flood (two-finger hold on the lock screen): the plug-in moment without a charger. */
    var demo by mutableStateOf(false)
        internal set
    private var demoEnd = 0L
    private var demoStarted = 0L

    /** Starts the Flood show-off: water to the current battery level, drains by itself after ~20 s. */
    fun startDemo(): Boolean {
        Log.d("QuietCharge", "startDemo demo=$demo still=$still plugged=$plugged style=$style shown=$shown")
        if (demo || still || Build.VERSION.SDK_INT < 33) return false
        if (plugged && style == ChargeAnim.FLOOD) { flood.ringAt(); stir(); wake = true; return false }
        demoStarted = SystemClock.uptimeMillis()
        demoEnd = demoStarted + 20_000L
        demo = true   // the engine restarts with FLOOD and begin() runs the plug-in moment
        return true
    }

    /** Drains the show-off water early (a double tap). */
    fun endDemo() {
        if (!demo || flood.draining) return
        demoEnd = 0L
        flood.unplug(); fullUntil = SystemClock.uptimeMillis() + 2500L
        wake = true; stir()
    }

    // ---- inputs
    internal var plugged = false
    private var haveBattery = false
    internal var level = 0.5f
    private var fastTarget = 0f
    internal var fast = 0f
    internal var watts = 0f
    internal var amps = 0f
    private var rawGx = 0f
    private var rawGy = 1f
    internal var gx = 0f
    internal var gy = 1f
    internal var tilt = 0f
    private var lastWakeTilt = 0f
    internal var playing = false

    // ---- layout (scene px)
    internal var w = 0f
    internal var h = 0f
    internal var dp = 1f
    internal var ringTop = 0f
    internal var ringR = 0f
    internal var root: LayoutCoordinates? = null
    internal var clockBlock: LayoutCoordinates? = null
    internal var orbCoords: LayoutCoordinates? = null
    internal var orbX: () -> Float = { 0f }
    internal var orbBeat: () -> Float = { 0f }
    internal var coverCoords: LayoutCoordinates? = null
    internal var clockW = 0f
    internal var clockH = 0f
    internal var clockBaseline = 0f
    internal var clockCap = 0f
    internal var orbCx = -1f
    internal var orbCy = -1f
    internal val coverRect = FloatArray(4)
    internal var hasCover = false
    internal var anchorBx = 0f
    internal var anchorBy = 0f

    // ---- palette (sRGB 0..1)
    internal val pDk = FloatArray(3); internal val pAc = FloatArray(3); internal val pMd = FloatArray(3)
    internal val pLt = FloatArray(3); internal val pSheen = FloatArray(3)

    // ---- simulations
    internal val flood = FloodSim()
    internal val glass = GlassSim()
    internal val thread = ThreadSim()
    internal val clock = ClockSim()
    internal var gpu: Any? = null

    // ---- frame clock
    private var acc = 0f
    private var fullUntil = 0L
    private var lastStir = 0L
    private var wake = false
    private var staticDirty = true
    private var lastBeat = 0f
    private var beatArmed = true
    private var lastOrbX = 0f
    internal var time = 0f
    private var logAt = 0L

    // ---------------------------------------------------------------- inputs

    internal fun onBattery(i: Intent) {
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (lv >= 0) updateLevel(lv / sc.toFloat())
        // Plug wattage, where the charger reports it (hidden extras on most phones).
        val ma = i.getIntExtra("max_charging_current", 0)
        val mv = i.getIntExtra("max_charging_voltage", 0)
        if (ma > 0) watts = ma / 1e6f * (if (mv > 0) mv / 1e6f else 5f)
        val p = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        setPlugged(p, wakeUp = haveBattery)
        haveBattery = true
    }

    internal fun updateLevel(l: Float) {
        val nl = l.coerceIn(0f, 1f)
        if (abs(nl - level) > 0.001f) {
            level = nl
            flood.setLevel(nl); glass.setLevel(nl); clock.setLevel(nl)
            wake = true; staticDirty = true; stir()
        }
    }

    internal fun setPlugged(p: Boolean, wakeUp: Boolean) {
        if (p == plugged && haveBattery) return
        if (demo && haveBattery) { plugged = p; demo = false; return }   // a real charger takes over
        plugged = p
        staticDirty = true
        if (still) return
        val now = SystemClock.uptimeMillis()
        if (p) startSims(wakeUp) else {
            flood.unplug(); glass.unplug(); thread.unplug(); clock.unplug()
            fullUntil = now + 2500L
        }
        stir()
        wake = true
        updateShown()
    }

    private var pendingStart = 0   // 0 none, 1 settled, 2 with the plug-in animation

    private fun startSims(wakeUp: Boolean) {
        measure()
        // The scene has not been laid out yet: start on the first frame that knows its size.
        if (w <= 0f || h <= 0f) { pendingStart = if (wakeUp) 2 else 1; wake = true; return }
        pendingStart = 0
        when (style) {
            ChargeAnim.FLOOD -> { layoutFlood(); flood.plugIn(level, wakeUp) }
            ChargeAnim.GLASS -> glass.plugIn(level, wakeUp)
            ChargeAnim.THREAD -> { layoutThread(); thread.plugIn(wakeUp) }
            ChargeAnim.CLOCK -> clock.plugIn(level, wakeUp)
            else -> {}
        }
        fullUntil = SystemClock.uptimeMillis() + if (wakeUp) 3000L else 1200L
    }

    /** Charge rate from the instantaneous current (µA on most phones, mA on some). */
    internal fun onCurrent(raw: Int) {
        if (raw == Int.MIN_VALUE || raw == 0) return
        val a = abs(raw).let { if (it < 20_000) it / 1000f else it / 1_000_000f }
        amps = a
        val isFast = plugged && (a > 1.6f || watts >= 15f)
        fastTarget = if (isFast) 1f else 0f
    }

    internal fun onGravity(x: Float, y: Float) {
        // Device x right / y up, reported against gravity; screen x right / y down.
        rawGx = -x; rawGy = y
        val plane = sqrt(rawGx * rawGx + rawGy * rawGy)
        val ts = if (plane > 2.5f && rawGy > 0.8f) (rawGx / rawGy).coerceIn(-0.7f, 0.7f) else 0f
        if (abs(ts - lastWakeTilt) > 0.035f) { lastWakeTilt = ts; wake = true }
    }

    internal fun touch(x: Float, y: Float, dx: Float, dy: Float) {
        if (shown != ChargeAnim.FLOOD || still) return
        flood.touch(x, y, dx, dy)
        wake = true; stir()
    }

    private fun stir() { lastStir = SystemClock.uptimeMillis() }

    internal fun setPalette(tones: List<Color>, lightVibrant: Int?) {
        fun put(a: FloatArray, c: Color) { a[0] = c.red; a[1] = c.green; a[2] = c.blue }
        put(pDk, tones[0]); put(pAc, tones[1]); put(pMd, tones[2]); put(pLt, tones[3])
        val sheen = app.quiet.launcher.feature.lock.PaintingTint.sheen(lightVibrant ?: 0xFFF6D29A.toInt())
        pSheen[0] = ((sheen shr 16) and 0xFF) / 255f; pSheen[1] = ((sheen shr 8) and 0xFF) / 255f; pSheen[2] = (sheen and 0xFF) / 255f
    }

    // ---------------------------------------------------------------- lifecycle

    internal fun begin(s: ChargeAnim, still: Boolean) {
        style = s; this.still = still
        flood.reset(); glass.reset(); thread.reset(); clock.reset()
        acc = 0f; wake = true; staticDirty = true
        fast = fastTarget
        Log.d("QuietCharge", "begin s=$s still=$still demo=$demo plugged=$plugged w=$w")
        if (demo) {
            if (s == ChargeAnim.FLOOD && !still) { startSims(true); stir(); fullUntil = SystemClock.uptimeMillis() + 3000L }
            else demo = false
        }
        // The sticky battery broadcast may have landed before this ran: pick up its state settled.
        else if (plugged && !still) { startSims(false); stir() }
        updateShown()
    }

    internal fun stop() {
        flood.reset(); glass.reset(); thread.reset(); clock.reset()
        shown = ChargeAnim.OFF
    }

    private fun simVisible(): Boolean = when (style) {
        ChargeAnim.FLOOD -> flood.active
        ChargeAnim.GLASS -> glass.visible
        ChargeAnim.THREAD -> thread.visible
        ChargeAnim.CLOCK -> clock.visible
        else -> false
    }

    private fun updateShown() {
        val v = if (still) plugged && style != ChargeAnim.OFF else simVisible()
        val s = if (v) style else ChargeAnim.OFF
        if (shown != s) shown = s
    }

    // ---------------------------------------------------------------- frame clock

    internal fun wantsFrames(): Boolean {
        if (pendingStart != 0) return true
        if (shown == ChargeAnim.OFF && !simVisible()) return false
        val now = SystemClock.uptimeMillis()
        if (now < fullUntil || wake) return true
        if (energy() > restEnergy()) return true
        if (orbCoords != null && abs(orbX() - lastOrbX) > 0.5f) return true
        return false
    }

    internal fun fullRate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < fullUntil) return true
        return when (style) {
            ChargeAnim.FLOOD -> flood.jetT >= 0f || flood.draining || flood.dropsAlive > 0
            ChargeAnim.GLASS -> glass.state == 2 || glass.state == 3
            ChargeAnim.THREAD -> thread.state == 1 || thread.state == 3
            ChargeAnim.CLOCK -> clock.state == 2
            else -> false
        }
    }

    private fun restEnergy(): Float = when (style) {
        ChargeAnim.FLOOD -> 3f
        ChargeAnim.GLASS -> 0.6f
        ChargeAnim.THREAD -> 0.4f
        ChargeAnim.CLOCK -> 0.5f
        else -> 0f
    }

    private fun energy(): Float = when (style) {
        ChargeAnim.FLOOD -> flood.energy()
        ChargeAnim.GLASS -> glass.energy()
        ChargeAnim.THREAD -> thread.energy()
        ChargeAnim.CLOCK -> clock.energy()
        else -> 0f
    }

    /** Has the frame to hold changed while resting (reduced motion / battery saver)? */
    internal fun takeStaticDirty(): Boolean { val d = staticDirty; staticDirty = false; return d }

    internal fun measure() {
        val r = root?.takeIf { it.isAttached } ?: return
        w = r.size.width.toFloat(); h = r.size.height.toFloat()
        orbCx = -1f
        orbCoords?.takeIf { it.isAttached }?.let { o ->
            val p = r.localPositionOf(o, Offset(o.size.width / 2f + orbX(), o.size.height * 0.46f))
            orbCx = p.x; orbCy = p.y
        }
        hasCover = false
        if (playing || orbCx >= 0f) coverCoords?.takeIf { it.isAttached }?.let { c ->
            val b = r.localBoundingBoxOf(c)
            coverRect[0] = b.left; coverRect[1] = b.top; coverRect[2] = b.right; coverRect[3] = b.bottom
            hasCover = true
        }
        anchorBx = w / 2f; anchorBy = h * 0.3f
        clockBlock?.takeIf { it.isAttached }?.let { c ->
            val b = r.localBoundingBoxOf(c)
            anchorBx = b.center.x; anchorBy = b.bottom + 18f * dp
        }
    }

    private fun layoutFlood() { measure(); if (w > 0f) flood.resize(w, h, dp) }

    private fun layoutThread() {
        measure()
        thread.dp = dp; thread.w = w; thread.h = h
        val bx: Float; val by: Float
        if (orbCx >= 0f) { bx = orbCx; by = orbCy + 30f * dp } else { bx = anchorBx; by = anchorBy }
        thread.anchors(w / 2f, h + 3f * dp, bx, by)
    }

    /** One displayed frame: fixed 120 Hz substeps, dt clamped by the caller. */
    internal fun frame(dt: Float) {
        measure()
        if (w <= 0f) return
        if (pendingStart != 0 && plugged) startSims(pendingStart == 2)
        val now = SystemClock.uptimeMillis()
        val calm = now - lastStir > 12_000L
        fast += (fastTarget - fast) * min(1f, dt / 4f)
        // Beat (from the orb while a song plays): a rising edge fires once per beat.
        val beatNow = if (playing) orbBeat() else 0f
        var beat = 0f
        if (beatArmed && beatNow > 0.55f && beatNow > lastBeat) { beat = 1f; beatArmed = false }
        if (beatNow < 0.2f) beatArmed = true
        lastBeat = beatNow
        lastOrbX = orbX()
        wake = false

        when (style) {
            ChargeAnim.FLOOD -> {
                flood.resize(w, h, dp)
                if (beat > 0f && orbCx >= 0f) flood.ring(orbCx, orbCy, 110f)
            }
            ChargeAnim.GLASS -> glass.orbMode = orbCx >= 0f
            ChargeAnim.THREAD -> {
                layoutThread()
                if (beat > 0f) thread.firePulse()
            }
            else -> {}
        }
        acc += dt
        var steps = 0
        while (acc >= SUB_DT && steps < 8) {
            acc -= SUB_DT; steps++
            time += SUB_DT
            // Tilt eases in like a heavy liquid would feel it (the slosh itself is the springs').
            gx += (rawGx - gx) * 0.08f; gy += (rawGy - gy) * 0.08f
            val plane = sqrt(gx * gx + gy * gy)
            val ts = if (plane > 2.5f && gy > 0.8f) (gx / gy).coerceIn(-0.7f, 0.7f) else 0f
            tilt = ts
            val b = if (steps == 1) beat else 0f
            when (style) {
                ChargeAnim.FLOOD -> flood.step(SUB_DT, ts, fast, (plugged || demo) && !calm, !calm)
                ChargeAnim.GLASS -> glass.step(SUB_DT, ts, fast, b, level)
                ChargeAnim.THREAD -> {
                    val n = if (plane > 2.5f) 1f / plane else 0f
                    thread.step(SUB_DT, gx * n, if (plane > 2.5f) gy * n else 1f, fast, level, !calm && !playing)
                }
                ChargeAnim.CLOCK -> clock.step(SUB_DT, ts, clockW, b, level)
                else -> {}
            }
        }
        if (steps == 8) acc = 0f
        if (demo) {
            if (demoEnd in 1L until now && !flood.draining) { demoEnd = 0L; flood.unplug(); fullUntil = now + 2500L }
            if (!flood.active && now - demoStarted > 800L) demo = false
        }
        updateShown()
        if (now > logAt) {
            logAt = now + 2000L
            Log.d("QuietCharge", "style=$style shown=$shown plugged=$plugged level=$level fast=${"%.2f".format(fast)} A=${"%.2f".format(amps)} W=${"%.1f".format(watts)} tilt=${"%.2f".format(tilt)} energy=${"%.2f".format(energy())} full=${fullRate()}")
        }
    }

    /** Reduced motion / battery saver: one settled frame of the same idea. */
    internal fun drawStatic() {
        measure()
        if (w <= 0f) { staticDirty = true; return }
        if (plugged) when (style) {
            ChargeAnim.FLOOD -> { flood.resize(w, h, dp); flood.settle(level) }
            ChargeAnim.GLASS -> { glass.orbMode = orbCx >= 0f; glass.settle(level) }
            ChargeAnim.THREAD -> { layoutThread(); thread.settle() }
            ChargeAnim.CLOCK -> clock.settle(level)
            else -> {}
        }
        updateShown()
        tick++
    }

    // ---------------------------------------------------------------- glass drop placement

    /**
     * Glass drop centre and radius (px). It takes the fingerprint ring's place, centred on the
     * in-display sensor, so resting a finger on the drop unlocks; the ring hides while it shows.
     */
    internal fun dropCx() = w / 2f
    internal fun dropCy(): Float = if (ringTop > 0f) ringTop + ringR else h * 0.9f
    internal fun dropR(): Float {
        val want = maxOf(ringR * 1.55f, w * 0.115f)
        val room = h - dropCy() - 14f * dp
        return minOf(want, room).coerceAtLeast(ringR)
    }
}

/**
 * Runs the charging animation for the lock screen: battery and gravity listeners only while the
 * lock screen is in front (and the gravity sensor only while something animates), the fixed-step
 * simulation, and the battery rules (full rate ~3 s after plugging in; then 30 fps only while the
 * water still moves; otherwise the frame holds until the level, tilt or a touch changes it).
 */
@Composable
fun ChargeEngine(host: ChargeHost, style: ChargeAnim, reduced: Boolean, playing: Boolean, palette: IntArray?, ringTop: Float, ringR: Float = 0f) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val powerSave = remember(resumed) { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }
    val supported = Build.VERSION.SDK_INT >= 33
    val eff = if (host.demo) ChargeAnim.FLOOD else style
    val enabled = supported && eff != ChargeAnim.OFF && resumed
    val still = reduced || powerSave
    host.playing = playing
    host.dp = density
    host.ringTop = ringTop
    host.ringR = ringR
    val tones = remember(palette) { tonePalette(palette) }
    SideEffect { host.setPalette(tones, palette?.getOrNull(3)) }

    // Plug state and level: a sticky BATTERY_CHANGED receiver (plus connect/disconnect), only while visible.
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> host.onBattery(i)
                    Intent.ACTION_POWER_CONNECTED -> host.setPlugged(true, wakeUp = true)
                    Intent.ACTION_POWER_DISCONNECTED -> host.setPlugged(false, wakeUp = true)
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(ctx, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }

    // Charge rate: the current's magnitude, polled slowly while plugged and visible.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return@LaunchedEffect
        while (true) {
            if (host.plugged) host.onCurrent(runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0))
            delay(2000)
        }
    }

    // Tilt: gravity (or the accelerometer), only while an animation is on screen.
    val sensorOn = enabled && !still && host.shown != ChargeAnim.OFF
    DisposableEffect(sensorOn) {
        if (!sensorOn) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { host.onGravity(e.values[0], e.values[1]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l) }
    }

    // host.demo is a key too: with Flood already the style, starting the show-off changes nothing else.
    LaunchedEffect(enabled, eff, still, host.demo) {
        if (!enabled) { host.stop(); return@LaunchedEffect }
        host.begin(eff, still)
        if (still) {
            while (true) {
                if (host.takeStaticDirty()) withFrameNanos { host.drawStatic() }
                delay(250)
            }
        }
        var last = 0L
        while (true) {
            if (!host.wantsFrames()) { last = 0L; delay(150); continue }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                host.frame(dt)
                host.tick = now
            }
            if (!host.fullRate()) delay(18)
        }
    }
}

/** The painting's swatches for the charging colours when no song is playing (cached per painting). */
@Composable
fun rememberPaintingSwatches(path: String?): IntArray? {
    val st = produceState(app.quiet.launcher.feature.lock.PaintingPaletteCache.get(path), path) {
        if (path == null) { value = null; return@produceState }
        value = app.quiet.launcher.feature.lock.PaintingPaletteCache.get(path) ?: withContext(Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, o)
                var s = 1
                while (max(o.outWidth, o.outHeight) / (s * 2) >= 256) s *= 2
                val b = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
                b?.let { app.quiet.launcher.feature.lock.paintingPalette(it) }
            }.getOrNull()
        }?.also { app.quiet.launcher.feature.lock.PaintingPaletteCache.put(path, it) }
    }
    return st.value
}

/** The orb's own beat (the same curve the orb breathes with), for lock-scene charging effects. */
internal fun orbBeatCurve(time: Float): Float = max(0f, sin(time * 2f * PI.toFloat() * 1.55f)).pow(6)
