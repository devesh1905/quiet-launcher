package app.quiet.launcher.feature.focus.ui

import app.quiet.launcher.feature.focus.engine.BlockInfo
import app.quiet.launcher.feature.focus.engine.BlockReason
import app.quiet.launcher.feature.focus.engine.GateParams
import app.quiet.launcher.feature.focus.engine.ScheduleMath
import app.quiet.launcher.feature.focus.engine.TimesUpParams
import app.quiet.launcher.feature.focus.engine.TimesUpReason
import app.quiet.launcher.feature.focus.engine.ToastKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * All focus copy in one place, as pure functions. Plain sentences, sentence case, no dashes or
 * arrows, numbers as figures except small minute counts in questions.
 */
object FocusCopy {
    private val small = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    )

    /** "ten minutes", "one minute", "45 minutes". */
    fun minutesWords(n: Int): String {
        val num = when {
            n in 0..20 -> small[n]
            n == 30 -> "thirty"
            n == 40 -> "forty"
            n == 50 -> "fifty"
            n == 60 -> "sixty"
            else -> n.toString()
        }
        return if (n == 1) "$num minute" else "$num minutes"
    }

    /** "24 minutes", "1 hour 5 minutes", "3 days". */
    fun duration(ms: Long): String {
        val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        val days = totalMin / (24 * 60)
        val hours = (totalMin % (24 * 60)) / 60
        val minutes = totalMin % 60
        fun unit(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"
        return when {
            days >= 2 -> unit(days, "day", "days")
            days == 1L -> if (hours > 0) "1 day ${unit(hours, "hour", "hours")}" else "1 day"
            hours > 0 && minutes > 0 -> "${unit(hours, "hour", "hours")} ${unit(minutes, "minute", "minutes")}"
            hours > 0 -> unit(hours, "hour", "hours")
            else -> unit(minutes, "minute", "minutes")
        }
    }

    fun toast(kind: ToastKind, label: String, minutes: Int): String = when (kind) {
        ToastKind.ONE_MINUTE_LEFT -> "One minute left on $label."
        ToastKind.TIME_OVER -> if (minutes > 0) "Time is up. $label can wait ${minutesWords(minutes)}." else "Time is up on $label."
        ToastKind.BLOCKED_FOR -> "Time is up. $label is blocked for ${minutesWords(minutes)}."
        ToastKind.SESSION_OVER -> "Focus session done. Notifications are back."
    }

    fun shortVideoToast(surface: String): String = "$surface is off. Quiet closed it."

    // ---- Mindful launch ----

    fun gateQuestion(label: String, askIntention: Boolean): String =
        if (askIntention) "Why are you opening $label?" else "A moment before $label."

    val intentions = listOf("Reply to someone", "Look something up", "Post something", "Pass the time", "Something else")

    /** A gentle line after the answer, or null. */
    fun intentionEcho(intention: String): String? = when (intention) {
        "Pass the time" -> "A short walk passes it too."
        "Reply to someone" -> "Reply, then come back here."
        "Look something up" -> "Find it, then close it."
        else -> null
    }

    fun gateNote(p: GateParams): String? = when {
        p.coolingDown -> "You closed it a little while ago, so the pause is longer."
        p.opensBefore >= 2 -> "You have opened it ${p.opensBefore} times today. Each pause gets longer."
        p.opensBefore == 1 -> "Second time today."
        else -> null
    }

    fun budgetLine(budgetLeftMs: Long?): String? =
        budgetLeftMs?.let { "${duration(it.coerceAtLeast(0))} left of today's limit." }

    fun breathWord(inhale: Boolean, reducedMotion: Boolean): String = when {
        reducedMotion -> "Breathe slowly"
        inhale -> "Breathe in"
        else -> "Breathe out"
    }

    // ---- Time's up ----

    fun timesUpQuestion(label: String, p: TimesUpParams): String = when (p.reason) {
        TimesUpReason.TIMER -> {
            val m = (p.grantedMs / 60_000).toInt().coerceAtLeast(1)
            "Your ${minutesWords(m)} on $label are up.".replace("one minute on $label are", "one minute on $label is")
        }
        TimesUpReason.BUDGET -> "That is today's ${duration(p.budgetMinutes * 60_000L)} on $label."
    }

    fun timesUpDetail(p: TimesUpParams): String {
        val today = if (p.usedTodayMs >= 60_000) "That makes ${duration(p.usedTodayMs)} there today." else null
        val set = when (p.reason) {
            TimesUpReason.TIMER -> "You set ${(p.grantedMs / 60_000).coerceAtLeast(1)} for this visit."
            TimesUpReason.BUDGET -> "Your daily limit is ${duration(p.budgetMinutes * 60_000L)}."
        }
        return listOfNotNull(today, set).joinToString(" ")
    }

    fun pauseNote(seconds: Int): String? = if (seconds > 0) "After a $seconds-second pause." else null

    // ---- Blocked ----

    fun blockedHeadline(label: String): String = "$label is blocked until"

    fun clock(ms: Long, zone: ZoneId, is24h: Boolean): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return if (is24h) "%02d:%02d".format(t.hour, t.minute)
        else "%d:%02d %s".format(((t.hour + 11) % 12) + 1, t.minute, if (t.hour < 12) "am" else "pm")
    }

    /** "on Saturday, 21 November" when the block ends on another day, else null. */
    fun blockedDate(untilMs: Long, nowMs: Long, zone: ZoneId): String? {
        val end = Instant.ofEpochMilli(untilMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (end == today) return null
        if (end == today.plusDays(1)) return "tomorrow"
        val pattern = if (Locale.getDefault().country == "US") "EEEE, MMMM d" else "EEEE, d MMMM"
        return "on " + end.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun fromNow(untilMs: Long, nowMs: Long): String = "${duration(untilMs - nowMs)} from now."

    /** Italic title and one plain sentence for the reason block. */
    fun blockedReason(info: BlockInfo, zone: ZoneId): Pair<String, String> = when (val r = info.reason) {
        is BlockReason.FromSchedule -> {
            val s = r.schedule
            val days = ScheduleMath.daysLabel(s.days).let { if (it == "Every day") "every day" else it.replaceFirstChar { c -> c.lowercase() } }
            val next = if (s.overnight) " the next day" else ""
            s.name to "Your schedule, $days ${ScheduleMath.hhmm(s.startMinute)} to ${ScheduleMath.hhmm(s.endMinute)}$next."
        }
        is BlockReason.Manual -> {
            val d = Instant.ofEpochMilli(r.createdMs).atZone(zone)
            val pattern = if (Locale.getDefault().country == "US") "MMMM d" else "d MMMM"
            "Your own block" to "You set it on ${d.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))}."
        }
        BlockReason.Timer -> "Time ran out" to "You chose to close it when your time is up."
        is BlockReason.Session -> "Focus session" to (r.intention.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "Only the apps you chose are open.")
    }
}
