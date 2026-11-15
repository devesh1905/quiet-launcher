package app.quiet.launcher.feature.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.platform.Permissions
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QMotion
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.designsystem.pressable
import app.quiet.launcher.feature.apps.QuietSheet
import app.quiet.launcher.feature.apps.SheetAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Gutter = 24.dp
private val RowHeight = 56.dp

@Composable
fun NotificationFilterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val filter = remember { NotificationFilter.get(ctx) }
    val scope = rememberCoroutineScope()
    val settings by filter.settings.collectAsStateWithLifecycle(FilterSettings())
    val held by filter.held.collectAsStateWithLifecycle(emptyList())
    val apps by ctx.container.apps.apps.collectAsStateWithLifecycle()

    var access by remember { mutableStateOf(Permissions.hasNotificationAccess(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = Permissions.hasNotificationAccess(ctx) }

    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }

    val postPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setEnabled(on: Boolean) {
        scope.launch { filter.setEnabled(on) }
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) postPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val labels = remember(apps) { apps.groupBy { it.key.packageName }.mapValues { it.value.first().label } }
    fun labelFor(pkg: String): String = labels[pkg] ?: runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString()
    }.getOrDefault(pkg)

    fun open(n: HeldNotification) {
        val entry = apps.firstOrNull { it.key.packageName == n.packageName && it.key.userSerial == n.userSerial }
            ?: apps.firstOrNull { it.key.packageName == n.packageName }
        if (entry != null) {
            ctx.container.apps.launch(entry.key)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(n.packageName)?.let {
                runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    BackHandler(onBack = onBack)

    val groups = remember(held) { held.groupBy { it.packageName to it.userSerial }.values.toList() }
    val defaults = remember { filter.defaultAllowed() }
    val listApps = remember(apps, query) {
        val q = query.trim()
        apps.asSequence()
            .filter { it.key.packageName != ctx.packageName && it.key.packageName !in NotificationRules.alwaysPassed }
            .distinctBy { it.key.packageName }
            .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
            .toList()
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item(key = "top") {
                Column {
                    Box(
                        Modifier.padding(start = 8.dp, top = 4.dp).size(44.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack,
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Chevron(c.ink) }
                    Txt(
                        "Notifications", QType.title(40.sp), c.ink,
                        Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 12.dp),
                    )
                }
            }

            if (!access) {
                item(key = "access") {
                    Column(
                        Modifier.fillMaxWidth()
                            .pressable(pressedScale = 0.99f) {
                                Permissions.launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            .padding(horizontal = Gutter, vertical = 12.dp),
                    ) {
                        Txt("Notification access is off", QType.body(), c.ink)
                        Txt(
                            "Quiet cannot hold anything until you allow it. Tap to open the setting.",
                            QType.secondary(), c.muted, Modifier.padding(top = 2.dp),
                        )
                    }
                    Hairline()
                }
            }

            item(key = "master") {
                SwitchRow("Hold notifications", settings.enabled) { setEnabled(it) }
                Txt(
                    "Notifications from apps you have not allowed are taken out of your shade and kept here for seven days. " +
                        "Calls, messages, calendar and alarms stay as they are until you change that.",
                    QType.secondary(), c.muted, Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 16.dp),
                )
            }

            if (settings.enabled) {
                item(key = "ongoing") {
                    SwitchRow("Also hide ongoing notifications", settings.hideOngoing) { v ->
                        scope.launch { filter.setHideOngoing(v) }
                    }
                    Txt(
                        "Ongoing notifications cannot be cleared, so Quiet snoozes them for a day. " +
                            "They can come back after a restart, and some apps post them again right away.",
                        QType.secondary(), c.muted, Modifier.padding(horizontal = Gutter).padding(top = 12.dp, bottom = 8.dp),
                    )
                }
            }

            item(key = "tabs") {
                Spacer(Modifier.height(16.dp))
                Tabs(listOf("Held", "Allowed apps"), tab) { tab = it }
            }

            if (tab == 0) {
                if (held.isEmpty()) {
                    item(key = "empty") {
                        Txt(
                            if (settings.enabled) "Nothing is being held. Notifications from apps you have not allowed will wait here."
                            else "Nothing held. Turn on the switch above and they will wait here.",
                            QType.body(), c.muted, Modifier.padding(horizontal = Gutter, vertical = 28.dp),
                        )
                    }
                } else {
                    item(key = "clear") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Gutter).height(52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Txt(
                                if (held.size == 1) "1 notification" else "${held.size} notifications",
                                QType.secondary(), c.muted, Modifier.weight(1f),
                            )
                            Box(Modifier.height(44.dp).pressable { confirmClear = true }.padding(start = 16.dp), contentAlignment = Alignment.CenterEnd) {
                                Txt("Clear all", QType.secondary(), c.ink)
                            }
                        }
                    }
                    groups.forEach { group ->
                        val first = group.first()
                        item(key = "g-${first.packageName}-${first.userSerial}") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 24.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Txt(labelFor(first.packageName), QType.labelTitle(22.sp), c.ink, Modifier.weight(1f))
                                Txt(group.size.toString(), QType.secondary(), c.muted)
                            }
                        }
                        items(group, key = { "n-${it.id}" }) { n ->
                            SwipeToDismissRow(onDismiss = { scope.launch { filter.remove(n.id) } }) {
                                HeldRow(n, NotificationRules.relativeTime(now, n.postedAt)) { open(n) }
                            }
                        }
                    }
                }
            } else {
                item(key = "search") {
                    SearchField(query, "Search apps") { query = it }
                    Txt(
                        if (settings.enabled) "Allow notifications from" else "Allow notifications from. These apply once holding is on.",
                        QType.secondary(), c.muted, Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 8.dp),
                    )
                }
                if (listApps.isEmpty()) {
                    item(key = "none") {
                        Txt("No apps match.", QType.body(), c.muted, Modifier.padding(horizontal = Gutter, vertical = 16.dp))
                    }
                }
                items(listApps, key = { "a-${it.key.packageName}" }) { app: AppEntry ->
                    val pkg = app.key.packageName
                    SwitchRow(app.label, settings.isAllowed(pkg, defaults)) { v ->
                        scope.launch { filter.setAllowed(pkg, v) }
                    }
                }
            }
        }

        QuietSheet(visible = confirmClear, onDismiss = { confirmClear = false }) {
            Txt(
                "Clear everything held?", QType.labelTitle(24.sp), c.ink,
                Modifier.padding(vertical = 12.dp),
            )
            Txt(
                "These notifications are only kept here. Once cleared, they cannot be brought back.",
                QType.secondary(), c.muted, Modifier.padding(bottom = 12.dp),
            )
            SheetAction(if (held.size == 1) "Clear 1 notification" else "Clear ${held.size} notifications") {
                scope.launch { filter.clearAll() }
                confirmClear = false
            }
            SheetAction("Keep them") { confirmClear = false }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// Rows

@Composable
private fun HeldRow(n: HeldNotification, time: String, onClick: () -> Unit) {
    val c = Q.colors
    Column(
        Modifier.fillMaxWidth().background(c.bg).pressable(pressedScale = 0.99f, onClick = onClick)
            .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = Gutter, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Txt(
                n.title.ifEmpty { n.text }, QType.body(), c.ink, Modifier.weight(1f),
                maxLines = 1,
            )
            Txt(time, QType.small(), c.muted, Modifier.padding(start = 12.dp, top = 3.dp))
        }
        if (n.title.isNotEmpty() && n.text.isNotEmpty()) {
            Txt(n.text, QType.secondary(), c.muted, Modifier.padding(top = 2.dp), maxLines = 2)
        }
        if (n.meta.isNotEmpty()) {
            Txt(n.meta, QType.small(), c.muted, Modifier.padding(top = 4.dp), maxLines = 1)
        }
    }
}

/**
 * Left swipe dismisses. The row follows the finger, rubber-bands to the right, and flies off on a
 * spring that inherits the release velocity when the projected fling passes 45% of the width.
 */
@Composable
private fun SwipeToDismissRow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Q.colors
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { ch, dx ->
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        val raw = offset.value + dx
                        scope.launch { offset.snapTo(if (raw > 0) QMotion.rubberBand(raw, widthPx * 0.1f) else raw) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        if (offset.value + QMotion.project(v) < -widthPx * 0.45f) {
                            scope.launch {
                                offset.animateTo(-widthPx, QMotion.Fling, initialVelocity = v)
                                onDismiss()
                            }
                        } else {
                            scope.launch { offset.animateTo(0f, QMotion.Return, initialVelocity = v) }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, QMotion.Return) } },
                )
            },
    ) {
        Box(
            Modifier.matchParentSize().padding(end = Gutter)
                .graphicsLayer { alpha = (-offset.value / widthPx * 5f).coerceIn(0f, 1f) },
            contentAlignment = Alignment.CenterEnd,
        ) { Txt("Dismiss", QType.secondary(), c.muted) }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Q.colors
    val pos by animateFloatAsState(selected.toFloat(), QMotion.Snappy, label = "tab")
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) { Txt(label, QType.body(), if (i == selected) c.ink else c.muted) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(2.dp).drawBehind {
                val y = size.height / 2
                drawLine(c.line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                val w = size.width / labels.size
                drawLine(c.ink, Offset(pos * w, y), Offset((pos + 1) * w, y), 1.5.dp.toPx())
            },
        )
    }
}

@Composable
private fun SearchField(value: String, placeholder: String, onValue: (String) -> Unit) {
    val c = Q.colors
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = QType.body().copy(color = c.ink), cursorBrush = SolidColor(c.ink),
        modifier = Modifier.padding(horizontal = Gutter).padding(top = 16.dp).fillMaxWidth()
            .border(1.dp, c.edge, RoundedCornerShape(14.dp)).padding(14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Txt(placeholder, QType.body(), c.muted)
                inner()
            }
        },
    )
}

// Building blocks (same look as Settings rows)

@Composable
private fun Hairline() {
    Box(Modifier.padding(horizontal = Gutter).fillMaxWidth().height(1.dp).background(Q.colors.line))
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(RowHeight)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
                .padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Txt(title, QType.body(), Q.colors.ink, Modifier.weight(1f), maxLines = 1)
            QuietSwitch(checked)
        }
        Hairline()
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

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
        val w = 1.6.dp.toPx()
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height), w, StrokeCap.Round)
    }
}

@Composable
private fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text, modifier, style = style.copy(color = color), maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}
