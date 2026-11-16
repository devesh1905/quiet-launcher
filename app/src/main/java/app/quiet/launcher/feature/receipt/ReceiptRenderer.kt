package app.quiet.launcher.feature.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import app.quiet.launcher.feature.share.PaperFonts
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws the receipt as warm thermal paper. One renderer for the screen (at the display density)
 * and the saved PNG (at 3x). Units are dp-like: the paper is [W] wide.
 */
class ReceiptRenderer(context: Context) {
    private val serif: Typeface = PaperFonts.fraunces(context)
    private val sans: Typeface = PaperFonts.geist(context, 400)
    private val sansMedium: Typeface = PaperFonts.geist(context, 500)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** Height of the receipt in units. */
    fun measure(r: Receipt): Float = draw(null, r, 0f, 1f)

    /** Renders at [scale] pixels per unit. */
    fun render(r: Receipt, scale: Float): Bitmap {
        val h = measure(r)
        val bmp = Bitmap.createBitmap((W * scale).roundToInt(), (h * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        draw(canvas, r, h, scale)
        return bmp
    }

    private fun font(tf: Typeface, size: Float, spacingPx: Float = 0f) {
        paint.typeface = tf
        paint.textSize = size
        paint.letterSpacing = if (spacingPx == 0f) 0f else spacingPx / size
    }

    private fun draw(cv: Canvas?, r: Receipt, height: Float, scale: Float): Float {
        var y = 0f
        fun text(s: String, x: Float, align: Paint.Align, col: Int = INK) {
            paint.color = col; paint.textAlign = align
            cv?.drawText(s, x, y, paint)
        }
        fun centre(s: String, col: Int = INK) = text(s, W / 2f, Paint.Align.CENTER, col)
        fun dashed() {
            if (cv == null) return
            paint.color = SOFT
            var x = X0
            while (x < X1) { cv.drawRect(x, y - 0.5f, x + 3f, y + 0.5f, paint); x += 6f }
        }
        fun solid(yy: Float) { paint.color = INK; cv?.drawRect(X0, yy, X1, yy + 1f, paint) }
        fun row(label: String, value: String, col: Int = INK, weight: Typeface = sans) {
            font(weight, 14f)
            val lw = paint.measureText(label)
            val vw = paint.measureText(value)
            text(label, X0, Paint.Align.LEFT, col)
            text(value, X1, Paint.Align.RIGHT, col)
            if (cv != null) {
                paint.color = SOFT
                var x = X0 + lw + 7f
                while (x < X1 - vw - 6f) { cv.drawRect(x, y - 2.5f, x + 1.25f, y - 1.25f, paint); x += 4f }
            }
        }

        if (cv != null && height > 0f) {
            cv.save()
            cv.clipPath(teeth(height))
            cv.drawColor(PAPER)
            // a little light falling off toward the curled top and bottom
            val g = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height,
                    intArrayOf(0x1A3C3223, 0x003C3223, 0x003C3223, 0x143C3223),
                    floatArrayOf(0f, 0.06f, 0.94f, 1f), Shader.TileMode.CLAMP,
                )
            }
            cv.drawRect(0f, 0f, W, height, g)
        }

        y = 46f; font(serif, 30f, 10f); centre("QUIET")
        y += 24f; font(sans, 12f, 0.6f); centre("Day receipt  No. %04d".format(r.number), SOFT)
        y += 26f; font(sansMedium, 14f); centre(r.dateLine)
        y += 18f; font(sans, 12f); centre("Printed at ${r.printedAt}", SOFT)
        y += 18f; dashed()

        y += 24f; font(sansMedium, 11f, 1.4f); text("SCREEN TIME", X0, Paint.Align.LEFT, SOFT)
        if (!r.hasUsage) {
            y += 22f; row("Usage access is off", "-", SOFT)
        } else if (r.apps.isEmpty()) {
            y += 22f; row("Nothing yet", "0 min", SOFT)
        }
        r.apps.forEach { (name, min) -> y += 22f; row(ellipsize(name, 200f), "$min min") }
        if (r.otherMin > 0) { y += 22f; row("Everything else", "${r.otherMin} min", SOFT) }
        y += 18f; dashed()

        y += 24f; row("Pickups", r.pickups.toString())
        y += 22f; row("First pickup", r.firstPickup)
        y += 22f; row("Longest away", if (r.longestAwayMin > 0) ReceiptMath.hm(r.longestAwayMin) else "-")
        val focusLabel = when (r.focusSessions) {
            0 -> "Focus"
            1 -> "Focus, 1 session"
            else -> "Focus, ${r.focusSessions} sessions"
        }
        y += 22f; row(focusLabel, if (r.focusMin > 0) ReceiptMath.hm(r.focusMin) else "-")
        y += 18f; solid(y - 1f); solid(y + 2f)

        y += 40f; font(sansMedium, 12f, 2f); text("TOTAL", X0, Paint.Align.LEFT)
        font(serif, 36f); text(ReceiptMath.hm(r.totalMin), X1, Paint.Align.RIGHT)
        if (r.allowanceMin > 0) {
            y += 20f; font(sans, 12f); text(ReceiptMath.allowanceLine(r.totalMin, r.allowanceMin), X1, Paint.Align.RIGHT, SOFT)
        }
        y += 20f; dashed()

        // Barcode: drawn in whole device pixels so it scans.
        y += 24f
        val code = r.code
        val elements = Code39.elements(code)
        val narrowPx = floor(1.2f * scale).coerceAtLeast(1f)
        val widePx = (narrowPx * 2.6f).roundToInt().toFloat()
        val widthPx = elements.sumOf { (if (it == 'w') widePx else narrowPx).toDouble() }.toFloat()
        if (cv != null) {
            cv.save()
            cv.scale(1f / scale, 1f / scale)
            val bars = Paint().apply { color = INK; isAntiAlias = false }
            var bx = ((W * scale - widthPx) / 2f).roundToInt().toFloat()
            val top = (y * scale).roundToInt().toFloat()
            val bottom = ((y + BAR_H) * scale).roundToInt().toFloat()
            elements.forEachIndexed { i, e ->
                val w = if (e == 'w') widePx else narrowPx
                if (i % 2 == 0) cv.drawRect(bx, top, bx + w, bottom, bars)
                bx += w
            }
            cv.restore()
        }
        y += BAR_H
        y += 15f; font(sans, 11f, 2.2f); centre(ReceiptMath.spacedCode(code), SOFT)
        y += 24f; font(sans, 12f); centre("Phone down. Sleep well.", SOFT)
        val end = y + 22f

        if (cv != null && height > 0f) {
            // thermal print texture: faint head banding (lighter streaks) and paper grain
            val rnd = Lcg(r.number * 7L + 3)
            paint.color = PAPER
            repeat(7) {
                paint.alpha = ((0.18f + rnd.next() * 0.22f) * 255).toInt()
                val x = X0 + rnd.next() * (X1 - X0)
                cv.drawRect(x, 0f, x + 0.6f + rnd.next() * 0.8f, height, paint)
            }
            paint.alpha = 255
            cv.drawRect(0f, 0f, W, height, Paint().apply { shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
            cv.restore()
        }
        paint.letterSpacing = 0f
        return end
    }

    private fun ellipsize(s: String, max: Float): String {
        font(sans, 14f)
        if (paint.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && paint.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private fun teeth(h: Float): Path {
        val tw = 8f; val td = 4f
        return Path().apply {
            moveTo(0f, td)
            var x = 0f
            while (x < W) { lineTo(x + tw / 2, 0f); lineTo(minOf(W, x + tw), td); x += tw }
            lineTo(W, h - td)
            x = W
            while (x > 0) { lineTo(x - tw / 2, h); lineTo(maxOf(0f, x - tw), h - td); x -= tw }
            close()
        }
    }

    private class Lcg(seed: Long) {
        private var s = seed and 0xFFFFFFFFL
        fun next(): Float { s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL; return s / 4294967296f }
    }

    companion object {
        const val W = 312f
        private const val PAD = 26f
        private const val X0 = PAD
        private const val X1 = W - PAD
        private const val BAR_H = 42f
        const val PAPER = 0xFFECE8DF.toInt()
        private const val INK = 0xFF1E1C19.toInt()
        private const val SOFT = 0xFF6A655C.toInt()

        @Volatile private var grainTile: Bitmap? = null

        /** 96 px seeded grain tile, shared and kept for the process (36 KB). */
        private fun grain(): Bitmap = grainTile ?: synchronized(this) {
            grainTile ?: run {
                val n = 96
                val rnd = Lcg(91)
                val px = IntArray(n * n) {
                    val a = (rnd.next() * rnd.next() * 22).toInt()
                    (a shl 24) or 0x282828
                }
                Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).also { grainTile = it }
            }
        }
    }
}
