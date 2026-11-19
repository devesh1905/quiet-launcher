package app.quiet.launcher.feature.lock.water

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.feature.lock.charge.Rng
import app.quiet.launcher.feature.underwater.engine.GlassStyle
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlinx.coroutines.channels.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the Water lock theme adds on top of the shared water engine ([WaterHost]): the engine already
 * holds the waterline at the battery level, sloshes it with the tilt, stirs it under fingers and
 * shades water and glass in one pass. This drives the lock's own moments through the engine's API:
 *
 *  - charging: Flood's plug-in jet from the USB port (the same ballistic tip and tail, as a rigid
 *    glass column), crowning into drops that rain back as engine drops, then a steady inflow (a bump
 *    over the port, pulsing rings, rising glass bubbles) while the water is not calm;
 *  - the faint drizzle that keeps the water alive for a few seconds after wake;
 *  - the play orb, a glass drop you tap or drag (song change), its beat rings, and the floating cover's
 *    sideways ride;
 *  - the fingerprint drop on the sensor, and the unlock pull (the meniscus follows the finger up).
 *
 * Fixed 120 Hz substeps like every other simulation here; frames only while something moves.
 * Positions are px in the world layer.
 */
@Stable
internal class WaterLockDriver(val host: WaterHost) {
    val wakeCh = Channel<Unit>(Channel.CONFLATED)
    fun kick() { wakeCh.trySend(Unit) }

    private val rng = Rng(0x5EA1)
    private var acc = 0f
    private var time = 0f
    private val still get() = host.still

    // ---------------------------------------------------------------- charging
    private var plugged = false
    private var demoUntil = 0L
    /** The inflow runs until this (it calms after 12 s without a touch, like Flood). */
    private var flowUntil = 0L
    private var flow = 0f
    private var bumpOn = false
    private var pendingJet = false

    // The jet: tip and tail ride ballistic paths (px from top, px/s).
    private var jetT = -1f
    private var jetOn = 0f
    private var tipY = 0f; private var tipV = 0f
    private var tailY = 0f; private var tailV = 0f
    private var tailFree = false
    private var crowned = false
    private var jetId = -1

    // Bubbles from the port: tiny rigid glass lenses.
    private val bubN = 6
    private val bubId = IntArray(bubN) { -1 }
    private val bx = FloatArray(bubN); private val by = FloatArray(bubN); private val bvy = FloatArray(bubN)
    private val br = FloatArray(bubN); private val bph = FloatArray(bubN)
    private var bubAlive = 0
    private var bubbleClock = 0f
    private var sourceClock = 0f

    // Drizzle after wake.
    var drizzleUntil = 0L
    private var drizzleClock = 0f

    // ---------------------------------------------------------------- orb and cover
    var rowCoords: LayoutCoordinates? = null
        set(v) { field = v; orbDirty = true }
    /** The orb's row moved (or appeared): place the drop once even if nothing else moves. */
    private var orbDirty = false
    var orbR = 30f
    var playing = false
    var showingMusic = false
    var dragging = false
    var dragOff = 0f
    private var flying = 0
    private var flyStart = 0L
    var orbId = -1
        private set
    private var rowCx = 0f
    private var rowCy = 0f
    private var rowW = 0f
    private var orbTarget = 0f
    /** Where the drawn orb is, relative to its row centre (px), for the glyph under the glass. */
    var orbDx = 0f
        private set
    var orbDy = 0f
        private set
    var coverX by mutableFloatStateOf(0f)
        private set
    private var vcx = 0f
    private var beatTime = 0f
    private var lastBeat = 0f
    private var beatArmed = true
    private var beat = 0f
    /** The orb's beat now (0..1; eases to 0 when paused), for the cover's light. */
    val beatNow: Float get() = beat
    var onSongChange: (Int) -> Unit = {}

    // ---------------------------------------------------------------- fingerprint drop and unlock
    private var fpId = -1
    private var fpCx = 0f; private var fpCy = 0f; private var fpR = 0f
    private var fpPress = false
    private var fpGone = false
    private var riseArmed = true

    // ================================================================ events

    /** Plugged in ([jet] = the plug-in moment) or found charging when the lock screen came up. */
    fun plugIn(jet: Boolean) {
        plugged = true
        flowUntil = SystemClock.uptimeMillis() + CALM_MS
        if (jet && !still) pendingJet = true
        kick()
    }

    fun unplug() {
        plugged = false
        if (SystemClock.uptimeMillis() > demoUntil) flowUntil = 0L
        // The inflow stops: the hump over the port sinks back with a small slosh.
        if (!still && host.sim.grid != null) host.sim.surf.kick(0.5f, -50f * host.dp, 3f)
        kick()
    }

    /** The two-finger show-off: the plug-in moment without a charger. */
    fun showOff(): Boolean {
        if (still || host.sim.grid == null) return false
        val now = SystemClock.uptimeMillis()
        demoUntil = now + 8_000L
        flowUntil = max(flowUntil, demoUntil)
        pendingJet = true
        kick()
        return true
    }

    /** A finger touched the scene: the water stays awake (the calm clock restarts). */
    fun touched() {
        if (plugged) flowUntil = SystemClock.uptimeMillis() + CALM_MS
    }

    fun wakeDrizzle() {
        if (still) return
        drizzleUntil = SystemClock.uptimeMillis() + DRIZZLE_MS
        kick()
    }

    // ---- unlock

    /** The unlock drag moved by [dy] (px) with the scene lifted [lift] px: the meniscus follows the finger up. */
    fun unlockDrag(lift: Float, h: Float, x: Float, dy: Float) {
        val past = lift < -h * 0.15f
        if (past && riseArmed) { riseArmed = false; host.feel(Feel.Rise) }
        else if (!past) riseArmed = true
        if (still || host.sim.grid == null || host.w <= 0f) return
        if (dy < 0f) {
            host.sim.surf.kick((x / host.w).coerceIn(0f, 1f), -dy * 9f, 5f)
            host.wake()
        }
    }

    fun unlockEnd() { riseArmed = true }

    // ---- fingerprint drop

    fun sensor(cx: Float, cy: Float, r: Float) {
        fpCx = cx; fpCy = cy; fpR = r
        if (fpId < 0) fpId = host.obtainGlass(
            GlassStyle(corner = 999.dp, bevel = (r / host.dp).dp, lens = 0.9f, stiffness = 520f, damping = 0.5f),
        )
        placeFp()
    }

    fun sensorPress(down: Boolean) {
        if (fpGone || down == fpPress) return
        fpPress = down
        if (down) host.ring(fpCx, fpCy, 0.5f)
        placeFp()
    }

    fun unlocked() {
        if (fpGone) return
        fpGone = true
        placeFp()
        if (fpId >= 0) host.glassAlpha(fpId, 0f)
        host.ring(fpCx, fpCy, 0.9f)
    }

    private fun placeFp() {
        if (fpId < 0) return
        val s = when { fpGone -> 2.2f; fpPress -> 0.82f; else -> 1f }
        host.moveGlass(fpId, fpCx, fpCy, fpR * s, fpR * s)
    }

    // ---- orb

    fun orbTap() {
        host.feel(Feel.Clink)
        // A tap squeezes the drop and lets it ring.
        if (orbId >= 0) host.kickGlass(orbId, 0f, 260f * host.dp)
        kick()
    }

    fun beginDrag() { dragging = true; dragOff = orbDx; kick() }

    fun endDrag(vx: Float) {
        dragging = false
        val fling = abs(vx) > 700f * host.dp
        if (rowW > 0f && (abs(dragOff) > rowW * CHANGE_AT || fling)) {
            flying = if (fling) (if (vx > 0) 1 else -1) else (if (dragOff > 0) 1 else -1)
            flyStart = SystemClock.uptimeMillis()
            if (orbId >= 0) host.kickGlass(orbId, vx * 0.6f, 0f)
        } else if (orbId >= 0) host.kickGlass(orbId, vx * 0.35f, 0f)
        kick()
    }

    // ================================================================ frames

    fun wantsFrames(now: Long): Boolean {
        if (!still) {
            if (pendingJet || jetT >= 0f || flow > 0.01f || bubAlive > 0 || bumpOn) return true
            if (now < drizzleUntil || now < flowUntil) return true
        }
        if (showingMusic && (dragging || flying != 0 || abs(vcx) > 3f || abs(coverX - coverWant()) > 0.5f)) return true
        if (showingMusic && playing && !still) return true
        return showingMusic && orbDirty
    }

    fun frame(dt: Float) {
        val sim = host.sim
        if (sim.grid == null || host.w <= 0f) return
        val now = SystemClock.uptimeMillis()
        if (pendingJet && !still) { pendingJet = false; startJet() } else pendingJet = false
        acc += dt
        var steps = 0
        while (acc >= STEP && steps < 8) {
            acc -= STEP; steps++
            time += STEP
            if (!still) stepWater(STEP, now)
            stepCover(STEP)
        }
        if (steps == 8) acc = 0f
        placeBodies()
        orbFrame(dt, now)
        host.wake()
    }

    private fun stepWater(dt: Float, now: Long) {
        val sim = host.sim
        val w = host.w; val h = host.h; val dp = host.dp
        val gravity = h * 3.8f
        val charging = (plugged || now < demoUntil) && now < flowUntil
        flow += ((if (charging) 0.62f else 0f) - flow) * min(1f, dt * 1.5f)
        // ---- the jet (Flood's): the tip rises on a slowed parabola, the tail follows, it crowns into drops
        if (jetT >= 0f) {
            jetT += dt
            tipV += gravity * dt; tipY += tipV * dt
            if (jetT > 0.42f && !tailFree) { tailFree = true; tailV = tipV * 0.35f - h * 0.6f }
            if (tailFree) { tailV += gravity * dt; tailY += tailV * dt }
            if (!crowned && tipV > -h * 0.05f) {
                crowned = true
                for (i in 0 until 5) {
                    sim.drop(
                        w / 2f + rng.range(-4f, 4f) * dp, tipY, rng.range(3.5f, 7.5f) * dp,
                        rng.range(-0.55f, 0.55f) * h * 0.5f, rng.range(-0.25f, 0.1f) * h, gravity,
                    )
                }
            }
            val surfaceY = sim.waterY(w / 2f)
            if ((tipY > surfaceY && tipV > 0f) || tailY <= tipY + 2f * dp || jetT > 2.5f) {
                jetT = -1f; jetOn = 0f
                sim.ripple(w / 2f, min(surfaceY, h - 2f) + 2f * dp, 260f, 2.6f)
                host.feel(Feel.Plunk)
            } else {
                jetOn = 1f
                // The jet pours into the pool: rings where it enters.
                if (surfaceY < h) sim.ripple(w / 2f, surfaceY + 4f * dp, 90f * sin(time * 40f), 1.8f)
            }
        }
        // ---- the inflow hump over the port (Flood's bump; the engine levels it with the tilt)
        val n = sim.surf.n
        val amp = dp * (5f * flow + 22f * jetOn)
        if (amp > 0.01f * dp || bumpOn) {
            for (i in 0 until n) { val d = i - 0.5f * (n - 1); sim.surf.bump[i] = exp(-d * d / 18f) * amp }
            bumpOn = amp > 0.01f * dp
            if (!bumpOn) sim.surf.clearBump()
        }
        // ---- steady inflow: the port pulses rings and breathes out bubbles
        if (flow > 0.01f) {
            sourceClock += dt
            sim.ripple(w / 2f, h - 2f * dp, flow * 70f * sin(sourceClock * 13f), 1.6f)
            bubbleClock += dt * 4.5f * flow
            while (bubbleClock > 1f) { bubbleClock -= 1f; spawnBubble() }
        }
        // ---- bubbles: buoyancy against drag, a wobble, a pop at the surface
        bubAlive = 0
        for (i in 0 until bubN) {
            if (br[i] <= 0f) continue
            val buoy = h * 0.9f * (br[i] / (4f * dp))
            bvy[i] += (-buoy - 3.2f * bvy[i]) * dt
            by[i] += bvy[i] * dt
            bph[i] += dt * 9f
            bx[i] += sin(bph[i]) * dp * 22f * dt
            val wy = sim.waterY(bx[i])
            if (by[i] - br[i] <= wy) {
                sim.ripple(bx[i], wy + 2f * dp, 40f, 1.1f)
                br[i] = 0f
                continue
            }
            bubAlive++
        }
        // ---- a faint drizzle: the water is never dead still just after wake (and while it charges)
        if (now < drizzleUntil || flow > 0.05f) {
            drizzleClock += dt
            if (drizzleClock > 0.11f) {
                drizzleClock = 0f
                val x = rng.range(0.03f, 0.97f) * w
                val wy = sim.waterY(x)
                if (wy < h - 10f * dp) sim.ripple(x, rng.range(wy + 8f * dp, h - 2f * dp), rng.range(-14f, 14f), 1.2f)
            }
        }
    }

    private fun startJet() {
        val w = host.w; val h = host.h; val dp = host.dp
        val gravity = h * 3.8f
        // Up to about 62% of the screen as in Flood, but always well clear of the water.
        val surface = host.sim.waterY(w / 2f)
        val apex = min(h * 0.38f, surface - h * 0.18f).coerceAtLeast(h * 0.12f)
        jetT = 0f; jetOn = 1f; crowned = false; tailFree = false
        tipY = h; tailY = h
        tipV = -sqrt(2f * gravity * (h - apex))
        tailV = 0f
        if (jetId < 0) jetId = host.obtainGlass(GlassStyle(corner = 999.dp, bevel = 7.dp, lens = 0.3f, rigid = true, growIn = false))
        host.feel(Feel.Rise)
        flowUntil = max(flowUntil, SystemClock.uptimeMillis() + CALM_MS)
    }

    private fun spawnBubble() {
        val h = host.h; val dp = host.dp; val w = host.w
        val y0 = h - 3f * dp
        if (host.sim.waterY(w / 2f) > y0 - 6f * dp) return
        for (i in 0 until bubN) if (br[i] <= 0f) {
            if (bubId[i] < 0) bubId[i] = host.obtainGlass(GlassStyle(corner = 999.dp, bevel = 3.dp, lens = 1f, rigid = true, growIn = false))
            bx[i] = w / 2f + rng.range(-7f, 7f) * dp; by[i] = y0; bvy[i] = -h * 0.05f
            br[i] = rng.range(1.6f, 3.8f) * dp; bph[i] = rng.range(0f, 6.28f)
            return
        }
    }

    private fun placeBodies() {
        val w = host.w; val h = host.h; val dp = host.dp
        if (jetId >= 0) {
            if (jetT >= 0f) {
                val hw = 8f * dp
                val tail = min(tailY, h + 20f * dp)
                val top = min(tipY, tail - 2f * hw)
                host.placeGlass(jetId, w / 2f, (top + tail) / 2f, hw, (tail - top) / 2f)
                host.glassAlpha(jetId, 1f)
            } else host.glassAlpha(jetId, 0f)
        }
        for (i in 0 until bubN) {
            val id = bubId[i]
            if (id < 0) continue
            if (br[i] > 0f) { host.placeGlass(id, bx[i], by[i], br[i], br[i]); host.glassAlpha(id, 1f) }
            else host.glassAlpha(id, 0f)
        }
    }

    // ---- orb and cover

    private fun coverWant(): Float = orbDx * (if (flying != 0) 1.25f else 0.6f)

    private fun stepCover(dt: Float) {
        val k = if (dragging) 600f else 220f
        val z = if (dragging) 1f else 0.62f
        val c = 2f * sqrt(k) * z
        vcx += (-k * (coverX - coverWant()) - c * vcx) * dt
        coverX += vcx * dt
    }

    private fun orbFrame(dt: Float, now: Long) {
        val row = rowCoords?.takeIf { it.isAttached && showingMusic } ?: return
        orbDirty = false
        val c = host.toWorld(row, Offset(row.size.width / 2f, row.size.height * 0.46f))
        rowCx = c.x; rowCy = c.y; rowW = row.size.width.toFloat()
        if (orbId < 0) {
            orbId = host.obtainGlass(GlassStyle(corner = 999.dp, bevel = 17.dp, lens = 0.5f, floats = true, stir = true, stiffness = 190f, damping = 0.42f))
        }
        // The beat (the orb's own slow curve, as on the classic lock player) sends soft rings out.
        if (playing && !still) {
            beatTime += dt
            beat = max(0f, sin(beatTime * 2f * PI.toFloat() * 1.55f)).pow(6)
            if (beatArmed && beat > 0.55f && beat > lastBeat) {
                beatArmed = false
                val ox = rowCx + orbDx; val oy = rowCy + orbDy
                // A soft refraction ring that travels out from the drop (the grid splash alone was
                // too faint to read on the phone), plus the splash itself while under water.
                host.ring(ox, oy, 0.22f)
                if (host.isUnder(ox, oy)) host.sim.ripple(ox, oy, 150f, 1.8f)
            }
            if (beat < 0.2f) beatArmed = true
            lastBeat = beat
        } else { beat *= 0.85f; lastBeat = 0f; beatArmed = true }
        if (flying != 0 && (orbDx * flying > rowW * 0.72f || now - flyStart > 420L)) {
            val dir = flying
            flying = 0
            onSongChange(dir)
            host.feel(Feel.Pop)
            // The new song's drop comes in from the other side, and the cover with it.
            host.placeGlass(orbId, rowCx - dir * rowW * 0.62f, rowCy, orbR, orbR)
            host.kickGlass(orbId, dir * 900f * host.dp, 0f)
            coverX = -dir * rowW * 0.9f
            vcx = dir * 400f * host.dp
        }
        orbTarget = when { dragging -> dragOff; flying != 0 -> flying * rowW * 0.85f; else -> 0f }
        val s = orbR * (1f + 0.06f * beat)
        host.holdGlass(orbId, dragging)
        host.moveGlass(orbId, rowCx + orbTarget, rowCy, s, s)
        orbDx = orbTarget + host.glassDx(orbId)
        orbDy = host.glassDy(orbId)
    }

    /** The orb's drawn alpha cue for the cover (0 at rest .. 1 far away). */
    fun away(): Float = if (rowW > 0f) (abs(coverX) / rowW).coerceIn(0f, 1f) else 0f

    fun release() {
        for (id in bubId) if (id >= 0) host.freeGlass(id)
        bubId.fill(-1)
        if (jetId >= 0) host.freeGlass(jetId); jetId = -1
        if (orbId >= 0) host.freeGlass(orbId); orbId = -1
        if (fpId >= 0) host.freeGlass(fpId); fpId = -1
        if (bumpOn) { host.sim.surf.clearBump(); bumpOn = false }
    }

    fun releaseOrb() { if (orbId >= 0) { host.freeGlass(orbId); orbId = -1 } }

    companion object {
        const val STEP = 1f / 120f
        const val CALM_MS = 12_000L
        const val DRIZZLE_MS = 4_000L
        private const val CHANGE_AT = 0.22f
    }
}
