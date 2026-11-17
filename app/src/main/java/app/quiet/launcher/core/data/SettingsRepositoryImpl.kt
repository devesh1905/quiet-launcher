package app.quiet.launcher.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.quiet.launcher.core.model.LauncherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** DataStore-backed settings. One file ("settings"); every [LauncherSettings] field is persisted. */
class SettingsRepositoryImpl(context: Context, scope: CoroutineScope) : SettingsRepository {

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.applicationContext.preferencesDataStoreFile("settings")
    }

    override val settings: StateFlow<LauncherSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, LauncherSettings())

    override suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        // `edit` calls are serialized by DataStore, so reading the current value inside it is atomic.
        store.edit { prefs -> prefs.writeSettings(transform(prefs.toSettings())) }
    }
}

private object K {
    val theme = stringPreferencesKey("theme")
    val lockEnabled = booleanPreferencesKey("quietLockOverlay")
    val lockWallpaper = booleanPreferencesKey("lockWallpaper")
    val lockOnlyWithMusic = booleanPreferencesKey("lockOnlyWithMusic")
    val lockShowMusic = booleanPreferencesKey("lockShowMusic")
    val lockStyle = stringPreferencesKey("lockStyle")
    val lockFont = stringPreferencesKey("lockFont")
    val favouriteArtists = stringSetPreferencesKey("favouriteArtists")
    val dailyBudgetMinutes = intPreferencesKey("dailyBudgetMinutes")
    val showUsageOnHome = booleanPreferencesKey("showUsageOnHome")
    val showNowPlayingOnHome = booleanPreferencesKey("showNowPlayingOnHome")
    val homeGlow = booleanPreferencesKey("homeGlow")
    val ambientStrength = floatPreferencesKey("ambientStrength")
    val swipeUpSearch = booleanPreferencesKey("swipeUpSearch")
    val swipeDownNotifications = booleanPreferencesKey("swipeDownNotifications")
    val doubleTapLock = booleanPreferencesKey("doubleTapLock")
    val autoKeyboard = booleanPreferencesKey("autoKeyboard")
    val oneThingToday = stringPreferencesKey("oneThingToday")
    val cityName = stringPreferencesKey("cityName")
    val latitude = doublePreferencesKey("latitude")
    val longitude = doublePreferencesKey("longitude")
    val reducedMotion = booleanPreferencesKey("reducedMotion")
    val receiptEnabled = booleanPreferencesKey("receiptEnabled")
    val receiptMinute = intPreferencesKey("receiptMinute")
    val playerControl = stringPreferencesKey("playerControl")
}

/** Parses an enum by name, falling back to [default] for missing or unknown values. */
private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

internal fun Preferences.toSettings(): LauncherSettings {
    val d = LauncherSettings()
    return LauncherSettings(
        theme = this[K.theme].toEnumOr(d.theme),
        lockEnabled = this[K.lockEnabled] ?: d.lockEnabled,
        lockWallpaper = this[K.lockWallpaper] ?: d.lockWallpaper,
        lockOnlyWithMusic = this[K.lockOnlyWithMusic] ?: d.lockOnlyWithMusic,
        lockShowMusic = this[K.lockShowMusic] ?: d.lockShowMusic,
        lockStyle = this[K.lockStyle].toEnumOr(d.lockStyle),
        lockFont = this[K.lockFont].toEnumOr(d.lockFont),
        favouriteArtists = this[K.favouriteArtists] ?: d.favouriteArtists,
        dailyBudgetMinutes = this[K.dailyBudgetMinutes] ?: d.dailyBudgetMinutes,
        showUsageOnHome = this[K.showUsageOnHome] ?: d.showUsageOnHome,
        showNowPlayingOnHome = this[K.showNowPlayingOnHome] ?: d.showNowPlayingOnHome,
        homeGlow = this[K.homeGlow] ?: d.homeGlow,
        ambientStrength = this[K.ambientStrength] ?: d.ambientStrength,
        swipeUpSearch = this[K.swipeUpSearch] ?: d.swipeUpSearch,
        swipeDownNotifications = this[K.swipeDownNotifications] ?: d.swipeDownNotifications,
        doubleTapLock = this[K.doubleTapLock] ?: d.doubleTapLock,
        autoKeyboard = this[K.autoKeyboard] ?: d.autoKeyboard,
        oneThingToday = this[K.oneThingToday] ?: d.oneThingToday,
        cityName = this[K.cityName] ?: d.cityName,
        latitude = this[K.latitude] ?: d.latitude,
        longitude = this[K.longitude] ?: d.longitude,
        reducedMotion = this[K.reducedMotion] ?: d.reducedMotion,
        receiptEnabled = this[K.receiptEnabled] ?: d.receiptEnabled,
        receiptMinute = this[K.receiptMinute] ?: d.receiptMinute,
        playerControl = this[K.playerControl].toEnumOr(d.playerControl),
    )
}

internal fun MutablePreferences.writeSettings(s: LauncherSettings) {
    this[K.theme] = s.theme.name
    this[K.lockEnabled] = s.lockEnabled
    this[K.lockWallpaper] = s.lockWallpaper
    this[K.lockOnlyWithMusic] = s.lockOnlyWithMusic
    this[K.lockShowMusic] = s.lockShowMusic
    this[K.lockStyle] = s.lockStyle.name
    this[K.lockFont] = s.lockFont.name
    this[K.favouriteArtists] = s.favouriteArtists
    this[K.dailyBudgetMinutes] = s.dailyBudgetMinutes
    this[K.showUsageOnHome] = s.showUsageOnHome
    this[K.showNowPlayingOnHome] = s.showNowPlayingOnHome
    this[K.homeGlow] = s.homeGlow
    this[K.ambientStrength] = s.ambientStrength
    this[K.swipeUpSearch] = s.swipeUpSearch
    this[K.swipeDownNotifications] = s.swipeDownNotifications
    this[K.doubleTapLock] = s.doubleTapLock
    this[K.autoKeyboard] = s.autoKeyboard
    this[K.oneThingToday] = s.oneThingToday
    this[K.cityName] = s.cityName
    this[K.latitude] = s.latitude
    this[K.longitude] = s.longitude
    this[K.reducedMotion] = s.reducedMotion
    this[K.receiptEnabled] = s.receiptEnabled
    this[K.receiptMinute] = s.receiptMinute
    this[K.playerControl] = s.playerControl.name
}
