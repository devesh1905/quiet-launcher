package app.quiet.launcher.core.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.view.inputmethod.InputMethodManager
import app.quiet.launcher.core.data.usage.UsageAggregator
import app.quiet.launcher.core.data.usage.UsageEventReader
import app.quiet.launcher.core.model.AppUsage
import app.quiet.launcher.core.model.UsageToday
import app.quiet.launcher.core.model.WeekUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class UsageRepositoryImpl(
    context: Context,
    scope: CoroutineScope,
    private val settings: SettingsRepository,
) : UsageRepository {

    private val app = context.applicationContext
    private val mutex = Mutex()
    private val labels = HashMap<String, String>()
    private var weekComputedAt = 0L
    private var weekComputedFor: LocalDate? = null

    private val _hasPermission = MutableStateFlow(checkPermission())
    override val hasPermission: StateFlow<Boolean> = _hasPermission

    private val _today = MutableStateFlow(UsageToday.Empty.copy(budgetMs = budgetMs()))
    override val today: StateFlow<UsageToday> = _today

    private val _week = MutableStateFlow<WeekUsage?>(null)
    override val week: StateFlow<WeekUsage?> = _week

    init {
        // Keep the budget current without requiring a usage refresh.
        scope.launch {
            settings.settings.map { it.dailyBudgetMinutes * 60_000L }.distinctUntilChanged().collect { b ->
                _today.value = _today.value.copy(budgetMs = b)
            }
        }
    }

    private fun budgetMs() = settings.settings.value.dailyBudgetMinutes * 60_000L

    private fun checkPermission(): Boolean {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            app.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun refresh() {
        withContext(Dispatchers.Default) {
            if (mutex.tryLock()) {
                try { doRefresh() } finally { mutex.unlock() }
            } else {
                // Coalesce: another refresh is running; wait for it and reuse its result.
                mutex.withLock { }
            }
        }
    }

    private fun doRefresh() {
        val granted = checkPermission()
        _hasPermission.value = granted
        val budget = budgetMs()
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (!granted || usm == null) {
            _today.value = UsageToday.Empty.copy(budgetMs = budget)
            _week.value = null
            return
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = UsageAggregator.startOfDay(todayDate, zone)
        val ignored = ignoredPackages()

        val events = UsageEventReader.read(usm, dayStart - LOOKBACK_MS, now)
        val sessions = UsageAggregator.sessions(events, dayStart, now, ignored)
        _today.value = UsageToday(
            totalMs = UsageAggregator.totalMs(sessions),
            budgetMs = budget,
            pickups = UsageAggregator.pickups(events, dayStart, now),
            hourlyMinutes = UsageAggregator.hourlyMinutes(sessions, todayDate, zone),
            currentHourIndex = UsageAggregator.currentHourIndex(now, zone),
            perApp = UsageAggregator.perApp(sessions).map { (pkg, ms) -> AppUsage(pkg, labelFor(pkg), ms) },
        )

        // The 14-day query is heavier: redo it at most every 5 minutes, or when the date changes.
        if (_week.value == null || weekComputedFor != todayDate || now - weekComputedAt > WEEK_TTL_MS) {
            val weekStart = UsageAggregator.startOfDay(todayDate.minusDays(13), zone)
            val wEvents = UsageEventReader.read(usm, weekStart - LOOKBACK_MS, now)
            val wSessions = UsageAggregator.sessions(wEvents, weekStart, now, ignored)
            val totals = UsageAggregator.dailyTotals(wSessions, todayDate, zone, 14)
            val thisWeek = totals.copyOfRange(7, 14)
            val locale = Locale.getDefault()
            _week.value = WeekUsage(
                days = thisWeek,
                dayLabels = (6 downTo 0).map {
                    todayDate.minusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                },
                lastWeekTotalMs = totals.copyOfRange(0, 7).sum(),
                thisWeekTotalMs = thisWeek.sum(),
            )
            weekComputedAt = now
            weekComputedFor = todayDate
        }
    }

    private fun ignoredPackages(): Set<String> {
        val out = HashSet<String>()
        out.add(app.packageName)
        out.add("com.android.systemui")
        out.add("app.quiet.launcher")
        val pm = app.packageManager
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (def != null && def != "android") out.add(def)
            else pm.queryIntentActivities(home, 0).forEach { out.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        try {
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { out.add(it.packageName) }
        } catch (_: Exception) {
        }
        return out
    }

    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    private companion object {
        /** Extra history read before the window so a session already running at window start is clipped, not lost. */
        const val LOOKBACK_MS = 6 * 3_600_000L
        const val WEEK_TTL_MS = 5 * 60_000L
    }
}
