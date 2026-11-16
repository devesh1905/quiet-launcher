package app.quiet.launcher.feature.receipt

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.designsystem.pressable
import app.quiet.launcher.feature.share.ImageExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** The day receipt, opened from the evening notification or from Screen time. */
class ReceiptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val s by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (s.theme) {
                ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            QuietTheme(dark = dark, reducedMotion = s.reducedMotion) { ReceiptScreen() }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ReceiptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(context: Context) = context.startActivity(intent(context))
    }
}

@Composable
private fun ReceiptScreen() {
    val ctx = LocalContext.current
    val c = Q.colors
    val reduced = Q.reducedMotion
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var receipt by remember { mutableStateOf<Receipt?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Read and draw once, off the main thread.
    LaunchedEffect(Unit) {
        val r = ReceiptLoader.load(ctx)
        val bmp = withContext(Dispatchers.Default) { ReceiptRenderer(ctx).render(r, density) }
        receipt = r
        bitmap = bmp
    }

    // Saved and shared images are drawn again at 3x, then let go.
    fun export(share: Boolean) {
        val r = receipt ?: return
        if (busy) return
        busy = true
        scope.launch {
            val big = withContext(Dispatchers.Default) { ReceiptRenderer(ctx).render(r, 3f) }
            val name = "quiet-receipt-${r.day}.png"
            if (share) {
                if (!ImageExport.sharePng(ctx, big, name, "Share receipt")) toast(ctx, "Could not share the receipt.")
            } else {
                val uri = ImageExport.savePng(ctx, big, name)
                toast(ctx, if (uri != null) "Saved to Pictures/Quiet" else "Could not save the image.")
            }
            big.recycle()
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 32.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(receipt?.let { "${it.dayName} night" } ?: "", style = QType.secondary(14.sp).copy(color = c.muted), modifier = Modifier.weight(1f))
            Pill("Share", ink = false, enabled = bitmap != null && !busy) { export(share = true) }
            Spacer(Modifier.width(8.dp))
            Pill("Save image", ink = true, enabled = bitmap != null && !busy) { export(share = false) }
        }
        val bmp = bitmap
        val r = receipt
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.BottomCenter) {
            if (bmp != null && r != null) {
                val ratio = bmp.height.toFloat() / bmp.width
                // 312 wide like the paper, smaller if the screen is short; room above for the tear.
                var w = minOf(ReceiptRenderer.W.dp, maxWidth - 48.dp)
                if (w * ratio > maxHeight - 28.dp) w = (maxHeight - 28.dp) / ratio
                val h = w * ratio
                PrintedPaper(bmp, w, h, reduced, describe(r))
            }
        }
        Printer(printing = bitmap == null)
        Spacer(Modifier.height(20.dp))
    }
}

/** The stepped advance out of the slot, a short rest, then the tear: up 16 dp and a 0.6 degree turn. */
@Composable
private fun PrintedPaper(bmp: Bitmap, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, reduced: Boolean, description: String) {
    val px = LocalDensity.current
    val hPx = with(px) { h.toPx() }
    val step = with(px) { 1.5.dp.toPx() }
    val fed = remember { Animatable(if (reduced) 1f else 0f) }
    val lift = remember { Animatable(if (reduced) 1f else 0f) }
    val alpha = remember { Animatable(if (reduced) 0f else 1f) }
    var printing by remember { mutableStateOf(false) }
    LaunchedEffect(bmp) {
        if (reduced) { alpha.animateTo(1f, tween(200)); return@LaunchedEffect }
        delay(300)
        printing = true
        fed.animateTo(1f, tween(1200, easing = LinearEasing))
        printing = false
        delay(220)
        lift.animateTo(1f, spring(dampingRatio = 0.54f, stiffness = 170f))
    }
    val image = remember(bmp) { bmp.asImageBitmap() }
    Image(
        image, contentDescription = null,
        modifier = Modifier.size(w, h)
            .semantics { contentDescription = description }
            .graphicsLayer {
                val e = fed.value * hPx
                val q = floor(e / step) * step
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = (hPx - q) - lift.value * 16.dp.toPx()
                translationX = if (printing) (Math.random().toFloat() - 0.5f) * 0.4.dp.toPx() else 0f
                rotationZ = -0.6f * lift.value
                this.alpha = alpha.value
                shadowElevation = 0f
            },
    )
}

@Composable
private fun Printer(printing: Boolean) {
    val c = Q.colors
    Box(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(c.raised).border(1.dp, c.line, RoundedCornerShape(18.dp)),
    ) {
        Box(Modifier.align(Alignment.Center).padding(horizontal = 22.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black))
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(4.dp).clip(CircleShape)
                .background(if (printing) c.ink else c.faint),
        )
    }
}

@Composable
private fun Pill(text: String, ink: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .background(if (ink) c.ink else Color.Transparent)
            .border(1.dp, if (ink) c.ink else c.edge, shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable { if (enabled) onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = QType.secondary(14.sp).copy(color = if (ink) c.onInk else c.ink)) }
}

private fun describe(r: Receipt): String {
    val apps = r.apps.joinToString(", ") { "${it.first} ${it.second} minutes" }
    return "Day receipt number ${r.number}, ${r.dateLine}. Screen time: ${apps.ifEmpty { "none" }}. " +
        "Pickups ${r.pickups}. First pickup ${r.firstPickup}. Longest away ${ReceiptMath.hm(r.longestAwayMin)}. " +
        "Focus ${ReceiptMath.hm(r.focusMin)}. Total ${ReceiptMath.hm(r.totalMin)}" +
        (if (r.allowanceMin > 0) ", ${ReceiptMath.allowanceLine(r.totalMin, r.allowanceMin)}." else ".")
}

private suspend fun toast(ctx: Context, text: String) = withContext(Dispatchers.Main) {
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
