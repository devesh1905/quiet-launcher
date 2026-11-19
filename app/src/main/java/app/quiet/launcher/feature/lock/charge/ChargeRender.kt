package app.quiet.launcher.feature.lock.charge

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import app.quiet.launcher.core.model.ChargeAnim
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.sin

/**
 * A small float texture the simulations write into (RGBA_F16, read with bilinear filtering).
 * Two of them alternate so the render thread never reads the one being written. Each upload
 * nudges the shader's local matrix by a hair so the shader re-snapshots the bitmap's new pixels.
 */
@RequiresApi(33)
internal class FloatTex(val w: Int, val h: Int) {
    private val bmps = Array(2) { Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)) }
    private val shaders = Array(2) { i ->
        BitmapShader(bmps[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    }
    private val buf: ShortBuffer = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val mA = Matrix().apply { setTranslate(1e-3f, 0f) }
    private val mB = Matrix().apply { setTranslate(-1e-3f, 0f) }
    private var cur = 0
    private var flip = 0
    private val one = Half.toHalf(1f)

    fun put(i: Int, r: Float, g: Float = 0.5f, b: Float = 0.5f) {
        val o = i * 4
        buf.put(o, Half.toHalf(r.coerceIn(0f, 1f)))
        buf.put(o + 1, Half.toHalf(g.coerceIn(0f, 1f)))
        buf.put(o + 2, Half.toHalf(b.coerceIn(0f, 1f)))
        buf.put(o + 3, one)
    }

    /** Uploads what was put and returns the shader to bind for this frame. */
    fun upload(): Shader {
        cur = 1 - cur
        buf.rewind()
        bmps[cur].copyPixelsFromBuffer(buf)
        buf.rewind()
        flip++
        shaders[cur].setLocalMatrix(if (flip % 4 < 2) mA else mB)
        return shaders[cur]
    }
}

/** Compiled shaders and their textures; only built on API 33+. */
@RequiresApi(33)
internal class ChargeGpu {
    private fun compile(name: String, src: String): RuntimeShader? = try {
        RuntimeShader(src)
    } catch (e: RuntimeException) {
        Log.w("QuietCharge", "$name shader rejected", e); null
    }

    val flood: RuntimeShader? by lazy { compile("flood", ChargeShaders.flood()) }
    val glass: RuntimeShader? by lazy { compile("glass", ChargeShaders.glass()) }
    val thread: RuntimeShader? by lazy { compile("thread", ChargeShaders.thread()) }
    val clock: RuntimeShader? by lazy { compile("clock", ChargeShaders.clock()) }

    private val floodSurf by lazy { FloatTex(96, 1) }
    private val glassSurf by lazy { FloatTex(48, 1) }
    private val clockSurf by lazy { FloatTex(48, 1) }
    private var hf: FloatTex? = null
    private val bub = FloatArray(18 * 3)
    private val drops = FloatArray(12 * 3)
    private val gBub = FloatArray(16 * 3)
    private val pulses = FloatArray(6)
    // Ribbon for the thread.
    private val verts = FloatArray(40 * 4)
    private val texs = FloatArray(40 * 4)
    val paint = Paint().apply { blendMode = BlendMode.PLUS; isAntiAlias = true }

    private fun RuntimeShader.palette(c: ChargeHost) {
        setFloatUniform("uDk", c.pDk); setFloatUniform("uAc", c.pAc); setFloatUniform("uMd", c.pMd)
        setFloatUniform("uLt", c.pLt); setFloatUniform("uSheen", c.pSheen)
    }

    // ------------------------------------------------------------ flood
    fun floodEffect(c: ChargeHost): RenderEffect? {
        val sh = flood ?: return null
        val s = c.flood
        val g = s.grid ?: return null
        val dp = c.dp
        val amp = 200f * dp
        for (i in 0 until 96) floodSurf.put(i, 0.5f + s.surf.h[i] / amp)
        sh.setInputShader("uSurf", floodSurf.upload())
        val t = hf?.takeIf { it.w == g.gw && it.h == g.gh } ?: FloatTex(g.gw, g.gh).also { hf = it }
        // Gradient and laplacian of the ripple field, per cell, scaled into the texture's range.
        val gain = 0.07f
        val gw = g.gw; val gh = g.gh; val hh = g.h
        for (y in 0 until gh) {
            val row = y * gw
            for (x in 0 until gw) {
                val i = row + x
                val cc = hh[i]
                val l = if (x > 0) hh[i - 1] else cc
                val r = if (x < gw - 1) hh[i + 1] else cc
                val u = if (y > 0) hh[i - gw] else cc
                val d = if (y < gh - 1) hh[i + gw] else cc
                t.put(i, 0.5f + (r - l) * 0.5f * gain * 0.5f, 0.5f + (d - u) * 0.5f * gain * 0.5f, 0.5f + (l + r + u + d - 4f * cc) * gain * 0.5f)
            }
        }
        sh.setInputShader("uHF", t.upload())
        sh.setFloatUniform("uRes", c.w, c.h)
        sh.setFloatUniform("uGrid", gw.toFloat(), gh.toFloat())
        sh.setFloatUniform("uSurfN", 96f)
        sh.setFloatUniform("uSurfAmp", amp)
        sh.setFloatUniform("uWater", c.h * (1f - s.fill[0]))
        sh.setFloatUniform("uPort", c.w / 2f, c.h)
        sh.setFloatUniform("uJet", c.w / 2f, s.tipY, minOf(s.tailY, c.h + 20f * dp), s.jetHw)
        sh.setFloatUniform("uJetOn", if (s.jetT >= 0f) s.jetOn else 0f)
        sh.setFloatUniform("uFlow", s.flow)
        sh.setFloatUniform("uSwirl", s.swirl)
        sh.setFloatUniform("uTime", c.time)
        sh.setFloatUniform("uDp", dp)
        sh.setFloatUniform("uRefr", 34f * dp)
        sh.setFloatUniform("uCaus", 9f)
        for (i in 0 until 18) { bub[i * 3] = s.bx[i]; bub[i * 3 + 1] = s.by[i]; bub[i * 3 + 2] = s.br[i] }
        sh.setFloatUniform("uBub", bub)
        sh.setFloatUniform("uBubOn", if (s.bubblesAlive > 0) 1f else 0f)
        for (i in 0 until 12) { drops[i * 3] = s.dx[i]; drops[i * 3 + 1] = s.dy[i]; drops[i * 3 + 2] = s.dr[i] }
        sh.setFloatUniform("uDrop", drops)
        sh.setFloatUniform("uDrops", if (s.dropsAlive > 0) 1f else 0f)
        // The cover floats when the water reaches it, bobbing with the wave under its centre.
        if (c.hasCover) {
            val r = c.coverRect
            sh.setFloatUniform("uCover", r[0], r[1], r[2], r[3])
            val cx = (r[0] + r[2]) / 2f
            val wy = s.waterY(cx)
            val sub = ((r[3] - wy) / (r[3] - r[1]).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val gh2 = g.heightAt(cx / c.w * gw, (r[1] + r[3]) / 2f / c.h * gh)
            val bobY = (sin(c.time * 1.15f) * 2.2f + (gh2 * 0.35f).coerceIn(-3f, 3f) - s.surf.at(cx / c.w) / dp * 0.08f).coerceIn(-5f, 5f) * dp
            val bobX = (sin(c.time * 0.7f + 1f) * 1.1f + s.tilt() * 6f) * dp
            sh.setFloatUniform("uBob", bobX, bobY, sub)
        } else {
            sh.setFloatUniform("uCover", 0f, 0f, 0f, 0f)
            sh.setFloatUniform("uBob", 0f, 0f, 0f)
        }
        sh.palette(c)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    private fun FloodSim.tilt(): Float = (surf.eq[surf.n - 1] - surf.eq[0]) / w.coerceAtLeast(1f)

    // ------------------------------------------------------------ glass
    fun glassEffect(c: ChargeHost): RenderEffect? {
        val sh = glass ?: return null
        val s = c.glass
        val orb = s.orbMode && c.orbCx >= 0f
        if (!orb && s.body[0] < 0.01f) return null
        val amp = 1.2f
        for (i in 0 until 48) glassSurf.put(i, 0.5f + s.surf.h[i] / amp)
        sh.setInputShader("uSurf", glassSurf.upload())
        sh.setFloatUniform("uRes", c.w, c.h)
        if (orb) {
            sh.setFloatUniform("uC", c.orbCx, c.orbCy)
            sh.setFloatUniform("uR", 30f * c.dp)
            sh.setFloatUniform("uSq", 1f, 1f)
            sh.setFloatUniform("uFillY", s.fillY(s.fill[0], 0.84f))
            sh.setFloatUniform("uMode", 1f)
        } else {
            val b = s.body[0].coerceAtLeast(0.01f)
            val q = s.squash[0]
            sh.setFloatUniform("uC", c.dropCx(), c.dropCy())
            sh.setFloatUniform("uR", c.dropR())
            sh.setFloatUniform("uSq", b * (1f + q), b / (1f + q))
            sh.setFloatUniform("uFillY", s.fillY(s.fill[0]))
            sh.setFloatUniform("uMode", 0f)
        }
        sh.setFloatUniform("uSurfN", 48f)
        sh.setFloatUniform("uSurfAmp", amp)
        sh.setFloatUniform("uLiquid", if (s.fill[0] > 0.002f) 1f else 0f)
        sh.setFloatUniform("uSpark", s.spark)
        sh.setFloatUniform("uTime", c.time)
        sh.setFloatUniform("uDp", c.dp)
        for (i in 0 until 16) { gBub[i * 3] = s.bx[i]; gBub[i * 3 + 1] = s.by[i]; gBub[i * 3 + 2] = s.br[i] }
        sh.setFloatUniform("uBub", gBub)
        sh.palette(c)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    // ------------------------------------------------------------ clock
    fun clockEffect(c: ChargeHost, w: Float, h: Float): RenderEffect? {
        val sh = clock ?: return null
        val s = c.clock
        if (s.presence[0] <= 0.003f) return null
        val dp = c.dp
        val amp = 120f * dp
        for (i in 0 until 48) clockSurf.put(i, 0.5f + s.surf.h[i] / amp)
        sh.setInputShader("uSurf", clockSurf.upload())
        val base = if (c.clockBaseline > 0f) c.clockBaseline else h * 0.8f
        val cap = if (c.clockCap > 0f) c.clockCap else h * 0.6f
        sh.setFloatUniform("uSize", w, h)
        sh.setFloatUniform("uSurfN", 48f)
        sh.setFloatUniform("uSurfAmp", amp)
        // Empty sits a hair under the baseline so 0% shows no liquid at all.
        sh.setFloatUniform("uFillY", base + 2f * dp - s.fill[0] * (cap + 4f * dp))
        sh.setFloatUniform("uCap", cap)
        sh.setFloatUniform("uPresence", s.presence[0].coerceIn(0f, 1f))
        sh.setFloatUniform("uShine", s.shine)
        sh.setFloatUniform("uTime", s.time)
        sh.setFloatUniform("uDp", dp)
        sh.palette(c)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }

    // ------------------------------------------------------------ thread
    fun drawThread(c: ChargeHost, canvas: android.graphics.Canvas) {
        val sh = thread ?: return
        val t = c.thread
        if (!t.visible) return
        val n = t.n
        val hw = 16f * c.dp
        var len = 0f
        for (i in 0 until n) {
            val i0 = if (i > 0) i - 1 else i
            val i1 = if (i < n - 1) i + 1 else i
            var tx = t.x[i1] - t.x[i0]; var ty = t.y[i1] - t.y[i0]
            val tl = kotlin.math.sqrt(tx * tx + ty * ty)
            if (tl < 1e-3f) { tx = 0f; ty = -1f } else { tx /= tl; ty /= tl }
            val nx = -ty * hw; val ny = tx * hw
            val o = i * 4
            verts[o] = t.x[i] + nx; verts[o + 1] = t.y[i] + ny
            verts[o + 2] = t.x[i] - nx; verts[o + 3] = t.y[i] - ny
            val s = i / (n - 1f)
            texs[o] = s; texs[o + 1] = 1f; texs[o + 2] = s; texs[o + 3] = -1f
            if (i > 0) { val dx = t.x[i] - t.x[i - 1]; val dy = t.y[i] - t.y[i - 1]; len += kotlin.math.sqrt(dx * dx + dy * dy) }
        }
        for (i in 0 until 6) pulses[i] = t.pulses[i]
        sh.setFloatUniform("uLen", len.coerceAtLeast(1f))
        sh.setFloatUniform("uHalfW", hw)
        sh.setFloatUniform("uLevel", c.level)
        sh.setFloatUniform("uFade", t.fade)
        sh.setFloatUniform("uSpark", t.spark)
        sh.setFloatUniform("uDrawing", if (t.state == 1) 1f else 0f)
        sh.setFloatUniform("uTime", t.time)
        sh.setFloatUniform("uDp", c.dp)
        sh.setFloatUniform("uPulse", pulses)
        sh.palette(c)
        paint.shader = sh
        if (t.state == 3) {
            val half = n / 2
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLE_STRIP, half * 4, verts, 0, texs, 0, null, 0, null, 0, 0, paint)
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLE_STRIP, (n - half) * 4, verts, half * 4, texs, half * 4, null, 0, null, 0, 0, paint)
        } else {
            canvas.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLE_STRIP, n * 4, verts, 0, texs, 0, null, 0, null, 0, 0, paint)
        }
    }
}

/** Any shader error (a device rejecting a uniform, say) turns the effect off instead of crashing the lock screen. */
internal var chargeBroken = false

internal inline fun <T> safely(what: String, block: () -> T?): T? {
    if (chargeBroken) return null
    return try { block() } catch (e: RuntimeException) {
        Log.w("QuietCharge", "$what failed; charging animation off", e); chargeBroken = true; null
    }
}

internal fun ChargeHost.gpuOrNull(): ChargeGpu? {
    if (Build.VERSION.SDK_INT < 33) return null
    return (gpu as? ChargeGpu) ?: ChargeGpu().also { gpu = it }
}

/**
 * The live lock scene under Flood and Filling glass: a RenderEffect whose shader reads the scene
 * itself, so the water and the glass bend the real painting (and the cover, the clock, the orb).
 * Off, or for the other styles, there is no effect at all.
 */
fun Modifier.chargeScene(host: ChargeHost?): Modifier {
    if (host == null || Build.VERSION.SDK_INT < 33) return this
    return graphicsLayer {
        host.tick
        val gpu = host.gpuOrNull()
        renderEffect = when (host.shown) {
            ChargeAnim.FLOOD -> safely("flood") { gpu?.floodEffect(host)?.asComposeRenderEffect() }
            ChargeAnim.GLASS -> safely("glass") { gpu?.glassEffect(host)?.asComposeRenderEffect() }
            else -> null
        }
    }
}

/** The lock clock's time under Clock fill: glass digits filling with liquid light. */
fun Modifier.chargeClock(host: ChargeHost?): Modifier {
    if (host == null || Build.VERSION.SDK_INT < 33) return this
    return graphicsLayer {
        host.tick
        renderEffect = if (host.shown == ChargeAnim.CLOCK) safely("clock") { host.gpuOrNull()?.clockEffect(host, size.width, size.height)?.asComposeRenderEffect() } else null
    }
}

/** The glowing thread, over the scene (additive). */
@Composable
fun ChargeOverlay(host: ChargeHost, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 33 || host.shown != ChargeAnim.THREAD) return
    Canvas(modifier.fillMaxSize()) {
        host.tick
        val cv = drawContext.canvas.nativeCanvas
        safely("thread") { host.gpuOrNull()?.drawThread(host, cv) }
    }
}

@Suppress("unused") private fun keep() = abs(0f)
