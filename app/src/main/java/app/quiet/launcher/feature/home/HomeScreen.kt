@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.quiet.launcher.feature.home

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.UsageToday
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.core.platform.SystemActions
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.AmbientField
import app.quiet.launcher.feature.gesture.GesturePrefs
import app.quiet.launcher.feature.gesture.Ink
import app.quiet.launcher.feature.gesture.InkHandle
import app.quiet.launcher.feature.gesture.InkHost
import app.quiet.launcher.feature.gesture.LetterGestureLayer
import app.quiet.launcher.feature.gesture.hapticClock
import app.quiet.launcher.feature.gesture.inkLabel
import app.quiet.launcher.feature.gesture.rememberHapticClockState
import app.quiet.launcher.feature.player.NowPlayingCard
import app.quiet.launcher.feature.player.PlayerScreen
import app.quiet.launcher.feature.quick.FocusHome
import kotlinx.coroutines.launch

/**
 * Home: clock, date, battery hairline, the usage block, favourites, and either the
 * now-playing card or the two quiet actions. Swipe up opens search, swipe down the shade.
 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenUsage: () -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    playerOpen: Boolean,
    onPlayerOpenChange: (Boolean) -> Unit,
    /** False while another page is in front: the colour field stops rendering (it was competing with list scrolling). */
    isActive: Boolean = true,
    onOpenFocusSetup: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val container = ctx.container
    // A running focus session takes over Home.
    val focusRt = remember { app.quiet.launcher.feature.focus.FocusRuntime.get(ctx) }
    val focusSnap by focusRt.repo.snapshot.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val session = focusSnap.session?.takeIf { it.endsMs > nowMs }
    LaunchedEffect(focusSnap.session?.endsMs) {
        val ends = focusSnap.session?.endsMs ?: return@LaunchedEffect
        nowMs = System.currentTimeMillis()
        if (ends > nowMs) { kotlinx.coroutines.delay(ends - nowMs + 50); nowMs = System.currentTimeMillis() }
    }
    var quickMenu by remember { mutableStateOf(false) }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val gesture by remember { GesturePrefs.get(ctx) }.state.collectAsStateWithLifecycle()
    val reduced = Q.reducedMotion
    val view = androidx.compose.ui.platform.LocalView.current
    val inkScope = rememberCoroutineScope()
    InkHost()
    val favourites by container.apps.favourites.collectAsStateWithLifecycle(emptyList())
    val usage by container.usage.today.collectAsStateWithLifecycle()
    val usagePerm by container.usage.hasPermission.collectAsStateWithLifecycle()
    val minute by container.device.minuteTick.collectAsStateWithLifecycle()
    val battery by container.device.battery.collectAsStateWithLifecycle()
    // (A fake song only when the adb test file exists: see PlayerTestHook.)
    val np by app.quiet.launcher.feature.player.PlayerTestHook.nowPlaying(ctx, container.nowPlaying.state).collectAsStateWithLifecycle()
    // Long press on the card: hide it until the next song, or turn music off on Home (back on in Settings).
    var hiddenTrack by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var cardMenu by remember { mutableStateOf(false) }
    var glowHiddenTrack by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var glowMenu by remember { mutableStateOf(false) }
    val nowPlaying = np.takeIf { settings.showNowPlayingOnHome && it?.track?.id?.substringBefore('|') != hiddenTrack }
    val is24h = remember(minute) { DateFormat.is24HourFormat(ctx) }

    // Refresh usage on every minute tick while Home is composed (it pauses when stopped).
    LaunchedEffect(minute) { container.usage.refresh() }
    LaunchedEffect(nowPlaying == null) { if (nowPlaying == null) onPlayerOpenChange(false) }

    // The glow drifts only for 20 s after the last touch, then holds still (no rendering).
    var touchTick by remember { mutableIntStateOf(0) }
    var awake by remember { mutableStateOf(true) }
    LaunchedEffect(touchTick, isActive) { awake = true; kotlinx.coroutines.delay(20_000); awake = false }
    SharedTransitionLayout(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    touchTick++
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            // Colour pools softly around the card on Home (u_pool 1.0, cap 0.014).
            val glowTrack = np?.track?.id?.substringBefore('|')
            val water = app.quiet.launcher.feature.underwater.engine.LocalWater.current
            if (water == null && isActive && settings.homeGlow && glowTrack != glowHiddenTrack) AmbientField(
                palette = nowPlaying?.palette, artRect = null, modifier = Modifier.fillMaxSize(),
                maxLum = 0.014f, glow = 0.6f, pool = 0f, strength = settings.ambientStrength * 0.8f,
                running = isActive && awake && nowPlaying?.isPlaying == true && !playerOpen,
                fps = 12, resolution = 0.33f,
            )
            // Home <-> focus Home: the clock morphs into the countdown (shared bounds), the rest of
            // Home sinks back and fades while the focus content rises in behind the clock.
            AnimatedContent(
                targetState = session,
                contentKey = { it != null },
                transitionSpec = {
                    val toFocus = targetState != null
                    (fadeIn(tween(420, 120, QMotion.EaseOutQuiet)) +
                        scaleIn(spring(dampingRatio = 0.86f, stiffness = 240f), initialScale = if (toFocus) 1.03f else 0.97f)) togetherWith
                        (fadeOut(tween(240)) + scaleOut(tween(320, easing = QMotion.EaseOutQuiet), targetScale = if (toFocus) 0.94f else 1.03f))
                },
                label = "focusHome",
            ) { s ->
              if (s != null) {
                with(this@SharedTransitionLayout) { FocusHome(s, this@AnimatedContent) }
              } else {
            // Draw a letter on Home to open an app (off while anything sits on top of Home).
            LetterGestureLayer(
                enabled = gesture.letters && isActive && !playerOpen && !quickMenu && !cardMenu && !glowMenu,
                map = gesture.map, reduced = reduced,
                onVerticalSwipe = { up ->
                    if (up && settings.swipeUpSearch) SystemActions.googleSearch(ctx)
                    else if (!up && settings.swipeDownNotifications) SystemActions.expandNotifications(ctx)
                },
            ) {
            if (water != null) app.quiet.launcher.feature.underwater.UnderwaterHomeContent(
                host = water,
                minute = minute, is24h = is24h, batteryLevel = battery.level, charging = battery.charging,
                usage = usage, usagePerm = usagePerm, showUsage = settings.showUsageOnHome, favourites = favourites,
                oneThing = settings.oneThingToday,
                nowPlaying = nowPlaying, playerOpen = playerOpen,
                onOpenPlayer = { onPlayerOpenChange(true) },
                onCardLongPress = { cardMenu = true },
                onBackgroundLongPress = { if (np != null) glowMenu = true },
                onOpenUsage = onOpenUsage,
                onAppLongPress = onAppLongPress,
                swipeUp = settings.swipeUpSearch, swipeDown = settings.swipeDownNotifications,
                sharedScope = this@SharedTransitionLayout,
                contentScope = this@AnimatedContent,
                onClockLongPress = { quickMenu = true },
                hapticClockOn = gesture.hapticClock,
                active = isActive && !quickMenu && !cardMenu && !glowMenu,
            ) else HomeContent(
                minute = minute, is24h = is24h, batteryLevel = battery.level, charging = battery.charging,
                usage = usage, usagePerm = usagePerm, showUsage = settings.showUsageOnHome, favourites = favourites,
                oneThing = settings.oneThingToday,
                nowPlaying = nowPlaying, playerOpen = playerOpen,
                onOpenPlayer = { onPlayerOpenChange(true) },
                onCardLongPress = { cardMenu = true },
                onBackgroundLongPress = { if (np != null) glowMenu = true },
                onOpenSearch = onOpenSearch, onOpenUsage = onOpenUsage,
                onLaunch = { app, handle -> Ink.open(view, app.key, handle, gesture.ink && !reduced, inkScope) },
                onAppLongPress = onAppLongPress,
                swipeUp = settings.swipeUpSearch, swipeDown = settings.swipeDownNotifications,
                sharedScope = this@SharedTransitionLayout,
                contentScope = this@AnimatedContent,
                onClockLongPress = { quickMenu = true },
                hapticClockOn = gesture.hapticClock,
            )
            }
              }
            }
            app.quiet.launcher.feature.quick.QuickActionsSheet(
                visible = quickMenu, onDismiss = { quickMenu = false },
                onOpenFocusSetup = onOpenFocusSetup, onOpenUsage = onOpenUsage,
            )
            AnimatedVisibility(
                visible = playerOpen && nowPlaying != null,
                enter = fadeIn(tween(320, easing = QMotion.EaseOutQuiet)),
                exit = fadeOut(tween(260, 80)),
            ) {
                val current = nowPlaying
                if (current != null) {
                    PlayerScreen(
                        np = current, repo = container.nowPlaying, visibility = this,
                        opening = playerOpen, ambientStrength = settings.ambientStrength,
                        onClose = { onPlayerOpenChange(false) },
                    )
                }
            }
            val scope = rememberCoroutineScope()
            app.quiet.launcher.feature.apps.QuietSheet(visible = cardMenu, onDismiss = { cardMenu = false }) {
                BasicText("Now playing", style = QType.labelTitle(24.sp).copy(color = Q.colors.ink), modifier = Modifier.padding(vertical = 12.dp))
                app.quiet.launcher.feature.apps.SheetAction("Hide until the next song") {
                    hiddenTrack = np?.track?.id?.substringBefore('|'); glowHiddenTrack = hiddenTrack; cardMenu = false
                }
                app.quiet.launcher.feature.apps.SheetAction("Don’t show music on Home") {
                    scope.launch { container.settings.update { it.copy(showNowPlayingOnHome = false) } }; cardMenu = false
                }
                BasicText(
                    "You can turn it back on in Settings, under Home.",
                    style = QType.secondary(14.sp).copy(color = Q.colors.muted),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
            }
            app.quiet.launcher.feature.apps.QuietSheet(visible = glowMenu, onDismiss = { glowMenu = false }) {
                BasicText("Colour glow", style = QType.labelTitle(24.sp).copy(color = Q.colors.ink), modifier = Modifier.padding(vertical = 12.dp))
                app.quiet.launcher.feature.apps.SheetAction("Hide until the next song") {
                    glowHiddenTrack = np?.track?.id?.substringBefore('|'); glowMenu = false
                }
                app.quiet.launcher.feature.apps.SheetAction("Turn off the glow on Home") {
                    scope.launch { container.settings.update { it.copy(homeGlow = false) } }; glowMenu = false
                }
                BasicText(
                    "You can turn it back on in Settings, under Home.",
                    style = QType.secondary(14.sp).copy(color = Q.colors.muted),
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    minute: Long, is24h: Boolean, batteryLevel: Float, charging: Boolean,
    usage: UsageToday, usagePerm: Boolean, showUsage: Boolean, favourites: List<AppEntry>, oneThing: String,
    nowPlaying: NowPlaying?, playerOpen: Boolean,
    onOpenPlayer: () -> Unit, onCardLongPress: () -> Unit, onBackgroundLongPress: () -> Unit, onOpenSearch: () -> Unit, onOpenUsage: () -> Unit,
    onLaunch: (AppEntry, InkHandle) -> Unit, onAppLongPress: (AppEntry) -> Unit,
    swipeUp: Boolean, swipeDown: Boolean,
    sharedScope: SharedTransitionScope,
    contentScope: AnimatedVisibilityScope? = null,
    onClockLongPress: () -> Unit = {},
    hapticClockOn: Boolean = false,
) {
    val ctx = LocalContext.current
    val c = Q.colors
    val clock = rememberHapticClockState(Q.reducedMotion)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 32.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onBackgroundLongPress() }, onDoubleTap = {
                    if (ctx.container.settings.settings.value.doubleTapLock &&
                        !app.quiet.launcher.feature.focus.LockController.lockNow(ctx)) {
                        // Not a device admin yet: ask from the gesture itself.
                        app.quiet.launcher.core.platform.Permissions.launch(ctx, app.quiet.launcher.feature.focus.LockController.adminIntent(ctx))
                    }
                })
            }
            .pointerInput(swipeUp, swipeDown) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = {
                        if (total < -60.dp.toPx() && swipeUp) SystemActions.googleSearch(ctx)
                        else if (total > 60.dp.toPx() && swipeDown) SystemActions.expandNotifications(ctx)
                    },
                )
            },
    ) {
        Spacer(Modifier.height(56.dp))
        // Clock: tap for alarms; hold to feel the time (or, with that off, for quick focus actions).
        // It morphs into the focus countdown.
        BasicText(
            Format.clock(minute, is24h),
            style = QType.clock(112.sp).copy(color = c.ink),
            modifier = Modifier
                .then(
                    if (contentScope != null) with(sharedScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(app.quiet.launcher.feature.quick.ClockKey), contentScope,
                            boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 300f) },
                            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
                        )
                    } else Modifier
                )
                .then(
                    if (hapticClockOn) Modifier.hapticClock(clock, c.ink, c.edge) { SystemActions.alarms(ctx) }
                    else Modifier.pressable(pressedScale = 0.99f, pressedAlpha = 0.7f, onLongPress = onClockLongPress) { SystemActions.alarms(ctx) }
                ),
        )
        Spacer(Modifier.height(18.dp))
        BasicText(Format.longDate(minute), style = QType.body(17.sp).copy(color = c.muted))
        Spacer(Modifier.height(14.dp))
        BatteryHairline(batteryLevel, charging)

        if (oneThing.isNotBlank()) {
            Spacer(Modifier.height(28.dp))
            BasicText(oneThing, style = QType.question(22.sp).copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        if (showUsage) {
            Spacer(Modifier.height(36.dp))
            if (usagePerm) UsageBlock(usage, compact = nowPlaying != null, onClick = onOpenUsage)
            else BasicText(
                "Allow usage access to see your day here.",
                style = QType.secondary(15.sp).copy(color = c.muted),
                modifier = Modifier.pressable {
                    app.quiet.launcher.core.platform.Permissions.launch(ctx, app.quiet.launcher.core.platform.Permissions.usageAccessIntent(ctx))
                },
            )
        }

        Spacer(Modifier.height(36.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
          // Rows shrink to the space left (40-52 dp) so every favourite fits; type scales with them.
          val shown = favourites.take(7)
          val rowH = if (shown.isEmpty()) 52.dp else (maxHeight / shown.size).coerceIn(40.dp, 52.dp)
          val fontSp = (26f * (rowH / 52.dp)).coerceAtLeast(21f)
          Column {
            shown.forEach { app ->
                val ink = remember(app.key.id) { InkHandle(app.key.id) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowH)
                        .inkLabel(ink)
                        .pressable(onLongPress = { onAppLongPress(app) }) { onLaunch(app, ink) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(app.label, style = QType.favourite(fontSp.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (favourites.isEmpty()) {
                BasicText(
                    "Long press any app in the list to add it here.",
                    style = QType.secondary(15.sp).copy(color = c.muted),
                )
            }
          }
        }

        // Bottom: the now-playing card (when music plays) above the phone and camera buttons,
        // which always stay.
        AnimatedVisibility(
            visible = nowPlaying != null,
            enter = fadeIn(tween(360, 120)) + expandVertically(tween(360)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(260)),
        ) {
            val np = nowPlaying
            if (np != null) {
                with(sharedScope) {
                    // The card is visible only while the player is closed: the cover is the shared element.
                    AnimatedVisibility(visible = !playerOpen, enter = fadeIn(tween(200, 200)), exit = fadeOut(tween(1))) {
                        NowPlayingCard(
                            np = np, visibility = this, opening = playerOpen,
                            onOpen = onOpenPlayer, onPlayPause = { ctx.container.nowPlaying.playPause() },
                            onLongPress = onCardLongPress,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.size(52.dp).pressable { SystemActions.dialer(ctx) }, contentAlignment = Alignment.CenterStart) {
                QIconView(QIcon.Phone, c.muted)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(52.dp).pressable { SystemActions.camera(ctx) }, contentAlignment = Alignment.CenterEnd) {
                QIconView(QIcon.Camera, c.muted)
            }
        }
    }
}

@Composable
private fun BatteryHairline(level: Float, charging: Boolean) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    // While charging, a small spark travels the line once every ~10 s. The value is read only in
    // the draw phase, so Home never recomposes for it; between passes nothing redraws at all.
    val spark = remember { androidx.compose.animation.core.Animatable(-1f) }
    LaunchedEffect(charging, reduced) {
        if (!charging || reduced) { spark.snapTo(-1f); return@LaunchedEffect }
        while (true) {
            spark.snapTo(0f)
            spark.animateTo(1f, tween(2400, easing = QMotion.EaseInOutSine))
            spark.snapTo(-1f)
            kotlinx.coroutines.delay(8000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(148.dp).height(8.dp)) {
            val y = size.height / 2
            drawLine(c.edge, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            drawLine(c.ink, Offset(0f, y), Offset(size.width * level, y), 1.5.dp.toPx())
            val sp = spark.value
            if (sp >= 0f) drawCircle(c.ink, 2.5.dp.toPx(), Offset(size.width * level * sp, y))
        }
        Spacer(Modifier.width(12.dp))
        BasicText(
            "${(level * 100).toInt()}%" + if (charging) ", charging" else "",
            style = QType.secondary(14.sp).copy(color = c.muted),
        )
    }
}

@Composable
internal fun UsageBlock(usage: UsageToday, compact: Boolean, onClick: () -> Unit) {
    val c = Q.colors
    val over = usage.totalMs - usage.budgetMs
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.99f, pressedAlpha = 0.75f, onClick = onClick)) {
        AnimatedContent(compact, label = "usage", transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }) { small ->
            if (small) {
                BasicText(
                    "${Format.duration(usage.totalMs)} of ${Format.duration(usage.budgetMs)} today, ${usage.pickups} pickups",
                    style = QType.secondary(15.sp).copy(color = c.muted),
                )
            } else {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(Format.duration(usage.totalMs), style = QType.number(44.sp).copy(color = c.ink))
                        Spacer(Modifier.width(10.dp))
                        BasicText(
                            if (over > 0) "over your ${Format.duration(usage.budgetMs)} by ${Format.duration(over)}"
                            else "of ${Format.duration(usage.budgetMs)} today",
                            style = QType.secondary(15.sp).copy(color = c.muted),
                            modifier = Modifier.padding(bottom = 4.dp).weight(1f),
                        )
                        BasicText("${usage.pickups} pickups", style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    DayStrip(usage.hourlyMinutes, usage.currentHourIndex)
                }
            }
        }
    }
}
