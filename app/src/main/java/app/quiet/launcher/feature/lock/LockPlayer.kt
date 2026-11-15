package app.quiet.launcher.feature.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.AmbientField
import app.quiet.launcher.feature.player.TransportControls
import app.quiet.launcher.feature.player.rememberPosition

/**
 * Lock-screen player (DESIGN-NOTES §8.3). Playing: the framed cover leads and the field spreads
 * wide. Paused: the art steps back (0.56, dimmed) and the clock takes over (1.55x); the room
 * darkens as the field's cap eases from 0.028 to 0.011. One spring drives all of it.
 */
@Composable
fun LockPlayerScene(ctx: LockContext, np: NowPlaying, repo: NowPlayingRepository, ambientStrength: Float, modifier: Modifier = Modifier, onArtLongPress: () -> Unit = {}) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val t = updateTransition(!np.isPlaying, label = "paused")
    val spec = if (reduced) tween<Float>(0) else QMotion.Settle
    // Playing: the cover leads and the clock steps back; paused: the clock takes over.
    val clockScale by t.animateFloat({ spec }, label = "clock") { if (it) 1.35f else 0.72f }
    val clockDrop by t.animateFloat({ spec }, label = "drop") { if (it) 34f else 0f }
    val artScale by t.animateFloat({ spec }, label = "art") { if (it) 0.56f else 1f }
    val dim by t.animateFloat({ if (reduced) tween(0) else tween(600) }, label = "dim") { if (it) 1f else 0f }
    val ctrlRise by t.animateFloat({ spec }, label = "rise") { if (it) -38f else 0f }
    val progAlpha by t.animateFloat({ if (reduced) tween(0) else tween(400) }, label = "prog") { if (it) 0f else 1f }

    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var artRect by remember { mutableStateOf<Rect?>(null) }

    Box(modifier.fillMaxSize().background(c.bg).onGloballyPositioned { root = it }) {
        AmbientField(
            palette = np.palette, artRect = artRect, modifier = Modifier.fillMaxSize(),
            maxLum = if (np.isPlaying) 0.028f else 0.011f, glow = 1.3f, pool = 0.32f,
            strength = ambientStrength, running = ctx.animate && np.isPlaying, fallbackArt = np.track.art,
        )
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            var clockH by remember { mutableIntStateOf(0) }
            LockClock(
                ctx,
                Modifier
                    .onSizeChanged { clockH = it.height }
                    .graphicsLayer {
                        scaleX = clockScale; scaleY = clockScale
                        translationY = clockDrop.dp.toPx()
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    },
            )
            // The clock grows with a transform (cheap), so give the layout the same extra room:
            // the art below then shrinks into the space that is really left.
            val density = androidx.compose.ui.platform.LocalDensity.current
            Spacer(Modifier.height(16.dp + with(density) { (clockH * (clockScale - 1f)).toDp() } + clockDrop.dp))
            GalleryFrame(
                art = np.track.art, fallbackTitle = np.track.title, matColor = c.mat, matFraction = 15f / 332f,
                contentKey = np.track.id, shadow = 26.dp,
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onArtLongPress() }) }
                    // As big as the space allows (full width at most) while the controls still clear
                    // the fingerprint ring below.
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .graphicsLayer {
                        scaleX = artScale; scaleY = artScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        // Dim and desaturate while paused (brightness .5, saturate .55).
                        alpha = 1f - 0.45f * dim
                    }
                    .onGloballyPositioned { coords ->
                        val r = root ?: return@onGloballyPositioned
                        val b = coords.boundsInRoot(); val rb = r.boundsInRoot()
                        artRect = Rect(b.left - rb.left, b.top - rb.top, b.right - rb.left, b.bottom - rb.top)
                    },
            )
            Spacer(Modifier.height(18.dp))
            Column(Modifier.graphicsLayer { translationY = ctrlRise.dp.toPx() }) {
                AnimatedContent(np.track, transitionSpec = { fadeIn(tween(300, 160)) togetherWith fadeOut(tween(160)) }, label = "lbl") { tr ->
                    LockLabel(ctx, tr.title, tr.artist, listOfNotNull(tr.album, tr.year).joinToString(", ").ifBlank { null }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.graphicsLayer { alpha = progAlpha }) {
                    val pos = rememberPosition(np)
                    val dur = np.track.durationMs
                    HairlineProgress(if (dur > 0) pos / dur.toFloat() else 0f, track = c.edgeOnField)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        BasicText(Format.mmss(pos), style = QType.secondary(13.sp).copy(color = c.muted))
                        Spacer(Modifier.weight(1f))
                        if (dur > 0) BasicText("-" + Format.mmss(dur - pos), style = QType.secondary(13.sp).copy(color = c.muted))
                    }
                }
                Spacer(Modifier.height(12.dp))
                TransportControls(np.isPlaying, repo, c.edgeOnField)
            }
            Spacer(Modifier.height(ctx.bottomReserve))
        }
    }
}

@Suppress("unused") private val keepMatrix = ColorMatrix()
