package app.quiet.launcher.feature.focus

import app.quiet.launcher.feature.focus.engine.Schedule
import app.quiet.launcher.feature.focus.engine.ScheduleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleMathTest {
    private val utc = ZoneId.of("UTC")
    private val london = ZoneId.of("Europe/London")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun utcMs(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun schedule(start: Int, end: Int, days: Set<DayOfWeek>, paused: LocalDate? = null, enabled: Boolean = true) =
        Schedule("id", "Test", start, end, days, setOf("x"), enabled, paused)

    // 16 November 2026 is a Monday.

    @Test
    fun sameDayWindowIsHalfOpen() {
        val s = schedule(9 * 60, 17 * 60, setOf(MONDAY))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 12), utc))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 9), utc))
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 17), utc))
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 17, 12), utc))
    }

    @Test
    fun overnightRunsIntoTheNextDay() {
        val s = schedule(22 * 60, 7 * 60, setOf(FRIDAY)) // Friday 20 Nov 2026
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 20, 21, 59), utc))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 20, 23), utc))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 21, 3), utc))
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 21, 7), utc))
        // Saturday evening is not a start day.
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 21, 22, 30), utc))
    }

    @Test
    fun sundayOvernightEndsMondayMorning() {
        val s = schedule(23 * 60, 6 * 60, setOf(SUNDAY))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 5, 59), utc))
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 6), utc))
    }

    @Test
    fun equalStartAndEndMeansTwentyFourHours() {
        val s = schedule(0, 0, setOf(MONDAY))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 0), utc))
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 16, 23, 59), utc))
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 17, 0), utc))
    }

    @Test
    fun disabledOrPausedIsInactive() {
        val days = DayOfWeek.entries.toSet()
        val now = at(utc, 2026, 11, 16, 12)
        assertFalse(ScheduleMath.isActive(schedule(9 * 60, 17 * 60, days, enabled = false), now, utc))
        val paused = schedule(9 * 60, 17 * 60, days, paused = LocalDate.of(2026, 11, 17))
        assertFalse(ScheduleMath.isActive(paused, now, utc))
        assertTrue(ScheduleMath.isActive(paused, at(utc, 2026, 11, 17, 12), utc))
    }

    @Test
    fun pausingAnOvernightWindowCoversItsMorning() {
        val s = schedule(22 * 60, 7 * 60, DayOfWeek.entries.toSet(), paused = LocalDate.of(2026, 11, 17))
        // Window started on the 16th (paused) still running on the 17th: off.
        assertFalse(ScheduleMath.isActive(s, at(utc, 2026, 11, 17, 3), utc))
        // The window starting on the 17th is back on.
        assertTrue(ScheduleMath.isActive(s, at(utc, 2026, 11, 17, 23), utc))
    }

    @Test
    fun springForwardGapShiftsTheStart() {
        // 29 March 2026: 01:00 GMT jumps to 02:00 BST. 01:30 local does not exist.
        val s = schedule(90, 3 * 60, setOf(SUNDAY))
        val w = ScheduleMath.windowStartingOn(s, LocalDate.of(2026, 3, 29), london)
        assertNotNull(w)
        assertEquals(utcMs("2026-03-29T01:30:00Z"), w!!.startMs) // 02:30 BST
        assertEquals(utcMs("2026-03-29T02:00:00Z"), w.endMs) // 03:00 BST
        assertFalse(ScheduleMath.isActive(s, utcMs("2026-03-29T01:00:00Z"), london))
        assertTrue(ScheduleMath.isActive(s, utcMs("2026-03-29T01:45:00Z"), london))
    }

    @Test
    fun overnightAcrossSpringForwardIsOneHourShorter() {
        val s = schedule(22 * 60, 7 * 60, setOf(SATURDAY))
        val w = ScheduleMath.windowStartingOn(s, LocalDate.of(2026, 3, 28), london)!!
        assertEquals(8 * 3_600_000L, w.endMs - w.startMs)
        assertTrue(ScheduleMath.isActive(s, utcMs("2026-03-29T05:30:00Z"), london)) // 06:30 BST
        assertFalse(ScheduleMath.isActive(s, utcMs("2026-03-29T06:00:00Z"), london)) // 07:00 BST
    }

    @Test
    fun overnightAcrossFallBackIsOneHourLonger() {
        val s = schedule(22 * 60, 7 * 60, setOf(SATURDAY))
        val w = ScheduleMath.windowStartingOn(s, LocalDate.of(2026, 10, 24), london)!!
        assertEquals(10 * 3_600_000L, w.endMs - w.startMs)
        assertEquals(utcMs("2026-10-25T07:00:00Z"), w.endMs)
        assertTrue(ScheduleMath.isActive(s, utcMs("2026-10-25T06:30:00Z"), london))
        assertFalse(ScheduleMath.isActive(s, utcMs("2026-10-25T07:00:00Z"), london))
    }

    @Test
    fun activeWindowReportsTheEnd() {
        val s = schedule(13 * 60, 21 * 60, DayOfWeek.entries.toSet())
        val w = ScheduleMath.activeWindow(s, at(utc, 2026, 11, 16, 15), utc)!!
        assertEquals(at(utc, 2026, 11, 16, 21), w.endMs)
    }

    @Test
    fun nextStartSkipsToTheNextStartDay() {
        val s = schedule(9 * 60, 10 * 60, setOf(FRIDAY))
        assertEquals(at(utc, 2026, 11, 20, 9), ScheduleMath.nextStart(s, at(utc, 2026, 11, 16, 12), utc))
    }

    @Test
    fun guidedSetupBuildsMorningAndEvening() {
        var n = 0
        val list = ScheduleMath.guided(7 * 60, 23 * 60, setOf("a"), { "id${n++}" }, 0L)
        assertEquals(listOf("Morning calm", "Evening without screens"), list.map { it.name })
        assertEquals(7 * 60 to 8 * 60, list[0].startMinute to list[0].endMinute)
        assertEquals(22 * 60 to 23 * 60, list[1].startMinute to list[1].endMinute)
        val late = ScheduleMath.guided(23 * 60 + 30, 30, emptySet(), { "x" }, 0L)
        assertEquals(30 to 0, late[0].startMinute - 23 * 60 to late[0].endMinute - 30)
        assertEquals(23 * 60 + 30, late[1].startMinute)
        assertTrue(late[1].overnight)
    }

    @Test
    fun daysMaskRoundTripsAndLabels() {
        val days = setOf(MONDAY, FRIDAY, SUNDAY)
        assertEquals(days, ScheduleMath.maskToDays(ScheduleMath.daysToMask(days)))
        assertEquals("Every day", ScheduleMath.daysLabel(DayOfWeek.entries.toSet()))
        assertEquals("Weekdays", ScheduleMath.daysLabel(DayOfWeek.entries.take(5).toSet()))
        assertEquals("Weekends", ScheduleMath.daysLabel(setOf(SATURDAY, SUNDAY)))
        assertEquals("Mon, Fri, Sun", ScheduleMath.daysLabel(days))
    }
}
