package app.quiet.launcher.feature.focus.settings

import android.app.NotificationManager
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.ui.*
import java.time.ZoneId

/**
 * Start a focus session: one line of intention, a length, the apps that may still open, and
 * Do Not Disturb when Quiet has notification-policy access. While a session runs, this screen
 * offers to open it instead. [onStarted] runs after the session screen has been launched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusSessionStart(onBack: () -> Unit, onStarted: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Q.colors
    val rt = remember { FocusRuntime.get(ctx) }
    val snap by rt.repo.snapshot.collectAsStateWithLifecycle()
    val apps = rememberApps()
    val week = rememberWeekUsage()
    var intention by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(50) }
    var allowed by rememberSaveable { mutableStateOf(app.quiet.launcher.feature.quick.QuickActions.lastAllowed(ctx).toList()) }
    var dnd by rememberSaveable { mutableStateOf(true) }
    var policy by remember { mutableStateOf(hasPolicyAccess(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { policy = hasPolicyAccess(ctx) }
    BackHandler(onBack = onBack)
    val defaults = snap.defaultWatched
    // A to Z (accent- and case-insensitive), filtered by the search line.
    var query by rememberSaveable { mutableStateOf("") }
    val collator = remember { java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY } }
    val sorted = remember(apps) { apps.sortedWith { a, b -> collator.compare(a.label, b.label) } }
    val list = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) sorted else sorted.filter { it.label.contains(q, ignoreCase = true) }
    }

    val running = snap.session?.takeIf { it.endsMs > System.currentTimeMillis() }
    FocusScreen("Focus session", onBack, "A countdown, one intention, and only the apps you choose.") {
        if (running != null) {
            item {
                val is24h = DateFormat.is24HourFormat(ctx)
                Note("A session is running until ${FocusCopy.clock(running.endsMs, ZoneId.systemDefault(), is24h)}.")
                Column(Modifier.padding(horizontal = ListGutter).padding(top = 8.dp)) {
                    SolidButton("Open it") { FocusSessionActivity.open(ctx) }
                }
            }
            return@FocusScreen
        }
        item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 16.dp)) {
                Txt("What will you do?", QType.question(28.sp), c.ink)
                LineField(intention, "Finish the chapter draft", { intention = it.take(80) }, Modifier.padding(top = 8.dp), QType.body(19.sp))
                Txt("How long?", QType.question(28.sp), c.ink, Modifier.padding(top = 32.dp, bottom = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(25, 50, 90).forEach { m -> FocusChip("$m min", selected = minutes == m) { minutes = m } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepButton(plus = false, label = "Shorter") { minutes = (minutes - 5).coerceAtLeast(5) }
                    Txt(FocusCopy.duration(minutes * 60_000L), QType.number(28.sp), c.ink, Modifier.weight(1f), TextAlign.Center)
                    StepButton(plus = true, label = "Longer") { minutes = (minutes + 5).coerceAtMost(240) }
                }
            }
            SectionTitle("Notifications")
            SwitchRow(
                "Silence notifications", dnd && policy,
                if (policy) "Do Not Disturb for the session, then back as it was" else "Needs Do Not Disturb access",
            ) { v ->
                if (!policy) {
                    open(ctx, notificationPolicyIntent())
                } else {
                    dnd = v
                }
            }
            SectionTitle("Apps you may open")
            Note("Everything else stays closed until the session ends. Your phone app and Quiet always stay open.")
            LineField(query, "Search apps", { query = it.take(40) }, Modifier.padding(horizontal = ListGutter).padding(top = 8.dp, bottom = 4.dp), QType.body(17.sp))
            if (allowed.isNotEmpty()) {
                Note("${allowed.size} chosen")
            }
        }
        items(list, key = { it.pkg }) { app ->
            AppCheckRow(app.label, null, app.pkg in allowed) {
                allowed = if (app.pkg in allowed) allowed - app.pkg else allowed + app.pkg
            }
        }
        item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 28.dp)) {
                SolidButton("Start ${FocusCopy.duration(minutes * 60_000L)}") {
                    app.quiet.launcher.feature.quick.QuickActions.rememberAllowed(ctx, allowed.toSet())
                    rt.startSession(minutes, intention, allowed.toSet(), dnd && policy)
                    onStarted()
                }
            }
        }
    }
}

private fun hasPolicyAccess(ctx: android.content.Context): Boolean =
    ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
