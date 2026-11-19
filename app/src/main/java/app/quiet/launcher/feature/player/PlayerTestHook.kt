package app.quiet.launcher.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import app.quiet.launcher.core.data.NowPlayingRepository
import app.quiet.launcher.core.model.NowPlaying
import app.quiet.launcher.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Debug builds only. Device testing without a media session (e.g. the underwater edition before it has notification
 * access): when the file `fake-player` exists in the app's external files dir (put there with adb,
 * `adb shell touch /sdcard/Android/data/<package>/files/fake-player`), Home and the player see a fake
 * song that plays, pauses, seeks and changes tracks. Without that file nothing here runs.
 */
object PlayerTestHook {
    private var checked = false
    private var on = false

    fun active(ctx: Context): Boolean {
        if (!checked) {
            checked = true
            // Debug builds only: release builds never read the file.
            on = app.quiet.launcher.BuildConfig.DEBUG && runCatching { File(ctx.getExternalFilesDir(null), "fake-player").exists() }.getOrDefault(false)
        }
        return on
    }

    fun nowPlaying(ctx: Context, real: StateFlow<NowPlaying?>): StateFlow<NowPlaying?> = if (active(ctx)) Fake.state else real

    fun repoOr(ctx: Context, real: NowPlayingRepository): NowPlayingRepository = if (active(ctx)) Fake else real

    private object Fake : NowPlayingRepository {
        private val songs = listOf(
            Triple("Clair de lune", "Claude Debussy", intArrayOf(0xFF102236.toInt(), 0xFF3F7FC0.toInt(), 0xFF5A7A96.toInt(), 0xFFBFE0F5.toInt())),
            Triple("Gymnopédie No. 1", "Erik Satie", intArrayOf(0xFF2A1A10.toInt(), 0xFFE0913F.toInt(), 0xFF8A5A36.toInt(), 0xFFF6D29A.toInt())),
            Triple("Spiegel im Spiegel", "Arvo Pärt", intArrayOf(0xFF14261A.toInt(), 0xFF5DB37A.toInt(), 0xFF4E7458.toInt(), 0xFFCFF0D5.toInt())),
        )
        private val arts = HashMap<Int, Bitmap>()
        private var index = 0
        override val state = MutableStateFlow<NowPlaying?>(make(0, true, 42_000L))
        override val hasPermission = MutableStateFlow(true)

        private fun art(i: Int): Bitmap = arts.getOrPut(i) {
            val p = songs[i].third
            val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 600f, 600f, p[0], p[2], Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 600f, 600f, paint)
            paint.shader = RadialGradient(200f + i * 90f, 240f, 260f, p[1], 0x00000000, Shader.TileMode.CLAMP)
            c.drawCircle(200f + i * 90f, 240f, 260f, paint)
            paint.shader = null
            // Detail for the water to bend: thin bands and a disc.
            for (k in 0 until 14) {
                paint.color = (p[3] and 0x00FFFFFF) or (0x30 shl 24)
                c.drawRect(0f, 330f + k * 18f, 600f, 336f + k * 18f, paint)
            }
            paint.color = p[3]
            c.drawCircle(420f - i * 60f, 170f, 70f, paint)
            paint.color = 0xFF0C0C0C.toInt(); paint.textSize = 54f; paint.isFakeBoldText = true
            c.drawText(songs[i].first, 36f, 560f, paint)
            b
        }

        private fun make(i: Int, playing: Boolean, pos: Long): NowPlaying {
            val (title, artist, pal) = songs[i]
            return NowPlaying(
                track = Track("fake|$i", title, artist, "Test album", "2026", art(i), 214_000L),
                isPlaying = playing, positionMs = pos, positionUpdatedAt = SystemClock.elapsedRealtime(),
                speed = 1f, sourcePackage = "fake", palette = pal,
            )
        }

        private fun now(): NowPlaying = state.value ?: make(index, true, 0L)
        private fun pos(): Long = now().positionAt(SystemClock.elapsedRealtime())

        override fun playPause() { val n = now(); state.value = make(index, !n.isPlaying, pos()) }
        // A real player reports the new song a moment later.
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        override fun next() { index = (index + 1) % songs.size; main.postDelayed({ state.value = make(index, true, 0L) }, 280) }
        override fun previous() { index = (index + songs.size - 1) % songs.size; main.postDelayed({ state.value = make(index, true, 0L) }, 280) }
        override fun seekTo(positionMs: Long) { state.value = make(index, now().isPlaying, positionMs) }
    }
}
