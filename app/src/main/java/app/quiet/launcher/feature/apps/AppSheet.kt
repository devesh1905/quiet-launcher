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
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.LocalWater
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.rememberGlass
import app.quiet.launcher.feature.underwater.rigidGlass
import app.quiet.launcher.feature.underwater.smooth
import androidx.compose.ui.graphics.drawOutline
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Bottom sheet that tracks the finger: it slides up on a spring, follows a downward drag 1:1,
 * rubber-bands upward, and closes when the projected fling lands past half its height.
 * Never fades, so it is never see-through at rest (DESIGN-NOTES §4).
 */
@Composable
fun QuietSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
    // Quiet Underwater: a sheet opened from inside the world (Home's music and clock sheets) draws on
    // the layer ABOVE it, so the water never bends it and its own frost never blurs its words; only
    // its glass body stays in the world, exactly under the sheet's rect. Sheets already above the
    // world (app actions, overlays) and classic draw in place, unchanged.
    if (LocalWater.current != null && app.quiet.launcher.feature.underwater.LocalWaterTop.current != null) {
        val key = remember { Any() }
        app.quiet.launcher.feature.underwater.OnWaterTop(key, z = 100) {
            SheetBody(offset, heightPx, { heightPx = it }, onDismiss, content)
        }
    } else SheetBody(offset, heightPx, { heightPx = it }, onDismiss, content)
}

@Composable
private fun SheetBody(
    offset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    heightPx: Float, onHeight: (Float) -> Unit,
    onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val c = Q.colors
    val scope = rememberCoroutineScope()
    val progress = (1f - offset.value / heightPx).coerceIn(0f, 1f)
    // Quiet Underwater: a slab of frosted glass sliding over the water (null = classic, unchanged).
    val water = LocalWater.current
    val glassId = if (water != null) rememberGlass(water, SheetGlass) else -1

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(if (water != null) UnderwaterScrim else c.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { onHeight(it.height.toFloat().coerceAtLeast(1f)) }
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .then(
                    if (water != null && glassId >= 0) Modifier.frostedSheet(water, glassId, c.raised)
                    else Modifier.background(c.raised, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                )
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

// ---- Quiet Underwater: the frosted sheet

/** The sheet's glass: a frosted slab, its top edge a soft glass rim (the rest runs on under the screen). */
private val SheetGlass = GlassStyle(corner = 26.dp, bevel = 14.dp, frost = 1f)
/** Lighter than the classic scrim, so the water and painting stay in view around the sheet. */
private val UnderwaterScrim = androidx.compose.ui.graphics.Color(0x73000000)
/**
 * The sheet's own tint over the frost once the glass is in. With the scrim under it, the frosted
 * world shows at <= 23 % of its brightness: ink >= 13:1, muted >= 5:1 (WCAG AA).
 */
private const val SHEET_TINT = 0.8f
private val SheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

/**
 * The sheet's bounds become frosted glass (tracked exactly, so it slides and drags with the sheet),
 * and its fill turns translucent as the frost condenses: opaque until the glass is in, never sharp
 * water behind the type.
 */
private fun Modifier.frostedSheet(host: WaterHost, id: Int, fill: androidx.compose.ui.graphics.Color): Modifier =
    rigidGlass(host, id, extendBottom = 64f * host.dp)
        .drawBehind {
            host.tick
            val a = smooth(host.glassAlphaNow(id))
            drawOutline(SheetShape.createOutline(size, layoutDirection, this), fill.copy(alpha = 1f - (1f - SHEET_TINT) * a))
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
