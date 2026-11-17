package app.quiet.launcher.feature.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.PlayerControl
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.rememberHaptics
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The play orb on the lock screen: a small living object in the cover's colours, where the pause
 * button was. Tap it to play or pause; drag it sideways and the cover comes along; let go past a
 * fifth of the width, or flick it, and it flies off, the song changes and it springs back from the
 * other side. Physics is stepped per frame on the CPU (springs); the look is an AGSL shader on
 * API 33+ ([OrbShaders]), with a plain Canvas drawing below that. When paused and at rest it stops
 * asking for frames.
 */
@Stable
class OrbState internal constructor() {
    /** Where the cover should sit (px). It follows the orb while dragging and during a song change. */
    var coverX by mutableFloatStateOf(0f)
        internal set
    internal var tick by mutableLongStateOf(0L)
    // Orb offset from the centre of its row, and velocity (px, px/s).
    internal var x = 0f
    internal var vx = 0f
    internal var s = 1f
    internal var vs = 0f
    internal var vcx = 0f
    internal var dragging = false
    internal var flying = 0
    internal var armed = false
    internal var time = 0f
    /** Advances only while playing: what the inner currents are animated by, so they freeze on pause. */
    internal var flow = 0f
    internal var paused = 0f
    internal var roll = 0f
    internal var spin = 0f
    /** Radius in px, set by the drawing (moon targets depend on it). */
    internal var rPx = 0f
    // Two trailing droplets (offsets like x) that chase the orb on softer springs.
    internal var t1 = 0f
    internal var t1v = 0f
    internal var t2 = 0f
    internal var t2v = 0f
    // Moons for the sun, relative to the row centre (x) and the orb's centre line (y).
    internal val mx = FloatArray(3)
    internal val my = FloatArray(3)
    internal val mvx = FloatArray(3)
    internal val mvy = FloatArray(3)
    internal var moonsReady = false

    /** Being dragged, flung or still springing back: worth full frame rate. */
    internal fun moving() = dragging || flying != 0 || abs(vx) > 20f || abs(vcx) > 20f || abs(vs) > 0.05f ||
        abs(t1v) > 20f || abs(t2v) > 20f

    internal fun atRest(playing: Boolean) = !playing && !dragging && flying == 0 &&
        abs(x) < 0.5f && abs(vx) < 4f && abs(coverX) < 0.5f && abs(vcx) < 4f &&
        abs(t1 - x) < 0.5f && abs(t2 - x) < 0.5f && abs(t1v) < 4f && abs(t2v) < 4f &&
        abs(s - 1f) < 0.002f && abs(vs) < 0.02f && paused > 0.995f &&
        (0 until 3).all { abs(mvx[it]) < 2f && abs(mvy[it]) < 2f }
}

@Composable
fun rememberOrbState(): OrbState = remember { OrbState() }

private const val CHANGE_AT = 0.22f
private val OrbRadius = 30.dp

@Composable
fun PlayerOrb(
    style: PlayerControl,
    np: NowPlaying,
    repo: NowPlayingRepository,
    state: OrbState,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
    /** False while the screen is off or the lock screen is not in front: no frames at all. */
    running: Boolean = true,
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current.density
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Battery saver counts as reduced motion: the orb only moves when touched.
    val powerSave = remember { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }
    val reduced = Q.reducedMotion || powerSave
    val still by rememberUpdatedState(reduced)
    val tones = tonePalette(np.palette)
    val pal = List(4) { i -> animateColorAsState(tones[i], tween(900), label = "orb$i").value }
    val playing by rememberUpdatedState(np.isPlaying)
    var widthPx by remember { mutableFloatStateOf(0f) }
    val shader = rememberOrbShader(style)

    LaunchedEffect(state, running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            // With reduced motion the idle life (wobble, currents, orbit) is off: it rests as if paused.
            val alive = playing && !still
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                stepOrb(state, dt, alive, widthPx, density) { dir ->
                    if (dir < 0) repo.next() else repo.previous()
                    haptics.play(Feel.Click)
                }
                state.tick = now
            }
            when {
                // At rest: no frames; look again for a touch or a state change a few times a second.
                state.atRest(alive) -> { last = 0L; delay(150) }
                // Only breathing (playing, nobody touching it): 30 fps is plenty for slow motion.
                !state.moving() -> delay(18)
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics {
                role = Role.Button
                contentDescription = if (np.isPlaying) "Pause. Drag sideways to change song" else "Play. Drag sideways to change song"
                onClick { repo.playPause(); true }
            }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = OrbRadius.toPx()
                    // Only the orb itself (and a little around it) is touchable; elsewhere the lock screen's own gestures run.
                    if (abs(down.position.x - (size.width / 2f + state.x)) > r * 1.8f || state.flying != 0) return@awaitEachGesture
                    down.consume()
                    state.vs -= 3f
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() }
                    if (start == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed && up.uptimeMillis - down.uptimeMillis < 450) {
                            haptics.play(Feel.Click)
                            state.vs += 9f
                            repo.playPause()
                        }
                        return@awaitEachGesture
                    }
                    val base = state.x
                    val w = size.width.toFloat()
                    state.dragging = true
                    state.armed = false
                    fun follow(px: Float) {
                        val raw = base + px - down.position.x
                        val lim = w * 0.42f
                        state.x = if (abs(raw) < lim) raw else sign(raw) * (lim + (abs(raw) - lim) * 0.3f)
                        val past = abs(state.x) > w * CHANGE_AT
                        if (past != state.armed) { state.armed = past; if (past) haptics.play(Feel.Tick) }
                    }
                    follow(start.position.x)
                    tracker.addPosition(start.uptimeMillis, start.position)
                    horizontalDrag(start.id) { ch ->
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        follow(ch.position.x)
                        ch.consume()
                    }
                    state.dragging = false
                    val v = tracker.calculateVelocity().x
                    state.vx = v
                    val fling = abs(v) > 700f * density
                    if (abs(state.x) > w * CHANGE_AT || fling) state.flying = sign(if (fling) v else state.x).toInt()
                }
            },
    ) {
        state.tick // redraw every stepped frame
        val beat = if (np.isPlaying && !reduced && running) max(0f, sin(state.time * 2f * PI.toFloat() * 1.55f)).pow(6) else 0f
        renderOrb(style, shader, state, pal, Offset(size.width / 2f + state.x, size.height * 0.46f), OrbRadius.toPx(), beat)
    }
}

/** A small live sample of a style, for the settings chooser. */
@Composable
fun OrbSample(style: PlayerControl, modifier: Modifier = Modifier, radius: Dp = 18.dp) {
    val state = remember(style) { OrbState() }
    val density = LocalDensity.current.density
    val pal = remember { tonePalette(null) }
    val shader = rememberOrbShader(style)
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                stepOrb(state, dt, true, 0f, density) {}
                state.tick = now
            }
            delay(18) // a sample: 30 fps
        }
    }
    Canvas(modifier) {
        state.tick
        val beat = max(0f, sin(state.time * 2f * PI.toFloat() * 1.55f)).pow(6)
        renderOrb(style, shader, state, pal, center, radius.toPx(), beat)
    }
}

// ---------------------------------------------------------------- physics

private fun spring(v: Float, x: Float, target: Float, k: Float, z: Float, dt: Float): Pair<Float, Float> {
    val c = 2f * sqrt(k) * z
    val nv = v + (-k * (x - target) - c * v) * dt
    return nv to (x + nv * dt)
}

private fun stepOrb(st: OrbState, dt: Float, playing: Boolean, w: Float, d: Float, onChange: (Int) -> Unit) {
    st.time += dt
    if (playing) st.flow += dt
    st.paused += ((if (playing) 0f else 1f) - st.paused) * min(1f, dt * 7f)
    if (st.flying != 0) {
        st.vx += st.flying * 2600f * d * dt
        st.x += st.vx * dt
        if (abs(st.x) > w * 0.75f || w <= 0f) {
            val dir = st.flying
            onChange(dir)
            st.x = -dir * w * 0.6f
            st.vx = dir * 900f * d
            st.t1 = st.x; st.t2 = st.x; st.t1v = st.vx; st.t2v = st.vx
            for (i in 0 until 3) { st.mx[i] += st.x; st.mvx[i] = st.vx }
            st.coverX = -dir * w * 0.9f
            st.vcx = dir * 400f * d
            st.flying = 0
        }
    } else if (!st.dragging) {
        spring(st.vx, st.x, 0f, 170f, 0.42f, dt).let { (v, x) -> st.vx = v; st.x = x }
    }
    // The droplets lag behind the orb, so it stretches into a drop when it moves.
    spring(st.t1v, st.t1, st.x, 120f, 0.55f, dt).let { (v, x) -> st.t1v = v; st.t1 = x }
    spring(st.t2v, st.t2, st.t1, 80f, 0.5f, dt).let { (v, x) -> st.t2v = v; st.t2 = x }
    val want = if (st.flying != 0) st.x * 1.25f else st.x * 0.6f
    spring(st.vcx, st.coverX, want, if (st.dragging) 600f else 220f, if (st.dragging) 1f else 0.62f, dt).let { (v, x) -> st.vcx = v; st.coverX = x }
    spring(st.vs, st.s, if (st.dragging) 0.9f else 1f, 320f, 0.32f, dt).let { (v, x) -> st.vs = v; st.s = x }
    if (playing) st.spin += dt * 0.6f
    st.roll = st.x / (18f * d) + st.spin
    stepMoons(st, dt)
}

/** Moons orbit while playing and glide into a play triangle when paused; each chases its spot on its own spring. */
private fun stepMoons(st: OrbState, dt: Float) {
    val R = st.rPx
    if (R <= 0f) return
    val tri = floatArrayOf(-0.32f, -0.48f, 0.5f, 0f, -0.32f, 0.48f)
    val ks = floatArrayOf(140f, 95f, 65f)
    for (i in 0 until 3) {
        val a = st.flow * 2.1f + i * 2f * PI.toFloat() / 3f
        val p = st.paused
        val tx = st.x + cos(a) * R * 0.95f * (1 - p) + tri[i * 2] * R * p
        val ty = sin(a) * R * 0.95f * 0.85f * (1 - p) + tri[i * 2 + 1] * R * p
        if (!st.moonsReady) { st.mx[i] = tx; st.my[i] = ty }
        val k = ks[i]
        val c = 2f * sqrt(k) * 0.5f
        st.mvx[i] += (-k * (st.mx[i] - tx) - c * st.mvx[i]) * dt
        st.mvy[i] += (-k * (st.my[i] - ty) - c * st.mvy[i]) * dt
        st.mx[i] += st.mvx[i] * dt
        st.my[i] += st.mvy[i] * dt
    }
    st.moonsReady = true
}

/**
 * Quiet toning: keep the cover's hue but cap its saturation and fix each swatch's lightness, so the
 * orb sits in the theme instead of shouting. Swatches: dark, accent, mid, light.
 */
fun tonePalette(p: IntArray?): List<Color> {
    val src = if (p == null || p.size < 4) intArrayOf(0xFF2B1A10.toInt(), 0xFFE0913F.toInt(), 0xFF8A5A36.toInt(), 0xFFF6D29A.toInt()) else p
    val spec = arrayOf(0.10f to 0.10f, 0.30f to 0.62f, 0.20f to 0.40f, 0.18f to 0.88f)
    return List(4) { i ->
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(src[i], hsl)
        hsl[1] = min(hsl[1], spec[i].first)
        hsl[2] = spec[i].second
        val c = Color(ColorUtils.HSLToColor(hsl))
        if (i == 3) lerp(c, Color(0xFFEDEBE6), 0.35f) else lerp(c, Color(0xFF1A1714), 0.15f)
    }
}

// ---------------------------------------------------------------- drawing

/** A compiled orb shader, or null below API 33 or if this device rejects it (then the Canvas version draws). */
@Composable
private fun rememberOrbShader(style: PlayerControl): Any? = remember(style) {
    if (Build.VERSION.SDK_INT >= 33 && style != PlayerControl.BUTTONS) compileOrb(style) else null
}

@RequiresApi(33)
private fun compileOrb(style: PlayerControl): RuntimeShader? = try {
    RuntimeShader(OrbShaders.source(style)).apply {
        OrbShaders.FLOAT1.forEach { setFloatUniform(it, 0f) }
        OrbShaders.FLOAT2.forEach { setFloatUniform(it, 0f, 0f) }
        OrbShaders.FLOAT3.forEach { setFloatUniform(it, 0f, 0f, 0f) }
    }
} catch (e: RuntimeException) {
    android.util.Log.w("QuietOrb", "orb shader ${style.name} rejected", e)
    null
}

private fun DrawScope.renderOrb(style: PlayerControl, shader: Any?, st: OrbState, pal: List<Color>, o: Offset, R: Float, beat: Float) {
    st.rPx = R
    if (shader != null && Build.VERSION.SDK_INT >= 33 && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
        drawOrbShader(shader as RuntimeShader, st, pal, o, R, beat)
        return
    }
    val stretch = (abs(st.vx) / (2600f * density)).coerceIn(0f, 0.38f)
    when (style) {
        PlayerControl.MARBLE -> drawMarble(st, pal, o, R, beat)
        PlayerControl.SUN -> drawSun(st, pal, o, R, beat)
        else -> drawDrop(st, pal, o, R, stretch, beat)
    }
}

@RequiresApi(33)
private fun DrawScope.drawOrbShader(sh: RuntimeShader, st: OrbState, pal: List<Color>, o: Offset, R: Float, beat: Float) {
    val cy = o.y
    val rowCx = o.x - st.x
    sh.setFloatUniform("uC", o.x, o.y)
    sh.setFloatUniform("uR", R)
    sh.setFloatUniform("uTime", st.time)
    sh.setFloatUniform("uFlow", st.flow)
    sh.setFloatUniform("uPaused", st.paused)
    sh.setFloatUniform("uBeat", beat)
    sh.setFloatUniform("uPress", st.s)
    // Velocity in orb radii per second: the drops stretch along it.
    sh.setFloatUniform("uVel", st.vx / R, 0f)
    sh.setFloatUniform("uRoll", st.roll)
    sh.setFloatUniform("uT1", rowCx + st.t1, cy)
    sh.setFloatUniform("uT2", rowCx + st.t2, cy)
    sh.setFloatUniform("uM0", rowCx + st.mx[0], cy + st.my[0])
    sh.setFloatUniform("uM1", rowCx + st.mx[1], cy + st.my[1])
    sh.setFloatUniform("uM2", rowCx + st.mx[2], cy + st.my[2])
    sh.setFloatUniform("uV0", st.mvx[0], st.mvy[0])
    sh.setFloatUniform("uV1", st.mvx[1], st.mvy[1])
    sh.setFloatUniform("uV2", st.mvx[2], st.mvy[2])
    val (dk, ac, md, lt) = pal
    sh.setFloatUniform("uDk", dk.red, dk.green, dk.blue)
    sh.setFloatUniform("uAc", ac.red, ac.green, ac.blue)
    sh.setFloatUniform("uMd", md.red, md.green, md.blue)
    sh.setFloatUniform("uLt", lt.red, lt.green, lt.blue)
    // Only the area around the orb (and its trails) is shaded.
    val left = min(o.x, rowCx + min(st.t2, st.t1)) - R * 2.6f
    val right = max(o.x, rowCx + max(st.t2, st.t1)) + R * 2.6f
    drawRect(ShaderBrush(sh), Offset(left, 0f), Size(right - left, size.height))
}

private fun DrawScope.playMark(o: Offset, color: Color, alpha: Float) {
    if (alpha <= 0.02f) return
    val u = density
    val p = Path().apply {
        moveTo(o.x - 2.5f * u, o.y - 6f * u); lineTo(o.x + 8.5f * u, o.y); lineTo(o.x - 2.5f * u, o.y + 6f * u); close()
    }
    drawPath(p, color.copy(alpha = color.alpha * alpha))
}

private fun DrawScope.glow(o: Offset, color: Color, radius: Float, alpha: Float) {
    val gr = min(radius, size.height / 2f - 1f)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), o, gr), gr, o)
}

private fun DrawScope.drawDrop(st: OrbState, pal: List<Color>, o: Offset, R: Float, stretch: Float, beat: Float) {
    val (dk, vib, mut, lt) = pal
    val r = R * st.s * (1f + beat * 0.06f) * (1f - st.paused * 0.08f)
    glow(o, vib, r * 2.6f, 0.35f)
    val dir = if (st.vx >= 0f) 1f else -1f
    val path = Path()
    val n = 48
    for (i in 0..n) {
        val a = i / n.toFloat() * 2f * PI.toFloat()
        val wob = (1f - st.paused) * (0.035f * sin(3f * a + st.time * 2.3f) + 0.025f * sin(5f * a - st.time * 3.1f)) +
            beat * 0.05f * sin(2f * a + st.time)
        val tail = stretch * 0.25f * max(0f, -cos(a) * dir)
        val rr = r * (1f + wob + tail)
        val px = cos(a) * rr; val py = sin(a) * rr
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    withTransform({
        translate(o.x, o.y)
        scale(1f + stretch, (1f - stretch * 0.55f) * (1f - st.paused * 0.1f), Offset.Zero)
    }) {
        drawPath(path, Brush.radialGradient(0f to lt, 0.35f to vib, 0.8f to mut, 1f to dk, center = Offset(-r * 0.35f, -r * 0.4f), radius = r * 1.15f))
        rotate(-34f, Offset(-r * 0.32f, -r * 0.42f)) {
            drawOval(Color.White.copy(alpha = 0.45f), Offset(-r * 0.6f, -r * 0.56f), Size(r * 0.56f, r * 0.28f))
        }
    }
    playMark(o, dk, st.paused * 0.9f)
}

private fun DrawScope.drawMarble(st: OrbState, pal: List<Color>, o: Offset, R: Float, beat: Float) {
    val (dk, vib, mut, lt) = pal
    val r = R * st.s * (1f + beat * 0.03f)
    val sx = 1f + (1f - st.s) * 2f
    val sc = Offset(o.x, o.y + r + 6f * density)
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), sc, r * 1.1f * sx),
        Offset(sc.x - r * 1.1f * sx, sc.y - r * 0.28f), Size(r * 2.2f * sx, r * 0.56f),
    )
    val ball = Path().apply { addOval(androidx.compose.ui.geometry.Rect(o, r)) }
    clipPath(ball) {
        drawRect(dk, Offset(o.x - r, o.y - r), Size(r * 2, r * 2))
        val cols = listOf(vib, lt, mut, vib)
        for (k in 0 until 4) {
            val ang = st.roll * (if (k % 2 == 1) 1f else -0.7f) + k * 1.6f
            rotate(Math.toDegrees(ang.toDouble()).toFloat(), o) {
                rotate(28.6f, Offset(o.x + r * 0.35f, o.y)) {
                    drawOval(
                        Brush.radialGradient(listOf(cols[k].copy(alpha = 0.95f), cols[k].copy(alpha = 0f)), Offset(o.x + r * 0.45f, o.y), r * 0.9f),
                        Offset(o.x + r * 0.35f - r * 0.95f, o.y - r * 0.32f), Size(r * 1.9f, r * 0.64f),
                    )
                }
            }
        }
        drawRect(
            Brush.radialGradient(0f to Color.Transparent, 0.75f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.6f), center = Offset(o.x - r * 0.3f, o.y - r * 0.35f), radius = r * 1.3f),
            Offset(o.x - r, o.y - r), Size(r * 2, r * 2),
        )
        if (st.paused > 0.01f) drawRect(Color.Black.copy(alpha = st.paused * 0.4f), Offset(o.x - r, o.y - r), Size(r * 2, r * 2))
    }
    rotate(-31.5f, Offset(o.x - r * 0.35f, o.y - r * 0.45f)) {
        drawOval(Color.White.copy(alpha = 0.55f), Offset(o.x - r * 0.67f, o.y - r * 0.61f), Size(r * 0.64f, r * 0.32f))
    }
    drawCircle(Color.White.copy(alpha = 0.18f), r - 0.5f, o, style = Stroke(1f * density))
    playMark(o, Color.White.copy(alpha = 0.85f), st.paused * 0.9f)
}

private fun DrawScope.drawSun(st: OrbState, pal: List<Color>, o: Offset, R: Float, beat: Float) {
    val (_, vib, mut, lt) = pal
    val r = R * 0.48f * st.s * (1f + beat * 0.12f)
    glow(o, vib, R * 2.2f, 0.45f + beat * 0.2f)
    val cols = listOf(lt, vib, mut)
    val rowCx = o.x - st.x
    for (i in 0 until 3) {
        val m = Offset(rowCx + st.mx[i], o.y + st.my[i])
        val sp = hypot(st.mvx[i], st.mvy[i])
        val len = min(R * 1.2f, sp * 0.05f)
        if (len > 2f) {
            val u = Offset(st.mvx[i] / sp, st.mvy[i] / sp)
            val end = m - u * len
            drawLine(Brush.linearGradient(listOf(cols[i].copy(alpha = 0.8f), cols[i].copy(alpha = 0f)), m, end), m, end, R * 0.22f, StrokeCap.Round)
        }
        drawCircle(cols[i], R * (0.13f + st.paused * 0.03f), m)
    }
    drawCircle(
        Brush.radialGradient(0f to Color.White, 0.35f to lt, 1f to vib, center = Offset(o.x - r * 0.3f, o.y - r * 0.3f), radius = r * 1.2f),
        r, o, alpha = 1f - st.paused * 0.85f,
    )
}
