package app.quiet.launcher.core.data

import app.quiet.launcher.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Contract between the data layer (implemented by agents) and the UI. Do not change signatures without the owner. */
interface AppsRepository {
    /** All launchable apps for all profiles, sorted by label (locale-aware), hidden ones included with hidden=true. */
    val apps: StateFlow<List<AppEntry>>
    /** Favourites in user order. */
    val favourites: Flow<List<AppEntry>>
    suspend fun refresh()
    fun launch(key: AppKey, sourceBounds: android.graphics.Rect? = null)
    fun openAppInfo(key: AppKey)
    fun uninstall(key: AppKey)
    suspend fun setFavourite(key: AppKey, favourite: Boolean)
    suspend fun moveFavourite(from: Int, to: Int)
    suspend fun setHidden(key: AppKey, hidden: Boolean)
    suspend fun rename(key: AppKey, label: String?)
    suspend fun setFolder(key: AppKey, folder: String?)
}

interface UsageRepository {
    val hasPermission: StateFlow<Boolean>
    /** Emits now and whenever [refresh] runs. */
    val today: StateFlow<UsageToday>
    val week: StateFlow<WeekUsage?>
    suspend fun refresh()
}

interface NowPlayingRepository {
    /** Null when nothing is playing or paused recently. */
    val state: StateFlow<NowPlaying?>
    val hasPermission: StateFlow<Boolean>
    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
}

interface SettingsRepository {
    val settings: StateFlow<LauncherSettings>
    suspend fun update(transform: (LauncherSettings) -> LauncherSettings)
}

interface ArtRepository {
    /** Today's artwork for the masterpiece style (cached file), or null until first fetch. */
    val today: StateFlow<Artwork?>
    /** Up to 7 cached works incl. today, newest first, plus user-provided works. */
    val library: StateFlow<List<Artwork>>
    suspend fun refreshIfStale()
    /** Show the next cached work now (used by the lock picker). */
    suspend fun skip()
    suspend fun addUserArtwork(uri: android.net.Uri, title: String, artist: String): Artwork?
}

data class BatteryState(val level: Float, val charging: Boolean)

interface DeviceStateRepository {
    val battery: StateFlow<BatteryState>
    /** Emits once a minute on TIME_TICK and on time/zone change, value = System.currentTimeMillis(). */
    val minuteTick: StateFlow<Long>
}
