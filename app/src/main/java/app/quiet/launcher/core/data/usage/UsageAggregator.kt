package app.quiet.launcher.core.data.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class RawType { RESUMED, PAUSED, SCREEN_ON, SCREEN_OFF, KEYGUARD_SHOWN, KEYGUARD_HIDDEN }

/** Android-free projection of a UsageEvents.Event. [className] distinguishes activities of one package. */
data class RawEvent(
    val timeMs: Long,
    val packageName: String,
    val type: RawType,
    val className: String? = null,
)

data class Session(val packageName: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Pure aggregation of usage events into sessions, buckets and counters. */
object UsageAggregator {

    /**
     * Builds foreground sessions clipped to [windowStart, windowEnd]. Events before [windowStart]
     * may be supplied (lookback) so that a session already running at the window start is clipped, not lost.
     * Sessions of [ignored] packages still affect the foreground state but are not emitted.
     */
    fun sessions(
        events: List<RawEvent>,
        windowStart: Long,
        windowEnd: Long,
        ignored: Set<String>,
    ): List<Session> {
        val out = ArrayList<Session>()
        var current: String? = null
        var currentStart = 0L
        val resumed = HashSet<String>() // resumed activity classes of [current]

        fun close(t: Long) {
            val pkg = current ?: return
            if (pkg !in ignored) {
                val s = maxOf(currentStart, windowStart)
                val e = minOf(t, windowEnd)
                if (e > s) out.add(Session(pkg, s, e))
            }
            current = null
            resumed.clear()
        }

        for (ev in events.sortedBy { it.timeMs }) {
            if (ev.timeMs > windowEnd) break
            when (ev.type) {
                RawType.RESUMED -> {
                    if (current != null && current != ev.packageName) close(ev.timeMs)
                    if (current == null) {
                        current = ev.packageName
                        currentStart = ev.timeMs
                    }
                    resumed.add(ev.className ?: "")
                }
                RawType.PAUSED -> if (current == ev.packageName) {
                    resumed.remove(ev.className ?: "")
                    // A class-less pause cannot be matched to an activity: treat it as the last one going away.
                    if (ev.className == null) resumed.clear()
                    if (resumed.isEmpty()) close(ev.timeMs)
                }
                RawType.SCREEN_OFF, RawType.KEYGUARD_SHOWN -> close(ev.timeMs)
                RawType.SCREEN_ON, RawType.KEYGUARD_HIDDEN -> Unit
            }
        }
        close(windowEnd)
        return out
    }

    /** Unlocks in the window; falls back to screen-on events when no keyguard-hidden events exist at all. */
    fun pickups(events: List<RawEvent>, windowStart: Long, windowEnd: Long): Int {
        val hasKeyguard = events.any { it.type == RawType.KEYGUARD_HIDDEN }
        val type = if (hasKeyguard) RawType.KEYGUARD_HIDDEN else RawType.SCREEN_ON
        return events.count { it.type == type && it.timeMs in windowStart..windowEnd }
    }

    fun totalMs(sessions: List<Session>): Long = sessions.sumOf { it.durationMs }

    fun perApp(sessions: List<Session>): List<Pair<String, Long>> =
        sessions.groupBy { it.packageName }
            .map { (pkg, list) -> pkg to list.sumOf { it.durationMs } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

    fun startOfDay(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun overlap(sessions: List<Session>, from: Long, to: Long): Long {
        var sum = 0L
        for (s in sessions) {
            val o = minOf(s.endMs, to) - maxOf(s.startMs, from)
            if (o > 0) sum += o
        }
        return sum
    }

    /** 18 buckets, 06:00-24:00 local time of [day]; minutes per hour, rounded to nearest. */
    fun hourlyMinutes(sessions: List<Session>, day: LocalDate, zone: ZoneId): IntArray {
        val out = IntArray(18)
        for (i in 0 until 18) {
            val h = 6 + i
            val from = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
            val to = if (h == 23) startOfDay(day.plusDays(1), zone)
            else day.atTime(h + 1, 0).atZone(zone).toInstant().toEpochMilli()
            out[i] = ((overlap(sessions, from, to) + 30_000L) / 60_000L).toInt()
        }
        return out
    }

    /** Index into hourly buckets for the hour containing [nowMs], or -1 before 06:00. */
    fun currentHourIndex(nowMs: Long, zone: ZoneId): Int {
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        return if (hour < 6) -1 else hour - 6
    }

    /** Total ms per local day for [days] days ending at [today], oldest first. DST-safe (day length varies). */
    fun dailyTotals(sessions: List<Session>, today: LocalDate, zone: ZoneId, days: Int): LongArray =
        LongArray(days) { i ->
            val d = today.minusDays((days - 1 - i).toLong())
            overlap(sessions, startOfDay(d, zone), startOfDay(d.plusDays(1), zone))
        }
}
