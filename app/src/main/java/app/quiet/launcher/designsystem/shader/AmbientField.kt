package app.quiet.launcher.designsystem.shader

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Q

/** Neutral warm palette used when nothing plays (keeps the shader valid). */
val NeutralPalette = intArrayOf(0xFF2A2724.toInt(), 0xFF8A7B6A.toInt(), 0xFF5C554D.toInt(), 0xFFCFC4B4.toInt())

/**
 * The signature colour field (DESIGN-NOTES §5 ambient.frag).
 *
 * @param artRect the framed artwork in this composable's local px (top-left origin), or null.
 * @param maxLum luminance cap (0.028 player/lock, 0.014 Home); scaled by [strength].
 * @param glow light spill off the frame; @param pool 0 = fills screen, 1 = pools around the frame.
 * @param glowScale read on the draw thread each frame (a lock player breath), so it never recomposes.
 */
@Composable
fun AmbientField(
    palette: IntArray?,
    artRect: Rect?,
    modifier: Modifier = Modifier,
    maxLum: Float = 0.028f,
    glow: Float = 1f,
    pool: Float = 0.55f,
    strength: Float = 1f,
    running: Boolean = true,
    fallbackArt: Bitmap? = null,
    fps: Int = 24,
    resolution: Float = 0.5f,
    glowScale: () -> Float = { 1f },
) {
    val reduced = Q.reducedMotion
    val light = !Q.colors.isDark
    val target = palette ?: NeutralPalette

    // Palette crossfade over 1.2 s (colour change is information: kept under reduced motion).
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(target.contentHashCode()) {
        if (!to.contentEquals(target)) {
            from = if (mix.value > 0.5f) to else from
            to = target
            mix.snapTo(0f)
            mix.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        }
    }
    val cap by animateFloatAsState(maxLum * strength.coerceIn(0f, 1f), tween(900), label = "cap")
    val presence by animateFloatAsState(if (palette == null) 0f else 1f, tween(900), label = "presence")

    if (strength <= 0f) return

    if (ShadersSupported) {
        val time by rememberShaderTime(running && !reduced, fps = fps)
        ShaderCanvas(Shaders.AMBIENT, modifier.alpha(presence), resolutionScale = resolution) { w, h ->
            setFloatUniform("u_res", w, h)
            setFloatUniform("u_time", if (reduced) 12f else time)
            listOf("u_a0", "u_a1", "u_a2", "u_a3").forEachIndexed { i, n -> setColor3(n, from[i]) }
            listOf("u_b0", "u_b1", "u_b2", "u_b3").forEachIndexed { i, n -> setColor3(n, to[i]) }
            setFloatUniform("u_mix", mix.value)
            setFloatUniform("u_maxLum", cap)
            setFloatUniform("u_minLum", 0.66f)
            setFloatUniform("u_light", if (light) 1f else 0f)
            // Rect: local px (top-left) -> shader px at internal scale with bottom-left origin.
            val scale = resolution
            val r = artRect
            if (r != null) {
                setFloatUniform("u_rect", r.left * scale, h - r.bottom * scale, r.width * scale, r.height * scale)
            } else {
                setFloatUniform("u_rect", 0f, 0f, 0f, 0f)
            }
            setFloatUniform("u_glow", glow * glowScale())
            setFloatUniform("u_pool", pool)
        }
    } else if (fallbackArt != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31–32: the cover itself, heavily blurred and darkened.
        val img = remember(fallbackArt) { fallbackArt.asImageBitmap() }
        Box(modifier.alpha(0.22f * presence * strength)) {
            Image(img, null, Modifier.fillMaxSize().blur(80.dp), contentScale = ContentScale.Crop)
        }
    }
}
