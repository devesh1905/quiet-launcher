package app.quiet.launcher.feature.gesture

import android.app.ActivityOptions
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppKey
import app.quiet.launcher.designsystem.QMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Ink transitions: the tapped name dissolves like ink while the app opens, and is drawn back in when
 * Home returns. The app is started first and never waits for the effect; the effect is a draw-layer
 * shader on that one row (AGSL on API 33+, blur and fade on 31-32, fade below), so rows that are not
 * animating draw exactly as before and nothing runs while idle.
 */
object Ink {
    private const val OUT_MS = 180
    private const val BACK_MS = 200
    /** If Home was not paused by then, the launch did not happen: draw the name back. */
    private const val FAILSAFE_MS = 700L

    internal var activeId by mutableStateOf<String?>(null)
        private set
    internal val progress = Animatable(0f)
    private var job: Job? = null
    private var left = false

    /**
     * Starts [key] at once (with a clip reveal from the row), then dissolves the row on [scope].
     * [animate] false (setting off or reduced motion) skips the effect.
     */
    fun open(view: View, key: AppKey, handle: InkHandle?, animate: Boolean, scope: CoroutineScope) {
        val bounds = handle?.windowBounds()
        var screen: Rect? = null
        var opts: Bundle? = null
        if (bounds != null) {
            val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
            val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
            val dx = onScreen[0] - inWindow[0]; val dy = onScreen[1] - inWindow[1]
            screen = Rect(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy)
            opts = runCatching {
                ActivityOptions.makeClipRevealAnimation(
                    view, bounds.left - inWindow[0], bounds.top - inWindow[1], bounds.width(), bounds.height(),
                ).toBundle()
            }.getOrNull()
        }
        view.context.container.apps.launch(key, screen, opts)
        if (!animate || handle == null) return
        job?.cancel()
        left = false
        activeId = handle.id
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(OUT_MS, easing = QMotion.EaseOutQuiet))
            delay(FAILSAFE_MS - OUT_MS)
            if (!left) back()
        }
    }

    /** Clip reveal from a rect in window px, for launches that have no row (gesture launch). */
    fun revealOptions(view: View, bounds: Rect): Bundle? = runCatching {
        val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
        ActivityOptions.makeClipRevealAnimation(view, bounds.left - inWindow[0], bounds.top - inWindow[1], bounds.width(), bounds.height()).toBundle()
    }.getOrNull()

    private suspend fun back() {
        progress.animateTo(0f, tween(BACK_MS, easing = QMotion.EaseOutQuiet))
        activeId = null
    }

    internal fun onPause() { if (activeId != null) left = true }

    internal fun onResume(scope: CoroutineScope) {
        if (activeId == null || !left) return
        job?.cancel()
        left = false
        job = scope.launch { back() }
    }
}

/** Put once on each screen that opens apps with ink: redraws the name when Home comes back. */
@Composable
fun InkHost() {
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { Ink.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { Ink.onResume(scope) }
}

/** Ties a row to its ink effect and gives its bounds at tap time (nothing is tracked while scrolling). */
class InkHandle(val id: String) {
    internal var node: InkNode? = null

    internal fun windowBounds(): Rect? {
        val n = node?.takeIf { it.isAttached } ?: return null
        val b = runCatching { n.requireLayoutCoordinates().boundsInWindow() }.getOrNull() ?: return null
        if (b.width <= 0f || b.height <= 0f) return null
        return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
    }
}

/** The row that dissolves. Draws straight through unless this row is the one animating. */
fun Modifier.inkLabel(handle: InkHandle): Modifier = this then InkElement(handle)

private data class InkElement(val handle: InkHandle) : ModifierNodeElement<InkNode>() {
    override fun create() = InkNode(handle)
    override fun update(node: InkNode) {
        if (node.handle !== handle) { node.handle.node = null; node.handle = handle; handle.node = node; node.invalidateDraw() }
    }
}

internal class InkNode(var handle: InkHandle) : Modifier.Node(), DrawModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() { handle.node = this }
    override fun onDetach() {
        if (handle.node === this) handle.node = null
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun ContentDrawScope.draw() {
        // Both reads are snapshot reads, so only the draw phase is invalidated while the effect runs.
        val p = if (Ink.activeId == handle.id) Ink.progress.value else 0f
        if (p <= 0.001f) { drawContent(); return }
        if (p >= 0.999f) return
        val l = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
        l.record { this@draw.drawContent() }
        val dp = density
        // The ink leaves from near the start of the name, where the finger usually is.
        val fx = if (Build.VERSION.SDK_INT >= 33) InkShader.effect(size.width, size.height, min(size.width * 0.2f, 90 * dp), size.height / 2, p) else null
        if (fx != null) {
            l.alpha = 1f
            l.translationY = 0f
            l.renderEffect = fx
        } else {
            l.alpha = 1f - p
            l.translationY = -6 * dp * p
            l.renderEffect = if (Build.VERSION.SDK_INT >= 31) BlurEffect(8 * dp * p, 5 * dp * p, TileMode.Decal) else null
        }
        drawLayer(l)
    }
}

@RequiresApi(33)
private object InkShader {
    // Noise threshold that eats the glyphs outward from the origin, while the remaining ink curls and lifts.
    private const val SRC = """
        uniform shader content;
        uniform float2 size;
        uniform float2 origin;
        uniform float progress;
        float hash(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
        float noise(float2 p) {
            float2 i = floor(p); float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
        }
        half4 main(float2 xy) {
            float p = progress;
            float d = length((xy - origin) * float2(1.0, 1.6)) / max(size.x, 1.0);
            float n = noise(xy / 6.0) * 0.6 + noise(xy / 21.0) * 0.4;
            float2 flow = float2(noise(xy / 17.0 + p * 3.0) - 0.5, noise(xy / 17.0 + 9.1 - p * 3.0) - 0.5);
            half4 col = content.eval(xy + flow * 26.0 * p + float2(0.0, 16.0 * p * p));
            float t = p * 1.8 - 0.2 - d * 0.4;
            float keep = smoothstep(t - 0.12, t + 0.12, n);
            return col * keep * (1.0 - smoothstep(0.85, 1.0, p));
        }
    """
    // Compiled on first use (after the first launch has already started); null if the device rejects it.
    private val shader by lazy { runCatching { RuntimeShader(SRC) }.getOrNull() }

    fun effect(w: Float, h: Float, ox: Float, oy: Float, p: Float): androidx.compose.ui.graphics.RenderEffect? {
        val s = shader ?: return null
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("origin", ox, oy)
        s.setFloatUniform("progress", p)
        // A RenderEffect snapshots the uniforms, so one is made per frame, only while the ~11-frame effect runs.
        return android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }
}
