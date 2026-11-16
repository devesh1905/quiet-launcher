package app.quiet.launcher.feature.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.quiet.launcher.core.model.Artwork
import kotlin.math.roundToInt

enum class CardLayout { WALL, BLEED }

/**
 * A 1080 x 1920 story image of a painting: the work, its title and artist, and the Quiet signature.
 * Drawn on a plain Canvas (no screenshot), so nothing private ends up in it.
 */
class ShareCardRenderer(context: Context) {
    private val serifItalic: Typeface = PaperFonts.bodoniItalic(context)
    private val sans: Typeface = PaperFonts.geist(context, 400)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(art: Artwork, painting: Bitmap?, layout: CardLayout): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        when (layout) {
            CardLayout.WALL -> wall(c, art, painting)
            CardLayout.BLEED -> bleed(c, art, painting)
        }
        signature(c, 1532f)
        return bmp
    }

    // 1. The painting framed on a black wall under a picture light.
    private fun wall(c: Canvas, art: Artwork, p: Bitmap?) {
        val aspect = if (p != null && p.height > 0) p.width.toFloat() / p.height else 0.8f
        var iw = 640f
        var ih = iw / aspect
        if (ih > 820f) { ih = 820f; iw = ih * aspect }
        val wood = 14f
        val mat = 48f
        val fw = iw + 2 * (wood + mat)
        val fh = ih + 2 * (wood + mat)
        val block = fh + 190f
        val fx = (W - fw) / 2f
        val fy = maxOf(300f, 300f + (1440f - 300f - block) / 2f)

        // picture light: a warm cone that falls to exact black
        paint.shader = RadialGradient(
            W / 2f, fy + 100f, 900f,
            intArrayOf(0x29FFE2BA, 0x0DFFE2BA, 0x00FFE2BA), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFF1A1714.toInt(); c.drawRect(W / 2f - 90f, fy - 46f, W / 2f + 90f, fy - 36f, paint)
        paint.color = 0x59FFE2BA; c.drawRect(W / 2f - 90f, fy - 36f, W / 2f + 90f, fy - 35f, paint)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WOOD; setShadowLayer(60f, 0f, 36f, 0xE6000000.toInt()) }
        c.drawRect(fx, fy, fx + fw, fy + fh, shadow)
        paint.color = MAT; c.drawRect(fx + wood, fy + wood, fx + fw - wood, fy + fh - wood, paint)
        val inner = RectF(fx + wood + mat, fy + wood + mat, fx + wood + mat + iw, fy + wood + mat + ih)
        if (p != null) c.drawBitmap(p, null, inner, image) else { paint.color = 0xFF2A2925.toInt(); c.drawRect(inner, paint) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = 0x40000000
        c.drawRect(inner, paint); paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, fy, 0f, fy + fh, 0x12FFECCE, 0x2E000000, Shader.TileMode.CLAMP)
        c.drawRect(fx, fy, fx + fw, fy + fh, paint)
        paint.shader = null

        label(c, art, fx, fy + fh + 76f, fw, 46f)
    }

    // 2. The painting fills the card and dissolves into black under the words.
    private fun bleed(c: Canvas, art: Artwork, p: Bitmap?) {
        val area = RectF(0f, 0f, W.toFloat(), 1380f)
        if (p != null) c.drawBitmap(p, centreCrop(p, area.width() / area.height()), area, image)
        paint.shader = LinearGradient(0f, 0f, 0f, 520f, 0x59000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), 520f, paint)
        paint.shader = LinearGradient(
            0f, 860f, 0f, 1381f, intArrayOf(0x00000000, 0xB8000000.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 860f, W.toFloat(), 1381f, paint)
        paint.shader = null
        label(c, art, 96f, 1220f, W - 192f, 54f)
    }

    /** Museum label: title in the lock face italic, painter in Geist. */
    private fun label(c: Canvas, art: Artwork, x: Float, top: Float, maxW: Float, size: Float) {
        paint.typeface = serifItalic; paint.textSize = size; paint.letterSpacing = 0f
        paint.textAlign = Paint.Align.LEFT; paint.color = INK
        var y = top
        wrap(art.title, maxW, 2).forEach { c.drawText(it, x, y, paint); y += size * 1.15f }
        y += size * 0.1f
        paint.typeface = sans; paint.textSize = (size * 0.62f).roundToInt().toFloat(); paint.color = MUTED
        c.drawText(wrap(art.artist, maxW, 1).firstOrNull() ?: "", x, y, paint)
    }

    /** The signature: hairline, "Quiet" wordmark, the line and the address, inside the story safe zone. */
    private fun signature(c: Canvas, top: Float) {
        paint.shader = null
        paint.color = LINE; c.drawRect(96f, top, W - 96f, top + 2f, paint)
        paint.typeface = serifItalic; paint.textSize = 82f; paint.letterSpacing = -1f / 82f
        paint.textAlign = Paint.Align.LEFT; paint.color = INK
        c.drawText("Quiet", 92f, top + 104f, paint)
        paint.letterSpacing = 0f; paint.typeface = sans; paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 30f; paint.color = MUTED
        c.drawText("a quiet launcher for Android", W - 96f, top + 58f, paint)
        paint.textSize = 29f; paint.color = INK
        c.drawText("github.com/devesh1905/quiet-launcher", W - 96f, top + 102f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun wrap(s: String, maxW: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (w in s.trim().split(Regex("\\s+"))) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(t) > maxW && cur.isNotEmpty()) { lines += cur; cur = w } else cur = t
        }
        if (cur.isNotEmpty()) lines += cur
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && paint.measureText("$last…") > maxW) last = last.dropLast(1)
        kept[kept.size - 1] = last.trimEnd() + "…"
        return kept
    }

    private fun centreCrop(p: Bitmap, ratio: Float): Rect {
        val pr = p.width.toFloat() / p.height
        return if (pr > ratio) {
            val w = (p.height * ratio).roundToInt()
            Rect((p.width - w) / 2, 0, (p.width + w) / 2, p.height)
        } else {
            val h = (p.width / ratio).roundToInt()
            Rect(0, (p.height - h) / 2, p.width, (p.height + h) / 2)
        }
    }

    companion object {
        const val W = 1080
        const val H = 1920
        private const val INK = 0xFFEDEBE6.toInt()
        private const val MUTED = 0xFF9A968E.toInt()
        private const val LINE = 0xFF2A2925.toInt()
        private const val WOOD = 0xFF2B241D.toInt()
        private const val MAT = 0xFFECE8DF.toInt()

        /** Decodes the painting no larger than needed for the card (about 1600 px on the long side). */
        fun loadPainting(path: String, maxSide: Int = 1600): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }
}
