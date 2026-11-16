package app.quiet.launcher.feature.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saving and sharing rendered PNGs (the day receipt, the story card). */
object ImageExport {
    private const val TAG = "QuietShare"

    fun authority(context: Context) = "${context.packageName}.files"

    /**
     * Writes [bitmap] to Pictures/Quiet through MediaStore. No storage permission on Android 10+;
     * older versions return null (they would need the storage permission Quiet does not ask for).
     */
    suspend fun savePng(context: Context, bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Quiet")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Writes [bitmap] to the cache and opens the system share sheet with a FileProvider URI. */
    suspend fun sharePng(context: Context, bitmap: Bitmap, name: String, title: String): Boolean {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                // Only the latest image is kept.
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, name)
                f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, authority(context), f)
            }.onFailure { Log.w(TAG, "share failed", it) }.getOrNull()
        } ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}

/** Typefaces for drawing on a plain Canvas, from the same variable fonts the UI uses. Cached. */
object PaperFonts {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun get(context: Context, path: String, axes: String): Typeface = cache.getOrPut("$path|$axes") {
        runCatching {
            Typeface.Builder(context.applicationContext.assets, path).setFontVariationSettings(axes).build()
        }.getOrNull() ?: Typeface.SERIF
    }

    fun fraunces(context: Context) = get(context, "fonts/fraunces.ttf", "'wght' 300, 'opsz' 144, 'SOFT' 60, 'WONK' 0")
    fun geist(context: Context, weight: Int = 400) = get(context, "fonts/geist.ttf", "'wght' $weight")
    fun bodoniItalic(context: Context) = get(context, "fonts/bodoni_moda_italic.ttf", "'wght' 400, 'opsz' 28")
}
