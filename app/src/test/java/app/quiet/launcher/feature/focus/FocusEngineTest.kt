package app.quiet.launcher.feature.focus

import app.quiet.launcher.feature.focus.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class FakeClock(var now: Long, private val zone: ZoneId = ZoneId.of("UTC")) : FocusClock {
    override fun nowMs(): Long = now
    override fun zone(): ZoneId = zone
}

class FocusEngineTest {
    private val ig = "com.instagram.android"
    private val notes = "com.example.notes"
    private val noon = ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val clock = FakeClock(noon)
    private val engine = FocusEngine(clock)
    private var snap = FocusSnapshot(defaultWatched = setOf(ig), alwaysAllowed = setOf("com.android.dialer"))

    private fun advance(ms: Long, step: Long = 1_000L): List<Effect> {
        val out = ArrayList<Effect>()
        var left = ms
        while (left > 0) {
            val d = minOf(step, left)
            clock.now += d
            left -= d
            out += engine.onTick(snap)
        }
        return out
    }

    private inline fun <reified T : Effect> List<Effect>.only(): List<T> = filterIsInstance<T>()

    @Test
    fun unwatchedAppProceedsWithoutEffects() {
        val fx = engine.onForeground(notes, snap)
        assertTrue(fx.isEmpty())
        assertEquals(OpenDecision.Proceed, engine.evaluateOpen(notes, snap))
    }

    @Test
    fun firstOpenShowsGateWithFiveSecondPause() {
        val gate = engine.onForeground(ig, snap).only<Effect.ShowGate>().single()
        assertEquals(5, gate.params.delaySeconds)
        assertEquals(0, gate.params.opensBefore)
        assertTrue(gate.params.askIntention)
    }

    @Test
    fun frictionGrowsWithOpensToday() {
        val delays = (0 until 4).map {
            val g = engine.onForeground(ig, snap).only<Effect.ShowGate>().single()
            engine.decline(ig)
            engine.onForeground(null, snap)
            clock.now += 3 * 60_000L // past the pending-gate window, so each is a new open
            g.params.delaySeconds
        }
        assertEquals(listOf(5, 10, 20, 20), delays)
    }

    @Test
    fun flatPauseWhenProgressiveOffAndNoneWhenDelayOff() {
        snap = snap.copy(config = FocusConfig(progressiveFriction = false))
        assertEquals(15, engine.onForeground(ig, snap).only<Effect.ShowGate>().single().params.delaySeconds)
        engine.onForeground(null, snap)
        clock.now += 3_000
        snap = snap.copy(config = FocusConfig(launchDelay = false))
        assertEquals(0, engine.onForeground(ig, snap).only<Effect.ShowGate>().single().params.delaySeconds)
    }

    @Test
    fun lateNightPauseIsAtLeastTwentySeconds() {
        clock.now = ZonedDateTime.of(2026, 11, 14, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(20, engine.onForeground(ig, snap).only<Effect.ShowGate>().single().params.delaySeconds)
    }

    @Test
    fun perAppRuleOverridesDefaultsAndGlobal() {
        snap = snap.copy(rules = mapOf(ig to AppRule(ig, reminder = false), notes to AppRule(notes, reminder = true, launchDelay = false, intention = false)))
        assertTrue(engine.onForeground(ig, snap).only<Effect.ShowGate>().isEmpty())
        val g = engine.onForeground(notes, snap).only<Effect.ShowGate>().single()
        assertEquals(0, g.params.delaySeconds)
        assertFalse(g.params.askIntention)
    }

    @Test
    fun gateIsDebouncedAndCountsOneOpen() {
        assertEquals(1, engine.onForeground(ig, snap).only<Effect.ShowGate>().size)
        engine.onForeground(null, snap)
        clock.now += 500
        assertTrue(engine.onForeground(ig, snap).only<Effect.ShowGate>().isEmpty())
        clock.now += 5_000
        engine.onForeground(null, snap)
        // Shown again (still unanswered), but not counted as another open.
        assertEquals(0, engine.onForeground(ig, snap).only<Effect.ShowGate>().single().params.opensBefore)
        assertEquals(1, engine.state.opens[ig])
    }

    @Test
    fun runningVisitWarnsAtOneMinuteAndRemindsWhenOver() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 10, snap)
        val fg = engine.onForeground(ig, snap)
        assertTrue(fg.only<Effect.Overlay>().isNotEmpty())
        val first = advance(9 * 60_000L)
        assertEquals(1, first.only<Effect.Toast>().count { it.kind == ToastKind.ONE_MINUTE_LEFT })
        assertTrue(first.only<Effect.ShowTimesUp>().isEmpty())
        val over = advance(61_000L)
        val up = over.only<Effect.ShowTimesUp>().single()
        assertEquals(TimesUpReason.TIMER, up.params.reason)
        assertEquals(10 * 60_000L, up.params.grantedMs)
        assertEquals(15, up.params.extensionDelaySeconds)
    }

    @Test
    fun visitTimerPausesWhileAwayAndSurvivesReturn() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 10, snap)
        engine.onForeground(ig, snap)
        advance(4 * 60_000L)
        engine.onForeground(notes, snap)
        advance(5 * 60_000L)
        val back = engine.onForeground(ig, snap)
        assertTrue(back.only<Effect.ShowGate>().isEmpty())
        val t = engine.timerFor(ig)
        assertNotNull(t)
        assertTrue(t!!.usedMs in (4 * 60_000L - 2_000)..(4 * 60_000L + 2_000))
    }

    @Test
    fun longGapEndsTheVisit() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 10, snap)
        engine.onForeground(ig, snap)
        advance(60_000L)
        engine.onForeground(null, snap)
        clock.now += 11 * 60_000L
        assertEquals(1, engine.onForeground(ig, snap).only<Effect.ShowGate>().size)
    }

    @Test
    fun exitAndBlockAddsBlockAndGoesHome() {
        snap = snap.copy(config = FocusConfig(behaviour = Behaviour.EXIT_AND_BLOCK, blockMinutes = 30))
        engine.onForeground(ig, snap)
        engine.grant(ig, 1, snap)
        engine.onForeground(ig, snap)
        val fx = advance(61_000L)
        val block = fx.only<Effect.AddBlock>().single()
        assertTrue(block.untilMs - clock.now in (29 * 60_000L)..(30 * 60_000L))
        assertTrue(fx.contains(Effect.GoHome))
        assertEquals(30, fx.only<Effect.Toast>().single { it.kind == ToastKind.BLOCKED_FOR }.minutes)
        assertNull(engine.timerFor(ig))
    }

    @Test
    fun cooldownMakesTheNextPauseLong() {
        snap = snap.copy(config = FocusConfig(behaviour = Behaviour.COOLDOWN, blockMinutes = 20))
        engine.onForeground(ig, snap)
        engine.grant(ig, 1, snap)
        engine.onForeground(ig, snap)
        assertTrue(advance(61_000L).contains(Effect.GoHome))
        engine.onForeground(null, snap)
        clock.now += 60_000L
        val g = engine.onForeground(ig, snap).only<Effect.ShowGate>().single()
        assertTrue(g.params.coolingDown)
        assertEquals(Friction.COOLDOWN_SECONDS, g.params.delaySeconds)
    }

    @Test
    fun moreTimeExtendsTheVisit() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 1, snap)
        engine.onForeground(ig, snap)
        advance(61_000L)
        engine.onForeground(null, snap)
        engine.extend(ig, 5, TimesUpReason.TIMER)
        assertTrue(engine.onForeground(ig, snap).only<Effect.ShowTimesUp>().isEmpty())
        assertTrue(advance(4 * 60_000L).only<Effect.ShowTimesUp>().isEmpty())
        assertEquals(1, advance(65_000L).only<Effect.ShowTimesUp>().size)
    }

    @Test
    fun leaveClearsTheVisit() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 5, snap)
        val fx = engine.leave(ig)
        assertTrue(fx.contains(Effect.GoHome))
        assertNull(engine.timerFor(ig))
    }

    @Test
    fun manualBlockWinsOverReminder() {
        snap = snap.copy(blocks = mapOf(ig to AppBlock(ig, noon + 3_600_000L, BlockSource.MANUAL, noon - 1)))
        val b = engine.onForeground(ig, snap).only<Effect.ShowBlocked>().single()
        assertEquals(noon + 3_600_000L, b.info.untilMs)
        assertTrue(b.info.reason is BlockReason.Manual)
    }

    @Test
    fun expiredBlockIsIgnored() {
        snap = snap.copy(blocks = mapOf(notes to AppBlock(notes, noon - 1, BlockSource.MANUAL, noon - 10)))
        assertTrue(engine.onForeground(notes, snap).isEmpty())
    }

    @Test
    fun activeScheduleBlocksAndLatestEndWins() {
        val all = DayOfWeek.entries.toSet()
        val s1 = Schedule("a", "Afternoon focus", 11 * 60, 13 * 60, all, setOf(notes))
        val s2 = Schedule("b", "Long one", 10 * 60, 21 * 60, all, setOf(notes))
        snap = snap.copy(schedules = listOf(s1, s2))
        val b = engine.onForeground(notes, snap).only<Effect.ShowBlocked>().single()
        val reason = b.info.reason as BlockReason.FromSchedule
        assertEquals("Long one", reason.schedule.name)
    }

    @Test
    fun scheduleStartingWhileInsideTheAppBlocksOnTick() {
        val s = Schedule("a", "Lunch", 12 * 60 + 1, 13 * 60, DayOfWeek.entries.toSet(), setOf(notes))
        snap = snap.copy(schedules = listOf(s))
        assertTrue(engine.onForeground(notes, snap).isEmpty())
        val fx = advance(61_000L)
        assertEquals(1, fx.only<Effect.ShowBlocked>().size)
    }

    @Test
    fun focusSessionBlocksOthersAndEnds() {
        val session = FocusSession(noon, noon + 25 * 60_000L, "Write", allowed = setOf(notes), dnd = false)
        snap = snap.copy(session = session)
        val other = "com.reddit.frontpage"
        assertTrue(engine.onForeground(other, snap).only<Effect.ShowBlocked>().single().info.reason is BlockReason.Session)
        assertTrue(engine.onForeground(notes, snap).only<Effect.ShowBlocked>().isEmpty())
        assertTrue(engine.onForeground("com.android.dialer", snap).only<Effect.ShowBlocked>().isEmpty())
        val fx = advance(25 * 60_000L)
        assertEquals(1, fx.count { it == Effect.EndSession })
    }

    @Test
    fun spentBudgetShowsTimesUpInsteadOfGate() {
        snap = snap.copy(rules = mapOf(ig to AppRule(ig, reminder = true, budgetMinutes = 30)), usedTodayMs = mapOf(ig to 31 * 60_000L))
        val up = engine.onForeground(ig, snap).only<Effect.ShowTimesUp>().single()
        assertEquals(TimesUpReason.BUDGET, up.params.reason)
        assertTrue(up.params.extensionDelaySeconds >= Friction.EXTENSION_SECONDS)
    }

    @Test
    fun grantIsCappedByBudgetAndExtensionCountsAsBonus() {
        snap = snap.copy(rules = mapOf(ig to AppRule(ig, reminder = true, budgetMinutes = 30)), usedTodayMs = mapOf(ig to 25 * 60_000L))
        val g = engine.onForeground(ig, snap).only<Effect.ShowGate>().single()
        assertEquals(5 * 60_000L, g.params.budgetLeftMs)
        engine.grant(ig, 15, snap)
        val t = engine.timerFor(ig)!!
        assertEquals(5 * 60_000L, t.grantedMs)
        assertTrue(t.budgetLimited)
        engine.extend(ig, 10, TimesUpReason.BUDGET)
        assertEquals(10 * 60_000L, engine.state.budgetBonusMs[ig])
        assertEquals(15 * 60_000L, engine.budgetLeftMs(ig, snap))
    }

    @Test
    fun opensResetOnANewDay() {
        engine.onForeground(ig, snap)
        assertEquals(1, engine.state.opens[ig])
        engine.onForeground(null, snap)
        clock.now += 24 * 3_600_000L
        val g = engine.onForeground(ig, snap).only<Effect.ShowGate>().single()
        assertEquals(0, g.params.opensBefore)
    }

    @Test
    fun launcherPathCountsOpenAndSuppressesDuplicateGate() {
        val d = engine.openFromLauncher(ig, snap)
        assertTrue(d is OpenDecision.Gate)
        assertEquals(1, engine.state.opens[ig])
        assertTrue(engine.onForeground(ig, snap).only<Effect.ShowGate>().isEmpty())
    }

    @Test
    fun stateRoundTripsThroughRestore() {
        engine.onForeground(ig, snap)
        engine.grant(ig, 10, snap)
        val restored = FocusEngine(clock, engine.state)
        assertNotNull(restored.timerFor(ig))
        assertTrue(restored.onForeground(ig, snap).only<Effect.ShowGate>().isEmpty())
    }

    @Test
    fun needsTrackingFollowsRules() {
        assertFalse(FocusSnapshot().needsTracking(noon))
        assertTrue(snap.needsTracking(noon))
        assertFalse(snap.copy(config = FocusConfig(reminderEnabled = false)).needsTracking(noon))
        assertTrue(
            FocusSnapshot(blocks = mapOf(notes to AppBlock(notes, noon + 1, BlockSource.MANUAL, 0))).needsTracking(noon),
        )
    }
}
