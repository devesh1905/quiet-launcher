package app.quiet.launcher.feature.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import app.quiet.launcher.core.data.ArtRepository
import app.quiet.launcher.core.data.SettingsRepository
import app.quiet.launcher.core.model.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * Daily public-domain artwork. State lives in filesDir/art/index.json (works, current id/date, shown history).
 * All disk/network work runs on Dispatchers.IO; nothing throws to callers.
 */
class ArtRepositoryImpl(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) : ArtRepository {

    private val http = ArtHttp()
    private val sources: List<ArtSource> = listOf(AicSource(http), MetSource(http), ClevelandSource(http))

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "art")
    private val indexFile = File(dir, "index.json")
    private val rnd = Random.Default

    private val _today = MutableStateFlow<Artwork?>(null)
    override val today: StateFlow<Artwork?> = _today
    private val _library = MutableStateFlow<List<Artwork>>(emptyList())
    override val library: StateFlow<List<Artwork>> = _library

    private var index = ArtIndex()
    private val stateLock = Mutex()
    private val fetchLock = Mutex()
    private val loadJob: Job

    init {
        loadJob = scope.launch(Dispatchers.IO) {
            try {
                stateLock.withLock {
                    index = ArtLogic.ensureToday(loadIndex(), System.currentTimeMillis())
                    saveIndex(index)
                    publish()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "initial load failed", e)
            }
        }
        // Rotate at local midnight (re-checked at least every 15 min to survive sleep / zone changes).
        scope.launch(Dispatchers.IO) {
            while (true) {
                val now = System.currentTimeMillis()
                delay(minOf(ArtLogic.millisUntilNextMidnight(now) + 1_000, 15 * 60_000L))
                loadJob.join()
                mutate { ArtLogic.ensureToday(it, System.currentTimeMillis()) }
            }
        }
    }

    // ---- public API ----

    override suspend fun refreshIfStale() {
        withContext(Dispatchers.IO) {
            try {
                loadJob.join()
                if (!fetchLock.tryLock()) return@withContext
                try {
                    refreshLocked()
                } finally {
                    fetchLock.unlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "refresh failed", e)
            }
        }
    }

    override suspend fun skip() {
        withContext(Dispatchers.IO) {
            try {
                loadJob.join()
                mutate { ArtLogic.skip(it, System.currentTimeMillis()) }
                if (ArtLogic.unseenCount(index, System.currentTimeMillis()) < ArtLogic.MIN_CACHED) {
                    scope.launch { refreshIfStale() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "skip failed", e)
            }
        }
    }

    override suspend fun addUserArtwork(uri: Uri, title: String, artist: String): Artwork? =
        withContext(Dispatchers.IO) {
            try {
                loadJob.join()
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                val id = "user_" + UUID.randomUUID().toString().replace("-", "")
                val file = "$id.jpg"
                dir.mkdirs()
                if (!processImage(bytes, null, ArtLogic.MAX_LONGEST_SIDE, File(dir, file))) return@withContext null
                val work = StoredWork(
                    id = id,
                    title = title.trim().ifEmpty { "Untitled" },
                    artist = artist.trim().ifEmpty { "Unknown" },
                    file = file, isUser = true, addedAt = System.currentTimeMillis(),
                )
                mutate { it.copy(works = it.works + work) }
                toArtwork(work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "addUserArtwork failed", e)
                null
            }
        }

    // ---- refresh ----

    private suspend fun refreshLocked() {
        val now = System.currentTimeMillis()
        // Also settle the daily rotation in case the process was asleep.
        mutate { ArtLogic.ensureToday(it, now) }
        val start = index
        if (!ArtLogic.needsRefresh(start, now)) return
        if (ArtLogic.inBackoff(start, now)) return
        val nothingCached = ArtLogic.cachedWorks(start).isEmpty()
        when (networkState()) {
            NetState.NONE -> return
            NetState.METERED -> if (!nothingCached) return
            NetState.UNMETERED -> {}
        }
        val target = ArtLogic.fetchTarget(start)
        var added = 0
        var attempts = 0
        while (added < target && attempts < target * 2 + 1) {
            attempts++
            if (fetchOne()) added++
        }
        mutate { cur ->
            val withFailure = cur.copy(lastFailureAt = if (added == 0) System.currentTimeMillis() else 0)
            withFailure
        }
    }

    private suspend fun fetchOne(): Boolean {
        val s = settings.settings.value
        val artist = ArtLogic.pickArtist(s.favouriteArtists, ArtLogic.OTHER_MASTERS, rnd)
        val now = System.currentTimeMillis()
        val exclude = index.works.map { it.id }.toSet() + ArtLogic.recentIds(index.history, now)
        for (src in sources.shuffled(rnd)) {
            val cands = try {
                ArtLogic.shortlist(src.candidates(artist), artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "source ${src.name} failed", e); emptyList()
            }
            var pool = cands
            repeat(3) {
                val pick = ArtLogic.selectCandidate(pool, exclude, rnd) ?: return@repeat
                pool = pool.filter { it.id != pick.id }
                if (download(pick)) return true
            }
        }
        return false
    }

    private suspend fun download(c: ArtCandidate): Boolean {
        val bytes = http.getBytes(c.imageUrl) ?: return false
        dir.mkdirs()
        val file = "${c.id}.jpg"
        if (!processImage(bytes, ArtLogic.MIN_LONGEST_SIDE, ArtLogic.MAX_LONGEST_SIDE, File(dir, file))) {
            Log.i(TAG, "rejected ${c.id}: too small or undecodable")
            return false
        }
        val work = StoredWork(c.id, c.title, c.artist, c.year, c.museum, file, false, System.currentTimeMillis())
        val evicted = ArrayList<StoredWork>()
        mutate { cur ->
            var next = cur.copy(works = cur.works.filter { it.id != work.id } + work)
            next = ArtLogic.ensureToday(next, System.currentTimeMillis())
            val drop = ArtLogic.toEvict(next, System.currentTimeMillis())
            evicted += drop
            next.copy(works = next.works.filter { w -> drop.none { it.id == w.id } })
        }
        evicted.forEach { File(dir, it.file).delete() }
        return true
    }

    // ---- state ----

    private suspend fun mutate(transform: (ArtIndex) -> ArtIndex) {
        stateLock.withLock {
            val next = transform(index)
            if (next != index) {
                index = next
                try { saveIndex(next) } catch (e: Exception) { Log.w(TAG, "save index failed", e) }
            }
            publish()
        }
    }

    private fun publish() {
        val cur = index.works.firstOrNull { it.id == index.currentId && !it.isUser }
        _today.value = cur?.let(::toArtwork)
        _library.value = index.works.sortedByDescending { it.addedAt }.map(::toArtwork)
    }

    private fun toArtwork(w: StoredWork) = Artwork(
        id = w.id, title = w.title, artist = w.artist, year = w.year, museum = w.museum,
        imagePath = File(dir, w.file).absolutePath, isUserProvided = w.isUser,
    )

    private fun loadIndex(): ArtIndex {
        val loaded = try {
            if (indexFile.exists()) ArtJson.decodeFromString<ArtIndex>(indexFile.readText()) else ArtIndex()
        } catch (e: Exception) {
            Log.w(TAG, "index unreadable, starting fresh", e); ArtIndex()
        }
        // Drop entries whose file vanished.
        val works = loaded.works.filter { File(dir, it.file).exists() }
        return loaded.copy(works = works)
    }

    private fun saveIndex(i: ArtIndex) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ArtJson.encodeToString(ArtIndex.serializer(), i))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    // ---- helpers ----

    private enum class NetState { NONE, METERED, UNMETERED }

    private fun networkState(): NetState {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return NetState.UNMETERED
        val net = cm.activeNetwork ?: return NetState.NONE
        val caps = cm.getNetworkCapabilities(net) ?: return NetState.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetState.NONE
        return if (cm.isActiveNetworkMetered) NetState.METERED else NetState.UNMETERED
    }

    /**
     * Decodes [bytes], enforces [minLongest] (if given), downsizes to [maxLongest] and writes a JPEG atomically.
     */
    private fun processImage(bytes: ByteArray, minLongest: Int?, maxLongest: Int, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        val longest = maxOf(w, h)
        if (minLongest != null && longest < minLongest) return false
        var sample = 1
        while (longest / (sample * 2) >= maxLongest) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        var bmp = decoded
        try {
            val dl = maxOf(bmp.width, bmp.height)
            if (dl > maxLongest) {
                val f = maxLongest.toFloat() / dl
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
            }
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            return out.exists()
        } finally {
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
        }
    }

    private companion object { const val TAG = ArtHttp.TAG }
}
