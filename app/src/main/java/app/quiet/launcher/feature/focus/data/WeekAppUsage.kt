package app.quiet.launcher.feature.focus.data

import android.app.usage.UsageStatsManager
import android.content.Context
import app.quiet.launcher.core.data.usage.UsageAggregator
import app.quiet.launcher.core.data.usage.UsageEventReader
import app.quiet.launcher.core.platform.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Per-app foreground time over the last 7 days (today included), for the reminder app list,
 * "Also block" suggestions and the block explainer. The shared usage repository only exposes
 * today's per-app split, so this reads events itself; results are cached for five minutes.
 */
class WeekAppUsage(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var cached: Map<String, Long> = emptyMap()
    private var cachedAt = 0L

    suspend fun load(force: Boolean = false): Map<String, Long> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && cachedAt > 0 && now - cachedAt < TTL_MS) return@withLock cached
            cached = compute(now)
            cachedAt = now
            cached
        }
    }

    private fun compute(now: Long): Map<String, Long> {
        if (!Permissions.hasUsageAccess(app)) return emptyMap()
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = UsageAggregator.startOfDay(today.minusDays(6), zone)
        val events = UsageEventReader.read(usm, start - LOOKBACK_MS, now)
        val sessions = UsageAggregator.sessions(events, start, now, setOf(app.packageName, "com.android.systemui"))
        return UsageAggregator.perApp(sessions).toMap()
    }

    private companion object {
        const val TTL_MS = 5 * 60_000L
        const val LOOKBACK_MS = 6 * 3_600_000L
    }
}
