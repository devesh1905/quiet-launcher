package app.quiet.launcher.feature.focus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QMotion
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import app.quiet.launcher.designsystem.shader.ShaderCanvas
import app.quiet.launcher.designsystem.shader.Shaders
import app.quiet.launcher.designsystem.shader.ShadersSupported
import app.quiet.launcher.designsystem.shader.setColor3
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

/** Building blocks shared by the focus interstitials and settings. No Material visuals. */

internal val ListGutter = 24.dp
internal val InterGutter = 32.dp
private val RowHeight = 56.dp

@Composable
internal fun Txt(
    text: String,
    style: TextStyle,
    color: Color = Q.colors.ink,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(text, modifier, style = style.copy(color = color, textAlign = align), maxLines = maxLines)
}

private fun Modifier.plainClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.then(
        if (!enabled) Modifier else Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
    )

// ---------- Buttons ----------

@Composable
internal fun SolidButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(shape).background(c.ink)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Txt(text, QType.body(), c.onInk) }
}

@Composable
internal fun LineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(shape).border(1.dp, c.edge, shape)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Txt(text, QType.body(), c.ink) }
}

/** Quiet text action ("Not now, go home"). 48 dp tall. */
@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = Q.colors.muted, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).pressable(onClick = onClick).padding(horizontal = 12.dp)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Txt(text, QType.secondary(15.sp), color) }
}

@Composable
internal fun FocusChip(text: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(24.dp)
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(300), label = "chipAlpha")
    val bg by animateColorAsState(if (selected) c.ink else Color.Transparent, tween(160), label = "chipBg")
    Box(
        modifier.heightIn(min = 44.dp).graphicsLayer { this.alpha = alpha }
            .clip(shape).background(bg).border(1.dp, if (selected) c.ink else c.edge, shape)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp)
            .semantics { role = Role.Button; if (selected) stateDescription = "Selected" },
        contentAlignment = Alignment.Center,
    ) { Txt(text, QType.secondary(15.sp), if (selected) c.onInk else c.ink) }
}

// ---------- Breathing pause ----------

/** Live values of a running pause: [progress] 0..1, [breath] 0..1 (8 s eased sine cycle). */
@Stable
internal class PauseState(val seconds: Int) {
    var progress by mutableFloatStateOf(if (seconds <= 0) 1f else 0f)
    var timeSec by mutableFloatStateOf(0f)
    val done: Boolean get() = progress >= 1f
    val left: Int get() = if (seconds <= 0) 0 else ceil(seconds * (1f - progress)).toInt()
    val breath: Float get() = (0.5f - 0.5f * cos(2f * PI.toFloat() * timeSec / 8f))
    val inhale: Boolean get() = (timeSec % 8f) < 4f
}

/**
 * Runs a pause of [seconds] on the frame clock, so it pauses with the window and the
 * countdown cannot be skipped by backgrounding. Calls [onDone] once at the end.
 */
@Composable
internal fun rememberPause(seconds: Int, key: Any = Unit, onDone: () -> Unit): PauseState {
    val state = remember(seconds, key) { PauseState(seconds) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state) {
        if (seconds <= 0) {
            done()
            return@LaunchedEffect
        }
        var startNs = -1L
        var lastNs = -1L
        var elapsed = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (startNs < 0) startNs = now
            // Frames stop while the window is hidden; skip that gap instead of counting it.
            if (lastNs >= 0) elapsed += (now - lastNs).coerceAtMost(100_000_000L)
            lastNs = now
            state.timeSec = elapsed / 1e9f
            state.progress = (elapsed / 1e9f / seconds).coerceIn(0f, 1f)
            if (state.progress >= 1f) break
        }
        done()
    }
    return state
}

/**
 * The ink breathing ring (DESIGN-NOTES §4, §5): a hairline arc that fills over the pause and a
 * ring that swells on the in-breath. AGSL on API 33+, a Canvas fallback below. Under reduced
 * motion the ring holds still; the arc still fills because it carries information.
 */
@Composable
internal fun BreathRing(pause: PauseState, modifier: Modifier = Modifier, diameter: Dp = 240.dp) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    Box(modifier.size(diameter).semantics { contentDescription = "Pause, ${pause.left} seconds left" }, contentAlignment = Alignment.Center) {
        if (ShadersSupported) {
            val ink = c.ink.toArgb()
            ShaderCanvas(Shaders.INK_BREATH, Modifier.fillMaxSize(), resolutionScale = 1f) { w, h ->
                setFloatUniform("u_res", w, h)
                setFloatUniform("u_time", if (reduced) 0f else pause.timeSec)
                setFloatUniform("u_breath", if (reduced) 0.5f else pause.breath)
                setFloatUniform("u_progress", pause.progress)
                setColor3("u_ink", ink)
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f * 0.95f
                val stroke = 1.5.dp.toPx()
                drawCircle(c.line, r, style = Stroke(1.dp.toPx()))
                drawArc(
                    c.ink, -90f, 360f * pause.progress, false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val b = if (reduced) 0.5f else pause.breath
                drawCircle(c.muted, r * (0.66f + 0.16f * b), style = Stroke(1.dp.toPx()))
            }
        }
        Txt(if (pause.done) "" else pause.left.toString(), QType.number(64.sp), c.ink)
    }
}

// ---------- Hold to confirm ----------

/** Press and hold for 1.5 s; release early rewinds in 200 ms (DESIGN-NOTES §4). */
@Composable
internal fun HoldButton(label: String, holdingLabel: String, modifier: Modifier = Modifier, onComplete: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(28.dp)
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val complete by rememberUpdatedState(onComplete)
    var holding by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(shape).border(1.dp, c.edge, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    val job = scope.launch {
                        val remaining = ((1f - fill.value) * HOLD_MS).toInt().coerceAtLeast(1)
                        fill.animateTo(1f, tween(remaining, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        complete()
                    }
                    tryAwaitRelease()
                    holding = false
                    if (fill.value < 1f) {
                        job.cancel()
                        scope.launch { fill.animateTo(0f, tween(200, easing = QMotion.EaseOutQuiet)) }
                    }
                })
            }
            .semantics {
                role = Role.Button
                // Screen readers cannot hold; a double tap ends the session.
                onClick(label) { complete(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fill.value.coerceIn(0f, 1f))
                .background(c.ink.copy(alpha = 0.22f)),
        )
        Txt(if (holding) holdingLabel else label, QType.body(), c.ink)
    }
}

private const val HOLD_MS = 1500

// ---------- Slide to confirm ----------

/** A thumb dragged to the end of the track confirms. Short of 85% it springs back. */
@Composable
internal fun SlideToConfirm(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onConfirm: () -> Unit) {
    val c = Q.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirm)
    val offset = remember { Animatable(0f) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    val thumb = 52.dp
    val pad = 4.dp
    val maxPx = (trackPx - with(density) { (thumb + pad * 2).toPx() }).coerceAtLeast(1f)
    var confirmed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier.fillMaxWidth().height(60.dp).onSizeChanged { trackPx = it.width.toFloat() }
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(shape).border(1.dp, c.edge, shape)
            .semantics {
                role = Role.Button
                onClick(label) { if (enabled && !confirmed) { confirmed = true; confirm() }; true }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (confirmed) return@detectHorizontalDragGestures
                            if (offset.value >= maxPx * 0.85f) {
                                confirmed = true
                                scope.launch {
                                    offset.animateTo(maxPx, QMotion.Snappy)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    confirm()
                                }
                            } else scope.launch { offset.animateTo(0f, QMotion.Return) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, QMotion.Return) } },
                        onHorizontalDrag = { ch, dx ->
                            ch.consume()
                            if (!confirmed) scope.launch { offset.snapTo((offset.value + dx).coerceIn(0f, maxPx)) }
                        },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val fade = 1f - (offset.value / maxPx).coerceIn(0f, 1f)
        Txt(label, QType.body(), c.muted, Modifier.graphicsLayer { alpha = fade })
        Box(
            Modifier.align(Alignment.CenterStart).padding(pad)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb).clip(CircleShape).background(c.ink),
            contentAlignment = Alignment.Center,
        ) {
            val onInk = c.onInk
            Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
                val w = 1.8.dp.toPx()
                drawLine(onInk, Offset(0f, 0f), Offset(size.width, size.height / 2), w, StrokeCap.Round)
                drawLine(onInk, Offset(size.width, size.height / 2), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }
}

// ---------- Discrete slider ----------

@Composable
internal fun DiscreteSlider(count: Int, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier, describe: (Int) -> String) {
    val c = Q.colors
    val haptic = LocalHapticFeedback.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onIndex)
    val density = LocalDensity.current
    val inset = with(density) { 14.dp.toPx() }
    fun pick(x: Float) {
        val usable = (widthPx - inset * 2).coerceAtLeast(1f)
        val i = (((x - inset) / usable) * (count - 1)).roundToInt().coerceIn(0, count - 1)
        if (i != current) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(i)
        }
    }
    val fraction = if (count <= 1) 0f else index / (count - 1f)
    val thumbX by animateDpAsState(
        with(density) { (inset + (widthPx - inset * 2) * fraction).toDp() }, spring(dampingRatio = 0.9f, stiffness = 900f), label = "thumb",
    )
    Box(
        modifier.fillMaxWidth().height(48.dp).onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(count) { detectTapGestures { pick(it.x) } }
            .pointerInput(count) { detectHorizontalDragGestures { ch, _ -> ch.consume(); pick(ch.position.x) } }
            .semantics {
                stateDescription = describe(index)
                contentDescription = "Duration"
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            drawLine(c.edge, Offset(inset, y), Offset(size.width - inset, y), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(c.ink, Offset(inset, y), Offset(inset + (size.width - inset * 2) * fraction, y), 1.5.dp.toPx(), StrokeCap.Round)
            for (i in 0 until count) {
                val x = inset + (size.width - inset * 2) * (i / (count - 1f))
                drawCircle(if (i <= index) c.ink else c.edge, 2.dp.toPx(), Offset(x, y))
            }
        }
        Box(
            Modifier.offset(x = thumbX - 14.dp).align(Alignment.CenterStart).size(28.dp).clip(CircleShape).background(c.ink),
        )
    }
}

// ---------- Lists and settings rows ----------

@Composable
internal fun FocusScreen(title: String, onBack: () -> Unit, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    val c = Q.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item {
                Box(
                    Modifier.padding(start = 8.dp, top = 4.dp).size(44.dp).plainClick(onClick = onBack)
                        .semantics { contentDescription = "Back"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { Chevron(c.ink, left = true) }
                Txt(title, QType.title(40.sp), c.ink, Modifier.padding(horizontal = ListGutter).padding(top = 8.dp, bottom = 8.dp))
                if (subtitle != null) Txt(subtitle, QType.secondary(), c.muted, Modifier.padding(horizontal = ListGutter).padding(bottom = 8.dp))
            }
            content()
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Txt(title, QType.title(28.sp), Q.colors.ink, Modifier.padding(horizontal = ListGutter).padding(top = 36.dp, bottom = 8.dp))
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Txt(text, QType.secondary(), Q.colors.muted, modifier.padding(horizontal = ListGutter).padding(top = 8.dp, bottom = 12.dp))
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = ListGutter).fillMaxWidth().height(1.dp).background(Q.colors.line))
}

@Composable
internal fun RowBase(onClick: (() -> Unit)?, minHeight: Dp = RowHeight, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight)
                .then(if (onClick != null) Modifier.plainClick(onClick = onClick) else Modifier)
                .padding(horizontal = ListGutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        Hairline()
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    RowBase(onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Column(Modifier.weight(1f).graphicsLayer { alpha = if (enabled) 1f else 0.4f }) {
            Txt(title, QType.body(), Q.colors.ink)
            if (subtitle != null) Txt(subtitle, QType.secondary(14.sp), Q.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        QSwitch(checked, Modifier.semantics { stateDescription = if (checked) "On" else "Off" })
    }
}

@Composable
internal fun QSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Q.colors
    val track by animateColorAsState(if (checked) c.ink else Color.Transparent, label = "track")
    val thumb by animateColorAsState(if (checked) c.bg else c.ink, label = "thumb")
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, label = "thumbX")
    Box(modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(46.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(track)
                .border(1.5.dp, if (checked) c.ink else c.edge, RoundedCornerShape(14.dp)),
        ) {
            Box(Modifier.padding(4.dp).offset(x = x).size(20.dp).clip(RoundedCornerShape(10.dp)).background(thumb))
        }
    }
}

@Composable
internal fun NavRow(title: String, value: String = "", subtitle: String? = null, onClick: () -> Unit) {
    RowBase(onClick) {
        Column(Modifier.weight(1f)) {
            Txt(title, QType.body(), Q.colors.ink)
            if (subtitle != null) Txt(subtitle, QType.secondary(14.sp), Q.colors.muted)
        }
        if (value.isNotEmpty()) Txt(value, QType.secondary(), Q.colors.muted, Modifier.padding(start = 12.dp))
        Box(Modifier.padding(start = 8.dp)) { Chevron(Q.colors.edge, left = false) }
    }
}

@Composable
internal fun StepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, subtitle: String? = null) {
    RowBase(onClick = null) {
        Column(Modifier.weight(1f)) {
            Txt(title, QType.body(), Q.colors.ink)
            if (subtitle != null) Txt(subtitle, QType.secondary(14.sp), Q.colors.muted)
        }
        StepButton(plus = false, label = "Less", onClick = onMinus)
        Txt(value, QType.secondary(), Q.colors.ink, Modifier.width(76.dp), TextAlign.Center)
        StepButton(plus = true, label = "More", onClick = onPlus)
    }
}

@Composable
internal fun StepButton(plus: Boolean, label: String, onClick: () -> Unit) {
    val color = Q.colors.ink
    Box(
        Modifier.size(44.dp).pressable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = 1.5.dp.toPx()
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
            if (plus) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
        }
    }
}

@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Q.colors
    Row(
        Modifier.padding(horizontal = ListGutter).fillMaxWidth().height(44.dp)
            .border(1.dp, c.edge, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) c.ink else Color.Transparent)
                    .plainClick { onSelect(i) }
                    .semantics { role = Role.Tab; if (on) stateDescription = "Selected" },
                contentAlignment = Alignment.Center,
            ) { Txt(label, QType.secondary(14.sp), if (on) c.bg else c.ink, maxLines = 1) }
        }
    }
}

@Composable
internal fun Chevron(color: Color, left: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 9.dp, height = 16.dp)) {
        val w = 1.6.dp.toPx()
        val a = if (left) size.width else 0f
        val b = if (left) 0f else size.width
        drawLine(color, Offset(a, 0f), Offset(b, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(b, size.height / 2), Offset(a, size.height), w, StrokeCap.Round)
    }
}

@Composable
internal fun SearchField(value: String, placeholder: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Q.colors
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = QType.body().copy(color = c.ink), cursorBrush = SolidColor(c.ink),
        modifier = modifier.padding(horizontal = ListGutter).fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(14.dp)).background(c.raised).border(1.dp, c.edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(placeholder, QType.body(), c.muted)
                inner()
            }
        },
    )
}

@Composable
internal fun LineField(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = QType.body(),
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    val c = Q.colors
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = singleLine,
        textStyle = style.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .drawBehind { drawLine(c.edge, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx()) }
            .padding(vertical = 10.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(placeholder, style, c.muted)
                inner()
            }
        },
    )
}

/** A selectable app row: label, a muted line, and a round check. */
@Composable
internal fun AppCheckRow(label: String, detail: String?, checked: Boolean, onToggle: () -> Unit) {
    val c = Q.colors
    RowBase(onClick = onToggle) {
        Column(Modifier.weight(1f)) {
            Txt(label, QType.body(), c.ink, maxLines = 1)
            if (detail != null) Txt(detail, QType.secondary(14.sp), c.muted)
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (checked) c.ink else Color.Transparent)
                .border(1.5.dp, if (checked) c.ink else c.edge, CircleShape)
                .semantics { stateDescription = if (checked) "Chosen" else "Not chosen" },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                val onInk = c.onInk
                Canvas(Modifier.size(11.dp)) {
                    val w = 1.6.dp.toPx()
                    drawLine(onInk, Offset(0f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.9f), w, StrokeCap.Round)
                    drawLine(onInk, Offset(size.width * 0.38f, size.height * 0.9f), Offset(size.width, size.height * 0.1f), w, StrokeCap.Round)
                }
            }
        }
    }
}

// ---------- Time and weekdays ----------

/** A simple time picker: hour and minute in Fraunces, each with step buttons (minutes in 5s). */
@Composable
internal fun TimePicker(minuteOfDay: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Q.colors
    val h = (minuteOfDay / 60) % 24
    val m = minuteOfDay % 60
    fun set(hour: Int, minute: Int) = onChange(((hour + 24) % 24) * 60 + ((minute + 60) % 60))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepButton(plus = true, label = "Later hour") { set(h + 1, m) }
            Txt("%02d".format(h), QType.number(48.sp), c.ink)
            StepButton(plus = false, label = "Earlier hour") { set(h - 1, m) }
        }
        Txt(":", QType.number(48.sp), c.muted, Modifier.padding(horizontal = 6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepButton(plus = true, label = "Later minute") { set(if (m + 5 >= 60) h + 1 else h, (m / 5 * 5) + 5) }
            Txt("%02d".format(m), QType.number(48.sp), c.ink)
            StepButton(plus = false, label = "Earlier minute") { set(if (m - 5 < 0) h - 1 else h, ((m + 4) / 5 * 5) - 5) }
        }
    }
}

private val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
private val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
internal fun WeekdayChips(days: Set<java.time.DayOfWeek>, onChange: (Set<java.time.DayOfWeek>) -> Unit) {
    val c = Q.colors
    Row(Modifier.padding(horizontal = ListGutter).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        java.time.DayOfWeek.entries.forEachIndexed { i, d ->
            val on = d in days
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (on) c.ink else Color.Transparent)
                    .border(1.dp, if (on) c.ink else c.edge, CircleShape)
                    .pressable { onChange(if (on) days - d else days + d) }
                    .semantics { contentDescription = dayNames[i]; stateDescription = if (on) "On" else "Off"; role = Role.Checkbox },
                contentAlignment = Alignment.Center,
            ) { Txt(dayLetters[i], QType.secondary(15.sp), if (on) c.onInk else c.ink) }
        }
    }
}
