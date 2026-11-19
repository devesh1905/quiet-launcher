@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.quiet.launcher.feature.underwater

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.PlayerControl
import app.quiet.launcher.core.model.Track
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.AmbientField
import app.quiet.launcher.feature.player.CoverKey
import app.quiet.launcher.feature.player.OrbRadius
import app.quiet.launcher.feature.player.OrbState
import app.quiet.launcher.feature.player.PlayerOrb
import app.quiet.launcher.feature.player.coverBounds
import app.quiet.launcher.feature.player.rememberOrbState
import app.quiet.launcher.feature.player.rememberPosition
import app.quiet.launcher.feature.player.tonePalette
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import app.quiet.launcher.feature.underwater.engine.WaterSim
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * Quiet Underwater's player (only behind LocalWater; the classic player is untouched).
 *
 * The cover is a glass raft floating on the water, a fifth of it under the surface; the waterline is
 * moved to it while the player is open (and handed back to the battery level on close). It bobs and
 * rolls with the local wave, its rim bends the light, and the painting and the song's ambient field
 * show through the water under it; the water takes the song's colours.
 *  - Opening: the cover keeps the shared-element morph from the Home card and lands with a dip, a
 *    splash along its bottom edge and a ring (Plunk); the glass condenses around it as it lands.
 *  - The beat: on each beat of the play orb (the curve it breathes with) a soft refraction ring goes
 *    out from under the cover and the raft dips a little, only while playing; paused, it calms flat.
 *  - The play orb is the Glass drop, resting on the surface (its own glass body floats and bobs);
 *    tap clinks and plays/pauses, drag sideways changes the song as before, and it leaves a wake.
 *  - The waterline is the seek line: the elapsed part glows along the surface, the knob is a bead on
 *    it; dragging seeks and sloshes the water under the finger (Drip, rate-limited; Tick and Click kept).
 *  - Changing songs: the old cover tilts, sinks, refracts and fades, with bubbles rising and popping;
 *    the new one bobs up from below, breaks the surface (ripples, ring, Plunk) and settles.
 *  - Closing: the cover lifts out of the water (Rise), drips fall back (Drip), then it morphs home.
 *
 * Glass bodies: cover + orb (2), plus while it lasts the sinking cover (1), up to 6 bubbles and 3 drips.
 * All are freed when the player leaves. Home's own glass (dock, buttons) is veiled while it is open.
 */

private val CoverGlass = GlassStyle(corner = 3.dp, bevel = 14.dp, floats = true, rigid = true)
private val SinkGlass = GlassStyle(corner = 3.dp, bevel = 14.dp, floats = false, rigid = true)
private val OrbGlass = GlassStyle(corner = 999.dp, bevel = 8.dp, lens = 0.12f, floats = true, rigid = true)
private val BubbleGlass = GlassStyle(corner = 999.dp, bevel = 3.dp, lens = 0.85f, rigid = true)
private val DripGlass = GlassStyle(corner = 999.dp, bevel = 3.dp, lens = 0.9f, rigid = true)

/** The part of the cover that rides above the water. */
private const val ABOVE = 0.78f
/** PlayerOrb's band height (dp) and where in it the orb's centre sits. */
private const val BAND_DP = 104f
private const val BAND_CY = 0.46f
/** The orb's centre above the waterline, in orb radii: a drop resting on the surface. */
private const val ORB_RIDE = 0.35f
/** Sink progress points at which a bubble leaves the going-under cover. */
private val BUBBLE_AT = floatArrayOf(0.08f, 0.16f, 0.25f, 0.34f, 0.46f, 0.6f)

/**
 * The same picture? (The previous cover is often re-sent, re-decoded, with the next song's title.)
 * Compares 49 sampled pixels; cheap enough to run on each composition while a stale cover is held.
 */
private fun sameArt(a: android.graphics.Bitmap?, b: android.graphics.Bitmap?): Boolean {
    if (a == null || b == null) return false
    if (a === b) return true
    if (a.width != b.width || a.height != b.height) return false
    return runCatching {
        for (i in 1..7) for (j in 1..7) {
            val x = a.width * i / 8; val y = a.height * j / 8
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            var diff = 0
            for (sh in intArrayOf(0, 8, 16)) diff += abs(((p shr sh) and 0xFF) - ((q shr sh) and 0xFF))
            if (diff > 24) return@runCatching false
        }
        true
    }.getOrDefault(false)
}

private fun smooth(a: Float, b: Float, v: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** Home's glass alpha, veiled while the player covers Home (wraps an anchor's own alpha). */
private class Veiled(val orig: (() -> Float)?, val owner: PoolState) : () -> Float {
    override fun invoke(): Float = (orig?.invoke() ?: 1f) * (1f - owner.veil)
}

/**
 * Everything the underwater player moves per frame. Plain fields, stepped once per frame by
 * [step]; drawing reads [frame] (bumped when something drawn changed) and [WaterHost.tick].
 * Positions are world px (the water's coordinates).
 */
@Stable
internal class PoolState(val host: WaterHost) {
    var frame by mutableLongStateOf(0L)
    var layout by mutableIntStateOf(0)

    // ---- layout (world px)
    var rootX = 0f; var rootY = 0f; var rootW = 0f
    var slotL = 0f; var slotT = 0f; var slotS = 0f
    val ready get() = slotS > 0f
    /** Where the player keeps the waterline: across the cover, [ABOVE] of it in the air. */
    val targetLine get() = slotT + slotS * ABOVE
    var artRect by mutableStateOf<Rect?>(null)

    // ---- animations (driven by coroutines, read by step)
    val landedA = Animatable(0f)
    val liftA = Animatable(0f)
    val sinkA = Animatable(0f)
    val riseA = Animatable(0f)
    val showA = Animatable(1f)
    val veilA = Animatable(0f)
    val tintA = Animatable(0f)
    val dragA = Animatable(0f)

    // ---- the water
    var line = 0f
    var pulling = true
    var closing = false
    var glassOut = false
    var veil = 0f

    // ---- the cover (the main one)
    var coverId = -1; var orbId = -1; var sinkId = -1
    var landed = 0f
    private var dip = 0f; private var dipV = 0f
    private var follow = 1f
    private var coverX = 0f; private var lastCoverX = 0f; private var coverTravel = 0f
    var mainOffX = 0f; var mainOffY = 0f; var mainAlpha = 1f
    var mainHidden = false
    var rising = false; var riseDir = 0; var broke = false; var riseGen = 0
    private var lastBottom = Float.NaN
    private var liftedOut = false

    // ---- the sinking cover
    var sinkTrack by mutableStateOf<Track?>(null)
    var sinkGen = 0
    var sinkDir = 0
    private var sinkX0 = 0f; private var sinkY0 = 0f; private var sinkClosed = false; private var bubbleNext = 0
    var sinkOffX = 0f; var sinkOffY = 0f; var sinkScale = 1f; var sinkRot = 0f; var sinkAlpha = 1f

    // ---- the orb
    var orbBand = 0f
    private var lastOrbX = Float.NaN; private var orbTravel = 0f

    // ---- the beat
    private var lastBeat = Long.MIN_VALUE

    // ---- seeking
    var seek by mutableStateOf<Float?>(null)
    var heldPos = -1L; var heldAt = 0L

    // ---- bubbles and drips (glass bodies)
    private val bId = IntArray(6) { -1 }
    private val bX = FloatArray(6); private val bY = FloatArray(6); private val bVy = FloatArray(6)
    private val bR = FloatArray(6); private val bAge = FloatArray(6); private val bPh = FloatArray(6)
    private val dId = IntArray(3) { -1 }
    private val dX = FloatArray(3); private val dY = FloatArray(3); private val dVy = FloatArray(3)
    private val dR = FloatArray(3); private val dAge = FloatArray(3); private val dWait = FloatArray(3); private val dHang = FloatArray(3)
    private var dripsSpawned = false
    private val rnd = java.util.Random()

    // ---- the water borrowed from Home: level and tint, handed back on close
    private var savedLevel = -1f
    private var heldLevel = -1f
    private var levelReleased = false
    private val pal = arrayOf(host.pDk, host.pAc, host.pMd, host.pLt, host.pSheen)
    private val savedPal = Array(5) { FloatArray(3) }
    private val wrotePal = Array(5) { FloatArray(3) }
    private var palSaved = false
    /** The song's water colours (dark, accent, mid, light, sheen), animated by the screen. */
    val song = Array(5) { FloatArray(3) }
    private var lastSig = 0f
    private var lastBump = 0L

    fun setSong(tones: List<Color>, sheen: Color) {
        for (i in 0 until 4) { song[i][0] = tones[i].red; song[i][1] = tones[i].green; song[i][2] = tones[i].blue }
        song[4][0] = sheen.red; song[4][1] = sheen.green; song[4][2] = sheen.blue
    }

    fun measureRoot(x: Float, y: Float, w: Float) {
        if (abs(x - rootX) > 0.5f || abs(y - rootY) > 0.5f || abs(w - rootW) > 0.5f) { rootX = x; rootY = y; rootW = w; layout++ }
    }

    fun measureSlot(l: Float, t: Float, s: Float) {
        if (abs(l - slotL) > 0.5f || abs(t - slotT) > 0.5f || abs(s - slotS) > 0.5f) {
            slotL = l; slotT = t; slotS = s; layout++
            artRect = Rect(l - rootX, t - rootY, l - rootX + s, t - rootY + s)
        }
    }

    // ================================================================ borrowing the water

    private fun holdLevel() {
        val h = host.h
        if (h <= 0f || !ready || levelReleased) return
        val want = (1f - (targetLine / h - WaterHost.TOP) / (WaterHost.BOTTOM - WaterHost.TOP)).coerceIn(0f, 1f)
        // Anything else that set the level since our last frame is the battery: remember it for later.
        if (savedLevel < 0f || (heldLevel >= 0f && abs(host.level - heldLevel) > 1e-4f)) savedLevel = host.level
        if (abs(host.level - want) > 1e-4f) host.setLevel(want, host.charging)
        heldLevel = want
    }

    fun releaseLevel() {
        if (levelReleased) return
        levelReleased = true
        if (savedLevel >= 0f) {
            if (heldLevel >= 0f && abs(host.level - heldLevel) > 1e-4f) savedLevel = host.level
            host.setLevel(savedLevel, host.charging)
        }
        host.wake()
    }

    private fun tintWater(k: Float) {
        var foreign = !palSaved
        if (palSaved) for (i in 0 until 5) for (j in 0 until 3) if (pal[i][j] != wrotePal[i][j]) foreign = true
        // The engine (re)set the painting's colours: those are what we hand back.
        if (foreign) { for (i in 0 until 5) pal[i].copyInto(savedPal[i]); palSaved = true }
        var changed = false
        for (i in 0 until 5) for (j in 0 until 3) {
            val v = savedPal[i][j] + (song[i][j] - savedPal[i][j]) * k
            if (pal[i][j] != v) { pal[i][j] = v; changed = true }
            wrotePal[i][j] = v
        }
        if (changed) host.wake()
    }

    private fun restorePalette() {
        if (!palSaved) return
        var foreign = false
        for (i in 0 until 5) for (j in 0 until 3) if (pal[i][j] != wrotePal[i][j]) foreign = true
        if (!foreign) for (i in 0 until 5) savedPal[i].copyInto(pal[i])
        host.wake()
    }

    private fun veilOthers() {
        val g = host.sim.glass
        for (i in 0 until g.n) {
            if (!g.used[i] || host.anchors[i] == null) continue
            val cur = host.alphaOf[i]
            if (cur is Veiled) continue
            host.alphaOf[i] = Veiled(cur, this)
        }
    }

    private fun unveil() {
        for (i in 0 until WaterSim.MAX_GLASS) {
            val cur = host.alphaOf[i]
            if (cur is Veiled && cur.owner === this) host.alphaOf[i] = cur.orig
        }
    }

    // ================================================================ the frame

    fun step(dt: Float, orb: OrbState, playing: Boolean, still: Boolean, now: Long) {
        if (!ready || host.w <= 0f) return
        val d = host.dp
        val S = slotS
        holdLevel()
        tintWater(tintA.value)
        veil = veilA.value
        veilOthers()
        landed = landedA.value

        // The water: while opening it is pulled to the cover quickly (the sim's own spring takes ~1.5 s).
        line = host.sim.line[0]
        if (pulling && !still && !levelReleased) {
            val diff = targetLine - line
            if (abs(diff) > 0.4f * d) {
                host.sim.line[0] += diff * (1f - exp(-dt * 6.5f))
                host.sim.line[1] *= 0.5f
                line = host.sim.line[0]
                host.wake()
            }
        }

        // The raft's own dip (landing, beats), on a soft underdamped spring.
        val k = 70f
        dipV += (-k * dip - 2f * sqrt(k) * 0.26f * dipV) * dt
        dip += dipV * dt

        // The beat: the orb's own (a peak 1.55 times a second), only while playing.
        if (playing && !still && landed > 0.95f && !closing && !rising && sinkTrack == null) {
            val b = floor(orb.time * 1.55f - 0.25f).toLong()
            if (b != lastBeat) { if (lastBeat != Long.MIN_VALUE) beat(); lastBeat = b }
        } else lastBeat = Long.MIN_VALUE

        // The cover follows the orb's drag (softly limited), not while a song change runs.
        val lim = max(rootW * 0.2f, 1f)
        val fTarget = if (mainHidden || rising) 0f else 1f
        follow = if (fTarget < follow) fTarget else follow + (fTarget - follow) * min(1f, dt * 5f)
        coverX = follow * lim * tanh(orb.coverX / lim)

        val lift = liftA.value * (S * (1f - ABOVE) + 22f * d)
        val rise = if (rising) riseA.value else 0f
        mainOffX = coverX
        mainOffY = (line - targetLine) * landed + dip - lift + dragA.value + rise * S
        mainAlpha = when { rising -> showA.value; mainHidden -> 0f; else -> 1f }
        val ccx = slotL + S / 2f + mainOffX
        val ccy = slotT + S / 2f + mainOffY
        val g = host.sim.glass
        if (coverId >= 0) {
            host.placeGlass(coverId, ccx, ccy, S / 2f, S / 2f)
            host.glassAlpha(coverId, if (glassOut) 0f else landed * mainAlpha)
            if (rising) { g.rot[coverId] = riseDir * 0.07f * rise.coerceIn(0f, 1f); g.vrot[coverId] = 0f }
        }

        // Sliding sideways pushes the water at the leading edge and leaves a wake at the trailing one.
        val dx = coverX - lastCoverX
        lastCoverX = coverX
        if (landed > 0.5f && !still && abs(dx) < 80f * d) {
            coverTravel += abs(dx)
            val v = abs(dx) / dt
            if (coverTravel > 9f * d && v > 50f * d) {
                coverTravel = 0f
                val sg = sign(dx)
                val s = (v / (900f * d)).coerceIn(0.14f, 0.55f)
                host.ripple(ccx + sg * S * 0.5f, line + 4f * d, s)
                host.ripple(ccx - sg * S * 0.5f, line + 6f * d, s * 0.7f)
                host.wake()
            }
        }

        // Lifting out on close: the surface lets go of the bottom edge, and drips fall back.
        val bottom = ccy + S / 2f
        if (closing && !liftedOut && !lastBottom.isNaN() && lastBottom > line && bottom <= line + 2f * d) {
            liftedOut = true
            for (f in floatArrayOf(-0.38f, 0f, 0.38f)) host.ripple(ccx + f * S, line + 2f * d, 0.42f)
            host.wake()
        }
        lastBottom = bottom
        if (closing && liftedOut && !dripsSpawned && !still) { dripsSpawned = true; spawnDrips(ccx, S) }
        stepDrips(dt, ccx, bottom)

        // The orb: a glass drop resting on the surface, leaving a wake as it moves.
        val R = OrbRadius.value * d
        val ox = rootX + rootW / 2f + orb.x
        val oy = line - ORB_RIDE * R
        if (orbId >= 0) {
            host.placeGlass(orbId, ox, oy, R * 0.98f, R * 0.98f)
            // It condenses once the water has reached the player's line (not on Home's waterline).
            val near = 1f - smooth(8f * d, 40f * d, abs(line - targetLine))
            host.glassAlpha(orbId, if (glassOut) 0f else veil * max(near, landed))
        }
        orbBand = line - targetLine
        if (!lastOrbX.isNaN()) {
            val odx = ox - lastOrbX
            // (a jump is the orb coming back from the other side after a song change: no wake for that)
            if (!still && abs(odx) < 120f * d) {
                orbTravel += abs(odx)
                val ov = abs(odx) / dt
                if (orbTravel > 7f * d && ov > 70f * d) {
                    orbTravel = 0f
                    val s = (ov / (1400f * d)).coerceIn(0.12f, 0.55f)
                    host.ripple(ox - sign(odx) * R * 0.85f, line + 3f * d, s)
                    host.wake()
                }
            }
        }
        lastOrbX = ox

        // The old cover going under.
        if (sinkTrack != null) stepSink(S)
        stepBubbles(dt)

        // Redraw only when something drawn changed (and at 4 Hz while playing, for the seek knob).
        val sig = mainOffX * 1.3f + mainOffY * 1.7f + mainAlpha * 97f + sinkOffX + sinkOffY * 2.3f + sinkAlpha * 51f + sinkRot * 300f + orbBand * 0.7f + landed * 13f
        if (abs(sig - lastSig) > 0.01f || (playing && now - lastBump > 250L)) { lastSig = sig; lastBump = now; frame = now }
    }

    fun idle(orb: OrbState, playing: Boolean): Boolean {
        val d = host.dp
        return !playing && !closing && sinkTrack == null && !rising && seek == null &&
            landed >= 1f && !landedA.isRunning && !dragA.isRunning && dragA.value == 0f &&
            !veilA.isRunning && !tintA.isRunning && !orb.moving() &&
            abs(dipV) < 0.5f * d && abs(dip) < 0.3f * d && abs(line - targetLine) < 0.5f * d &&
            bId.all { it < 0 } && dId.all { it < 0 }
    }

    // ================================================================ events

    /** The cover settles onto the water at the end of the morph. */
    fun land() {
        val d = host.dp; val S = slotS
        val cx = slotL + S / 2f
        dipV += 150f * d
        host.ripple(cx - 0.42f * S, line + 3f * d, 0.5f)
        host.ripple(cx, line + 3f * d, 0.7f)
        host.ripple(cx + 0.42f * S, line + 3f * d, 0.5f)
        host.ring(cx, line + 10f * d, 0.55f)
        host.feel(Feel.Plunk)
        host.wake()
    }

    private fun beat() {
        val d = host.dp; val S = slotS
        val cx = slotL + S / 2f + mainOffX
        // A soft ring from under the cover, the raft dips with it, the water at its corners stirs.
        host.ring(cx, line + S * 0.1f, 0.32f)
        host.ripple(slotL + mainOffX + 4f * d, line + 6f * d, 0.14f)
        host.ripple(slotL + S + mainOffX - 4f * d, line + 6f * d, 0.14f)
        dipV += 24f * d
        host.wake()
    }

    /** The cover rising with the new song breaks the surface. */
    private fun breakSurface(cx: Float) {
        val d = host.dp; val S = slotS
        for (f in floatArrayOf(-0.36f, -0.12f, 0.12f, 0.36f)) host.ripple(cx + f * S, line + 2f * d, 0.65f)
        host.ring(cx, line, 0.6f)
        host.feel(Feel.Plunk)
        host.wake()
    }

    /** Hand the floating glass to the old cover (it goes down with it); a fresh body rises with the new one. */
    fun beginSink(dir: Int) {
        sinkDir = dir
        sinkX0 = mainOffX; sinkY0 = mainOffY - (if (rising) riseA.value * slotS else 0f)
        sinkOffX = sinkX0; sinkOffY = sinkY0; sinkScale = 1f; sinkRot = 0f; sinkAlpha = 1f
        sinkClosed = false; bubbleNext = 0
        if (sinkId >= 0) host.freeGlass(sinkId)
        sinkId = coverId
        if (sinkId >= 0) host.styleGlass(sinkId, SinkGlass)
        coverId = host.obtainGlass(CoverGlass)
        mainHidden = true
        rising = false
        host.wake()
    }

    fun endSink() {
        if (sinkId >= 0) host.freeGlass(sinkId)
        sinkId = -1
        sinkTrack = null
        host.wake()
    }

    private fun stepSink(S: Float) {
        val d = host.dp
        val s = sinkA.value
        val e = smooth(0f, 1f, s)
        // Buoyancy lost slowly, then it goes: down faster and faster, drifting the way it was flung.
        sinkOffX = sinkX0 + sinkDir * rootW * 0.1f * e
        sinkOffY = sinkY0 + S * 1.3f * s.pow(1.55f)
        sinkScale = 1f - 0.12f * e
        sinkRot = (if (sinkDir == 0) 0.1f else sinkDir * 0.2f) * e
        sinkAlpha = 1f - smooth(0.4f, 1f, s)
        val cx = slotL + S / 2f + sinkOffX
        val cy = slotT + S / 2f + sinkOffY
        val half = S / 2f * sinkScale
        if (sinkId >= 0) {
            host.placeGlass(sinkId, cx, cy, half, half)
            host.sim.glass.rot[sinkId] = sinkRot; host.sim.glass.vrot[sinkId] = 0f
            host.glassAlpha(sinkId, 1f - smooth(0.3f, 0.85f, s))
        }
        // The surface closes over its top edge.
        if (!sinkClosed && cy - half > line + 2f * d) {
            sinkClosed = true
            for (f in floatArrayOf(-0.3f, 0.05f, 0.34f)) host.ripple(cx + f * S, line + 3f * d, 0.42f)
            host.feel(Feel.Drip)
            host.wake()
        }
        while (bubbleNext < BUBBLE_AT.size && s >= BUBBLE_AT[bubbleNext]) { spawnBubble(cx, cy, half); bubbleNext++ }
    }

    fun stepRise() {
        if (!rising || broke) return
        if (riseA.value < ABOVE) { broke = true; breakSurface(slotL + slotS / 2f + mainOffX) }
    }

    private fun spawnBubble(cx: Float, cy: Float, half: Float) {
        val d = host.dp
        val slot = bId.indexOfFirst { it < 0 }
        if (slot < 0) return
        val id = host.obtainGlass(BubbleGlass)
        if (id < 0) return
        bId[slot] = id
        bR[slot] = (2.4f + rnd.nextFloat() * 3.8f) * d
        bX[slot] = cx + (rnd.nextFloat() - 0.5f) * half * 1.7f
        // From the cover's upper part, but always in the water.
        bY[slot] = max(cy - half + rnd.nextFloat() * half * 0.6f, line + 14f * d + bR[slot])
        bVy[slot] = -30f * d
        bAge[slot] = 0f
        bPh[slot] = rnd.nextFloat() * 6.28f
        host.placeGlass(id, bX[slot], bY[slot], bR[slot], bR[slot])
    }

    private fun stepBubbles(dt: Float) {
        val d = host.dp
        for (i in bId.indices) {
            val id = bId[i]
            if (id < 0) continue
            bAge[i] += dt
            // Buoyancy against drag: small ones rise slower, everything wobbles.
            val vmax = (150f + bR[i] / d * 22f) * d
            bVy[i] = max(bVy[i] - 900f * d * dt, -vmax)
            bY[i] += bVy[i] * dt
            val x = bX[i] + sin(bAge[i] * 10f + bPh[i]) * bR[i] * 0.55f
            val wy = host.waterY(x)
            if (bY[i] - bR[i] <= wy) {
                host.ripple(x, wy + 1f * d, 0.2f + bR[i] / (18f * d))
                host.feel(if (bR[i] > 5f * d) Feel.Pop else Feel.Drip)
                host.freeGlass(id); bId[i] = -1
                host.wake()
                continue
            }
            val flat = 0.14f * min(1f, abs(bVy[i]) / (300f * d))
            host.placeGlass(id, x, bY[i], bR[i] * (1f + flat * 0.5f), bR[i] * (1f - flat))
        }
    }

    private fun spawnDrips(cx: Float, S: Float) {
        val d = host.dp
        val fx = floatArrayOf(-0.27f, 0.08f, 0.34f)
        val wait = floatArrayOf(0f, 0.06f, 0.11f)
        for (i in 0 until 3) {
            if (dId[i] >= 0) continue
            val id = host.obtainGlass(DripGlass)
            if (id < 0) continue
            dId[i] = id
            dX[i] = cx + fx[i] * S + (rnd.nextFloat() - 0.5f) * 10f * d
            dR[i] = (3.4f + rnd.nextFloat() * 1.6f) * d
            dAge[i] = 0f; dVy[i] = 0f; dWait[i] = wait[i]; dHang[i] = 0.07f + rnd.nextFloat() * 0.05f
            dY[i] = Float.NaN
            host.glassAlpha(id, 0f)
        }
    }

    private fun stepDrips(dt: Float, cx: Float, bottom: Float) {
        val d = host.dp
        for (i in dId.indices) {
            val id = dId[i]
            if (id < 0) continue
            dAge[i] += dt
            val t = dAge[i] - dWait[i]
            if (t < 0f) continue
            val x = dX[i] + (cx - (slotL + slotS / 2f + mainOffX))
            if (t < dHang[i]) {
                // Gathering under the edge: it grows and hangs.
                val g = t / dHang[i]
                val r = dR[i] * (0.35f + 0.65f * g)
                dY[i] = bottom + r * 0.6f
                host.glassAlpha(id, 1f)
                host.placeGlass(id, x, dY[i], r * 0.92f, r * (1f + 0.25f * g))
                continue
            }
            dVy[i] += 2600f * d * dt
            dY[i] += dVy[i] * dt
            val wy = host.waterY(x)
            if (dY[i] + dR[i] * 0.4f >= wy) {
                host.ripple(x, wy + 2f * d, 0.34f)
                host.feel(Feel.Drip)
                host.freeGlass(id); dId[i] = -1
                host.wake()
                continue
            }
            val st = (dVy[i] / (1800f * d)).coerceIn(0f, 0.5f)
            host.placeGlass(id, x, dY[i], dR[i] * (1f - st * 0.3f), dR[i] * (1f + st))
        }
    }

    /** The morph home takes the cover and the orb: their glass goes at once (a fading body would linger as a ghost). */
    fun dropGlass() {
        for (id in intArrayOf(coverId, orbId, sinkId)) if (id >= 0) host.freeGlass(id)
        coverId = -1; orbId = -1; sinkId = -1
        glassOut = true
        host.wake()
    }

    fun dispose() {
        for (id in intArrayOf(coverId, orbId, sinkId)) if (id >= 0) host.freeGlass(id)
        coverId = -1; orbId = -1; sinkId = -1
        for (i in bId.indices) if (bId[i] >= 0) { host.freeGlass(bId[i]); bId[i] = -1 }
        for (i in dId.indices) if (dId[i] >= 0) { host.freeGlass(dId[i]); dId[i] = -1 }
        unveil()
        restorePalette()
        releaseLevel()
        host.wake()
    }
}

/**
 * The underwater edition's expanded player. Same contract as the classic [app.quiet.launcher.feature.player.PlayerScreen]
 * (which calls this when [app.quiet.launcher.feature.underwater.engine.LocalWater] is set).
 */
@Composable
fun SharedTransitionScope.UnderwaterPlayerScreen(
    host: WaterHost,
    np: NowPlaying,
    repo: NowPlayingRepository,
    visibility: AnimatedVisibilityScope,
    opening: Boolean,
    ambientStrength: Float,
    onClose: () -> Unit,
) {
    val c = Q.colors
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val d = density.density
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val still = Q.reducedMotion || host.still
    val st = remember(host) { PoolState(host) }
    val orb = rememberOrbState()
    val playing by rememberUpdatedState(np.isPlaying)
    val stillNow by rememberUpdatedState(still)
    val latest by rememberUpdatedState(np)
    val onCloseNow by rememberUpdatedState(onClose)

    // ---- the water takes the song's colours (cross-fading through the tint on a song change)
    val tones = tonePalette(np.palette)
    val t0 by animateColorAsState(tones[0], tween(1200), label = "w0")
    val t1 by animateColorAsState(tones[1], tween(1200), label = "w1")
    val t2 by animateColorAsState(tones[2], tween(1200), label = "w2")
    val t3 by animateColorAsState(tones[3], tween(1200), label = "w3")
    val sheenTarget = Color(app.quiet.launcher.feature.lock.PaintingTint.sheen(np.palette.getOrNull(3) ?: 0xFFBFE3EC.toInt()))
    val sheen by animateColorAsState(sheenTarget, tween(1200), label = "ws")
    SideEffect { st.setSong(listOf(t0, t1, t2, t3), sheen) }

    // ---- the painting behind the water
    val art by ctx.container.art.today.collectAsStateWithLifecycle()
    val swatches = app.quiet.launcher.feature.lock.charge.rememberPaintingSwatches(art?.imagePath)

    // ---- glass bodies for the life of the player
    DisposableEffect(st) {
        st.coverId = host.obtainGlass(CoverGlass)
        st.orbId = host.obtainGlass(OrbGlass)
        onDispose { st.dispose() }
    }

    // ---- the frame
    LaunchedEffect(st) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = t
                st.step(dt, orb, playing, stillNow, SystemClock.uptimeMillis())
                st.stepRise()
            }
            if (st.idle(orb, playing)) { last = 0L; delay(100) }
        }
    }

    // ---- opening: the morph lands, then the cover settles onto the water
    LaunchedEffect(st) {
        launch { st.veilA.animateTo(1f, tween(170)) }
        launch { st.tintA.animateTo(1f, tween(900)) }
        delay(80)
        withTimeoutOrNull(1200) { snapshotFlow { isTransitionActive }.first { !it } }
        withTimeoutOrNull(700) { while (abs(st.line - st.targetLine) > 6f * d) delay(16) }
        if (!stillNow) st.land()
        st.landedA.animateTo(1f, tween(380, easing = QMotion.EaseOutQuiet))
        st.pulling = false
    }

    // ---- closing: lift out (drips fall back), hand the water back, then morph home
    fun close(fromDrag: Boolean) {
        if (st.closing) return
        st.closing = true
        scope.launch {
            if (!fromDrag && !stillNow && st.landed > 0.5f) {
                host.feel(Feel.Rise)
                launch { st.liftA.animateTo(1f, tween(280, easing = QMotion.EaseOutQuiet)) }
                delay(230)
            }
            st.releaseLevel()
            st.dropGlass()
            launch { st.veilA.animateTo(0f, tween(300)) }
            launch { st.tintA.animateTo(0f, tween(320)) }
            onCloseNow()
        }
    }
    BackHandler { close(false) }

    // ---- songs: the cover shown is the one floating; a change sinks it and raises the next
    var shown by remember { mutableStateOf(np.track) }
    // Some players publish the new title with the previous cover for a moment: until the real
    // cover arrives (or 2.5 s pass) the rising cover shows the new title set as a cover instead.
    var staleArt by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var staleJob by remember { mutableStateOf<Job?>(null) }
    val display = shown.let { t -> if (t.art != null && sameArt(t.art, staleArt)) t.copy(art = null) else t }
    var awaiting by remember { mutableStateOf(false) }
    var waitJob by remember { mutableStateOf<Job?>(null) }

    fun startSink(old: Track, dir: Int) {
        st.sinkTrack = old
        staleArt = old.art
        staleJob?.cancel()
        staleJob = scope.launch { delay(2500); staleArt = null }
        st.beginSink(dir)
        val gen = ++st.sinkGen
        scope.launch {
            st.sinkA.snapTo(0f)
            st.sinkA.animateTo(1f, tween(1150, easing = LinearEasing))
            if (st.sinkGen == gen) st.endSink()
        }
    }

    fun startRise(t: Track) {
        shown = t
        val gen = ++st.riseGen
        scope.launch {
            // Let the old cover get under first, so the two don't cross at the surface.
            withTimeoutOrNull(520) { snapshotFlow { st.sinkTrack == null || st.sinkA.value >= 0.42f }.first { it } }
            if (st.riseGen != gen) return@launch
            st.riseDir = st.sinkDir
            st.broke = false
            st.riseA.snapTo(1.06f)
            st.showA.snapTo(0f)
            st.rising = true
            st.mainHidden = false
            launch { st.showA.animateTo(1f, tween(460, easing = QMotion.EaseOutQuiet)) }
            st.riseA.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 68f, visibilityThreshold = 0.0005f))
            if (st.riseGen == gen) st.rising = false
        }
    }

    val onOrbChange: (Int) -> Unit = { dir ->
        if (!stillNow && st.landed > 0.5f && !st.closing) {
            startSink(shown, dir)
            awaiting = true
            waitJob?.cancel()
            // "Previous" may only restart the song: then the same cover comes back up.
            waitJob = scope.launch { delay(900); if (awaiting) { awaiting = false; startRise(latest.track) } }
        }
    }

    LaunchedEffect(np.track) {
        val t = np.track
        if (t.id == shown.id) { shown = t; return@LaunchedEffect } // same song: new metadata or a late cover
        if (stillNow || st.closing || st.landed < 0.5f) { shown = t; return@LaunchedEffect }
        if (awaiting) { awaiting = false; waitJob?.cancel(); startRise(t); return@LaunchedEffect }
        // Changed from elsewhere (the notification, a headset): sink and rise without the orb.
        startSink(shown, 0)
        awaiting = true
        delay(260)
        awaiting = false
        startRise(latest.track)
    }

    // ---- seeking: the hold after a release, as the classic seek line
    LaunchedEffect(np.positionUpdatedAt) { if (st.heldPos >= 0 && np.positionUpdatedAt >= st.heldAt) st.heldPos = -1L }
    fun posNow(): Long {
        val n = latest
        val dur = n.track.durationMs
        val s = st.seek
        if (s != null && dur > 0) return (s * dur).toLong()
        if (st.heldPos >= 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - st.heldAt <= 2000) return (st.heldPos + if (n.isPlaying) now - st.heldAt else 0L).coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
            st.heldPos = -1L
        }
        return n.positionAt(SystemClock.elapsedRealtime())
    }

    val measurer = rememberTextMeasurer()
    val wet = remember { Path() }
    val deep = lerp(t0, Color.Black, 0.35f)
    var screenH by remember { mutableFloatStateOf(2000f) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val p = host.toWorld(it)
                st.measureRoot(p.x, p.y, it.size.width.toFloat())
                screenH = it.size.height.toFloat()
            }
            .background(c.bg)
    ) {
        // The painting under the water, with the song's light pooling around the cover.
        WaterBackdrop(art?.imagePath, swatches)
        AmbientField(
            palette = np.palette, artRect = st.artRect, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.72f },
            maxLum = 0.06f, glow = 1f, pool = 0.7f, strength = ambientStrength, running = np.isPlaying,
            fallbackArt = np.track.art,
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 40.dp)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val raw = st.dragA.value + dy
                            // Up lifts the raft a little out of the water (rubber band); down pushes it under.
                            val next = if (raw < 0f) QMotion.rubberBand(raw, screenH * 0.12f) else raw
                            scope.launch { st.dragA.snapTo(next) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            val landing = st.dragA.value + QMotion.project(v)
                            if (landing > screenH / 3f) close(true)
                            else {
                                // Let go: it bobs back up and splashes in proportion.
                                val push = (st.dragA.value / (120f * d)).coerceIn(0f, 1f)
                                if (push > 0.1f && !stillNow) {
                                    val cx = st.slotL + st.slotS / 2f
                                    host.ripple(cx - st.slotS * 0.45f, st.line + 4f * d, 0.6f * push)
                                    host.ripple(cx + st.slotS * 0.45f, st.line + 4f * d, 0.6f * push)
                                    host.wake()
                                }
                                scope.launch { st.dragA.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 320f), initialVelocity = v) }
                            }
                        },
                        onDragCancel = { scope.launch { st.dragA.animateTo(0f, QMotion.Return) } },
                    )
                },
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).pressable { close(false) }, contentAlignment = Alignment.CenterStart) {
                    QIconView(QIcon.ChevronDown, c.ink)
                }
                Spacer(Modifier.weight(1f))
                BasicText("Playing on this phone", style = QType.secondary(14.sp).copy(color = c.muted))
            }
            Spacer(Modifier.weight(0.55f))
            // The cover's slot: the raft floats here (its waterline is ABOVE of the way down).
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onGloballyPositioned {
                        val p = host.toWorld(it)
                        st.measureSlot(p.x, p.y, it.size.width.toFloat())
                    },
            ) {
                st.sinkTrack?.let { old ->
                    GalleryFrame(
                        art = old.art, fallbackTitle = old.title, matColor = c.mat, matFraction = 15f / 332f,
                        contentKey = old.id, crossfadeMs = 1,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            host.tick; st.frame
                            translationX = st.sinkOffX
                            translationY = st.sinkOffY + (if (st.sinkId >= 0) host.glassDy(st.sinkId) else 0f)
                            rotationZ = Math.toDegrees(st.sinkRot.toDouble()).toFloat()
                            scaleX = st.sinkScale; scaleY = st.sinkScale
                            alpha = st.sinkAlpha
                        },
                    )
                }
                GalleryFrame(
                    art = display.art, fallbackTitle = display.title, matColor = c.mat, matFraction = 15f / 332f,
                    contentKey = display.id to (display.art == null), crossfadeMs = 320,
                    modifier = Modifier
                        // Layout offset (not a layer), so the morph home starts where the cover really is.
                        .offset {
                            host.tick; st.frame
                            val bob = if (st.coverId >= 0) host.glassDy(st.coverId) * st.landed else 0f
                            IntOffset(st.mainOffX.roundToInt(), (st.mainOffY + bob).roundToInt())
                        }
                        .graphicsLayer {
                            host.tick; st.frame
                            rotationZ = if (st.coverId >= 0) host.glassRotation(st.coverId) * (if (st.rising) 1f else st.landed) else 0f
                            alpha = st.mainAlpha
                        }
                        .sharedElement(rememberSharedContentState(CoverKey), visibility, boundsTransform = coverBounds(opening))
                        .fillMaxSize(),
                )
                // Under the surface the raft is seen through the water: darker and tinted with depth.
                Canvas(Modifier.fillMaxSize()) {
                    host.tick; st.frame
                    if (!st.ready || st.mainAlpha <= 0.01f || st.closing) return@Canvas
                    val bob = if (st.coverId >= 0) host.glassDy(st.coverId) * st.landed else 0f
                    val left = st.mainOffX; val top = st.mainOffY + bob
                    val right = left + size.width; val bottom = top + size.height
                    val n = 24
                    wet.reset()
                    var minY = bottom
                    for (i in 0..n) {
                        val x = left + size.width * i / n
                        val y = (host.waterY(x + st.slotL) - st.slotT).coerceIn(top, bottom)
                        minY = min(minY, y)
                        if (i == 0) wet.moveTo(x, y) else wet.lineTo(x, y)
                    }
                    if (minY >= bottom - 1f) return@Canvas
                    wet.lineTo(right, bottom); wet.lineTo(left, bottom); wet.close()
                    drawPath(
                        wet,
                        Brush.verticalGradient(0f to deep.copy(alpha = 0.16f), 1f to deep.copy(alpha = 0.46f), startY = minY, endY = bottom),
                        alpha = st.mainAlpha,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // Under the water: the times and the label (they bend; bright type keeps reading).
            Column(
                with(visibility) {
                    Modifier.animateEnterExit(
                        enter = fadeIn(tween(260, 160, QMotion.EaseOutQuiet)) + slideInVertically(tween(260, 160, QMotion.EaseOutQuiet)) { with(density) { 10.dp.roundToPx() } },
                        exit = fadeOut(tween(120)),
                    )
                }.graphicsLayer { translationY = st.dragA.value * 0.6f }
            ) {
                val live = rememberPosition(np)
                val dur = np.track.durationMs
                val dragging = st.seek != null
                val pos = if (dragging || st.heldPos >= 0) posNow() else live
                Row(Modifier.fillMaxWidth()) {
                    BasicText(Format.mmss(pos), style = QType.secondary(14.sp).copy(color = if (dragging) c.ink else c.muted))
                    Spacer(Modifier.weight(1f))
                    if (dur > 0) BasicText("-" + Format.mmss((dur - pos).coerceAtLeast(0)), style = QType.secondary(14.sp).copy(color = c.muted))
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = display,
                    contentKey = { it.id },
                    transitionSpec = { fadeIn(tween(420, 220)) togetherWith fadeOut(tween(200)) },
                    label = "label",
                ) { t ->
                    MuseumLabel(
                        title = t.title, line2 = t.artist,
                        line3 = listOfNotNull(t.album, t.year).joinToString(", ").ifBlank { null },
                        titleStyle = QType.labelTitle(32.sp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }

        // ---- the waterline is the seek line: the elapsed part glows along the surface.
        val ink = c.ink
        val track = remember { Path() }
        val done = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            host.tick; st.frame
            val dur = latest.track.durationMs
            if (!st.ready || dur <= 0) return@Canvas
            val L = st.slotL - st.rootX
            val R = L + st.slotS
            val f = (posNow() / dur.toFloat()).coerceIn(0f, 1f)
            val grow = if (st.seek != null) 1f else 0f
            val lift = 1.4f * d
            fun yAt(x: Float) = host.waterY(x + st.rootX) - st.rootY - lift
            val n = 64
            track.reset(); done.reset()
            val xf = L + (R - L) * f
            for (i in 0..n) {
                val x = L + (R - L) * i / n
                val y = yAt(x)
                if (i == 0) track.moveTo(x, y) else track.lineTo(x, y)
            }
            val nf = max(1, (n * f).roundToInt())
            for (i in 0..nf) {
                val x = L + (xf - L) * i / nf
                val y = yAt(x)
                if (i == 0) done.moveTo(x, y) else done.lineTo(x, y)
            }
            drawPath(track, Color.White.copy(alpha = 0.16f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (f > 0.002f) {
                drawPath(done, ink.copy(alpha = 0.16f + 0.1f * grow), style = Stroke((5f + 3f * grow).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(done, ink.copy(alpha = 0.92f), style = Stroke((1.6f + 1.2f * grow).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // The knob: a bright bead riding the surface.
            val ky = yAt(xf)
            val kr = (3.5f + 4f * grow).dp.toPx()
            drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.35f), Color.Transparent), Offset(xf, ky), kr * 3.2f), kr * 3.2f, Offset(xf, ky))
            drawCircle(ink, kr, Offset(xf, ky))
            drawCircle(Color.White.copy(alpha = 0.7f), kr * 0.35f, Offset(xf - kr * 0.3f, ky - kr * 0.3f))
            // While seeking, the time rides above the bead.
            val s = st.seek
            if (s != null) {
                val label = Format.mmss((s * dur).toLong())
                val style = QType.secondary(13.sp).copy(color = ink, shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 6f))
                val m = measurer.measure(label, style)
                val tx = (xf - m.size.width / 2f).coerceIn(L - 8.dp.toPx(), R - m.size.width + 8.dp.toPx())
                drawText(m, topLeft = Offset(tx, ky - kr - 10.dp.toPx() - m.size.height))
            }
        }

        // ---- the orb on the surface, inside the seek band (touches off the orb seek).
        val bandH = BAND_DP * d
        val rPx = OrbRadius.value * d
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAND_DP.dp)
                .offset {
                    st.layout
                    IntOffset(0, (st.targetLine - st.rootY - ORB_RIDE * rPx - BAND_CY * bandH).roundToInt())
                }
                .pointerInput(np.track.durationMs) {
                    val dur = np.track.durationMs
                    if (dur <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun frac(px: Float) = ((px + st.rootX - st.slotL) / st.slotS.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        st.seek = frac(down.position.x)
                        haptics.play(Feel.Tick)
                        down.consume()
                        var lastX = down.position.x
                        var travel = 0f
                        horizontalDrag(down.id) { ch ->
                            st.seek = frac(ch.position.x)
                            travel += abs(ch.position.x - lastX)
                            lastX = ch.position.x
                            // The finger sloshes the surface as it goes.
                            if (travel > 12.dp.toPx()) {
                                travel = 0f
                                val wx = ch.position.x + st.rootX
                                host.ripple(wx, host.waterY(wx) + 3.dp.toPx(), 0.32f)
                                host.feel(Feel.Drip)
                                host.wake()
                            }
                            ch.consume()
                        }
                        val f = st.seek ?: return@awaitEachGesture
                        val target = (f * dur).toLong()
                        st.heldPos = target; st.heldAt = SystemClock.elapsedRealtime()
                        st.seek = null
                        repo.seekTo(target)
                        haptics.play(Feel.Click)
                    }
                },
        ) {
            PlayerOrb(
                PlayerControl.GLASS, np, repo, orb,
                modifier = Modifier.graphicsLayer {
                    host.tick; st.frame
                    translationY = st.orbBand + (if (st.orbId >= 0) host.glassDy(st.orbId) else 0f)
                },
                tapFeel = Feel.Clink,
                onSongChange = onOrbChange,
            )
        }
    }
}
