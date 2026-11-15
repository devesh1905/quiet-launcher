package app.quiet.launcher.feature.focus.engine

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure focus-engine types. Nothing in this package touches Android, so the whole state machine
 * runs in plain JVM unit tests with a fake clock.
 */

/** What happens when the time the user chose has run out. */
enum class Behaviour {
    /** Show the time's-up screen; leaving or more time is the user's choice. */
    ONLY_REMIND,

    /** Close the app and block it for [AppRule.blockMinutes] / [FocusConfig.blockMinutes]. */
    EXIT_AND_BLOCK,

    /**
     * Close the app; reopening it during the cooldown window (same minutes setting) is allowed,
     * but only after a long pause ([Friction.COOLDOWN_SECONDS]) in the mindful launch screen.
     * Softer than a block, stronger than a reminder.
     */
    COOLDOWN,
}

/** Global settings of the in-app reminder. Per-app [AppRule] fields override these when non-null. */
data class FocusConfig(
    val reminderEnabled: Boolean = true,
    val floatingBar: Boolean = true,
    val launchDelay: Boolean = true,
    /** Launch delay grows with each open today (5, 10, 20 s) instead of a flat 15 s. */
    val progressiveFriction: Boolean = true,
    val extensionDelay: Boolean = true,
    val behaviour: Behaviour = Behaviour.ONLY_REMIND,
    /** Minutes for EXIT_AND_BLOCK and COOLDOWN. */
    val blockMinutes: Int = 15,
    val intentionPrompt: Boolean = true,
)

/** One app's reminder rule. Null fields follow [FocusConfig]. */
data class AppRule(
    val pkg: String,
    val reminder: Boolean,
    val behaviour: Behaviour? = null,
    val blockMinutes: Int? = null,
    val launchDelay: Boolean? = null,
    val extensionDelay: Boolean? = null,
    val intention: Boolean? = null,
    /** Daily budget across all visits; 0 = none. */
    val budgetMinutes: Int = 0,
)

enum class BlockSource { MANUAL, TIMER }

data class AppBlock(val pkg: String, val untilMs: Long, val source: BlockSource, val createdMs: Long)

/**
 * A named, repeating block window. [days] are the days the window STARTS on, so an overnight
 * 22:00 to 07:00 on Friday ends on Saturday morning. start == end means a full 24 hours.
 */
data class Schedule(
    val id: String,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek>,
    val apps: Set<String>,
    val enabled: Boolean = true,
    /** "Turn off until tomorrow": windows starting before this date are ignored. */
    val pausedUntil: LocalDate? = null,
    val createdMs: Long = 0,
) {
    val overnight: Boolean get() = endMinute <= startMinute
}

data class FocusSession(
    val startedMs: Long,
    val endsMs: Long,
    val intention: String,
    /** Apps the user may open during the session (besides the always-allowed system ones). */
    val allowed: Set<String>,
    val dnd: Boolean,
)

/** Everything the engine needs to decide, as one immutable value. */
data class FocusSnapshot(
    val config: FocusConfig = FocusConfig(),
    val rules: Map<String, AppRule> = emptyMap(),
    val blocks: Map<String, AppBlock> = emptyMap(),
    val schedules: List<Schedule> = emptyList(),
    val session: FocusSession? = null,
    /** Foreground time today per package (from usage stats; may lag about a minute). */
    val usedTodayMs: Map<String, Long> = emptyMap(),
    /** Packages watched when the user has not set a rule (social, video, games). */
    val defaultWatched: Set<String> = emptySet(),
    /** Never blocked by a focus session (dialer, our own app). */
    val alwaysAllowed: Set<String> = emptySet(),
    val shortVideo: ShortVideoConfig = ShortVideoConfig(),
) {
    fun isWatched(pkg: String): Boolean = rules[pkg]?.reminder ?: (pkg in defaultWatched)

    /** True when anything needs the foreground app to be known. */
    fun needsTracking(nowMs: Long): Boolean =
        session != null ||
            blocks.values.any { it.untilMs > nowMs } ||
            schedules.any { it.enabled && it.apps.isNotEmpty() } ||
            (config.reminderEnabled && (rules.values.any { it.reminder } || defaultWatched.isNotEmpty()))
}

data class ShortVideoConfig(val enabled: Boolean = false, val disabledRules: Set<String> = emptySet())

/** Why an app is blocked right now. */
sealed interface BlockReason {
    data class Manual(val createdMs: Long) : BlockReason
    data object Timer : BlockReason
    data class FromSchedule(val schedule: Schedule) : BlockReason
    data class Session(val intention: String) : BlockReason
}

data class BlockInfo(val untilMs: Long, val reason: BlockReason)

data class GateParams(
    val delaySeconds: Int,
    val askIntention: Boolean,
    /** Opens of this app today before this one. */
    val opensBefore: Int,
    /** Remaining daily budget, or null when the app has none. */
    val budgetLeftMs: Long?,
    val coolingDown: Boolean,
)

enum class TimesUpReason { TIMER, BUDGET }

data class TimesUpParams(
    val reason: TimesUpReason,
    val grantedMs: Long,
    val usedTodayMs: Long,
    val extensionDelaySeconds: Int,
    val budgetMinutes: Int,
)

/** Result of asking "may this app open right now?". */
sealed interface OpenDecision {
    data object Proceed : OpenDecision
    data class Blocked(val info: BlockInfo) : OpenDecision
    data class Gate(val params: GateParams) : OpenDecision
    data class TimesUp(val params: TimesUpParams) : OpenDecision
}

enum class ToastKind { ONE_MINUTE_LEFT, TIME_OVER, BLOCKED_FOR, SESSION_OVER }

/** Side effects the Android runtime executes. The engine never performs them itself. */
sealed interface Effect {
    data class ShowGate(val pkg: String, val params: GateParams) : Effect
    data class ShowTimesUp(val pkg: String, val params: TimesUpParams) : Effect
    data class ShowBlocked(val pkg: String, val info: BlockInfo) : Effect
    data class Toast(val kind: ToastKind, val pkg: String, val minutes: Int = 0) : Effect
    data class AddBlock(val pkg: String, val untilMs: Long) : Effect
    data object GoHome : Effect
    data class Overlay(val pkg: String, val remainingMs: Long, val grantedMs: Long) : Effect
    data object HideOverlay : Effect
    data object EndSession : Effect
    /** Engine state changed in a way worth persisting now. */
    data object Persist : Effect
}

interface FocusClock {
    fun nowMs(): Long
    fun zone(): ZoneId
}

object SystemFocusClock : FocusClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Visit timer: counts foreground time only, so switching away pauses it. */
@Serializable
data class AppTimer(
    val pkg: String,
    val grantedMs: Long,
    val usedMs: Long = 0,
    /** Last time the app was seen in front; a long gap ends the visit. */
    val lastSeenMs: Long,
    val warned: Boolean = false,
    val budgetLimited: Boolean = false,
)

@Serializable
data class EngineState(
    /** ISO date the per-day counters belong to. */
    val day: String = "",
    val timers: Map<String, AppTimer> = emptyMap(),
    val opens: Map<String, Int> = emptyMap(),
    val cooldownUntil: Map<String, Long> = emptyMap(),
    val budgetBonusMs: Map<String, Long> = emptyMap(),
)
