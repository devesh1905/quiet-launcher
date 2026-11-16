package app.quiet.launcher.feature.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Minimal view of a media session used by the pure selection logic. */
interface SessionInfo {
    val packageName: String
    /** True for PLAYING or BUFFERING. */
    val isActivelyPlaying: Boolean
}

internal object MediaLogic {
    /** A paused/stopped track stays on screen this long after music last played, then disappears. */
    const val PAUSED_TTL_MS = 3 * 60_000L

    /**
     * Picks the session to show: first actively playing one; else the one matching [lastPlayingPackage];
     * else the first in the list (the system orders by recent activity). Null for an empty list.
     */
    fun <T : SessionInfo> select(sessions: List<T>, lastPlayingPackage: String?): T? {
        if (sessions.isEmpty()) return null
        sessions.firstOrNull { it.isActivelyPlaying }?.let { return it }
        if (lastPlayingPackage != null) sessions.firstOrNull { it.packageName == lastPlayingPackage }?.let { return it }
        return sessions.first()
    }

    fun trackId(pkg: String, title: String, artist: String, durationMs: Long): String =
        "$pkg:" + (title + "\u0000" + artist + "\u0000" + durationMs).hashCode().toUInt().toString(16)

    /** True when a non-playing session has been idle for longer than the TTL. */
    fun isExpired(isPlaying: Boolean, idleSinceElapsed: Long, nowElapsed: Long): Boolean =
        !isPlaying && nowElapsed - idleSinceElapsed > PAUSED_TTL_MS

    /** Year from a YEAR number or a DATE like "2019-05-01" / "2019". */
    fun yearOf(year: Long, date: String?): String? {
        if (year in 1000..9999) return year.toString()
        val m = date?.let { Regex("(\\d{4})").find(it) } ?: return null
        return m.groupValues[1]
    }

    /** Target dimensions so the longest side is <= [maxSide]. */
    fun scaledSize(w: Int, h: Int, maxSide: Int = 640): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxSide || longest <= 0) return w to h
        val f = maxSide.toFloat() / longest
        return max(1, (w * f).toInt()) to max(1, (h * f).toInt())
    }
}

/** HSL helpers and palette fallback derivation, pure so they can be unit tested. */
internal object PaletteMath {
    /** Neutral warm grey set: darkMuted, vibrant, muted, lightVibrant. */
    val Neutral: IntArray = intArrayOf(0xFF1C1A18.toInt(), 0xFF8A7F73.toInt(), 0xFF4A4540.toInt(), 0xFFC9BFB2.toInt())

    fun rgbToHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(if (h < 0) h + 360f else h, s.coerceIn(0f, 1f), l)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val rgb = when {
            hp < 1 -> floatArrayOf(c, x, 0f)
            hp < 2 -> floatArrayOf(x, c, 0f)
            hp < 3 -> floatArrayOf(0f, c, x)
            hp < 4 -> floatArrayOf(0f, x, c)
            hp < 5 -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(rgb[0]) shl 16) or (ch(rgb[1]) shl 8) or ch(rgb[2])
    }

    /**
     * Builds the 4-colour palette. Any null swatch is derived from [dominant] (or from the first available
     * swatch). With no colours at all, returns a copy of [Neutral].
     */
    fun build(darkMuted: Int?, vibrant: Int?, muted: Int?, lightVibrant: Int?, dominant: Int?): IntArray {
        val base = dominant ?: vibrant ?: muted ?: lightVibrant ?: darkMuted ?: return Neutral.copyOf()
        val hsl = rgbToHsl(base)
        val dm = darkMuted ?: hslToRgb(hsl[0], (hsl[1] * 0.6f).coerceIn(0.1f, 0.5f), 0.14f)
        val vb = vibrant ?: hslToRgb(hsl[0], max(hsl[1], 0.5f).coerceAtMost(0.9f), hsl[2].coerceIn(0.4f, 0.55f))
        val mu = muted ?: hslToRgb(hsl[0], (hsl[1] * 0.5f).coerceIn(0.1f, 0.4f), 0.35f)
        val lv = lightVibrant ?: hslToRgb(hsl[0], max(hsl[1], 0.5f).coerceAtMost(0.9f), 0.72f)
        return intArrayOf(dm, vb, mu, lv)
    }
}
