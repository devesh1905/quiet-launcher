package app.quiet.launcher.feature.lock

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.LockStyle
import app.quiet.launcher.designsystem.lockFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Renders the chosen lock style into the SYSTEM lock-screen wallpaper, so the phone's own lock
 * screen (clock, notifications, fingerprint) keeps working exactly as before, with our art behind it.
 *
 * The real scene composables are recorded into an offscreen graphics layer (never drawn on screen),
 * then captured as a bitmap at the display's size. Re-rendered when anything visible changes:
 * style, font, theme, today's painting, the day, or the quiet canvas's calm level.
 */
@Composable
fun LockWallpaperSync() {
    val ctx = LocalContext.current
    val container = ctx.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    if (!settings.lockWallpaper) return
    val art by container.art.today.collectAsStateWithLifecycle()
    val usage by container.usage.today.collectAsStateWithLifecycle()
    val np by container.nowPlaying.state.collectAsStateWithLifecycle()
    val minute by container.device.minuteTick.collectAsStateWithLifecycle()

    val today = LocalDate.now()
    val calmStep = (calmFor(usage.totalMs) * 4).toInt()        // re-render at most 5 times a day for calm
    val skyHour = if (settings.lockStyle == LockStyle.SKY) (minute / 3_600_000L) else 0L
    val coverId = if (settings.lockStyle == LockStyle.COVER) np?.track?.id else null
    val key = listOf(settings.lockStyle, settings.lockFont, settings.theme, art?.id, today, calmStep, skyHour, coverId, settings.oneThingToday)
        .joinToString("|")

    val prefs = remember { ctx.getSharedPreferences("lock_wallpaper", Context.MODE_PRIVATE) }
    if (prefs.getString("key", null) == key) return

    val (wPx, hPx) = remember { displaySize(ctx) }
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    val lockCtx = LockContext(
        face = lockFace(settings.lockFont), minute = minute, is24h = DateFormat.is24HourFormat(ctx),
        usageMs = usage.totalMs, oneThing = settings.oneThingToday, art = art,
        lat = settings.latitude, lon = settings.longitude, city = settings.cityName,
        animate = false, wallpaper = true,
    )

    // Zero-size host; the scene is laid out at full display size but only recorded, never drawn.
    Box(Modifier.size(0.dp)) {
        Box(
            Modifier
                .requiredSize(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
        ) {
            when (settings.lockStyle) {
                LockStyle.MASTERPIECE -> MasterpieceScene(lockCtx)
                LockStyle.CANVAS -> QuietCanvasScene(lockCtx)
                LockStyle.SKY -> SkyScene(lockCtx)
                LockStyle.TYPESET -> TypesetScene(lockCtx)
                LockStyle.COVER -> CoverScene(lockCtx, np?.track?.art, np?.track?.title, np?.track?.artist)
            }
        }
    }

    LaunchedEffect(key) {
        // Let the painting decode, the palette resolve and the colour field settle.
        delay(3500)
        runCatching {
            val bmp = layer.toImageBitmap().asAndroidBitmap()
            withContext(Dispatchers.IO) {
                val soft = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                WallpaperManager.getInstance(ctx).setBitmap(soft, null, true, WallpaperManager.FLAG_LOCK)
            }
            prefs.edit().putString("key", key).apply()
        }.onFailure { Log.w("Quiet", "Lock wallpaper failed", it) }
    }
}

private fun displaySize(ctx: Context): Pair<Int, Int> {
    val wm = ctx.getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.maximumWindowMetrics.bounds
        b.width() to b.height()
    } else {
        val dm = ctx.resources.displayMetrics
        dm.widthPixels to dm.heightPixels
    }
}
