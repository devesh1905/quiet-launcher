package app.quiet.launcher.feature.hidden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import kotlinx.coroutines.launch

private val Gutter = 24.dp

@Composable
fun HiddenAppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.container.apps
    val scope = rememberCoroutineScope()
    val all by repo.apps.collectAsState()
    val hidden = remember(all) { all.filter { it.hidden } }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
        ) {
            item {
                Box(
                    Modifier.padding(start = 8.dp, top = 4.dp).size(44.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack,
                    ),
                    contentAlignment = Alignment.Center,
                ) { Chevron(Q.colors.ink) }
                Txt("Hidden apps", QType.title(40.sp), Q.colors.ink, Modifier.padding(horizontal = Gutter).padding(top = 8.dp))
                Txt(
                    "These stay out of the app list and search. Tap one to open it.",
                    QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 20.dp),
                )
                Hairline()
            }
            if (hidden.isEmpty()) {
                item {
                    Txt(
                        "Nothing is hidden. To hide an app, long press it in the app list and choose Hide.",
                        QType.body(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(top = 28.dp),
                    )
                }
            }
            items(hidden, key = { it.key.id }) { app ->
                Row(
                    Modifier.fillMaxWidth().height(60.dp).padding(start = Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(
                        app.label, QType.body(), Q.colors.ink,
                        Modifier.weight(1f).height(60.dp).pressable { repo.launch(app.key) }
                            .padding(top = 18.dp),
                    )
                    Box(
                        Modifier.height(60.dp).pressable { scope.launch { repo.setHidden(app.key, false) } }
                            .padding(horizontal = Gutter),
                        contentAlignment = Alignment.Center,
                    ) { Txt("Unhide", QType.secondary(), Q.colors.muted) }
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun Txt(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = style.copy(color = color))
}

@Composable
private fun Hairline() {
    Box(Modifier.padding(horizontal = Gutter).fillMaxWidth().height(1.dp).background(Q.colors.line))
}

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
        val w = 1.6.dp.toPx()
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height), w, StrokeCap.Round)
    }
}
