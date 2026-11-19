@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.quiet.launcher.feature.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.AmbientField
import app.quiet.launcher.feature.underwater.UnderwaterPlayerScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val CoverKey = "now-playing-cover"

/** Spring for the cover flight: opens with a slight overshoot, closes critically damped. */
fun coverBounds(opening: Boolean): BoundsTransform = BoundsTransform { _, _ ->
    if (opening) spring(dampingRatio = 0.72f, stiffness = 380f, visibilityThreshold = Rect.VisibilityThreshold)
    else spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = Rect.VisibilityThreshold)
}

/** Live playback position, ticking twice a second while playing. */
@Composable
fun rememberPosition(np: NowPlaying): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying, np.positionUpdatedAt) {
        while (np.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    return np.positionAt(now)
}

/** Small framed card on Home (58 dp cover on a 3 dp mat) with title, artist and play/pause. */
@Composable
fun SharedTransitionScope.NowPlayingCard(
    np: NowPlaying,
    visibility: AnimatedVisibilityScope,
    opening: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val c = Q.colors
    Row(modifier.fillMaxWidth().pressable(pressedScale = 0.985f, pressedAlpha = 0.8f, onLongPress = onLongPress, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        GalleryFrame(
            art = np.track.art, fallbackTitle = np.track.title, matColor = c.mat, matFraction = 3f / 64f,
            contentKey = np.track.id,
            modifier = Modifier
                .sharedElement(rememberSharedContentState(CoverKey), visibility, boundsTransform = coverBounds(opening))
                .size(64.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            BasicText(np.track.title, style = QType.labelTitle(21.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(np.track.artist, style = QType.secondary(14.sp).copy(color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).pressable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
            QIconView(if (np.isPlaying) QIcon.Pause else QIcon.Play, c.ink)
        }
    }
}

/**
 * The expanded player: the signature moment. The cover flies in from the card (shared element),
 * the ambient field gathers around the frame, and the label and controls rise in behind it.
 * Drag down to dismiss: the sheet follows the finger with a rubber band, and the release
 * velocity decides (projected landing beyond a third of the screen closes it).
 */
@Composable
fun SharedTransitionScope.PlayerScreen(
    np: NowPlaying,
    repo: NowPlayingRepository,
    visibility: AnimatedVisibilityScope,
    opening: Boolean,
    ambientStrength: Float,
    onClose: () -> Unit,
) {
    // Quiet Underwater: the cover floats on the water (feature/underwater/UnderwaterPlayer.kt).
    val water = app.quiet.launcher.feature.underwater.engine.LocalWater.current
    if (water != null) {
        val r = PlayerTestHook.repoOr(androidx.compose.ui.platform.LocalContext.current, repo)
        UnderwaterPlayerScreen(water, np, r, visibility, opening, ambientStrength, onClose)
        return
    }
    val c = Q.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var artRect by remember { mutableStateOf<Rect?>(null) }
    val drag = remember { Animatable(0f) }
    var screenH by remember { mutableFloatStateOf(2000f) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoords = it; screenH = it.size.height.toFloat() }
            .background(c.bg)
    ) {
        AmbientField(
            palette = np.palette, artRect = artRect, modifier = Modifier.fillMaxSize(),
            maxLum = 0.028f, glow = 1f, pool = 0.55f, strength = ambientStrength, running = true,
            fallbackArt = np.track.art,
        )
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 40.dp)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val raw = drag.value + dy
                            // Upward drags rubber-band; downward drags follow the finger.
                            val next = if (raw < 0f) QMotion.rubberBand(raw, screenH * 0.15f) else raw
                            scope.launch { drag.snapTo(next) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            val landing = drag.value + QMotion.project(v)
                            if (landing > screenH / 3f) onClose()
                            else scope.launch { drag.animateTo(0f, QMotion.Return, initialVelocity = v) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, QMotion.Return) } },
                    )
                },
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).pressable(onClick = onClose), contentAlignment = Alignment.CenterStart) {
                    QIconView(QIcon.ChevronDown, c.ink)
                }
                Spacer(Modifier.weight(1f))
                BasicText("Playing on this phone", style = QType.secondary(14.sp).copy(color = c.muted))
            }
            Spacer(Modifier.weight(0.6f))
            GalleryFrame(
                art = np.track.art, fallbackTitle = np.track.title, matColor = c.mat, matFraction = 15f / 332f,
                contentKey = np.track.id, shadow = 24.dp,
                modifier = Modifier
                    .sharedElement(rememberSharedContentState(CoverKey), visibility, boundsTransform = coverBounds(opening))
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onGloballyPositioned { coords ->
                        val root = rootCoords ?: return@onGloballyPositioned
                        val b = coords.boundsInRoot()
                        val r = root.boundsInRoot()
                        artRect = Rect(b.left - r.left, b.top - r.top, b.right - r.left, b.bottom - r.top)
                    },
            )
            Spacer(Modifier.height(32.dp))
            // Label, progress and controls rise in 8 dp behind the flying cover (DESIGN-NOTES §4).
            Column(
                with(visibility) {
                    Modifier.animateEnterExit(
                        enter = fadeIn(tween(240, 140, QMotion.EaseOutQuiet)) + slideInVertically(tween(240, 140, QMotion.EaseOutQuiet)) { with(density) { 8.dp.roundToPx() } },
                        exit = fadeOut(tween(120)),
                    )
                }
            ) {
                AnimatedContent(
                    targetState = np.track,
                    transitionSpec = { fadeIn(tween(300, 160)) togetherWith fadeOut(tween(160)) },
                    label = "label",
                ) { t ->
                    MuseumLabel(
                        title = t.title, line2 = t.artist,
                        line3 = listOfNotNull(t.album, t.year).joinToString(", ").ifBlank { null },
                        titleStyle = QType.labelTitle(32.sp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SeekLine(np, repo::seekTo, c.edgeOnField, 14.sp, labelGap = 6.dp)
                Spacer(Modifier.height(8.dp))
                TransportControls(np.isPlaying, repo, c.edgeOnField)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun TransportControls(isPlaying: Boolean, repo: NowPlayingRepository, edge: Color, modifier: Modifier = Modifier) {
    val c = Q.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).pressable(onClick = repo::previous), contentAlignment = Alignment.Center) { QIconView(QIcon.Prev, c.ink) }
        Box(
            Modifier.size(68.dp).clip(CircleShape).border(1.dp, edge, CircleShape).pressable(pressedScale = 0.92f, onClick = repo::playPause),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(isPlaying, transitionSpec = { (fadeIn(tween(160)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) }, label = "pp") {
                QIconView(if (it) QIcon.Pause else QIcon.Play, c.ink, size = 26.dp)
            }
        }
        Box(Modifier.size(56.dp).pressable(onClick = repo::next), contentAlignment = Alignment.Center) { QIconView(QIcon.Next, c.ink) }
    }
}

@Suppress("unused") private val keep = Modifier.graphicsLayer()
