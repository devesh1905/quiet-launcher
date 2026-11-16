package app.quiet.launcher.feature.focus

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import app.quiet.launcher.container
import app.quiet.launcher.feature.focus.content.ContentRules
import app.quiet.launcher.feature.focus.data.FocusRepository
import app.quiet.launcher.feature.focus.data.WeekAppUsage
import app.quiet.launcher.feature.focus.engine.BlockInfo
import app.quiet.launcher.feature.focus.engine.BlockSource
import app.quiet.launcher.feature.focus.engine.DefaultRules
import app.quiet.launcher.feature.focus.engine.Effect
import app.quiet.launcher.feature.focus.engine.FocusEngine
import app.quiet.launcher.feature.focus.engine.FocusSession
import app.quiet.launcher.feature.focus.engine.FocusSnapshot
import app.quiet.launcher.feature.focus.engine.GateParams
import app.quiet.launcher.feature.focus.engine.SystemFocusClock
import app.quiet.launcher.feature.focus.engine.TimesUpParams
import app.quiet.launcher.feature.focus.engine.TimesUpReason
import app.quiet.launcher.feature.focus.engine.ToastKind
import app.quiet.launcher.feature.focus.ui.BlockedActivity
import app.quiet.launcher.feature.focus.ui.FocusCopy
import app.quiet.launcher.feature.focus.ui.FocusSessionActivity
import app.quiet.launcher.feature.focus.ui.MindfulLaunchActivity
import app.quiet.launcher.feature.focus.ui.TimesUpActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide host of the focus engine. Owns the repository, feeds the engine with foreground
 * changes and one-second ticks, and turns its [Effect]s into activities, toasts, the floating
 * bar, DND changes and database writes. Everything touching the engine runs on the main thread.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
class FocusRuntime private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container = app.container

    val repo = FocusRepository(app, container.appScope, container.usage)
    val engine = FocusEngine(SystemFocusClock)
    val weekUsage = WeekAppUsage(app)
    private val tracker: ForegroundTrackerImpl? = container.foreground as? ForegroundTrackerImpl
    private val floatingBar = FloatingBar(app)

    private val _ready = MutableStateFlow(false)
    /** True once stored state is loaded and the engine restored. */
    val ready: StateFlow<Boolean> = _ready

    private val _contentRules = MutableStateFlow(ContentRules())
    /** Detection rules from assets/focus_rules.json (empty until read). */
    val contentRulesFlow: StateFlow<ContentRules> = _contentRules
    val contentRules: ContentRules get() = _contentRules.value

    /** Visits whose floating bar the user hid ("this visit"). */
    private val overlayHiddenFor = HashSet<String>()
    private var tickJob: Job? = null
    private var persistJob: Job? = null
    private var lastUsageRefresh = 0L
    private val labels = HashMap<String, String>()

    /** An app the launcher asked to open; started by the interstitial once the user proceeds. */
    private data class PendingLaunch(val pkg: String, val launch: (() -> Unit)?, val atMs: Long)
    private var pendingLaunch: PendingLaunch? = null

    val snapshot: FocusSnapshot get() = repo.snapshot.value

    init {
        scope.launch {
            // Restore the engine before it sees any input.
            val saved = runCatching { repo.readPrefsOnce() }.getOrNull()
            if (saved != null) engine.restore(saved.engine)
            repo.loaded.first { it }
            _ready.value = true
            repo.cleanUp()
            launch { observeForeground() }
            launch { observeNeeds() }
        }
        scope.launch(Dispatchers.IO) {
            _contentRules.value = loadRules()
        }
        container.appScope.launch { computeDefaults() }
    }

    // ---- Wiring ----

    private suspend fun observeForeground() {
        val t = tracker ?: return
        t.foreground.collect { pkg ->
            perform(engine.onForeground(pkg, snapshot))
            updateTicker(pkg)
        }
    }

    private suspend fun observeNeeds() {
        repo.snapshot.map { it.needsTracking(System.currentTimeMillis()) || it.shortVideo.enabled }
            .distinctUntilChanged()
            .collect { needed ->
                tracker?.setNeeded(needed)
                FocusService.sync(app, needed)
            }
    }

    private fun updateTicker(fg: String?) {
        val needTicks = fg != null || snapshot.session != null
        if (needTicks && tickJob?.isActive != true) {
            tickJob = scope.launch {
                var n = 0
                while (isActive) {
                    delay(1_000L)
                    val effects = engine.onTick(snapshot)
                    perform(effects)
                    if (++n % 15 == 0 && engine.foreground?.let { engine.timerFor(it) } != null) schedulePersist()
                    maybeRefreshUsage()
                    if (engine.foreground == null && snapshot.session == null) break
                }
            }
        }
    }

    private fun maybeRefreshUsage() {
        val fg = engine.foreground ?: return
        if ((snapshot.rules[fg]?.budgetMinutes ?: 0) <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastUsageRefresh < 60_000L) return
        lastUsageRefresh = now
        container.appScope.launch { runCatching { container.usage.refresh() } }
    }

    private fun schedulePersist() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch {
            delay(800L)
            val s = engine.state
            withContext(Dispatchers.IO) { runCatching { repo.saveEngine(s) } }
        }
    }

    private fun loadRules(): ContentRules = try {
        app.assets.open("focus_rules.json").bufferedReader().use { ContentRules.parse(it.readText()) }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read focus rules", e)
        ContentRules()
    }

    private suspend fun computeDefaults() {
        val pm = app.packageManager
        container.apps.apps.map { list -> list.map { it.key.packageName }.toSet() }.distinctUntilChanged().collect { pkgs ->
            val watched = withContext(Dispatchers.IO) {
                pkgs.filter { pkg ->
                    val category = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pm.getApplicationInfo(pkg, 0).category else null
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                    DefaultRules.isWatchedByDefault(pkg, category)
                }.toSet()
            }
            repo.defaultWatched.value = watched
            val safe = withContext(Dispatchers.IO) { pkgs.filter { PaymentGuard.isPayment(app, it) }.toSet() }
            repo.alwaysAllowed.value = alwaysAllowedPackages() + safe
        }
    }

    private fun alwaysAllowedPackages(): Set<String> {
        // Payment, bank and authenticator apps are added in computeDefaults; these are the system ones.
        val out = mutableSetOf(
            app.packageName, "com.android.systemui", "com.android.settings",
            "com.miui.securitycenter", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        )
        try {
            (app.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { out += it }
        } catch (_: SecurityException) {
        }
        return out
    }

    // ---- Effects ----

    fun perform(effects: List<Effect>) {
        for (e in effects) {
            try {
                execute(e)
            } catch (ex: Exception) {
                Log.w(TAG, "Effect failed: $e", ex)
            }
        }
    }

    private fun execute(e: Effect) {
        when (e) {
            is Effect.ShowGate -> showGate(e.pkg, e.params)
            is Effect.ShowTimesUp -> showTimesUp(e.pkg, e.params)
            is Effect.ShowBlocked -> showBlocked(e.pkg, e.info)
            is Effect.Toast -> toast(FocusCopy.toast(e.kind, labelFor(e.pkg), e.minutes))
            is Effect.AddBlock -> container.appScope.launch { repo.block(listOf(e.pkg), e.untilMs, BlockSource.TIMER) }
            Effect.GoHome -> goHome()
            is Effect.Overlay -> showOverlay(e)
            Effect.HideOverlay -> floatingBar.hide()
            Effect.EndSession -> endSession(early = false)
            Effect.Persist -> schedulePersist()
        }
    }

    private fun showOverlay(e: Effect.Overlay) {
        val p = repo.prefs.value
        if (!p.config.floatingBar || p.floatingHiddenAlways || e.pkg in overlayHiddenFor) {
            floatingBar.hide()
            return
        }
        floatingBar.show(
            remainingMs = e.remainingMs,
            grantedMs = e.grantedMs,
            onHideVisit = { overlayHiddenFor += e.pkg; floatingBar.hide() },
            onHideAlways = {
                floatingBar.hide()
                container.appScope.launch { repo.setFloatingHiddenAlways(true) }
            },
        )
    }

    fun showGate(pkg: String, params: GateParams, fromLauncher: Boolean = false) {
        start(MindfulLaunchActivity.intent(app, pkg, params, fromLauncher))
    }

    fun showTimesUp(pkg: String, params: TimesUpParams, fromLauncher: Boolean = false) {
        start(TimesUpActivity.intent(app, pkg, params, fromLauncher))
    }

    fun showBlocked(pkg: String, info: BlockInfo) {
        start(BlockedActivity.intent(app, pkg, info))
    }

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            // Background start refused (no overlay permission): fall back to a toast.
            Log.w(TAG, "Cannot show interstitial", e)
        }
    }

    fun toast(text: String) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }

    fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(home)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot go home", e)
        }
    }

    // ---- Answers from the interstitials ----

    fun rememberLaunch(pkg: String, launch: (() -> Unit)?) {
        pendingLaunch = PendingLaunch(pkg, launch, System.currentTimeMillis())
    }

    /** The user chose [minutes] for [pkg] in the mindful launch screen. */
    fun grant(pkg: String, minutes: Int, intention: String?) {
        overlayHiddenFor -= pkg
        perform(engine.grant(pkg, minutes, snapshot))
        if (!intention.isNullOrBlank()) container.appScope.launch { runCatching { repo.logIntention(pkg, intention, minutes) } }
        openApp(pkg)
    }

    fun extend(pkg: String, minutes: Int, reason: TimesUpReason) {
        perform(engine.extend(pkg, minutes, reason))
        openApp(pkg)
    }

    fun leave(pkg: String) {
        overlayHiddenFor -= pkg
        clearPending(pkg)
        perform(engine.leave(pkg))
    }

    fun decline(pkg: String) {
        clearPending(pkg)
        perform(engine.decline(pkg))
    }

    private fun clearPending(pkg: String) {
        if (pendingLaunch?.pkg == pkg) pendingLaunch = null
    }

    /** Bring [pkg] to the front: the launcher's own launch when it asked, else its launch intent. */
    private fun openApp(pkg: String) {
        val pending = pendingLaunch?.takeIf { it.pkg == pkg && System.currentTimeMillis() - it.atMs < 10 * 60_000L }
        pendingLaunch = null
        val custom = pending?.launch
        if (custom != null) {
            try {
                custom()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Launcher launch failed", e)
            }
        }
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return
        // Same flags a launcher uses: brings the existing task forward instead of restarting it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open $pkg", e)
        }
    }

    // ---- Focus session ----

    fun startSession(minutes: Int, intention: String, allowed: Set<String>, dnd: Boolean) {
        val now = System.currentTimeMillis()
        val session = FocusSession(now, now + minutes * 60_000L, intention.trim(), allowed, dnd)
        var previous = 0
        if (dnd) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.isNotificationPolicyAccessGranted) {
                try {
                    previous = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } catch (e: SecurityException) {
                    previous = 0
                }
            }
        }
        container.appScope.launch {
            repo.setSession(session, dndPrevious = previous)
            withContext(Dispatchers.Main) {
                updateTicker(engine.foreground)
                start(FocusSessionActivity.intent(app))
            }
        }
    }

    fun endSession(early: Boolean) {
        val session = snapshot.session ?: return
        val previous = repo.prefs.value.dndPrevious
        if (session.dnd && previous != 0) {
            val nm = app.getSystemService(NotificationManager::class.java)
            try {
                // Only undo our own change: leave DND alone if the user changed it meanwhile.
                if (nm != null && nm.isNotificationPolicyAccessGranted &&
                    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                ) nm.setInterruptionFilter(previous)
            } catch (_: SecurityException) {
            }
        }
        container.appScope.launch { repo.setSession(null, dndPrevious = 0) }
        if (!early) toast(FocusCopy.toast(ToastKind.SESSION_OVER, "", 0))
    }

    // ---- Helpers ----

    fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        container.apps.apps.value.firstOrNull { it.key.packageName == pkg }?.label ?: try {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        private const val TAG = "QuietFocus"

        @Volatile private var instance: FocusRuntime? = null

        fun get(context: Context): FocusRuntime = instance ?: synchronized(this) {
            instance ?: FocusRuntime(context.applicationContext).also { instance = it }
        }
    }
}
