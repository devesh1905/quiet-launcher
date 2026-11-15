package app.quiet.launcher.feature.notifications

/** Pure decision logic for the notification filter. No Android types, so it is unit tested on the JVM. */

/** User choices. [allow] and [deny] are explicit overrides on top of the built-in defaults. */
data class FilterSettings(
    val enabled: Boolean = false,
    val hideOngoing: Boolean = false,
    val allow: Set<String> = emptySet(),
    val deny: Set<String> = emptySet(),
) {
    fun isAllowed(pkg: String, defaults: Set<String>): Boolean =
        pkg in allow || (pkg !in deny && pkg in defaults)
}

/** What the filter needs to know about one posted notification. */
data class NotificationFacts(
    val packageName: String,
    val ongoing: Boolean,
    val foregroundService: Boolean,
    val noClear: Boolean,
    val clearable: Boolean,
    val media: Boolean,
    val callStyle: Boolean,
    val category: String?,
    val groupSummary: Boolean,
)

enum class Verdict { LEAVE, HOLD, SNOOZE }

object NotificationRules {
    const val EXTRA_OPEN = "quiet.open"
    const val OPEN_NOTIFICATIONS = "notifications"

    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    const val SNOOZE_MS = 24L * 60 * 60 * 1000

    /** Never filtered and never shown in the allow list. */
    val alwaysPassed = setOf("android", "com.android.systemui")

    /** Categories that must always reach the user. */
    val protectedCategories = setOf("call", "alarm", "navigation", "transport", "reminder", "event")

    /** Calls, messaging, calendar and clock apps that are allowed until the user says otherwise. */
    val defaultAllowed = setOf(
        "com.android.phone", "com.android.server.telecom", "com.android.incallui",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.providers.telephony", "com.android.mms", "com.android.messaging",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "com.android.calendar", "com.google.android.calendar", "com.samsung.android.calendar",
        "com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
        "com.miui.calendar", "com.android.settings",
    )

    fun decide(
        facts: NotificationFacts,
        settings: FilterSettings,
        defaults: Set<String>,
        ownPackage: String,
    ): Verdict {
        if (!settings.enabled) return Verdict.LEAVE
        val pkg = facts.packageName
        if (pkg == ownPackage || pkg in alwaysPassed) return Verdict.LEAVE
        if (settings.isAllowed(pkg, defaults)) return Verdict.LEAVE
        if (facts.media || facts.callStyle) return Verdict.LEAVE
        if (facts.category in protectedCategories) return Verdict.LEAVE
        val sticky = facts.ongoing || facts.foregroundService || facts.noClear || !facts.clearable
        if (sticky) return if (settings.hideOngoing) Verdict.SNOOZE else Verdict.LEAVE
        return Verdict.HOLD
    }

    data class Extracted(val title: String, val text: String, val meta: String)

    /**
     * Title from the title; body from bigText, else the text lines, else text; sub text and
     * info text are kept apart as [Extracted.meta]. A body that is empty falls back to meta.
     */
    fun extract(
        title: CharSequence?,
        text: CharSequence?,
        bigText: CharSequence?,
        textLines: List<CharSequence>?,
        subText: CharSequence?,
        infoText: CharSequence?,
    ): Extracted {
        fun clean(s: CharSequence?) = s?.toString()?.trim().orEmpty()
        val lines = textLines.orEmpty().map(::clean).filter { it.isNotEmpty() }.joinToString("\n")
        val body = listOf(clean(bigText), lines, clean(text)).firstOrNull { it.isNotEmpty() }.orEmpty()
        val meta = listOf(clean(subText), clean(infoText)).filter { it.isNotEmpty() }.distinct().joinToString("  ")
        val finalBody = body.ifEmpty { meta }
        val finalMeta = if (body.isEmpty()) "" else meta
        return Extracted(clean(title).take(300), finalBody.take(2000), finalMeta.take(300))
    }

    fun summaryTitle(count: Int): String = when {
        count > 99 -> "99+ notifications held"
        count == 1 -> "1 notification held"
        else -> "$count notifications held"
    }

    fun pruneCutoff(now: Long): Long = now - RETENTION_MS

    /** Short relative time for list rows. */
    fun relativeTime(now: Long, then: Long): String {
        val mins = ((now - then).coerceAtLeast(0) / 60_000L)
        return when {
            mins < 1 -> "Now"
            mins < 60 -> "$mins min"
            mins < 24 * 60 -> "${mins / 60} h"
            mins < 48 * 60 -> "Yesterday"
            else -> "${mins / (24 * 60)} days"
        }
    }
}
