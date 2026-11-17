package app.quiet.launcher.feature.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.rememberHaptics

/**
 * The hairline progress you can seek with: tap a point or drag along it (a 36 dp tall touch band,
 * though the line stays a hairline). While dragging the knob grows and the times follow the finger;
 * the player is asked to seek on release, and the line holds the new spot until the player reports it.
 */
@Composable
fun SeekLine(
    np: NowPlaying,
    onSeek: (Long) -> Unit,
    track: Color,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
    labelGap: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val c = Q.colors
    val haptics = rememberHaptics()
    val live = rememberPosition(np)
    val dur = np.track.durationMs
    var drag by remember { mutableStateOf<Float?>(null) }
    // After a seek: the target and when it was asked, held until the player catches up (or 2 s).
    var held by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(np.positionUpdatedAt) { held?.let { (_, at) -> if (np.positionUpdatedAt >= at) held = null } }
    val heldPos = held?.let { (p, at) ->
        if (SystemClock.elapsedRealtime() - at > 2000) null
        else p + if (np.isPlaying) SystemClock.elapsedRealtime() - at else 0
    }
    val pos = when {
        drag != null && dur > 0 -> (drag!! * dur).toLong()
        heldPos != null -> heldPos.coerceAtMost(dur.takeIf { it > 0 } ?: Long.MAX_VALUE)
        else -> live
    }
    val fraction = if (dur > 0) pos / dur.toFloat() else 0f
    val grow by animateFloatAsState(if (drag != null) 1f else 0f, tween(160), label = "knob")

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .then(if (dur > 0) Modifier.pointerInput(dur) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        drag = (down.position.x / w).coerceIn(0f, 1f)
                        haptics.play(Feel.Tick)
                        down.consume()
                        horizontalDrag(down.id) { ch ->
                            drag = (ch.position.x / w).coerceIn(0f, 1f)
                            ch.consume()
                        }
                        val f = drag ?: return@awaitEachGesture
                        val target = (f * dur).toLong()
                        held = target to SystemClock.elapsedRealtime()
                        drag = null
                        onSeek(target)
                        haptics.play(Feel.Click)
                    }
                } else Modifier),
        ) {
            val y = size.height / 2
            val f = fraction.coerceIn(0f, 1f)
            drawLine(track, Offset(0f, y), Offset(size.width, y), (1f + grow).dp.toPx())
            drawLine(c.ink, Offset(0f, y), Offset(size.width * f, y), (1.5f + 1.5f * grow).dp.toPx())
            drawCircle(c.ink, (3.5f + 4.5f * grow).dp.toPx(), Offset(size.width * f, y))
        }
        Row(Modifier.fillMaxWidth().padding(top = labelGap).offset(y = (-12).dp)) {
            BasicText(Format.mmss(pos), style = QType.secondary(textSize).copy(color = if (drag != null) c.ink else c.muted))
            Spacer(Modifier.weight(1f))
            if (dur > 0) BasicText("-" + Format.mmss((dur - pos).coerceAtLeast(0)), style = QType.secondary(textSize).copy(color = c.muted))
        }
    }
}
