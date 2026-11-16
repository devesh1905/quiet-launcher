package app.quiet.launcher.feature.gesture

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hold the Home clock to feel the time: a hairline fills under it for [HapticTime.HOLD_MS], one soft
 * confirm click, then the hour, tens and minutes as pulses (see [HapticTime]). Each pulse sends a ring
 * out from the clock. Frames are asked for only while it plays or rings are still fading.
 */
@Stable
class HapticClockState internal constructor(private val ctx: Context, private val scope: CoroutineScope) {
    internal val hold = Animatable(0f)
    internal val press = Animatable(0f)
    internal val thump = Animatable(1f)
    var playing by mutableStateOf(false)
        private set
    /** Frame time for the rings, written only while they are alive. */
    internal var frameMs by mutableLongStateOf(0L)
    internal var reduced = false

    // Up to 6 rings at once, in fixed arrays.
    internal val ringStart = LongArray(6)
    internal val ringDur = LongArray(6)
    internal val ringKind = IntArray(6)
    private var ringNext = 0
    private var job: Job? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }

    internal fun play() {
        job?.cancel()
        val now = Calendar.getInstance()
        val plan = HapticTime.build(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val vib = vibrator?.takeIf { it.hasVibrator() }
        val prims = vib != null && Build.VERSION.SDK_INT >= 30 &&
            vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)
        // Without primitives the whole reading is one waveform, so the timing stays exact.
        if (vib != null && !prims) runCatching {
            val timings = plan.timings()
            if (vib.hasAmplitudeControl()) {
                val amps = IntArray(timings.size) { i -> if (i % 2 == 0) 0 else amplitude(plan.pulses[i / 2].kind) }
                vib.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else vib.vibrate(VibrationEffect.createWaveform(timings, -1))
        }
        playing = true
        job = scope.launch {
            val start = SystemClock.uptimeMillis()
            val frames = launch { runFrames() }
            for (p in plan.pulses) {
                val wait = start + p.startMs - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait)
                if (vib != null && prims && Build.VERSION.SDK_INT >= 30) runCatching { vib.vibrate(effect(p.kind, vib)) }
                ring(p.kind)
                if (!reduced && p.kind != HapticTime.Kind.CONFIRM) launch {
                    val sc = when (p.kind) { HapticTime.Kind.LONG -> 1.022f; HapticTime.Kind.SHORT -> 1.012f; else -> 1.006f }
                    thump.snapTo(sc)
                    thump.animateTo(1f, tween(max(260, (p.kind.onMs + 200).toInt())))
                }
            }
            delay(max(0L, start + plan.totalMs - SystemClock.uptimeMillis()))
            playing = false
            hold.animateTo(0f, tween(220))
            frames.join()
        }
    }

    internal fun stop() {
        if (!playing && job == null) return
        job?.cancel(); job = null
        if (playing) runCatching { vibrator?.cancel() }
        playing = false
        // The frame loop went with the job: clear the rings so none is left frozen on screen.
        ringStart.fill(0L)
        frameMs = 0L
        scope.launch { hold.animateTo(0f, tween(160)) }
    }

    private fun ring(kind: HapticTime.Kind) {
        val i = ringNext; ringNext = (ringNext + 1) % ringStart.size
        ringStart[i] = SystemClock.uptimeMillis()
        ringDur[i] = if (reduced) max(260L, kind.onMs + 220) else kind.ringMs
        ringKind[i] = kind.ordinal
    }

    private fun ringsAlive(now: Long): Boolean {
        for (i in ringStart.indices) if (ringStart[i] > 0 && now - ringStart[i] < ringDur[i]) return true
        return false
    }

    private suspend fun runFrames() {
        while (playing || ringsAlive(SystemClock.uptimeMillis())) {
            withFrameMillis { frameMs = SystemClock.uptimeMillis() }
        }
        ringStart.fill(0L)
        frameMs = 0L
    }

    private fun amplitude(kind: HapticTime.Kind) = when (kind) {
        HapticTime.Kind.CONFIRM -> 90
        HapticTime.Kind.LONG -> 170
        HapticTime.Kind.SHORT -> 160
        HapticTime.Kind.TICK -> 255
    }

    @androidx.annotation.RequiresApi(30)
    private fun effect(kind: HapticTime.Kind, vib: Vibrator): VibrationEffect = when (kind) {
        HapticTime.Kind.TICK -> VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f).compose()
        HapticTime.Kind.CONFIRM -> VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f).compose()
        else -> VibrationEffect.createOneShot(kind.onMs, if (vib.hasAmplitudeControl()) amplitude(kind) else VibrationEffect.DEFAULT_AMPLITUDE)
    }
}

@Composable
fun rememberHapticClockState(reduced: Boolean): HapticClockState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { HapticClockState(ctx.applicationContext, scope) }
    state.reduced = reduced
    // Leaving Home or turning the screen off stops the reading.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { state.stop() }
    DisposableEffect(state) { onDispose { state.stop() } }
    return state
}

/**
 * The clock's touch handling when the haptic clock is on: tap = [onTap], hold = feel the time,
 * tap while playing = stop. Moving the finger (or a drawing taking the stroke) cancels the hold.
 */
fun Modifier.hapticClock(state: HapticClockState, ink: Color, edge: Color, onTap: () -> Unit): Modifier = this
    .drawBehind {
        val now = state.frameMs
        val y = size.height + 6 * density
        // The hairline under the clock.
        val h = state.hold.value
        if (h > 0f) {
            drawLine(edge, Offset(0f, y), Offset(size.width, y), 1 * density)
            drawLine(ink, Offset(0f, y), Offset(size.width * h, y), 1.5f * density)
        }
        if (now == 0L) return@drawBehind
        val c = Offset(size.width / 2, size.height * 0.55f)
        for (i in state.ringStart.indices) {
            val st = state.ringStart[i]
            if (st == 0L) continue
            val q = (now - st).toFloat() / state.ringDur[i]
            if (q < 0f || q >= 1f) continue
            val reach = when (state.ringKind[i]) {
                HapticTime.Kind.LONG.ordinal -> 260f
                HapticTime.Kind.SHORT.ordinal -> 200f
                HapticTime.Kind.TICK.ordinal -> 140f
                else -> 90f
            } * density
            val e = 1f - (1f - q) * (1f - q) * (1f - q)
            val r = size.height * 0.35f + reach * e
            drawCircle(ink, r, c, alpha = 0.32f * (1f - q) * (1f - q), style = Stroke(width = 1 * density))
        }
    }
    .graphicsLayer {
        val p = state.press.value
        val s = 1f - 0.01f * p
        scaleX = s * state.thump.value; scaleY = scaleX
        alpha = 1f - 0.3f * p
    }
    .pointerInput(state) {
        val slop = 12 * density
        kotlinx.coroutines.coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                if (state.playing) {
                    state.stop()
                    while (true) { val e = awaitPointerEvent(); if (e.changes.none { it.pressed }) break }
                    return@awaitEachGesture
                }
                var fired = false
                scope.launch { state.press.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1400f)) }
                val holdJob = scope.launch {
                    delay(90)
                    state.hold.snapTo(0f)
                    state.hold.animateTo(1f, tween((HapticTime.HOLD_MS - 90).toInt(), easing = LinearEasing))
                    fired = true
                    state.play()
                }
                fun release(tap: Boolean) {
                    if (!fired) { holdJob.cancel(); scope.launch { state.hold.animateTo(0f, tween(120)) } }
                    scope.launch { state.press.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                    if (tap && !fired) onTap()
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null) { release(false); break }
                    if (!ch.pressed) { ch.consume(); release(true); break }
                    if (ch.isConsumed || hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > slop) {
                        release(false); break
                    }
                }
            }
        }
    }
