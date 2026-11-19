package app.quiet.launcher.feature.lock

import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.rememberHaptics
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import app.quiet.launcher.feature.lock.charge.chargeScene
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.LockFont
import app.quiet.launcher.core.model.LockStyle
import app.quiet.launcher.designsystem.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LockStyle.title: String
    get() = when (this) {
        LockStyle.MASTERPIECE -> "Daily masterpiece"
        LockStyle.CANVAS -> "Quiet canvas"
        LockStyle.SKY -> "Tonight’s sky"
        LockStyle.TYPESET -> "Typeset"
        LockStyle.COVER -> "Cover of the moment"
    }

/**
 * The lock screen. One gesture model, Apple-like:
 *  - drag up: the scene follows the finger, scales back slightly and fades; release uses the
 *    projected landing point, so a short quick flick unlocks and a slow half drag springs back
 *    with the finger's velocity;
 *  - drag down rubber-bands;
 *  - press and hold the wall: the scene shrinks into a card and the other styles slide in beside it.
 */
@Composable
fun LockRoot(startInPicker: Boolean, unlockSignal: Int, onUnlock: () -> Unit, onPickerDone: () -> Unit, onIdle: () -> Unit = {}) {
    val ctx = LocalContext.current
    val container = ctx.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val minute by container.device.minuteTick.collectAsStateWithLifecycle()
    val usage by container.usage.today.collectAsStateWithLifecycle()
    val art by container.art.today.collectAsStateWithLifecycle()
    val favourites by container.art.favourites.collectAsStateWithLifecycle()
    val artIsFavourite = art?.let { a -> favourites.any { it.id == a.id } } == true
    // Bumped when today's painting is added to favourites: the scene shows a small heart.
    var heartPulse by remember { mutableIntStateOf(0) }
    val np by container.nowPlaying.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val density = LocalDensity.current
    val reduced = Q.reducedMotion
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val musicHiddenTrackHolder = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        container.usage.refresh()
        container.art.refreshIfStale()
    }

    // Remember the last cover for "Cover of the moment" even after the music stops.
    var lastCover by remember { mutableStateOf(np?.track) }
    LaunchedEffect(np?.track?.id) { np?.track?.let { lastCover = it } }

    // The ring sits on the in-display sensor; music only needs to clear the ring (+16 dp), not the hint.
    val sensorReserve = remember(size) {
        val sp = readSensorSpot(size.width, size.height)
        with(density) { ((size.height - (sp.cy - sp.radius)).coerceAtLeast(0f)).toDp() } + 16.dp
    }
    // ---------- charging ----------
    val charge = remember { app.quiet.launcher.feature.lock.charge.ChargeHost() }
    val two = remember { ShowOffGesture() }
    val holdHaptic = app.quiet.launcher.designsystem.rememberHaptics()
    val showingMusic = np != null && settings.lockShowMusic && np?.track?.id?.substringBefore('|') != musicHiddenTrackHolder.value
    val paintingSwatches = app.quiet.launcher.feature.lock.charge.rememberPaintingSwatches(art?.imagePath)
    val ringSpot = remember(size) { readSensorSpot(size.width, size.height) }
    val ringTop = ringSpot.cy - ringSpot.radius
    // ---------- Water (Quiet Underwater): a theme of its own, placed before the painting in the gallery.
    // Its charging animation is the water itself, so the charging host stays off under it.
    val waterAvailable = app.quiet.launcher.feature.lock.water.lockWaterAvailable()
    val waterOn = app.quiet.launcher.feature.lock.water.lockWaterChosen()
    val water = remember { app.quiet.launcher.feature.lock.water.WaterLockControl() }
    val waterNow by rememberUpdatedState(waterOn)   // read inside the long-lived gesture loops
    app.quiet.launcher.feature.lock.charge.ChargeEngine(
        charge, if (waterOn) app.quiet.launcher.core.model.ChargeAnim.OFF else settings.lockCharging, reduced,
        playing = showingMusic && np?.isPlaying == true,
        palette = if (showingMusic) np?.palette else paintingSwatches,
        ringTop = if (size.height > 1) ringTop else 0f,
        ringR = ringSpot.radius,
    )

    val lockCtx = LockContext(
        face = lockFace(settings.lockFont), minute = minute, is24h = DateFormat.is24HourFormat(ctx),
        usageMs = usage.totalMs, oneThing = settings.oneThingToday, art = art,
        lat = settings.latitude, lon = settings.longitude, city = settings.cityName, animate = !reduced,
        bottomReserve = if (size.height > 1) sensorReserve else LockBottomReserve,
    )

    // ---------- unlock drag ----------
    val lift = remember { Animatable(0f) }      // px, negative = up
    val h = size.height.toFloat().coerceAtLeast(1f)
    val progress = (-lift.value / (h * 0.45f)).coerceIn(0f, 1f)
    var unlocking by remember { mutableStateOf(false) }
    fun finishUnlock(velocity: Float) {
        if (unlocking) return
        unlocking = true
        // Ask for the PIN / fingerprint at once; the scene lifts away in parallel (fast, ~220 ms).
        onUnlock()
        scope.launch {
            lift.animateTo(-h, if (reduced) tween(100) else spring(dampingRatio = 1f, stiffness = 1100f), initialVelocity = velocity)
        }
    }
    LaunchedEffect(unlockSignal) { if (unlockSignal > 0) finishUnlock(-2500f) }

    // ---------- music on the lock screen ----------
    var musicHiddenTrack by musicHiddenTrackHolder
    var musicMenu by remember { mutableStateOf(false) }
    var artPressHandled by remember { mutableStateOf(false) }
    var paintingMenu by remember { mutableStateOf(false) }

    // ---------- picker ----------
    var picking by remember { mutableStateOf(startInPicker) }
    val pick = remember { Animatable(if (startInPicker) 1f else 0f) }
    // The gallery's pages; null is Water (underwater edition only, right before the painting).
    val styles: List<LockStyle?> = if (waterAvailable) listOf(null) + LockStyle.entries else LockStyle.entries
    val currentStyle: LockStyle? = if (waterOn) null else settings.lockStyle
    val pager = rememberPagerState(initialPage = styles.indexOf(currentStyle).coerceAtLeast(0)) { styles.size }
    fun openPicker() {
        // The artwork has its own long-press menu; it fires first in the same gesture.
        if (artPressHandled) { artPressHandled = false; return }
        if (picking) return
        haptic.play(Feel.Heavy)
        scope.launch { pager.scrollToPage(styles.indexOf(currentStyle).coerceAtLeast(0)) }
        picking = true
        scope.launch { pick.animateTo(1f, if (reduced) tween(0) else QMotion.Open) }
    }
    fun closePicker(apply: Boolean) {
        scope.launch {
            if (apply) {
                val s = styles[pager.currentPage]
                // Water keeps the painting as its lock style: that is what shows wherever the water cannot.
                if (waterAvailable) app.quiet.launcher.feature.lock.water.LockWaterPrefs.get(ctx).setOn(s == null)
                container.settings.update { it.copy(lockStyle = s ?: LockStyle.MASTERPIECE) }
            }
            pick.animateTo(0f, if (reduced) tween(0) else QMotion.Close)
            picking = false
            if (startInPicker) onPickerDone()
        }
    }
    BackHandler(enabled = picking) { closePicker(apply = false) }

    // Like the system lock screen: the screen goes off after 10 s without a touch.
    // Paused while a menu or the style picker is open, or while unlocking.
    var touchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchTick, picking, musicMenu, paintingMenu, unlocking, startInPicker) {
        if (picking || musicMenu || paintingMenu || unlocking || startInPicker) return@LaunchedEffect
        delay(10_000)
        onIdle()
    }
    LaunchedEffect(musicMenu, paintingMenu) { if (musicMenu || paintingMenu) haptic.play(Feel.Heavy) }
    BackHandler(enabled = !picking) { /* Back never leaves the lock screen. */ }

    // The live scene for a style; music wins over the chosen style when not picking.
    @Composable
    fun Scene(style: LockStyle?, animate: Boolean, allowMusic: Boolean) {
        val lc = lockCtx.copy(animate = animate && !reduced)
        val playing = np
        val musicHere = allowMusic && playing != null && settings.lockShowMusic && playing.track.id.substringBefore('|') != musicHiddenTrack
        // Water: the painting under water; music floats on it. No notifications here. Can't run: the painting.
        if (style == null) {
            if (waterAvailable) {
                app.quiet.launcher.feature.lock.water.WaterLockScene(
                    lc, control = if (allowMusic) water else null,
                    np = if (musicHere) playing else null, repo = container.nowPlaying,
                    onArtLongPress = if (animate) ({ artPressHandled = true; paintingMenu = true }) else null,
                    onMusicLongPress = { artPressHandled = true; musicMenu = true },
                )
                return
            }
        }
        if (musicHere && playing != null) {
            LockPlayerScene(lc, playing, container.nowPlaying, settings.ambientStrength, control = settings.playerControl,
                onArtLongPress = { artPressHandled = true; musicMenu = true })
            return
        }
        when (style ?: LockStyle.MASTERPIECE) {
            LockStyle.MASTERPIECE -> MasterpieceScene(lc, onArtLongPress = if (animate) ({ artPressHandled = true; paintingMenu = true }) else null, heart = if (allowMusic) heartPulse else 0)
            LockStyle.CANVAS -> QuietCanvasScene(lc)
            LockStyle.SKY -> SkyScene(lc)
            LockStyle.TYPESET -> TypesetScene(lc)
            LockStyle.COVER -> CoverScene(lc, lastCover?.art, lastCover?.title, lastCover?.artist)
        }
    }

    val c = Q.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        touchTick++
                        // Show-off Flood: hold two fingers still for ~0.7 s. While it shows, a double tap drains it.
                        if (!picking) {
                            val down = e.changes.count { it.pressed }
                            if (down >= 2 && !two.held) {
                                two.held = true
                                two.moved = 0f
                                two.job?.cancel()
                                two.job = scope.launch {
                                    kotlinx.coroutines.delay(700)
                                    android.util.Log.d("QuietCharge", "hold held=${two.held} moved=${two.moved}")
                                    // Under Water the show-off is the water's own plug-in moment.
                                    if (two.held && two.moved < 40f * density.density && (if (waterNow) water.showOff() else charge.startDemo())) holdHaptic.play(app.quiet.launcher.designsystem.Feel.Heavy)
                                }
                            }
                            if (two.held) e.changes.forEach { two.moved += (it.position - it.previousPosition).getDistance() }
                            // Two fingers belong to the show-off: consume them here (Initial pass) so the theme
                            // picker's and the artwork's long-presses, taps and the unlock swipe all cancel.
                            if (down >= 2 || two.sawTwo) { two.sawTwo = down > 0; e.changes.forEach { it.consume() } }
                            if (down < 2) { two.held = false; two.job?.cancel() }
                            if (charge.demo && down == 0 && e.changes.any { it.changedToUp() }) {
                                val t = android.os.SystemClock.uptimeMillis()
                                if (t - two.lastTapUp < 320L) { charge.endDemo(); two.lastTapUp = 0L } else two.lastTapUp = t
                            }
                        }
                        // Flood: fingers stir the water. Read only, never consumed, so every gesture still works.
                        if (!picking && charge.shown == app.quiet.launcher.core.model.ChargeAnim.FLOOD) e.changes.forEach { ch ->
                            if (ch.pressed) charge.touch(ch.position.x, ch.position.y - lift.value, ch.position.x - ch.previousPosition.x, ch.position.y - ch.previousPosition.y)
                        }
                    }
                }
            }
    ) {
        if (!picking) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = lift.value
                        val s = 1f - 0.06f * progress
                        scaleX = s; scaleY = s
                        alpha = 1f - 0.85f * progress
                        transformOrigin = TransformOrigin(0.5f, 0.35f)
                    }
                    .pointerInput(Unit) {
                        // Wait a frame so an artwork's own long-press (same gesture) can claim it first.
                        detectTapGestures(onLongPress = { scope.launch { kotlinx.coroutines.delay(32); openPicker() } })
                    }
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onVerticalDrag = { ch, dy ->
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                val raw = lift.value + dy
                                scope.launch { lift.snapTo(if (raw > 0) QMotion.rubberBand(raw, h * 0.12f) else raw) }
                                // Water: the meniscus is drawn up after the finger; a soft Rise past the threshold.
                                if (waterNow) water.unlockDrag(raw, h, ch.position.x, dy)
                            },
                            onDragEnd = {
                                if (waterNow) water.unlockEnd()
                                val v = tracker.calculateVelocity().y
                                val landing = lift.value + QMotion.project(v)
                                if (landing < -h * 0.2f || lift.value < -h * 0.15f || v < -700f) finishUnlock(minOf(v, -1200f))
                                else scope.launch { lift.animateTo(0f, QMotion.Return, initialVelocity = v) }
                            },
                            onDragCancel = { scope.launch { lift.animateTo(0f, QMotion.Return) } },
                        )
                    }
            ) {
                androidx.compose.runtime.CompositionLocalProvider(app.quiet.launcher.feature.lock.charge.LocalCharge provides charge) {
                    Box(
                        Modifier.fillMaxSize()
                            .onGloballyPositioned { charge.root = it }
                            .chargeScene(charge)
                    ) {
                        Scene(currentStyle, animate = true, allowMusic = true)
                    }
                }
                app.quiet.launcher.feature.lock.charge.ChargeOverlay(charge)
            }
            // Fingerprint ring drawn exactly over the in-display sensor; a hint just above it.
            val spot = remember(size) { readSensorSpot(size.width, size.height) }
            val ringDp = with(density) { (spot.radius * 2f).toDp() }
            val fadeOut = (1f - progress * 1.6f).coerceIn(0f, 1f)
            if (np == null) UnlockHint(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(0, (spot.cy - spot.radius - 64.dp.toPx()).toInt()) }
                    .fillMaxWidth()
                    .graphicsLayer { alpha = fadeOut; translationY = lift.value * 0.3f },
            )
            // The charging glass drop sits on the sensor in place of the ring; the ring keeps its touch handling.
            val glassOnRing = androidx.compose.animation.core.animateFloatAsState(
                if (charge.shown == app.quiet.launcher.core.model.ChargeAnim.GLASS && !showingMusic && !unlocking) 1f else 0f,
                label = "glassOnRing",
            ).value
            // Water: the ring becomes a small glass drop on the sensor (drawn in the water); the ring keeps the touch.
            LaunchedEffect(unlocking, waterOn) { if (unlocking && waterOn) water.unlocked() }
            FingerprintRing(
                diameter = ringDp,
                success = unlocking,
                onHold = { finishUnlock(-1600f) },
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((spot.cx - spot.radius).toInt(), (spot.cy - spot.radius).toInt()) }
                    .then(if (waterOn) Modifier.pointerInput(Unit) {
                        // Read only (Initial pass, never consumed): the drop squeezes and rings under the finger.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                water.sensorPress(e.changes.any { it.pressed })
                            }
                        }
                    } else Modifier)
                    .graphicsLayer { alpha = if (waterOn) 0f else (if (unlocking) 1f else fadeOut) * (1f - glassOnRing) },
            )
        } else {
            // ---------- the gallery of lock screens ----------
            val r = 0.78f
            val wDp = with(density) { size.width.toDp() }
            val hDp = with(density) { size.height.toDp() }
            val p = pick.value
            val radius = lerp(0.dp, 34.dp, p.coerceIn(0f, 1f))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Spacer(Modifier.height(lerp(0.dp, 12.dp, p.coerceIn(0f, 1f))))
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = wDp * (1 - r) / 2),
                    pageSpacing = 14.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(hDp * r)
                        .graphicsLayer {
                            // At p = 0 the current page fills the screen exactly; neighbours sit off-screen.
                            val s = lerp(1f / r, 1f, p)
                            scaleX = s; scaleY = s
                        },
                    userScrollEnabled = p > 0.95f,
                    beyondViewportPageCount = 0,
                ) { page ->
                    val style = styles[page]
                    val selected = currentStyle == style
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(radius))
                            .border(if (selected && p > 0.5f) 1.dp else 0.dp, c.edge.copy(alpha = p), RoundedCornerShape(radius))
                            .pointerInput(page) { detectTapGestures { scope.launch { pager.animateScrollToPage(page) }; if (pager.currentPage == page) closePicker(apply = true) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.requiredSize(wDp, hDp).graphicsLayer { scaleX = r; scaleY = r }) {
                            Scene(style, animate = page == pager.currentPage, allowMusic = false)
                        }
                    }
                }
                // Controls under the cards rise in as the scene shrinks.
                Column(
                    Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f); translationY = (1 - p) * 60.dp.toPx() }
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BasicText(
                        styles[pager.currentPage]?.title ?: "Water",
                        style = TextStyle(fontFamily = lockCtx.face.label, fontStyle = if (lockCtx.face.labelItalic) FontStyle.Italic else FontStyle.Normal, fontSize = 24.sp, color = c.ink),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        LockFont.entries.forEach { f ->
                            val face = lockFace(f)
                            val on = settings.lockFont == f
                            val sel by animateFloatAsState(if (on) 1f else 0f, spring(0.7f, 500f), label = "f")
                            Box(
                                Modifier.size(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .border(1.dp, if (on) c.ink else c.edge, RoundedCornerShape(28.dp))
                                    .graphicsLayer { scaleX = 1f + 0.06f * sel; scaleY = scaleX }
                                    .pressable { scope.launch { container.settings.update { it.copy(lockFont = f) } } },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText("Aa", style = TextStyle(fontFamily = face.display, fontSize = 22.sp, color = c.ink))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.ink).padding(horizontal = 22.dp)
                                .pressable { closePicker(apply = true) },
                            contentAlignment = Alignment.Center,
                        ) { BasicText("Done", style = QType.body(16.sp).copy(color = c.onInk)) }
                    }
                    if (styles[pager.currentPage].let { it == LockStyle.MASTERPIECE || it == null }) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.height(44.dp).pressable { scope.launch { container.art.skip() } }, contentAlignment = Alignment.Center) {
                            BasicText("Show another painting", style = QType.secondary(14.sp).copy(color = c.muted))
                        }
                    }
                }
            }
        }
        app.quiet.launcher.feature.apps.QuietSheet(visible = paintingMenu, onDismiss = { paintingMenu = false }) {
            BasicText(art?.title ?: "Today’s painting", style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.padding(top = 12.dp))
            BasicText(listOfNotNull(art?.artist, art?.year).joinToString(", "), style = QType.secondary(14.sp).copy(color = c.muted), modifier = Modifier.padding(bottom = 12.dp))
            art?.let { a ->
                app.quiet.launcher.feature.apps.SheetAction(if (artIsFavourite) "Remove from favourites" else "Add to favourites") {
                    val add = !artIsFavourite
                    paintingMenu = false
                    scope.launch { container.art.setFavourite(a.id, add) }
                    if (add) { heartPulse++; haptic.play(Feel.Heart) }
                }
            }
            app.quiet.launcher.feature.apps.SheetAction("Show another painting") {
                scope.launch { container.art.skip() }; paintingMenu = false
            }
            app.quiet.launcher.feature.apps.SheetAction("Change lock screen style") {
                paintingMenu = false; artPressHandled = false; openPicker()
            }
            Spacer(Modifier.height(8.dp))
        }
        app.quiet.launcher.feature.apps.QuietSheet(visible = musicMenu, onDismiss = { musicMenu = false }) {
            BasicText("Music on the lock screen", style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.padding(vertical = 12.dp))
            app.quiet.launcher.feature.apps.SheetAction("Hide until the next song") {
                musicHiddenTrack = np?.track?.id?.substringBefore('|'); musicMenu = false
            }
            app.quiet.launcher.feature.apps.SheetAction("Don’t show music on the lock screen") {
                scope.launch { container.settings.update { it.copy(lockShowMusic = false) } }; musicMenu = false
            }
            BasicText(
                "You can turn it back on in Settings, under Lock screen and art.",
                style = QType.secondary(14.sp).copy(color = c.muted),
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
        }
    }
}

/** "Swipe up, or touch the ring" with a hairline that nudges up twice after wake, then rests. */
@Composable
private fun UnlockHint(modifier: Modifier) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(900)
        repeat(2) {
            nudge.animateTo(1f, tween(800, easing = QMotion.EaseInOutSine))
            nudge.animateTo(0f, tween(800, easing = QMotion.EaseInOutSine))
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.graphicsLayer { translationY = -5.dp.toPx() * nudge.value }
                .size(28.dp, 2.dp).background(c.muted, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.height(10.dp))
        BasicText("Swipe up, or rest your finger", style = QType.secondary(13.sp).copy(color = c.muted))
    }
}

/** State of the two-finger "show off Flood" hold on the lock screen. */
private class ShowOffGesture {
    var held = false
    var moved = 0f
    var job: kotlinx.coroutines.Job? = null
    var lastTapUp = 0L
    /** Two fingers were down in this gesture; stays set until every finger lifts. */
    var sawTwo = false
}
