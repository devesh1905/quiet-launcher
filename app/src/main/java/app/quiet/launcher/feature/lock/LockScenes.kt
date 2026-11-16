package app.quiet.launcher.feature.lock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import app.quiet.launcher.designsystem.shader.AmbientField
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.quiet.launcher.core.model.Artwork
import app.quiet.launcher.core.platform.Astronomy
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.*
import app.quiet.launcher.designsystem.shader.ShaderCanvas
import app.quiet.launcher.designsystem.shader.Shaders
import app.quiet.launcher.designsystem.shader.ShadersSupported
import app.quiet.launcher.designsystem.shader.rememberShaderTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/** Space kept clear at the bottom for the unlock hint and the fingerprint ring over the sensor. */
val LockBottomReserve = 212.dp

/** Space the system lock-screen clock needs when we are its wallpaper. */
val WallpaperClockSpace = 250.dp

/** Everything a lock scene may need, gathered once by the lock root. */
data class LockContext(
    val face: LockFace,
    val minute: Long,
    val is24h: Boolean,
    val usageMs: Long,
    val oneThing: String,
    val art: Artwork?,
    val lat: Double,
    val lon: Double,
    val city: String,
    val animate: Boolean,
    /** Rendering for the system lock-screen wallpaper: no clock of ours, top kept clear for the system's. */
    val wallpaper: Boolean = false,
    /** Space kept clear above the bottom edge for the fingerprint ring (measured from the sensor). */
    val bottomReserve: androidx.compose.ui.unit.Dp = LockBottomReserve,
)

// ---------- shared pieces ----------

@Composable
fun LockClock(ctx: LockContext, modifier: Modifier = Modifier, scale: Float = 1f, tint: Color? = null) {
    val c = Q.colors
    val ink = tint ?: c.ink
    if (ctx.wallpaper) { Spacer(modifier.height(WallpaperClockSpace)); return }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            Format.clock(ctx.minute, ctx.is24h),
            style = TextStyle(
                fontFamily = ctx.face.display, fontSize = (ctx.face.clockSp * scale).sp, color = ink,
                fontFeatureSettings = "lnum, tnum", letterSpacing = (-0.01).em,
            ),
        )
        BasicText(
            Format.longDate(ctx.minute),
            style = QType.body(16.sp).copy(color = ink.copy(alpha = 0.86f)),
        )
    }
}

@Composable
fun LockLabel(ctx: LockContext, title: String, line2: String, line3: String?, modifier: Modifier = Modifier) {
    MuseumLabel(
        title = title, line2 = line2, line3 = line3, modifier = modifier,
        titleStyle = TextStyle(
            fontFamily = ctx.face.label, fontStyle = if (ctx.face.labelItalic) FontStyle.Italic else FontStyle.Normal,
            fontSize = 24.sp, lineHeight = 28.sp,
        ),
    )
}

/** Decode a cached image file once, downsampled to about [maxSide]. */
@Composable
fun rememberFileBitmap(path: String?, maxSide: Int = 1600): Bitmap? {
    val state = produceState<Bitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    return state.value
}

// ---------- 1. Daily masterpiece ----------

/**
 * The painting hung on a black wall under a picture light: a thin dark wood frame, an ivory mat,
 * the work at its true aspect ratio, and the museum label under it.
 */
@Composable
fun MasterpieceScene(ctx: LockContext, modifier: Modifier = Modifier, onArtLongPress: (() -> Unit)? = null, heart: Int = 0) {
    val c = Q.colors
    val art = ctx.art
    val bmp = rememberFileBitmap(art?.imagePath)
    // The painting's own colours, for the field that glows around the frame, the clock and the sheen.
    // Worked out once per painting, off the main thread, and kept while the painting stays.
    val palette by produceState(PaintingPaletteCache.get(art?.imagePath), bmp) {
        val path = art?.imagePath
        val b = bmp ?: return@produceState
        value = PaintingPaletteCache.get(path) ?: withContext(Dispatchers.Default) { paintingPalette(b) }.also { PaintingPaletteCache.put(path, it) }
    }
    // Swatch 3 is the light vibrant one; kept at 9:1 or more on the wall.
    val clockTint = palette?.let { Color(PaintingTint.clock(it[3], c.ink.toArgb(), c.bg.toArgb())) } ?: c.ink
    val tint by animateColorAsState(clockTint, androidx.compose.animation.core.tween(if (Q.reducedMotion) 0 else 900), label = "tint")
    val sheen = palette?.let { Color(PaintingTint.sheen(it[3])) } ?: Color.White
    var root by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var frameRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(
        modifier.fillMaxSize().background(c.bg)
            .onGloballyPositioned { root = it }
    ) {
        AmbientField(
            palette = palette, artRect = frameRect, modifier = Modifier.fillMaxSize(),
            maxLum = 0.026f, glow = 1.2f, pool = 0.62f, strength = 1f, running = ctx.animate,
        )
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            LockClock(ctx, tint = tint)
            Spacer(Modifier.weight(0.5f))
            if (bmp != null) {
                val aspect = bmp.width.toFloat() / bmp.height
                BoxWithConstraints(Modifier.fillMaxWidth().weight(3f), contentAlignment = Alignment.Center) {
                    // Fit the framed work in the slot at its own aspect ratio.
                    val maxW = maxWidth
                    val maxH = maxHeight
                    val w = if (maxW / aspect > maxH) maxH * aspect else maxW
                    val h = w / aspect
                    Box(
                        Modifier.size(w, h)
                            .onGloballyPositioned { coords ->
                                val r = root ?: return@onGloballyPositioned
                                val b = coords.boundsInRoot(); val rb = r.boundsInRoot()
                                frameRect = androidx.compose.ui.geometry.Rect(b.left - rb.left, b.top - rb.top, b.right - rb.left, b.bottom - rb.top)
                            }
                            .frameLightSweep(ctx.animate, art?.id, sheen)
                            .then(if (onArtLongPress != null) Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                                    if (up == null) onArtLongPress()
                                }
                            } else Modifier)
                            .shadow(30.dp, RectangleShape, ambientColor = Color.Black, spotColor = Color.Black)
                            .background(c.wood)
                            .padding(5.dp)
                            .background(c.matBig)
                            .padding(w * 0.06f),
                    ) {
                        // A new painting fades in over the old one.
                        androidx.compose.animation.Crossfade(targetState = bmp, animationSpec = androidx.compose.animation.core.tween(700), label = "painting") { b ->
                            val img = remember(b) { b.asImageBitmap() }
                            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        FavouriteHeart(heart, Modifier.align(Alignment.Center))
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(3f), contentAlignment = Alignment.Center) {
                    BasicText("Today’s painting is on its way.", style = QType.secondary().copy(color = c.muted))
                }
            }
            Spacer(Modifier.height(28.dp))
            if (art != null) {
                LockLabel(
                    ctx, art.title, art.artist,
                    listOfNotNull(art.year, art.museum).joinToString(", ").ifBlank { null },
                    Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(LockBottomReserve))
        }
    }
}

// ---------- 2. Quiet canvas ----------

fun calmFor(usageMs: Long): Float = (1f - (usageMs / 60_000f) / 240f).coerceIn(0f, 1f)

@Composable
fun QuietCanvasScene(ctx: LockContext, modifier: Modifier = Modifier) {
    val c = Q.colors
    val calm = calmFor(ctx.usageMs)
    val seed = remember(LocalDate.now()) { (LocalDate.now().toEpochDay() % 97).toFloat() * 1.37f }
    Box(modifier.fillMaxSize().background(c.bg)) {
        if (ShadersSupported) {
            val time by rememberShaderTime(ctx.animate, fps = 20)
            ShaderCanvas(Shaders.QUIET_CANVAS, Modifier.fillMaxSize(), resolutionScale = 0.6f) { w, h ->
                setFloatUniform("u_res", w, h)
                setFloatUniform("u_time", time + 40f)
                setFloatUniform("u_calm", calm)
                setFloatUniform("u_light", if (c.isDark) 0f else 1f)
                setFloatUniform("u_seed", seed)
            }
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            LockClock(ctx)
            Spacer(Modifier.weight(1f))
            LockLabel(
                ctx, "Quiet canvas",
                "Drawn from today: ${Format.duration(ctx.usageMs)} on screen.",
                "Fewer hours, calmer lines.",
                Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LockBottomReserve))
        }
    }
}

// ---------- 3. Tonight's sky ----------

@Composable
fun SkyScene(ctx: LockContext, modifier: Modifier = Modifier) {
    val c = Q.colors
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(ctx.minute).atZone(zone)
    val phase = remember(ctx.minute / 3_600_000L) { Astronomy.moonPhase(now.toInstant()) }
    val lit = Astronomy.illuminatedFraction(phase)
    val full = remember(now.toLocalDate()) { Astronomy.nextFullMoon(now) }
    val sun = remember(now.toLocalDate(), ctx.lat, ctx.lon) { Astronomy.sunTimes(now.toLocalDate(), ctx.lat, ctx.lon, zone) }
    val alt = Astronomy.sunAltitudeDeg(now.toInstant(), ctx.lat, ctx.lon)
    val dusk = ((1 + alt / 12.0).coerceIn(0.0, 1.0)).toFloat()
    val arc = Astronomy.sunArcFraction(now, sun)
    var sizePx by remember { mutableStateOf(IntSize(1, 1)) }
    val density = LocalDensity.current
    val horizonFrac = 0.42f
    val hhmm = DateTimeFormatter.ofPattern(if (ctx.is24h) "HH:mm" else "h:mm a", Locale.getDefault())
    val fullText = full.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))

    Box(modifier.fillMaxSize().background(c.bg).onSizeChanged { sizePx = it }) {
        if (ShadersSupported) {
            val time by rememberShaderTime(ctx.animate, fps = 10)
            val scale = 0.75f
            ShaderCanvas(Shaders.NIGHT_SKY, Modifier.fillMaxSize(), resolutionScale = scale) { w, h ->
                setFloatUniform("u_res", w, h)
                setFloatUniform("u_time", time)
                setFloatUniform("u_phase", phase.toFloat())
                // Moon: upper right, radius 34 dp; shader uses bottom-left origin.
                val r = with(density) { 34.dp.toPx() } * scale
                setFloatUniform("u_moon", w * 0.70f, h * 0.70f, r)
                setFloatUniform("u_horizon", horizonFrac)
                setFloatUniform("u_dusk", dusk)
                setFloatUniform("u_light", if (c.isDark) 0f else 1f)
                setFloatUniform("u_seed", (LocalDate.now().toEpochDay() % 50).toFloat())
            }
        }
        // Sun arc under the horizon line.
        Canvas(Modifier.fillMaxSize()) {
            val hy = size.height * (1 - horizonFrac)
            val left = size.width * 0.12f
            val right = size.width * 0.88f
            drawLine(c.line.copy(alpha = 1f), Offset(0f, hy), Offset(size.width, hy), 1.dp.toPx())
            val arcH = size.height * 0.10f
            val path = androidx.compose.ui.graphics.Path()
            val steps = 60
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val x = left + (right - left) * f
                val y = hy + 18.dp.toPx() + arcH - (sin(Math.PI * f).toFloat() * arcH)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c.edge, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))))
            if (sun.sunrise != null && sun.sunset != null) {
                val f = arc.coerceIn(-0.15f, 1.15f)
                val x = left + (right - left) * f.coerceIn(0f, 1f)
                val y = hy + 18.dp.toPx() + arcH - (sin(Math.PI * f.coerceIn(0f, 1f)).toFloat() * arcH)
                if (f in 0f..1f) drawCircle(c.ink, 4.dp.toPx(), Offset(x, y))
                else drawCircle(c.muted, 4.dp.toPx(), Offset(x, y + 10.dp.toPx() * abs(f - f.coerceIn(0f, 1f)) * 6), style = Stroke(1.dp.toPx()))
            }
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            LockClock(ctx)
            Spacer(Modifier.weight(1f))
            val sunLine = when {
                sun.sunset == null || sun.sunrise == null -> "No sunrise or sunset today here."
                now.isBefore(sun.sunrise) -> "The sun rises at ${sun.sunrise.format(hhmm)}."
                now.isBefore(sun.sunset) -> "The sun sets at ${sun.sunset.format(hhmm)}."
                else -> "The sun set at ${sun.sunset.format(hhmm)}" + (sun.civilDusk?.let { "; twilight ends at ${it.format(hhmm)}." } ?: ".")
            }
            LockLabel(
                ctx, "Tonight’s sky",
                "${Astronomy.phaseName(phase)}, ${(lit * 100).toInt()}% lit. Full on $fullText.",
                sunLine + if (ctx.city.isNotBlank()) " ${ctx.city}." else "",
                Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LockBottomReserve))
        }
    }
}

// ---------- 4. Typeset ----------

@Composable
fun TypesetScene(ctx: LockContext, modifier: Modifier = Modifier) {
    val c = Q.colors
    val t = Instant.ofEpochMilli(ctx.minute).atZone(ZoneId.systemDefault())
    val hour = if (ctx.wallpaper) "${t.dayOfMonth}" else if (ctx.is24h) "%02d".format(t.hour) else "%d".format(((t.hour + 11) % 12) + 1)
    val min = if (ctx.wallpaper) t.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) else "%02d".format(t.minute)
    val poster = TextStyle(
        fontFamily = ctx.face.display, fontSize = (ctx.face.posterSp * 0.92f).sp, lineHeight = (ctx.face.posterSp * 0.8f).sp,
        color = c.ink, fontFeatureSettings = "lnum, tnum", letterSpacing = (-0.03).em,
    )
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(if (ctx.wallpaper) WallpaperClockSpace else 40.dp))
            BasicText(hour, style = poster, maxLines = 1)
            BasicText(min, style = poster.copy(drawStyle = Stroke(width = 1.2f * 2.75f)), maxLines = 1)
            Spacer(Modifier.height(16.dp))
            BasicText(Format.longDate(ctx.minute), style = QType.body(18.sp).copy(color = c.ink))
            if (ctx.oneThing.isNotBlank()) {
                Spacer(Modifier.height(28.dp))
                BasicText("One thing today", style = QType.secondary(14.sp).copy(color = c.muted))
                BasicText(
                    ctx.oneThing,
                    style = TextStyle(fontFamily = ctx.face.label, fontStyle = if (ctx.face.labelItalic) FontStyle.Italic else FontStyle.Normal, fontSize = 28.sp, lineHeight = 32.sp, color = c.ink),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            LockLabel(
                ctx,
                if (ctx.wallpaper) t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) else Astronomy.timeInWords(t.hour, t.minute),
                "Set in ${ctx.face.name}", null, Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LockBottomReserve))
        }
    }
}

// ---------- 5. Cover of the moment ----------

@Composable
fun CoverScene(ctx: LockContext, cover: Bitmap?, title: String?, artist: String?, modifier: Modifier = Modifier) {
    val c = Q.colors
    Box(modifier.fillMaxSize().background(c.bg).pictureLight(strength = 0.06f)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            LockClock(ctx)
            Spacer(Modifier.weight(1f))
            if (cover != null || title != null) {
                GalleryFrame(cover, title ?: "", c.matBig, Modifier.fillMaxWidth(0.82f).aspectRatio(1f), matFraction = 0.06f, shadow = 26.dp)
                Spacer(Modifier.height(24.dp))
                LockLabel(ctx, title ?: "Nothing playing", artist ?: "", "Last played", Modifier.fillMaxWidth())
            } else {
                BasicText("Play some music and its cover hangs here.", style = QType.secondary().copy(color = c.muted))
            }
            Spacer(Modifier.weight(0.6f))
            Spacer(Modifier.height(LockBottomReserve))
        }
    }
}

@Suppress("unused") private val keepSize = Size.Zero

/**
 * A small heart over the painting when it is added to favourites: fades in, holds, fades out.
 * Runs only when [pulse] changes (0 = never shown).
 */
@Composable
fun FavouriteHeart(pulse: Int, modifier: Modifier = Modifier) {
    val reduced = Q.reducedMotion
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        a.snapTo(0f)
        a.animateTo(1f, androidx.compose.animation.core.tween(if (reduced) 0 else 220))
        kotlinx.coroutines.delay(700)
        a.animateTo(0f, androidx.compose.animation.core.tween(if (reduced) 0 else 500))
    }
    if (a.value <= 0f) return
    Canvas(
        modifier.size(56.dp).graphicsLayer {
            alpha = a.value
            val s = if (reduced) 1f else 0.85f + 0.15f * a.value
            scaleX = s; scaleY = s
        }
    ) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.1f, h * 0.6f, -w * 0.05f, h * 0.28f, w * 0.25f, h * 0.15f)
            cubicTo(w * 0.38f, h * 0.1f, w * 0.47f, h * 0.18f, w * 0.5f, h * 0.28f)
            cubicTo(w * 0.53f, h * 0.18f, w * 0.62f, h * 0.1f, w * 0.75f, h * 0.15f)
            cubicTo(w * 1.05f, h * 0.28f, w * 0.9f, h * 0.6f, w * 0.5f, h * 0.88f)
            close()
        }
        drawPath(p, Color.Black.copy(alpha = 0.35f), style = Stroke(6.dp.toPx()))
        drawPath(p, Color(0xFFEDEBE6))
    }
}

/** The last painting's swatches, so coming back to the lock screen does not run Palette again. */
private object PaintingPaletteCache {
    private var path: String? = null
    private var swatches: IntArray? = null
    @Synchronized fun get(p: String?): IntArray? = if (p != null && p == path) swatches else null
    @Synchronized fun put(p: String?, s: IntArray) { if (p != null) { path = p; swatches = s } }
}

/** Palette swatches (darkMuted, vibrant, muted, lightVibrant) for the ambient field. */
fun paintingPalette(b: Bitmap): IntArray {
    val p = androidx.palette.graphics.Palette.from(b).maximumColorCount(16).generate()
    val dom = p.getDominantColor(0xFF6E6252.toInt())
    return intArrayOf(
        p.getDarkMutedColor(p.getDarkVibrantColor(dom)),
        p.getVibrantColor(p.getLightMutedColor(dom)),
        p.getMutedColor(dom),
        p.getLightVibrantColor(p.getLightMutedColor(dom)),
    )
}

/**
 * A picture light passing over the glass: a soft diagonal sheen crosses the frame once when the
 * work is hung (and when it changes), then every 6–7 s, faintly.
 */
fun Modifier.frameLightSweep(animate: Boolean, key: Any?, sheen: Color = Color.White): Modifier = composed {
    val sweep = remember { androidx.compose.animation.core.Animatable(-0.4f) }
    LaunchedEffect(animate, key) {
        if (!animate) return@LaunchedEffect
        while (true) {
            sweep.snapTo(-0.4f)
            sweep.animateTo(1.4f, androidx.compose.animation.core.tween(2600, easing = QMotion.EaseInOutSine))
            // Rest 3.5–5 s (a sweep every ~6–7.6 s); the small variation keeps it feeling like real light.
            kotlinx.coroutines.delay(3_500L + kotlin.random.Random.nextLong(1_500L))
        }
    }
    drawWithContent {
        drawContent()
        val x = size.width * sweep.value
        drawRect(
            androidx.compose.ui.graphics.Brush.linearGradient(
                0f to Color.Transparent, 0.5f to sheen.copy(alpha = 0.10f), 1f to Color.Transparent,
                start = Offset(x - size.width * 0.35f, 0f), end = Offset(x + size.width * 0.35f, size.height),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.Plus,
        )
    }
}
