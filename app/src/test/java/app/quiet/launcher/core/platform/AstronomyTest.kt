package app.quiet.launcher.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

class AstronomyTest {
    private val utc = ZoneId.of("UTC")

    private fun phaseDistanceTo(target: Double, instant: Instant): Double {
        val p = moonPhase(instant)
        return min(abs(p - target), min(abs(p - target + 1), abs(p - target - 1)))
    }

    private fun assertDayWithin(expected: LocalDate, actual: LocalDate, days: Long = 1) {
        assertTrue("expected $expected +-$days got $actual", abs(expected.toEpochDay() - actual.toEpochDay()) <= days)
    }

    private fun assertMinutesWithin(expected: ZonedDateTime, actual: ZonedDateTime?, minutes: Long) {
        assertTrue("null time", actual != null)
        val diff = abs(Duration.between(expected, actual).toMinutes())
        assertTrue("expected $expected got $actual", diff <= minutes)
    }

    @Test fun fullMoon2024() {
        val d = nextFullMoon(ZonedDateTime.of(2024, 1, 20, 12, 0, 0, 0, utc))
        assertDayWithin(LocalDate.of(2024, 1, 25), d)
    }

    @Test fun fullMoon2026() {
        val d = nextFullMoon(ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, utc))
        assertDayWithin(LocalDate.of(2026, 11, 24), d)
    }

    @Test fun newMoons() {
        // 2024-01-11 11:57 UTC and 2026-12-09 00:52 UTC; 1 day is about 0.034 of a cycle.
        assertTrue(phaseDistanceTo(0.0, Instant.parse("2024-01-11T11:57:00Z")) < 0.02)
        assertTrue(phaseDistanceTo(0.0, Instant.parse("2026-12-09T00:52:00Z")) < 0.02)
        assertTrue(phaseDistanceTo(0.5, Instant.parse("2024-01-25T17:54:00Z")) < 0.02)
    }

    @Test fun illuminationAndNames() {
        assertEquals(0.0, illuminatedFraction(0.0), 1e-9)
        assertEquals(1.0, illuminatedFraction(0.5), 1e-9)
        assertEquals(0.5, illuminatedFraction(0.25), 1e-9)
        assertEquals("New moon", phaseName(0.0))
        assertEquals("New moon", phaseName(0.99))
        assertEquals("Waxing crescent", phaseName(0.125))
        assertEquals("First quarter", phaseName(0.25))
        assertEquals("Waxing gibbous", phaseName(0.375))
        assertEquals("Full moon", phaseName(0.5))
        assertEquals("Waning gibbous", phaseName(0.625))
        assertEquals("Last quarter", phaseName(0.75))
        assertEquals("Waning crescent", phaseName(0.875))
    }

    @Test fun londonMidsummer() {
        val z = ZoneId.of("Europe/London")
        val s = sunTimes(LocalDate.of(2026, 6, 21), 51.5074, -0.1278, z)
        assertMinutesWithin(ZonedDateTime.of(2026, 6, 21, 4, 43, 0, 0, z), s.sunrise, 3)
        assertMinutesWithin(ZonedDateTime.of(2026, 6, 21, 21, 21, 0, 0, z), s.sunset, 3)
        assertTrue(s.civilDawn!!.isBefore(s.sunrise))
        assertTrue(s.civilDusk!!.isAfter(s.sunset))
    }

    @Test fun newDelhiNovember() {
        val z = ZoneId.of("Asia/Kolkata")
        val s = sunTimes(LocalDate.of(2026, 11, 14), 28.61, 77.21, z)
        assertMinutesWithin(ZonedDateTime.of(2026, 11, 14, 6, 43, 0, 0, z), s.sunrise, 5)
        assertMinutesWithin(ZonedDateTime.of(2026, 11, 14, 17, 28, 0, 0, z), s.sunset, 5)
    }

    @Test fun polarNightIsNull() {
        val s = sunTimes(LocalDate.of(2026, 12, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen"))
        assertEquals(null, s.sunrise)
        assertEquals(null, s.sunset)
    }

    @Test fun arcFractionAndAltitude() {
        val z = ZoneId.of("Europe/London")
        val s = sunTimes(LocalDate.of(2026, 6, 21), 51.5074, -0.1278, z)
        assertEquals(0f, sunArcFraction(s.sunrise!!, s), 1e-4f)
        assertEquals(1f, sunArcFraction(s.sunset!!, s), 1e-4f)
        assertTrue(sunArcFraction(s.sunrise!!.minusHours(1), s) < 0f)
        assertTrue(sunArcFraction(s.sunset!!.plusHours(1), s) > 1f)
        val noon = ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, z).toInstant()
        val alt = sunAltitudeDeg(noon, 51.5074, -0.1278)
        assertTrue("alt $alt", alt in 60.0..63.0)
        assertTrue(sunAltitudeDeg(ZonedDateTime.of(2026, 6, 21, 1, 0, 0, 0, z).toInstant(), 51.5074, -0.1278) < 0)
    }

    @Test fun timeWords() {
        assertEquals("Sixteen forty-two", timeInWords(16, 42))
        assertEquals("Nine oh five", timeInWords(9, 5))
        assertEquals("Twelve hundred", timeInWords(12, 0))
        assertEquals("Sixteen hundred", timeInWords(16, 0))
        assertEquals("Zero thirty", timeInWords(0, 30))
        assertEquals("Twenty-three fifty-nine", timeInWords(23, 59))
        assertEquals("Seven ten", timeInWords(7, 10))
        assertEquals("Eleven twenty", timeInWords(11, 20))
    }
}
