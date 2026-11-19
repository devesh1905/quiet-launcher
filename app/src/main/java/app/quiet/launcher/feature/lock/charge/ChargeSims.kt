package app.quiet.launcher.feature.lock.charge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The CPU side of the charging animations: small physical simulations stepped at a fixed
 * [SUB_DT] (120 Hz), whatever the display rate. Nothing here allocates after construction.
 */
internal const val SUB_DT = 1f / 120f

/** A cheap deterministic random (xorshift); kotlin.random would be fine too, this just never allocates. */
internal class Rng(seed: Int = 0x2545F491) {
    private var s = seed
    fun next(): Float {
        var x = s
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        s = x
        return (x ushr 8) / 16777216f
    }
    fun range(a: Float, b: Float) = a + (b - a) * next()
}

/** Damped spring step (semi-implicit Euler); returns nothing, writes through the two-slot array. */
internal fun springStep(xv: FloatArray, target: Float, k: Float, zeta: Float, dt: Float) {
    val c = 2f * sqrt(k) * zeta
    xv[1] += (-k * (xv[0] - target) - c * xv[1]) * dt
    xv[0] += xv[1] * dt
}

/**
 * A liquid surface as a row of columns. Each column is pulled back to its equilibrium (which the
 * tilt slopes) and coupled to its neighbours, so a disturbance travels as a wave and reflects at the
 * walls. The mean displacement is pinned at zero every step: the volume never changes, it only moves.
 * Units are the owner's (px for the flood and clock, drop radii for the glass).
 */
internal class SpringSurface(val n: Int) {
    val h = FloatArray(n)
    val v = FloatArray(n)
    val eq = FloatArray(n)
    /** Extra equilibrium shape on top of the tilt (inflow bump, drain dip). */
    val bump = FloatArray(n)
    var k = 30f
    var spread = 900f
    var damp = 1.4f
    private var slope = 0f

    /** Water stays level in the world: [s] is the rise per unit of column index span (owner units). */
    fun setTilt(s: Float, span: Float) {
        slope = s
        var mean = 0f
        for (i in 0 until n) { eq[i] = s * span * (i / (n - 1f) - 0.5f) + bump[i]; mean += eq[i] }
        mean /= n
        for (i in 0 until n) eq[i] -= mean
    }

    fun step(dt: Float) {
        for (i in 0 until n) {
            val di = h[i] - eq[i]
            val l = if (i > 0) h[i - 1] - eq[i - 1] else di
            val r = if (i < n - 1) h[i + 1] - eq[i + 1] else di
            v[i] += (-k * di + spread * (l + r - 2f * di) - damp * v[i]) * dt
        }
        var mean = 0f
        for (i in 0 until n) { h[i] += v[i] * dt; mean += h[i] }
        mean /= n
        for (i in 0 until n) h[i] -= mean
    }

    /** A push (velocity) shaped like a bell around [x01], [width] in columns. */
    fun kick(x01: Float, amount: Float, width: Float) {
        val c = x01 * (n - 1)
        val inv = 1f / (2f * width * width)
        for (i in 0 until n) { val d = i - c; v[i] += amount * exp(-d * d * inv) }
    }

    fun at(x01: Float): Float {
        val f = (x01.coerceIn(0f, 1f)) * (n - 1)
        val i = f.toInt().coerceAtMost(n - 2)
        val t = f - i
        return h[i] * (1 - t) + h[i + 1] * t
    }

    fun energy(): Float {
        var e = 0f
        for (i in 0 until n) { val d = h[i] - eq[i]; e += v[i] * v[i] + k * d * d }
        return e / n
    }

    fun flatten() { for (i in 0 until n) { h[i] = eq[i]; v[i] = 0f } }
    fun clearBump() { for (i in 0 until n) bump[i] = 0f }
}

/**
 * A 2D height-field ripple tank (the wave equation on a grid, two buffers: height and velocity),
 * damped, with reflecting walls and a free surface at the waterline. Rows above the waterline are
 * held flat, so ripples reflect off it like they do off the real surface.
 */
internal class RippleGrid(val gw: Int, val gh: Int) {
    val h = FloatArray(gw * gh)
    val v = FloatArray(gw * gh)
    /** Per column: the first row under water. */
    val top = IntArray(gw)
    var c2 = 38f * 38f
    var damp = 0.85f

    fun step(dt: Float) {
        for (x in 0 until gw) {
            val t = top[x]
            for (y in 0 until min(t, gh)) { val i = y * gw + x; h[i] = 0f; v[i] = 0f }
        }
        for (y in 0 until gh) {
            val row = y * gw
            for (x in 0 until gw) {
                if (y < top[x]) continue
                val i = row + x
                val c = h[i]
                val l = if (x > 0) h[i - 1] else c
                val r = if (x < gw - 1) h[i + 1] else c
                val u = if (y > 0) h[i - gw] else c       // above the surface it is 0 (held flat)
                val d = if (y < gh - 1) h[i + gw] else c
                v[i] += (c2 * (l + r + u + d - 4f * c) - damp * v[i]) * dt
            }
        }
        for (i in h.indices) h[i] += v[i] * dt
    }

    /** Push a bell of velocity at (cx, cy) in cells. */
    fun splash(cx: Float, cy: Float, radius: Float, amount: Float) {
        val r = max(1f, radius)
        val x0 = (cx - r * 2.5f).toInt().coerceIn(0, gw - 1)
        val x1 = (cx + r * 2.5f).toInt().coerceIn(0, gw - 1)
        val y0 = (cy - r * 2.5f).toInt().coerceIn(0, gh - 1)
        val y1 = (cy + r * 2.5f).toInt().coerceIn(0, gh - 1)
        val inv = 1f / (2f * r * r)
        for (y in y0..y1) for (x in x0..x1) {
            if (y < top[x]) continue
            val dx = x - cx; val dy = y - cy
            v[y * gw + x] += amount * exp(-(dx * dx + dy * dy) * inv)
        }
    }

    fun energy(): Float {
        var e = 0f
        // Every 7th cell is enough to tell "still moving" from "at rest".
        var i = 0
        while (i < h.size) { e += v[i] * v[i] + c2 * 0.002f * h[i] * h[i]; i += 7 }
        return e * 7f / h.size
    }

    fun clear() { h.fill(0f); v.fill(0f) }
    fun heightAt(cx: Float, cy: Float): Float {
        val x = cx.toInt().coerceIn(0, gw - 1); val y = cy.toInt().coerceIn(0, gh - 1)
        return h[y * gw + x]
    }
}

// ------------------------------------------------------------------------------------ Flood

/**
 * Flood: water arrives through the USB port. A jet gushes up, breaks into drops at its top, the
 * drops rain back and the screen fills from the bottom to the battery level. The waterline is a
 * [SpringSurface] (96 columns) that sloshes with the tilt; under it a [RippleGrid] carries the
 * inflow, touch drags, splash rings and a faint drizzle of micro-disturbances.
 * Positions are screen px.
 */
internal class FloodSim {
    val surf = SpringSurface(96).apply { k = 14f; spread = 30f * 30f; damp = 0.9f }
    var grid: RippleGrid? = null
    private val rng = Rng(77)
    var w = 1f; var h = 1f; var dp = 1f

    /** The fill (0..1 of the screen) and its velocity. */
    val fill = FloatArray(2)
    var target = 0f
    var active = false
    var draining = false
    var time = 0f

    // Jet: tip and tail ride ballistic paths (px from top, velocities px/s).
    var jetOn = 0f
    var tipY = 0f; var tipV = 0f
    var tailY = 0f; var tailV = 0f
    var jetT = -1f
    private var tailFree = false
    private var crowned = false
    var jetHw = 0f

    // Drops: x, y, vx, vy, r. r <= 0 means free.
    val dropN = 12
    val dx = FloatArray(dropN); val dy = FloatArray(dropN); val dvx = FloatArray(dropN); val dvy = FloatArray(dropN); val dr = FloatArray(dropN)
    var dropsAlive = 0

    // Bubbles from the port.
    val bubN = 18
    val bx = FloatArray(bubN); val by = FloatArray(bubN); val bvy = FloatArray(bubN); val br = FloatArray(bubN); val bph = FloatArray(bubN)
    var bubblesAlive = 0
    private var bubbleClock = 0f
    private var sourceClock = 0f
    private var drizzleClock = 0f
    var flow = 0f   // steady inflow strength, eased
    var swirl = 0f

    val gravity get() = h * 3.8f

    fun resize(w: Float, h: Float, dp: Float) {
        if (this.w == w && this.h == h && grid != null) return
        this.w = w; this.h = h; this.dp = dp
        val gw = 90
        val gh = (gw * h / w).toInt().coerceIn(120, 200)
        grid = RippleGrid(gw, gh)
    }

    fun waterY(x: Float): Float = h * (1f - fill[0]) - surf.at(x / w)

    fun plugIn(level: Float, wake: Boolean) {
        active = true; draining = false
        target = level
        if (!wake) {
            // Already charging when the lock screen came up: water sits at the level, a small slosh.
            fill[0] = level; fill[1] = 0f
            surf.flatten(); surf.kick(0.5f, 40f * dp, 6f)
            return
        }
        fill[0] = max(fill[0], 0f); fill[1] = 0f
        jetOn = 1f; jetT = 0f; crowned = false; tailFree = false
        tipY = h; tailY = h
        // Up to about 60% of the screen, slowed to read as water, not as a spark.
        tipV = -sqrt(2f * gravity * h * 0.62f)
        tailV = 0f
        jetHw = 11f * dp
    }

    fun unplug() {
        if (!active) return
        draining = true
        target = -0.03f
        jetOn = 0f; jetT = -1f
    }

    fun setLevel(level: Float) { if (active && !draining) target = level }

    /** A splash ring at the port (the show-off gesture while the water is already in). */
    fun ringAt() { grid?.let { it.splash(it.gw * 0.5f, it.gh - 2f, 2.4f, 240f) }; surf.kick(0.5f, -120f * dp, 3f) }

    /** Energy left in the water (px²/s² scale), for "still moving". */
    fun energy(): Float = surf.energy() / (dp * dp) + (grid?.energy() ?: 0f) * 40f + abs(fill[1]) * 400f +
        (if (jetT >= 0f) 1000f else 0f) + dropsAlive * 100f

    fun step(dt: Float, tilt: Float, fast: Float, charging: Boolean, rngDisturb: Boolean) {
        val g = grid ?: return
        time += dt
        // ---- the fill: inflow is a soft spring toward the level, drains like a sink.
        if (jetT in 0f..0.25f) { /* the jet leads; the water follows a moment later */ }
        else if (draining) {
            fill[1] += (-0.22f - fill[1]) * min(1f, dt * 2.2f)
            fill[0] += fill[1] * dt
            if (fill[0] <= -0.02f) { fill[0] = -0.02f; fill[1] = 0f }
        } else springStep(fill, target, 3.2f, 0.62f, dt)
        // ---- the waterline: level in the world, a bump over the port while it flows in, a dip and swirl draining.
        val portCol = 0.5f
        flow += ((if (charging && !draining) 0.45f + 0.55f * fast else 0f) - flow) * min(1f, dt * 1.5f)
        val drainRate = if (draining && fill[0] > 0f) min(1f, -fill[1] * 6f) else 0f
        swirl += (drainRate - swirl) * min(1f, dt * 2f)
        for (i in 0 until surf.n) {
            val d = i - portCol * (surf.n - 1)
            surf.bump[i] = exp(-d * d / 18f) * dp * (5f * flow + 22f * jetOn) - exp(-d * d / 10f) * dp * 26f * swirl
        }
        surf.setTilt(tilt, w)
        surf.step(dt)
        // ---- the jet
        if (jetT >= 0f) {
            jetT += dt
            tipV += gravity * dt; tipY += tipV * dt
            if (jetT > 0.42f && !tailFree) { tailFree = true; tailV = tipV * 0.35f - h * 0.6f }
            if (tailFree) { tailV += gravity * dt; tailY += tailV * dt }
            // At its top the jet crowns into drops.
            if (!crowned && tipV > -h * 0.05f) {
                crowned = true
                for (i in 0 until 9) spawnDrop(w / 2f + rng.range(-4f, 4f) * dp, tipY, rng.range(-0.55f, 0.55f) * h * 0.55f, rng.range(-0.25f, 0.15f) * h, rng.range(3.5f, 8f) * dp)
            }
            val surfaceY = waterY(w / 2f)
            if ((tipY > surfaceY && tipV > 0f) || tailY <= tipY + 2f * dp || jetT > 2.5f) {
                jetT = -1f; jetOn = 0f
                g.splash(g.gw * 0.5f, cellY(min(surfaceY, h - 2f), g) + 2f, 2.6f, 260f)
                surf.kick(0.5f, -220f * dp, 3f)
            } else {
                jetOn = 1f
                // The jet pours into the pool: rings around where it enters.
                val sy = cellY(surfaceY, g)
                if (sy < g.gh) g.splash(g.gw * 0.5f, sy + 1.5f, 1.8f, 90f * sin(time * 40f))
            }
        }
        // ---- drops
        dropsAlive = 0
        for (i in 0 until dropN) {
            if (dr[i] <= 0f) continue
            dvy[i] += gravity * dt
            dvx[i] *= (1f - 0.4f * dt)
            dx[i] += dvx[i] * dt; dy[i] += dvy[i] * dt
            if (dx[i] < dr[i] || dx[i] > w - dr[i]) { dvx[i] = -dvx[i] * 0.5f; dx[i] = dx[i].coerceIn(dr[i], w - dr[i]) }
            val sy = waterY(dx[i])
            if (dvy[i] > 0f && (dy[i] > sy || dy[i] > h)) {
                val cx = dx[i] / w * g.gw
                g.splash(cx, cellY(min(sy, h - 2f), g) + 1.5f, 1.4f + dr[i] / (6f * dp), 160f)
                surf.kick(dx[i] / w, -dr[i] * 14f, 2f)
                dr[i] = 0f
                continue
            }
            dropsAlive++
        }
        // ---- the grid's free surface follows the waterline
        for (x in 0 until g.gw) {
            val wy = waterY((x + 0.5f) / g.gw * w)
            g.top[x] = cellY(wy, g).toInt().coerceIn(0, g.gh)
        }
        // ---- steady inflow: the port keeps pulsing rings and breathing out bubbles.
        if (flow > 0.01f) {
            sourceClock += dt
            g.splash(g.gw * 0.5f, g.gh - 1.5f, 1.6f, flow * 70f * sin(sourceClock * 13f))
            bubbleClock += dt * (2.5f + 9f * fast) * flow
            while (bubbleClock > 1f) { bubbleClock -= 1f; spawnBubble() }
        }
        // ---- bubbles rise (buoyancy against drag), wobble, pop at the surface.
        bubblesAlive = 0
        for (i in 0 until bubN) {
            if (br[i] <= 0f) continue
            val buoy = h * 0.9f * (br[i] / (4f * dp))
            bvy[i] += (-buoy - 3.2f * bvy[i]) * dt
            by[i] += bvy[i] * dt
            bph[i] += dt * 9f
            bx[i] += sin(bph[i]) * dp * 22f * dt
            if (by[i] - br[i] <= waterY(bx[i])) {
                g.splash(bx[i] / w * g.gw, cellY(waterY(bx[i]), g) + 1f, 1.1f, 40f)
                surf.kick(bx[i] / w, -br[i] * 6f, 1.2f)
                br[i] = 0f
                continue
            }
            bubblesAlive++
        }
        // ---- a faint drizzle so the water is never dead still while it charges
        if (rngDisturb && charging && !draining) {
            drizzleClock += dt
            if (drizzleClock > 0.11f) {
                drizzleClock = 0f
                val cx = rng.range(2f, g.gw - 3f)
                val top = g.top[cx.toInt()]
                if (top < g.gh - 2) g.splash(cx, rng.range(top.toFloat(), g.gh - 1f), 1.2f, rng.range(-14f, 14f))
            }
        }
        g.step(dt)
        if (draining && fill[0] <= -0.019f && surf.energy() < 4f * dp * dp) {
            active = false; draining = false
        }
    }

    fun cellY(y: Float, g: RippleGrid) = y / h * g.gh

    private fun spawnDrop(x: Float, y: Float, vx: Float, vy: Float, r: Float) {
        for (i in 0 until dropN) if (dr[i] <= 0f) { dx[i] = x; dy[i] = y; dvx[i] = vx; dvy[i] = vy; dr[i] = r; return }
    }

    private fun spawnBubble() {
        val y0 = h - 3f * dp
        if (waterY(w / 2f) > y0 - 6f * dp) return
        for (i in 0 until bubN) if (br[i] <= 0f) {
            bx[i] = w / 2f + rng.range(-7f, 7f) * dp; by[i] = y0; bvy[i] = -h * 0.05f
            br[i] = rng.range(1.4f, 3.6f) * dp; bph[i] = rng.range(0f, 6.28f); return
        }
    }

    fun touch(x: Float, y: Float, dxp: Float, dyp: Float) {
        val g = grid ?: return
        if (!active) return
        if (y < waterY(x)) return
        val sp = sqrt(dxp * dxp + dyp * dyp) / dp
        g.splash(x / w * g.gw, y / h * g.gh, 2.2f, (40f + sp * 14f).coerceAtMost(320f))
    }

    /** Soft rings from a point (the orb on the beat). */
    fun ring(x: Float, y: Float, amount: Float) {
        val g = grid ?: return
        if (y < waterY(x)) return
        g.splash(x / w * g.gw, y / h * g.gh, 1.8f, amount)
    }

    fun settle(level: Float) {
        active = true; draining = false; target = level
        fill[0] = level; fill[1] = 0f
        surf.clearBump(); surf.setTilt(0f, w); surf.flatten()
        grid?.clear(); grid?.let { g -> for (x in 0 until g.gw) g.top[x] = cellY(waterY((x + 0.5f) / g.gw * w), g).toInt().coerceIn(0, g.gh) }
        jetT = -1f; jetOn = 0f; flow = 0f; swirl = 0f
        for (i in 0 until dropN) dr[i] = 0f
        for (i in 0 until bubN) br[i] = 0f
        dropsAlive = 0; bubblesAlive = 0
    }

    fun reset() { settle(0f); active = false }
}

// ------------------------------------------------------------------------------------ Filling glass

/**
 * Filling glass: a clear drop whose body squashes and stretches on springs, holding liquid at the
 * battery level. The liquid surface is a [SpringSurface] (48 columns) that sloshes with the tilt;
 * bubbles rise with buoyancy and drag and pop at the surface. Units are drop radii, y down.
 */
internal class GlassSim {
    val surf = SpringSurface(48).apply { k = 26f; spread = 22f * 22f; damp = 1.6f }
    private val rng = Rng(11)
    /** Body scale and squash, each [value, velocity]. */
    val body = FloatArray(2)
    val squash = FloatArray(2)
    val fill = FloatArray(2)
    var liquid = 0f
    var target = 0f
    var state = 0         // 0 idle, 1 in, 2 draining, 3 shrinking
    var spark = 0f
    var time = 0f
    var orbMode = false

    val bubN = 16
    val bx = FloatArray(bubN); val by = FloatArray(bubN); val bvy = FloatArray(bubN); val br = FloatArray(bubN); val bph = FloatArray(bubN)
    private var bubbleClock = 0f
    private var lastBodyV = 0f

    val visible get() = state != 0

    fun plugIn(level: Float, wake: Boolean) {
        target = level
        if (state == 1) return
        state = 1
        if (!wake) {
            body[0] = 1f; body[1] = 0f; fill[0] = level; fill[1] = 0f; liquid = 1f
            surf.flatten(); surf.kick(0.5f, 0.5f, 5f)
            return
        }
        body[0] = 0.02f; body[1] = 0f
        squash[0] = 0f; squash[1] = 4f
        fill[0] = 0f; fill[1] = 0f; liquid = 1f
        surf.flatten()
        for (i in 0 until 6) spawnBubble(rng.range(0.4f, 0.8f))
    }

    fun unplug() { if (state == 1) { state = 2; target = 0f } }
    fun setLevel(level: Float) { if (state == 1) target = level }

    /** The surface height (y down, in radii) whose circular segment holds [f] of the drop's area. */
    fun fillY(f: Float, rad: Float = 1f): Float {
        val ff = f.coerceIn(0f, 1f)
        if (ff <= 0f) return rad
        if (ff >= 1f) return -rad
        var lo = -1f; var hi = 1f
        repeat(22) {
            val y = (lo + hi) / 2f
            // Area below the chord at y (y down), as a fraction of the disc.
            val a = (acos(y.toDouble()) - y * sqrt(1.0 - y * y)) / PI
            if (a > ff) lo = y else hi = y
        }
        return (lo + hi) / 2f * rad
    }

    fun energy(): Float = surf.energy() * 400f + body[1] * body[1] * 50f + squash[1] * squash[1] * 50f +
        fill[1] * fill[1] * 400f + (if (state == 2 || state == 3) 100f else 0f) + (if (bubblesAlive() > 0) 20f else 0f)

    fun bubblesAlive(): Int { var n = 0; for (i in 0 until bubN) if (br[i] > 0f) n++; return n }

    fun step(dt: Float, tilt: Float, fast: Float, beat: Float, level: Float) {
        time += dt
        when (state) {
            1 -> {
                springStep(body, 1f, 170f, 0.3f, dt)
                springStep(fill, if (body[0] > 0.6f) target else 0f, 9f, 0.65f, dt)
            }
            2 -> {
                springStep(body, 1f, 170f, 0.3f, dt)
                fill[1] += (-0.6f - fill[1]) * min(1f, dt * 4f)
                fill[0] += fill[1] * dt
                if (fill[0] <= 0f) { fill[0] = 0f; fill[1] = 0f; state = 3; body[1] += 1.5f }
            }
            3 -> {
                springStep(body, -0.08f, 130f, 0.75f, dt)
                if (body[0] <= 0.01f) { body[0] = 0f; state = 0 }
            }
        }
        // Squash answers the body's acceleration: a pop in stretches it tall, then it jiggles.
        val acc = (body[1] - lastBodyV) / dt
        lastBodyV = body[1]
        squash[1] += -acc * 0.12f * dt
        springStep(squash, 0f, 260f, 0.22f, dt)
        squash[0] = squash[0].coerceIn(-0.35f, 0.35f)
        // Calm at full; liquid sparkles instead.
        val full = level >= 0.995f && state == 1
        spark += ((if (full) 1f else 0f) - spark) * min(1f, dt * 1.2f)
        surf.damp = if (full) 4.5f else 1.6f
        surf.setTilt(tilt.coerceIn(-0.9f, 0.9f), 2f)
        if (beat > 0f && orbMode) surf.kick(0.5f + 0.3f * sin(time * 1.3f), beat * 0.9f, 4f)
        surf.step(dt)
        // Bubbles: more on fast charge, none once full or draining.
        if (state == 1 && !full && fill[0] > 0.05f) {
            bubbleClock += dt * (0.9f + 3.6f * fast)
            while (bubbleClock > 1f) { bubbleClock -= 1f; spawnBubble(rng.range(0.7f, 0.9f)) }
        }
        val sy = fillY(fill[0])
        for (i in 0 until bubN) {
            if (br[i] <= 0f) continue
            val buoy = 2.2f * (br[i] / 0.05f)
            bvy[i] += (-buoy - 4f * bvy[i]) * dt
            by[i] += bvy[i] * dt
            bph[i] += dt * 7f
            bx[i] += cos(bph[i]) * 0.1f * dt
            val lim = sqrt(max(0f, 1f - by[i] * by[i])) - br[i]
            bx[i] = bx[i].coerceIn(-lim, lim)
            if (by[i] - br[i] <= sy - surf.at(bx[i] * 0.5f + 0.5f) || fill[0] <= 0.01f) {
                surf.kick(bx[i] * 0.5f + 0.5f, -br[i] * 4f, 1.2f)
                br[i] = 0f
            }
        }
    }

    private fun spawnBubble(yFrac: Float) {
        for (i in 0 until bubN) if (br[i] <= 0f) {
            by[i] = yFrac
            val lim = sqrt(max(0f, 1f - yFrac * yFrac)) * 0.8f
            bx[i] = rng.range(-lim, lim); bvy[i] = 0f
            br[i] = rng.range(0.018f, 0.05f); bph[i] = rng.range(0f, 6.28f); return
        }
    }

    fun settle(level: Float) {
        state = 1; target = level
        body[0] = 1f; body[1] = 0f; squash[0] = 0f; squash[1] = 0f
        fill[0] = level; fill[1] = 0f; liquid = 1f
        spark = if (level >= 0.995f) 1f else 0f
        surf.setTilt(0f, 2f); surf.flatten()
        for (i in 0 until bubN) br[i] = 0f
    }

    fun reset() { state = 0; body[0] = 0f; fill[0] = 0f; for (i in 0 until bubN) br[i] = 0f }
}

// ------------------------------------------------------------------------------------ Light thread

/**
 * Light thread: a Verlet rope of [N] points between the USB port and the clock block (or the orb
 * while music plays): small gravity along the real tilt, distance constraints, damping. A spark
 * draws it up when plugged in; unplugged, the middle link breaks and both halves fall and swing.
 * Positions are screen px.
 */
internal class ThreadSim {
    val n = 40
    val x = FloatArray(n); val y = FloatArray(n)
    val ox = FloatArray(n); val oy = FloatArray(n)
    var rest = 10f
    var state = 0     // 0 idle, 1 drawing, 2 live, 3 broken
    var spark = 0f    // 0..1 head along the rope while drawing
    private var sparkV = 0f
    var fade = 0f
    var time = 0f
    val pulses = FloatArray(6) { -1f }
    private var pulseClock = 0f
    var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
    var dp = 1f; var h = 1f; var w = 1f
    private var brokenT = 0f

    val visible get() = state != 0

    fun anchors(ax: Float, ay: Float, bx: Float, by: Float) {
        this.ax = ax; this.ay = ay; this.bx = bx; this.by = by
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        rest = len * 1.035f / (n - 1)
    }

    fun plugIn(wake: Boolean) {
        if (state == 1 || state == 2) return
        fade = 1f
        for (i in 0 until 6) pulses[i] = -1f
        if (!wake) { settle(); return }
        state = 1; spark = 0f; sparkV = 0f
        for (i in 0 until n) { x[i] = ax; y[i] = ay; ox[i] = ax; oy[i] = ay }
    }

    fun unplug() { if (state == 1 || state == 2) { state = 3; brokenT = 0f } }

    fun settle() {
        state = 2; spark = 1f; fade = 1f
        for (i in 0 until n) {
            val t = i / (n - 1f)
            x[i] = ax + (bx - ax) * t; y[i] = ay + (by - ay) * t
            ox[i] = x[i]; oy[i] = y[i]
        }
    }

    fun energy(): Float {
        var e = 0f
        for (i in 0 until n) { val vx = x[i] - ox[i]; val vy = y[i] - oy[i]; e += vx * vx + vy * vy }
        return e / (dp * dp) * 60f + (if (state == 1 || state == 3) 100f else 0f)
    }

    fun firePulse() {
        for (i in 0 until 6) if (pulses[i] < 0f) { pulses[i] = 0f; return }
    }

    fun step(dt: Float, gx: Float, gy: Float, fast: Float, level: Float, autoPulse: Boolean) {
        time += dt
        val broken = state == 3
        val g = (if (broken) 2600f else 140f) * dp
        val damp = if (broken) 0.992f else 0.985f
        // ---- the spark: eases up the rope with a little overshoot.
        if (state == 1) {
            sparkV += ((1.08f - spark) * 38f - sparkV * 7f) * dt
            spark = min(1f, spark + max(sparkV, 0.2f) * dt)
            if (spark >= 1f) state = 2
        }
        val headX: Float; val headY: Float
        run {
            val t = spark
            // A slight sideways arc while it rises, so it is drawn by a hand, not a ruler.
            val wob = sin(t * PI.toFloat()) * 14f * dp * sin(time * 3f + 1f)
            headX = ax + (bx - ax) * t + wob; headY = ay + (by - ay) * t
        }
        // ---- Verlet
        for (i in 0 until n) {
            val vx = (x[i] - ox[i]) * damp; val vy = (y[i] - oy[i]) * damp
            ox[i] = x[i]; oy[i] = y[i]
            x[i] += vx + gx * g * dt * dt
            y[i] += vy + gy * g * dt * dt
        }
        val mid = n / 2
        repeat(if (broken) 12 else 24) {
            for (i in 0 until n - 1) {
                if (broken && i == mid - 1) continue
                if (state == 1 && i / (n - 1f) >= spark) continue
                val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
                val f = (d - rest) / d * 0.5f
                x[i] += dx * f; y[i] += dy * f
                x[i + 1] -= dx * f; y[i + 1] -= dy * f
            }
            pin(0, ax, ay)
            if (state == 1) {
                for (i in 0 until n) if (i / (n - 1f) >= spark) pin(i, headX, headY)
            } else pin(n - 1, bx, by)
            if (broken) for (i in 0 until n) {
                if (y[i] > h - 2f * dp) y[i] = h - 2f * dp
                x[i] = x[i].coerceIn(2f * dp, w - 2f * dp)
            }
        }
        // ---- pulses: rise toward the top, faster on fast charge.
        if (autoPulse && state == 2) {
            pulseClock += dt
            if (pulseClock > 1.7f - 0.9f * fast) { pulseClock = 0f; firePulse() }
        }
        for (i in 0 until 6) if (pulses[i] >= 0f) {
            pulses[i] += dt * (0.32f + 0.45f * fast)
            if (pulses[i] > 1.05f) pulses[i] = -1f
        }
        if (broken) {
            brokenT += dt
            if (brokenT > 0.35f) fade = max(0f, fade - dt / 1.6f)
            if (fade <= 0f) state = 0
        }
    }

    private fun pin(i: Int, px: Float, py: Float) { x[i] = px; y[i] = py }

    fun reset() { state = 0; fade = 0f }
}

// ------------------------------------------------------------------------------------ Clock fill

/**
 * Clock fill: the clock digits become glass and fill with liquid light. One [SpringSurface] across
 * the clock's width (48 columns, px) sloshes with the tilt, a plug impulse and the beat.
 */
internal class ClockSim {
    val surf = SpringSurface(48).apply { k = 22f; spread = 20f * 20f; damp = 1.5f }
    val fill = FloatArray(2)
    val presence = FloatArray(2)
    var target = 0f
    var state = 0   // 0 idle, 1 on, 2 draining
    var shine = -1f
    private var shineClock = 0f
    var time = 0f
    var dp = 1f

    val visible get() = state != 0 || presence[0] > 0.01f

    fun plugIn(level: Float, wake: Boolean) {
        target = level
        if (state == 1) return
        state = 1
        if (!wake) { presence[0] = 1f; fill[0] = level; surf.flatten(); surf.kick(0.3f, 30f * dp, 4f); return }
        fill[0] = 0f; fill[1] = 0f
        surf.flatten()
        surf.kick(0.15f, 90f * dp, 3f)
    }

    fun unplug() { if (state == 1) { state = 2; target = 0f } }
    fun setLevel(level: Float) { if (state == 1) target = level }

    fun energy(): Float = surf.energy() / (dp * dp) + fill[1] * fill[1] * 400f + presence[1] * presence[1] * 400f +
        (if (shine > -0.5f) 100f else 0f) + (if (state == 2) 100f else 0f)

    fun step(dt: Float, tiltSlope: Float, width: Float, beat: Float, level: Float) {
        time += dt
        when (state) {
            1 -> { springStep(presence, 1f, 60f, 1f, dt); springStep(fill, target, 8f, 0.55f, dt) }
            2 -> {
                springStep(fill, -0.05f, 10f, 0.9f, dt)
                if (fill[0] <= 0.0f) springStep(presence, 0f, 40f, 1f, dt)
                if (fill[0] <= 0f && presence[0] < 0.01f) { presence[0] = 0f; state = 0 }
            }
        }
        surf.setTilt(tiltSlope.coerceIn(-0.5f, 0.5f), width)
        if (beat > 0f) surf.kick(0.5f, beat * 24f * dp, 6f)
        surf.step(dt)
        // At full a shine sweeps across every few seconds.
        if (state == 1 && level >= 0.995f && fill[0] > 0.97f) {
            shineClock += dt
            if (shine < -0.5f && shineClock > 3.5f) { shine = -0.3f; shineClock = 0f }
        }
        if (shine > -0.5f) { shine += dt / 1.3f; if (shine > 1.3f) shine = -1f }
    }

    fun settle(level: Float) {
        state = 1; target = level; presence[0] = 1f; presence[1] = 0f; fill[0] = level; fill[1] = 0f
        surf.setTilt(0f, 1f); surf.flatten(); shine = -1f
    }

    fun reset() { state = 0; presence[0] = 0f; fill[0] = 0f }
}
