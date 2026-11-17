package app.quiet.launcher.feature.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of a stroke, in any unit (px on screen, 0..100 for templates). */
data class Pt(val x: Float, val y: Float)

/**
 * A $1-style unistroke recogniser. Orientation is kept (no rotate-to-zero, only a small ±18° search),
 * so M and W stay different. Look-alike "decoy" shapes (O, lines, Z, V, caret) open nothing, so a near
 * miss is rejected instead of opening the wrong app.
 */
object Unistroke {
    private const val N = 64
    private const val SIZE = 250f
    private val HALF_DIAG = 0.5f * hypot(SIZE, SIZE)
    private const val RANGE = (18 * PI / 180).toFloat()
    private const val PREC = (2 * PI / 180).toFloat()
    private val PHI = (0.5 * (sqrt(5.0) - 1)).toFloat()
    const val THRESHOLD = 0.80f

    /** Letters that can be mapped to an app. */
    val LETTERS: List<Char> = listOf('C', 'M', 'W', 'S', 'N', 'L', 'U')

    data class Result(
        /** The letter to act on, or null for no match. */
        val letter: Char?,
        /** The best shape overall ("C", "O", "line", ...), and its score. */
        val top: String,
        val score: Float,
    )

    private class Template(val key: String, val letter: Char?, val pts: List<Pt>)

    private val templates: List<Template> by lazy {
        val out = ArrayList<Template>()
        fun add(key: String, letter: Char?, shapes: List<List<Pt>>) = shapes.forEach { s ->
            out += Template(key, letter, normalise(s))
            out += Template(key, letter, normalise(s.reversed())) // drawn the other way round
        }
        letterShapes().forEach { (l, s) -> add(l.toString(), l, s) }
        decoyShapes().forEach { (k, s) -> add(k, null, s) }
        out
    }

    /**
     * Reads [raw]. Only letters in [allowed] can match; any other letter, or a decoy, wins as "no match".
     */
    fun recognise(raw: List<Pt>, allowed: Set<Char> = LETTERS.toSet()): Result {
        if (raw.size < 2 || pathLength(raw) <= 0f) return Result(null, "dot", 0f)
        val p = normalise(raw)
        var bestKey = ""
        var bestLetter: Char? = null
        var best = -Float.MAX_VALUE
        for (t in templates) {
            val s = 1f - bestAngleDistance(p, t.pts) / HALF_DIAG
            if (s > best) { best = s; bestKey = t.key; bestLetter = t.letter }
        }
        val letter = bestLetter?.takeIf { best >= THRESHOLD && it in allowed }
        return Result(letter, bestKey, best)
    }

    // ---- templates, drawn in a 0..100 box with y pointing down ----------------------------------

    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, n: Int = 28) = (0..n).map { i ->
        val a = (a0 + (a1 - a0) * i / n) * PI.toFloat() / 180f
        Pt(cx + rx * cos(a), cy + ry * sin(a))
    }

    private fun poly(vararg xy: Float) = (xy.indices step 2).map { Pt(xy[it], xy[it + 1]) }

    private fun sShape(slant: Float) = (arc(50f, 28f, 24f, 22f, -30f, -270f) + arc(50f, 72f, 24f, 22f, -90f, 150f))
        .map { Pt(it.x - (it.y - 50f) * slant, it.y) }

    private fun uShape(w: Float, depth: Float) =
        poly(50 - w, 8f, 50 - w, 92 - depth) + arc(50f, 92 - depth, w, depth, 180f, 0f) + poly(50 + w, 92 - depth, 50 + w, 8f)

    internal fun letterShapes(): List<Pair<Char, List<List<Pt>>>> = listOf(
        'C' to listOf(arc(50f, 50f, 45f, 45f, -45f, -315f), arc(50f, 50f, 38f, 46f, -60f, -300f), arc(50f, 50f, 42f, 46f, -30f, -320f)),
        'M' to listOf(poly(10f, 90f, 20f, 10f, 50f, 62f, 80f, 10f, 90f, 90f), poly(14f, 90f, 14f, 10f, 50f, 66f, 86f, 10f, 86f, 90f), poly(8f, 90f, 28f, 10f, 50f, 48f, 72f, 10f, 92f, 90f)),
        'W' to listOf(poly(10f, 10f, 30f, 90f, 50f, 38f, 70f, 90f, 90f, 10f), poly(8f, 10f, 24f, 90f, 50f, 20f, 76f, 90f, 92f, 10f), poly(14f, 10f, 32f, 90f, 50f, 56f, 68f, 90f, 86f, 10f)),
        'S' to listOf(sShape(0f), sShape(0.18f), sShape(-0.1f)),
        'N' to listOf(poly(15f, 90f, 15f, 10f, 85f, 90f, 85f, 10f), poly(10f, 90f, 20f, 10f, 80f, 90f, 90f, 10f), poly(18f, 90f, 12f, 10f, 88f, 90f, 82f, 10f)),
        'L' to listOf(poly(20f, 10f, 20f, 90f, 80f, 90f), poly(15f, 10f, 25f, 90f, 85f, 88f), poly(25f, 5f, 15f, 90f, 80f, 95f)),
        'U' to listOf(uShape(35f, 30f), uShape(32f, 40f), uShape(38f, 22f)),
    )

    internal fun decoyShapes(): List<Pair<String, List<List<Pt>>>> = listOf(
        "O" to listOf(arc(50f, 50f, 45f, 45f, -90f, -450f), arc(50f, 50f, 45f, 45f, -45f, -405f)),
        "line" to listOf(poly(0f, 50f, 100f, 50f), poly(50f, 0f, 50f, 100f), poly(0f, 0f, 100f, 100f), poly(100f, 0f, 0f, 100f)),
        "Z" to listOf(poly(10f, 10f, 90f, 10f, 10f, 90f, 90f, 90f)),
        "V" to listOf(poly(10f, 10f, 50f, 90f, 90f, 10f)),
        "caret" to listOf(poly(10f, 90f, 50f, 10f, 90f, 90f)),
    )

    // ---- $1 steps ------------------------------------------------------------------------------

    private fun dist(a: Pt, b: Pt) = hypot(b.x - a.x, b.y - a.y)

    internal fun pathLength(p: List<Pt>): Float {
        var d = 0f
        for (i in 1 until p.size) d += dist(p[i - 1], p[i])
        return d
    }

    private fun resample(pts: List<Pt>, n: Int): List<Pt> {
        val interval = pathLength(pts) / (n - 1)
        var d = 0f
        val src = ArrayList(pts)
        val out = arrayListOf(src[0])
        var i = 1
        while (i < src.size) {
            val a = src[i - 1]
            val b = src[i]
            val seg = dist(a, b)
            if (seg > 0f && d + seg >= interval) {
                val t = (interval - d) / seg
                val q = Pt(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                out += q
                src.add(i, q)
                d = 0f
            } else d += seg
            i++
        }
        while (out.size < n) out += src.last()
        return out.subList(0, n)
    }

    private fun centroid(p: List<Pt>): Pt {
        var x = 0f; var y = 0f
        for (q in p) { x += q.x; y += q.y }
        return Pt(x / p.size, y / p.size)
    }

    private fun rotateBy(p: List<Pt>, r: Float): List<Pt> {
        val c = centroid(p)
        val cs = cos(r); val sn = sin(r)
        return p.map { q -> Pt((q.x - c.x) * cs - (q.y - c.y) * sn + c.x, (q.x - c.x) * sn + (q.y - c.y) * cs + c.y) }
    }

    /** Non-uniform scale to a square, except nearly one-dimensional strokes (lines), which scale uniformly. */
    private fun scaleTo(p: List<Pt>): List<Pt> {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (q in p) { x0 = min(x0, q.x); y0 = min(y0, q.y); x1 = max(x1, q.x); y1 = max(y1, q.y) }
        val w = x1 - x0; val h = y1 - y0
        val m = max(w, h).takeIf { it > 0f } ?: 1f
        val oneD = min(w, h) / m < 0.3f
        val sx = if (oneD) SIZE / m else SIZE / (if (w > 0f) w else 1f)
        val sy = if (oneD) SIZE / m else SIZE / (if (h > 0f) h else 1f)
        return p.map { Pt(it.x * sx, it.y * sy) }
    }

    private fun toOrigin(p: List<Pt>): List<Pt> {
        val c = centroid(p)
        return p.map { Pt(it.x - c.x, it.y - c.y) }
    }

    private fun normalise(pts: List<Pt>) = toOrigin(scaleTo(resample(pts, N)))

    private fun pathDistance(a: List<Pt>, b: List<Pt>): Float {
        var d = 0f
        for (i in a.indices) d += dist(a[i], b[i])
        return d / a.size
    }

    private fun bestAngleDistance(p: List<Pt>, t: List<Pt>): Float {
        var a = -RANGE; var b = RANGE
        var x1 = PHI * a + (1 - PHI) * b; var x2 = (1 - PHI) * a + PHI * b
        var f1 = pathDistance(rotateBy(p, x1), t); var f2 = pathDistance(rotateBy(p, x2), t)
        while (abs(b - a) > PREC) {
            if (f1 < f2) {
                b = x2; x2 = x1; f2 = f1; x1 = PHI * a + (1 - PHI) * b; f1 = pathDistance(rotateBy(p, x1), t)
            } else {
                a = x1; x1 = x2; f1 = f2; x2 = (1 - PHI) * a + PHI * b; f2 = pathDistance(rotateBy(p, x2), t)
            }
        }
        return min(f1, f2)
    }
}

/**
 * Decides, while the finger moves, whether Home is seeing a swipe or a drawing. A drawing starts only
 * after the finger has travelled a bit and the path is not a clear straight horizontal or vertical swipe.
 * Works on the raw coordinate arrays (no allocation); units are px, [dp] is the density.
 */
object StrokeGate {
    enum class Verdict { Pending, Swipe, Draw }

    /** How far the stroke ever moved against its overall sideways direction ([dx]'s sign). */
    fun xBacktrack(xs: FloatArray, n: Int, dx: Float): Float {
        val dir = if (dx >= 0f) 1f else -1f
        var best = xs[0] * dir
        var back = 0f
        for (i in 1 until n) {
            val v = xs[i] * dir
            if (v > best) best = v else back = max(back, best - v)
        }
        return back
    }

    /** A finished stroke that reads as a sideways page swipe rather than a letter. */
    fun looksLikeSideSwipe(xs: FloatArray, ys: FloatArray, n: Int, dp: Float): Boolean {
        if (n < 2) return false
        val dx = xs[n - 1] - xs[0]; val dy = ys[n - 1] - ys[0]
        return abs(dx) >= 40 * dp && abs(dx) > 1.4f * abs(dy) && xBacktrack(xs, n, dx) < 14 * dp
    }

    fun classify(xs: FloatArray, ys: FloatArray, n: Int, dp: Float): Verdict {
        if (n < 2) return Verdict.Pending
        val sx = xs[0]; val sy = ys[0]
        var travel = 0f
        for (i in 0 until n) travel = max(travel, hypot(xs[i] - sx, ys[i] - sy))
        // Decide only once the finger has really moved: a thumb's page swipe often starts with a curl.
        if (travel < 32 * dp) return Verdict.Pending
        val dx = xs[n - 1] - sx; val dy = ys[n - 1] - sy
        val len = hypot(dx, dy)
        // Mostly sideways and never turning back is a page swipe, even when it arcs like a thumb does.
        if (abs(dx) >= 40 * dp && abs(dx) > 1.6f * abs(dy) && xBacktrack(xs, n, dx) < 10 * dp) return Verdict.Swipe
        // Furthest any point strays from the straight line start -> now.
        var dev = 0f
        for (i in 0 until n) {
            val d = if (len < 1f) hypot(xs[i] - sx, ys[i] - sy) else abs((xs[i] - sx) * dy - (ys[i] - sy) * dx) / len
            dev = max(dev, d)
        }
        val straight = len >= travel * 0.85f && dev < max(8 * dp, 0.15f * len)
        if (!straight) return Verdict.Draw
        val angle = Math.toDegrees(kotlin.math.atan2(abs(dy), abs(dx)).toDouble())
        return when {
            angle < 22 -> if (abs(dx) >= 40 * dp) Verdict.Swipe else Verdict.Pending // the pager is moving: let it have it
            angle > 68 -> Verdict.Pending // vertical swipes act only on release, so a letter may still turn out of one
            else -> Verdict.Draw
        }
    }
}
