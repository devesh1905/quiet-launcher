package app.quiet.launcher.feature.underwater

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Feel
import app.quiet.launcher.designsystem.Haptics
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.lock.rememberFileBitmap
import app.quiet.launcher.feature.underwater.engine.WaterHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The glass keyboard (Quiet Underwater, All apps search only; classic keeps the system keyboard).
 *
 * Keys are glass floating over the water, drawn ABOVE the world (the WaterTop layer) so type and
 * press springs stay crisp and never wait on the world's RenderEffect. The glass body budget (16)
 * cannot hold a body per key, so every key dome is drawn in ONE AGSL pass over the keyboard area
 * (KEYS_SRC): each pixel finds its key from the row and the key cells (uniform arrays of animated
 * rects and press states) and shades it in the engine's glass language (dome normal from the SDF
 * gradient, chromatic refraction, fresnel, specular, rim, contact shadow and caustic line). What the
 * glass looks into is the backdrop the world shows there: the same painting, mapped exactly as
 * WaterBackdrop maps it (crop, 1.06 scale, tilt parallax, the veil), with the water's absorption
 * below the live waterline, as the engine's glass does (absorb .65). Between the keys the live water
 * shows through, and each press sends a real ripple into it.
 *
 * The key preview is a magnifying bead (lens .9) drawn by the same shader in a second, small pass,
 * fused to its key by a smooth-min neck while it pops out.
 */

/** Height of the keyboard above the navigation bar (the list pads by this while it is up). */
internal val GlassKeyboardHeight = 230.dp

private const val TOP_DP = 8f
private const val ROW_DP = 54f
private const val MAX_KEYS = 32
private const val TAG = "QuietKeys"

private enum class KeyKind { Letter, Space, Back, Mic, Sys, Go }

private class GKey(val kind: KeyKind, val ch: Char, val row: Int, val units: Float, val desc: String)

private fun buildKeys(voice: Boolean): List<GKey> = buildList {
    "qwertyuiop".forEach { add(GKey(KeyKind.Letter, it, 0, 1f, it.toString())) }
    "asdfghjkl".forEach { add(GKey(KeyKind.Letter, it, 1, 1f, it.toString())) }
    add(GKey(KeyKind.Sys, ' ', 2, 1.5f, "Use the phone's keyboard"))
    "zxcvbnm".forEach { add(GKey(KeyKind.Letter, it, 2, 1f, it.toString())) }
    add(GKey(KeyKind.Back, ' ', 2, 1.5f, "Delete"))
    if (voice) add(GKey(KeyKind.Mic, ' ', 3, 1.5f, "Search by voice"))
    add(GKey(KeyKind.Space, ' ', 3, if (voice) 7f else 8.5f, "Space"))
    add(GKey(KeyKind.Go, ' ', 3, 1.5f, "Open the first app"))
}

/** Key rects (px, keyboard-local, at rest) and touch cells. Every point of a row belongs to one key. */
private class KbGeo(val keys: List<GKey>) {
    val n = keys.size
    val cx = FloatArray(n); val cy = FloatArray(n); val hw = FloatArray(n); val hh = FloatArray(n)
    val l = FloatArray(n); val r = FloatArray(n)
    val rowStart = IntArray(4); val rowEnd = IntArray(4)
    var w = -1f; var dp = 1f
    var top = 0f; var rowH = 0f

    fun layout(width: Float, density: Float) {
        if (width == w && density == dp) return
        w = width; dp = density
        val padX = 3f * dp
        top = TOP_DP * dp; rowH = ROW_DP * dp
        val u = (w - 2f * padX) / 10f
        val gx = 2.8f * dp; val gy = 5f * dp
        var i = 0
        for (row in 0 until 4) {
            rowStart[row] = i
            val y0 = top + row * rowH
            var x = padX + if (row == 1) 0.5f * u else 0f
            while (i < n && keys[i].row == row) {
                val kw = keys[i].units * u
                cx[i] = x + kw / 2f; cy[i] = y0 + rowH / 2f
                hw[i] = kw / 2f - gx; hh[i] = rowH / 2f - gy
                l[i] = x; r[i] = x + kw
                x += kw; i++
            }
            rowEnd[row] = i
            if (rowEnd[row] > rowStart[row]) { l[rowStart[row]] = -1e5f; r[rowEnd[row] - 1] = 1e5f }
        }
    }

    fun hit(x: Float, y: Float): Int {
        if (w <= 0f) return -1
        val row = ((y - top) / rowH).toInt().coerceIn(0, 3).let { if (y < top) 0 else it }
        for (i in rowStart[row] until rowEnd[row]) if (x >= l[i] && x < r[i]) return i
        return -1
    }
}

/** Every spring of the keyboard, stepped on its own frame clock (no recomposition per frame). */
private class KbMotion {
    val p = FloatArray(MAX_KEYS); val v = FloatArray(MAX_KEYS); val tgt = FloatArray(MAX_KEYS)
    var rise = 0f; var vRise = 0f; var riseT = 0f
    var drag = 0f; var vDrag = 0f; var dragging = false
    var bubKey = -1; var bubR = 0f; var vBubR = 0f; var bubRT = 0f
    var bubX = 0f; var bubY = 0f; var vBubX = 0f; var vBubY = 0f; var bubTx = 0f; var bubTy = 0f
    var bubHideAt = 0L
    var orgX = 0f; var orgY = 0f
    var reduced = false
    val frame = mutableIntStateOf(0)
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun kick() { wake.trySend(Unit) }

    fun busy(now: Long): Boolean {
        for (i in 0 until MAX_KEYS) if (abs(p[i] - tgt[i]) > 0.002f || abs(v[i]) > 0.02f) return true
        if (abs(rise - riseT) > 0.001f || abs(vRise) > 0.01f) return true
        if (dragging || abs(drag) > 0.3f || abs(vDrag) > 1f) return true
        if (bubKey >= 0 && (bubHideAt > 0L || abs(bubR - bubRT) > 0.002f || abs(vBubR) > 0.02f ||
                abs(bubX - bubTx) > 0.3f || abs(bubY - bubTy) > 0.3f)) return true
        return false
    }

    fun step(dt: Float, now: Long) {
        if (bubHideAt in 1..now) { bubHideAt = 0L; bubRT = 0f }
        if (reduced) {
            for (i in 0 until MAX_KEYS) { p[i] = tgt[i]; v[i] = 0f }
            rise = riseT; vRise = 0f
            if (!dragging) { drag = 0f; vDrag = 0f }
            bubR = bubRT; vBubR = 0f; bubX = bubTx; bubY = bubTy
        } else {
            val subs = ceil(dt / (1f / 240f)).toInt().coerceIn(1, 12)
            val h = dt / subs
            repeat(subs) {
                for (i in 0 until MAX_KEYS) {
                    // Down: a quick firm squash. Up: a looser spring that overshoots (the key pops back).
                    val down = tgt[i] > 0.5f
                    val k = if (down) 3000f else 1000f
                    val z = if (down) 0.8f else 0.36f
                    val a = k * (tgt[i] - p[i]) - 2f * z * sqrt(k) * v[i]
                    v[i] += a * h; p[i] += v[i] * h
                }
                run {
                    val k = if (riseT > 0.5f) 230f else 320f
                    val z = if (riseT > 0.5f) 0.8f else 1f
                    vRise += (k * (riseT - rise) - 2f * z * sqrt(k) * vRise) * h; rise += vRise * h
                }
                if (!dragging) { val k = 520f; vDrag += (k * (0f - drag) - 2f * 0.8f * sqrt(k) * vDrag) * h; drag += vDrag * h }
                if (bubKey >= 0) {
                    run { val k = 1300f; vBubR += (k * (bubRT - bubR) - 2f * 0.52f * sqrt(k) * vBubR) * h; bubR += vBubR * h }
                    val k = 2600f; val c = 2f * 0.78f * sqrt(k)
                    vBubX += (k * (bubTx - bubX) - c * vBubX) * h; bubX += vBubX * h
                    vBubY += (k * (bubTy - bubY) - c * vBubY) * h; bubY += vBubY * h
                }
            }
        }
        if (bubKey >= 0 && bubRT == 0f && bubR < 0.01f && bubHideAt == 0L) { bubKey = -1; bubR = 0f; vBubR = 0f }
        if (riseT == 0f && rise < 0.002f && abs(vRise) < 0.05f) { rise = 0f; vRise = 0f }
    }
}

private fun springsBubbleTo(m: KbMotion, g: KbGeo, i: Int, slide: Float) {
    val dp = g.dp
    val r = 27f * dp
    val tx = g.cx[i].coerceIn(r + 4f * dp, g.w - r - 4f * dp)
    val ty = g.cy[i] - g.hh[i] - r - 10f * dp
    if (m.bubKey < 0 || m.bubR < 0.3f) {
        // Pops out of the key: starts at its centre, small.
        m.bubX = g.cx[i]; m.bubY = g.cy[i]; m.vBubX = 0f; m.vBubY = 0f
        if (m.bubKey < 0) { m.bubR = 0.15f; m.vBubR = 0f }
    }
    m.bubKey = i; m.bubTx = tx; m.bubTy = ty; m.bubRT = 1f; m.bubHideAt = 0L
}

/** Is TalkBack (touch exploration) on? The glass keyboard steps aside for the system one then. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        val l = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}

/**
 * The glass keyboard. Call it on the underwater path of the All apps screen; it shows on the
 * WaterTop layer while [open] (and while it sinks away after). [alpha] (the page fade) and [shiftX]
 * (the page's slide, world px) are read every frame. Letters and space type on touch down (no lag,
 * two-thumb rollover just works); a swipe down that starts on a key takes the letter back and
 * dismisses the keyboard.
 */
@Composable
internal fun GlassKeyboard(
    host: WaterHost,
    open: Boolean,
    alpha: () -> Float,
    shiftX: () -> Float,
    onType: (Char) -> Unit,
    onUndoType: () -> Unit,
    onDelete: () -> Unit,
    onGo: () -> Unit,
    onVoice: (String) -> Unit,
    onSystemKeyboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val reduced = Q.reducedMotion
    val motion = remember { KbMotion() }
    motion.reduced = reduced || Build.VERSION.SDK_INT < 33

    val type by rememberUpdatedState(onType)
    val undo by rememberUpdatedState(onUndoType)
    val delete by rememberUpdatedState(onDelete)
    val go by rememberUpdatedState(onGo)
    val voiceDone by rememberUpdatedState(onVoice)
    val system by rememberUpdatedState(onSystemKeyboard)
    val dismiss by rememberUpdatedState(onDismiss)

    // Voice search (RecognizerIntent), only if something on the phone answers it.
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an app name")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val hasVoice = remember { runCatching { ctx.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { voiceDone(it) }
    }

    val keys = remember(hasVoice) { buildKeys(hasVoice) }
    val geo = remember(keys) { KbGeo(keys) }

    var alive by remember { mutableStateOf(false) }
    var wantArt by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) { alive = true; wantArt = true } }
    SideEffect {
        val t = if (open) 1f else 0f
        if (motion.riseT != t) {
            motion.riseT = t
            if (!open) { for (i in 0 until MAX_KEYS) motion.tgt[i] = 0f; motion.bubRT = 0f; motion.dragging = false }
            motion.kick()
        }
    }

    // The painting the keys look into: decoded once the keyboard is first wanted, like the backdrop's.
    val art by ctx.container.art.today.collectAsStateWithLifecycle()
    val bmp = rememberFileBitmap(if (wantArt) art?.imagePath else null, maxSide = 1400)

    // The frame clock: runs only while a spring moves, then waits for the next touch or change.
    LaunchedEffect(motion) {
        var last = 0L
        var prevRise = motion.rise
        while (true) {
            if (!motion.busy(SystemClock.uptimeMillis())) {
                last = 0L
                if (motion.riseT == 0f && motion.rise == 0f) alive = false
                motion.wake.receive()
                continue
            }
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 120f else ((t - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = t
                motion.step(dt, SystemClock.uptimeMillis())
                motion.frame.intValue++
            }
            // Out of the water: a ring of refraction and splashes along its top edge as it rises; a
            // splash as it sinks back.
            val g = geo
            if (g.w > 0f) {
                val topY = motion.orgY + g.top + (1f - motion.rise) * (g.rowH * 4f + 40f * g.dp)
                if (motion.riseT == 1f && prevRise < 0.42f && motion.rise >= 0.42f) {
                    host.ring(motion.orgX + g.w / 2f, topY, 0.9f)
                    host.ripple(motion.orgX + g.w * 0.22f, topY, 0.7f)
                    host.ripple(motion.orgX + g.w * 0.78f, topY, 0.7f)
                }
                if (motion.riseT == 0f && prevRise > 0.55f && motion.rise <= 0.55f) {
                    host.ripple(motion.orgX + g.w * 0.35f, topY, 0.6f)
                    host.ripple(motion.orgX + g.w * 0.68f, topY, 0.5f)
                }
            }
            prevRise = motion.rise
        }
    }

    if (!alive) return

    val scope = rememberCoroutineScope()
    val haptics = remember { Haptics.get(ctx) }
    val input = remember(geo) {
        KbInput(geo, motion, host, haptics,
            onKey = { k ->
                when (k.kind) {
                    KeyKind.Letter -> type(k.ch)
                    KeyKind.Space -> type(' ')
                    KeyKind.Back -> delete()
                    KeyKind.Go -> go()
                    KeyKind.Sys -> system()
                    KeyKind.Mic -> runCatching { voice.launch(voiceIntent) }.onFailure { haptics.play(Feel.Thud) }
                }
            },
            onUndo = { undo() },
            onDismiss = { dismiss() },
        )
    }
    input.scope = scope

    val c = Q.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = QType.body(21.sp).copy(color = c.ink)
    val bubStyle = QType.body(32.sp).copy(color = c.ink)
    val labels = remember(keys, measurer) {
        Array(keys.size) { i -> if (keys[i].kind == KeyKind.Letter) measurer.measure(keys[i].ch.toString(), labelStyle) else null }
    }
    val bubLabels = remember(keys, measurer) {
        Array(keys.size) { i -> if (keys[i].kind == KeyKind.Letter) measurer.measure(keys[i].ch.toString(), bubStyle) else null }
    }
    val gpu = remember { if (Build.VERSION.SDK_INT >= 33) KeysGpu.create() else null }
    val artShader = remember(bmp) { bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }

    OnWaterTop("glassKeyboard", z = 40) {
        val density = LocalDensity.current
        val nav = WindowInsets.navigationBars.getBottom(density)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dp = density.density
            geo.layout(constraints.maxWidth.toFloat(), dp)
            val hPx = GlassKeyboardHeight.value * dp + nav
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(with(density) { hPx.toDp() })
                    .onGloballyPositioned { k -> val o = host.toWorld(k); motion.orgX = o.x; motion.orgY = o.y }
                    .pointerInput(input) { input.run(this) }
                    .drawBehind {
                        host.tick
                        motion.frame.intValue
                        drawKeyboard(host, geo, motion, gpu, artShader, bmp, labels, bubLabels, c.ink, alpha(), shiftX())
                    },
            ) {
                // One node per key for accessibility services (Switch Access, Voice Access); TalkBack
                // users get the system keyboard instead (see AppsScreen).
                for (i in keys.indices) {
                    val k = keys[i]
                    Box(
                        Modifier
                            .layout { m, _ ->
                                val w = (geo.hw[i] * 2f).toInt().coerceAtLeast(1); val h = (geo.hh[i] * 2f).toInt().coerceAtLeast(1)
                                val pl = m.measure(Constraints.fixed(w, h))
                                layout(w, h) { pl.place((geo.cx[i] - geo.hw[i]).toInt(), (geo.cy[i] - geo.hh[i]).toInt()) }
                            }
                            .clearAndSetSemantics {
                                contentDescription = k.desc
                                role = Role.Button
                                onClick { input.fire(i); true }
                            },
                    )
                }
            }
        }
    }
}

/**
 * What the search pill shows while the glass keyboard types into it (no text field, so the system
 * keyboard never opens): the query and a blinking caret while the keyboard is up.
 */
@Composable
internal fun GlassKeysField(query: String, open: Boolean, style: androidx.compose.ui.text.TextStyle, caret: Color) {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(open, query) {
        on.value = true
        if (open) while (true) { delay(530); on.value = !on.value }
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicText(
            query, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            Modifier
                .padding(start = 1.dp)
                .size(2.dp, 22.dp)
                .drawBehind { if (open && on.value) drawRect(caret) },
        )
    }
}

// ---------------------------------------------------------------- touch

private class KbInput(
    val geo: KbGeo, val m: KbMotion, val host: WaterHost, val haptics: Haptics,
    val onKey: (GKey) -> Unit, val onUndo: () -> Unit, val onDismiss: () -> Unit,
) {
    var scope: kotlinx.coroutines.CoroutineScope? = null
    private val ids = LongArray(6) { -1L }
    private val key = IntArray(6) { -1 }
    private val downX = FloatArray(6); private val downY = FloatArray(6)
    private val typed = BooleanArray(6)
    private var dragSlot = -1
    private var lastY = 0f; private var lastT = 0L; private var vy = 0f
    private var repeatJob: Job? = null
    private var lastFeelMs = 0L
    private var lastBackFeelMs = 0L

    private fun slide(): Float = (1f - m.rise) * (geo.rowH * 4f + 40f * geo.dp) + m.drag

    /** Haptics, rate-limited on top of the engine's own limits. */
    private fun feel(kind: KeyKind) {
        val now = SystemClock.uptimeMillis()
        when (kind) {
            KeyKind.Back -> { if (now - lastBackFeelMs < 75L) return; lastBackFeelMs = now; haptics.play(Feel.Tick) }
            KeyKind.Space -> { if (now - lastFeelMs < 45L) return; lastFeelMs = now; host.feel(Feel.Drip) }
            else -> { if (now - lastFeelMs < 45L) return; lastFeelMs = now; host.feel(Feel.Clink) }
        }
    }

    private fun press(i: Int) {
        m.tgt[i] = 1f
        val k = geo.keys[i]
        feel(k.kind)
        // A small ripple into the water under the key.
        host.ripple(m.orgX + geo.cx[i], m.orgY + geo.cy[i] + slide(), if (k.kind == KeyKind.Space) 0.45f else 0.3f)
        if (k.kind == KeyKind.Letter) springsBubbleTo(m, geo, i, slide())
        m.kick()
    }

    private fun release(i: Int) {
        m.tgt[i] = 0f
        if (m.bubKey == i) m.bubHideAt = SystemClock.uptimeMillis() + 70L
        m.kick()
    }

    /** An accessibility click: press, act, release. */
    fun fire(i: Int) {
        if (i !in 0 until geo.n) return
        press(i); onKey(geo.keys[i]); release(i)
    }

    private fun startRepeat() {
        repeatJob?.cancel()
        repeatJob = scope?.launch {
            delay(420)
            var n = 0
            while (true) {
                onKey(geo.keys.first { it.kind == KeyKind.Back })
                feel(KeyKind.Back)
                delay(if (n < 6) 85L else if (n < 18) 60L else 42L)
                n++
            }
        }
    }

    suspend fun run(scope: androidx.compose.ui.input.pointer.PointerInputScope) = scope.awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            for (ch in ev.changes) {
                val id = ch.id.value
                if (ch.pressed && !ch.previousPressed) {
                    ch.consume(); down(id, ch.position.x, ch.position.y, ch.uptimeMillis)
                } else if (!ch.pressed && ch.previousPressed) {
                    ch.consume(); up(id, ch.position.x, ch.position.y)
                } else if (ch.pressed) {
                    if (ch.position != ch.previousPosition) { ch.consume(); move(id, ch.position.x, ch.position.y, ch.uptimeMillis) }
                }
            }
        }
    }

    private fun slotOf(id: Long): Int { for (s in ids.indices) if (ids[s] == id) return s; return -1 }

    private fun down(id: Long, x: Float, y: Float, t: Long) {
        if (m.riseT == 0f || dragSlot >= 0) return
        val s = ids.indexOfFirst { it == -1L }.takeIf { it >= 0 } ?: return
        val i = geo.hit(x, y - slide())
        if (i < 0) return
        ids[s] = id; key[s] = i; downX[s] = x; downY[s] = y; typed[s] = false
        lastY = y; lastT = t; vy = 0f
        press(i)
        val k = geo.keys[i]
        when (k.kind) {
            KeyKind.Letter, KeyKind.Space -> { onKey(k); typed[s] = true }
            KeyKind.Back -> { onKey(k); startRepeat() }
            else -> {}
        }
    }

    private fun move(id: Long, x: Float, y: Float, t: Long) {
        val s = slotOf(id); if (s < 0) return
        if (t > lastT) { val nv = (y - lastY) / (t - lastT); vy = vy * 0.4f + nv * 0.6f; lastY = y; lastT = t }
        val slop = 12f * geo.dp
        val dy = y - downY[s]; val dx = x - downX[s]
        if (dragSlot < 0) {
            val others = ids.count { it != -1L } > 1
            if (!others && dy > slop && dy > 1.3f * abs(dx)) {
                // A swipe down: the key it started on lets go (its letter is taken back), the board follows.
                dragSlot = s; m.dragging = true
                repeatJob?.cancel(); repeatJob = null
                if (key[s] >= 0) { release(key[s]); m.bubRT = 0f; m.bubHideAt = 0L }
                if (typed[s]) { onUndo(); typed[s] = false }
            }
        }
        if (dragSlot == s) {
            val d = dy - slop
            m.drag = if (d >= 0f) d else d * 0.25f
            m.kick()
        }
    }

    private fun up(id: Long, x: Float, y: Float) {
        val s = slotOf(id); if (s < 0) return
        val i = key[s]
        ids[s] = -1L; key[s] = -1
        if (dragSlot == s) {
            dragSlot = -1; m.dragging = false
            val travel = geo.rowH * 4f + 40f * geo.dp
            if (m.drag > 56f * geo.dp || vy > 0.9f * geo.dp) {
                // Hand the drag to the sink.
                m.rise = (1f - m.drag / travel).coerceIn(0f, 1f)
                m.vRise = -(vy * 1000f / travel).coerceAtLeast(0f)
                m.drag = 0f; m.vDrag = 0f
                onDismiss()
            }
            m.kick()
            return
        }
        if (i < 0) return
        release(i)
        val k = geo.keys[i]
        when (k.kind) {
            KeyKind.Back -> { repeatJob?.cancel(); repeatJob = null }
            KeyKind.Mic, KeyKind.Sys, KeyKind.Go -> if (geo.hit(x, y - slide()) == i) onKey(k)
            else -> {}
        }
    }
}

// ---------------------------------------------------------------- drawing

@androidx.annotation.RequiresApi(33)
private class KeysGpu(val keys: RuntimeShader, val bub: RuntimeShader) {
    val keysBrush = ShaderBrush(keys)
    val bubBrush = ShaderBrush(bub)
    val kr = FloatArray(MAX_KEYS * 4)
    val kc = FloatArray(MAX_KEYS * 4)
    val line = FloatArray(16)
    var none: Shader? = null

    companion object {
        fun create(): KeysGpu? = try {
            KeysGpu(RuntimeShader(KEYS_SRC), RuntimeShader(KEYS_SRC))
        } catch (e: RuntimeException) { Log.w(TAG, "keys shader rejected", e); null }
    }
}

private val gpuBroken = booleanArrayOf(false)

@android.annotation.SuppressLint("NewApi")
private fun DrawScope.drawKeyboard(
    host: WaterHost, geo: KbGeo, m: KbMotion, gpuAny: Any?, art: Shader?, bmp: Bitmap?,
    labels: Array<TextLayoutResult?>, bubLabels: Array<TextLayoutResult?>, ink: Color, pageAlpha: Float, shiftX: Float,
) {
    if (geo.w <= 0f) return
    val dp = geo.dp
    val travel = geo.rowH * 4f + 40f * dp
    val slide = (1f - m.rise) * travel + m.drag
    // Fades in through the first part of the rise (it surfaces), out as it sinks.
    val a = (pageAlpha * smooth(((m.rise - 0.05f) / 0.45f).coerceIn(0f, 1f))).coerceIn(0f, 1f)
    if (a <= 0.003f) return
    val n = geo.n

    val gpu = if (Build.VERSION.SDK_INT >= 33 && !gpuBroken[0]) gpuAny as? KeysGpu else null
    if (gpu != null) {
        try {
            for (i in 0 until MAX_KEYS) {
                val o = i * 4
                if (i < n) {
                    val pc = m.p[i].coerceIn(-0.5f, 1.3f)
                    gpu.kr[o] = geo.cx[i] + shiftX; gpu.kr[o + 1] = geo.cy[i] + slide + 2.2f * dp * pc
                    gpu.kr[o + 2] = geo.hw[i] * (1f - 0.05f * pc); gpu.kr[o + 3] = geo.hh[i] * (1f - 0.08f * pc)
                    gpu.kc[o] = geo.l[i] + shiftX; gpu.kc[o + 1] = geo.r[i] + shiftX; gpu.kc[o + 2] = pc
                    gpu.kc[o + 3] = geo.keys[i].row * 4f + if (geo.keys[i].kind == KeyKind.Letter) 0f else 1f
                } else { gpu.kr[o + 2] = 0f; gpu.kc[o] = 0f; gpu.kc[o + 1] = 0f; gpu.kc[o + 3] = -8f }
            }
            val hw = if (host.w > 0f) host.w else size.width
            val hh = if (host.h > 0f) host.h else size.height
            for (k in 0 until 16) gpu.line[k] = host.waterY(hw * k / 15f)
            val child = art ?: gpu.none ?: BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { gpu.none = it }
            var s = 1f; var tx = 0f; var ty = 0f
            if (bmp != null && art != null) {
                s = 1.06f * max(hw / bmp.width, hh / bmp.height)
                tx = hw / 2f + host.parX * 13f * dp - s * bmp.width / 2f
                ty = hh / 2f + host.parY * 13f * dp - s * bmp.height / 2f
            }
            fun RuntimeShader.common() {
                setInputShader("uArt", child)
                setFloatUniform("uHasArt", if (art != null) 1f else 0f)
                setFloatUniform("uArtM", s, tx, ty)
                setFloatUniform("uOrg", m.orgX, m.orgY)
                setFloatUniform("uRes", hw, hh)
                setFloatUniform("uDp", dp)
                setFloatUniform("uLine", gpu.line)
                setFloatUniform("uDk", host.pDk); setFloatUniform("uLt", host.pLt); setFloatUniform("uMd", host.pMd)
                setFloatUniform("uSheen", host.pSheen)
                setFloatUniform("uRowGeo", geo.top + slide, geo.rowH)
                setFloatUniform("uN", n.toFloat())
                setFloatUniform("uKR", gpu.kr); setFloatUniform("uKC", gpu.kc)
                setFloatUniform("uBevel", 9.5f * dp); setFloatUniform("uCorner", 12f * dp)
                setFloatUniform("uAlpha", a)
            }
            gpu.keys.common()
            gpu.keys.setFloatUniform("uMode", 0f)
            gpu.keys.setFloatUniform("uBub", 0f, 0f, 0f, 0f)
            gpu.keys.setFloatUniform("uBubKey", 0f, 0f, 0f, 0f)
            val top = max(slide + geo.top - 4f * dp, -40f * dp)
            if (top < size.height) drawRect(gpu.keysBrush, Offset(0f, top), Size(size.width, size.height - top))
        } catch (e: RuntimeException) {
            Log.w(TAG, "keys shader failed; flat keys", e); gpuBroken[0] = true
        }
    }
    if (gpu == null || gpuBroken[0]) {
        for (i in 0 until n) {
            val pc = m.p[i].coerceIn(-0.5f, 1.3f)
            val hw = geo.hw[i] * (1f - 0.05f * pc); val hh = geo.hh[i] * (1f - 0.08f * pc)
            val cx = geo.cx[i] + shiftX; val cy = geo.cy[i] + slide + 2.2f * dp * pc
            drawRoundRect(Color.White.copy(alpha = 0.10f * a), Offset(cx - hw, cy - hh), Size(2 * hw, 2 * hh), CornerRadius(12f * dp))
        }
    }

    // Labels ride their keys (sink and squash with the press).
    val inkA = ink.copy(alpha = ink.alpha * a * 0.94f)
    for (i in 0 until n) {
        val pc = m.p[i].coerceIn(-0.5f, 1.3f)
        val cx = geo.cx[i] + shiftX; val cy = geo.cy[i] + slide + 2.2f * dp * pc
        if (cy - geo.hh[i] > size.height) continue
        val sc = 1f - 0.06f * pc
        val k = geo.keys[i]
        val lay = labels[i]
        withTransform({ scale(sc, sc, Offset(cx, cy)) }) {
            if (lay != null) drawText(lay, inkA, Offset(cx - lay.size.width / 2f, cy - lay.size.height / 2f))
            else drawIcon(k.kind, cx, cy, dp, ink.copy(alpha = ink.alpha * a * 0.82f))
        }
    }

    // The preview bead above the finger.
    val b = m.bubKey
    if (b in 0 until n && m.bubR > 0.01f) {
        val r = 27f * dp * m.bubR.coerceAtLeast(0f)
        val bx = m.bubX + shiftX; val by = m.bubY + slide
        if (gpu != null && !gpuBroken[0]) {
            try {
                val o = b * 4
                gpu.bub.setInputShader("uArt", art ?: gpu.none!!)
                gpu.bub.setFloatUniform("uHasArt", if (art != null) 1f else 0f)
                // (the rest of the shared uniforms were set on the keys shader; set them here too)
                val hw = if (host.w > 0f) host.w else size.width
                val hh = if (host.h > 0f) host.h else size.height
                var s = 1f; var tx = 0f; var ty = 0f
                if (bmp != null && art != null) {
                    s = 1.06f * max(hw / bmp.width, hh / bmp.height)
                    tx = hw / 2f + host.parX * 13f * dp - s * bmp.width / 2f
                    ty = hh / 2f + host.parY * 13f * dp - s * bmp.height / 2f
                }
                gpu.bub.setFloatUniform("uArtM", s, tx, ty)
                gpu.bub.setFloatUniform("uOrg", m.orgX, m.orgY)
                gpu.bub.setFloatUniform("uRes", hw, hh)
                gpu.bub.setFloatUniform("uDp", dp)
                gpu.bub.setFloatUniform("uLine", gpu.line)
                gpu.bub.setFloatUniform("uDk", host.pDk); gpu.bub.setFloatUniform("uLt", host.pLt); gpu.bub.setFloatUniform("uMd", host.pMd)
                gpu.bub.setFloatUniform("uSheen", host.pSheen)
                gpu.bub.setFloatUniform("uRowGeo", geo.top + slide, geo.rowH)
                gpu.bub.setFloatUniform("uN", 0f)
                gpu.bub.setFloatUniform("uKR", gpu.kr); gpu.bub.setFloatUniform("uKC", gpu.kc)
                gpu.bub.setFloatUniform("uBevel", 12f * dp); gpu.bub.setFloatUniform("uCorner", 12f * dp)
                gpu.bub.setFloatUniform("uAlpha", a)
                gpu.bub.setFloatUniform("uMode", 1f)
                gpu.bub.setFloatUniform("uBub", bx, by, r, a * m.bubR.coerceIn(0f, 1f))
                gpu.bub.setFloatUniform("uBubKey", gpu.kr[o], gpu.kr[o + 1], gpu.kr[o + 2], gpu.kr[o + 3])
                val pad = 34f * dp
                val x0 = min(bx - r, gpu.kr[o] - gpu.kr[o + 2]) - pad
                val x1 = max(bx + r, gpu.kr[o] + gpu.kr[o + 2]) + pad
                val y0 = by - r - pad
                val y1 = max(by + r, gpu.kr[o + 1] + gpu.kr[o + 3]) + pad
                drawRect(gpu.bubBrush, Offset(x0, y0), Size(x1 - x0, y1 - y0))
            } catch (e: RuntimeException) {
                Log.w(TAG, "bead failed", e); gpuBroken[0] = true
            }
        } else {
            drawCircle(Color.White.copy(alpha = 0.14f * a), r, Offset(bx, by))
        }
        val lay = bubLabels[b]
        if (lay != null) {
            val sc = m.bubR.coerceIn(0f, 1.2f)
            withTransform({ scale(sc, sc, Offset(bx, by)) }) {
                drawText(lay, ink.copy(alpha = ink.alpha * a * m.bubR.coerceIn(0f, 1f)), Offset(bx - lay.size.width / 2f, by - lay.size.height / 2f))
            }
        }
    }
}

/** Thin line icons for the function keys (1.6 dp strokes, ~20 dp boxes). */
private fun DrawScope.drawIcon(kind: KeyKind, x: Float, y: Float, dp: Float, col: Color) {
    val st = Stroke(width = 1.6f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        KeyKind.Back -> {
            val p = Path().apply {
                moveTo(x - 10f * dp, y); lineTo(x - 4.5f * dp, y - 6.5f * dp); lineTo(x + 9f * dp, y - 6.5f * dp)
                lineTo(x + 9f * dp, y + 6.5f * dp); lineTo(x - 4.5f * dp, y + 6.5f * dp); close()
            }
            drawPath(p, col, style = st)
            drawLine(col, Offset(x - 0.5f * dp, y - 3f * dp), Offset(x + 5.5f * dp, y + 3f * dp), st.width, StrokeCap.Round)
            drawLine(col, Offset(x + 5.5f * dp, y - 3f * dp), Offset(x - 0.5f * dp, y + 3f * dp), st.width, StrokeCap.Round)
        }
        KeyKind.Mic -> {
            drawRoundRect(col, Offset(x - 3.5f * dp, y - 10f * dp), Size(7f * dp, 12.5f * dp), CornerRadius(3.5f * dp), style = st)
            drawArc(col, 0f, 180f, false, Offset(x - 7f * dp, y - 6.5f * dp), Size(14f * dp, 12f * dp), style = st)
            drawLine(col, Offset(x, y + 5.5f * dp), Offset(x, y + 9f * dp), st.width, StrokeCap.Round)
            drawLine(col, Offset(x - 3.5f * dp, y + 9f * dp), Offset(x + 3.5f * dp, y + 9f * dp), st.width, StrokeCap.Round)
        }
        KeyKind.Sys -> {
            drawRoundRect(col, Offset(x - 10.5f * dp, y - 7f * dp), Size(21f * dp, 14f * dp), CornerRadius(2.5f * dp), style = st)
            for (rr in 0 until 2) for (cc in 0 until 4) {
                drawCircle(col, 0.95f * dp, Offset(x - 6f * dp + cc * 4f * dp, y - 3f * dp + rr * 3.5f * dp))
            }
            drawLine(col, Offset(x - 4f * dp, y + 4f * dp), Offset(x + 4f * dp, y + 4f * dp), st.width, StrokeCap.Round)
        }
        KeyKind.Go -> {
            drawLine(col, Offset(x - 8f * dp, y), Offset(x + 7f * dp, y), st.width, StrokeCap.Round)
            val p = Path().apply { moveTo(x + 1.5f * dp, y - 5.5f * dp); lineTo(x + 7.5f * dp, y); lineTo(x + 1.5f * dp, y + 5.5f * dp) }
            drawPath(p, col, style = st)
        }
        else -> {}
    }
}

/**
 * The key domes and the preview bead in one pass (AGSL, API 33+). Local px of the keyboard box;
 * uOrg turns them into world px for the backdrop and the waterline.
 *  uKR[i] = animated centre + half size; uKC[i] = touch cell left/right, press, row*4 + (function key).
 *  uMode 0: the keys (each pixel shades only its own key, plus the shadow of the key above it).
 *  uMode 1: the bead uBub (centre, radius, alpha), fused by a smooth-min neck to its key uBubKey.
 */
private const val KEYS_SRC = """
uniform shader uArt;
uniform float uHasArt;
uniform float3 uArtM;
uniform float2 uOrg;
uniform float2 uRes;
uniform float uDp;
uniform float uLine[16];
uniform float3 uDk;
uniform float3 uMd;
uniform float3 uLt;
uniform float3 uSheen;
uniform float2 uRowGeo;
uniform float uN;
uniform float4 uKR[32];
uniform float4 uKC[32];
uniform float uBevel;
uniform float uCorner;
uniform float uAlpha;
uniform float uMode;
uniform float4 uBub;
uniform float4 uBubKey;

float lum(float3 c){ return dot(c,float3(.299,.587,.114)); }
float sdRR(float2 q,float2 b,float r){ float2 v=abs(q)-b+r; return length(max(v,0.))+min(max(v.x,v.y),0.)-r; }
float smin(float a,float b,float k){ float h=clamp(.5+.5*(b-a)/k,0.,1.); return mix(b,a,h)-k*h*(1.-h); }
float surfY(float xw){
  float t=clamp(xw/uRes.x,0.,1.)*15.;
  float y=uLine[0];
  for(int k=0;k<15;k++){ if(t>=float(k)) y=mix(uLine[k],uLine[k+1],clamp(t-float(k),0.,1.)); }
  return y;
}
// The backdrop's veil (WaterBackdrop's vertical gradient of black).
float veil(float yw){
  float t=clamp(yw/uRes.y,0.,1.);
  float a=t<.22 ? mix(.8,.54,t/.22) : (t<.5 ? mix(.54,.4,(t-.22)/.28) : mix(.4,.62,(t-.5)/.5));
  return 1.-a;
}
float3 art(float2 pw){
  pw=clamp(pw,float2(.5),uRes-.5);
  float3 c = uHasArt>.5 ? uArt.eval((pw-uArtM.yz)/uArtM.x).rgb : mix(uDk,float3(0.),.35);
  return c*veil(pw.y);
}
// The engine's depth absorption, tinted by the painting.
float3 absorb(float3 w,float d,float amt){
  float dz=max(d,0.)/uRes.y;
  float3 ab=exp(-dz*float3(2.1,1.05,.7));
  float3 water=mix(uDk,uMd,.45);
  float3 t=w*mix(float3(1.),ab,.8)+water*(1.-ab.g)*.42;
  float keep=smoothstep(.45,.85,lum(w))*.6;
  return mix(w,mix(t,w,keep),amt);
}
// One glass dome (premultiplied): gd the SDF (<0 inside), gn its normal, ctr the lens centre.
half4 shade(float2 xy,float gd,float2 gn,float2 ctr,float lens,float press,float smoke,float a){
  float dp=uDp;
  float2 pw=xy+uOrg;
  float d=pw.y-surfY(pw.x);
  float pr=clamp(press,0.,1.);
  half4 o=half4(0.);
  // contact shadow and the caustic line on the water under the glass
  if(gd>0. && gd<30.*dp){
    float below=clamp(gn.y*.7+.45,0.,1.);
    float under=smoothstep(-2.,2.,d);
    float s=exp(-gd/(9.*dp))*(.14+.12*under)*below*a*(1.-.55*pr);
    float ce=(gd-5.*dp)/(2.4*dp);
    float3 add=uSheen*exp(-ce*ce*.5)*.2*below*under*a*(1.-.4*pr);
    o=half4(half3(add),half(s));
  }
  if(gd<1.5){
    float bev=max(uBevel,1.);
    float s=clamp(-gd/bev,0.,1.);
    float hgt=sqrt(1.-(1.-s)*(1.-s));
    float3 n=normalize(float3(gn*(1.-hgt),hgt+.04));
    float2 off=-n.xy*(7.+lens*18.)*dp*(.35+.65*hgt)*(1.+.5*pr)-(xy-ctr)*lens*.42*hgt;
    float2 q=pw+off;
    float3 bc=float3(art(q).r, art(q+off*.08).g, art(q+off*.17).b);
    float dq=d+off.y;
    if(dq>0.) bc=absorb(bc,dq,.65);
    bc*=smoke*(1.-.12*pr);
    float3 gc=bc*.97+uLt*.022;
    gc+=pow(1.-n.z,3.)*mix(float3(1.),uLt,.4)*.55;
    float3 Hg=normalize(float3(-.45,-.6,.65)+float3(0.,0.,1.));
    gc+=pow(max(dot(n,Hg),0.),90.)*1.3;
    gc+=pow(max(dot(n,normalize(float3(.5,.55,.7)+float3(0.,0.,1.))),0.),30.)*.16;
    float rim=1.-smoothstep(0.,1.1*dp,abs(gd+1.2*dp));
    gc+=rim*(.16+.18*clamp(-gn.y,0.,1.)+.1*pr)*mix(uLt,float3(1.),.5);
    gc+=uSheen*.05*pr;
    float ins=(1.-smoothstep(-.75,.75,gd))*clamp(a*1.4,0.,1.);
    o=half4(half3(gc*ins)+o.rgb*half(1.-ins), half(ins)+o.a*half(1.-ins));
  }
  return o;
}
half4 main(float2 xy){
  float dp=uDp;
  if(uMode<.5){
    float row=clamp(floor((xy.y-uRowGeo.x)/uRowGeo.y),0.,3.);
    float4 A=float4(0.); float4 C=float4(0.); float f1=0.;
    float4 U=float4(0.); float f2=0.;
    for(int i=0;i<32;i++){
      if(float(i)>=uN) break;
      float4 c=uKC[i];
      if(xy.x>=c.x && xy.x<c.y){
        float ro=floor(c.w/4.);
        if(ro==row){ A=uKR[i]; C=c; f1=1.; }
        else if(ro==row-1.){ U=uKR[i]; f2=1.; }
      }
    }
    // The key above throws its shadow down into this cell.
    float sU=0.;
    if(f2>0. && xy.y>U.y){
      float r2=min(uCorner,min(U.z,U.w));
      float du=sdRR(xy-U.xy,U.zw,r2);
      if(du>0.) sU=exp(-du/(9.*dp))*.2*uAlpha;
    }
    if(f1<.5) return half4(0.,0.,0.,half(sU));
    float r=min(uCorner,min(A.z,A.w));
    float2 v=xy-A.xy;
    float g0=sdRR(v,A.zw,r);
    if(g0>30.*dp) return half4(0.,0.,0.,half(sU));
    float gx=sdRR(v+float2(1.,0.),A.zw,r);
    float gy=sdRR(v+float2(0.,1.),A.zw,r);
    float2 gg=float2(gx-g0,gy-g0); float2 gn=gg/(length(gg)+1e-5);
    float fk=C.w-floor(C.w/4.)*4.;
    half4 o=shade(xy,g0,gn,A.xy,.12,C.z,fk>.5?.66:.8,uAlpha);
    o.a=o.a+half(sU)*(1.-o.a);
    return o;
  }
  // the preview bead, fused to its key while it pops out
  float k=14.*dp;
  float rk=min(uCorner,min(uBubKey.z,uBubKey.w));
  float2 v=xy-uBub.xy;
  float db=length(v)-uBub.z;
  float db1=length(v+float2(1.,0.))-uBub.z;
  float db2=length(v+float2(0.,1.))-uBub.z;
  float dk=1e4; float dk1=1e4; float dk2=1e4;
  if(uBubKey.z>0.){
    float2 w=xy-uBubKey.xy;
    dk=sdRR(w,uBubKey.zw,rk); dk1=sdRR(w+float2(1.,0.),uBubKey.zw,rk); dk2=sdRR(w+float2(0.,1.),uBubKey.zw,rk);
  }
  float f0=smin(db,dk,k);
  float2 gg=float2(smin(db1,dk1,k)-f0, smin(db2,dk2,k)-f0);
  float2 gn=gg/(length(gg)+1e-5);
  // 1 at the bead, 0 at the key (the key itself is drawn by the keys pass)
  float wb=clamp(.5+.5*(dk-db)/k,0.,1.);
  float show=smoothstep(.05,.55,wb);
  if(show<=0.) return half4(0.);
  half4 o=shade(xy,f0,gn,uBub.xy,mix(.12,.9,wb),0.,mix(.8,.76,wb),uBub.w);
  return o*half(show);
}
"""
