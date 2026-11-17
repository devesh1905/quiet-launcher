@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.quiet.launcher.feature.quick

import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.rememberHaptics
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.feature.apps.QuietSheet
import app.quiet.launcher.feature.apps.SheetAction
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.engine.FocusSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ClockKey = "home-clock"

/**
 * The manual grayscale end time, followed live from the store (so a tap shows at once instead of
 * reading the old value before the write lands). [nowMs] ticks so "on until" expires on screen.
 */
@Composable
private fun rememberGrayscaleUntil(): Pair<Long, Long> {
    val ctx = LocalContext.current
    val until by remember { app.quiet.launcher.feature.monochrome.MonochromeController.get(ctx).store.config }
        .collectAsStateWithLifecycle(initialValue = null)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); nowMs = System.currentTimeMillis() } }
    return (until?.manualUntilMs ?: 0L) to nowMs
}

@Composable
private fun DurationChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Q.colors
    val sel by animateFloatAsState(if (selected) 1f else 0f, spring(0.8f, 600f), label = "chip")
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.ink.copy(alpha = sel))
            .border(1.dp, if (selected) c.ink else c.edge, RoundedCornerShape(24.dp))
            .pressable(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "$minutes min",
            style = QType.body(16.sp).copy(color = androidx.compose.ui.graphics.lerp(c.ink, c.onInk, sel)),
        )
    }
}

/**
 * The quick actions, shared by the Focus page and the hold-the-clock sheet.
 * [onAfter] runs after an action that should close a sheet.
 */
@Composable
fun QuickActionList(onOpenFocusSetup: () -> Unit, onOpenUsage: () -> Unit, onAfter: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Q.colors
    var minutes by remember { mutableIntStateOf(50) }
    val (grayUntil, nowMs) = rememberGrayscaleUntil()
    val grayOn = grayUntil > maxOf(nowMs, System.currentTimeMillis())
    val distracting = remember { QuickActions.distractingApps(ctx).size }
    val haptic = rememberHaptics()
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(25, 50, 90).forEach { m -> DurationChip(m, minutes == m) { minutes = m } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            app.quiet.launcher.feature.focus.ui.StepButton(plus = false, label = "Shorter") {
                haptic.play(Feel.Tick)
                minutes = (minutes - 5).coerceAtLeast(5)
            }
            AnimatedContent(
                targetState = minutes,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(0.85f, 500f)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(spring(1f, 600f)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(120)))
                },
                contentAlignment = Alignment.Center,
                label = "minutes",
            ) { m ->
                BasicText(
                    app.quiet.launcher.feature.focus.ui.FocusCopy.duration(m * 60_000L),
                    style = QType.number(26.sp).copy(color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            app.quiet.launcher.feature.focus.ui.StepButton(plus = true, label = "Longer") {
                haptic.play(Feel.Tick)
                minutes = (minutes + 5).coerceAtMost(240)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.ink)
                .pressable(pressedScale = 0.97f) {
                    haptic.play(Feel.Double)
                    QuickActions.startFocus(ctx, minutes); onAfter()
                },
            contentAlignment = Alignment.Center,
        ) { BasicText("Focus for $minutes minutes", style = QType.body(17.sp).copy(color = c.onInk)) }
        Box(Modifier.fillMaxWidth().height(48.dp).pressable { onOpenFocusSetup(); onAfter() }, contentAlignment = Alignment.Center) {
            BasicText("Choose the apps first", style = QType.secondary(15.sp).copy(color = c.muted))
        }
        Spacer(Modifier.height(8.dp))
        var confirmPause by remember { mutableStateOf(false) }
        QuickRow(
            "Pause distracting apps for an hour",
            if (distracting > 0) "$distracting apps" else "None marked yet",
        ) {
            if (distracting > 0) {
                haptic.play(Feel.Tick)
                confirmPause = !confirmPause
            }
        }
        PauseConfirm(
            visible = confirmPause,
            onConfirm = {
                haptic.play(Feel.Double)
                QuickActions.pauseDistractions(ctx, 60); confirmPause = false; onAfter()
            },
            onCancel = { confirmPause = false },
        )
        QuickRow(
            if (grayOn) "Turn grayscale off" else "Grayscale for an hour",
            if (grayOn) "On until ${Format.clock(grayUntil, is24)}" else "Colour back after an hour",
        ) {
            QuickActions.setGrayscale(ctx, if (grayOn) 0 else 60); onAfter()
        }
        QuickRow("Screen time", null) { onOpenUsage(); onAfter() }
    }
}

/**
 * Asks before blocking: unfolds under the row with the apps that will be paused and when they
 * come back. Nothing is blocked until "Pause for an hour" is pressed.
 */
@Composable
private fun PauseConfirm(visible: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val apps by ctx.container.apps.apps.collectAsStateWithLifecycle()
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f), expandFrom = Alignment.Top) +
            fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(0.8f, 500f), initialScale = 0.96f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f), shrinkTowards = Alignment.Top) +
            fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.97f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)),
    ) {
        val names = remember(apps, visible) {
            val pkgs = QuickActions.distractingApps(ctx)
            apps.filter { it.key.packageName in pkgs }.map { it.label }.distinct().sorted()
        }
        val back = remember(visible) { System.currentTimeMillis() + 60 * 60_000L }
        Column(
            Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .border(1.dp, c.edge, RoundedCornerShape(24.dp)).padding(20.dp),
        ) {
            BasicText("Pause ${names.size} apps until ${Format.clock(back, is24)}?", style = QType.body(17.sp).copy(color = c.ink))
            Spacer(Modifier.height(6.dp))
            val shown = names.take(6).joinToString(", ")
            BasicText(
                if (names.size > 6) "$shown and ${names.size - 6} more" else shown,
                style = QType.secondary(14.sp).copy(color = c.muted),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.edge, RoundedCornerShape(24.dp))
                        .pressable(pressedScale = 0.96f, onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { BasicText("Not now", style = QType.body(16.sp).copy(color = c.ink)) }
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.ink)
                        .pressable(pressedScale = 0.96f, onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) { BasicText("Pause for an hour", style = QType.body(16.sp).copy(color = c.onInk)) }
            }
        }
    }
}

@Composable
private fun QuickRow(title: String, detail: String?, onClick: () -> Unit) {
    val c = Q.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).pressable(pressedScale = 0.99f, onClick = onClick)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            BasicText(title, style = QType.body(17.sp).copy(color = c.ink))
            if (detail != null) BasicText(detail, style = QType.secondary(14.sp).copy(color = c.muted))
        }
        QIconView(QIcon.ChevronRight, c.muted, size = 18.dp, stroke = 1.6f)
    }
}

/** Hold the clock: the same actions in a sheet. */
@Composable
fun QuickActionsSheet(visible: Boolean, onDismiss: () -> Unit, onOpenFocusSetup: () -> Unit, onOpenUsage: () -> Unit) {
    QuietSheet(visible = visible, onDismiss = onDismiss) {
        BasicText("Right now", style = QType.labelTitle(26.sp).copy(color = Q.colors.ink), modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        QuickActionList(onOpenFocusSetup, onOpenUsage, onAfter = onDismiss)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The Focus page, one swipe right of Home: start a session, pause distractions, grayscale,
 * see what is blocked right now and switch schedules on or off.
 */
@Composable
fun FocusPage(onOpenFocusSetup: () -> Unit, onOpenUsage: () -> Unit, onOpenSchedules: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val container = ctx.container
    val rt = remember { FocusRuntime.get(ctx) }
    val usage by container.usage.today.collectAsStateWithLifecycle()
    val blocks by rt.repo.blocks.collectAsStateWithLifecycle()
    val schedules by rt.repo.schedules.collectAsStateWithLifecycle()
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val minute by container.device.minuteTick.collectAsStateWithLifecycle()
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val labelOf = remember(apps) { apps.associate { it.key.packageName to it.label } }
    val scope = rememberCoroutineScope()
    val over = usage.totalMs - usage.budgetMs

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        BasicText("Focus", style = QType.title(44.sp).copy(color = c.ink))
        Spacer(Modifier.height(10.dp))
        BasicText(
            if (over > 0) "${Format.duration(usage.totalMs)} today, over your ${Format.duration(usage.budgetMs)} by ${Format.duration(over)}."
            else "${Format.duration(usage.totalMs)} of ${Format.duration(usage.budgetMs)} today.",
            style = QType.secondary(16.sp).copy(color = c.muted),
        )
        Spacer(Modifier.height(36.dp))
        QuickActionList(onOpenFocusSetup, onOpenUsage)

        val now = minute.coerceAtLeast(System.currentTimeMillis())
        val active = blocks.values.filter { it.untilMs > now }.sortedBy { it.untilMs }
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            BasicText("Blocked now", style = QType.labelTitle(22.sp).copy(color = c.ink))
            Spacer(Modifier.height(6.dp))
            active.forEach { b ->
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(labelOf[b.pkg] ?: b.pkg, style = QType.body(17.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    BasicText("until ${Format.clock(b.untilMs, is24)}", style = QType.secondary(14.sp).copy(color = c.muted))
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("Schedules", style = QType.labelTitle(22.sp).copy(color = c.ink), modifier = Modifier.weight(1f))
            Box(Modifier.height(44.dp).pressable(onClick = onOpenSchedules), contentAlignment = Alignment.CenterEnd) {
                BasicText("Edit", style = QType.secondary(15.sp).copy(color = c.muted))
            }
        }
        if (schedules.isEmpty()) {
            BasicText("No schedules yet.", style = QType.secondary(15.sp).copy(color = c.muted))
        }
        schedules.forEach { s ->
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(s.name, style = QType.body(17.sp).copy(color = c.ink))
                    BasicText(
                        "%02d:%02d to %02d:%02d".format(s.startMinute / 60, s.startMinute % 60, s.endMinute / 60, s.endMinute % 60),
                        style = QType.secondary(14.sp).copy(color = c.muted),
                    )
                }
                QuietSwitch(s.enabled) { v -> scope.launch { rt.repo.saveSchedule(s.copy(enabled = v)) } }
            }
        }
        Spacer(Modifier.height(36.dp))
        BasicText("Streak", style = QType.labelTitle(22.sp).copy(color = c.ink))
        Spacer(Modifier.height(14.dp))
        app.quiet.launcher.feature.streaks.FocusStreaks()
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun QuietSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Q.colors
    val t by animateFloatAsState(if (checked) 1f else 0f, spring(0.7f, 500f), label = "sw")
    Box(Modifier.size(56.dp, 48.dp).pressable(pressedScale = 0.95f) { onChange(!checked) }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(46.dp, 28.dp)) {
            val r = size.height / 2
            drawRoundRect(androidx.compose.ui.graphics.lerp(c.raised, c.ink, t), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            drawRoundRect(c.edge, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(androidx.compose.ui.graphics.lerp(c.ink, c.bg, t), r - 4.dp.toPx(), Offset(r + (size.width - 2 * r) * t, r))
        }
    }
}

/**
 * Home during a focus session: the clock becomes the countdown (shared bounds with the Home clock),
 * then the intention, the apps that may open, and press-and-hold to end early.
 */
@Composable
fun SharedTransitionScope.FocusHome(session: FocusSession, visibility: AnimatedVisibilityScope) {
    val ctx = LocalContext.current
    val c = Q.colors
    val apps by ctx.container.apps.apps.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.endsMs) {
        while (now < session.endsMs) { delay(1000L - System.currentTimeMillis() % 1000L); now = System.currentTimeMillis() }
    }
    val left = (session.endsMs - now).coerceAtLeast(0)
    val s = left / 1000
    val text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val allowed = remember(apps, session.allowed) {
        apps.filter { it.key.packageName in session.allowed && !it.hidden }.distinctBy { it.key.packageName }.sortedBy { it.label.lowercase() }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 32.dp)) {
        Spacer(Modifier.height(56.dp))
        BasicText(
            text, style = QType.clock(112.sp).copy(color = c.ink),
            modifier = Modifier.sharedBounds(
                rememberSharedContentState(ClockKey), visibility,
                boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 300f) },
                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
            ),
        )
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.animateEnterExitCompat(visibility),
        ) {
            BasicText("Focus until ${Format.clock(session.endsMs, is24)}", style = QType.body(17.sp).copy(color = c.muted))
            if (session.intention.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                BasicText(session.intention, style = QType.question(26.sp).copy(color = c.ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(36.dp))
            BasicText(if (allowed.isEmpty()) "Only calls and Quiet stay open." else "You can open", style = QType.secondary(15.sp).copy(color = c.muted))
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).animateEnterExitCompat(visibility, delayMs = 80)) {
            allowed.forEach { a ->
                Box(Modifier.fillMaxWidth().height(50.dp).pressable { ctx.container.apps.launch(a.key) }, contentAlignment = Alignment.CenterStart) {
                    BasicText(a.label, style = QType.favourite(24.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HoldToEnd(Modifier.padding(bottom = 16.dp).animateEnterExitCompat(visibility, delayMs = 140)) {
            QuickActions.endFocusEarly(ctx)
        }
    }
}

/** Fade and rise in a little after the clock morph; leave quickly. */
@Composable
private fun Modifier.animateEnterExitCompat(visibility: AnimatedVisibilityScope, delayMs: Int = 0): Modifier =
    with(visibility) {
        this@animateEnterExitCompat.animateEnterExit(
            enter = fadeIn(tween(380, 180 + delayMs, QMotion.EaseOutQuiet)) + slideInVertically(tween(420, 180 + delayMs, QMotion.EaseOutQuiet)) { it / 10 },
            exit = fadeOut(tween(160)),
        )
    }

/** Press and hold for 1.5 s to end early; the fill rewinds if you let go. */
@Composable
fun HoldToEnd(modifier: Modifier = Modifier, onDone: () -> Unit) {
    val c = Q.colors
    val haptic = rememberHaptics()
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp))
            .border(1.dp, c.edge, RoundedCornerShape(28.dp))
            .drawBehind { drawRect(c.ink.copy(alpha = 0.18f), size = size.copy(width = size.width * fill.value)) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        fill.animateTo(1f, tween(1500, easing = LinearEasing))
                        haptic.play(Feel.Heavy)
                        onDone()
                    }
                    waitForUpOrCancellation()
                    if (fill.value < 1f) { job.cancel(); scope.launch { fill.animateTo(0f, tween(200, easing = QMotion.EaseOutQuiet)) } }
                }
            },
        contentAlignment = Alignment.Center,
    ) { BasicText("Hold to end early", style = QType.body(16.sp).copy(color = c.muted)) }
}

@Suppress("unused") private val keepGraphics = Modifier.graphicsLayer()
