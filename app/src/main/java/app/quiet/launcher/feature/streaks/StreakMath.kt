package app.quiet.launcher.feature.streaks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One focus session, start to end (epoch ms). */
data class Span(val startMs: Long, val endMs: Long)

data class Streaks(val current: Int, val best: Int)

/** Pure counting behind the focus heat map and streaks. */
object StreakMath {
    /** A day counts towards a streak at this many focused minutes. */
    const val GOAL_MIN = 25
    const val WEEKS = 20

    /** Monday of the first column: the map ends with the week that holds [today]. */
    fun gridStart(today: LocalDate, weeks: Int = WEEKS): LocalDate {
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return monday.minusWeeks((weeks - 1).toLong())
    }

    fun dayIndex(start: LocalDate, day: LocalDate): Int = ChronoUnit.DAYS.between(start, day).toInt()

    private fun startOf(day: LocalDate, zone: ZoneId) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Focused minutes per local day from [first], [days] days long. A session over midnight is split. */
    fun dailyMinutes(spans: List<Span>, first: LocalDate, days: Int, zone: ZoneId): IntArray {
        val ms = LongArray(days)
        for (i in 0 until days) {
            val from = startOf(first.plusDays(i.toLong()), zone)
            val to = startOf(first.plusDays(i + 1L), zone)
            for (s in spans) {
                val o = minOf(s.endMs, to) - maxOf(s.startMs, from)
                if (o > 0) ms[i] += o
            }
        }
        return IntArray(days) { (ms[it] / 60_000L).toInt() }
    }

    /** Sessions touching [day], clipped to it, in time order. */
    fun spansOn(spans: List<Span>, day: LocalDate, zone: ZoneId): List<Span> {
        val from = startOf(day, zone)
        val to = startOf(day.plusDays(1), zone)
        return spans.filter { it.endMs > from && it.startMs < to }
            .map { Span(maxOf(it.startMs, from), minOf(it.endMs, to)) }
            .sortedBy { it.startMs }
    }

    /**
     * Current and best run of days at or over [goal], up to [todayIndex]. Today only joins the run
     * once it reaches the goal; until then the run still stands from yesterday.
     */
    fun streaks(minutes: IntArray, todayIndex: Int, goal: Int = GOAL_MIN): Streaks {
        val last = todayIndex.coerceAtMost(minutes.size - 1)
        var best = 0
        var run = 0
        for (i in 0..last) {
            run = if (minutes[i] >= goal) run + 1 else 0
            best = maxOf(best, run)
        }
        var cur = 0
        var i = if (last >= 0 && minutes[last] >= goal) last else last - 1
        while (i >= 0 && minutes[i] >= goal) { cur++; i-- }
        return Streaks(cur, best)
    }

    /** Five steps: none, under 30 min, 30 to 60, 1 to 2 hours, over 2 hours. */
    fun level(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes < 30 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }
}
