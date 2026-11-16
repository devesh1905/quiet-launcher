package app.quiet.launcher.feature.gesture

import app.quiet.launcher.feature.gesture.HapticTime.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticTimeTest {
    private fun counts(p: HapticTime.Plan) = Kind.entries.associateWith { k -> p.pulses.count { it.kind == k } }

    @Test fun sevenOhFiveSkipsTheTens() {
        val p = HapticTime.build(7, 5)
        val c = counts(p)
        assertEquals(1, c[Kind.CONFIRM]); assertEquals(7, c[Kind.LONG]); assertEquals(0, c[Kind.SHORT]); assertEquals(5, c[Kind.TICK])
        // First long after the confirm click and its pause.
        assertEquals(538L, p.pulses[1].startMs)
        // One group gap between the hours and the ticks.
        val lastLong = p.pulses.last { it.kind == Kind.LONG }
        val firstTick = p.pulses.first { it.kind == Kind.TICK }
        assertEquals(lastLong.endMs + HapticTime.GROUP_GAP_MS, firstTick.startMs)
    }

    @Test fun noonIsTwelveLongPulses() {
        val p = HapticTime.build(12, 0)
        assertEquals(13, p.pulses.size)
        assertEquals(12, counts(p)[Kind.LONG])
        assertEquals(538L + 12 * 420 + 11 * 280 + 200, p.totalMs)
        assertEquals(12, HapticTime.build(0, 0).hours) // midnight plays as 12 too
    }

    @Test fun twentyOneThirtySeven() {
        val p = HapticTime.build(21, 37)
        val c = counts(p)
        assertEquals(9, c[Kind.LONG]); assertEquals(3, c[Kind.SHORT]); assertEquals(7, c[Kind.TICK])
        assertEquals(listOf(-1, 0, 1, 2), p.pulses.map { it.group }.distinct())
        // Waveform: off/on pairs that add up to the end of the last pulse.
        val t = p.timings()
        assertEquals(p.pulses.size * 2, t.size)
        assertEquals(0L, t[0]); assertEquals(18L, t[1])
        assertEquals(p.pulses.last().endMs, t.sum())
        assertEquals(p.pulses.last().endMs + 200, p.totalMs)
    }
}
