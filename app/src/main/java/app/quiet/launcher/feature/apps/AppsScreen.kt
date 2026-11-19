package app.quiet.launcher.feature.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.feature.gesture.GesturePrefs
import app.quiet.launcher.feature.gesture.Ink
import app.quiet.launcher.feature.gesture.InkHandle
import app.quiet.launcher.feature.gesture.InkHost
import app.quiet.launcher.feature.gesture.inkLabel
import kotlinx.coroutines.launch
import java.text.Normalizer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.LocalWater
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.rememberGlass
import app.quiet.launcher.feature.underwater.GlassKeyboard
import app.quiet.launcher.feature.underwater.GlassKeyboardHeight
import app.quiet.launcher.feature.underwater.GlassKeysField
import app.quiet.launcher.feature.underwater.rememberTouchExploration
import app.quiet.launcher.feature.underwater.rigidGlass
import app.quiet.launcher.feature.underwater.smooth
import app.quiet.launcher.feature.underwater.worldRect
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface Row {
    data class Letter(val letter: Char) : Row
    data class App(val app: AppEntry) : Row
}

private fun fold(s: String) =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private fun sectionOf(label: String): Char {
    val c = fold(label).firstOrNull() ?: '#'
    return if (c in 'a'..'z') c.uppercaseChar() else '#'
}

/**
 * All apps: text only. Search at the top (Enter opens the first match), letter sections,
 * a 44 dp A–Z rail, hidden apps left out. Long press opens the quiet action sheet.
 */
@Composable
fun AppsScreen(
    isActive: Boolean,
    focusSearch: Boolean,
    onFocusConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
) {
    val ctx = LocalContext.current
    val c = Q.colors
    val container = ctx.container
    val apps by container.apps.apps.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val gesture by remember { GesturePrefs.get(ctx) }.state.collectAsStateWithLifecycle()
    val inkOn = gesture.ink && !Q.reducedMotion
    InkHost()
    // Quiet Underwater: the glass keyboard instead of the system one. TalkBack, or its keyboard key,
    // brings the system keyboard back (the key: until the page is left).
    val waterOn = LocalWater.current != null
    val talkBack = if (waterOn) rememberTouchExploration() else false
    var sysKeys by remember { mutableStateOf(false) }
    val glassKeys = waterOn && !talkBack && !sysKeys
    var keysOpen by remember { mutableStateOf(false) }
    BackHandler { query = ""; keyboard?.hide(); onBack() }
    BackHandler(enabled = keysOpen) { keysOpen = false }

    // Focus only while this page is in front: focusing off-screen would pull the pager here.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Put the keyboard away when the user starts browsing instead of typing.
    fun dismissKeyboard() { keyboard?.hide(); focusManager.clearFocus(); keysOpen = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling) dismissKeyboard() }
    }
    LaunchedEffect(isActive, focusSearch) {
        if (isActive && (focusSearch || settings.autoKeyboard)) {
            if (glassKeys) keysOpen = true
            else {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
        } else if (!isActive) {
            query = ""
            focusManager.clearFocus()
            keyboard?.hide()
            keysOpen = false; sysKeys = false
        }
        if (focusSearch) onFocusConsumed()
    }
    // The glass keyboard's keyboard key: the field is composed on the next frame, then focused.
    LaunchedEffect(sysKeys) {
        if (sysKeys) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    val visible = remember(apps) { apps.filter { !it.hidden } }
    val q = fold(query.trim())
    val filtered = remember(visible, q) {
        if (q.isEmpty()) visible
        else visible.filter { fold(it.label).contains(q) }
            .sortedBy { if (fold(it.label).startsWith(q)) 0 else 1 }
    }
    val rows = remember(filtered, q) {
        if (q.isNotEmpty()) filtered.map { Row.App(it) }
        else buildList<Row> {
            var last: Char? = null
            filtered.forEach { a ->
                val s = sectionOf(a.label)
                if (s != last) { add(Row.Letter(s)); last = s }
                add(Row.App(a))
            }
        }
    }
    val letterIndex = remember(rows) {
        rows.withIndex().filter { it.value is Row.Letter }.associate { (it.value as Row.Letter).letter to it.index }
    }

    // Quiet Underwater (null = classic): the page's glass only while the page is on screen (the
    // pager composes it beside Home), faded with the page.
    val water = LocalWater.current
    val pageK = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var onScreen by remember { mutableStateOf(false) }
    val pageAlpha: () -> Float = pageAlpha@{
        val k = pageK[0]
        if (water == null || k == null || !k.isAttached || water.w <= 0f) return@pageAlpha 1f
        (1f - abs(water.toWorld(k).x) / water.w).coerceIn(0f, 1f)
    }
    var slabShown by remember { mutableStateOf(false) }
    LaunchedEffect(q.isNotEmpty()) {
        if (q.isNotEmpty()) slabShown = true else { kotlinx.coroutines.delay(420); slabShown = false }
    }

    Column(
        Modifier.fillMaxSize()
            .then(
                if (water != null) Modifier.onGloballyPositioned { k ->
                    pageK[0] = k
                    val x = water.toWorld(k).x
                    val on = abs(x) < k.size.width * 0.995f
                    if (on != onScreen) onScreen = on
                } else Modifier
            )
            .windowInsetsPadding(WindowInsets.systemBars).imePadding()
            .then(if (water != null && keysOpen) Modifier.padding(bottom = GlassKeyboardHeight) else Modifier)
    ) {
        if (water != null) GlassSearchBar(
            host = water, glassOn = onScreen, pageAlpha = pageAlpha,
            query = query, onQuery = { query = it }, focus = focus,
            glassKeys = glassKeys, keysOpen = keysOpen,
            onGo = { filtered.firstOrNull()?.let { container.apps.launch(it.key); query = "" } },
            onTap = { if (glassKeys) keysOpen = true else { runCatching { focus.requestFocus() }; keyboard?.show() } },
            onSettings = { dismissKeyboard(); onOpenSettings() },
        ) else
        // Search field: 14 dp radius, control edge at 3:1 (DESIGN-NOTES §3).
        Row(
            Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, c.edge, RoundedCornerShape(14.dp))
                .background(c.raised, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QIconView(QIcon.Search, c.muted, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) BasicText("Search", style = QType.body(17.sp).copy(color = c.muted))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = QType.body(17.sp).copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        filtered.firstOrNull()?.let { container.apps.launch(it.key); query = "" }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Box(Modifier.size(44.dp).pressable { dismissKeyboard(); onOpenSettings() }, contentAlignment = Alignment.CenterEnd) {
                QIconView(QIcon.Settings, c.muted, size = 20.dp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Underwater: search results sit on a slab of smoked glass that springs to their count.
            val slabOn = water != null && onScreen && slabShown
            if (slabOn) ResultsSlab(water!!, rows = if (rows.isEmpty()) 1 else rows.size, showing = q.isNotEmpty(), pageAlpha = pageAlpha)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 52.dp),
                contentPadding = PaddingValues(top = if (slabOn) SLAB_PAD else 0.dp, bottom = 32.dp),
            ) {
                items(rows, key = { r -> if (r is Row.App) r.app.key.id else "L" + (r as Row.Letter).letter }) { r ->
                    when (r) {
                        is Row.Letter -> BasicText(
                            r.letter.toString(), style = QType.small(13.sp).copy(color = c.muted),
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                        )
                        is Row.App -> {
                          val ink = remember(r.app.key.id) { InkHandle(r.app.key.id) }
                          Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .inkLabel(ink)
                                .pressable(onLongPress = { onAppLongPress(r.app) }) {
                                    Ink.open(view, r.app.key, ink, inkOn, scope); query = ""; keysOpen = false
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(r.app.label, style = QType.row().copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (r.app.isWorkProfile) {
                                    Spacer(Modifier.width(10.dp))
                                    BasicText("work", style = QType.small(12.sp).copy(color = c.muted))
                                }
                            }
                          }
                        }
                    }
                }
                if (rows.isEmpty() && q.isNotEmpty()) {
                    item { BasicText("Nothing called “$query”.", style = QType.secondary().copy(color = c.muted), modifier = Modifier.padding(top = 12.dp)) }
                }
            }
            if (q.isEmpty() && letterIndex.isNotEmpty()) {
                // The section of the first visible row stays lit at rest.
                val current by remember(rows) {
                    derivedStateOf {
                        val i = listState.firstVisibleItemIndex
                        rows.getOrNull(i)?.let { r -> if (r is Row.Letter) r.letter else sectionOf((r as Row.App).app.label) }
                    }
                }
                AlphabetScroller(
                    letters = letterIndex.keys.toList(),
                    current = current,
                    onScrub = { l -> dismissKeyboard(); letterIndex[l]?.let { idx -> scope.launch { listState.scrollToItem(idx) } } },
                    onTap = { l -> dismissKeyboard(); letterIndex[l]?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    glassOn = onScreen,
                    glassAlpha = pageAlpha,
                )
            }
        }
    }

    // Underwater: the glass keyboard (drawn above the world; it follows the page's slide and fade).
    if (water != null) GlassKeyboard(
        host = water,
        open = keysOpen && glassKeys && onScreen,
        alpha = pageAlpha,
        shiftX = { pageK[0]?.takeIf { it.isAttached }?.let { water.toWorld(it).x } ?: 0f },
        onType = { ch -> query += ch },
        onUndoType = { query = query.dropLast(1) },
        onDelete = { query = query.dropLast(1) },
        onGo = { filtered.firstOrNull()?.let { container.apps.launch(it.key); query = ""; keysOpen = false } },
        onVoice = { query = it },
        onSystemKeyboard = { keysOpen = false; sysKeys = true },
        onDismiss = { keysOpen = false },
    )
}

// ---------------------------------------------------------------- Quiet Underwater: search on glass

/** The search pill: clear glass, a narrow dome so the type in its flat middle stays crisp. */
private val PillGlass = GlassStyle(corner = 26.dp, bevel = 12.dp)
/** The results slab: smoked clear glass (frost would blur the rows, which are drawn under it). */
private val SlabGlass = GlassStyle(corner = 24.dp, bevel = 12.dp)
private val SLAB_PAD = 8.dp
private val PillFill = Color(0x38000000)
private val SlabSmoke = Color(0x80080808)

/**
 * The search field as a glass pill that hugs what you type: its width springs (a slight liquid
 * overshoot) to the text, a little wider while focused, up to the full row. Touching it clinks;
 * opening search sends a refraction ring out from the pill across the page.
 */
@Composable
private fun GlassSearchBar(
    host: WaterHost, glassOn: Boolean, pageAlpha: () -> Float,
    query: String, onQuery: (String) -> Unit, focus: FocusRequester,
    glassKeys: Boolean, keysOpen: Boolean,
    onGo: () -> Unit, onTap: () -> Unit, onSettings: () -> Unit,
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val style = QType.body(17.sp)
    val measurer = rememberTextMeasurer()
    val id = if (glassOn) rememberGlass(host, PillGlass) else -1
    val pillK = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var fieldFocused by remember { mutableStateOf(false) }
    val focused = fieldFocused || (glassKeys && keysOpen)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Glass keyboard: search opens with the same ring of refraction from the pill.
    LaunchedEffect(glassKeys && keysOpen) {
        if (glassKeys && keysOpen) {
            val k = pillK[0]
            if (k != null && k.isAttached) {
                val r = host.worldRect(k)
                host.ring((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, 1f)
            }
        }
    }

    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val maxW = constraints.maxWidth.toFloat()
            val textW = remember(query) { measurer.measure(query.ifEmpty { "Search" }, style).size.width.toFloat() }
            val target = with(density) {
                // padding 16 + icon 20 + gap 12 + text + room for the next letters and the cursor + padding 16
                val chrome = (16 + 20 + 12 + 16).dp.toPx()
                val room = (if (focused) 40 else 18).dp.toPx()
                (chrome + textW + room).coerceIn(minOf(132.dp.toPx(), maxW), maxW)
            }
            val width = remember { Animatable(target) }
            LaunchedEffect(target) {
                if (reduced) width.snapTo(target) else width.animateTo(target, spring(dampingRatio = 0.58f, stiffness = 340f))
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .layout { m, cons ->
                        val w = width.value.roundToInt().coerceIn(0, cons.maxWidth)
                        val pl = m.measure(Constraints.fixed(w, cons.maxHeight))
                        layout(w, cons.maxHeight) { pl.place(0, 0) }
                    }
                    .onGloballyPositioned { pillK[0] = it }
                    .rigidGlass(host, id, alpha = { pageAlpha() })
                    .drawBehind { drawRoundRect(PillFill, cornerRadius = CornerRadius(size.height / 2f)) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            host.feel(Feel.Clink)
                            onTap()
                        }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QIconView(QIcon.Search, c.muted, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) BasicText("Search", style = style.copy(color = c.muted), maxLines = 1)
                        if (glassKeys) GlassKeysField(query, keysOpen, style.copy(color = c.ink), c.ink)
                        else BasicTextField(
                            value = query, onValueChange = onQuery, singleLine = true,
                            textStyle = style.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onGo() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { f ->
                                    if (f.isFocused && !fieldFocused) {
                                        // Search opens: a ring of refraction runs out from the pill.
                                        val k = pillK[0]
                                        if (k != null && k.isAttached) {
                                            val r = host.worldRect(k)
                                            host.ring((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, 1f)
                                        }
                                    }
                                    fieldFocused = f.isFocused
                                },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(44.dp).pressable(onClick = onSettings), contentAlignment = Alignment.CenterEnd) {
            QIconView(QIcon.Settings, c.muted, size = 20.dp)
        }
    }
}

/**
 * Smoked glass under the search results, springing to their number (it melts away when the query
 * clears). Clear, not frosted: the rows are drawn in the world under it, and frost would blur them.
 */
@Composable
private fun ResultsSlab(host: WaterHost, rows: Int, showing: Boolean, pageAlpha: () -> Float) {
    val reduced = Q.reducedMotion
    val id = rememberGlass(host, SlabGlass)
    SideEffect { if (!showing) host.glassAlpha(id, 0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = constraints.maxHeight.toFloat()
        val target = with(androidx.compose.ui.platform.LocalDensity.current) {
            (rows * 50.dp.toPx() + 2 * SLAB_PAD.toPx()).coerceAtMost(maxH)
        }
        val height = remember { Animatable(target) }
        LaunchedEffect(target) {
            if (reduced) height.snapTo(target) else height.animateTo(target, spring(dampingRatio = 0.66f, stiffness = 300f))
        }
        Box(
            Modifier
                .padding(start = 8.dp, end = 40.dp)
                .fillMaxWidth()
                .layout { m, cons ->
                    val h = height.value.roundToInt().coerceIn(0, cons.maxHeight)
                    val pl = m.measure(Constraints.fixed(cons.maxWidth, h))
                    layout(cons.maxWidth, h) { pl.place(0, 0) }
                }
                .rigidGlass(host, id, alpha = { if (showing) pageAlpha() else 0f })
                .drawBehind {
                    host.tick
                    val a = smooth(host.glassAlphaNow(id))
                    drawRoundRect(SlabSmoke.copy(alpha = SlabSmoke.alpha * a), cornerRadius = CornerRadius(24.dp.toPx()))
                },
        )
    }
}
