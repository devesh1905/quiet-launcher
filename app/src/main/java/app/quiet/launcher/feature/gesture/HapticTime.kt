package app.quiet.launcher.feature.gesture

/**
 * The time as vibration: one soft confirm click, then long pulses for the hour (12-hour clock), short
 * pulses for the tens of minutes and ticks for the single minutes. Groups are separated by a longer
 * pause and a group of zero is left out (07:05 plays 7 long and 5 ticks).
 */
object HapticTime {
    enum class Kind(val onMs: Long, val afterMs: Long, val ringMs: Long) {
        CONFIRM(18, 520, 650), LONG(420, 280, 1800), SHORT(140, 240, 1250), TICK(32, 210, 820),
    }

    const val GROUP_GAP_MS = 820L
    const val HOLD_MS = 480L

    data class Pulse(val kind: Kind, val startMs: Long, val group: Int) {
        val endMs get() = startMs + kind.onMs
    }

    data class Plan(val hours: Int, val tens: Int, val ones: Int, val pulses: List<Pulse>, val totalMs: Long) {
        /** Waveform timings: off, on, off, on ... starting at 0 (for VibrationEffect.createWaveform). */
        fun timings(): LongArray {
            val out = LongArray(pulses.size * 2)
            var t = 0L
            pulses.forEachIndexed { i, p ->
                out[i * 2] = p.startMs - t
                out[i * 2 + 1] = p.kind.onMs
                t = p.endMs
            }
            return out
        }
    }

    fun build(hour24: Int, minute: Int): Plan {
        val h = (hour24 % 12).let { if (it == 0) 12 else it }
        val groups = listOf(Kind.LONG to h, Kind.SHORT to minute / 10, Kind.TICK to minute % 10).filter { it.second > 0 }
        val pulses = arrayListOf(Pulse(Kind.CONFIRM, 0, -1))
        var t = Kind.CONFIRM.onMs + Kind.CONFIRM.afterMs
        groups.forEachIndexed { gi, (kind, n) ->
            repeat(n) { i ->
                pulses += Pulse(kind, t, gi)
                t += kind.onMs + if (i < n - 1) kind.afterMs else 0
            }
            if (gi < groups.size - 1) t += GROUP_GAP_MS
        }
        return Plan(h, minute / 10, minute % 10, pulses, t + 200)
    }
}
