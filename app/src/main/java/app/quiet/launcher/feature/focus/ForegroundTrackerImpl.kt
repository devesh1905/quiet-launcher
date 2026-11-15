package app.quiet.launcher.feature.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import app.quiet.launcher.core.data.ForegroundTracker
import app.quiet.launcher.core.platform.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Which app is in front.
 *
 * Two sources:
 * - **Usage events** (default): while the screen is interactive and something needs it
 *   ([setNeeded]), poll `UsageStatsManager.queryEvents` over the last 10 s every 800 ms and take
 *   the latest ACTIVITY_RESUMED. Polling stops on SCREEN_OFF and resumes on SCREEN_ON.
 * - **Accessibility** (precision mode): [QuietAccessibilityService] reports window changes
 *   through [onAccessibilityWindow]; while it is connected, [precise] is true and polling stops.
 *
 * Neutral packages (system UI, keyboards) keep the previous value, because they float over an
 * app without replacing it. Our own package and other home apps report null.
 */
class ForegroundTrackerImpl(context: Context, private val scope: CoroutineScope) : ForegroundTracker {
    private val app = context.applicationContext
    private val _foreground = MutableStateFlow<String?>(null)
    private val _precise = MutableStateFlow(false)
    override val foreground: StateFlow<String?> = _foreground
    override val precise: StateFlow<Boolean> = _precise

    @Volatile private var needed = false
    @Volatile private var interactive = true
    private var pollJob: Job? = null
    private var receiverRegistered = false

    private val neutral: Set<String> by lazy { computeNeutral() }
    private val homes: Set<String> by lazy { computeHomes() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    interactive = false
                    _foreground.value = null
                    updatePolling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    interactive = true
                    updatePolling()
                }
            }
        }
    }

    private val owners = HashSet<String>()

    /** Turn usage-event polling on or off for the focus engine (called whenever its rules change). */
    fun setNeeded(value: Boolean) = setNeeded(OWNER_FOCUS, value)

    /**
     * Polling runs while any owner needs it. Other consumers (monochrome, for example) register
     * under their own [owner] name so the focus engine switching off does not stop them.
     */
    fun setNeeded(owner: String, value: Boolean) {
        ensureReceiver()
        synchronized(owners) {
            if (value) owners += owner else owners -= owner
            val any = owners.isNotEmpty()
            if (needed == any) return
            needed = any
        }
        updatePolling()
    }

    /** Called by the accessibility service when it connects or disconnects. */
    fun setAccessibilityConnected(connected: Boolean) {
        ensureReceiver()
        _precise.value = connected
        updatePolling()
    }

    /**
     * A TYPE_WINDOW_STATE_CHANGED from the accessibility service. [isActivity] is false for
     * dialogs, toasts and popups that do not replace the app underneath.
     */
    fun onAccessibilityWindow(pkg: String?, isActivity: Boolean) {
        if (pkg == null || !isActivity || !interactive) return
        publish(pkg)
    }

    /** True for packages that never count as "the app in front" (system UI, keyboards). */
    fun isNeutral(pkg: String): Boolean = pkg in neutral

    private fun publish(pkg: String) {
        when {
            pkg in neutral -> Unit
            pkg == app.packageName || pkg in homes -> _foreground.value = null
            else -> _foreground.value = pkg
        }
    }

    @Synchronized
    private fun ensureReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        interactive = pm?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            // Process-lifetime receiver on the application context; never unregistered.
            ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    @Synchronized
    private fun updatePolling() {
        val shouldPoll = needed && interactive && !_precise.value
        if (shouldPoll && pollJob?.isActive != true) {
            pollJob = scope.launch(Dispatchers.Default) { pollLoop() }
        } else if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private suspend fun pollLoop() {
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val event = UsageEvents.Event()
        // The first read looks further back: the user may have been in the app for a while.
        var window = FIRST_WINDOW_MS
        while (true) {
            if (Permissions.hasUsageAccess(app)) {
                val now = System.currentTimeMillis()
                latestResumed(usm, now - window, now, event)?.let(::publish)
                window = WINDOW_MS
            }
            delay(POLL_MS) // Also the cancellation point.
        }
    }

    private fun latestResumed(usm: UsageStatsManager, from: Long, to: Long, ev: UsageEvents.Event): String? {
        var latest: String? = null
        try {
            val events = usm.queryEvents(from, to) ?: return null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = ev.packageName ?: continue
                // A keyboard or system UI resuming does not replace the app under it.
                if (pkg !in neutral) latest = pkg
            }
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        return latest
    }

    private fun computeNeutral(): Set<String> {
        val out = HashSet<String>()
        out += "com.android.systemui"
        out += "android"
        out += "com.google.android.permissioncontroller"
        out += "com.android.permissioncontroller"
        try {
            val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { out += it.packageName }
        } catch (_: Exception) {
        }
        return out
    }

    private fun computeHomes(): Set<String> {
        val out = HashSet<String>()
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            app.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {
        }
        // Settings sometimes registers a fallback home; it must stay a normal app.
        out -= "com.android.settings"
        out -= "android"
        return out
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val FIRST_WINDOW_MS = 30 * 60_000L
        const val OWNER_FOCUS = "focus"
        const val POLL_MS = 800L
    }
}
