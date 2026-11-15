package app.quiet.launcher.designsystem.shader

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

val ShadersSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Seconds since start, advanced at most [fps] times per second while [running].
 * Compose pauses the frame clock when the window stops, so nothing renders off-screen.
 */
@Composable
fun rememberShaderTime(running: Boolean, fps: Int = 30): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, fps) {
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
        val frameMs = 1000L / fps
        // Sleep between frames instead of waking on every vsync (the screen runs at 120 Hz).
        while (true) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1e9f }
            kotlinx.coroutines.delay((frameMs - 4).coerceAtLeast(1))
        }
    }
    return time
}

/**
 * Draws an AGSL shader full-size at a reduced internal resolution, then upscales.
 * The offscreen layer is allocated at [resolutionScale] of the final size: the shader runs on
 * a quarter of the pixels at 0.5, which is invisible for these soft fields.
 *
 * [uniforms] is called on the draw thread every frame with the internal width/height in px.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun ShaderCanvas(
    source: String,
    modifier: Modifier = Modifier,
    resolutionScale: Float = 0.5f,
    uniforms: RuntimeShader.(w: Float, h: Float) -> Unit,
) {
    val shader = remember(source) { RuntimeShader(source) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val wPx = constraints.maxWidth * resolutionScale
        val hPx = constraints.maxHeight * resolutionScale
        val inv = 1f / resolutionScale
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopStart) {
            Canvas(
                Modifier
                    .requiredSize(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .graphicsLayer {
                        // Offscreen buffer is sized to the unscaled layer, so the shader runs at low res.
                        compositingStrategy = CompositingStrategy.Offscreen
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = inv
                        scaleY = inv
                    }
                    .align(Alignment.TopStart)
            ) {
                shader.uniforms(size.width, size.height)
                drawRect(brush)
            }
        }
    }
}

/** Convenience: float3 uniform from an ARGB int (sRGB 0..1). */
fun RuntimeShader.setColor3(name: String, argb: Int) {
    setFloatUniform(
        name,
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )
}

@Suppress("unused")
private val Unused = 0.dp
