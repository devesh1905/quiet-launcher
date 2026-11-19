package app.quiet.launcher.feature.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.quiet.launcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One notification shown as a bubble on the underwater Home. */
data class BubbleNote(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    internal val contentIntent: PendingIntent?,
    internal val autoCancel: Boolean,
    /** Injected over adb for testing: there is no real notification behind it. */
    internal val fake: Boolean = false,
)

/**
 * The live feed of new notifications for Quiet Underwater's Home bubbles. In memory only: it
 * starts empty when the process starts and is fed by the notification listener (posted / removed).
 * Only the underwater edition records anything; classic Quiet never fills it.
 *
 * Only what would show in the shade becomes a bubble: clearable, not ongoing, not media, not a
 * group summary, not Quiet's own. A notification the filter holds is cancelled at once and so
 * removes its bubble again (it stays quiet, as in classic).
 */
object NotificationBubbles {
    private const val MAX = 24
    private val _items = MutableStateFlow<List<BubbleNote>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<BubbleNote>> = _items.asStateFlow()

    @Volatile private var service: NotificationListenerService? = null

    internal fun attach(s: NotificationListenerService?) { service = s }

    internal fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (!BuildConfig.UNDERWATER) return
        val n = sbn.notification ?: return
        val flags = n.flags
        if (sbn.packageName == ctx.packageName) return
        if (!sbn.isClearable || flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val template = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (ex.containsKey("android.mediaSession") || template.endsWith("MediaStyle") || template.endsWith("CallStyle")) return
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val note = BubbleNote(
            key = sbn.key, packageName = sbn.packageName, appLabel = labelOf(ctx, sbn.packageName),
            title = title, text = text, postedAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
            contentIntent = n.contentIntent, autoCancel = flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
        put(note)
    }

    internal fun onRemoved(key: String) {
        _items.update { l -> if (l.none { it.key == key }) l else l.filterNot { it.key == key } }
    }

    private fun put(note: BubbleNote) {
        _items.update { l ->
            val old = l.firstOrNull { it.key == note.key }
            // An update to the same notification with the same words is not news: keep its place.
            if (old != null && old.title == note.title && old.text == note.text) l.map { if (it.key == note.key) note else it }
            else (listOf(note) + l.filterNot { it.key == note.key }).take(MAX)
        }
    }

    /** Opens what the notification points at (its content intent), then lets it go. */
    fun open(ctx: Context, note: BubbleNote): Boolean {
        val pi = note.contentIntent
        val ok = if (pi != null) runCatching {
            val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            pi.send(ctx, 0, null, null, null, null, opts); true
        }.getOrDefault(false) else false
        if (!ok) runCatching {
            ctx.packageManager.getLaunchIntentForPackage(note.packageName)?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        }
        if (note.autoCancel || note.fake) dismiss(note) else onRemoved(note.key)
        return ok
    }

    /** Clears the notification (from the shade too) and its bubble. */
    fun dismiss(note: BubbleNote) {
        onRemoved(note.key)
        if (!note.fake) runCatching { service?.cancelNotification(note.key) }
    }

    /** Lets the bubble go without touching the notification in the shade. */
    fun forget(note: BubbleNote) = onRemoved(note.key)

    /** For testing over adb (ShellCommandReceiver "bubble"): a bubble with nothing behind it. */
    fun inject(ctx: Context, pkg: String, title: String, text: String) {
        if (!BuildConfig.UNDERWATER) return
        val now = System.currentTimeMillis()
        put(BubbleNote("fake:$now", pkg, labelOf(ctx, pkg), title, text, now, null, true, fake = true))
    }

    @Suppress("DEPRECATION")
    private fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm: PackageManager = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
