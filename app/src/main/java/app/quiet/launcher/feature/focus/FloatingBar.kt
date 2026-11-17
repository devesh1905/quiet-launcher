package app.quiet.launcher.feature.focus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating progress bar over a watched app: a small black pill under the status bar with
 * the time left and a hairline that empties. Tap it for "Hide for this visit" and "Hide always".
 *
 * Plain Views on purpose: a ComposeView in a WindowManager window needs its own lifecycle and
 * saved-state owners, and this is two lines of text. Drawn with the dark tokens in both themes,
 * because it floats over other apps' colours. Needs the draw-over-apps permission.
 */
class FloatingBar(private val app: Context) {
    private var view: BarView? = null
    private var host: Context? = null
    private var wm: WindowManager? = null

    fun show(remainingMs: Long, grantedMs: Long, onHideVisit: () -> Unit, onHideAlways: () -> Unit) {
        val target: Context = app
        if (!Settings.canDrawOverlays(app)) return
        if (view == null || host !== target) {
            hide()
            if (!attach(target, false)) return
        }
        view?.update(remainingMs, grantedMs, onHideVisit, onHideAlways)
    }

    fun hide() {
        val v = view ?: return
        try {
            wm?.removeViewImmediate(v)
        } catch (_: Exception) {
            // Already detached.
        }
        view = null
        host = null
        wm = null
    }

    private fun attach(target: Context, accessibility: Boolean): Boolean {
        val manager = target.getSystemService(WindowManager::class.java) ?: return false
        val v = BarView(target)
        val type = if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight(target) + v.dp(6)
            title = "Quiet time left"
        }
        return try {
            manager.addView(v, lp)
            view = v
            host = target
            wm = manager
            true
        } catch (e: Exception) {
            Log.w("FloatingBar", "Cannot add overlay", e)
            false
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    @SuppressLint("ViewConstructor", "SetTextI18n")
    private class BarView(ctx: Context) : LinearLayout(ctx) {
        private val geist: Typeface = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/geist.ttf") }.getOrDefault(Typeface.DEFAULT)
        private val label = text(14f, INK)
        private val line = LineView(ctx)
        private val options = LinearLayout(ctx).apply { orientation = VERTICAL; visibility = GONE }
        private val hideVisit = option("Hide for this visit")
        private val hideAlways = option("Hide always")
        private var onHideVisit: () -> Unit = {}
        private var onHideAlways: () -> Unit = {}

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(176)
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), EDGE)
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(line, LayoutParams(dp(140), dp(6)).apply { topMargin = dp(6) })
            options.addView(hideVisit, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
            options.addView(hideAlways, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
            addView(options, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            setOnClickListener { options.visibility = if (options.visibility == VISIBLE) GONE else VISIBLE }
            hideVisit.setOnClickListener { onHideVisit() }
            hideAlways.setOnClickListener { onHideAlways() }
            contentDescription = "Time left in this app. Tap for options."
        }

        fun update(remainingMs: Long, grantedMs: Long, visit: () -> Unit, always: () -> Unit) {
            onHideVisit = visit
            onHideAlways = always
            val s = (remainingMs / 1000).coerceAtLeast(0)
            label.text = if (s >= 60) "${(s + 59) / 60} min left" else "0:%02d left".format(s)
            line.fraction = if (grantedMs > 0) (remainingMs.toFloat() / grantedMs).coerceIn(0f, 1f) else 0f
        }

        private fun text(sp: Float, color: Int) = TextView(context).apply {
            typeface = geist
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            fontFeatureSettings = "tnum"
        }

        private fun option(t: String) = text(14f, MUTED).apply {
            text = t
            gravity = Gravity.CENTER
        }

        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    }

    private class LineView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        var fraction = 1f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val y = height / 2f
            val w = resources.displayMetrics.density * 1.5f
            paint.strokeWidth = w
            paint.color = EDGE
            canvas.drawLine(w, y, width - w, y, paint)
            paint.color = INK
            if (fraction > 0f) canvas.drawLine(w, y, w + (width - 2 * w) * fraction, y, paint)
        }
    }

    private companion object {
        const val BG = 0xFF000000.toInt()
        const val INK = 0xFFEDEBE6.toInt()
        const val MUTED = 0xFF9A968E.toInt()
        const val EDGE = 0xFF6E6B64.toInt()
    }
}
