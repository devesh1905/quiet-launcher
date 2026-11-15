package app.quiet.launcher.core.data.usage

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/** Thin Android adapter: UsageEvents -> RawEvent list. */
internal object UsageEventReader {
    @SuppressLint("InlinedApi")
    fun read(usm: UsageStatsManager, begin: Long, end: Long): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        try {
            val events = usm.queryEvents(begin, end) ?: return out
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val type = when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> RawType.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> RawType.PAUSED
                    UsageEvents.Event.SCREEN_INTERACTIVE -> RawType.SCREEN_ON
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> RawType.SCREEN_OFF
                    UsageEvents.Event.KEYGUARD_SHOWN -> RawType.KEYGUARD_SHOWN
                    UsageEvents.Event.KEYGUARD_HIDDEN -> RawType.KEYGUARD_HIDDEN
                    else -> continue
                }
                out.add(RawEvent(ev.timeStamp, ev.packageName ?: "", type, ev.className))
            }
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
        return out
    }
}
