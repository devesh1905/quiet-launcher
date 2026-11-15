package app.quiet.launcher.feature.monochrome

import app.quiet.launcher.core.platform.rememberAppCategories
import app.quiet.launcher.designsystem.AppPreset
import app.quiet.launcher.designsystem.PresetChips
import app.quiet.launcher.designsystem.presetsFor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import kotlinx.coroutines.launch

private val Gutter = 24.dp
private val RowHeight = 56.dp

@Composable
fun MonochromeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MonochromeController.get(ctx).store }
    val cfg by store.config.collectAsState(MonochromeConfig())
    val apps by ctx.container.apps.apps.collectAsState()
    var granted by remember { mutableStateOf(hasSecureSettingsPermission(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasSecureSettingsPermission(ctx) }
    BackHandler(onBack = onBack)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun update(t: (MonochromeConfig) -> MonochromeConfig) {
        scope.launch { store.update(t) }
    }

    // One row per package: work-profile copies and multiple launcher activities share a setting.
    val packages = remember(apps) {
        apps.distinctBy { it.key.packageName }.map { it.key.packageName to it.systemLabel.ifBlank { it.label } }
    }
    val labels = remember(apps) { apps.associate { it.key.packageName to it.label } }
    var preset by remember { mutableStateOf<AppPreset>(AppPreset.All) }
    val cats = rememberAppCategories(packages.map { it.first })
    val filtered = remember(packages, query, labels, preset, cats) {
        val q = query.trim()
        packages.map { (pkg, _) -> pkg to (labels[pkg] ?: pkg) }
            .filter { (q.isEmpty() || it.second.contains(q, ignoreCase = true)) && preset.matches(cats[it.first]) }
    }

    Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item {
                Box(
                    Modifier.padding(start = 8.dp, top = 4.dp).size(44.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack,
                    ),
                    contentAlignment = Alignment.Center,
                ) { Chevron(Q.colors.ink) }
                Txt("Monochrome", QType.title(40.sp), Q.colors.ink, Modifier.padding(horizontal = Gutter).padding(top = 8.dp))
                Txt(
                    "Colour is what makes a screen feel rewarding. Turn it to grey for the apps that pull you in, " +
                        "or for the whole phone late in the evening.",
                    QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 20.dp),
                )
            }

            item {
                if (granted) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 8.dp)) {
                        Txt("Permission", QType.body(), Q.colors.ink, Modifier.weight(1f))
                        Txt("Granted", QType.secondary(), Q.colors.ink)
                    }
                } else {
                    Column(Modifier.padding(horizontal = Gutter).padding(bottom = 16.dp)) {
                        Txt("Needs one-time setup", QType.body(), Q.colors.ink)
                        Txt(
                            "Android only lets an app change display colour after you allow it from a computer. " +
                                "Connect the phone with USB debugging on and run this once:",
                            QType.secondary(), Q.colors.muted, Modifier.padding(top = 6.dp),
                        )
                        val shape = RoundedCornerShape(14.dp)
                        Txt(
                            ADB_GRANT_COMMAND, QType.secondary(14.sp), Q.colors.ink,
                            Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape)
                                .border(1.dp, Q.colors.edge, shape).padding(14.dp),
                        )
                        Box(
                            Modifier.padding(top = 12.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
                                .border(1.dp, Q.colors.edge, RoundedCornerShape(22.dp))
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    clipboard.setText(AnnotatedString(ADB_GRANT_COMMAND)); copied = true
                                }
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt(if (copied) "Copied" else "Copy command", QType.secondary(), Q.colors.ink) }
                    }
                }
                Hairline()
            }

            item {
                Column {
                    SwitchRow("Grey for selected apps", cfg.selectedEnabled) { v -> update { it.copy(selectedEnabled = v) } }
                    SwitchRow("Grey everything at wind-down", cfg.windDownEnabled) { v -> update { it.copy(windDownEnabled = v) } }
                    if (cfg.windDownEnabled) {
                        TimeRow("From", cfg.windDownStartMin) { v -> update { it.copy(windDownStartMin = v) } }
                        TimeRow("Until", cfg.windDownEndMin) { v -> update { it.copy(windDownEndMin = v) } }
                    }
                }
            }

            item {
                Txt("Apps", QType.title(28.sp), Q.colors.ink, Modifier.padding(horizontal = Gutter).padding(top = 36.dp, bottom = 4.dp))
                Txt(
                    if (cfg.selectedEnabled) "${cfg.selectedPackages.size} selected" else "Turn on \"Grey for selected apps\" to use this list.",
                    QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp),
                )
                SearchField(query) { query = it }
                PresetChips(
                    presetsFor(cats), preset, { preset = it }, Modifier.padding(vertical = 8.dp), gutter = Gutter,
                    selectAllLabel = "Grey for all ${filtered.size}",
                    onSelectAll = { update { it.copy(selectedEnabled = true, selectedPackages = it.selectedPackages + filtered.map { p -> p.first }) } },
                )
                Hairline()
            }

            items(filtered, key = { it.first }) { (pkg, label) ->
                SwitchRow(label, pkg in cfg.selectedPackages) { on ->
                    update { it.copy(selectedPackages = if (on) it.selectedPackages + pkg else it.selectedPackages - pkg) }
                }
            }
        }
    }
}

@Composable
private fun Txt(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, align: TextAlign? = null) {
    BasicText(text, modifier, style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified))
}

@Composable
private fun Hairline() {
    Box(Modifier.padding(horizontal = Gutter).fillMaxWidth().height(1.dp).background(Q.colors.line))
}

@Composable
private fun RowBase(onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(RowHeight)
                .then(
                    if (onClick != null) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
                    ) else Modifier,
                )
                .padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        Hairline()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowBase(onClick = { onChange(!checked) }) {
        Txt(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
        QuietSwitch(checked)
    }
}

@Composable
private fun QuietSwitch(checked: Boolean) {
    val edge = Q.colors.edge
    val ink = Q.colors.ink
    val track by animateColorAsState(if (checked) ink else Color.Transparent, label = "track")
    val thumb by animateColorAsState(if (checked) Q.colors.bg else ink, label = "thumb")
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, label = "thumbX")
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(46.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(track)
                .border(1.5.dp, if (checked) ink else edge, RoundedCornerShape(14.dp)),
        ) {
            Box(Modifier.padding(4.dp).offset(x = x).size(20.dp).clip(RoundedCornerShape(10.dp)).background(thumb))
        }
    }
}

/** Hour and minute steppers (hour by 1, minute by 5) for a minute-of-day value. */
@Composable
private fun TimeRow(title: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    fun set(nh: Int, nm: Int) = onChange(((nh + 24) % 24) * 60 + ((nm + 60) % 60))
    RowBase(onClick = null) {
        Txt(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
        StepButton(plus = false) { set(h - 1, m) }
        Txt("%02d".format(h), QType.secondary(), Q.colors.ink, Modifier.width(26.dp), TextAlign.Center)
        StepButton(plus = true) { set(h + 1, m) }
        Txt(":", QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = 4.dp))
        StepButton(plus = false) { set(h, m - 5) }
        Txt("%02d".format(m), QType.secondary(), Q.colors.ink, Modifier.width(26.dp), TextAlign.Center)
        StepButton(plus = true) { set(h, m + 5) }
    }
}

@Composable
private fun StepButton(plus: Boolean, onClick: () -> Unit) {
    val color = Q.colors.ink
    Box(
        Modifier.size(40.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = 1.5.dp.toPx()
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
            if (plus) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = QType.body().copy(color = Q.colors.ink),
        cursorBrush = SolidColor(Q.colors.ink),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter).height(48.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                if (value.isEmpty()) Txt("Search apps", QType.body(), Q.colors.faint)
                inner()
            }
        },
    )
}

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
        val w = 1.6.dp.toPx()
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height), w, StrokeCap.Round)
    }
}
