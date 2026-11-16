package app.quiet.launcher.feature.gesture

import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The stroke being drawn, kept in growable float arrays so moving the finger allocates nothing. */
@Stable
internal class LetterState {
    var xs = FloatArray(256); var ys = FloatArray(256); var ws = FloatArray(256); var ts = LongArray(256)
    var n = 0
    var version by mutableIntStateOf(0)
    var phase by mutableStateOf(Phase.None)
    var label by mutableStateOf("")
    val t = Animatable(0f)
    var cx = 0f; var cy = 0f
    var hintY = 0f
    var cameraPkg: String? = null; var cameraKnown = false

    enum class Phase { None, Live, Hit, Miss }

    fun reset() { n = 0 }

    fun add(x: Float, y: Float, time: Long, dp: Float) {
        if (n > 0 && hypot(x - xs[n - 1], y - ys[n - 1]) < 1.5f * dp) return
        if (n == xs.size) {
            xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); ws = ws.copyOf(n * 2); ts = ts.copyOf(n * 2)
        }
        // Fast strokes thin out, like a brush (width in dp).
        ws[n] = if (n == 0) 5f else {
            val v = hypot(x - xs[n - 1], y - ys[n - 1]) / dp / max(1L, time - ts[n - 1])
            val target = (7.2f - v * 2.2f).coerceIn(2.6f, 7f)
            ws[n - 1] + (target - ws[n - 1]) * 0.35f
        }
        xs[n] = x; ys[n] = y; ts[n] = time
        n++
    }

    fun travel(): Float {
        var d = 0f
        for (i in 0 until n) d = max(d, hypot(xs[i] - xs[0], ys[i] - ys[0]))
        return d
    }
}

/**
 * Draw a letter anywhere on Home to open an app. Taps, long presses, vertical swipes and page swipes
 * still reach Home as before: the stroke is only claimed once the finger has moved and the path is not
 * a straight horizontal or vertical swipe. The recogniser runs once, on finger-up.
 *
 * [onVerticalSwipe] runs when a stroke that was claimed turns out to be a plain vertical swipe after all.
 */
@Composable
fun LetterGestureLayer(
    enabled: Boolean,
    map: Map<Char, String>,
    reduced: Boolean,
    onVerticalSwipe: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val s = remember { LetterState() }
    val scope = rememberCoroutineScope()
    val swipe by rememberUpdatedState(onVerticalSwipe)

    fun resolve(target: String): AppEntry? = GestureTargets.resolve(ctx, target, ctx.container.apps.apps.value) {
        if (!s.cameraKnown) { s.cameraPkg = GestureTargets.cameraPackage(ctx); s.cameraKnown = true }
        s.cameraPkg
    }

    fun finish(dp: Float, w: Float, h: Float) {
        val pts = List(s.n) { Pt(s.xs[it], s.ys[it]) }
        val targets = map.mapNotNull { (l, t) -> resolve(t)?.let { l to it } }.toMap()
        val res = Unistroke.recognise(pts, targets.keys)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until s.n) { x0 = min(x0, s.xs[i]); y0 = min(y0, s.ys[i]); x1 = max(x1, s.xs[i]); y1 = max(y1, s.ys[i]) }
        val app = res.letter?.let { targets[it] }
        if (app == null) {
            val dx = s.xs[s.n - 1] - s.xs[0]; val dy = s.ys[s.n - 1] - s.ys[0]
            if (abs(dy) > 60 * dp && abs(dx) < abs(dy) * 0.5f) {
                // It was a swipe with a wobble: do what the swipe does.
                s.phase = LetterState.Phase.None
                swipe(dy < 0)
                return
            }
            s.cx = (x0 + x1) / 2; s.cy = (y0 + y1) / 2
            s.hintY = (y1 + 18 * dp).coerceIn(120 * dp, h - 90 * dp)
            s.phase = LetterState.Phase.Miss
            if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            scope.launch {
                s.t.snapTo(0f)
                s.t.animateTo(1400f, tween(1400, easing = LinearEasing))
                if (s.phase == LetterState.Phase.Miss) s.phase = LetterState.Phase.None
            }
            return
        }
        s.cx = ((x0 + x1) / 2).coerceIn(110 * dp, max(110 * dp, w - 110 * dp))
        s.cy = ((y0 + y1) / 2).coerceIn(130 * dp, max(130 * dp, h - 130 * dp))
        s.label = app.label
        s.phase = LetterState.Phase.Hit
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        scope.launch {
            s.t.snapTo(0f)
            val anim = launch { s.t.animateTo(600f, tween(600, easing = LinearEasing)) }
            // A short rise of the name, then the app opens from it.
            if (!reduced) delay(200)
            val r = Rect((s.cx - 90 * dp).toInt(), (s.cy - 24 * dp).toInt(), (s.cx + 90 * dp).toInt(), (s.cy + 24 * dp).toInt())
            val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
            val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
            val screen = Rect(r).apply { offset(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1]) }
            ctx.container.apps.launch(app.key, screen, Ink.revealOptions(view, r))
            anim.join()
            if (s.phase == LetterState.Phase.Hit) s.phase = LetterState.Phase.None
        }
    }

    Box(
        modifier.pointerInput(enabled, map, reduced) {
            if (!enabled) return@pointerInput
            val dp = density
            val edge = 24 * dp
            val slop = viewConfiguration.touchSlop
            val longPress = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // System back-gesture edges stay free; nothing new starts while an app is opening.
                if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                if (s.phase == LetterState.Phase.Hit) return@awaitEachGesture
                s.reset()
                s.add(down.position.x, down.position.y, down.uptimeMillis, dp)
                var drawing = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    var pressed = 0
                    for (c in ev.changes) if (c.pressed) pressed++
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || pressed > 1) {
                        if (drawing) { s.phase = LetterState.Phase.None }
                        return@awaitEachGesture
                    }
                    if (!ch.pressed) {
                        if (drawing) { ch.consume(); finish(dp, size.width.toFloat(), size.height.toFloat()) }
                        return@awaitEachGesture
                    }
                    // A press held still belongs to the long-press menus, not to a drawing.
                    if (!drawing && ch.uptimeMillis - down.uptimeMillis > longPress && s.travel() < slop) return@awaitEachGesture
                    for (hc in ch.historical) s.add(hc.position.x, hc.position.y, hc.uptimeMillis, dp)
                    s.add(ch.position.x, ch.position.y, ch.uptimeMillis, dp)
                    if (!drawing) {
                        when (StrokeGate.classify(s.xs, s.ys, s.n, dp)) {
                            StrokeGate.Verdict.Swipe -> return@awaitEachGesture
                            StrokeGate.Verdict.Draw -> { drawing = true; s.phase = LetterState.Phase.Live }
                            StrokeGate.Verdict.Pending -> Unit
                        }
                    }
                    if (drawing) { ch.consume(); s.version++ }
                }
            }
        },
    ) {
        content()
        LetterOverlay(s, reduced)
    }
}

@Composable
private fun LetterOverlay(s: LetterState, reduced: Boolean) {
    val phase = s.phase
    if (phase == LetterState.Phase.None) return
    val c = Q.colors
    val ink = c.ink
    val muted = c.muted
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            val t = s.t.value
            alpha = when (phase) {
                LetterState.Phase.Hit -> { val q = min(1f, t / if (reduced) 200f else 520f); 0.94f * (1f - q * q) }
                LetterState.Phase.Miss -> { val q = min(1f, t / if (reduced) 600f else 1000f); 0.85f * if (q < 0.45f) 1f else 1f - (q - 0.45f) / 0.55f }
                else -> 0.94f
            }
            translationX = if (phase == LetterState.Phase.Miss && !reduced) sin(t / 1000f * 38f) * 6 * density * max(0f, 1f - t / 450f) else 0f
        },
    ) {
        s.version // redraw as points arrive
        val n = s.n
        if (n == 0) return@Canvas
        // On a match the ink gathers into the name.
        val k = if (phase == LetterState.Phase.Hit && !reduced) {
            val q = min(1f, s.t.value / 520f); 1f - (1f - q) * (1f - q) * (1f - q)
        } else 0f
        val shrink = 1f - k * 0.7f
        val color = if (phase == LetterState.Phase.Miss) muted else ink
        val dp = density
        if (n == 1) {
            drawCircle(color, s.ws[0] * dp * shrink / 2, Offset(s.xs[0] + (s.cx - s.xs[0]) * k, s.ys[0] + (s.cy - s.ys[0]) * k))
            return@Canvas
        }
        for (i in 1 until n) {
            val ax = s.xs[i - 1] + (s.cx - s.xs[i - 1]) * k; val ay = s.ys[i - 1] + (s.cy - s.ys[i - 1]) * k
            val bx = s.xs[i] + (s.cx - s.xs[i]) * k; val by = s.ys[i] + (s.cy - s.ys[i]) * k
            // The first few points ramp in, like a nib touching paper.
            val ramp = min(1f, 0.45f + i * 0.11f)
            drawLine(color, Offset(ax, ay), Offset(bx, by), strokeWidth = s.ws[i] * dp * ramp * shrink, cap = StrokeCap.Round)
        }
    }
    when (phase) {
        LetterState.Phase.Hit -> BasicText(
            s.label, style = QType.favourite(30.sp).copy(color = ink), maxLines = 1,
            modifier = Modifier.centredAt(s.cx, s.cy).graphicsLayer {
                val t = s.t.value
                alpha = if (t < 420f) min(1f, t / 160f) else max(0f, 1f - (t - 420f) / 180f)
                if (!reduced) translationY = 10 * density * (1f - min(1f, t / 220f)).let { it * it }
            },
        )
        LetterState.Phase.Miss -> BasicText(
            "No app for that letter", style = QType.secondary(14.sp).copy(color = muted), maxLines = 1,
            modifier = Modifier.centredAt(s.cx, s.hintY).graphicsLayer {
                val t = s.t.value
                alpha = when { t < 150f -> t / 150f; t < 1100f -> 1f; else -> max(0f, 1f - (t - 1100f) / 300f) }
            },
        )
        else -> Unit
    }
}

/** Places the content centred on (x, y) px of the parent. */
private fun Modifier.centredAt(x: Float, y: Float) = layout { m, cs ->
    val p = m.measure(cs.copy(minWidth = 0, minHeight = 0))
    layout(cs.maxWidth, cs.maxHeight) { p.place((x - p.width / 2).toInt(), (y - p.height / 2).toInt()) }
}
