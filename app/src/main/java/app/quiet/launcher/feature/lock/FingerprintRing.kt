package app.quiet.launcher.feature.lock

import android.annotation.SuppressLint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** In-display fingerprint sensor, in physical screen px (centre + radius), or null when unknown. */
data class SensorSpot(val cx: Float, val cy: Float, val radius: Float)

/**
 * Xiaomi publishes the optical sensor as `persist.vendor.sys.fp.fod.location.X_Y` (top-left corner)
 * and `...fod.size.width_height`. Other phones: AOSP `config_udfps_*` is not readable, so we fall
 * back to a bottom-centre spot.
 */
@SuppressLint("PrivateApi")
fun readSensorSpot(screenW: Int, screenH: Int): SensorSpot {
    fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")
    val loc = prop("persist.vendor.sys.fp.fod.location.X_Y").split(",").mapNotNull { it.trim().toFloatOrNull() }
    val size = prop("persist.vendor.sys.fp.fod.size.width_height").split(",").mapNotNull { it.trim().toFloatOrNull() }
    return if (loc.size == 2 && size.size == 2 && loc[1] < screenH) {
        SensorSpot(loc[0] + size[0] / 2f, loc[1] + size[1] / 2f, size[0] / 2f)
    } else {
        SensorSpot(screenW / 2f, screenH * 0.9f, screenW * 0.085f)
    }
}

/**
 * The fingerprint ring. At rest: a hairline circle with a drawn fingerprint that breathes very
 * slowly (it also keeps the AMOLED pixels moving). Under the finger: it settles in on a spring,
 * the ridges light up, and a ripple spreads outward. [onHold] fires once the finger has rested
 * for a beat; [success] (from the lock root) blooms the ring outward as the scene leaves.
 */
@Composable
fun FingerprintRing(diameter: Dp, success: Boolean, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    val bloom by animateFloatAsState(if (success) 1f else 0f, if (reduced) tween(0) else spring(0.9f, 220f), label = "bloom")
    val breath = if (reduced) 0.5f else rememberInfiniteTransition(label = "fp").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = QMotion.EaseInOutSine), RepeatMode.Reverse), label = "b",
    ).value

    Box(
        modifier
            .size(diameter)
            .graphicsLayer {
                val s = (1f - 0.08f * press.value) * (1f + 1.6f * bloom)
                scaleX = s; scaleY = s
                alpha = 1f - bloom
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { press.animateTo(1f, spring(0.6f, 900f)) }
                    scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(900, easing = QMotion.EaseOutQuiet)) }
                    // Give the system's own sensor a beat to unlock under us; then ask for the keyguard.
                    val holdJob = scope.launch { delay(60); onHold() }
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                    if (!holdJob.isCompleted) { holdJob.cancel(); onHold() }
                    scope.launch { press.animateTo(0f, spring(0.55f, 500f)) }
                }
            }
    ) {
        Canvas(Modifier.size(diameter)) {
            val r = size.minDimension / 2f
            val center = Offset(r, r)
            val lit = press.value.coerceIn(0f, 1f)
            // Ripple.
            if (ripple.value in 0.001f..0.999f) {
                drawCircle(c.ink.copy(alpha = 0.35f * (1f - ripple.value)), r * (1f + 0.9f * ripple.value), center, style = Stroke(1.dp.toPx()))
            }
            // Hairline ring.
            drawCircle(c.ink.copy(alpha = 0.35f + 0.25f * breath + 0.4f * lit), r - 1.dp.toPx(), center, style = Stroke(1.2.dp.toPx()))
            // Fingerprint ridges: offset concentric arcs, open at the bottom like a print.
            val ink = c.ink.copy(alpha = 0.45f + 0.15f * breath + 0.4f * lit)
            val st = 1.4.dp.toPx()
            val base = r * 0.18f
            for (i in 0 until 5) {
                val rr = base + i * r * 0.12f
                val sweep = 250f - i * 12f
                val start = 145f + i * 6f + if (i % 2 == 0) 0f else 8f
                drawArc(
                    ink, start, sweep, false,
                    topLeft = Offset(center.x - rr, center.y - rr * 1.08f),
                    size = Size(rr * 2, rr * 2.16f),
                    style = Stroke(st, cap = StrokeCap.Round),
                )
            }
            drawLine(ink, Offset(center.x, center.y - base * 0.4f), Offset(center.x, center.y + base * 0.9f), st, StrokeCap.Round)
        }
    }
}
