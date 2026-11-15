package app.quiet.launcher.feature.lock

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Quiet's lock screen sits above the system keyguard, so the keyguard's own short timeout does not
 * apply. While it is up we set the screen timeout to 10 s (what the system lock screen uses) and
 * put the user's value back on unlock. The saved value survives crashes and reboots: [restore] runs
 * on unlock, on app start and from the lock service, and is a no-op when nothing is pending.
 */
object LockTimeout {
    private const val PREFS = "lock_timeout"
    private const val KEY_SAVED = "saved_timeout"
    const val LOCK_TIMEOUT_MS = 10_000

    fun apply(ctx: Context) {
        if (!Settings.System.canWrite(ctx)) return
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val current = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
            if (!prefs.contains(KEY_SAVED) && current != LOCK_TIMEOUT_MS) {
                prefs.edit().putInt(KEY_SAVED, current).commit()
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w("Quiet", "Lock timeout apply failed", e)
        }
    }

    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) return
        val saved = prefs.getInt(KEY_SAVED, 600_000)
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved)
            }
            prefs.edit().remove(KEY_SAVED).commit()
        } catch (e: Exception) {
            Log.w("Quiet", "Lock timeout restore failed", e)
        }
    }
}
