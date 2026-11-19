package app.quiet.launcher.feature.apps

import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.rememberHaptics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.LocalWater
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.rememberGlass
import app.quiet.launcher.feature.underwater.rigidGlass
import app.quiet.launcher.feature.underwater.worldRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.min

/**
 * The A–Z scroller, after the reference app's rail-plus-bubble, made more physical:
 *  - a magnifying wave: letters near the finger grow (up to 2x) and arc out to the left with a
 *    Gaussian falloff, like the macOS Dock, and every letter springs back on release;
 *  - a floating bubble with the letter in Fraunces trails the finger on a stiff spring;
 *  - a haptic tick per letter; the list jumps while dragging and glides on a tap;
 *  - at rest, the section you are in stays lit.
 *
 * Lay it out over the right edge of the list (it only takes touches on its 44 dp rail).
 *
 * Quiet Underwater ([LocalWater] set): the letters sit on a thin glass rod, and a glass bead (a
 * magnifying lens) rides the wave's crest under the finger, refracting the letters it passes. It
 * lags the finger on a spring, stretches along fast scrubs and, on release, rolls on with the
 * finger's speed and sinks back into the rod. The bubble is a glass orb with the letter inside.
 * [glassOn] false (the page is off screen) gives the glass bodies back. [glassAlpha]: the page's fade.
 */
@Composable
fun AlphabetScroller(
    letters: List<Char>,
    current: Char?,
    onScrub: (Char) -> Unit,
    onTap: (Char) -> Unit,
    modifier: Modifier = Modifier,
    glassOn: Boolean = true,
    glassAlpha: () -> Float = { 1f },
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()
    val water = LocalWater.current
    val glass = if (water != null && glassOn) rememberRailGlass(water) else null
    val rodId = if (water != null && glassOn) rememberGlass(water, RodGlass) else -1

    var railTop by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var touchY by remember { mutableStateOf<Float?>(null) }   // in rail coordinates
    var active by remember { mutableStateOf<Char?>(null) }
    val presence = remember { Animatable(0f) }                // 0 idle .. 1 touching
    val bubbleY = remember { Animatable(0f) }                 // in container coordinates

    fun indexAt(y: Float) = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.lastIndex)

    Box(modifier.width(168.dp).fillMaxHeight()) {
        // Underwater: the glass rod the letters sit on (drawn by the water, under the letters).
        if (water != null && rodId >= 0) {
            val railDp = with(LocalDensity.current) { railHeight.toDp() }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 13.dp)
                    .width(18.dp)
                    .height(railDp + 12.dp)
                    .rigidGlass(water, rodId, alpha = { glassAlpha() }),
            )
        }
        // Bubble: 64 dp circle, raised fill, control edge, the letter in Fraunces.
        val shownLetter = active ?: current
        if (water != null && glass != null) {
            // Underwater: a glass orb a little further out (clear of the bead), the letter riding it
            // on a smoked disc so it reads over any painting.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-(BUBBLE_GAP.toPx()).roundToInt(), (bubbleY.value - 32.dp.toPx()).roundToInt()) }
                    .size(64.dp)
                    .onGloballyPositioned { glass.bubbleK = it }
                    .graphicsLayer {
                        water.tick
                        val p = presence.value
                        scaleX = 0.4f + 0.6f * p; scaleY = scaleX
                        alpha = p.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(1f, 0.5f)
                        val id = glass.bubble
                        if (id >= 0) { translationX = water.glassDx(id); translationY = water.glassDy(id) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) { drawCircle(Smoke, radius = size.minDimension / 2f - 3.dp.toPx()) }
                BasicText(shownLetter?.toString() ?: "", style = QType.number(34.sp).copy(color = c.ink))
            }
        } else
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-(64.dp.toPx()).roundToInt(), (bubbleY.value - 32.dp.toPx()).roundToInt()) }
                .size(64.dp)
                .graphicsLayer {
                    val p = presence.value
                    scaleX = 0.4f + 0.6f * p; scaleY = scaleX
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .background(c.raised, CircleShape)
                .border(1.dp, c.edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(shownLetter?.toString() ?: "", style = QType.number(34.sp).copy(color = c.ink))
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(44.dp)
                .onGloballyPositioned { railTop = it.positionInParent().y; railHeight = it.size.height.toFloat().coerceAtLeast(1f); glass?.railK = it }
                .pointerInput(letters, glass) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        fun update(y: Float, scrub: Boolean) {
                            touchY = y
                            glass?.fingerY = y.coerceIn(0f, railHeight)
                            val l = letters[indexAt(y)]
                            if (l != active) {
                                active = l
                                haptic.play(Feel.Tick)
                                if (scrub) onScrub(l)
                            }
                            scope.launch {
                                val target = railTop + y.coerceIn(0f, railHeight)
                                if (reduced) bubbleY.snapTo(target)
                                else bubbleY.animateTo(target, spring(dampingRatio = 0.85f, stiffness = 1500f))
                            }
                        }
                        scope.launch { bubbleY.snapTo(railTop + down.position.y) }
                        glass?.down(scope, down.position.y.coerceIn(0f, railHeight)) { presence.value }
                        update(down.position.y, scrub = true)
                        scope.launch { presence.animateTo(1f, if (reduced) spring(1f, 10_000f) else spring(0.7f, 700f)) }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            if (change.positionChange().y != 0f) moved = true
                            change.consume()
                            update(change.position.y, scrub = true)
                        }
                        // A tap glides there instead of jumping.
                        if (!moved) active?.let(onTap)
                        glass?.up(railHeight)
                        touchY = null
                        active = null
                        scope.launch { presence.animateTo(0f, if (reduced) spring(1f, 10_000f) else spring(0.9f, 500f)) }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val n = letters.size
            letters.forEachIndexed { i, l ->
                // Gaussian influence from the finger, scaled by presence so it eases in and out.
                val y = touchY
                val itemH = railHeight / n
                val d = if (y == null) 99f else (y - (i + 0.5f) * itemH) / itemH
                val influence = exp(-(d * d) / (2f * 2.2f * 2.2f)) * presence.value
                val isCurrent = l == (active ?: current)
                BasicText(
                    l.toString(),
                    style = QType.small(11.sp).copy(
                        color = lerp(if (isCurrent) c.ink else c.muted, c.ink, influence.coerceIn(0f, 1f)),
                    ),
                    modifier = Modifier
                        .padding(vertical = 1.5.dp)
                        .graphicsLayer {
                            // Underwater the bead's lens adds its own magnification on top.
                            val s = 1f + influence * (if (water != null) 0.7f else 1.0f)
                            scaleX = s; scaleY = s
                            translationX = -influence * 26.dp.toPx()
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Quiet Underwater: rod, bead, orb

/** The rod: a thin clear pill; a narrow dome so the letters in its flat middle stay crisp. */
private val RodGlass = GlassStyle(corner = 999.dp, bevel = 6.dp)
/** The bead: a magnifying lens that lags the finger a little and overshoots when it stops; stirs the water. */
private val BeadGlass = GlassStyle(corner = 999.dp, bevel = 13.dp, lens = 0.88f, stir = true, stiffness = 560f, damping = 0.56f)
/** The orb around the bubble letter: a soft lens, a stiffer spring than the bead (it already trails). */
private val OrbGlass = GlassStyle(corner = 999.dp, bevel = 18.dp, lens = 0.16f, stiffness = 760f, damping = 0.6f)
private val Smoke = androidx.compose.ui.graphics.Color(0x8C0B0B0A)
private val BUBBLE_GAP = 92.dp
private val BEAD_R = 21.dp

@Composable
private fun rememberRailGlass(host: WaterHost): RailGlass {
    val g = remember(host) { RailGlass(host) }
    DisposableEffect(g) { onDispose { g.release() } }
    return g
}

/**
 * The bead and the orb: obtained on touch, driven every frame while touched and while they settle,
 * then given back (two of the ten bodies, only while you scrub).
 */
private class RailGlass(private val host: WaterHost) {
    var railK: LayoutCoordinates? = null
    var bubbleK: LayoutCoordinates? = null
    var bead = -1
        private set
    var bubble = -1
        private set
    /** Finger y in rail px. */
    var fingerY = 0f
    private var touching = false
    private var upAt = 0L
    private var rollY = 0f
    private var vS = 0f            // smoothed finger speed along the rail, px/s
    private var job: Job? = null

    fun down(scope: CoroutineScope, y: Float, presence: () -> Float) {
        touching = true; fingerY = y; vS = 0f
        if (bead < 0) bead = host.obtainGlass(BeadGlass)
        if (bubble < 0) bubble = host.obtainGlass(OrbGlass)
        host.glassAlpha(bead, 1f)
        if (job?.isActive != true) job = scope.launch { drive(presence) }
    }

    fun up(railH: Float) {
        if (!touching) return
        touching = false
        upAt = SystemClock.uptimeMillis()
        // It rolls on along the rod with the finger's speed, then the spring settles it there.
        rollY = (fingerY + vS * 0.11f).coerceIn(0f, railH)
        if (bead >= 0) host.kickGlass(bead, 0f, vS * 0.25f)
    }

    fun release() {
        job?.cancel(); job = null
        if (bead >= 0) host.freeGlass(bead)
        if (bubble >= 0) host.freeGlass(bubble)
        bead = -1; bubble = -1
    }

    private suspend fun drive(presence: () -> Float) {
        var last = 0L
        var prevY = fingerY
        while (true) {
            val done = withFrameNanos { ns ->
                val dt = if (last == 0L) 1f / 120f else ((ns - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = ns
                if (touching) {
                    val v = (fingerY - prevY) / dt
                    vS += (v - vS) * min(1f, dt * 16f)
                } else vS *= exp(-dt * 9f)
                prevY = fingerY
                place(presence())
                !touching && SystemClock.uptimeMillis() - upAt > 560L && presence() < 0.02f
            }
            if (done) break
        }
        if (bead >= 0) host.freeGlass(bead)
        if (bubble >= 0) host.freeGlass(bubble)
        bead = -1; bubble = -1
        job = null
    }

    private fun place(p: Float) {
        val dp = host.dp
        val k = railK
        if (bead >= 0 && k != null && k.isAttached) {
            val r = host.worldRect(k)
            val sy = (r[3] - r[1]) / k.size.height.coerceAtLeast(1)
            val cx = (r[0] + r[2]) / 2f
            val y = r[1] + (if (touching) fingerY else rollY) * sy
            // Stretch along a fast scrub (area kept), on top of the engine's own squash.
            val st = (abs(vS) / (1500f * dp)).coerceIn(0f, 1f) * 0.3f
            val rad = BEAD_R.value * dp * sy
            // On the crest of the letters' wave while touched; back onto the rod as it lets go.
            host.moveGlass(bead, cx - 28f * dp * p * sy, y, rad / (1f + st * 0.5f), rad * (1f + st))
            if (!touching && SystemClock.uptimeMillis() - upAt > 90L) host.glassAlpha(bead, 0f)
        }
        val b = bubbleK
        if (bubble >= 0 && b != null && b.isAttached) {
            val r = host.worldRect(b)
            val half = (r[2] - r[0]) / 2f * (0.4f + 0.6f * p)
            host.moveGlass(bubble, r[2] - half, (r[1] + r[3]) / 2f, half, half)
            host.glassAlpha(bubble, p.coerceIn(0f, 1f))
        }
    }
}
