package app.quiet.launcher.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.quiet.launcher.BuildConfig
import app.quiet.launcher.container
import app.quiet.launcher.core.model.Artwork
import app.quiet.launcher.core.model.LauncherSettings
import androidx.compose.ui.graphics.asImageBitmap
import app.quiet.launcher.core.model.LockFont
import app.quiet.launcher.core.model.LockStyle
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.core.platform.Permissions
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.lockFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Gutter = 24.dp
private val RowHeight = 56.dp

private val ArtistChoices = listOf(
    "Vincent van Gogh", "Claude Monet", "Katsushika Hokusai", "Johannes Vermeer",
    "Gustav Klimt", "Paul Cézanne", "Georges Seurat", "Utagawa Hiroshige",
    "Rembrandt van Rijn", "J. M. W. Turner", "Edgar Degas", "Mary Cassatt",
)

private data class PermissionStatus(
    val home: Boolean,
    val usage: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
)

private fun readStatus(ctx: Context) = PermissionStatus(
    home = Permissions.isDefaultLauncher(ctx),
    usage = Permissions.hasUsageAccess(ctx),
    notifications = Permissions.hasNotificationAccess(ctx),
    overlay = Permissions.canDrawOverlays(ctx),
    battery = Permissions.isIgnoringBatteryOptimizations(ctx),
)

private fun styleName(style: LockStyle) = when (style) {
    LockStyle.MASTERPIECE -> "Masterpiece"
    LockStyle.CANVAS -> "Canvas"
    LockStyle.SKY -> "Tonight's sky"
    LockStyle.TYPESET -> "Typeset"
    LockStyle.COVER -> "Cover"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLockPicker: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenMonochrome: () -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOpenFocus: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenFocusSession: () -> Unit = {},
    onOpenPrecision: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val container = ctx.container
    val scope = rememberCoroutineScope()
    val s by container.settings.settings.collectAsState()
    var status by remember { mutableStateOf(readStatus(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = readStatus(ctx) }
    BackHandler(onBack = onBack)

    fun update(t: (LauncherSettings) -> LauncherSettings) {
        scope.launch { container.settings.update(t) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val library by container.art.library.collectAsState()
    val today by container.art.today.collectAsState()
    var openArt by remember { mutableStateOf<Artwork?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingUri = uri
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
                ) { Chevron(Q.colors.ink, left = true) }
                Text(
                    "Settings", QType.title(40.sp), Q.colors.ink,
                    Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 8.dp),
                )
            }

            section("Focus and screen time") {
                ValueRow("Start a focus session", "", onClick = onOpenFocusSession)
                ValueRow("App time and pauses", "", onClick = onOpenFocus)
                ValueRow("Blocking schedules", "", onClick = onOpenSchedules)
                ValueRow("Precision mode", "", onClick = onOpenPrecision)
                ValueRow("Notification filter", "", onClick = onOpenNotifications)
                ValueRow("Monochrome", "", onClick = onOpenMonochrome)
                ValueRow("Hidden apps", "", onClick = onOpenHidden)
                ValueRow("Screen time", "", onClick = onOpenUsage)
            }

            section("Home") {
                SwitchRow("Show usage on Home", s.showUsageOnHome) { v -> update { it.copy(showUsageOnHome = v) } }
                SwitchRow("Show now playing on Home", s.showNowPlayingOnHome) { v -> update { it.copy(showNowPlayingOnHome = v) } }
                SwitchRow("Colour glow from the artwork", s.homeGlow) { v -> update { it.copy(homeGlow = v) } }
                StepperRow("Daily budget", formatMinutes(s.dailyBudgetMinutes),
                    onMinus = { update { it.copy(dailyBudgetMinutes = (it.dailyBudgetMinutes - 30).coerceIn(30, 480)) } },
                    onPlus = { update { it.copy(dailyBudgetMinutes = (it.dailyBudgetMinutes + 30).coerceIn(30, 480)) } })
                TextFieldRow("One thing today", s.oneThingToday, "Optional") { v -> update { it.copy(oneThingToday = v) } }
            }

            section("Lock screen and art") {
                SwitchRow("Art on the system lock screen", s.lockWallpaper) { v -> update { it.copy(lockWallpaper = v) } }
                SwitchRow("Quiet lock screen on top", s.lockEnabled) { v -> update { it.copy(lockEnabled = v) } }
                SwitchRow("Only when music plays", s.lockOnlyWithMusic) { v -> update { it.copy(lockOnlyWithMusic = v) } }
                SwitchRow("Show music on the lock screen", s.lockShowMusic) { v -> update { it.copy(lockShowMusic = v) } }
                ValueRow("Lock screen style", styleName(s.lockStyle), onClick = onOpenLockPicker)
                FontChoiceRow(s.lockFont) { f -> update { it.copy(lockFont = f) } }
                Label("Favourite artists")
                FlowRow(
                    Modifier.padding(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArtistChoices.forEach { name ->
                        val on = name in s.favouriteArtists
                        Chip(name, on) {
                            update { cur ->
                                val next = if (on) cur.favouriteArtists - name else cur.favouriteArtists + name
                                if (next.isEmpty()) cur else cur.copy(favouriteArtists = next)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ValueRow("Add your own artwork", "", onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                YourArtworks(
                    works = library.filter { it.isUserProvided },
                    currentId = today?.id,
                    onOpen = { openArt = it },
                )
                Label("Ambient colour strength")
                SegmentedRow(
                    listOf("Off", "Subtle", "Full"),
                    selected = when {
                        s.ambientStrength <= 0.01f -> 0
                        s.ambientStrength < 0.8f -> 1
                        else -> 2
                    },
                ) { i -> update { it.copy(ambientStrength = floatArrayOf(0f, 0.6f, 1f)[i]) } }
                Label("Location for Tonight's sky")
                TextFieldRow("City", s.cityName, "City name") { v -> update { it.copy(cityName = v) } }
                CoordinateRow("Latitude", s.latitude, -90.0, 90.0) { v -> update { it.copy(latitude = v) } }
                CoordinateRow("Longitude", s.longitude, -180.0, 180.0) { v -> update { it.copy(longitude = v) } }
            }

            section("Display") {
                Label("Theme")
                SegmentedRow(
                    listOf("Black", "Light", "System"),
                    selected = s.theme.ordinal,
                ) { i -> update { it.copy(theme = ThemeMode.entries[i]) } }
                SwitchRow("Reduce motion", s.reducedMotion) { v -> update { it.copy(reducedMotion = v) } }
            }

            section("Gestures") {
                SwitchRow("Swipe up for search", s.swipeUpSearch) { v -> update { it.copy(swipeUpSearch = v) } }
                SwitchRow("Swipe down for notifications", s.swipeDownNotifications) { v -> update { it.copy(swipeDownNotifications = v) } }
                SwitchRow("Double tap to lock", s.doubleTapLock) { v -> update { it.copy(doubleTapLock = v) } }
                SwitchRow("Open keyboard in All apps", s.autoKeyboard) { v -> update { it.copy(autoKeyboard = v) } }
            }

            section("Permissions") {
                PermissionRow("Default home app", status.home) {
                    Permissions.launch(ctx, Permissions.defaultLauncherIntent(ctx))
                }
                PermissionRow("Usage access", status.usage) { Permissions.launch(ctx, Permissions.usageAccessIntent(ctx)) }
                PermissionRow("Notification access", status.notifications) {
                    Permissions.launch(ctx, Permissions.notificationAccessIntent(ctx))
                }
                PermissionRow("Display over other apps", status.overlay) {
                    Permissions.launch(ctx, Permissions.overlayIntent(ctx))
                }
                PermissionRow("Battery", status.battery) { Permissions.launch(ctx, Permissions.batteryIntent(ctx)) }
                if (Permissions.isXiaomi()) {
                    ValueRow("Show on lock screen and pop-ups", "Open", onClick = {
                        Permissions.launch(ctx, Permissions.xiaomiLockScreenIntent(ctx))
                    })
                }
            }

            section("More") {
                ValueRow("About", "Quiet ${BuildConfig.VERSION_NAME}", onClick = null)
                Text(
                    "Licences",
                    QType.body(), Q.colors.ink, Modifier.padding(horizontal = Gutter).padding(top = 16.dp),
                )
                Text(
                    "Fraunces, Geist, Bodoni Moda, Instrument Serif and Big Shoulders are used under the SIL Open Font Licence. " +
                        "Artwork is in the public domain and comes from open museum collections; credits are shown with each work.",
                    QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(top = 6.dp),
                )
            }
        }
    }

    openArt?.let { w ->
        ArtworkDialog(
            work = w,
            showing = today?.id == w.id,
            onDismiss = { openArt = null },
            onShow = {
                openArt = null
                scope.launch {
                    container.art.show(w.id)
                    container.settings.update { it.copy(lockStyle = LockStyle.MASTERPIECE) }
                }
            },
            onRemove = {
                openArt = null
                scope.launch { container.art.removeUserArtwork(w.id) }
            },
        )
    }

    pendingUri?.let { uri ->
        AddArtworkDialog(
            onDismiss = { pendingUri = null },
            onSave = { title, artist ->
                pendingUri = null
                scope.launch(Dispatchers.IO) { container.art.addUserArtwork(uri, title, artist) }
            },
        )
    }
}

private fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

// Building blocks

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 36.dp)) {
            Text(title, QType.title(28.sp), Q.colors.ink, Modifier.padding(horizontal = Gutter).padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun Text(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.foundation.text.BasicText(
        text, modifier, style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, QType.secondary(), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(top = 16.dp, bottom = 10.dp))
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
        Text(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
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
            Box(
                Modifier.padding(4.dp).offset(x = x).size(20.dp).clip(RoundedCornerShape(10.dp)).background(thumb),
            )
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)?) {
    RowBase(onClick) {
        Text(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, QType.secondary(), Q.colors.muted, Modifier.padding(start = 12.dp))
        if (onClick != null) {
            Box(Modifier.padding(start = 8.dp)) { Chevron(Q.colors.edge, left = false) }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    RowBase(onClick) {
        Text(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
        Text(
            if (granted) "On" else "Needs access", QType.secondary(),
            if (granted) Q.colors.ink else Q.colors.muted,
        )
    }
}

@Composable
private fun StepperRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    RowBase(onClick = null) {
        Text(title, QType.body(), Q.colors.ink, Modifier.weight(1f))
        StepButton(plus = false, onMinus)
        Text(value, QType.secondary(), Q.colors.ink, Modifier.width(84.dp), TextAlign.Center)
        StepButton(plus = true, onPlus)
    }
}

@Composable
private fun StepButton(plus: Boolean, onClick: () -> Unit) {
    val color = Q.colors.ink
    Box(
        Modifier.size(44.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = 1.5.dp.toPx()
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
            if (plus) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun Chevron(color: Color, left: Boolean) {
    Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
        val w = 1.6.dp.toPx()
        val a = if (left) size.width else 0f
        val b = if (left) 0f else size.width
        drawLine(color, Offset(a, 0f), Offset(b, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(b, size.height / 2), Offset(a, size.height), w, StrokeCap.Round)
    }
}

@Composable
private fun UnderlineField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    textAlign: TextAlign = TextAlign.End,
    onValue: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = QType.body().copy(color = Q.colors.ink, textAlign = textAlign),
        cursorBrush = SolidColor(Q.colors.ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, QType.body(), Q.colors.faint, align = textAlign)
                inner()
            }
        },
    )
}

@Composable
private fun TextFieldRow(title: String, value: String, placeholder: String, onValue: (String) -> Unit) {
    // Keep a local copy so typing is never fought by the async settings round trip.
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    RowBase(onClick = null) {
        Text(title, QType.body(), Q.colors.ink, Modifier.width(132.dp))
        UnderlineField(text, placeholder, Modifier.weight(1f)) { v -> text = v; onValue(v) }
    }
}

@Composable
private fun CoordinateRow(title: String, value: Double, min: Double, max: Double, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = value.toString()
    }
    RowBase(onClick = null) {
        Text(title, QType.body(), Q.colors.ink, Modifier.width(132.dp))
        UnderlineField(text, "0.0", Modifier.weight(1f), KeyboardType.Decimal) { v ->
            text = v
            v.toDoubleOrNull()?.takeIf { it in min..max }?.let(onValue)
        }
    }
}

@Composable
private fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = Gutter).fillMaxWidth().height(44.dp)
            .border(1.dp, Q.colors.edge, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxSize().background(if (on) Q.colors.ink else Color.Transparent).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, QType.secondary(), if (on) Q.colors.bg else Q.colors.ink)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(36.dp).clip(shape)
            .background(if (on) Q.colors.ink else Color.Transparent)
            .border(1.dp, if (on) Q.colors.ink else Q.colors.edge, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, QType.secondary(14.sp), if (on) Q.colors.bg else Q.colors.ink)
    }
}

@Composable
private fun FontChoiceRow(current: LockFont, onSelect: (LockFont) -> Unit) {
    Label("Lock font")
    Row(Modifier.padding(horizontal = Gutter).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LockFont.entries.forEach { font ->
            val face = lockFace(font)
            val on = font == current
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).clip(shape)
                    .border(if (on) 1.5.dp else 1.dp, if (on) Q.colors.ink else Q.colors.edge, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(font) }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.text.BasicText(
                    "Aa",
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = face.display, fontSize = 36.sp, color = Q.colors.ink,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(face.name, QType.small(), if (on) Q.colors.ink else Q.colors.muted, align = TextAlign.Center)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AddArtworkDialog(onDismiss: () -> Unit, onSave: (title: String, artist: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(20.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Q.colors.raised).border(1.dp, Q.colors.line, shape).padding(24.dp),
        ) {
            Text("Add your own artwork", QType.title(24.sp), Q.colors.ink)
            Spacer(Modifier.height(16.dp))
            Text("Title", QType.secondary(), Q.colors.muted)
            UnderlineField(title, "Untitled", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Start) { title = it }
            Hairline0()
            Spacer(Modifier.height(16.dp))
            Text("Artist", QType.secondary(), Q.colors.muted)
            UnderlineField(artist, "Unknown", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Start) { artist = it }
            Hairline0()
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton("Cancel", false, onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogButton("Add", true) {
                    onSave(title.trim().ifEmpty { "Untitled" }, artist.trim().ifEmpty { "Unknown" })
                }
            }
        }
    }
}

/** Your uploaded artworks as small framed thumbnails; tap one to show it now or remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YourArtworks(works: List<Artwork>, currentId: String?, onOpen: (Artwork) -> Unit) {
    if (works.isEmpty()) {
        Text(
            "Artworks you add appear here. A new one goes on the lock screen straight away, then comes back about once a week.",
            QType.secondary(14.sp), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(bottom = 8.dp),
        )
        return
    }
    Label("Your artworks  ${works.size}")
    Text(
        "Each one shows on the lock screen the day you add it, then about once a week. Tap one to show it now or remove it.",
        QType.secondary(14.sp), Q.colors.muted, Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp),
    )
    FlowRow(
        Modifier.padding(horizontal = Gutter).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        works.forEach { w ->
            val bmp = app.quiet.launcher.feature.lock.rememberFileBitmap(w.imagePath, maxSide = 360)
            val on = w.id == currentId
            val shape = RoundedCornerShape(10.dp)
            Column(Modifier.width(96.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(w) }) {
                Box(
                    Modifier.size(96.dp).clip(shape).background(Q.colors.raised)
                        .border(if (on) 2.dp else 1.dp, if (on) Q.colors.ink else Q.colors.line, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bmp.asImageBitmap(), contentDescription = w.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().padding(if (on) 2.dp else 1.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(w.title, QType.small(), Q.colors.ink, maxLines = 1)
                Text(if (on) "Showing today" else w.artist, QType.small(), Q.colors.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ArtworkDialog(work: Artwork, showing: Boolean, onDismiss: () -> Unit, onShow: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val bmp = app.quiet.launcher.feature.lock.rememberFileBitmap(work.imagePath, maxSide = 1000)
    var confirmRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Q.colors.raised).border(1.dp, Q.colors.line, shape).padding(24.dp),
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bmp.asImageBitmap(), contentDescription = work.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(work.title, QType.title(22.sp), Q.colors.ink)
            Text(work.artist, QType.secondary(), Q.colors.muted)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton(if (confirmRemove) "Remove for good" else "Remove", false) {
                    if (confirmRemove) onRemove() else confirmRemove = true
                }
                Spacer(Modifier.width(8.dp))
                DialogButton(if (showing) "Showing today" else "Show now", true) { if (showing) onDismiss() else onShow() }
            }
        }
    }
}

@Composable
private fun Hairline0() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.colors.line))
}

@Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.height(44.dp).clip(shape)
            .background(if (primary) Q.colors.ink else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, QType.secondary(), if (primary) Q.colors.bg else Q.colors.ink) }
}
