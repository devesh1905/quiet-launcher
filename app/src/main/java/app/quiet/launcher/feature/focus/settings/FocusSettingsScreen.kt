package app.quiet.launcher.feature.focus.settings

import app.quiet.launcher.core.platform.rememberAppCategories
import app.quiet.launcher.designsystem.AppPreset
import app.quiet.launcher.designsystem.PresetChips
import app.quiet.launcher.designsystem.presetsFor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.platform.Permissions
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.engine.AppRule
import app.quiet.launcher.feature.focus.engine.Behaviour
import app.quiet.launcher.feature.focus.engine.FocusConfig
import app.quiet.launcher.feature.focus.ui.*
import kotlinx.coroutines.launch

/**
 * In-app reminder settings: global switches, what happens when time is up, and the per-app
 * list (search, switch, 7-day minutes; tap a row for its own pause, prompt, limit and
 * behaviour). The optional callbacks add links to the other focus screens.
 */
@Composable
fun FocusSettingsScreen(
    onBack: () -> Unit,
    onOpenSchedules: (() -> Unit)? = null,
    onOpenBlockApp: ((packageName: String) -> Unit)? = null,
    onOpenFocusSession: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val rt = remember { FocusRuntime.get(ctx) }
    val appScope = ctx.container.appScope
    val prefs by rt.repo.prefs.collectAsStateWithLifecycle()
    val rules by rt.repo.rules.collectAsStateWithLifecycle()
    val defaults by rt.repo.defaultWatched.collectAsStateWithLifecycle()
    val apps = rememberApps()
    val week = rememberWeekUsage()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf(Permissions.hasUsageAccess(ctx)) }
    var overlay by remember { mutableStateOf(Permissions.canDrawOverlays(ctx)) }
    var battery by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usage = Permissions.hasUsageAccess(ctx)
        overlay = Permissions.canDrawOverlays(ctx)
        battery = Permissions.isIgnoringBatteryOptimizations(ctx)
    }
    BackHandler(onBack = onBack)

    val config = prefs.config
    fun setConfig(t: (FocusConfig) -> FocusConfig) {
        appScope.launch { rt.repo.updateConfig(t) }
    }
    fun setRule(pkg: String, t: (AppRule) -> AppRule) {
        appScope.launch { rt.repo.updateRule(pkg, t) }
    }

    var preset by remember { mutableStateOf<AppPreset>(AppPreset.All) }
    val cats = rememberAppCategories(apps.map { it.pkg })
    val list = remember(apps, week, query, preset, cats) {
        val q = query.trim().lowercase()
        apps.filter { (q.isEmpty() || it.label.lowercase().contains(q)) && preset.matches(cats[it.pkg]) }
            .sortedWith(compareByDescending<AppItem> { week[it.pkg] ?: 0L }.thenBy { it.label.lowercase() })
    }

    FocusScreen("Focus", onBack, "Pauses, reminders and blocks for the apps that pull at you.") {
        if (!usage || !overlay || !battery || Permissions.isXiaomi()) item {
            SectionTitle("Needed to work")
            if (!usage) NavRow("Usage access", "Off", "To know which app is open") { open(ctx, Permissions.usageAccessIntent(ctx)) }
            if (!overlay) NavRow("Display over other apps", "Off", "To show pauses and the time bar") { open(ctx, Permissions.overlayIntent(ctx)) }
            if (!battery) NavRow("Run in the background", "Off", "So Android does not stop the reminders") { open(ctx, Permissions.batteryIntent(ctx)) }
            if (Permissions.isXiaomi()) NavRow("Autostart", "", "On Xiaomi phones, allow Quiet to start by itself") {
                try {
                    ctx.startActivity(xiaomiAutostartIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    open(ctx, appDetails(ctx))
                }
            }
        }

        item {
            SectionTitle("In-app reminder")
            SwitchRow("In-app reminder", config.reminderEnabled, "Asks how long before a watched app opens") { v -> setConfig { it.copy(reminderEnabled = v) } }
            SwitchRow(
                "Floating time bar", config.floatingBar && !prefs.floatingHiddenAlways,
                if (prefs.floatingHiddenAlways) "You hid it. Turn on to show it again" else "Time left, shown over the app",
                enabled = config.reminderEnabled,
            ) { v ->
                setConfig { it.copy(floatingBar = v) }
                if (v) appScope.launch { rt.repo.setFloatingHiddenAlways(false) }
            }
            SwitchRow("Mindful launch delay", config.launchDelay, "A breathing pause before the app opens", enabled = config.reminderEnabled) { v ->
                setConfig { it.copy(launchDelay = v) }
            }
            SwitchRow(
                "Longer pause each time", config.progressiveFriction, "5, then 10, then 20 seconds per open today",
                enabled = config.reminderEnabled && config.launchDelay,
            ) { v -> setConfig { it.copy(progressiveFriction = v) } }
            SwitchRow("Mindful extension delay", config.extensionDelay, "A 15-second pause before more time", enabled = config.reminderEnabled) { v ->
                setConfig { it.copy(extensionDelay = v) }
            }
            SwitchRow("Ask why I am opening it", config.intentionPrompt, "One question before the pause", enabled = config.reminderEnabled) { v ->
                setConfig { it.copy(intentionPrompt = v) }
            }
        }

        item {
            SectionTitle("When time is up")
            Segmented(listOf("Only remind", "Close and block", "Cooldown"), config.behaviour.ordinal) { i ->
                setConfig { it.copy(behaviour = Behaviour.entries[i]) }
            }
            Note(behaviourNote(config.behaviour, config.blockMinutes))
            if (config.behaviour != Behaviour.ONLY_REMIND) {
                StepperRow(
                    if (config.behaviour == Behaviour.EXIT_AND_BLOCK) "Block for" else "Cooldown for",
                    "${config.blockMinutes} min",
                    onMinus = { setConfig { it.copy(blockMinutes = (it.blockMinutes - 5).coerceAtLeast(5)) } },
                    onPlus = { setConfig { it.copy(blockMinutes = (it.blockMinutes + 5).coerceAtMost(240)) } },
                )
            }
        }

        item {
            SectionTitle("Apps")
            Note("On by default for social, video and game apps. Tap an app for its own pause and daily limit.")
            SearchField(query, "Search apps", { query = it }, Modifier.padding(bottom = 8.dp))
            PresetChips(
                presetsFor(cats), preset, { preset = it }, Modifier.padding(bottom = 8.dp), gutter = ListGutter,
                selectAllLabel = "Remind me for all ${list.size}",
                onSelectAll = { list.forEach { a -> setRule(a.pkg) { it.copy(reminder = true) } } },
            )
        }

        items(list, key = { it.pkg }) { app ->
            val rule = rules[app.pkg] ?: AppRule(app.pkg, reminder = app.pkg in defaults)
            AppRuleRow(
                app = app,
                rule = rule,
                config = config,
                weekMs = week[app.pkg],
                expanded = expanded == app.pkg,
                onExpand = { expanded = if (expanded == app.pkg) null else app.pkg },
                onRule = { t -> setRule(app.pkg, t) },
                onBlock = onOpenBlockApp?.let { cb -> { cb(app.pkg) } },
            )
        }

        if (onOpenSchedules != null || onOpenFocusSession != null) item {
            SectionTitle("More")
            onOpenSchedules?.let { NavRow("Blocking schedules", subtitle = "Block apps at set times of day", onClick = it) }
            onOpenFocusSession?.let { NavRow("Focus session", subtitle = "A countdown with only the apps you choose", onClick = it) }
        }
    }
}

private fun behaviourNote(b: Behaviour, minutes: Int): String = when (b) {
    Behaviour.ONLY_REMIND -> "A reminder screen. You decide whether to leave or take more time."
    Behaviour.EXIT_AND_BLOCK -> "The app closes and stays blocked for $minutes minutes."
    Behaviour.COOLDOWN -> "The app closes. Opening it again within $minutes minutes needs a one-minute pause."
}

@Composable
private fun AppRuleRow(
    app: AppItem,
    rule: AppRule,
    config: FocusConfig,
    weekMs: Long?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onRule: ((AppRule) -> AppRule) -> Unit,
    onBlock: (() -> Unit)?,
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    Column {
        RowBase(onClick = onExpand) {
            Column(Modifier.weight(1f)) {
                Txt(app.label, QType.body(), c.ink, maxLines = 1)
                val parts = listOfNotNull(
                    weekLine(weekMs),
                    rule.budgetMinutes.takeIf { it > 0 && rule.reminder }?.let { "limit ${it} min a day" },
                )
                if (parts.isNotEmpty()) Txt(parts.joinToString(", ").replaceFirstChar { it.uppercase() }, QType.secondary(14.sp), c.muted)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.clickable(interactionSource = null, indication = null) { onRule { it.copy(reminder = !it.reminder) } }) {
                QSwitch(rule.reminder)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduced) fadeIn() else expandVertically() + fadeIn(),
            exit = if (reduced) fadeOut() else shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                SwitchRow("Watch this app", rule.reminder) { v -> onRule { it.copy(reminder = v) } }
                SwitchRow("Launch delay", rule.launchDelay ?: config.launchDelay, enabled = rule.reminder) { v -> onRule { it.copy(launchDelay = v) } }
                SwitchRow("Extension delay", rule.extensionDelay ?: config.extensionDelay, enabled = rule.reminder) { v -> onRule { it.copy(extensionDelay = v) } }
                SwitchRow("Ask why", rule.intention ?: config.intentionPrompt, enabled = rule.reminder) { v -> onRule { it.copy(intention = v) } }
                StepperRow(
                    "Daily limit", if (rule.budgetMinutes <= 0) "None" else "${rule.budgetMinutes} min",
                    onMinus = { onRule { it.copy(budgetMinutes = (it.budgetMinutes - 10).coerceAtLeast(0)) } },
                    onPlus = { onRule { it.copy(budgetMinutes = (it.budgetMinutes + 10).coerceAtMost(600)) } },
                )
                Txt("When time is up", QType.secondary(), c.muted, Modifier.padding(horizontal = ListGutter).padding(top = 14.dp, bottom = 8.dp))
                val idx = rule.behaviour?.let { it.ordinal + 1 } ?: 0
                Segmented(listOf("As set", "Remind", "Block", "Cooldown"), idx) { i ->
                    onRule { it.copy(behaviour = if (i == 0) null else Behaviour.entries[i - 1]) }
                }
                if (onBlock != null) {
                    Spacer(Modifier.height(4.dp))
                    NavRow("Block ${app.label}", subtitle = "For an hour, or up to 30 days", onClick = onBlock)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
