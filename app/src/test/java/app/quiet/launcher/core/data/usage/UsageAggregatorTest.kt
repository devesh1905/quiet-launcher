package app.quiet.launcher.core.data.usage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class UsageAggregatorTest {
    private val utc = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 1, 15)
    private val d0 = day.atStartOfDay(utc).toInstant().toEpochMilli()
    private val min = 60_000L
    private fun at(h: Int, m: Int = 0) = d0 + h * 60 * min + m * min
    private fun ev(t: Long, p: String, type: RawType, c: String? = "A") = RawEvent(t, p, type, c)
    private val none = emptySet<String>()

    @Test fun simpleSession() {
        val s = UsageAggregator.sessions(
            listOf(ev(at(9), "a", RawType.RESUMED), ev(at(9, 10), "a", RawType.PAUSED)), d0, at(12), none)
        assertEquals(listOf(Session("a", at(9), at(9, 10))), s)
    }

    @Test fun overlappingResumesOfSamePackageCountOnce() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "a", RawType.RESUMED, "A1"),
            ev(at(9, 5), "a", RawType.RESUMED, "A2"),
            ev(at(9, 5), "a", RawType.PAUSED, "A1"),
            ev(at(9, 20), "a", RawType.PAUSED, "A2"),
        ), d0, at(12), none)
        assertEquals(1, s.size)
        assertEquals(20 * min, UsageAggregator.totalMs(s))
    }

    @Test fun otherPackageResumeEndsPrevious() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "a", RawType.RESUMED),
            ev(at(9, 10), "b", RawType.RESUMED),
            ev(at(9, 15), "a", RawType.PAUSED), // late pause of a must not end b
            ev(at(9, 30), "b", RawType.PAUSED),
        ), d0, at(12), none)
        assertEquals(listOf(Session("a", at(9), at(9, 10)), Session("b", at(9, 10), at(9, 30))), s)
    }

    @Test fun screenOffEndsSession() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "a", RawType.RESUMED), ev(at(9, 7), "", RawType.SCREEN_OFF, null)), d0, at(12), none)
        assertEquals(7 * min, UsageAggregator.totalMs(s))
    }

    @Test fun keyguardShownEndsSession() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "a", RawType.RESUMED), ev(at(9, 3), "", RawType.KEYGUARD_SHOWN, null)), d0, at(12), none)
        assertEquals(3 * min, UsageAggregator.totalMs(s))
    }

    @Test fun openSessionEndsAtNow() {
        val s = UsageAggregator.sessions(listOf(ev(at(9), "a", RawType.RESUMED)), d0, at(9, 42), none)
        assertEquals(42 * min, UsageAggregator.totalMs(s))
    }

    @Test fun sessionSpanningWindowStartIsClipped() {
        val s = UsageAggregator.sessions(listOf(
            ev(d0 - 30 * min, "a", RawType.RESUMED), ev(d0 + 15 * min, "a", RawType.PAUSED)), d0, at(12), none)
        assertEquals(listOf(Session("a", d0, d0 + 15 * min)), s)
    }

    @Test fun sessionSpanningWindowEndIsClipped() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "a", RawType.RESUMED), ev(at(11), "a", RawType.PAUSED)), d0, at(10), none)
        assertEquals(60 * min, UsageAggregator.totalMs(s))
    }

    @Test fun hourBoundarySplitsIntoBuckets() {
        val s = listOf(Session("a", at(9, 50), at(10, 20)))
        val h = UsageAggregator.hourlyMinutes(s, day, utc)
        assertEquals(10, h[3]); assertEquals(20, h[4])
        assertEquals(30, h.sum())
    }

    @Test fun usageBeforeSixCountsInTotalButNoBucket() {
        val s = listOf(Session("a", at(5, 0), at(5, 30)))
        assertEquals(30 * min, UsageAggregator.totalMs(s))
        assertEquals(0, UsageAggregator.hourlyMinutes(s, day, utc).sum())
    }

    @Test fun currentHourIndex() {
        assertEquals(-1, UsageAggregator.currentHourIndex(at(5, 59), utc))
        assertEquals(0, UsageAggregator.currentHourIndex(at(6), utc))
        assertEquals(17, UsageAggregator.currentHourIndex(at(23, 59), utc))
    }

    @Test fun ignoredPackagesExcluded() {
        val s = UsageAggregator.sessions(listOf(
            ev(at(9), "launcher", RawType.RESUMED),
            ev(at(9, 5), "a", RawType.RESUMED),
            ev(at(9, 15), "a", RawType.PAUSED),
        ), d0, at(12), setOf("launcher"))
        assertEquals(listOf(Session("a", at(9, 5), at(9, 15))), s)
    }

    @Test fun pickupsCountKeyguardHidden() {
        val e = listOf(
            ev(at(8), "", RawType.KEYGUARD_HIDDEN, null), ev(at(9), "", RawType.KEYGUARD_HIDDEN, null),
            ev(at(9), "", RawType.SCREEN_ON, null), ev(at(10), "", RawType.SCREEN_ON, null),
            ev(at(11), "", RawType.SCREEN_ON, null))
        assertEquals(2, UsageAggregator.pickups(e, d0, at(12)))
    }

    @Test fun pickupsFallBackToScreenOn() {
        val e = listOf(ev(at(9), "", RawType.SCREEN_ON, null), ev(at(10), "", RawType.SCREEN_ON, null))
        assertEquals(2, UsageAggregator.pickups(e, d0, at(12)))
    }

    @Test fun perAppSortedDescending() {
        val r = UsageAggregator.perApp(listOf(
            Session("a", 0, 10), Session("b", 20, 50), Session("a", 60, 65)))
        assertEquals(listOf("b" to 30L, "a" to 15L), r)
    }

    @Test fun dstShortDayIs23Hours() {
        val london = ZoneId.of("Europe/London")
        val dst = LocalDate.of(2026, 3, 29)
        val start = UsageAggregator.startOfDay(dst, london)
        val next = UsageAggregator.startOfDay(dst.plusDays(1), london)
        assertEquals(23 * 60 * min, next - start)
        val s = listOf(Session("a",
            UsageAggregator.startOfDay(dst.minusDays(1), london) + 12 * 60 * min,
            next + 12 * 60 * min))
        val totals = UsageAggregator.dailyTotals(s, dst.plusDays(1), london, 3)
        assertArrayEquals(longArrayOf(12 * 60 * min, 23 * 60 * min, 12 * 60 * min), totals)
    }

    @Test fun dstHourlyBucketsUseLocalTime() {
        val london = ZoneId.of("Europe/London")
        val dst = LocalDate.of(2026, 3, 29)
        val tenLocal = dst.atTime(10, 0).atZone(london).toInstant().toEpochMilli()
        val h = UsageAggregator.hourlyMinutes(listOf(Session("a", tenLocal, tenLocal + 30 * min)), dst, london)
        assertEquals(30, h[4])
        assertEquals(30, h.sum())
    }
}
