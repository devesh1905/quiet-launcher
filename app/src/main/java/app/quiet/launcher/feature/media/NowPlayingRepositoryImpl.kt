package app.quiet.launcher.feature.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.core.app.NotificationManagerCompat
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Observes active media sessions (needs notification-listener access) and publishes [NowPlaying].
 * All session bookkeeping runs on the main thread; artwork decoding and palette run on Dispatchers.Default.
 */
class NowPlayingRepositoryImpl(context: Context, private val scope: CoroutineScope) : NowPlayingRepository {

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val component = ComponentName(ctx, MediaListenerService::class.java)

    private val _state = MutableStateFlow<NowPlaying?>(null)
    override val state: StateFlow<NowPlaying?> = _state
    private val _hasPermission = MutableStateFlow(checkPermission())
    override val hasPermission: StateFlow<Boolean> = _hasPermission

    private class Wrapper(val controller: MediaController) : SessionInfo {
        override val packageName: String get() = controller.packageName
        override val isActivelyPlaying: Boolean get() = isPlayingState(runCatching { controller.playbackState?.state }.getOrNull())
    }

    private class ArtEntry(val bitmap: Bitmap?, val palette: IntArray, val hadArtHint: Boolean)

    // Main-thread state.
    private var manager: MediaSessionManager? = null
    private var listenerRegistered = false
    private var sessions: List<Wrapper> = emptyList()
    private var chosen: MediaController? = null
    private var lastPlayingPackage: String? = null
    private var idleSince = 0L
    // When music last played, in wall-clock time and persisted, so a paused session that is still
    // registered after hours (or after a restart) does not bring the old song back.
    private val playPrefs = context.applicationContext.getSharedPreferences("now_playing", Context.MODE_PRIVATE)
    private var wasPlaying = false
    private fun markPlayedNow() { playPrefs.edit().putLong("last_played_at", System.currentTimeMillis()).apply() }
    private fun lastPlayedAt(): Long = playPrefs.getLong("last_played_at", 0L)
    private var lastEmitAt = 0L
    private var rebuildPending = false
    private var artJob: Job? = null
    private var artJobTrackId: String? = null
    private val artCache = LruCache<String, ArtEntry>(6)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        refreshSessions(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { rebuild() ; reselectIfNeeded() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { scheduleRebuild() }
        override fun onSessionDestroyed() { queryAndRefresh() }
    }

    private val rebuildRunnable = Runnable { rebuildPending = false; rebuild() }
    private val expiryRunnable = Runnable { rebuild() }

    init {
        main.post { connect() }
    }

    // ---- public API ----

    /** Re-evaluate permission and sessions; safe from any thread (call from UI onResume). */
    fun recheck() { main.post { connect() } }

    /** Called by [MediaListenerService] when a media notification's art may have changed. */
    internal fun onNotificationArt(pkg: String) {
        main.post {
            val c = chosen ?: return@post
            if (c.packageName != pkg) return@post
            val id = currentTrackId(c) ?: return@post
            val e = artCache.get(id)
            if (e != null && e.bitmap == null && MediaNotificationArt.get(pkg) != null) {
                artCache.remove(id)
                rebuild()
            }
        }
    }

    override fun playPause() = withControls { c ->
        if (isPlayingState(c.playbackState?.state)) c.transportControls.pause() else c.transportControls.play()
    }
    override fun next() = withControls { it.transportControls.skipToNext() }
    override fun previous() = withControls { it.transportControls.skipToPrevious() }
    override fun seekTo(positionMs: Long) = withControls { it.transportControls.seekTo(positionMs.coerceAtLeast(0)) }

    private fun withControls(block: (MediaController) -> Unit) {
        val run = Runnable { chosen?.let { runCatching { block(it) } } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    // ---- connection ----

    private fun checkPermission(): Boolean =
        runCatching { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }.getOrDefault(false)

    private fun connect() {
        val granted = checkPermission()
        _hasPermission.value = granted
        if (!granted) { teardown(); return }
        if (manager == null) manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val m = manager ?: return
        if (!listenerRegistered) {
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                listenerRegistered = true
            } catch (_: SecurityException) {
                // Listed as enabled but the service is not bound yet; onListenerConnected will call recheck().
            } catch (_: RuntimeException) { }
        }
        queryAndRefresh()
    }

    private fun teardown() {
        if (listenerRegistered) { runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }; listenerRegistered = false }
        detach()
        sessions = emptyList()
        main.removeCallbacks(rebuildRunnable); main.removeCallbacks(expiryRunnable); rebuildPending = false
        _state.value = null
    }

    private fun queryAndRefresh() {
        val m = manager ?: return
        val list = try {
            m.getActiveSessions(component)
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
        refreshSessions(list)
    }

    private fun refreshSessions(list: List<MediaController>) {
        sessions = list.map { Wrapper(it) }
        val pick = MediaLogic.select(sessions, lastPlayingPackage)?.controller
        if (pick?.sessionToken != chosen?.sessionToken) {
            detach()
            if (pick != null) {
                chosen = pick
                runCatching { pick.registerCallback(callback, main) }
                idleSince = 0L
            }
        }
        rebuild()
    }

    private fun detach() {
        chosen?.let { runCatching { it.unregisterCallback(callback) } }
        chosen = null
        artJob?.cancel(); artJob = null; artJobTrackId = null
    }

    /** If the chosen session paused and another is playing (or vice versa), switch. */
    private fun reselectIfNeeded() {
        val pick = MediaLogic.select(sessions, lastPlayingPackage)?.controller ?: return
        if (pick.sessionToken != chosen?.sessionToken) refreshSessions(sessions.map { it.controller })
    }

    // ---- state building ----

    private fun scheduleRebuild() {
        if (rebuildPending) return
        val wait = MIN_EMIT_GAP_MS - (SystemClock.elapsedRealtime() - lastEmitAt)
        if (wait <= 0) rebuild() else { rebuildPending = true; main.postDelayed(rebuildRunnable, wait) }
    }

    private fun currentTrackId(c: MediaController): String? {
        val md = runCatching { c.metadata }.getOrNull() ?: return null
        val title = titleOf(md)
        if (title.isBlank()) return null
        // Same key as the art cache: track + art fingerprint.
        return MediaLogic.trackId(c.packageName, title, artistOf(md), md.getLong(MediaMetadata.METADATA_KEY_DURATION)) + "|" + artSignature(md)
    }

    private fun titleOf(md: MediaMetadata) =
        (md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE)).orEmpty()

    private fun artistOf(md: MediaMetadata) =
        (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty()

    private fun rebuild() {
        main.removeCallbacks(rebuildRunnable); main.removeCallbacks(expiryRunnable); rebuildPending = false
        val c = chosen
        if (c == null) { publish(null); return }
        val md = runCatching { c.metadata }.getOrNull()
        val ps = runCatching { c.playbackState }.getOrNull()
        val title = md?.let { titleOf(it) }.orEmpty()
        if (md == null || title.isBlank()) { publish(null); return }

        val now = SystemClock.elapsedRealtime()
        val playing = isPlayingState(ps?.state)
        val wall = System.currentTimeMillis()
        if (playing) {
            lastPlayingPackage = c.packageName; idleSince = 0L
            if (!wasPlaying) markPlayedNow()
            wasPlaying = true
        } else {
            if (wasPlaying) { markPlayedNow(); wasPlaying = false }   // the moment it stopped
            val last = lastPlayedAt()
            // Never seen playing (e.g. a stale session after a restart), or stopped too long ago: hide it.
            if (last == 0L || wall - last > MediaLogic.PAUSED_TTL_MS) { publish(null); return }
            main.postDelayed(expiryRunnable, (last + MediaLogic.PAUSED_TTL_MS - wall + 1000).coerceAtLeast(1000))
        }

        val artist = artistOf(md)
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val id = MediaLogic.trackId(c.packageName, title, artist, duration)
        // Cache by track AND by what the art actually is: Apple Music (and others) publish the new
        // title first with the previous cover still attached, then the real cover a moment later.
        val artKey = id + "|" + artSignature(md)
        var cached = artCache.get(artKey)
        if (cached != null && cached.bitmap == null && !cached.hadArtHint && hasArtHint(md)) { artCache.remove(artKey); cached = null }
        if (cached == null) startArtJob(artKey, c.packageName, md)

        val track = Track(
            id = id,
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            year = MediaLogic.yearOf(md.getLong(MediaMetadata.METADATA_KEY_YEAR), md.getString(MediaMetadata.METADATA_KEY_DATE)),
            art = cached?.bitmap,
            durationMs = duration,
        )
        val np = NowPlaying(
            track = track,
            isPlaying = playing,
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0L,
            positionUpdatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
            speed = ps?.playbackSpeed?.takeIf { it != 0f } ?: 1f,
            sourcePackage = c.packageName,
            palette = cached?.palette ?: PaletteMath.Neutral.copyOf(),
        )
        publish(np)
    }

    /** Cheap fingerprint of the attached art: size plus a 4x4 grid of sampled pixels, and the art URIs. */
    private fun artSignature(md: MediaMetadata): String {
        val bmp = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { runCatching { md.getBitmap(it) }.getOrNull() }
        val sb = StringBuilder()
        if (bmp != null && !bmp.isRecycled) {
            sb.append(bmp.width).append('x').append(bmp.height)
            runCatching {
                for (i in 1..4) for (j in 1..4) {
                    sb.append(':').append(Integer.toHexString(bmp.getPixel(bmp.width * i / 5, bmp.height * j / 5)))
                }
            }
        }
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .forEach { sb.append('|').append(md.getString(it).orEmpty()) }
        return sb.toString().hashCode().toString(16)
    }

    private fun hasArtHint(md: MediaMetadata): Boolean = listOf(
        MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    ).any { runCatching { md.getBitmap(it) }.getOrNull() != null } || listOf(
        MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    ).any { !md.getString(it).isNullOrBlank() }

    private fun publish(np: NowPlaying?) {
        lastEmitAt = SystemClock.elapsedRealtime()
        _state.value = np
    }

    private fun startArtJob(id: String, pkg: String, md: MediaMetadata) {
        if (artJobTrackId == id && artJob?.isActive == true) return
        artJob?.cancel()
        artJobTrackId = id
        val notifArt = MediaNotificationArt.get(pkg)
        artJob = scope.launch(Dispatchers.Default) {
            val bmp = ArtworkLoader.load(ctx, md, notifArt)
            val pal = ArtworkLoader.palette(bmp)
            main.post {
                artCache.put(id, ArtEntry(bmp, pal, hasArtHint(md)))
                if (artJobTrackId == id) { artJobTrackId = null; artJob = null }
                val cur = chosen
                if (cur != null && currentTrackId(cur) == id) rebuild()
            }
        }
    }

    private companion object {
        const val MIN_EMIT_GAP_MS = 100L
        fun isPlayingState(s: Int?) = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }
}
