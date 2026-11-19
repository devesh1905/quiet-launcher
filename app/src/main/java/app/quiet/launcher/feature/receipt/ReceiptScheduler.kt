package app.quiet.launcher.feature.receipt

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The nightly receipt notification. One inexact, non-wakeup alarm a day (AlarmManager.set with RTC):
 * it fires the next time the phone is awake after the chosen minute, so it never wakes the device.
 * The receiver only posts a notification; usage is read when the receipt is opened.
 */
object ReceiptScheduler {
    private const val PREFS = "receipt_alarm"
    private const val CHANNEL = "day_receipt"
    private const val NOTIFICATION_ID = 2200

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(ctx: Context): Int =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Next local time at [minute] strictly after [now]. */
    fun nextAt(now: LocalDateTime, minute: Int): LocalDateTime {
        val today = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong())
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Settings changed: remember them and arm (or cancel) the alarm. */
    fun update(ctx: Context, enabled: Boolean, minute: Int) {
        prefs(ctx).edit().putBoolean("enabled", enabled).putInt("minute", minute).putLong("armedFor", 0L).apply()
        if (enabled) arm(ctx, minute) else cancel(ctx)
    }

    /**
     * Cheap check: arms the alarm only if it is not already armed in this boot. Called off the
     * main thread; a SharedPreferences read and at most one AlarmManager call.
     */
    fun ensure(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", true)) return
        val armedFor = p.getLong("armedFor", 0L)
        if (armedFor > System.currentTimeMillis() && p.getInt("boot", -2) == bootCount(ctx)) return
        arm(ctx, p.getInt("minute", 22 * 60))
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReceiptAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(ctx: Context, minute: Int) {
        val zone = ZoneId.systemDefault()
        val at = nextAt(LocalDateTime.now(zone), minute).atZone(zone).toInstant().toEpochMilli()
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.set(AlarmManager.RTC, at, pending(ctx)) }
        prefs(ctx).edit().putLong("armedFor", at).putInt("boot", bootCount(ctx)).apply()
    }

    private fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
    }

    /** Alarm fired: post the notification if it is still that evening, then arm tomorrow's. */
    internal fun onAlarm(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", true)) return
        val minute = p.getInt("minute", 22 * 60)
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val due = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong())
        // A non-wakeup alarm can arrive late (the phone was asleep). After midnight it is no longer "your day".
        if (!now.isBefore(due)) notify(ctx)
        arm(ctx, minute)
    }

    private fun notify(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Day receipt", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One quiet notification in the evening with the day's receipt"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 1, ReceiptActivity.intent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Your day, on paper")
            .setContentText("Tap to print today's receipt.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}

class ReceiptAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Quiet Underwater beside classic Quiet: no receipt unless it is the home app.
        if (!app.quiet.launcher.feature.underwater.engine.Underwater.ownsDevice(context)) return
        ReceiptScheduler.onAlarm(context.applicationContext)
    }
}
