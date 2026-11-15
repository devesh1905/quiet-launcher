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
import kotlinx.coroutines.launch
import java.text.Normalizer

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
    BackHandler { query = ""; keyboard?.hide(); onBack() }

    // Focus only while this page is in front: focusing off-screen would pull the pager here.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(isActive, focusSearch) {
        if (isActive && (focusSearch || settings.autoKeyboard)) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else if (!isActive) {
            query = ""
            focusManager.clearFocus()
            keyboard?.hide()
        }
        if (focusSearch) onFocusConsumed()
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

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
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
            Box(Modifier.size(44.dp).pressable(onClick = onOpenSettings), contentAlignment = Alignment.CenterEnd) {
                QIconView(QIcon.Settings, c.muted, size = 20.dp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 52.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(rows, key = { r -> if (r is Row.App) r.app.key.id else "L" + (r as Row.Letter).letter }) { r ->
                    when (r) {
                        is Row.Letter -> BasicText(
                            r.letter.toString(), style = QType.small(13.sp).copy(color = c.muted),
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                        )
                        is Row.App -> Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .pressable(onLongPress = { onAppLongPress(r.app) }) {
                                    container.apps.launch(r.app.key); query = ""
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
                    onScrub = { l -> letterIndex[l]?.let { idx -> scope.launch { listState.scrollToItem(idx) } } },
                    onTap = { l -> letterIndex[l]?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } } },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                )
            }
        }
    }
}
