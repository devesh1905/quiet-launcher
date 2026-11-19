package app.quiet.launcher.feature.media

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.quiet.launcher.container
import app.quiet.launcher.feature.notifications.NotificationFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Exists so the app is granted notification access, which in turn allows
 * MediaSessionManager.getActiveSessions. It also remembers the large icon of each package's
 * media notification as a last-resort artwork source. Non-media cancelling is done by the notification filter (feature/notifications).
 */
class MediaListenerService : NotificationListenerService() {

    private fun repo(): NowPlayingRepositoryImpl? =
        runCatching { applicationContext.container.nowPlaying as? NowPlayingRepositoryImpl }.getOrNull()

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { capture(it) } }
        repo()?.recheck()
        runCatching { NotificationFilter.get(applicationContext).onConnected(this) }
    }

    override fun onListenerDisconnected() {
        repo()?.recheck()
        runCatching { NotificationFilter.get(applicationContext).onDisconnected() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (capture(sbn)) repo()?.onNotificationArt(sbn.packageName)
        // Media art is captured first; the filter never touches media-style notifications.
        runCatching { NotificationFilter.get(applicationContext).onPosted(sbn, this) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isMedia(sbn.notification)) MediaNotificationArt.remove(sbn.packageName)
        runCatching { NotificationFilter.get(applicationContext).onRemoved(sbn) }
    }

    private fun isMedia(n: Notification?): Boolean = n?.extras?.containsKey("android.mediaSession") == true

    /** Returns true if an icon was stored for the package. */
    private fun capture(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (!isMedia(n)) return false
        val bmp = runCatching {
            val drawn = n.getLargeIcon()?.loadDrawable(this)?.let { d ->
                if (d is BitmapDrawable && d.bitmap != null) d.bitmap
                else {
                    val w = d.intrinsicWidth.coerceAtLeast(1)
                    val h = d.intrinsicHeight.coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        d.setBounds(0, 0, w, h); d.draw(Canvas(b))
                    }
                }
            }
            @Suppress("DEPRECATION")
            val any = drawn ?: n.extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
                ?: n.extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            ArtworkLoader.prepare(any)
        }.getOrNull() ?: return false
        MediaNotificationArt.put(sbn.packageName, bmp)
        return true
    }
}

/** package -> large icon of its latest media notification. */
internal object MediaNotificationArt {
    private val map = ConcurrentHashMap<String, Bitmap>()
    fun put(pkg: String, b: Bitmap) { map[pkg] = b }
    fun get(pkg: String): Bitmap? = map[pkg]
    fun remove(pkg: String) { map.remove(pkg) }
}
