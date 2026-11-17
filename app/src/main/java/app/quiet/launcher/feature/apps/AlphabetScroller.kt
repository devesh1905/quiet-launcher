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

/**
 * The A–Z scroller, after the reference app's rail-plus-bubble, made more physical:
 *  - a magnifying wave: letters near the finger grow (up to 2x) and arc out to the left with a
 *    Gaussian falloff, like the macOS Dock, and every letter springs back on release;
 *  - a floating bubble with the letter in Fraunces trails the finger on a stiff spring;
 *  - a haptic tick per letter; the list jumps while dragging and glides on a tap;
 *  - at rest, the section you are in stays lit.
 *
 * Lay it out over the right edge of the list (it only takes touches on its 44 dp rail).
 */
@Composable
fun AlphabetScroller(
    letters: List<Char>,
    current: Char?,
    onScrub: (Char) -> Unit,
    onTap: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()

    var railTop by remember { mutableFloatStateOf(0f) }
    var railHeight by remember { mutableFloatStateOf(1f) }
    var touchY by remember { mutableStateOf<Float?>(null) }   // in rail coordinates
    var active by remember { mutableStateOf<Char?>(null) }
    val presence = remember { Animatable(0f) }                // 0 idle .. 1 touching
    val bubbleY = remember { Animatable(0f) }                 // in container coordinates

    fun indexAt(y: Float) = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.lastIndex)

    Box(modifier.width(168.dp).fillMaxHeight()) {
        // Bubble: 64 dp circle, raised fill, control edge, the letter in Fraunces.
        val shownLetter = active ?: current
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
                .onGloballyPositioned { railTop = it.positionInParent().y; railHeight = it.size.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        fun update(y: Float, scrub: Boolean) {
                            touchY = y
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
                            val s = 1f + influence * 1.0f
                            scaleX = s; scaleY = s
                            translationX = -influence * 26.dp.toPx()
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
                )
            }
        }
    }
}
