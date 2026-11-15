package app.quiet.launcher.feature.monochrome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.quiet.launcher.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalTime

private const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
private const val KEY_MODE = "accessibility_display_daltonizer"
const val ADB_GRANT_COMMAND = "adb shell pm grant app.quiet.launcher android.permission.WRITE_SECURE_SETTINGS"

fun hasSecureSettingsPermission(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

/** DataStore "monochrome": user config plus the bookkeeping needed to restore the user's own display settings. */
class MonochromeStore(context: Context, scope: CoroutineScope) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.applicationContext.preferencesDataStoreFile("monochrome")
    }

    private object K {
        val selectedEnabled = booleanPreferencesKey("selectedEnabled")
        val packages = stringSetPreferencesKey("packages")
        val windDown = booleanPreferencesKey("windDown")
        val start = intPreferencesKey("windDownStart")
        val end = intPreferencesKey("windDownEnd")
        val weTurnedOn = booleanPreferencesKey("weTurnedOn")
        val prevEnabled = intPreferencesKey("prevEnabled")
        val prevMode = intPreferencesKey("prevMode")
    }

    private fun Preferences.toConfig(): MonochromeConfig {
        val d = MonochromeConfig()
        return MonochromeConfig(
            selectedEnabled = this[K.selectedEnabled] ?: d.selectedEnabled,
            selectedPackages = this[K.packages] ?: d.selectedPackages,
            windDownEnabled = this[K.windDown] ?: d.windDownEnabled,
            windDownStartMin = this[K.start] ?: d.windDownStartMin,
            windDownEndMin = this[K.end] ?: d.windDownEndMin,
        )
    }

    private val safeData: Flow<Preferences> =
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val config: Flow<MonochromeConfig> = safeData.map { it.toConfig() }

    suspend fun update(t: (MonochromeConfig) -> MonochromeConfig) {
        store.edit { p ->
            val n = t(p.toConfig())
            p[K.selectedEnabled] = n.selectedEnabled
            p[K.packages] = n.selectedPackages
            p[K.windDown] = n.windDownEnabled
            p[K.start] = n.windDownStartMin
            p[K.end] = n.windDownEndMin
        }
    }

    internal data class Saved(val weTurnedOn: Boolean, val prevEnabled: Int, val prevMode: Int)

    internal suspend fun saved(): Saved = safeData.first().let {
        Saved(it[K.weTurnedOn] ?: false, it[K.prevEnabled] ?: -1, it[K.prevMode] ?: -1)
    }

    internal suspend fun markOn(prevEnabled: Int, prevMode: Int) {
        store.edit { it[K.weTurnedOn] = true; it[K.prevEnabled] = prevEnabled; it[K.prevMode] = prevMode }
    }

    internal suspend fun clearOn() {
        store.edit { it[K.weTurnedOn] = false; it.remove(K.prevEnabled); it.remove(K.prevMode) }
    }
}

/**
 * Switches system grayscale (the colour-correction daltonizer, monochromacy mode) on and off following
 * foreground app and wind-down time. Needs WRITE_SECURE_SETTINGS (granted over adb).
 * On a crash or reboot the persisted "we turned it on" flag lets the first evaluation after start()
 * restore the user's own values if grayscale is no longer wanted.
 */
class MonochromeController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    val store = MonochromeStore(app, scope)
    private var started = false

    companion object {
        @Volatile private var instance: MonochromeController? = null

        /** Process-wide singleton (one DataStore per file). Uses the container's appScope. */
        fun get(context: Context): MonochromeController = instance ?: synchronized(this) {
            instance ?: MonochromeController(context, context.container.appScope).also { instance = it }
        }
    }

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        val minute: Flow<Int> = flow {
            while (true) {
                val t = LocalTime.now()
                emit(t.hour * 60 + t.minute)
                delay((60 - t.second) * 1000L)
            }
        }.distinctUntilChanged()
        scope.launch {
            combine(store.config, app.container.foreground.foreground, minute) { c, fg, now ->
                // Per-app grayscale needs the foreground app even when precision mode is off.
                (app.container.foreground as? app.quiet.launcher.feature.focus.ForegroundTrackerImpl)
                    ?.setNeeded("monochrome", c.selectedEnabled && c.selectedPackages.isNotEmpty())
                shouldBeMonochrome(c, fg, now)
            }.distinctUntilChanged().debounce(300).collect { apply(it) }
        }
    }

    private suspend fun apply(on: Boolean) {
        if (!hasSecureSettingsPermission(app)) return
        val cr = app.contentResolver
        try {
            val saved = store.saved()
            if (on) {
                if (!saved.weTurnedOn) {
                    store.markOn(
                        Settings.Secure.getInt(cr, KEY_ENABLED, -1),
                        Settings.Secure.getInt(cr, KEY_MODE, -1),
                    )
                }
                Settings.Secure.putInt(cr, KEY_MODE, 0)
                Settings.Secure.putInt(cr, KEY_ENABLED, 1)
            } else if (saved.weTurnedOn) {
                Settings.Secure.putInt(cr, KEY_ENABLED, if (saved.prevEnabled == 1) 1 else 0)
                if (saved.prevMode >= 0) Settings.Secure.putInt(cr, KEY_MODE, saved.prevMode)
                store.clearOn()
            }
        } catch (_: SecurityException) {
        }
    }
}
