package app.quiet.launcher.feature.share

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.quiet.launcher.core.model.Artwork
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share a painting as a 1080 x 1920 story card. The card is drawn once per chosen layout, off the
 * main thread; the same bitmap is shown, saved and shared.
 */
@Composable
fun ShareCardDialog(art: Artwork, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(CardLayout.WALL) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var painting by remember { mutableStateOf<Bitmap?>(null) }
    var paintingReady by remember { mutableStateOf(false) }

    // Decode the painting once, small enough for the card.
    LaunchedEffect(art.imagePath) {
        painting = withContext(Dispatchers.IO) { ShareCardRenderer.loadPainting(art.imagePath) }
        paintingReady = true
    }
    LaunchedEffect(paintingReady, layout) {
        if (!paintingReady) return@LaunchedEffect
        card = null
        card = withContext(Dispatchers.Default) { ShareCardRenderer(ctx).render(art, painting, layout) }
    }

    fun export(share: Boolean) {
        val bmp = card ?: return
        if (busy) return
        busy = true
        scope.launch {
            val name = "quiet-story-${layout.name.lowercase()}-${System.currentTimeMillis() / 1000}.png"
            if (share) {
                if (!ImageExport.sharePng(ctx, bmp, name, "Share painting")) {
                    Toast.makeText(ctx, "Could not share the image.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val ok = ImageExport.savePng(ctx, bmp, name) != null
                Toast.makeText(ctx, if (ok) "Saved to Pictures/Quiet" else "Could not save the image.", Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Share the painting", style = QType.labelTitle(22.sp).copy(color = c.ink), modifier = Modifier.weight(1f))
                Box(Modifier.height(44.dp).pressable(onClick = onDismiss), contentAlignment = Alignment.CenterEnd) {
                    BasicText("Done", style = QType.secondary(15.sp).copy(color = c.muted))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val bmp = card
                Box(
                    Modifier.fillMaxHeight().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))
                        .background(Color.Black).border(1.dp, c.line, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        val img = remember(bmp) { bmp.asImageBitmap() }
                        Image(
                            img, contentDescription = "Story card: ${art.title} by ${art.artist}, signed Quiet",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BasicText("Hanging the painting", style = QType.secondary(14.sp).copy(color = Color(0xFF9A968E)))
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Gallery wall", layout == CardLayout.WALL) { layout = CardLayout.WALL }
                Choice("Full bleed", layout == CardLayout.BLEED) { layout = CardLayout.BLEED }
            }
            Row(Modifier.padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Save", ink = false, enabled = card != null && !busy) { export(share = false) }
                Pill("Share", ink = true, enabled = card != null && !busy) { export(share = true) }
            }
        }
    }
}

@Composable
private fun Choice(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(40.dp).clip(shape).background(if (on) c.ink else Color.Transparent)
            .border(1.dp, if (on) c.ink else c.edge, shape)
            .semantics { role = Role.RadioButton; selected = on }
            .pressable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = QType.secondary(14.sp).copy(color = if (on) c.onInk else c.ink)) }
}

@Composable
private fun Pill(text: String, ink: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).widthIn(min = 120.dp).clip(shape)
            .background(if (ink) c.ink else Color.Transparent)
            .border(1.dp, if (ink) c.ink else c.edge, shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable { if (enabled) onClick() }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = QType.secondary(15.sp).copy(color = if (ink) c.onInk else c.ink)) }
}
