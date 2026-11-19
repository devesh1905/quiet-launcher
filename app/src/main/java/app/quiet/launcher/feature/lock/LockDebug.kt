package app.quiet.launcher.feature.lock

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.quiet.launcher.core.model.ChargeAnim

/**
 * Lock screen test knobs, reached only through the DUMP-protected [app.quiet.launcher.service.ShellCommandReceiver]
 * (so only adb can use them). Nothing here is saved: the owner's settings are never touched, and a
 * restart of the app clears every override.
 *
 *   adb shell am broadcast -a app.quiet.launcher.SHELL -n <pkg>/app.quiet.launcher.service.ShellCommandReceiver --es cmd lock
 *       [--ez open true]          open the lock screen (as a preview when the phone is unlocked)
 *       [--es style FLOOD|GLASS|THREAD|CLOCK|OFF|none]   charging style for this session (none = the setting)
 *       [--es theme water|painting|none]                  Water theme on/off for this session (none = the setting)
 *       [--ez demo true]          run the two-finger show-off (the same call the gesture makes)
 *       [--ez still true|false]   reduced motion / battery saver (shared with the water engine's knob)
 *       [--ei rate -1..4]         charging text tier: -1 real, 0 slow, 1 charging, 2 fast, 3 rapid, 4 charged
 *       [--ez stay true|false]    keep the lock screen on (no 10 s idle sleep)
 */
object LockDebug {
    var chargeStyle by mutableStateOf<ChargeAnim?>(null)
    var water by mutableStateOf<Boolean?>(null)
    var rate by mutableStateOf(-1)
    var stay by mutableStateOf(false)
    /** The live lock screen's two-finger action, while one is composed. */
    internal var showOff: (() -> Boolean)? = null

    fun handle(ctx: Context, i: Intent) = Handler(Looper.getMainLooper()).post {
        i.getStringExtra("style")?.let { s -> chargeStyle = if (s == "none") null else runCatching { ChargeAnim.valueOf(s.uppercase()) }.getOrNull() }
        i.getStringExtra("theme")?.let { s -> water = when (s) { "water" -> true; "painting" -> false; else -> null } }
        if (i.hasExtra("still")) app.quiet.launcher.feature.underwater.engine.WaterDebug.forceStill = i.getBooleanExtra("still", false)
        if (i.hasExtra("rate")) rate = i.getIntExtra("rate", -1)
        if (i.hasExtra("stay")) stay = i.getBooleanExtra("stay", false)
        if (i.getBooleanExtra("open", false)) {
            ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (i.getBooleanExtra("demo", false)) {
            val r = showOff?.invoke()
            Log.i("QuietLock", "debug demo -> ${r ?: "no lock screen"}")
        }
        Log.i("QuietLock", "debug style=$chargeStyle water=$water rate=$rate stay=$stay still=${app.quiet.launcher.feature.underwater.engine.WaterDebug.forceStill}")
    }
}
