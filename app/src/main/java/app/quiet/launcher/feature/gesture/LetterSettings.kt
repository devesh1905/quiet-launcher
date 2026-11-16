package app.quiet.launcher.feature.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import app.quiet.launcher.feature.apps.QuietSheet
import app.quiet.launcher.feature.apps.SheetAction

/** Settings summary for the letters row, e.g. "C, M, W". */
fun lettersSummary(s: GestureSettings): String =
    Unistroke.LETTERS.filter { it in s.map }.joinToString(", ").ifEmpty { "None" }

/** The letter -> app list: tap a letter, then pick one of the installed apps (or nothing). */
@Composable
fun LetterAppsSheet(visible: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { GesturePrefs.get(ctx) }
    val s by prefs.state.collectAsState()
    val apps by ctx.container.apps.apps.collectAsState()
    var editing by remember { mutableStateOf<Char?>(null) }
    LaunchedEffect(visible) { if (!visible) editing = null }
    val c = Q.colors
    QuietSheet(visible = visible, onDismiss = { if (editing != null) editing = null else onDismiss() }) {
        val letter = editing
        if (letter == null) {
            BasicText("Letters", style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.padding(vertical = 12.dp))
            BasicText(
                "Draw a letter on an empty part of Home to open its app.",
                style = QType.secondary(14.sp).copy(color = c.muted), modifier = Modifier.padding(bottom = 8.dp),
            )
            val labels = remember(s.map, apps) { Unistroke.LETTERS.associateWith { GestureTargets.label(ctx, s.map[it], apps) } }
            Unistroke.LETTERS.forEach { l ->
                Row(
                    Modifier.fillMaxWidth().height(56.dp).pressable(pressedScale = 0.99f) { editing = l }
                        .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(l.toString(), style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.width(44.dp))
                    BasicText(
                        labels[l].orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = QType.body().copy(color = if (l in s.map) c.ink else c.muted),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            BasicText("Draw $letter to open", style = QType.labelTitle(24.sp).copy(color = c.ink), modifier = Modifier.padding(vertical = 12.dp))
            val visibleApps = remember(apps) { apps.filter { !it.hidden } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                item { SheetAction("Nothing") { prefs.setLetter(letter, null); editing = null } }
                item { SheetAction("Default camera") { prefs.setLetter(letter, CAMERA); editing = null } }
                items(visibleApps, key = { it.key.id }) { a ->
                    Box(Modifier.fillMaxWidth()) {
                        SheetAction(a.label + if (a.isWorkProfile) "  (work)" else "") {
                            prefs.setLetter(letter, a.key.id); editing = null
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
