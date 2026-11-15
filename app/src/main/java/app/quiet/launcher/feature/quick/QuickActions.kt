package app.quiet.launcher.feature.quick

import android.app.NotificationManager
import android.content.Context
import app.quiet.launcher.container
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.monochrome.MonochromeController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The anti-screen shortcuts shared by the Focus page, the hold-the-clock sheet and the Quick
 * Settings tiles, so all three always do exactly the same thing.
 */
object QuickActions {
    private const val PREFS = "quick_actions"
    private const val KEY_ALLOWED = "focus_allowed"

    /** Apps chosen for the last focus session; quick starts reuse them. */
    fun lastAllowed(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_ALLOWED, emptySet()).orEmpty()

    fun rememberAllowed(ctx: Context, allowed: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_ALLOWED, allowed).apply()
    }

    private fun hasDndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun focusRunning(ctx: Context): Boolean =
        FocusRuntime.get(ctx).repo.snapshot.value.session?.let { it.endsMs > System.currentTimeMillis() } == true

    /** Start a focus session now with the last chosen apps, silencing notifications if allowed. */
    fun startFocus(ctx: Context, minutes: Int) {
        if (focusRunning(ctx)) return
        FocusRuntime.get(ctx).startSession(minutes, "", lastAllowed(ctx), hasDndAccess(ctx))
    }

    fun endFocusEarly(ctx: Context) = FocusRuntime.get(ctx).endSession(early = true)

    /** The apps Quiet treats as distracting: reminders on, or watched by default. */
    fun distractingApps(ctx: Context): Set<String> {
        val snap = FocusRuntime.get(ctx).repo.snapshot.value
        val withReminders = snap.rules.filterValues { it.reminder }.keys
        val turnedOff = snap.rules.filterValues { !it.reminder }.keys
        return (snap.defaultWatched - turnedOff) + withReminders
    }

    /** Block every distracting app for [minutes]. */
    fun pauseDistractions(ctx: Context, minutes: Int = 60) {
        val pkgs = distractingApps(ctx)
        if (pkgs.isEmpty()) return
        val repo = FocusRuntime.get(ctx).repo
        ctx.container.appScope.launch { repo.block(pkgs, System.currentTimeMillis() + minutes * 60_000L) }
    }

    suspend fun grayscaleUntil(ctx: Context): Long =
        MonochromeController.get(ctx).store.config.first().manualUntilMs

    /** Grayscale everywhere for [minutes], or off when [minutes] is 0. */
    fun setGrayscale(ctx: Context, minutes: Int) {
        val until = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        ctx.container.appScope.launch {
            MonochromeController.get(ctx).store.update { it.copy(manualUntilMs = until) }
        }
    }
}
