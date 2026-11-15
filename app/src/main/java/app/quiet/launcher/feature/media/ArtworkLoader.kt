package app.quiet.launcher.feature.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import androidx.palette.graphics.Palette

/** Decodes artwork and derives palettes. Everything here may block: call off the main thread. */
internal object ArtworkLoader {
    const val MAX_SIDE = 640

    /** Artwork fallback chain; [notificationArt] is the last resort. Returns null when nothing is found. */
    fun load(context: Context, md: MediaMetadata?, notificationArt: Bitmap?): Bitmap? {
        if (md != null) {
            for (key in listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
                val bmp = runCatching { md.getBitmap(key) }.getOrNull()
                prepare(bmp)?.let { return it }
            }
            for (key in listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
                val s = md.getString(key)?.takeIf { it.isNotBlank() } ?: continue
                decodeUri(context, s)?.let { return it }
            }
        }
        return prepare(notificationArt)
    }

    /** Software-config copy with the longest side <= [MAX_SIDE]; null if unusable. */
    fun prepare(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled || src.width <= 0 || src.height <= 0) return null
        return runCatching {
            var b: Bitmap = src
            if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val (w, h) = MediaLogic.scaledSize(b.width, b.height, MAX_SIDE)
            if (w != b.width || h != b.height) b = Bitmap.createScaledBitmap(b, w, h, true)
            b
        }.getOrNull()
    }

    private fun decodeUri(context: Context, raw: String): Bitmap? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        // http(s) is skipped on purpose: no network from here.
        if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE &&
            scheme != ContentResolver.SCHEME_ANDROID_RESOURCE) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (w, h) = MediaLogic.scaledSize(info.size.width, info.size.height, MAX_SIDE)
                    decoder.setTargetSize(w, h)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val raw2 = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                prepare(raw2)
            }
        }.getOrNull()
    }

    /** Palette -> [darkMuted, vibrant, muted, lightVibrant], with HSL-derived fallbacks. */
    fun palette(bitmap: Bitmap?): IntArray {
        if (bitmap == null) return PaletteMath.Neutral.copyOf()
        return runCatching {
            val p = Palette.from(bitmap).maximumColorCount(16).generate()
            PaletteMath.build(
                p.darkMutedSwatch?.rgb?.opaque(),
                p.vibrantSwatch?.rgb?.opaque(),
                p.mutedSwatch?.rgb?.opaque(),
                p.lightVibrantSwatch?.rgb?.opaque(),
                p.dominantSwatch?.rgb?.opaque(),
            )
        }.getOrElse { PaletteMath.Neutral.copyOf() }
    }

    private fun Int.opaque() = this or (0xFF shl 24)
}
