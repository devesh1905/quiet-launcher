package app.quiet.launcher.feature.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class UnistrokeTest {

    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, n: Int = 40) = (0..n).map { i ->
        val a = (a0 + (a1 - a0) * i / n) * PI.toFloat() / 180f
        Pt(cx + rx * cos(a), cy + ry * sin(a))
    }

    private fun poly(vararg xy: Float): List<Pt> {
        // Dense points along each segment, like a finger would report.
        val out = ArrayList<Pt>()
        for (i in 0 until xy.size - 2 step 2) {
            for (k in 0 until 12) {
                val t = k / 12f
                out += Pt(xy[i] + (xy[i + 2] - xy[i]) * t, xy[i + 1] + (xy[i + 3] - xy[i + 1]) * t)
            }
        }
        out += Pt(xy[xy.size - 2], xy[xy.size - 1])
        return out
    }

    /** A hand-drawn version: on-screen size, a little stretch, a small tilt, wobble. */
    private fun hand(shape: List<Pt>, seed: Int): List<Pt> {
        val r = Random(seed)
        val size = 180f + r.nextFloat() * 160f
        val sx = size * (0.85f + r.nextFloat() * 0.3f) / 100f
        val sy = size * (0.85f + r.nextFloat() * 0.3f) / 100f
        val rot = (r.nextFloat() * 12f - 6f) * PI.toFloat() / 180f
        val ox = 200f + r.nextFloat() * 300f; val oy = 600f + r.nextFloat() * 400f
        return shape.map { p ->
            val x = (p.x - 50f) * sx + (r.nextFloat() - 0.5f) * size * 0.04f
            val y = (p.y - 50f) * sy + (r.nextFloat() - 0.5f) * size * 0.04f
            Pt(ox + x * cos(rot) - y * sin(rot), oy + x * sin(rot) + y * cos(rot))
        }
    }

    private val c = arc(50f, 50f, 44f, 46f, -50f, -310f)
    private val m = poly(12f, 92f, 18f, 8f, 50f, 60f, 82f, 8f, 88f, 92f)
    private val w = poly(10f, 8f, 28f, 92f, 50f, 40f, 72f, 92f, 90f, 8f)

    @Test fun defaultLettersAreRecognised() {
        for (seed in 1..8) {
            assertEquals("C seed $seed", 'C', Unistroke.recognise(hand(c, seed)).letter)
            assertEquals("M seed $seed", 'M', Unistroke.recognise(hand(m, seed)).letter)
            assertEquals("W seed $seed", 'W', Unistroke.recognise(hand(w, seed)).letter)
        }
    }

    @Test fun lettersDrawnBackwardsStillMatch() {
        assertEquals('C', Unistroke.recognise(hand(c.reversed(), 3)).letter)
        assertEquals('M', Unistroke.recognise(hand(m.reversed(), 4)).letter)
    }

    @Test fun otherLettersAreRecognised() {
        val s = arc(50f, 28f, 24f, 22f, -30f, -270f) + arc(50f, 72f, 24f, 22f, -90f, 150f)
        val n = poly(15f, 90f, 15f, 10f, 85f, 90f, 85f, 10f)
        val l = poly(20f, 10f, 20f, 90f, 80f, 90f)
        val u = poly(16f, 8f, 16f, 60f) + arc(50f, 60f, 34f, 32f, 180f, 0f) + poly(84f, 60f, 84f, 8f)
        for (seed in 1..5) {
            assertEquals('U', Unistroke.recognise(hand(u, seed)).letter)
            assertEquals('S', Unistroke.recognise(hand(s, seed)).letter)
            assertEquals('N', Unistroke.recognise(hand(n, seed)).letter)
            assertEquals('L', Unistroke.recognise(hand(l, seed)).letter)
        }
    }

    @Test fun decoysAreRejected() {
        val o = arc(50f, 50f, 45f, 45f, -90f, -450f)
        val z = poly(10f, 10f, 90f, 10f, 10f, 90f, 90f, 90f)
        val lines = listOf(poly(0f, 50f, 100f, 50f), poly(50f, 0f, 50f, 100f), poly(5f, 5f, 95f, 95f))
        for (seed in 1..6) {
            assertNull("O seed $seed", Unistroke.recognise(hand(o, seed)).letter)
            assertNull("Z seed $seed", Unistroke.recognise(hand(z, seed)).letter)
            lines.forEach { assertNull("line seed $seed", Unistroke.recognise(hand(it, seed)).letter) }
        }
    }

    @Test fun lettersWithoutAnAppAreIgnored() {
        val res = Unistroke.recognise(hand(w, 2), allowed = setOf('C', 'M'))
        assertNull(res.letter)
        assertEquals("W", res.top)
    }

    @Test fun scribbleIsRejected() {
        val r = Random(9)
        val scribble = List(60) { Pt(r.nextFloat() * 300f, r.nextFloat() * 300f) }
        assertNull(Unistroke.recognise(scribble).letter)
    }

    @Test fun gateLetsStraightSwipesThrough() {
        val dp = 2.75f
        fun arr(pts: List<Pt>) = Triple(FloatArray(pts.size) { pts[it].x }, FloatArray(pts.size) { pts[it].y }, pts.size)
        val right = arr(List(30) { Pt(100f + it * 8f, 500f + it * 0.3f) })
        assertEquals(StrokeGate.Verdict.Swipe, StrokeGate.classify(right.first, right.second, right.third, dp))
        val up = arr(List(40) { Pt(300f + it * 0.2f, 1200f - it * 10f) })
        assertEquals(StrokeGate.Verdict.Pending, StrokeGate.classify(up.first, up.second, up.third, dp))
        val diag = arr(List(30) { Pt(100f + it * 4f, 500f + it * 4f) })
        assertEquals(StrokeGate.Verdict.Draw, StrokeGate.classify(diag.first, diag.second, diag.third, dp))
        val tiny = arr(List(5) { Pt(100f + it, 500f) })
        assertEquals(StrokeGate.Verdict.Pending, StrokeGate.classify(tiny.first, tiny.second, tiny.third, dp))
    }
}
