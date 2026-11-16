package app.quiet.launcher.feature.receipt

import android.app.usage.UsageStatsManager
import android.content.Context
import android.text.format.DateFormat
import app.quiet.launcher.container
import app.quiet.launcher.core.data.usage.UsageEventReader
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.feature.focus.data.FocusDatabase
import app.quiet.launcher.feature.streaks.Span
import app.quiet.launcher.feature.streaks.StreakMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything printed on one receipt. Minutes are whole minutes. */
data class Receipt(
    val number: Int,
    val day: LocalDate,
    val dayName: String,
    val dateLine: String,
    val printedAt: String,
    val apps: List<Pair<String, Int>>,
    val otherMin: Int,
    val hasUsage: Boolean,
    val pickups: Int,
    val firstPickup: String,
    val longestAwayMin: Int,
    val focusMin: Int,
    val focusSessions: Int,
    val totalMin: Int,
    val allowanceMin: Int,
) {
    val code: String get() = ReceiptMath.code(number, day)
}

object ReceiptLoader {
    /** Reads today's usage and focus log. Runs only when the receipt is opened, off the main thread. */
    suspend fun load(context: Context): Receipt = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        val c = app.container
        c.usage.refresh()
        val today = c.usage.today.value
        val granted = c.usage.hasPermission.value
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val is24 = DateFormat.is24HourFormat(app)

        val events = if (granted) {
            (app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)
                ?.let { UsageEventReader.read(it, dayStart, now) } ?: emptyList()
        } else emptyList()
        val first = ReceiptMath.firstPickup(events, dayStart, now)
        val away = ReceiptMath.longestAway(events, dayStart, now)

        val sessions = runCatching { FocusDatabase.get(app).dao().sessionsSince(dayStart) }.getOrDefault(emptyList())
        val spans = StreakMath.spansOn(sessions.map { Span(it.startMs, it.endMs) }, day, zone)
        val focusMin = (spans.sumOf { it.endMs - it.startMs } / 60_000L).toInt()

        val (top, other) = ReceiptMath.topApps(today.perApp.map { it.label to it.millis }, today.totalMs)
        val installDay = runCatching {
            val t = app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
            Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        }.getOrDefault(day)

        Receipt(
            number = ReceiptMath.receiptNumber(installDay, day),
            day = day,
            dayName = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
            dateLine = Format.longDate(now).replace(",", ""),
            printedAt = Format.clock(now, is24),
            apps = top,
            otherMin = other,
            hasUsage = granted,
            pickups = today.pickups,
            firstPickup = first?.let { Format.clock(it, is24) } ?: "-",
            longestAwayMin = (away / 60_000L).toInt(),
            focusMin = focusMin,
            focusSessions = spans.size,
            totalMin = ((today.totalMs + 30_000L) / 60_000L).toInt(),
            allowanceMin = (today.budgetMs / 60_000L).toInt(),
        )
    }
}
