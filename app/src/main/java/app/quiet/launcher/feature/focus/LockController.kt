package app.quiet.launcher.feature.focus

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import app.quiet.launcher.R

/** Double-tap to lock. Accessibility first (keeps biometric unlock), device admin as fallback. */
object LockController {

    /**
     * Locks the screen. Uses the precision-mode accessibility service when it is connected,
     * else DevicePolicyManager.lockNow when Quiet is a device admin, else returns false so the
     * caller can offer setup.
     */
    @JvmStatic
    fun lockNow(context: Context): Boolean {
        QuietAccessibilityService.instance?.let { if (it.lockScreen()) return true }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isAdminActive(adminComponent(context))) return false
        return try {
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            Log.w("LockController", "lockNow refused", e)
            false
        }
    }

    /** True when some way of locking is available right now. */
    @JvmStatic
    fun canLock(context: Context): Boolean =
        QuietAccessibilityService.instance != null || isAdminActive(context)

    fun adminComponent(context: Context) = ComponentName(context.applicationContext, QuietDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true

    /** System screen that asks the user to make Quiet a device admin (force-lock policy only). */
    fun adminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.focus_admin_explanation))

    /** Gives up device admin, so Quiet can be uninstalled normally again. */
    fun removeAdmin(context: Context) {
        try {
            context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(adminComponent(context))
        } catch (e: SecurityException) {
            Log.w("LockController", "removeActiveAdmin refused", e)
        }
    }
}

/** Device admin with the force-lock policy only (res/xml/device_admin_quiet.xml). */
class QuietDeviceAdminReceiver : DeviceAdminReceiver()
