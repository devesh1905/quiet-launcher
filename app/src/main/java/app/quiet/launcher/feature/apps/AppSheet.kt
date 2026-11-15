package app.quiet.launcher.feature.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.designsystem.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Bottom sheet that tracks the finger: it slides up on a spring, follows a downward drag 1:1,
 * rubber-bands upward, and closes when the projected fling lands past half its height.
 * Never fades, so it is never see-through at rest (DESIGN-NOTES §4).
 */
@Composable
fun QuietSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Q.colors
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableFloatStateOf(2000f) }
    val offset = remember { Animatable(2000f) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            offset.snapTo(heightPx)
            offset.animateTo(0f, QMotion.Return)
        } else if (shown) {
            offset.animateTo(heightPx, QMotion.Fling)
            shown = false
        }
    }
    if (!shown) return
    BackHandler(onBack = onDismiss)
    val progress = (1f - offset.value / heightPx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .background(c.raised, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { ch, dy ->
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val raw = offset.value + dy
                            scope.launch { offset.snapTo(if (raw < 0) QMotion.rubberBand(raw, heightPx * 0.2f) else raw) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            if (offset.value + QMotion.project(v) > heightPx / 2) onDismiss()
                            else scope.launch { offset.animateTo(0f, QMotion.Return, initialVelocity = v) }
                        },
                    )
                }
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(c.edge, RoundedCornerShape(2.dp)))
            }
            content()
        }
    }
}

@Composable
fun SheetAction(text: String, onClick: () -> Unit) {
    val c = Q.colors
    Box(
        Modifier.fillMaxWidth().height(56.dp).pressable(pressedScale = 0.99f, onClick = onClick)
            .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1f) },
        contentAlignment = Alignment.CenterStart,
    ) { BasicText(text, style = QType.body().copy(color = c.ink)) }
}

/** Long-press actions for one app. */
@Composable
fun AppActionsSheet(app: AppEntry?, onDismiss: () -> Unit, onBlock: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Q.colors
    val repo = ctx.container.apps
    val favs by repo.favourites.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var renaming by remember(app) { mutableStateOf(false) }
    var name by remember(app) { mutableStateOf(app?.label ?: "") }
    // Keep showing the last app while the sheet animates out.
    var last by remember { mutableStateOf(app) }
    if (app != null) last = app
    val a = last

    QuietSheet(visible = app != null, onDismiss = onDismiss) {
        if (a == null) return@QuietSheet
        BasicText(a.label, style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.padding(vertical = 12.dp))
        if (renaming) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    name, { name = it }, singleLine = true,
                    textStyle = QType.body().copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.weight(1f).border(1.dp, c.edge, RoundedCornerShape(14.dp)).padding(14.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.height(48.dp).pressable {
                    scope.launch { repo.rename(a.key, name.takeIf { it.isNotBlank() && it != a.systemLabel }) }
                    onDismiss()
                }, contentAlignment = Alignment.Center) { BasicText("Save", style = QType.body().copy(color = c.ink)) }
            }
            return@QuietSheet
        }
        val isFav = favs.any { it.key == a.key }
        SheetAction(if (isFav) "Remove from Home" else "Add to Home") { scope.launch { repo.setFavourite(a.key, !isFav) }; onDismiss() }
        SheetAction("Block for a while") { onBlock(a.key.packageName); onDismiss() }
        SheetAction("Rename") { renaming = true }
        if (a.label != a.systemLabel) SheetAction("Restore name") { scope.launch { repo.rename(a.key, null) }; onDismiss() }
        SheetAction(if (a.hidden) "Unhide" else "Hide from the list") { scope.launch { repo.setHidden(a.key, !a.hidden) }; onDismiss() }
        SheetAction("App info") { repo.openAppInfo(a.key); onDismiss() }
        SheetAction("Uninstall") { repo.uninstall(a.key); onDismiss() }
        Spacer(Modifier.height(8.dp))
    }
}
