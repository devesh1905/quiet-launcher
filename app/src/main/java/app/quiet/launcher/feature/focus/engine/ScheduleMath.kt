package app.quiet.launcher.feature.focus.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedule evaluation on wall-clock windows. Every window is built from a local date and time
 * and converted to an Instant through the zone, so DST gaps and overlaps resolve the way
 * java.time does (a start inside a spring-forward gap moves to the first valid minute; an end
 * inside the autumn overlap uses the earlier offset). Comparisons are then plain instants.
 */
object ScheduleMath {

    data class Window(val startMs: Long, val endMs: Long)

    private fun localTime(minute: Int): LocalTime = LocalTime.of((minute / 60) % 24, minute % 60)

    /** The window that starts on [date], or null when the schedule does not run that day. */
    fun windowStartingOn(s: Schedule, date: LocalDate, zone: ZoneId): Window? {
        if (date.dayOfWeek !in s.days) return null
        if (s.pausedUntil != null && date.isBefore(s.pausedUntil)) return null
        val start = ZonedDateTime.of(date, localTime(s.startMinute), zone).toInstant()
        val endDate = if (s.overnight) date.plusDays(1) else date
        val end = ZonedDateTime.of(endDate, localTime(s.endMinute), zone).toInstant()
        return Window(start.toEpochMilli(), end.toEpochMilli())
    }

    /** The active window containing [nowMs], or null. Disabled schedules are never active. */
    fun activeWindow(s: Schedule, nowMs: Long, zone: ZoneId): Window? {
        if (!s.enabled) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // A window that started yesterday can still be running (overnight or 24 h).
        for (d in listOf(today.minusDays(1), today)) {
            val w = windowStartingOn(s, d, zone) ?: continue
            if (nowMs >= w.startMs && nowMs < w.endMs) return w
        }
        return null
    }

    fun isActive(s: Schedule, nowMs: Long, zone: ZoneId): Boolean = activeWindow(s, nowMs, zone) != null

    /** The next window start strictly after [nowMs] within two weeks, for "starts at" copy. */
    fun nextStart(s: Schedule, nowMs: Long, zone: ZoneId): Long? {
        if (!s.enabled || s.days.isEmpty()) return null
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        for (i in 0..14) {
            val w = windowStartingOn(s, today.plusDays(i.toLong()), zone) ?: continue
            if (w.startMs > nowMs) return w.startMs
        }
        return null
    }

    /** "Morning calm" and "Evening without screens" from wake and bed times (minutes of day). */
    fun guided(wakeMinute: Int, bedMinute: Int, apps: Set<String>, idFactory: () -> String, nowMs: Long): List<Schedule> {
        val all = DayOfWeek.entries.toSet()
        val morningEnd = (wakeMinute + 60) % (24 * 60)
        val eveningStart = ((bedMinute - 60) % (24 * 60) + 24 * 60) % (24 * 60)
        return listOf(
            Schedule(idFactory(), "Morning calm", wakeMinute, morningEnd, all, apps, createdMs = nowMs),
            Schedule(idFactory(), "Evening without screens", eveningStart, bedMinute, all, apps, createdMs = nowMs),
        )
    }

    /** "21:00" style. */
    fun hhmm(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)

    /** Short weekday copy: "Every day", "Weekdays", "Weekends", or "Mon, Wed, Fri". */
    fun daysLabel(days: Set<DayOfWeek>): String {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return when {
            days.size == 7 -> "Every day"
            days == weekdays -> "Weekdays"
            days == weekend -> "Weekends"
            days.isEmpty() -> "No days"
            else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") {
                it.name.take(1) + it.name.substring(1, 3).lowercase()
            }
        }
    }

    fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
    fun maskToDays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
}
