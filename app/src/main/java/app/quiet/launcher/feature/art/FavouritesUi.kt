package app.quiet.launcher.feature.art

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.Artwork
import app.quiet.launcher.core.model.LockStyle
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.lock.rememberFileBitmap
import kotlinx.coroutines.launch

private val Gutter = 24.dp

/**
 * Settings, under Lock screen and art: keep today's painting, and the gallery of paintings you
 * kept. Favourites are never cleaned out of the cache and come back about every two weeks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavouritesSection() {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val favourites by container.art.favourites.collectAsStateWithLifecycle()
    val today by container.art.today.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Artwork?>(null) }
    val c = Q.colors

    today?.let { t ->
        val kept = favourites.any { it.id == t.id }
        Column {
            Row(
                Modifier.fillMaxWidth().height(56.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { container.art.setFavourite(t.id, !kept) }
                    }
                    .padding(horizontal = Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    if (kept) "Today’s painting is a favourite" else "Add today’s painting to favourites",
                    Modifier.weight(1f), style = QType.body().copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(if (kept) "Remove" else "Add", Modifier.padding(start = 12.dp), style = QType.secondary().copy(color = c.muted))
            }
            Box(Modifier.padding(horizontal = Gutter).fillMaxWidth().height(1.dp).background(c.line))
        }
    }

    BasicText(
        if (favourites.isEmpty()) "Favourites" else "Favourites  ${favourites.size}",
        Modifier.padding(horizontal = Gutter).padding(top = 16.dp, bottom = 10.dp),
        style = QType.secondary().copy(color = c.muted),
    )
    if (favourites.isEmpty()) {
        BasicText(
            "Paintings you keep appear here. Hold the painting on the lock screen and choose Add to favourites.",
            Modifier.padding(horizontal = Gutter).padding(bottom = 8.dp),
            style = QType.secondary(14.sp).copy(color = c.muted),
        )
    } else {
        FlowRow(
            Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            favourites.forEach { w ->
                val bmp = rememberFileBitmap(w.imagePath, maxSide = 360)
                val on = w.id == today?.id
                val shape = RoundedCornerShape(10.dp)
                Column(Modifier.width(96.dp).clickable(remember { MutableInteractionSource() }, indication = null) { open = w }) {
                    Box(
                        Modifier.size(96.dp).clip(shape).background(c.raised)
                            .border(if (on) 2.dp else 1.dp, if (on) c.ink else c.line, shape),
                    ) {
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), contentDescription = w.title, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (on) 2.dp else 1.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    BasicText(w.title, style = QType.small().copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(if (on) "Showing today" else w.artist, style = QType.small().copy(color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    open?.let { w ->
        FavouriteDialog(
            work = w,
            showing = today?.id == w.id,
            onDismiss = { open = null },
            onShow = {
                open = null
                scope.launch {
                    container.art.show(w.id)
                    container.settings.update { it.copy(lockStyle = LockStyle.MASTERPIECE) }
                }
            },
            onRemove = {
                open = null
                scope.launch { container.art.setFavourite(w.id, false) }
            },
        )
    }
}

/** The kept painting large, with its credit; show it now or let it go back to the cache. */
@Composable
private fun FavouriteDialog(work: Artwork, showing: Boolean, onDismiss: () -> Unit, onShow: () -> Unit, onRemove: () -> Unit) {
    val c = Q.colors
    val shape = RoundedCornerShape(20.dp)
    val bmp = rememberFileBitmap(work.imagePath, maxSide = 1400)
    var confirmRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(shape).background(c.raised).border(1.dp, c.line, shape).padding(24.dp)) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = work.title, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }
            BasicText(work.title, style = QType.title(22.sp).copy(color = c.ink))
            BasicText(work.artist, style = QType.secondary().copy(color = c.muted))
            val meta = listOfNotNull(work.year, work.museum).joinToString(", ")
            if (meta.isNotBlank()) BasicText(meta, style = QType.secondary(14.sp).copy(color = c.muted))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(if (confirmRemove) "Yes, remove it" else "Remove from favourites", primary = false) {
                    if (confirmRemove) onRemove() else confirmRemove = true
                }
                Spacer(Modifier.width(8.dp))
                Button(if (showing) "Showing today" else "Show now", primary = true) { if (showing) onDismiss() else onShow() }
            }
        }
    }
}

@Composable
private fun Button(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .background(if (primary) Q.colors.ink else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = QType.secondary().copy(color = if (primary) Q.colors.bg else Q.colors.ink), maxLines = 1) }
}
