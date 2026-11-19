package app.quiet.launcher.feature.lock.water

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.quiet.launcher.BuildConfig
import app.quiet.launcher.feature.underwater.engine.Underwater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "Water" lock theme (Quiet Underwater only). It is kept in a small prefs file of its own, so the
 * shared [app.quiet.launcher.core.model.LockStyle] (and every `when` over it in classic Quiet) stays
 * unchanged: choosing Water stores `on = true` here and the painting theme as the lock style, which is
 * also what shows wherever the water cannot (below API 33, underwater look off, the system wallpaper).
 */
class LockWaterPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lock_water", Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean("on", false))
    val on: StateFlow<Boolean> = _on

    fun setOn(v: Boolean) { if (v != _on.value) { prefs.edit().putBoolean("on", v).apply(); _on.value = v } }

    companion object {
        @Volatile private var instance: LockWaterPrefs? = null
        fun get(ctx: Context): LockWaterPrefs = instance ?: synchronized(this) {
            instance ?: LockWaterPrefs(ctx).also { instance = it }
        }
    }
}

/** Can the Water theme run here: the underwater edition, API 33+, and the underwater look switched on. */
@Composable
fun lockWaterAvailable(): Boolean = BuildConfig.UNDERWATER && Underwater.active()

/** Is the Water theme the chosen lock theme (and able to run)? */
@Composable
fun lockWaterChosen(): Boolean {
    if (!lockWaterAvailable()) return false
    val ctx = LocalContext.current
    val on by remember { LockWaterPrefs.get(ctx) }.on.collectAsState()
    return on
}
