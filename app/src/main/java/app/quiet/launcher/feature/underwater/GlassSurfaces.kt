package app.quiet.launcher.feature.underwater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.LocalWater
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.rememberGlass
import kotlin.math.max
import kotlin.math.min

/*
 * Glass surfaces for screens above or inside the world (Phase 2): rigid glass that tracks a layout
 * exactly (sheets, overlay panes, the A–Z rod, the search pill), and the frosted overlay pane.
 *
 * Why rigid: a layout-linked body ([app.quiet.launcher.feature.underwater.engine.glass]) chases its
 * anchor on a spring and squashes with its own speed. That is right for floating glass, wrong for a
 * sheet sliding 1000 px in 300 ms: it would lag behind the sheet's edge and stretch 15 %. [placeGlass]
 * puts the body exactly at the bounds with no velocity, so the spring and squash stay at rest, and it
 * runs in the layout pass, before the world's RenderEffect reads the bodies in the draw pass of the
 * same frame: no lag. (See ENGINE-REQUESTS.md: a public rigid placement in the engine would replace it.)
 */

/** Put glass body [id] at exactly this rect (px, world): no spring, no squash or stretch. */
internal fun WaterHost.placeGlass(id: Int, cx: Float, cy: Float, halfW: Float, halfH: Float) {
    val g = sim.glass
    if (id !in 0 until g.n || !g.used[id]) return
    val changed = g.x[id] != cx || g.y[id] != cy || g.hw[id] != halfW || g.hh[id] != halfH
    g.tx[id] = cx; g.ty[id] = cy; g.tw[id] = halfW; g.th[id] = halfH
    g.x[id] = cx; g.y[id] = cy; g.hw[id] = halfW; g.hh[id] = halfH
    g.vx[id] = 0f; g.vy[id] = 0f; g.vhw[id] = 0f; g.vhh[id] = 0f
    g.squash[id] = 0f; g.vsquash[id] = 0f
    if (changed) wake()
}

/** Bounds of [k] in world px (layer transforms included: the pager's page scale). */
internal fun WaterHost.worldRect(k: LayoutCoordinates): FloatArray {
    val a = toWorld(k, Offset.Zero)
    val b = toWorld(k, Offset(k.size.width.toFloat(), k.size.height.toFloat()))
    return floatArrayOf(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
}

/**
 * Makes this node's bounds glass body [id], tracked rigidly. [extendBottom] / [extendSides] grow
 * it past the bounds (px; a sheet's glass runs on under the screen's bottom edge). [alpha] is read
 * at each placement (a page's fade).
 */
internal fun Modifier.rigidGlass(
    host: WaterHost?, id: Int,
    extendBottom: Float = 0f, extendSides: Float = 0f,
    alpha: ((FloatArray) -> Float)? = null,
): Modifier {
    if (host == null || id < 0) return this
    return onGloballyPositioned { k ->
        if (!k.isAttached) return@onGloballyPositioned
        val r = host.worldRect(k)
        val hw = (r[2] - r[0]) / 2f + extendSides
        val hh = (r[3] - r[1] + extendBottom) / 2f
        host.placeGlass(id, (r[0] + r[2]) / 2f, r[1] + hh, hw, hh)
        if (alpha != null) host.glassAlpha(id, alpha(r).coerceIn(0f, 1f))
    }
}

/** Inside a frosted overlay pane: screens there leave their own background clear. */
val LocalOnGlass = compositionLocalOf { false }

/**
 * The background an overlay screen (Settings, Usage, ...) paints under its content: its usual
 * [Q.colors].bg, or clear when it sits on a frosted [GlassPane] (the pane draws the tint).
 */
@Composable
fun overlayScreenBg(): Color =
    if (LocalWater.current != null && LocalOnGlass.current) Color.Transparent else Q.colors.bg

/**
 * How dark the frosted pane's tint is (over the frost) once its glass is in. 0.68 black over the
 * frosted, veiled painting keeps muted type (#9A968E) at >= 4.5:1 and ink at >= 12:1 (WCAG AA).
 */
internal const val PANE_TINT = 0.68f

private val PaneGlass = GlassStyle(corner = 30.dp, bevel = 16.dp, frost = 1f)

/**
 * An overlay page (Settings, Usage, ...). Classic: a Box on the opaque background, as before.
 * Underwater: a pane of frosted glass sliding over the water, the water and painting showing
 * through it blurred; it tracks the pane's slide exactly. [leaving] (going to another overlay)
 * melts the glass away. The tint starts opaque and clears as the frost condenses, so the pane
 * never shows sharp water behind type.
 */
@Composable
fun GlassPane(leaving: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val host = LocalWater.current
    val c = Q.colors
    if (host == null) {
        Box(Modifier.fillMaxSize().background(c.bg), content = content)
        return
    }
    val id = rememberGlass(host, PaneGlass)
    if (id < 0) {
        Box(Modifier.fillMaxSize().background(c.bg), content = content)
        return
    }
    SideEffect { host.glassAlpha(id, if (leaving) 0f else 1f) }
    val bg = c.bg
    Box(
        Modifier
            .fillMaxSize()
            .rigidGlass(host, id)
            .drawBehind {
                host.tick
                val a = host.glassAlphaNow(id)
                drawRect(bg.copy(alpha = 1f - (1f - PANE_TINT) * smooth(a)))
            },
    ) {
        CompositionLocalProvider(LocalOnGlass provides true) { content() }
    }
}

internal fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
