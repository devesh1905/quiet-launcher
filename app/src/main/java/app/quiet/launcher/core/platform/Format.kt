package app.quiet.launcher.core.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    /** "1h 12m", "48m", "0m". */
    fun duration(ms: Long): String {
        val totalMin = (ms / 60_000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** "2h" / "1h 30m" for budgets. */
    fun budget(minutes: Int): String = duration(minutes * 60_000L)

    /** "1:52" / "-2:09" style, tabular in the UI. */
    fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun clock(epochMs: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return if (is24h) "%02d:%02d".format(t.hour, t.minute)
        else "%d:%02d".format(((t.hour + 11) % 12) + 1, t.minute)
    }

    /** "Saturday, 14 November" in the device locale order. */
    fun longDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val pattern = if (Locale.getDefault().country == "US") "EEEE, MMMM d" else "EEEE, d MMMM"
        return t.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    @Suppress("unused")
    fun shortDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}
