package app.quiet.launcher.feature.focus.engine

import java.time.Instant
import kotlin.math.max

/**
 * The focus state machine (PLAN §5), one instance per process, driven from a single thread.
 *
 * ```
 * Idle -> (watched app in front) -> Gate [intention, launch delay, how long?]
 *      -> Running(visit timer) -> (1 min left: toast) -> TimeOver
 *      -> ONLY_REMIND: TimesUp { Leave -> Home | More time -> [extension delay] -> Running }
 *      -> EXIT_AND_BLOCK: Block(now + N min) and Home
 *      -> COOLDOWN: Home; reopening within N min needs a long pause
 * ```
 * The blocked check (manual block, timer block, active schedule, focus session) runs first on
 * every foreground change and every tick.
 *
 * Inputs are foreground changes, one-second ticks and the user's answers. Outputs are [Effect]s;
 * the engine never touches Android. Visit timers count foreground time only, so a visit
 * survives switching away and back; a gap longer than [VISIT_GAP_MS] ends the visit.
 */
class FocusEngine(private val clock: FocusClock, initial: EngineState = EngineState()) {

    var state: EngineState = initial
        private set

    /** Package in front as last reported (null = home, our own screens, screen off, unknown). */
    var foreground: String? = null
        private set

    private var lastAccrualMs = 0L
    private val lastShown = HashMap<String, Long>()
    /** Gate shown and not answered yet: re-showing it must not count another open. */
    private val gatePendingSince = HashMap<String, Long>()

    fun restore(saved: EngineState) {
        state = saved
    }

    // ---- Queries ----

    /** The block that ends last among all reasons that apply to [pkg] now, or null. */
    fun blockFor(pkg: String, snap: FocusSnapshot, nowMs: Long = clock.nowMs()): BlockInfo? {
        // Phone, payment, bank and security apps are never blocked: not by sessions, blocks or schedules.
        if (pkg in snap.alwaysAllowed) return null
        var best: BlockInfo? = null
        fun offer(b: BlockInfo) {
            val cur = best
            if (cur == null || b.untilMs > cur.untilMs) best = b
        }
        snap.session?.let { s ->
            if (nowMs < s.endsMs && pkg !in s.allowed && pkg !in snap.alwaysAllowed) {
                offer(BlockInfo(s.endsMs, BlockReason.Session(s.intention)))
            }
        }
        snap.blocks[pkg]?.let { b ->
            if (b.untilMs > nowMs) {
                offer(BlockInfo(b.untilMs, if (b.source == BlockSource.TIMER) BlockReason.Timer else BlockReason.Manual(b.createdMs)))
            }
        }
        for (s in snap.schedules) {
            if (pkg !in s.apps) continue
            val w = ScheduleMath.activeWindow(s, nowMs, clock.zone()) ?: continue
            offer(BlockInfo(w.endMs, BlockReason.FromSchedule(s)))
        }
        return best
    }

    fun budgetLeftMs(pkg: String, snap: FocusSnapshot): Long? {
        val budget = (snap.rules[pkg]?.budgetMinutes ?: 0) * MINUTE
        if (budget <= 0) return null
        val bonus = state.budgetBonusMs[pkg] ?: 0L
        return budget + bonus - (snap.usedTodayMs[pkg] ?: 0L)
    }

    /** Decision without side effects (besides forgetting a visit that has gone stale). */
    fun evaluateOpen(pkg: String, snap: FocusSnapshot, nowMs: Long = clock.nowMs()): OpenDecision {
        rollDay(nowMs)
        if (pkg in snap.alwaysAllowed) return OpenDecision.Proceed
        blockFor(pkg, snap, nowMs)?.let { return OpenDecision.Blocked(it) }
        if (!snap.config.reminderEnabled || !snap.isWatched(pkg)) return OpenDecision.Proceed
        dropStaleVisit(pkg, nowMs)
        val t = state.timers[pkg]
        if (t != null) {
            if (t.usedMs < t.grantedMs) return OpenDecision.Proceed
            val reason = if (t.budgetLimited) TimesUpReason.BUDGET else TimesUpReason.TIMER
            return OpenDecision.TimesUp(timesUpParams(pkg, snap, reason, t.grantedMs))
        }
        val left = budgetLeftMs(pkg, snap)
        if (left != null && left <= 0) {
            return OpenDecision.TimesUp(timesUpParams(pkg, snap, TimesUpReason.BUDGET, 0))
        }
        return OpenDecision.Gate(gateParams(pkg, snap, nowMs, left))
    }

    // ---- Inputs ----

    fun onForeground(pkg: String?, snap: FocusSnapshot): List<Effect> {
        val now = clock.nowMs()
        rollDay(now)
        accrue(now)
        val prev = foreground
        foreground = pkg
        val out = ArrayList<Effect>()
        if (prev != null && prev != pkg) {
            out += Effect.HideOverlay
            if (state.timers.containsKey(prev)) out += Effect.Persist
        }
        if (pkg == null) return out
        out += decide(pkg, snap, now)
        return out
    }

    /** Call once a second while something is in front. */
    fun onTick(snap: FocusSnapshot): List<Effect> {
        val now = clock.nowMs()
        rollDay(now)
        accrue(now)
        val out = ArrayList<Effect>()
        val session = snap.session
        if (session != null && now >= session.endsMs) out += once("end:${session.startedMs}", now, 60_000L) { listOf(Effect.EndSession) }
        val pkg = foreground ?: return out
        blockFor(pkg, snap, now)?.let { info ->
            out += Effect.HideOverlay
            out += once("b:$pkg", now) { listOf(Effect.ShowBlocked(pkg, info)) }
            return out
        }
        if (!snap.config.reminderEnabled || !snap.isWatched(pkg)) return out
        val t = state.timers[pkg] ?: return out
        val remaining = t.grantedMs - t.usedMs
        if (remaining <= 0) {
            out += timeOver(pkg, t, snap, now)
            return out
        }
        if (remaining <= MINUTE && !t.warned && t.grantedMs > MINUTE) {
            putTimer(t.copy(warned = true))
            out += Effect.Toast(ToastKind.ONE_MINUTE_LEFT, pkg)
            out += Effect.Persist
        }
        out += Effect.Overlay(pkg, remaining, t.grantedMs)
        return out
    }

    /**
     * The launcher asks before starting [pkg]. Counts an open when the gate is shown, and marks
     * the interstitial as shown so the tracker does not show it a second time.
     */
    fun openFromLauncher(pkg: String, snap: FocusSnapshot): OpenDecision {
        val now = clock.nowMs()
        val d = evaluateOpen(pkg, snap, now)
        when (d) {
            is OpenDecision.Gate -> {
                countOpen(pkg, now)
                lastShown["g:$pkg"] = now
            }
            is OpenDecision.Blocked -> lastShown["b:$pkg"] = now
            is OpenDecision.TimesUp -> lastShown["t:$pkg"] = now
            OpenDecision.Proceed -> Unit
        }
        return d
    }

    /** The user chose [minutes] in the gate. The visit is capped by the remaining daily budget. */
    fun grant(pkg: String, minutes: Int, snap: FocusSnapshot): List<Effect> {
        val now = clock.nowMs()
        rollDay(now)
        accrue(now)
        gatePendingSince.remove(pkg)
        var granted = minutes.coerceAtLeast(1) * MINUTE
        var limited = false
        val left = budgetLeftMs(pkg, snap)
        if (left != null && left < granted) {
            granted = max(left, MINUTE)
            limited = true
        }
        putTimer(AppTimer(pkg, granted, 0, now, budgetLimited = limited))
        return listOf(Effect.Persist)
    }

    /** "More time" after the extension delay. Budget extensions are remembered for today. */
    fun extend(pkg: String, minutes: Int, reason: TimesUpReason): List<Effect> {
        val now = clock.nowMs()
        rollDay(now)
        accrue(now)
        val extra = minutes.coerceAtLeast(1) * MINUTE
        if (reason == TimesUpReason.BUDGET) {
            state = state.copy(budgetBonusMs = state.budgetBonusMs + (pkg to ((state.budgetBonusMs[pkg] ?: 0L) + extra)))
        }
        val t = state.timers[pkg]
        putTimer(
            t?.copy(grantedMs = t.usedMs + extra, warned = false, budgetLimited = false, lastSeenMs = now)
                ?: AppTimer(pkg, extra, 0, now),
        )
        lastShown.remove("t:$pkg")
        return listOf(Effect.Persist)
    }

    /** "Close the app" on the time's-up screen. */
    fun leave(pkg: String): List<Effect> {
        gatePendingSince.remove(pkg)
        lastShown.remove("t:$pkg")
        state = state.copy(timers = state.timers - pkg)
        return listOf(Effect.HideOverlay, Effect.GoHome, Effect.Persist)
    }

    /** "Not now" on the gate. */
    fun decline(pkg: String): List<Effect> {
        gatePendingSince.remove(pkg)
        return listOf(Effect.GoHome)
    }

    fun timerFor(pkg: String): AppTimer? = state.timers[pkg]

    // ---- Internals ----

    private fun decide(pkg: String, snap: FocusSnapshot, now: Long): List<Effect> =
        when (val d = evaluateOpen(pkg, snap, now)) {
            OpenDecision.Proceed -> {
                val t = state.timers[pkg]
                if (t != null && snap.config.reminderEnabled && snap.isWatched(pkg)) {
                    listOf(Effect.Overlay(pkg, t.grantedMs - t.usedMs, t.grantedMs))
                } else emptyList()
            }
            is OpenDecision.Blocked -> once("b:$pkg", now) { listOf(Effect.ShowBlocked(pkg, d.info)) }
            is OpenDecision.Gate -> once("g:$pkg", now) {
                countOpen(pkg, now)
                listOf(Effect.ShowGate(pkg, d.params))
            }
            is OpenDecision.TimesUp -> once("t:$pkg", now) { listOf(Effect.ShowTimesUp(pkg, d.params)) }
        }

    private fun timeOver(pkg: String, t: AppTimer, snap: FocusSnapshot, now: Long): List<Effect> {
        val rule = snap.rules[pkg]
        val minutes = (rule?.blockMinutes ?: snap.config.blockMinutes).coerceAtLeast(1)
        return when (rule?.behaviour ?: snap.config.behaviour) {
            Behaviour.ONLY_REMIND -> once("t:$pkg", now, TIMES_UP_REPEAT_MS) {
                val reason = if (t.budgetLimited) TimesUpReason.BUDGET else TimesUpReason.TIMER
                listOf(Effect.HideOverlay, Effect.Toast(ToastKind.TIME_OVER, pkg), Effect.ShowTimesUp(pkg, timesUpParams(pkg, snap, reason, t.grantedMs)))
            }
            Behaviour.EXIT_AND_BLOCK -> {
                state = state.copy(timers = state.timers - pkg)
                listOf(
                    Effect.HideOverlay, Effect.AddBlock(pkg, now + minutes * MINUTE),
                    Effect.Toast(ToastKind.BLOCKED_FOR, pkg, minutes), Effect.GoHome, Effect.Persist,
                )
            }
            Behaviour.COOLDOWN -> {
                state = state.copy(
                    timers = state.timers - pkg,
                    cooldownUntil = state.cooldownUntil + (pkg to now + minutes * MINUTE),
                )
                listOf(Effect.HideOverlay, Effect.Toast(ToastKind.TIME_OVER, pkg, minutes), Effect.GoHome, Effect.Persist)
            }
        }
    }

    private fun gateParams(pkg: String, snap: FocusSnapshot, now: Long, budgetLeft: Long?): GateParams {
        val rule = snap.rules[pkg]
        // A gate shown again before it was answered was already counted: friction stays the same.
        val pending = gatePendingSince[pkg]?.let { now - it < GATE_PENDING_MS } == true
        val opens = ((state.opens[pkg] ?: 0) - if (pending) 1 else 0).coerceAtLeast(0)
        val cooling = (state.cooldownUntil[pkg] ?: 0L) > now
        val delayOn = rule?.launchDelay ?: snap.config.launchDelay
        var delay = when {
            !delayOn -> 0
            snap.config.progressiveFriction -> Friction.progressive(opens)
            else -> Friction.FLAT_SECONDS
        }
        val hour = Instant.ofEpochMilli(now).atZone(clock.zone()).hour
        if (delayOn && Friction.isLateNight(hour)) delay = max(delay, Friction.LATE_NIGHT_SECONDS)
        if (cooling) delay = max(delay, Friction.COOLDOWN_SECONDS)
        return GateParams(
            delaySeconds = delay,
            askIntention = rule?.intention ?: snap.config.intentionPrompt,
            opensBefore = opens,
            budgetLeftMs = budgetLeft,
            coolingDown = cooling,
        )
    }

    private fun timesUpParams(pkg: String, snap: FocusSnapshot, reason: TimesUpReason, grantedMs: Long): TimesUpParams {
        val rule = snap.rules[pkg]
        val ext = if (rule?.extensionDelay ?: snap.config.extensionDelay) Friction.EXTENSION_SECONDS else 0
        return TimesUpParams(
            reason = reason,
            grantedMs = grantedMs,
            usedTodayMs = snap.usedTodayMs[pkg] ?: 0L,
            // Budget overruns always cost a pause.
            extensionDelaySeconds = if (reason == TimesUpReason.BUDGET) max(ext, Friction.EXTENSION_SECONDS) else ext,
            budgetMinutes = rule?.budgetMinutes ?: 0,
        )
    }

    private fun countOpen(pkg: String, now: Long) {
        val pending = gatePendingSince[pkg]
        if (pending != null && now - pending < GATE_PENDING_MS) return
        gatePendingSince[pkg] = now
        state = state.copy(opens = state.opens + (pkg to (state.opens[pkg] ?: 0) + 1))
    }

    private fun once(key: String, now: Long, windowMs: Long = DEBOUNCE_MS, effects: () -> List<Effect>): List<Effect> {
        val last = lastShown[key]
        if (last != null && now - last < windowMs) return emptyList()
        lastShown[key] = now
        return effects()
    }

    private fun accrue(now: Long) {
        val fg = foreground
        if (fg != null && lastAccrualMs > 0) {
            val t = state.timers[fg]
            if (t != null) {
                // Cap so a frozen process does not charge minutes it never saw.
                val delta = (now - lastAccrualMs).coerceIn(0L, MAX_ACCRUAL_STEP_MS)
                putTimer(t.copy(usedMs = t.usedMs + delta, lastSeenMs = now))
            }
        }
        lastAccrualMs = now
    }

    private fun dropStaleVisit(pkg: String, now: Long) {
        // lastSeenMs advances every tick while the app is in front, so only an app that has
        // been away (or a grant that was never used) can go stale.
        val t = state.timers[pkg] ?: return
        if (now - t.lastSeenMs > VISIT_GAP_MS) state = state.copy(timers = state.timers - pkg)
    }

    private fun rollDay(now: Long) {
        val day = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate().toString()
        if (state.day != day) {
            state = state.copy(
                day = day, opens = emptyMap(), budgetBonusMs = emptyMap(),
                cooldownUntil = state.cooldownUntil.filterValues { it > now },
            )
        }
    }

    private fun putTimer(t: AppTimer) {
        state = state.copy(timers = state.timers + (t.pkg to t))
    }

    companion object {
        const val MINUTE = 60_000L
        const val VISIT_GAP_MS = 10 * MINUTE
        const val DEBOUNCE_MS = 2_000L
        const val TIMES_UP_REPEAT_MS = 10_000L
        const val GATE_PENDING_MS = 2 * MINUTE
        const val MAX_ACCRUAL_STEP_MS = 2 * MINUTE
    }
}
