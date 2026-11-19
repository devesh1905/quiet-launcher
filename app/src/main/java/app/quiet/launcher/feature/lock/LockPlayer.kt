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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.AmbientField
import app.quiet.launcher.feature.player.TransportControls
import app.quiet.launcher.feature.player.SeekLine
import app.quiet.launcher.feature.player.PlayerOrb
import app.quiet.launcher.feature.player.rememberOrbState
import app.quiet.launcher.core.model.PlayerControl

/**
 * Lock-screen player (DESIGN-NOTES §8.3). Playing: the framed cover leads and the field spreads
 * wide. Paused: the art steps back (0.56, dimmed) and the clock takes over (1.55x); the room
 * darkens as the field's cap eases from 0.028 to 0.011. One spring drives all of it.
 */
@Composable
fun LockPlayerScene(ctx: LockContext, np: NowPlaying, repo: NowPlayingRepository, ambientStrength: Float, modifier: Modifier = Modifier, control: PlayerControl = PlayerControl.DROP, onArtLongPress: () -> Unit = {}) {
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
    val orb = rememberOrbState()
    val useOrb = control != PlayerControl.BUTTONS
    var artRect by remember { mutableStateOf<Rect?>(null) }

    // The cover breathes while music plays and the screen is on; paused, it settles and holds still.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val breath = rememberBreath(ctx.animate && np.isPlaying && resumed)

    // Charging: the orb's place and beat, and the cover's bounds, for the lock-scene animations.
    val charge = app.quiet.launcher.feature.lock.charge.LocalCharge.current
    val beating by rememberUpdatedState(np.isPlaying && !reduced && resumed)
    if (charge != null) {
        SideEffect {
            charge.orbX = { orb.x }
            charge.orbBeat = { if (beating) app.quiet.launcher.feature.lock.charge.orbBeatCurve(orb.time) else 0f }
        }
        DisposableEffect(charge) { onDispose { charge.orbCoords = null; charge.coverCoords = null } }
    }

    Box(modifier.fillMaxSize().background(c.bg).onGloballyPositioned { root = it }) {
        AmbientField(
            palette = np.palette, artRect = artRect, modifier = Modifier.fillMaxSize(),
            maxLum = if (np.isPlaying) 0.028f else 0.011f, glow = 1.3f, pool = 0.32f,
            strength = ambientStrength, running = ctx.animate && np.isPlaying, fallbackArt = np.track.art,
            glowScale = { 1f + Breath.GLOW * breath.floatValue },
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
                    .pointerInput(useOrb) { detectTapGestures(onLongPress = { onArtLongPress() }, onTap = if (useOrb) ({ repo.playPause() }) else null) }
                    // As big as the space allows (full width at most) while the controls still clear
                    // the fingerprint ring below.
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .graphicsLayer {
                        val s = artScale * (1f + Breath.SCALE * breath.floatValue)
                        // The cover rides along with the orb: dragged, flung off and back in on a song change.
                        val cx = orb.coverX
                        val away = (kotlin.math.abs(cx) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        scaleX = s * (1f - 0.12f * away); scaleY = scaleX
                        translationX = cx
                        rotationZ = cx / 36.dp.toPx()
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        // Dim and desaturate while paused (brightness .5, saturate .55).
                        alpha = (1f - 0.45f * dim) * (1f - 0.8f * away)
                    }
                    .onGloballyPositioned { coords ->
                        charge?.coverCoords = coords
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
                // The seek line's touch band is 12 dp taller each side than the line it draws.
                Spacer(Modifier.height(6.dp))
                SeekLine(np, repo::seekTo, c.edgeOnField, 13.sp, Modifier.graphicsLayer { alpha = progAlpha })
                if (useOrb) PlayerOrb(control, np, repo, orb, running = resumed,
                    modifier = if (charge != null) Modifier.onGloballyPositioned { charge.orbCoords = it } else Modifier) else TransportControls(np.isPlaying, repo, c.edgeOnField)
            }
            Spacer(Modifier.height(ctx.bottomReserve))
        }
    }
}

/**
 * The breath level (0..1) on a fixed slow cycle ([Breath.PERIOD_MS]), ticking at about 24 fps like
 * the field (it moves a fraction of a pixel per frame). When [running] goes false it eases back
 * to rest and stops asking for frames.
 */
@Composable
private fun rememberBreath(running: Boolean): MutableFloatState {
    val level = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) {
            if (level.floatValue > 0f) androidx.compose.animation.core.animate(level.floatValue, 0f, animationSpec = tween(600)) { v, _ -> level.floatValue = v }
            return@LaunchedEffect
        }
        // Pick up from the current level (on the rising half) so a resume never jumps.
        val start = withFrameNanos { it } - Breath.msFor(level.floatValue) * 1_000_000L
        while (true) {
            withFrameNanos { now -> level.floatValue = Breath.at((now - start) / 1_000_000L) }
            kotlinx.coroutines.delay(38)
        }
    }
    return level
}

@Suppress("unused") private val keepMatrix = ColorMatrix()
