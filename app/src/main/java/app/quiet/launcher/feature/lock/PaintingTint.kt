package app.quiet.launcher.feature.lock

import kotlin.math.pow

/**
 * Colours from today's painting for the lock clock and the frame's sheen (pure ARGB maths, no
 * Android types, so it is unit-tested). The clock keeps at least [CLOCK_MIN_CONTRAST]:1 on the wall.
 */
object PaintingTint {
    const val CLOCK_MIN_CONTRAST = 9.0

    /** The lock ink mixed half with the light vibrant swatch, pushed back toward ink until it reads at 9:1. */
    fun clock(lightVibrant: Int, ink: Int, bg: Int): Int =
        ensureContrast(mix(ink, lightVibrant, 0.5f), ink, bg, CLOCK_MIN_CONTRAST)

    /** The picture light's sheen: white warmed by the painting, so it never looks grey or blue on a warm work. */
    fun sheen(lightVibrant: Int): Int = mix(0xFFFFFFFF.toInt(), lightVibrant, 0.5f)

    /** Mix [c] toward [ink] in 4% steps until it has [ratio] contrast on [bg]; ink itself if nothing does. */
    fun ensureContrast(c: Int, ink: Int, bg: Int, ratio: Double): Int {
        if (contrast(c, bg) >= ratio) return c
        var t = 0f
        while (t < 1f) {
            t += 0.04f
            val out = mix(c, ink, t.coerceAtMost(1f))
            if (contrast(out, bg) >= ratio) return out
        }
        return ink
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG contrast ratio. */
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** WCAG relative luminance of an sRGB colour. */
    fun luminance(c: Int): Double {
        fun lin(s: Int): Double {
            val v = ((c shr s) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }
}

/**
 * The lock player's breath: a fixed slow cycle, not the beat (Android gives no tempo without the
 * microphone, and players publish none). 0 at rest, 1 at the top of the breath.
 */
object Breath {
    const val PERIOD_MS = 5_500L
    /** The framed cover grows by at most this much at the top of a breath. */
    const val SCALE = 0.012f
    /** The field's light spill swells by up to this fraction with it (the luminance cap does not move). */
    const val GLOW = 0.3f

    fun at(elapsedMs: Long, periodMs: Long = PERIOD_MS): Float {
        val f = (elapsedMs % periodMs).toFloat() / periodMs
        return ((1 - kotlin.math.cos(2 * Math.PI * f)) / 2).toFloat()
    }

    /** Where on the rising half of the cycle the breath reaches [level]. */
    fun msFor(level: Float, periodMs: Long = PERIOD_MS): Long =
        (kotlin.math.acos((1 - 2 * level.coerceIn(0f, 1f)).toDouble()) / (2 * Math.PI) * periodMs).toLong()
}
