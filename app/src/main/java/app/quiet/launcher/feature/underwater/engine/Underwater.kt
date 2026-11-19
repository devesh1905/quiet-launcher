package app.quiet.launcher.feature.underwater.engine

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.quiet.launcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place that decides whether the water-and-glass look is on. Every underwater code path is
 * behind [Underwater.active] (or a non-null [LocalWater]), so the classic edition never runs any of it:
 * [BuildConfig.UNDERWATER] is a compile-time false there and R8 drops the branches.
 *
 *  - edition: only the `underwater` flavor ([BuildConfig.UNDERWATER]);
 *  - setting: Settings › Look and motion › Underwater (on by default; off = classic Quiet look);
 *  - platform: API 33+ (AGSL RenderEffect); below that the classic look shows, with the toggle hidden.
 */
object Underwater {
    /** The edition and the platform allow it (the toggle is shown). */
    val supported: Boolean get() = BuildConfig.UNDERWATER && Build.VERSION.SDK_INT >= 33

    /**
     * May this install run background work that touches the device (lock service, boot start, focus,
     * per-app grayscale, the receipt alarm, the daily art job)? Always in classic Quiet. Quiet Underwater
     * sits beside classic Quiet, so it only does this while it is the default home app.
     */
    fun ownsDevice(ctx: Context): Boolean {
        val c = ctx.applicationContext
        // Underwater runs device-wide work only while it is home; classic steps aside only while
        // the underwater edition is home, so the two never both show a lock screen or focus blocks.
        if (BuildConfig.UNDERWATER) return app.quiet.launcher.core.platform.Permissions.isDefaultLauncher(c)
        return currentHome(c) != UNDERWATER_PACKAGE
    }

    const val UNDERWATER_PACKAGE = "app.quiet.launcher.underwater"

    private fun currentHome(ctx: Context): String? {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    /** Live: the edition, the platform and the setting. */
    @Composable
    fun active(): Boolean {
        if (!supported) return false
        val ctx = LocalContext.current
        val on by remember { UnderwaterPrefs.get(ctx) }.on.collectAsState()
        return on
    }
}

/** Small prefs of its own (like GesturePrefs), so the shared settings store stays untouched. */
class UnderwaterPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("underwater", Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean("on", true))
    val on: StateFlow<Boolean> = _on

    fun setOn(v: Boolean) { prefs.edit().putBoolean("on", v).apply(); _on.value = v }

    companion object {
        @Volatile private var instance: UnderwaterPrefs? = null
        fun get(ctx: Context): UnderwaterPrefs = instance ?: synchronized(this) {
            instance ?: UnderwaterPrefs(ctx).also { instance = it }
        }
    }
}
