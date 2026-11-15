package app.quiet.launcher.feature.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.quiet.launcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Context.notificationsStore: DataStore<Preferences> by preferencesDataStore("notifications")

private val KEY_ENABLED = booleanPreferencesKey("filter_enabled")
private val KEY_HIDE_ONGOING = booleanPreferencesKey("hide_ongoing")
private val KEY_ALLOW = stringSetPreferencesKey("allow_overrides")
private val KEY_DENY = stringSetPreferencesKey("deny_overrides")

/**
 * The notification filter. Process-wide singleton: [get]. The listener service calls
 * [onPosted], [onConnected] and [onDisconnected]; the UI uses [settings], [held] and the mutators.
 */
class NotificationFilter private constructor(context: Context) {
    private val ctx = context.applicationContext
    private val store = ctx.notificationsStore
    private val dao = NotificationsDatabase.get(ctx).held()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var listener: NotificationListenerService? = null

    val settings: Flow<FilterSettings> = store.data.map { p ->
        FilterSettings(
            enabled = p[KEY_ENABLED] ?: false,
            hideOngoing = p[KEY_HIDE_ONGOING] ?: false,
            allow = p[KEY_ALLOW] ?: emptySet(),
            deny = p[KEY_DENY] ?: emptySet(),
        )
    }

    val held: Flow<List<HeldNotification>> get() = dao.observeAll()
    val heldCount: Flow<Int> get() = dao.observeCount()

    /** Packages allowed by default on this phone: the static list plus the default dialer and SMS app. */
    fun defaultAllowed(): Set<String> {
        val extra = mutableSetOf<String>()
        runCatching { (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage }
            .getOrNull()?.let(extra::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()?.let(extra::add)
        extra += ctx.packageName
        return NotificationRules.defaultAllowed + extra
    }

    // Settings mutators

    suspend fun setEnabled(on: Boolean) {
        store.edit { it[KEY_ENABLED] = on }
        if (on) rescan() else cancelSummary()
    }

    suspend fun setHideOngoing(on: Boolean) {
        store.edit { it[KEY_HIDE_ONGOING] = on }
        if (on) rescan()
    }

    suspend fun setAllowed(pkg: String, allowed: Boolean) {
        val default = pkg in defaultAllowed()
        store.edit { p ->
            val allow = (p[KEY_ALLOW] ?: emptySet()) - pkg
            val deny = (p[KEY_DENY] ?: emptySet()) - pkg
            // Store an override only where the choice differs from the built-in default.
            p[KEY_ALLOW] = if (allowed && !default) allow + pkg else allow
            p[KEY_DENY] = if (!allowed && default) deny + pkg else deny
        }
        if (!allowed) rescan()
    }

    // Held list

    suspend fun remove(id: Long) {
        dao.delete(id)
        refreshSummary()
    }

    suspend fun clearAll() {
        dao.deleteAll()
        refreshSummary()
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        dao.deleteOlderThan(NotificationRules.pruneCutoff(now))
        refreshSummary()
    }

    // Listener hooks

    fun onConnected(service: NotificationListenerService) {
        listener = service
        schedulePrune(ctx)
        scope.launch {
            prune()
            scanActive(service)
        }
    }

    fun onDisconnected() { listener = null }

    fun onPosted(sbn: StatusBarNotification?, service: NotificationListenerService) {
        if (sbn == null) return
        val c = candidate(sbn) ?: return
        scope.launch { process(c, service) }
    }

    private fun rescan() {
        val service = listener ?: return
        scope.launch { scanActive(service) }
    }

    private suspend fun scanActive(service: NotificationListenerService) {
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        for (c in active.mapNotNull(::candidate)) process(c, service)
        refreshSummary()
    }

    private class Candidate(
        val key: String,
        val facts: NotificationFacts,
        val userSerial: Long,
        val extracted: NotificationRules.Extracted,
        val postedAt: Long,
    )

    private fun candidate(sbn: StatusBarNotification): Candidate? = try {
        val n = sbn.notification
        val ex = n.extras
        val flags = n.flags
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val facts = NotificationFacts(
            packageName = sbn.packageName,
            ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            noClear = flags and Notification.FLAG_NO_CLEAR != 0,
            clearable = sbn.isClearable,
            media = ex.containsKey("android.mediaSession") || template.endsWith("MediaStyle"),
            callStyle = template.endsWith("CallStyle"),
            category = n.category,
            groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
        val serial = (ctx.getSystemService(Context.USER_SERVICE) as? UserManager)
            ?.getSerialNumberForUser(sbn.user) ?: 0L
        val extracted = NotificationRules.extract(
            title = ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE),
            text = ex.getCharSequence(Notification.EXTRA_TEXT),
            bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            textLines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.filterNotNull(),
            subText = ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
            infoText = ex.getCharSequence(Notification.EXTRA_INFO_TEXT),
        )
        Candidate(sbn.key, facts, serial, extracted, if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis())
    } catch (_: Exception) {
        null
    }

    private suspend fun process(c: Candidate, service: NotificationListenerService) {
        val s = settings.first()
        when (NotificationRules.decide(c.facts, s, defaultAllowed(), ctx.packageName)) {
            Verdict.LEAVE -> Unit
            Verdict.SNOOZE -> runCatching { service.snoozeNotification(c.key, NotificationRules.SNOOZE_MS) }
            Verdict.HOLD -> {
                val e = c.extracted
                val empty = e.title.isEmpty() && e.text.isEmpty()
                // Group summaries only repeat their children, which arrive on their own.
                if (!c.facts.groupSummary && !empty && dao.countSame(c.key, e.title, e.text) == 0) {
                    dao.insert(
                        HeldNotification(
                            key = c.key, packageName = c.facts.packageName, userSerial = c.userSerial,
                            title = e.title, text = e.text, meta = e.meta, postedAt = c.postedAt,
                        ),
                    )
                    refreshSummary()
                }
                runCatching { service.cancelNotification(c.key) }
            }
        }
    }

    // Summary notification

    private suspend fun refreshSummary() {
        val s = settings.first()
        val count = dao.count()
        if (!s.enabled || count == 0) cancelSummary() else postSummary(count)
    }

    private fun cancelSummary() {
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(SUMMARY_ID) }
    }

    private fun postSummary(count: Int) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Held notifications", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "A quiet count of notifications Quiet is holding"
                        setShowBadge(false)
                    },
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, openIntent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(NotificationRules.summaryTitle(count))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
            nm.notify(SUMMARY_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "held_summary"
        private const val SUMMARY_ID = 7101

        @Volatile private var instance: NotificationFilter? = null

        fun get(context: Context): NotificationFilter = instance ?: synchronized(this) {
            instance ?: NotificationFilter(context).also { instance = it }
        }

        /**
         * Intent that opens the held list: MainActivity with extra quiet.open=notifications.
         * MainActivity must read it in onCreate and onNewIntent (it is singleTask).
         */
        fun openIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(NotificationRules.EXTRA_OPEN, NotificationRules.OPEN_NOTIFICATIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** True when [intent] asks for the held list. */
        fun wantsNotifications(intent: Intent?): Boolean =
            intent?.getStringExtra(NotificationRules.EXTRA_OPEN) == NotificationRules.OPEN_NOTIFICATIONS

        /** Remove the extra once handled so a configuration change does not reopen the screen. */
        fun consume(intent: Intent?) { intent?.removeExtra(NotificationRules.EXTRA_OPEN) }

        fun schedulePrune(context: Context) {
            val req = PeriodicWorkRequestBuilder<PruneWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork("held_notifications_prune", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Daily: drop held notifications older than seven days. */
class PruneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NotificationFilter.get(applicationContext).prune()
        return Result.success()
    }
}
