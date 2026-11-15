package app.quiet.launcher.feature.focus

import app.quiet.launcher.feature.focus.engine.BlockInfo
import app.quiet.launcher.feature.focus.engine.BlockReason
import app.quiet.launcher.feature.focus.engine.Friction
import app.quiet.launcher.feature.focus.engine.DefaultRules
import app.quiet.launcher.feature.focus.engine.Schedule
import app.quiet.launcher.feature.focus.engine.TimesUpParams
import app.quiet.launcher.feature.focus.engine.TimesUpReason
import app.quiet.launcher.feature.focus.ui.FocusCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class FocusCopyTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun minuteWordsAndDurations() {
        assertEquals("ten minutes", FocusCopy.minutesWords(10))
        assertEquals("one minute", FocusCopy.minutesWords(1))
        assertEquals("45 minutes", FocusCopy.minutesWords(45))
        assertEquals("24 minutes", FocusCopy.duration(24 * 60_000L))
        assertEquals("4 hours 18 minutes", FocusCopy.duration((4 * 60 + 18) * 60_000L))
        assertEquals("1 hour", FocusCopy.duration(60 * 60_000L))
        assertEquals("3 days", FocusCopy.duration(72 * 3_600_000L))
    }

    @Test
    fun timesUpReadsNaturally() {
        val p = TimesUpParams(TimesUpReason.TIMER, 10 * 60_000L, 24 * 60_000L, 15, 0)
        assertEquals("Your ten minutes on Instagram are up.", FocusCopy.timesUpQuestion("Instagram", p))
        assertEquals("That makes 24 minutes there today. You set 10 for this visit.", FocusCopy.timesUpDetail(p))
        val one = p.copy(grantedMs = 60_000L)
        assertEquals("Your one minute on Instagram is up.", FocusCopy.timesUpQuestion("Instagram", one))
    }

    @Test
    fun blockedReasonUsesTheScheduleName() {
        val weekdays = DayOfWeek.entries.take(5).toSet()
        val s = Schedule("a", "Afternoon focus", 13 * 60, 21 * 60, weekdays, setOf("x"))
        val (title, detail) = FocusCopy.blockedReason(BlockInfo(0, BlockReason.FromSchedule(s)), utc)
        assertEquals("Afternoon focus", title)
        assertEquals("Your schedule, weekdays 13:00 to 21:00.", detail)
    }

    @Test
    fun blockedDateOnlyWhenNotToday() {
        val now = LocalDateTime.of(2026, 11, 14, 12, 0).atZone(utc).toInstant().toEpochMilli()
        assertNull(FocusCopy.blockedDate(now + 3_600_000L, now, utc))
        assertEquals("tomorrow", FocusCopy.blockedDate(now + 20 * 3_600_000L, now, utc))
        assertTrue(FocusCopy.blockedDate(now + 5 * 86_400_000L, now, utc)!!.startsWith("on "))
        assertEquals("21:00", FocusCopy.clock(now + 9 * 3_600_000L, utc, is24h = true))
    }

    @Test
    fun copyAvoidsTemplatePunctuation() {
        val samples = listOf(
            FocusCopy.toast(app.quiet.launcher.feature.focus.engine.ToastKind.BLOCKED_FOR, "YouTube", 15),
            FocusCopy.gateQuestion("YouTube", true),
            FocusCopy.shortVideoToast("YouTube Shorts"),
        ) + FocusCopy.intentions
        samples.forEach { s -> assertFalse(s, s.contains('—') || s.contains('–') || s.contains('·') || s.contains("->")) }
    }

    @Test
    fun frictionAndDefaults() {
        assertEquals(5, Friction.progressive(0))
        assertEquals(10, Friction.progressive(1))
        assertEquals(20, Friction.progressive(7))
        assertTrue(Friction.isLateNight(23) && Friction.isLateNight(2) && !Friction.isLateNight(5))
        assertTrue(DefaultRules.isWatchedByDefault("com.instagram.android", null))
        assertTrue(DefaultRules.isWatchedByDefault("com.some.game", 0))
        assertFalse(DefaultRules.isWatchedByDefault("com.example.notes", 7))
    }
}
