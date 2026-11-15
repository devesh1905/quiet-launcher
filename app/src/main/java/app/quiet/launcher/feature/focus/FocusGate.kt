package app.quiet.launcher.feature.focus

import android.content.Context
import android.os.Looper
import app.quiet.launcher.feature.focus.engine.OpenDecision

/**
 * In-launcher interception. The launcher calls [beforeLaunch] before starting an app, on the
 * main thread:
 *
 * ```
 * if (FocusGate.beforeLaunch(context, key.packageName) { repo.launch(key, bounds) }) repo.launch(key, bounds)
 * ```
 *
 * Returns true when the app may open now. Returns false when the gate is showing an
 * interstitial (mindful launch, time's up, or blocked); for the first two it starts the app
 * itself once the user proceeds, through [launch] when given (keeps the exact activity, work
 * profile and source bounds) or the package's launch intent otherwise.
 */
object FocusGate {
    @JvmStatic
    @JvmOverloads
    fun beforeLaunch(context: Context, packageName: String, launch: (() -> Unit)? = null): Boolean {
        // The engine is single-threaded; off the main thread, never block a launch.
        if (Looper.myLooper() != Looper.getMainLooper()) return true
        val rt = FocusRuntime.get(context)
        if (!rt.ready.value) return true
        return when (val d = rt.engine.openFromLauncher(packageName, rt.snapshot)) {
            OpenDecision.Proceed -> true
            is OpenDecision.Blocked -> {
                rt.showBlocked(packageName, d.info)
                false
            }
            is OpenDecision.Gate -> {
                rt.rememberLaunch(packageName, launch)
                rt.showGate(packageName, d.params, fromLauncher = true)
                false
            }
            is OpenDecision.TimesUp -> {
                rt.rememberLaunch(packageName, launch)
                rt.showTimesUp(packageName, d.params, fromLauncher = true)
                false
            }
        }
    }

    /** True when [packageName] is blocked right now (for dimming it in lists, for example). */
    @JvmStatic
    fun isBlocked(context: Context, packageName: String): Boolean {
        val rt = FocusRuntime.get(context)
        return rt.engine.blockFor(packageName, rt.snapshot) != null
    }
}
