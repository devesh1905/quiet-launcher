package app.quiet.launcher.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.quiet.launcher.container
import app.quiet.launcher.feature.lock.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps a screen on/off receiver alive so the custom lock screen is up before the screen lights. */
class LockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastLaunch = 0L
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> showLock(context)
                Intent.ACTION_SCREEN_ON -> {
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isKeyguardLocked == true) showLock(context)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scope.launch {
            applicationContext.container.settings.settings
                .map { it.lockEnabled }.distinctUntilChanged()
                .collect { enabled -> if (!enabled) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already gone.
            }
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Quiet lock screen is on")
            .setOngoing(true)
            .setSilent(true)
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
            // ForegroundServiceStartNotAllowedException (API 31+) or a missing permission.
            Log.w(TAG, "Cannot start foreground", e)
            false
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Lock screen", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun showLock(context: Context) {
        val container = context.applicationContext.container
        val s = container.settings.settings.value
        if (!s.lockEnabled) return
        if (s.lockOnlyWithMusic && container.nowPlaying.state.value == null) return
        // Never cover a ringing or active call: the system's call screen must be on top.
        if (app.quiet.launcher.core.platform.CallState.isCallActive(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 2000L) return
        lastLaunch = now
        val intent = Intent(context, LockActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch lock screen", e)
        }
    }

    companion object {
        private const val TAG = "LockService"
        private const val CHANNEL_ID = "lock_screen"
        private const val NOTIFICATION_ID = 4101

        /** Starts the service when the lock screen is enabled, stops it otherwise. */
        fun startIfEnabled(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, LockService::class.java)
            if (!app.container.settings.settings.value.lockEnabled) {
                app.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start lock service", e)
            }
        }
    }
}
