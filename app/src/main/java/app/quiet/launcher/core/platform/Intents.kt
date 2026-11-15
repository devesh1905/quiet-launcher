package app.quiet.launcher.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log

object SystemActions {
    private fun start(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Quiet", "Cannot start $intent", e)
        }
    }

    fun camera(ctx: Context) = start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun dialer(ctx: Context) = start(ctx, Intent(Intent.ACTION_DIAL))
    fun alarms(ctx: Context) = start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    /** Google's search box with the keyboard up; falls back to any web search. */
    fun googleSearch(ctx: Context) {
        val google = "com.google.android.googlequicksearchbox"
        val tries = listOf(
            Intent("android.search.action.GLOBAL_SEARCH").setPackage(google),
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(google),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        for (i in tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) { }
        }
    }

    /** Pull down the notification shade (hidden API through reflection; needs EXPAND_STATUS_BAR). */
    @SuppressLint("WrongConstant")
    fun expandNotifications(ctx: Context): Boolean = try {
        val sbm = ctx.getSystemService("statusbar")
        sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
        true
    } catch (e: Exception) {
        Log.w("Quiet", "expandNotificationsPanel failed", e)
        false
    }
}
