package app.quiet.launcher.feature.underwater

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotateRad
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlin.math.max
import kotlin.math.min

/*
 * The layer ABOVE the world (Quiet Underwater). Everything inside the world layer goes through the
 * water's RenderEffect: a frosted glass body blurs whatever is drawn under it, its own words included.
 * Cards that need crisp type over frost (a notification card, an open folder, a lifted label) put the
 * glass body into the world and draw their tint and words here, on top, in the same world px (this
 * layer fills the same root box as the world, so its origin is the world's origin).
 */

@Stable
internal class WaterTop {
    internal class Entry(val key: Any, val z: Int, val content: @Composable () -> Unit)
    internal val entries = mutableStateListOf<Entry>()

    fun add(e: Entry) {
        val i = entries.indexOfFirst { it.z > e.z }
        if (i < 0) entries.add(e) else entries.add(i, e)
    }
    fun remove(e: Entry) { entries.remove(e) }
}

internal val LocalWaterTop = staticCompositionLocalOf<WaterTop?> { null }

@Composable
internal fun rememberWaterTop(): WaterTop = remember { WaterTop() }

/** Draws what screens put on the top layer. Place it right after the world box, the same size. */
@Composable
internal fun WaterTopLayer(top: WaterTop, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier) {
        for (e in top.entries) key(e.key) { e.content() }
    }
}

/**
 * Shows [content] on the top layer (above the world, not bent or frosted by it) while this is in the
 * composition. Higher [z] draws later. Without a top layer (classic look) nothing is drawn: callers
 * only use this on the underwater path.
 */
@Composable
internal fun OnWaterTop(key: Any, z: Int = 0, content: @Composable () -> Unit) {
    val top = LocalWaterTop.current ?: return
    val current = rememberUpdatedState(content)
    DisposableEffect(top, key) {
        val e = WaterTop.Entry(key, z) { current.value() }
        top.add(e)
        onDispose { top.remove(e) }
    }
}

// ---------------------------------------------------------------- live glass geometry (world px)

/** Centre of body [id] as drawn now (spring offset and bob included). */
internal fun WaterHost.glassCx(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.x[id] else 0f
internal fun WaterHost.glassCy(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.y[id] + sim.glass.bob[id] else 0f
/** Half size as drawn now (squash included). */
internal fun WaterHost.glassHalfW(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.hw[id] * (1f + sim.glass.squash[id]) else 0f
internal fun WaterHost.glassHalfH(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.hh[id] / (1f + sim.glass.squash[id]) else 0f
internal fun WaterHost.glassCorner(id: Int): Float =
    if (id in 0 until sim.glass.n) min(sim.glass.corner[id], min(glassHalfW(id), glassHalfH(id))) else 0f
internal fun WaterHost.glassRotRad(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.rot[id] else 0f

/**
 * Fills glass body [id]'s exact live shape (centre, half size, squash, corner, roll) with [color]:
 * the tint of a frosted card drawn on the top layer, so it hugs the glass through every spring and
 * morph (bubble to card and back). The DrawScope must be at the world origin.
 */
internal fun DrawScope.drawGlassShape(host: WaterHost, id: Int, color: Color, grow: Float = 0f) {
    if (id < 0 || color.alpha <= 0.001f) return
    val hw = host.glassHalfW(id) + grow; val hh = host.glassHalfH(id) + grow
    if (hw < 1f || hh < 1f) return
    val cx = host.glassCx(id); val cy = host.glassCy(id)
    val r = max(0f, host.glassCorner(id) + grow)
    rotateRad(host.glassRotRad(id), Offset(cx, cy)) {
        drawRoundRect(color, Offset(cx - hw, cy - hh), Size(2f * hw, 2f * hh), CornerRadius(r, r))
    }
}
