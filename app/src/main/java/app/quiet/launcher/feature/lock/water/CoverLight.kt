package app.quiet.launcher.feature.lock.water

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlin.math.max
import kotlin.math.min

/*
 * While a song plays on the Water lock, the cover shapes the painting behind it (the painting stays):
 *  1. a gentle colour grade toward the cover, like a gallery light gel (luminance kept, mix <= 22%);
 *  2. the caustics and glints under the water take the cover's light colours, toned so never neon;
 *  3. the floating cover casts soft pools of its colours onto the painting, breathing with the beat.
 * Swatch order (NowPlaying.palette, paintingPalette): 0 dark muted, 1 vibrant, 2 muted, 3 light vibrant.
 */

/** Strongest grade (fraction of the gel mixed in). */
private const val GRADE_MAX = 0.22f

/** A swatch with its saturation capped and its lightness set: cover colours made gentle. */
private fun toned(c: Int, satMax: Float, light: Float): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(c, hsl)
    hsl[1] = min(hsl[1], satMax)
    hsl[2] = light
    return ColorUtils.HSLToColor(hsl)
}

private fun lumOf(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

/**
 * The gel: every pixel keeps its luminance, and [amount] of it is re-toned toward the cover's
 * vibrant hue (a warm cover makes golden hour, a blue one moonlight). Null when there is nothing to do.
 */
internal fun gradeFilter(cover: IntArray?, amount: Float): ColorFilter? {
    if (cover == null || cover.size < 4 || amount <= 0.002f) return null
    val gel = Color(toned(lerp(Color(cover[1]), Color(cover[3]), 0.25f).toArgb(), 0.55f, 0.55f))
    val l = max(lumOf(gel.red, gel.green, gel.blue), 0.05f)
    val tr = gel.red / l; val tg = gel.green / l; val tb = gel.blue / l
    val m = (GRADE_MAX * amount).coerceIn(0f, GRADE_MAX)
    val k = 1f - m
    // out = (1 - m) * c + m * lum(c) * gel / lum(gel): luminance is kept exactly.
    val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                k + m * tr * lr, m * tr * lg, m * tr * lb, 0f, 0f,
                m * tg * lr, k + m * tg * lg, m * tg * lb, 0f, 0f,
                m * tb * lr, m * tb * lg, k + m * tb * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

/**
 * The palette for the water engine: the painting's depths (absorption stays the painting's own), with
 * the light swatch (the engine's caustics, glints and near-surface glow) moved [amount] of the way to
 * the cover's light vibrant and vibrant colours, toned pastel. The engine derives its meniscus sheen
 * from the same swatch at half white, so it stays a near-neutral sheen at this saturation.
 */
internal fun causticPalette(painting: IntArray?, cover: IntArray?, amount: Float): IntArray? {
    if (cover == null || cover.size < 4 || amount <= 0.002f) return painting
    val base = painting ?: app.quiet.launcher.designsystem.shader.NeutralPalette
    val light = toned(lerp(Color(cover[3]), Color(cover[1]), 0.35f).toArgb(), 0.42f, 0.78f)
    return base.copyOf().also { it[3] = lerp(Color(base[3]), Color(light), amount.coerceIn(0f, 1f)).toArgb() }
}

/** Swatch-by-swatch mix of two palettes (ARGB ints) at [t]. */
private fun blendPalette(a: IntArray, b: IntArray, t: Float): IntArray = IntArray(min(a.size, b.size)) { i ->
    lerp(Color(a[i]), Color(b[i]), t.coerceIn(0f, 1f)).toArgb()
}

/** [target], cross-faded over 1.2 s whenever it changes (a song change); null until there is one. */
@Composable
internal fun rememberBlendedPalette(target: IntArray?): IntArray? {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val mix = remember { Animatable(1f) }
    val reduced = Q.reducedMotion
    LaunchedEffect(target?.contentHashCode()) {
        if (target == null || to?.contentEquals(target) == true) return@LaunchedEffect
        if (to == null) { from = target; to = target; return@LaunchedEffect }
        from = if (mix.value > 0.5f) to else from
        to = target
        mix.snapTo(0f)
        mix.animateTo(1f, tween(if (reduced) 0 else 1200, easing = FastOutSlowInEasing))
    }
    val f = from; val t = to
    return if (f == null || t == null || mix.value >= 0.999f) t else blendPalette(f, t, mix.value)
}

/**
 * The floating cover's light on the painting: two soft pools in its colours, the stronger one around
 * the raft, a wider and fainter one spilling under it into the water. It breathes with the orb's beat
 * (small amplitude) while the song plays. Drawn in the world layer, so the water bends it; it adds no
 * frames of its own (it redraws on the frames the water and the orb already draw).
 */
@Composable
internal fun CoverGlow(host: WaterHost, driver: WaterLockDriver, cover: IntArray?, rect: () -> Rect?, amount: () -> Float) {
    if (cover == null || cover.size < 4) return
    val a = Color(toned(cover[1], 0.6f, 0.58f))
    val b = Color(toned(cover[3], 0.5f, 0.72f))
    Canvas(Modifier.fillMaxSize()) {
        host.tick
        val k = amount()
        val r = rect() ?: return@Canvas
        if (k <= 0.002f) return@Canvas
        val pulse = 1f + 0.14f * driver.beatNow
        val cx = r.center.x + driver.coverX
        val cy = r.center.y
        val side = r.width
        val r1 = side * 0.95f * (1f + 0.04f * driver.beatNow)
        drawCircle(
            Brush.radialGradient(listOf(a.copy(alpha = 0.30f * k * pulse), a.copy(alpha = 0.10f * k * pulse), Color.Transparent), center = Offset(cx, cy), radius = r1),
            radius = r1, center = Offset(cx, cy), blendMode = BlendMode.Screen,
        )
        val c2 = Offset(cx, cy + side * 0.55f)
        val r2 = side * 1.25f
        drawCircle(
            Brush.radialGradient(listOf(b.copy(alpha = 0.16f * k * pulse), Color.Transparent), center = c2, radius = r2),
            radius = r2, center = c2, blendMode = BlendMode.Screen,
        )
    }
}
