package app.quiet.launcher.feature.focus

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import app.quiet.launcher.container
import app.quiet.launcher.core.platform.AppCategories
import app.quiet.launcher.core.platform.AppCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Payment and bank apps (GPay, PhonePe, Paytm, BHIM, bank apps) refuse to work while any
 * non-Play accessibility service is on, and some only check again after a restart. So:
 *
 * - Before Quiet opens a payment app, precision mode is switched off in system settings (we hold
 *   WRITE_SECURE_SETTINGS for grayscale), the app is closed if it sits in the background so it
 *   starts fresh, and the launch waits a moment for the change to reach the system.
 * - If a payment app comes to the front some other way (a notification), the service switches
 *   itself off as soon as it sees it.
 * - Precision comes back once you have been away from payment apps for [RESTORE_AFTER_MS]
 *   (another app or Home, screen on and unlocked), or when Quiet starts after a reboot.
 *
 * Only a pause made here is undone here: if you switched precision off yourself, it stays off.
 */
object PaymentGuard {
    private const val TAG = "QuietPay"
    private const val PREFS = "payment_guard"
    private const val KEY_PAUSED = "paused"
    private const val OWNER = "payment_guard"
    private const val RESTORE_AFTER_MS = 45_000L
    /** Time for the accessibility change to reach the system before the payment app starts. */
    const val LAUNCH_DELAY_MS = 450L

    private val extra = setOf(
        "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android", "com.kotak811mobilebankingapp",
        "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.infrasofttech.indianBank", "com.canarabank.mobility",
        "com.unionbank.ecommerce.mobile.android", "com.pnb.mbanking", "com.idfcfirstbank.optimus", "com.yesbank",
        "com.amazon.mShop.android.shopping.pay", "money.super.payments", "com.whatsapp.payments",
    )

    private var watcher: Job? = null

    fun isPayment(ctx: Context, pkg: String): Boolean {
        if (pkg in extra) return true
        if (AppCategories.of(ctx, pkg) == AppCategory.MONEY) return true
        val p = pkg.lowercase()
        return "bank" in p || ".upi" in p || "upiapp" in p
    }

    private fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun component(ctx: Context) = ComponentName(ctx, QuietAccessibilityService::class.java).flattenToString()

    private fun enabledList(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }

    fun precisionOn(ctx: Context): Boolean {
        val me = component(ctx)
        return QuietAccessibilityService.instance != null || enabledList(ctx).any { it.equals(me, ignoreCase = true) }
    }

    /** Switch precision mode on or off in system settings. False without WRITE_SECURE_SETTINGS. */
    fun setPrecision(ctx: Context, on: Boolean): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            val me = component(ctx)
            val others = enabledList(ctx).filterNot { it.equals(me, ignoreCase = true) }
            val next = if (on) others + me else others
            val cr = ctx.contentResolver
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (next.isEmpty()) 0 else 1)
            if (on) prefs(ctx).edit().putBoolean(KEY_PAUSED, false).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not change precision mode", e); false
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaused(ctx: Context) = prefs(ctx).getBoolean(KEY_PAUSED, false)

    /** Pause precision for a payment app. True when it was on and is now off. */
    fun pause(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!precisionOn(app) || !setPrecision(app, false)) return false
        prefs(app).edit().putBoolean(KEY_PAUSED, true).apply()
        Log.i(TAG, "precision paused for a payment app")
        watch(app)
        return true
    }

    /**
     * Called before Quiet launches [pkg]. Returns true when the launch should wait
     * [LAUNCH_DELAY_MS] (precision was just switched off).
     */
    fun beforeLaunch(ctx: Context, pkg: String): Boolean {
        if (!isPayment(ctx, pkg)) return false
        val paused = pause(ctx)
        if (paused || isPaused(ctx)) {
            // A copy still running in the background may have checked while precision was on.
            try {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "could not close $pkg", e)
            }
        }
        return paused
    }

    /** Process start: finish a pause that was cut short (reboot, process death). */
    fun resume(ctx: Context) {
        if (isPaused(ctx)) watch(ctx.applicationContext)
    }

    private fun watch(app: Context) {
        if (watcher?.isActive == true) return
        val tracker = app.container.foreground as? ForegroundTrackerImpl ?: return
        watcher = app.container.appScope.launch {
            tracker.setNeeded(OWNER, true)
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            var awaySince = 0L
            try {
                while (isPaused(app)) {
                    delay(3_000)
                    val fg = tracker.foreground.value
                    val away = pm.isInteractive && !km.isKeyguardLocked && (fg == null || !isPayment(app, fg))
                    val now = System.currentTimeMillis()
                    awaySince = if (!away) 0L else if (awaySince == 0L) now else awaySince
                    if (awaySince != 0L && now - awaySince >= RESTORE_AFTER_MS) {
                        setPrecision(app, true)
                        Log.i(TAG, "precision restored")
                    }
                }
            } finally {
                tracker.setNeeded(OWNER, false)
            }
        }
    }
}
