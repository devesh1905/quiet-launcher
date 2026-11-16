package app.quiet.launcher.core.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.quiet.launcher.core.data.db.FavouriteEntity
import app.quiet.launcher.core.data.db.QuietDatabase
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.AppKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "AppsRepository"
private const val OWN_PACKAGE = "app.quiet.launcher"
private const val REFRESH_DEBOUNCE_MS = 300L

/** One row of the cold-start cache (apps_cache.json). */
@Serializable
private data class CachedApp(val id: String, val label: String, val installTime: Long = 0L)

@Serializable
private data class AppsCache(val apps: List<CachedApp> = emptyList())

@OptIn(FlowPreview::class)
class AppsRepositoryImpl(context: Context, private val scope: CoroutineScope) : AppsRepository {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val db = QuietDatabase.get(appContext)
    private val metaDao = db.appMetaDao()
    private val favDao = db.favouriteDao()

    private val cacheFile = File(appContext.filesDir, "apps_cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ownSerial = userManager.getSerialNumberForUser(Process.myUserHandle())

    /** Latest system list; null until the cache or the first system query has produced something. */
    private val raw = MutableStateFlow<List<RawApp>?>(null)
    private val loadMutex = Mutex()
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val apps: StateFlow<List<AppEntry>> = combine(raw.filterNotNull(), metaDao.observeAll()) { list, meta ->
        mergeApps(list, meta)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val favouriteRows: Flow<List<FavouriteEntity>> = favDao.observeAll()

    override val favourites: Flow<List<AppEntry>> = combine(favouriteRows, apps) { rows, list ->
        mergeFavourites(rows.map { it.id }, list)
    }.flowOn(Dispatchers.Default)

    init {
        registerLiveUpdates()
        scope.launch { refreshRequests.debounce(REFRESH_DEBOUNCE_MS).collect { loadFromSystem() } }
        scope.launch(Dispatchers.IO) {
            // Cache first for a fast cold start; never overwrite a fresher list.
            readCache()?.let { raw.compareAndSet(null, it) }
            loadFromSystem()
        }
    }

    override suspend fun refresh() = loadFromSystem()

    // ---- system source -------------------------------------------------------------------------

    private suspend fun loadFromSystem() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val list = queryLauncherApps()
            raw.value = list
            writeCache(list)
        }
    }

    private fun queryLauncherApps(): List<RawApp> {
        val result = ArrayList<RawApp>()
        for (user in userManager.userProfiles) {
            if (!isProfileAvailable(user)) continue
            val serial = userManager.getSerialNumberForUser(user)
            val isWork = user != Process.myUserHandle()
            try {
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == OWN_PACKAGE) continue
                    result += RawApp(
                        key = AppKey(info.componentName.packageName, info.componentName.className, serial),
                        systemLabel = info.label?.toString().orEmpty().ifBlank { info.componentName.packageName },
                        isWorkProfile = isWork,
                        firstInstallTime = info.firstInstallTime,
                    )
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot list apps for $user", e)
            }
        }
        return result
    }

    /** Locked or paused (quiet mode) profiles cannot be queried or launched. */
    private fun isProfileAvailable(user: UserHandle): Boolean {
        if (!userManager.isUserUnlocked(user)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && userManager.isQuietModeEnabled(user)) return false
        return true
    }

    // ---- cache ---------------------------------------------------------------------------------

    private fun readCache(): List<RawApp>? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString<AppsCache>(cacheFile.readText()).apps.mapNotNull { c ->
            val key = AppKey.parse(c.id) ?: return@mapNotNull null
            RawApp(key, c.label, key.userSerial != ownSerial, c.installTime)
        }
    }.onFailure { Log.w(TAG, "Ignoring unreadable apps cache", it) }.getOrNull()

    private fun writeCache(list: List<RawApp>) {
        runCatching {
            val text = json.encodeToString(AppsCache(list.map { CachedApp(it.key.id, it.systemLabel, it.firstInstallTime) }))
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) { cacheFile.writeText(text); tmp.delete() }
        }.onFailure { Log.w(TAG, "Failed to write apps cache", it) }
    }

    // ---- live updates --------------------------------------------------------------------------

    private fun requestRefresh() { refreshRequests.tryEmit(Unit) }

    /** Registered once for the lifetime of the process (this repository is an app singleton). */
    private fun registerLiveUpdates() {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = requestRefresh()
            override fun onPackageAdded(packageName: String, user: UserHandle) = requestRefresh()
            override fun onPackageChanged(packageName: String, user: UserHandle) = requestRefresh()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = requestRefresh()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = requestRefresh()
        }, android.os.Handler(android.os.Looper.getMainLooper())) // repository may be created off the main thread
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = requestRefresh()
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- actions -------------------------------------------------------------------------------

    private fun userFor(key: AppKey): UserHandle? = userManager.getUserForSerialNumber(key.userSerial)

    override fun launch(key: AppKey, sourceBounds: Rect?) {
        val go = {
            // The focus gate may show a pause / time question first and launch the app itself afterwards.
            if (app.quiet.launcher.feature.focus.FocusGate.beforeLaunch(appContext, key.packageName) { rawLaunch(key, sourceBounds) }) {
                rawLaunch(key, sourceBounds)
            }
        }
        // Payment apps refuse to run with precision mode on: switch it off first and give the system a moment.
        if (app.quiet.launcher.feature.focus.PaymentGuard.beforeLaunch(appContext, key.packageName)) {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ go() }, app.quiet.launcher.feature.focus.PaymentGuard.LAUNCH_DELAY_MS)
        } else {
            go()
        }
    }

    private fun rawLaunch(key: AppKey, sourceBounds: Rect?) {
        val user = userFor(key) ?: return
        try {
            launcherApps.startMainActivity(ComponentName(key.packageName, key.activityName), user, sourceBounds, null)
        } catch (e: Exception) { // ActivityNotFoundException, SecurityException, IllegalStateException
            Log.w(TAG, "Could not launch ${key.id}", e)
        }
    }

    override fun openAppInfo(key: AppKey) {
        val user = userFor(key) ?: return
        try {
            launcherApps.startAppDetailsActivity(ComponentName(key.packageName, key.activityName), user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open app info for ${key.id}", e)
        }
    }

    override fun uninstall(key: AppKey) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${key.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (key.userSerial != ownSerial) userFor(key)?.let { intent.putExtra(Intent.EXTRA_USER, it) }
        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start uninstall for ${key.id}", e)
        }
    }

    override suspend fun setFavourite(key: AppKey, favourite: Boolean) = withContext(Dispatchers.IO) {
        if (favourite) favDao.append(key.id) else favDao.delete(key.id)
    }

    override suspend fun moveFavourite(from: Int, to: Int): Unit = withContext(Dispatchers.IO) {
        val allIds = favDao.getAll().map { it.id }
        val visibleIds = mergeFavourites(allIds, apps.value).map { it.key.id }
        reorderFavourites(allIds, visibleIds, from, to)?.let { favDao.renumber(it) }
    }

    override suspend fun setHidden(key: AppKey, hidden: Boolean) = withContext(Dispatchers.IO) {
        metaDao.modify(key.id) { it.copy(hidden = hidden) }
    }

    override suspend fun rename(key: AppKey, label: String?) = withContext(Dispatchers.IO) {
        metaDao.modify(key.id) { it.copy(customLabel = label?.trim()?.takeIf { l -> l.isNotEmpty() }) }
    }

    override suspend fun setFolder(key: AppKey, folder: String?) = withContext(Dispatchers.IO) {
        metaDao.modify(key.id) { it.copy(folder = folder?.trim()?.takeIf { f -> f.isNotEmpty() }) }
    }
}
