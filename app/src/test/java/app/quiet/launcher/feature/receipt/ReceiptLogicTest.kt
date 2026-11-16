package app.quiet.launcher.feature.receipt

import app.quiet.launcher.core.data.usage.RawEvent
import app.quiet.launcher.core.data.usage.RawType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptLogicTest {
    @Test fun everyPatternIsNineElementsWithThreeWide() {
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. *".forEach { c ->
            val p = Code39.pattern(c)
            assertEquals("length of $c", 9, p.length)
            assertEquals("wide count of $c", 3, p.count { it == 'w' })
        }
    }

    @Test fun knownPatterns() {
        assertEquals("nnnwwnwnn", Code39.pattern('0'))
        assertEquals("wnnwnnnnw", Code39.pattern('1'))
        assertEquals("wnnnnwnnw", Code39.pattern('A'))
        assertEquals("nwnnwnwnn", Code39.pattern('*'))
    }

    @Test fun elementsWrapInStartStopWithNarrowGaps() {
        val e = Code39.elements("1")
        assertEquals("nwnnwnwnn" + "n" + "wnnwnnnnw" + "n" + "nwnnwnwnn", e)
        // 10 characters plus start and stop: 12 * 9 elements and 11 gaps, odd so it ends on a bar.
        val full = Code39.elements("0318141126")
        assertEquals(12 * 9 + 11, full.length)
        assertEquals(1, full.length % 2)
    }

    @Test fun rejectsWhatCode39CannotCarry() {
        assertFalse(Code39.canEncode("a"))
        assertFalse(Code39.canEncode("1*2"))
        assertTrue(Code39.canEncode("0318 1411-26"))
        try { Code39.elements("é"); throw AssertionError("expected a failure") } catch (_: IllegalArgumentException) {}
    }

    @Test fun receiptNumbersCountDaysSinceInstall() {
        val install = LocalDate.of(2026, 1, 1)
        assertEquals(1, ReceiptMath.receiptNumber(install, install))
        assertEquals(318, ReceiptMath.receiptNumber(install, LocalDate.of(2026, 11, 14)))
        assertEquals(1, ReceiptMath.receiptNumber(install, install.minusDays(3)))
        assertEquals(9999, ReceiptMath.receiptNumber(install, install.plusDays(9998)))
        assertEquals(1, ReceiptMath.receiptNumber(install, install.plusDays(9999)))
    }

    @Test fun codeIsNumberAndDate() {
        val code = ReceiptMath.code(318, LocalDate.of(2026, 11, 14))
        assertEquals("0318141126", code)
        assertEquals("0318 1411 26", ReceiptMath.spacedCode(code))
        assertTrue(Code39.canEncode(code))
    }

    private fun ev(t: Long, type: RawType) = RawEvent(t, "", type)

    @Test fun firstPickupAndLongestAway() {
        val events = listOf(
            ev(100, RawType.SCREEN_OFF), // night before, ignored
            ev(1_000, RawType.SCREEN_ON), ev(1_010, RawType.KEYGUARD_HIDDEN),
            ev(2_000, RawType.SCREEN_OFF), ev(2_001, RawType.KEYGUARD_SHOWN),
            ev(9_000, RawType.SCREEN_ON), ev(9_005, RawType.KEYGUARD_HIDDEN), // away 7004
            ev(10_000, RawType.SCREEN_OFF),
            ev(12_000, RawType.KEYGUARD_HIDDEN), // away 2000
            ev(13_000, RawType.SCREEN_OFF), // still down at the end: not counted
        )
        assertEquals(1_010L, ReceiptMath.firstPickup(events, 500, 20_000))
        assertEquals(7_005L, ReceiptMath.longestAway(events, 500, 20_000))
    }

    @Test fun screenOnCountsWithoutKeyguard() {
        val events = listOf(ev(1_000, RawType.SCREEN_ON), ev(2_000, RawType.SCREEN_OFF), ev(5_000, RawType.SCREEN_ON))
        assertEquals(1_000L, ReceiptMath.firstPickup(events, 0, 10_000))
        assertEquals(3_000L, ReceiptMath.longestAway(events, 0, 10_000))
        assertNull(ReceiptMath.firstPickup(emptyList(), 0, 10))
        assertEquals(0L, ReceiptMath.longestAway(emptyList(), 0, 10))
    }

    @Test fun formatting() {
        assertEquals("45 min", ReceiptMath.hm(45))
        assertEquals("2h 05m", ReceiptMath.hm(125))
        assertEquals("15 min under your 2h", ReceiptMath.allowanceLine(105, 120))
        assertEquals("1h 10m over your 1h 30m", ReceiptMath.allowanceLine(160, 90))
    }

    @Test fun topAppsLeaveTheRestAsEverythingElse() {
        val apps = (1..8).map { "App$it" to it * 60_000L }
        val (top, rest) = ReceiptMath.topApps(apps, totalMs = 36 * 60_000L + 20_000L)
        assertEquals(listOf(8, 7, 6, 5, 4, 3), top.map { it.second })
        assertEquals("App8", top.first().first)
        assertEquals(36 - 33, rest)
    }

    @Test fun nextReceiptTime() {
        val evening = java.time.LocalDateTime.of(2026, 11, 14, 21, 59)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 14, 22, 0), ReceiptScheduler.nextAt(evening, 22 * 60))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 15, 22, 0), ReceiptScheduler.nextAt(evening.plusMinutes(1), 22 * 60))
    }
}
