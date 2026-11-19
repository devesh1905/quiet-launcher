package app.quiet.launcher.feature.underwater.engine

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Haptics
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The water for this screen tree; null means the classic look (classic edition, setting off, API < 33). */
val LocalWater = staticCompositionLocalOf<WaterHost?> { null }

/** How a glass body looks and moves. Sizes in dp. */
data class GlassStyle(
    /** Corner radius; anything >= half the short side makes a pill (or a circle when square). */
    val corner: Dp = 22.dp,
    /** Dome width at the rim: the flat middle stays undistorted, so type on the glass stays crisp. */
    val bevel: Dp = 16.dp,
    /** 0: never merges. Bodies sharing a group 1..3 fuse and split (smooth-min). */
    val group: Int = 0,
    /** 0 a flat slab .. 1 a strong magnifying bead. */
    val lens: Float = 0f,
    /** 0 clear .. 1 frosted (sheets). */
    val frost: Float = 0f,
    /** Rides the water: bobs with the wave and rolls with its slope when it touches the surface. */
    val floats: Boolean = false,
    /** Moving through the water leaves ripples. */
    val stir: Boolean = false,
    /** Spring for position and size. */
    val stiffness: Float = 260f,
    val damping: Float = 0.62f,
    /** Grow the glass this far beyond the anchor's bounds. */
    val outset: Dp = 0.dp,
    /** Track the target exactly (no spring lag, no squash): sheets, panes, the rail rod. Bob/roll still apply if [floats]. */
    val rigid: Boolean = false,
    /** Condense from nothing on the first target (default); false: appear at full alpha at once. */
    val growIn: Boolean = true,
)

/**
 * Everything the underwater look shares on one screen tree: the inputs (battery level, tilt, touch),
 * the world layer it renders, the simulation ([WaterSim]: waterline, ripple grid, drops, rings, glass
 * bodies), the GPU side ([WaterGpu]) and the frame clock with the battery rules. Drawing reads [tick].
 *
 * Coordinates are px in the world layer (the composable carrying [waterWorld]); [toWorld] converts.
 * See ENGINE-API.md (scratchpad) for how screens use it.
 */
@Stable
class WaterHost internal constructor() {
    /** Bumped once per stepped frame; layers that draw or follow the water read it. */
    var tick by mutableLongStateOf(0L)
        internal set
    /** The waterline at rest (px, world; the battery level), for layout. Changes only when it moves > 0.5 px. */
    var restLine by mutableFloatStateOf(0f)
        private set

    internal val sim = WaterSim()
    internal var gpu: Any? = null
    internal var haptics: Haptics? = null
    internal var root: LayoutCoordinates? = null
    internal var w = 0f
    internal var h = 0f
    internal var dp = 1f
    internal var still = false
    internal var running = false

    // ---- inputs
    /** Battery level 0..1 (the waterline). */
    var level = 0.5f
        private set
    var charging = false
        internal set
    private var rawGx = 0f
    private var rawGy = 9.8f
    private var gx = 0f
    private var gy = 9.8f
    internal var tilt = 0f
    private var lastWakeTilt = 0f
    private var fingers = 0
    private var lastWakeGy = 9.8f

    // ---- tilt parallax of the painting (-1..1 each way), on a soft spring stepped with the sim
    /** How far the painting leans (-1..1), against the tilt; the backdrop shifts by this x ~13 dp. */
    var parX = 0f
        private set
    var parY = 0f
        private set
    private var vParX = 0f
    private var vParY = 0f
    private var tParX = 0f
    private var tParY = 0f
    /** The pitch the phone is usually held at (slow average of gy): the painting centres on it. */
    private var neutralGy = Float.NaN

    // ---- palette (sRGB 0..1)
    internal val pDk = FloatArray(3); internal val pAc = FloatArray(3); internal val pMd = FloatArray(3)
    internal val pLt = FloatArray(3); internal val pSheen = FloatArray(3)

    // ---- glass anchors (layout-linked bodies)
    internal val anchors = arrayOfNulls<LayoutCoordinates>(WaterSim.MAX_GLASS)
    internal val anchorOutset = FloatArray(WaterSim.MAX_GLASS)
    internal val alphaOf = arrayOfNulls<() -> Float>(WaterSim.MAX_GLASS)
    private val dropCb = arrayOfNulls<(Float, Float) -> Unit>(WaterSim.MAX_DROPS)
    private val listeners = ArrayList<(Int, Int, Int) -> Unit>(4)

    // ---- frame clock
    internal val wakeCh = Channel<Unit>(Channel.CONFLATED)
    private var acc = 0f
    private var lastStir = 0L
    private var fullUntil = 0L
    private var staticDirty = true
    private var oneFrame = true
    internal var time = 0f

    // ================================================================ public API

    /** The waterline (px, world) at [x], with its waves. */
    fun waterY(x: Float): Float = if (sim.w > 0f) sim.waterY(x) else restLine
    /** Is (x, y) under the water? */
    fun isUnder(x: Float, y: Float): Boolean = y > waterY(x)
    /** Where a battery level puts the resting waterline (px, world): 100% just under the date, 0% near the bottom. */
    fun levelToY(l: Float): Float = h * (TOP + (BOTTOM - TOP) * (1f - l.coerceIn(0f, 1f)))

    /** A splash in the water at (x, y): rings spread in the grid (or the surface is kicked from above). [strength] ~1. */
    fun ripple(x: Float, y: Float, strength: Float = 1f) {
        if (still || sim.grid == null) return
        sim.ripple(x, y, 200f * strength, 1.6f + strength)
        stir()
    }

    /** An analytic ring that bends whatever it crosses, air or water (search opening, a bead landing). [amp] ~1. */
    fun ring(x: Float, y: Float, amp: Float = 1f) {
        if (still) return
        sim.ring(x, y, amp.coerceIn(0f, 1.5f))
        stir(fullMs = 1200L)
    }

    /**
     * A glass drop falls from (x, y) into the water below it in about [fallMs] (a small hop first),
     * splashes, plays [Feel.Plunk] and calls [onLand] with the splash point. Returns false (and calls
     * [onLand] at once) when the water is still (reduced motion, battery saver) or not running.
     */
    fun drop(x: Float, y: Float, onLand: (Float, Float) -> Unit, radius: Dp = 7.dp, fallMs: Int = 190): Boolean {
        if (still || !running || sim.grid == null) { onLand(x, waterY(x)); return false }
        val r = radius.value * dp
        // Already in the water: the drop starts just over the surface above the point.
        val y0 = min(y, waterY(x) - 54f * dp)
        val surface = waterY(x)
        val t = fallMs / 1000f
        val v0 = -150f * dp
        val dist = max(surface - y0, 8f * dp)
        val g = 2f * (dist - v0 * t) / (t * t)
        val i = sim.drop(x, y0, r, 0f, v0, g)
        if (i < 0) { onLand(x, surface); return false }
        dropCb[i] = onLand
        stir(fullMs = 800L)
        return true
    }

    /** A finger moved at (x, y) by (dx, dy): stirs the water where it touches it. Returns true if it did. */
    fun touch(x: Float, y: Float, dx: Float, dy: Float): Boolean {
        if (still || sim.grid == null) return false
        val r = sim.touch(x, y, dx, dy)
        if (r) stir()
        return r
    }

    /** Plays one of the haptic feels ([Feel.Drip], [Feel.Clink], [Feel.Plunk], [Feel.Rise], [Feel.Pop] for water and glass). */
    fun feel(f: Feel) {
        if (WaterDebug.logFeel) android.util.Log.i("QuietWater", "feel $f haptics=${haptics != null}")
        haptics?.play(f)
    }

    /** A glass-on-glass knock, as strong as [intensity] (0..1). */
    fun clink(intensity: Float) {
        if (WaterDebug.logFeel) android.util.Log.i("QuietWater", "feel Clink x$intensity")
        haptics?.clink(intensity)
    }

    /** Ask for frames (call after changing anything the water should show). */
    fun wake() { staticDirty = true; oneFrame = true; wakeCh.trySend(Unit) }

    /** Converts a point in [coords] to world px. */
    fun toWorld(coords: LayoutCoordinates, p: Offset = Offset.Zero): Offset {
        if (!coords.isAttached) return p
        val r = root?.takeIf { it.isAttached } ?: return coords.localToRoot(p)
        return r.localPositionOf(coords, p)
    }

    /** World px to window px (for ActivityOptions and the like). */
    fun toWindow(p: Offset): Offset = root?.takeIf { it.isAttached }?.localToWindow(p) ?: p

    /** Glass events: (type, a, b) with type [GlassBodies.EV_MERGE], [GlassBodies.EV_SPLIT] or [GlassBodies.EV_CROSS]. */
    fun addGlassListener(l: (Int, Int, Int) -> Unit) { listeners.add(l) }
    fun removeGlassListener(l: (Int, Int, Int) -> Unit) { listeners.remove(l) }

    // ---- glass bodies (programmatic; layout-linked ones use GlassBox / Modifier.glass)

    /** A new glass body, or -1 if all [WaterSim.MAX_GLASS] are taken. It fades in at its first target. */
    fun obtainGlass(style: GlassStyle = GlassStyle()): Int = obtainGlass(style, -1)

    /**
     * A new glass body already fused with body [fusedWith] (same group): no [GlassBodies.EV_MERGE] on
     * its first step, only the [GlassBodies.EV_SPLIT] when it is pulled away. -1 = not fused.
     */
    fun obtainGlass(style: GlassStyle, fusedWith: Int): Int {
        val i = sim.glass.obtain()
        if (i >= 0) {
            styleGlass(i, style); anchors[i] = null; alphaOf[i] = null
            if (fusedWith >= 0) sim.glass.fuse(i, fusedWith)
        }
        return i
    }

    fun styleGlass(id: Int, s: GlassStyle) {
        val g = sim.glass
        if (id !in 0 until g.n) return
        g.corner[id] = s.corner.value * dp; g.bevel[id] = s.bevel.value * dp
        g.group[id] = s.group.coerceIn(0, 3); g.lens[id] = s.lens; g.frost[id] = s.frost
        g.floats[id] = s.floats; g.stir[id] = s.stir
        g.k[id] = s.stiffness; g.zeta[id] = s.damping
        g.rigid[id] = s.rigid; g.growIn[id] = s.growIn
        anchorOutset[id] = s.outset.value * dp
        wake()
    }

    /** Target centre and half size (px, world). The body springs there. */
    fun moveGlass(id: Int, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        val g = sim.glass
        if (id !in 0 until g.n || !g.used[id]) return
        if (g.tx[id] != cx || g.ty[id] != cy || g.tw[id] != halfW || g.th[id] != halfH) {
            g.tx[id] = cx; g.ty[id] = cy; g.tw[id] = halfW; g.th[id] = halfH
            wake()
        }
    }

    /**
     * Put the body exactly at this centre and half size now (no spring, no squash), e.g. every layout
     * pass for a sheet sliding in. Same frame: the draw pass sees it.
     */
    fun placeGlass(id: Int, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        val g = sim.glass
        if (id !in 0 until g.n || !g.used[id]) return
        val changed = g.x[id] != cx || g.y[id] != cy || g.hw[id] != halfW || g.hh[id] != halfH ||
            g.tx[id] != cx || g.ty[id] != cy || g.tw[id] != halfW || g.th[id] != halfH
        g.tx[id] = cx; g.ty[id] = cy; g.tw[id] = halfW; g.th[id] = halfH
        g.place(id)
        if (changed) wake()
    }

    /** Fade the body toward [a] (0..1); at 0 it shrinks away. */
    fun glassAlpha(id: Int, a: Float) { if (id in 0 until sim.glass.n && sim.glass.tAlpha[id] != a) { sim.glass.tAlpha[id] = a; wake() } }
    /** A finger holds the body: it follows its target stiffly and reports waterline crossings. */
    fun holdGlass(id: Int, held: Boolean) { if (id in 0 until sim.glass.n) { sim.glass.held[id] = held; wake() } }
    /** Push the body (px/s). */
    fun kickGlass(id: Int, vx: Float, vy: Float) { if (id in 0 until sim.glass.n) { sim.glass.vx[id] += vx; sim.glass.vy[id] += vy; wake() } }
    fun freeGlass(id: Int) { if (id in 0 until sim.glass.n) { sim.glass.free(id); anchors[id] = null; alphaOf[id] = null; wake() } }

    /** Where the body is drawn relative to its target (px): content riding the glass translates by this. */
    fun glassDx(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.x[id] - sim.glass.tx[id] else 0f
    fun glassDy(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.y[id] - sim.glass.ty[id] + sim.glass.bob[id] else 0f
    fun glassScaleX(id: Int): Float = if (id in 0 until sim.glass.n) (sim.glass.hw[id] / sim.glass.tw[id].coerceAtLeast(1f)) * (1f + sim.glass.squash[id]) else 1f
    fun glassScaleY(id: Int): Float = if (id in 0 until sim.glass.n) (sim.glass.hh[id] / sim.glass.th[id].coerceAtLeast(1f)) / (1f + sim.glass.squash[id]) else 1f
    fun glassRotation(id: Int): Float = if (id in 0 until sim.glass.n) Math.toDegrees(sim.glass.rot[id].toDouble()).toFloat() else 0f
    fun glassAlphaNow(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.alpha[id] else 0f
    /** The body's current velocity (px/s), e.g. to hand a released drop's motion to the next one. */
    fun glassVx(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.vx[id] else 0f
    fun glassVy(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.vy[id] else 0f
    /** 0..1 how deep the body sits in the water. */
    fun glassSubmerged(id: Int): Float = if (id in 0 until sim.glass.n) sim.glass.sub[id] else 0f

    // ================================================================ internals

    internal fun anchor(id: Int, c: LayoutCoordinates, alpha: (() -> Float)?) {
        if (id !in 0 until sim.glass.n) return
        anchors[id] = c; alphaOf[id] = alpha
        wake()
    }

    internal fun setLevel(l: Float, chg: Boolean) {
        charging = chg
        if (abs(l - level) > 0.001f) { level = l; staticDirty = true; stir(); wake() }
    }

    internal fun onGravity(x: Float, y: Float) {
        rawGx = -x; rawGy = y
        val plane = sqrt(rawGx * rawGx + rawGy * rawGy)
        val ts = if (plane > 2.5f && rawGy > 0.8f) (rawGx / rawGy).coerceIn(-0.7f, 0.7f) else 0f
        if (abs(ts - lastWakeTilt) > 0.03f) { lastWakeTilt = ts; stir(); wake() }
        else if (abs(y - lastWakeGy) > 0.35f) { lastWakeGy = y; stir(); wake() }
    }

    internal fun fingerDown(x: Float, y: Float) {
        fingers++
        if (!still && isUnder(x, y)) { touch(x, y, 0f, 0f); feel(Feel.Drip) }
        stir(); wake()
    }
    internal fun fingerUp() { fingers = max(0, fingers - 1); stir() }
    internal fun fingerMove(x: Float, y: Float, dx: Float, dy: Float) {
        val before = isUnder(x - dx, y - dy)
        if (touch(x, y, dx, dy)) {
            if (!before && isUnder(x, y)) feel(Feel.Drip)
            wake()
        }
    }

    internal fun setPalette(tones: List<Color>, lightVibrant: Int?) {
        fun put(a: FloatArray, c: Color) { a[0] = c.red; a[1] = c.green; a[2] = c.blue }
        put(pDk, tones[0]); put(pAc, tones[1]); put(pMd, tones[2]); put(pLt, tones[3])
        val sheen = app.quiet.launcher.feature.lock.PaintingTint.sheen(lightVibrant ?: 0xFFBFE3EC.toInt())
        pSheen[0] = ((sheen shr 16) and 0xFF) / 255f; pSheen[1] = ((sheen shr 8) and 0xFF) / 255f; pSheen[2] = (sheen and 0xFF) / 255f
        staticDirty = true
    }

    private fun stir(fullMs: Long = 0L) {
        val now = SystemClock.uptimeMillis()
        lastStir = now
        if (fullMs > 0) fullUntil = max(fullUntil, now + fullMs)
    }

    /** Lays out from the world root and the glass anchors. */
    internal fun measure() {
        val r = root?.takeIf { it.isAttached } ?: return
        w = r.size.width.toFloat(); h = r.size.height.toFloat()
        if (w <= 0f) return
        sim.resize(w, h, dp)
        sim.targetY = levelToY(level)
        val ry = sim.targetY
        if (abs(ry - restLine) > 0.5f) restLine = ry
        val g = sim.glass
        for (i in 0 until g.n) {
            val c = anchors[i] ?: continue
            if (!g.used[i]) continue
            if (!c.isAttached) { g.tAlpha[i] = 0f; continue }
            val b = r.localBoundingBoxOf(c, clipBounds = false)
            val o = anchorOutset[i]
            g.tx[i] = b.center.x; g.ty[i] = b.center.y
            g.tw[i] = b.width / 2f + o; g.th[i] = b.height / 2f + o
            g.tAlpha[i] = (alphaOf[i]?.invoke() ?: 1f).coerceIn(0f, 1f)
            if (g.rigid[i]) g.place(i)
        }
    }

    internal fun energy(): Float = sim.energy() + sim.glass.energy(dp) + parallaxEnergy()

    /**
     * The painting leans against the tilt: the water piles toward the lower side (positive tilt
     * raises the right), so the painting moves toward the raised side, and up or down with the
     * pitch relative to how the phone is usually held. A soft, slightly underdamped spring.
     */
    private fun stepParallax() {
        if (neutralGy.isNaN()) neutralGy = gy
        neutralGy += (gy - neutralGy) * (STEP / 2.8f)
        tParX = (-tilt / 0.5f).coerceIn(-1f, 1f)
        tParY = ((neutralGy - gy) / 2.6f).coerceIn(-1f, 1f)
        val k = 38f; val c = 2f * sqrt(k) * 0.8f
        vParX += (k * (tParX - parX) - c * vParX) * STEP; parX += vParX * STEP
        vParY += (k * (tParY - parY) - c * vParY) * STEP; parY += vParY * STEP
    }

    private fun parallaxEnergy(): Float {
        val e = abs(tParX - parX) + abs(tParY - parY) + (abs(vParX) + abs(vParY)) * 0.1f
        // Below ~0.1 dp of travel it is at rest (no frames for it).
        return if (e < 0.008f) 0f else 1.3f + e * 400f
    }

    internal fun wantsFrames(now: Long): Boolean {
        if (w <= 0f || oneFrame) return true
        if (fingers > 0 || now < fullUntil) return true
        if (sim.dropsAlive > 0 || sim.ringsAlive > 0) return true
        if (energy() > REST_ENERGY) return true
        // A short shimmer after the last touch (capped at 30 fps), then the frame holds.
        return now - lastStir < SHIMMER_MS
    }

    internal fun fullRate(now: Long): Boolean =
        fingers > 0 || now < fullUntil || sim.dropsAlive > 0 || sim.ringsAlive > 0 || energy() > FULL_ENERGY

    /** One displayed frame: fixed 120 Hz substeps; [dt] already clamped. */
    internal fun frame(dt: Float) {
        oneFrame = false
        measure()
        if (w <= 0f) return
        acc += dt
        var steps = 0
        while (acc >= STEP && steps < 8) {
            acc -= STEP; steps++
            time += STEP
            gx += (rawGx - gx) * 0.08f; gy += (rawGy - gy) * 0.08f
            val plane = sqrt(gx * gx + gy * gy)
            tilt = if (plane > 2.5f && gy > 0.8f) (gx / gy).coerceIn(-0.7f, 0.7f) else 0f
            sim.step(STEP, tilt)
            stepParallax()
        }
        if (steps == 8) acc = 0f
        dispatch()
    }

    private fun dispatch() {
        for (i in 0 until WaterSim.MAX_DROPS) if (sim.dropLanded[i]) {
            sim.dropLanded[i] = false
            feel(Feel.Plunk)
            val cb = dropCb[i]; dropCb[i] = null
            cb?.invoke(sim.dropLandX[i], sim.dropLandY[i])
        }
        val g = sim.glass
        for (e in 0 until g.evCount) {
            when (g.evType[e]) {
                GlassBodies.EV_MERGE -> feel(Feel.Plunk)
                GlassBodies.EV_SPLIT -> feel(Feel.Pop)
                GlassBodies.EV_CROSS -> feel(Feel.Rise)
            }
            for (l in listeners) l(g.evType[e], g.evA[e], g.evB[e])
        }
        g.clearEvents()
    }

    /** Reduced motion / battery saver: one settled frame. */
    internal fun drawStatic() {
        measure()
        if (w <= 0f) { staticDirty = true; return }
        sim.settle()
        // Drops that were falling land at once.
        for (i in 0 until WaterSim.MAX_DROPS) { val cb = dropCb[i]; dropCb[i] = null; cb?.invoke(sim.dropX[i], waterY(sim.dropX[i])) }
        tick++
    }

    internal fun takeStaticDirty(): Boolean { val d = staticDirty; staticDirty = false; return d }

    internal fun releaseFingers() { fingers = 0 }

    companion object {
        internal const val STEP = 1f / 120f
        /** Waterline at 100% and at 0%, as fractions of the world height. */
        const val TOP = 0.30f
        const val BOTTOM = 0.93f
        private const val REST_ENERGY = 1.2f
        private const val FULL_ENERGY = 10f
        private const val SHIMMER_MS = 6000L
    }
}
