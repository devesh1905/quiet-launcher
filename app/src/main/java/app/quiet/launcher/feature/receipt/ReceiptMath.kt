package app.quiet.launcher.feature.receipt

import app.quiet.launcher.core.data.usage.RawEvent
import app.quiet.launcher.core.data.usage.RawType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure numbers behind the day receipt. */
object ReceiptMath {

    /** Receipt No. 1 is the day Quiet was installed; one a day after that, wrapping after 9999. */
    fun receiptNumber(installDay: LocalDate, day: LocalDate): Int {
        val n = ChronoUnit.DAYS.between(installDay, day).coerceAtLeast(0) + 1
        return ((n - 1) % 9999 + 1).toInt()
    }

    /** What the barcode carries: the receipt number (4 digits) and the date as ddMMyy. */
    fun code(number: Int, day: LocalDate): String =
        "%04d%02d%02d%02d".format(number, day.dayOfMonth, day.monthValue, day.year % 100)

    /** "0318 1411 26" for the line under the barcode. */
    fun spacedCode(code: String): String =
        if (code.length == 10) "${code.substring(0, 4)} ${code.substring(4, 8)} ${code.substring(8)}" else code

    /** Unlocks count as pickups; without any keyguard events, screen-on does (same rule as the usage screen). */
    private fun pickupType(events: List<RawEvent>) =
        if (events.any { it.type == RawType.KEYGUARD_HIDDEN }) RawType.KEYGUARD_HIDDEN else RawType.SCREEN_ON

    /** Time of the first pickup in the window, or null. */
    fun firstPickup(events: List<RawEvent>, from: Long, to: Long): Long? {
        val type = pickupType(events)
        return events.filter { it.type == type && it.timeMs in from..to }.minOfOrNull { it.timeMs }
    }

    /**
     * Longest stretch with the phone put down, between the first pickup and [to]: from the screen
     * going off (or the keyguard showing) to the next pickup. The night before the first pickup and
     * a stretch still running at [to] do not count.
     */
    fun longestAway(events: List<RawEvent>, from: Long, to: Long): Long {
        val first = firstPickup(events, from, to) ?: return 0L
        val type = pickupType(events)
        var downAt: Long? = null
        var best = 0L
        for (ev in events.sortedBy { it.timeMs }) {
            if (ev.timeMs < first || ev.timeMs > to) continue
            when (ev.type) {
                RawType.SCREEN_OFF, RawType.KEYGUARD_SHOWN -> if (downAt == null) downAt = ev.timeMs
                type -> {
                    val d = downAt
                    if (d != null) best = maxOf(best, ev.timeMs - d)
                    downAt = null
                }
                else -> Unit
            }
        }
        return best
    }

    /** "45 min", "2h 05m". */
    fun hm(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h %02dm".format(minutes % 60) else "$minutes min"

    /** "2h", "1h 30m", "45 min" for the allowance. */
    fun allowance(minutes: Int): String = when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "$minutes min"
    }

    /** "12 min under your 2h" / "1h 05m over your 2h". */
    fun allowanceLine(totalMin: Int, allowanceMin: Int): String {
        val diff = allowanceMin - totalMin
        return if (diff >= 0) "${hm(diff)} under your ${allowance(allowanceMin)}"
        else "${hm(-diff)} over your ${allowance(allowanceMin)}"
    }

    /**
     * Top [count] apps in whole minutes (rounded), and the minutes left for "Everything else".
     * Apps under one minute fall into the rest.
     */
    fun topApps(perAppMs: List<Pair<String, Long>>, totalMs: Long, count: Int = 6): Pair<List<Pair<String, Int>>, Int> {
        val top = perAppMs.sortedByDescending { it.second }
            .map { it.first to ((it.second + 30_000L) / 60_000L).toInt() }
            .filter { it.second >= 1 }
            .take(count)
        val total = ((totalMs + 30_000L) / 60_000L).toInt()
        return top to (total - top.sumOf { it.second }).coerceAtLeast(0)
    }
}
