package app.quiet.launcher.feature.focus.settings

import app.quiet.launcher.core.platform.rememberAppCategories
import app.quiet.launcher.designsystem.AppPreset
import app.quiet.launcher.designsystem.PresetChips
import app.quiet.launcher.designsystem.presetsFor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.apps.QuietSheet
import app.quiet.launcher.feature.apps.SheetAction
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.engine.Schedule
import app.quiet.launcher.feature.focus.engine.ScheduleMath
import app.quiet.launcher.feature.focus.ui.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private sealed interface SchedulePage {
    data object List : SchedulePage
    data class Edit(val schedule: Schedule, val isNew: Boolean) : SchedulePage
    data object Guided : SchedulePage
    /** A running schedule: wait 15 s before [then]. */
    data class Wait(val schedule: Schedule, val then: SchedulePage?) : SchedulePage
}

/**
 * Blocking schedules (PLAN S1 to S4): list, add and edit (name, start, end, weekdays, apps),
 * turn off until tomorrow or permanently, and a guided setup from wake and bed times. Editing
 * or turning off a schedule that is running right now first asks for a 15-second pause.
 */
@Composable
fun SchedulesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rt = remember { FocusRuntime.get(ctx) }
    val schedules by rt.repo.schedules.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf<SchedulePage>(SchedulePage.List) }
    var turnOff by remember { mutableStateOf<Schedule?>(null) }
    val appScope = ctx.container.appScope
    val zone = remember { ZoneId.systemDefault() }

    fun isRunning(s: Schedule) = ScheduleMath.isActive(s, System.currentTimeMillis(), zone)

    Box(Modifier.fillMaxSize()) {
    when (val p = page) {
        SchedulePage.List -> {
            BackHandler(onBack = onBack)
            ScheduleList(
                schedules = schedules,
                onBack = onBack,
                onOpen = { s -> page = if (isRunning(s)) SchedulePage.Wait(s, SchedulePage.Edit(s, false)) else SchedulePage.Edit(s, false) },
                onToggle = { s, on ->
                    when {
                        on -> { appScope.launch { rt.repo.saveSchedule(s.copy(enabled = true, pausedUntil = null)) } }
                        isRunning(s) -> { page = SchedulePage.Wait(s, null) }
                        else -> { turnOff = s }
                    }
                },
                onNew = {
                    page = SchedulePage.Edit(
                        Schedule(UUID.randomUUID().toString(), "New schedule", 21 * 60, 7 * 60, DayOfWeek.entries.toSet(), emptySet(), createdMs = System.currentTimeMillis()),
                        isNew = true,
                    )
                },
                onGuided = { page = SchedulePage.Guided },
            )
        }
        is SchedulePage.Edit -> {
            BackHandler { page = SchedulePage.List }
            ScheduleEditor(
                initial = p.schedule,
                isNew = p.isNew,
                onBack = { page = SchedulePage.List },
                onSave = { s ->
                    appScope.launch { rt.repo.saveSchedule(s) }
                    page = SchedulePage.List
                },
                onDelete = {
                    appScope.launch { rt.repo.deleteSchedule(p.schedule.id) }
                    page = SchedulePage.List
                },
            )
        }
        SchedulePage.Guided -> {
            BackHandler { page = SchedulePage.List }
            GuidedSetup(
                onBack = { page = SchedulePage.List },
                onCreate = { list ->
                    appScope.launch { rt.repo.saveSchedules(list) }
                    page = SchedulePage.List
                },
            )
        }
        is SchedulePage.Wait -> {
            BackHandler { page = SchedulePage.List }
            WaitFirst(
                title = "${p.schedule.name} is running now.",
                detail = "Changing it waits 15 seconds, so the change is a choice and not a reflex.",
                onCancel = { page = SchedulePage.List },
                onDone = {
                    val next = p.then
                    if (next == null) {
                        turnOff = p.schedule
                        page = SchedulePage.List
                    } else {
                        page = next
                    }
                },
            )
        }
    }

    // Sheet stays outside the page switch so it overlays the list.
    val target = turnOff
    QuietSheet(visible = target != null, onDismiss = { turnOff = null }) {
        if (target == null) return@QuietSheet
        Txt("Turn off ${target.name}?", QType.labelTitle(24.sp), Q.colors.ink, Modifier.padding(vertical = 12.dp))
        SheetAction("Until tomorrow (turns back on by itself)") {
            appScope.launch { rt.repo.pauseScheduleUntilTomorrow(target) }
            turnOff = null
        }
        SheetAction("Turn off permanently") {
            appScope.launch { rt.repo.saveSchedule(target.copy(enabled = false, pausedUntil = null)) }
            turnOff = null
        }
        SheetAction("Keep it on") { turnOff = null }
        Spacer(Modifier.height(8.dp))
    }
    }
}

@Composable
private fun ScheduleList(
    schedules: List<Schedule>,
    onBack: () -> Unit,
    onOpen: (Schedule) -> Unit,
    onToggle: (Schedule, Boolean) -> Unit,
    onNew: () -> Unit,
    onGuided: () -> Unit,
) {
    val c = Q.colors
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val now = System.currentTimeMillis()
    FocusScreen("Blocking schedules", onBack, "Chosen apps stay closed at set times of day.") {
        if (schedules.isEmpty()) item {
            Note("No schedules yet. Start from your day, or make one yourself.")
        }
        items(schedules, key = { it.id }) { s ->
            val paused = s.pausedUntil?.isAfter(today) == true
            val on = s.enabled && !paused
            val status = when {
                !s.enabled -> "Off"
                paused -> "Off until tomorrow"
                ScheduleMath.isActive(s, now, zone) -> "Running now"
                else -> null
            }
            RowBase(onClick = { onOpen(s) }, minHeight = 72.dp) {
                Column(Modifier.weight(1f)) {
                    Txt(s.name, QType.body(), c.ink, maxLines = 1)
                    Txt(scheduleLine(s), QType.secondary(14.sp), c.muted)
                    if (status != null) Txt(status, QType.secondary(14.sp), if (status == "Running now") c.ink else c.muted)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.clickable(interactionSource = null, indication = null) { onToggle(s, !on) }) { QSwitch(on) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 28.dp)) {
                LineButton("New schedule", onClick = onNew)
                Spacer(Modifier.height(12.dp))
                LineButton("Set up from my day", onClick = onGuided)
            }
        }
    }
}

internal fun scheduleLine(s: Schedule): String {
    val next = if (s.overnight && s.startMinute != s.endMinute) " (next day)" else ""
    val apps = when (s.apps.size) {
        0 -> "no apps yet"
        1 -> "1 app"
        else -> "${s.apps.size} apps"
    }
    val days = ScheduleMath.daysLabel(s.days).let { if (it == "Every day") "every day" else it }
    return "${ScheduleMath.hhmm(s.startMinute)} to ${ScheduleMath.hhmm(s.endMinute)}$next, $days, $apps"
}

@Composable
private fun ScheduleEditor(initial: Schedule, isNew: Boolean, onBack: () -> Unit, onSave: (Schedule) -> Unit, onDelete: () -> Unit) {
    val c = Q.colors
    var s by remember(initial.id) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val apps = rememberApps()
    val week = rememberWeekUsage()
    var preset by remember { mutableStateOf<AppPreset>(AppPreset.All) }
    val cats = rememberAppCategories(apps.map { it.pkg })
    val list = remember(apps, week, query, preset, cats) {
        val q = query.trim().lowercase()
        apps.filter { (q.isEmpty() || it.label.lowercase().contains(q)) && preset.matches(cats[it.pkg]) }
            .sortedWith(compareByDescending<AppItem> { week[it.pkg] ?: 0L }.thenBy { it.label.lowercase() })
    }
    FocusScreen(if (isNew) "New schedule" else "Edit schedule", onBack) {
        item {
            Column(Modifier.padding(horizontal = ListGutter)) {
                Txt("Name", QType.secondary(), c.muted, Modifier.padding(top = 12.dp))
                LineField(s.name, "Evening without screens", { v -> s = s.copy(name = v.take(40)) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = ListGutter).padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt("Starts", QType.secondary(), c.muted)
                    TimePicker(s.startMinute, { s = s.copy(startMinute = it) })
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (s.overnight && s.startMinute != s.endMinute) "Ends the next day" else "Ends", QType.secondary(), c.muted)
                    TimePicker(s.endMinute, { s = s.copy(endMinute = it) })
                }
            }
            if (s.startMinute == s.endMinute) Note("Same start and end: the whole day.")
            Txt("Days", QType.secondary(), c.muted, Modifier.padding(horizontal = ListGutter).padding(top = 24.dp, bottom = 10.dp))
            WeekdayChips(s.days) { s = s.copy(days = it) }
            SectionTitle("Block these apps")
            Note("Sorted by screen time in the last 7 days.")
            SearchField(query, "Search apps", { query = it }, Modifier.padding(bottom = 8.dp))
            PresetChips(
                presetsFor(cats), preset, { preset = it }, Modifier.padding(bottom = 8.dp), gutter = ListGutter,
                selectAllLabel = "Block all ${list.size}",
                onSelectAll = { s = s.copy(apps = s.apps + list.map { it.pkg }) },
            )
        }
        items(list, key = { it.pkg }) { app ->
            AppCheckRow(app.label, weekLine(week[app.pkg])?.substringBefore(" in the last"), app.pkg in s.apps) {
                s = s.copy(apps = if (app.pkg in s.apps) s.apps - app.pkg else s.apps + app.pkg)
            }
        }
        item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 28.dp)) {
                val valid = s.name.isNotBlank() && s.days.isNotEmpty() && s.apps.isNotEmpty()
                SolidButton("Save", enabled = valid) { onSave(s.copy(name = s.name.trim())) }
                if (!valid) {
                    Txt("Give it a name, at least one day and one app.", QType.secondary(14.sp), c.muted, Modifier.fillMaxWidth().padding(top = 10.dp), TextAlign.Center)
                }
                if (!isNew) TextAction("Delete schedule", Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp), onClick = onDelete)
            }
        }
    }
}

@Composable
private fun GuidedSetup(onBack: () -> Unit, onCreate: (List<Schedule>) -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val rt = remember { FocusRuntime.get(ctx) }
    val defaults by rt.repo.defaultWatched.collectAsStateWithLifecycle()
    var wake by remember { mutableIntStateOf(7 * 60) }
    var bed by remember { mutableIntStateOf(23 * 60) }
    val preview = ScheduleMath.guided(wake, bed, defaults, { "" }, 0L)
    FocusScreen("Your day", onBack, "Two schedules from two times. You can change them later.") {
        item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 16.dp)) {
                Txt("When do you wake up?", QType.question(26.sp), c.ink)
                TimePicker(wake, { wake = it }, Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                Txt("When do you go to bed?", QType.question(26.sp), c.ink)
                TimePicker(bed, { bed = it }, Modifier.padding(top = 8.dp))
            }
            SectionTitle("This creates")
            preview.forEach { s ->
                RowBase(onClick = null, minHeight = 64.dp) {
                    Column {
                        Txt(s.name, QType.body(), c.ink)
                        Txt(scheduleLine(s), QType.secondary(14.sp), c.muted)
                    }
                }
            }
            Note(
                if (defaults.isEmpty()) "No social or video apps found yet. Add apps to each schedule afterwards."
                else "Both block the social, video and game apps Quiet watches. Add or remove apps afterwards.",
            )
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 16.dp)) {
                SolidButton("Create both") {
                    onCreate(ScheduleMath.guided(wake, bed, defaults, { UUID.randomUUID().toString() }, System.currentTimeMillis()))
                }
            }
        }
    }
}

/** A 15-second pause before changing something that is protecting the user right now. */
@Composable
internal fun WaitFirst(title: String, detail: String, onCancel: () -> Unit, onDone: () -> Unit) {
    val c = Q.colors
    val pause = rememberPause(15) {}
    Box(Modifier.fillMaxSize().background(c.bg).padding(horizontal = InterGutter)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Spacer(Modifier.height(48.dp))
            Txt(title, QType.question(32.sp), c.ink)
            Txt(detail, QType.secondary(), c.muted, Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(32.dp))
            BreathRing(pause, Modifier.align(Alignment.CenterHorizontally), diameter = 200.dp)
            Spacer(Modifier.weight(1f))
            SolidButton("Continue", enabled = pause.done, onClick = onDone)
            TextAction("Never mind", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), onClick = onCancel)
            Spacer(Modifier.height(16.dp))
        }
    }
}
