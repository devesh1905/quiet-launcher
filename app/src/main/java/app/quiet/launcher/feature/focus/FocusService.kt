package app.quiet.launcher.feature.focus

import app.quiet.launcher.feature.underwater.engine.Underwater
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.quiet.launcher.R

/**
 * Keeps the process alive while the focus engine has work (watched apps, blocks, schedules, a
 * focus session). The engine itself lives in [FocusRuntime]; this service only holds the
 * foreground notification, so starting and stopping it is cheap.
 */
class FocusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // The other Quiet edition is home and runs its own focus service.
        if (!Underwater.ownsDevice(this)) {
            stopSelf()
            return
        }
        if (!enterForeground()) {
            stopSelf()
            return
        }
        FocusRuntime.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun enterForeground(): Boolean {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(getString(R.string.focus_notification_title))
            .setContentText(getString(R.string.focus_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start foreground", e)
            false
        }
    }

    companion object {
        private const val TAG = "FocusService"
        private const val CHANNEL_ID = "focus_engine"
        private const val NOTIFICATION_ID = 4102
        @Volatile private var running = false

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.focus_channel_name), NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.focus_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /**
         * With [needed] null (the call for QuietApp.onCreate and boot): create the focus runtime,
         * which then calls back with the real value whenever its rules change. With a value:
         * start the service when the engine needs it, stop it otherwise. A refused background
         * start (Android 12+) is logged and ignored; the next change retries.
         */
        fun sync(context: Context, needed: Boolean? = null) {
            val app = context.applicationContext
            if (needed == null) {
                FocusRuntime.get(app)
                return
            }
            val intent = Intent(app, FocusService::class.java)
            if (!needed || !Underwater.ownsDevice(app)) {
                app.stopService(intent)
                running = false
                return
            }
            try {
                ContextCompat.startForegroundService(app, intent)
                running = true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start focus service", e)
            }
        }
    }
}
