package app.quiet.launcher.feature.streaks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StreakMathTest {
    private val g = StreakMath.GOAL_MIN

    @Test fun emptyHistory() {
        assertEquals(Streaks(0, 0), StreakMath.streaks(IntArray(140), 100))
    }

    @Test fun gapsBreakTheRun() {
        //            0  1  2  3  4  5  6  7
        val m = intArrayOf(30, 40, 0, 25, 26, 90, 10, 50)
        assertEquals(Streaks(current = 1, best = 3), StreakMath.streaks(m, 7))
    }

    @Test fun todayUnderGoalKeepsYesterdaysRun() {
        val m = intArrayOf(0, 30, 30, 30, 12)
        assertEquals(Streaks(current = 3, best = 3), StreakMath.streaks(m, 4))
    }

    @Test fun todayJoinsOnceItReachesTheGoal() {
        val m = intArrayOf(0, 30, 30, 30, g)
        assertEquals(Streaks(current = 4, best = 4), StreakMath.streaks(m, 4))
    }

    @Test fun yesterdayMissedMeansNoCurrentRun() {
        val m = intArrayOf(30, 30, 30, 0, 5)
        assertEquals(Streaks(current = 0, best = 3), StreakMath.streaks(m, 4))
    }

    @Test fun futureDaysAreIgnored() {
        val m = intArrayOf(30, 30, 99, 99)
        assertEquals(Streaks(current = 2, best = 2), StreakMath.streaks(m, 1))
    }

    @Test fun gridStartsOnAMondayNineteenWeeksBack() {
        val today = LocalDate.of(2026, 11, 14) // Saturday
        val start = StreakMath.gridStart(today)
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(19 * 7 + 5, StreakMath.dayIndex(start, today))
        assertEquals(19 * 7, StreakMath.dayIndex(StreakMath.gridStart(LocalDate.of(2026, 11, 16)), LocalDate.of(2026, 11, 16)))
    }

    @Test fun minutesSplitOverMidnight() {
        val zone: ZoneId = ZoneOffset.UTC
        val day = LocalDate.of(2026, 3, 1)
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val spans = listOf(
            Span(midnight - 20 * 60_000L, midnight + 30 * 60_000L),
            Span(midnight + 60 * 60_000L, midnight + 70 * 60_000L),
        )
        assertEquals(listOf(20, 40, 0), StreakMath.dailyMinutes(spans, day, 3, zone).toList())
        val on = StreakMath.spansOn(spans, day.plusDays(1), zone)
        assertEquals(2, on.size)
        assertEquals(midnight, on[0].startMs)
    }

    @Test fun levels() {
        assertEquals(listOf(0, 1, 2, 2, 3, 4), listOf(0, 29, 30, 59, 119, 120).map(StreakMath::level))
    }
}
