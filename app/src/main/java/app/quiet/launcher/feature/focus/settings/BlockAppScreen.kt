package app.quiet.launcher.feature.focus.settings

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.ui.*
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Block durations on the discrete slider, in hours: 1 h to 30 days. */
internal val BlockStops = listOf(1, 2, 4, 8, 12, 24, 48, 72, 168, 336, 720)

internal fun blockStopLabel(hours: Int): String = when {
    hours < 72 -> if (hours == 1) "1 hour" else "$hours hours"
    else -> "${hours / 24} days"
}

private fun blockStopShort(hours: Int): String = if (hours < 72) "${hours}h" else "${hours / 24}d"

/**
 * Block an app for 1 hour to 30 days (PLAN B1 to B3): a discrete slider, "Also block"
 * suggestions ranked by 7-day screen time, and a slide-to-block control. A block can only be
 * made longer here, never shorter.
 */
@Composable
fun BlockAppScreen(packageName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val rt = remember { FocusRuntime.get(ctx) }
    val appScope = ctx.container.appScope
    val apps = rememberApps()
    val week = rememberWeekUsage()
    val blocks by rt.repo.blocks.collectAsStateWithLifecycle()
    var stop by rememberSaveable { mutableIntStateOf(2) }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    BackHandler(onBack = onBack)

    val label = remember(packageName, apps) { apps.firstOrNull { it.pkg == packageName }?.label ?: rt.labelFor(packageName) }
    val hours = BlockStops[stop]
    val now = remember(stop) { System.currentTimeMillis() }
    val until = now + hours * 3_600_000L
    val zone = remember { ZoneId.systemDefault() }
    val is24h = remember { DateFormat.is24HourFormat(ctx) }
    val untilText = (FocusCopy.blockedDate(until, now, zone)?.removePrefix("on ")?.replaceFirstChar { it.uppercase() } ?: "Today") +
        ", " + FocusCopy.clock(until, zone, is24h)
    val suggestions = remember(apps, week, packageName) {
        apps.filter { it.pkg != packageName && (week[it.pkg] ?: 0L) >= 60_000L }
            .sortedByDescending { week[it.pkg] ?: 0L }
            .take(8)
    }
    val existing = blocks[packageName]?.takeIf { it.untilMs > System.currentTimeMillis() }

    FocusScreen("Block $label", onBack) {
        item {
            val spent = week[packageName]
            Note(
                if (spent != null && spent >= 60_000L) {
                    "You spent ${Format.duration(spent)} on $label in the last 7 days. A break does not need an uninstall: block it for up to 30 days."
                } else "Take a break without uninstalling. Block it for up to 30 days.",
            )
            if (existing != null) {
                Note("Already blocked until ${FocusCopy.clock(existing.untilMs, zone, is24h)}${FocusCopy.blockedDate(existing.untilMs, System.currentTimeMillis(), zone)?.let { " $it" } ?: ""}. A new block can only make it longer.")
            }
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 16.dp)) {
                Txt(blockStopLabel(hours), QType.number(44.sp), c.ink)
                Txt("Until $untilText", QType.secondary(), c.muted, Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                DiscreteSlider(BlockStops.size, stop, { stop = it }) { blockStopLabel(BlockStops[it]) }
                Row(Modifier.fillMaxWidth()) {
                    Txt(blockStopShort(BlockStops.first()), QType.small(), c.muted, Modifier.weight(1f))
                    Txt(blockStopShort(BlockStops.last()), QType.small(), c.muted, Modifier.weight(1f), TextAlign.End)
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            item {
                SectionTitle("Also block")
                Note("Screen time in the last 7 days.")
            }
            items(suggestions, key = { it.pkg }) { s ->
                AppCheckRow(s.label, weekLine(week[s.pkg])?.substringBefore(" in the last"), s.pkg in chosen) {
                    chosen = if (s.pkg in chosen) chosen - s.pkg else chosen + s.pkg
                }
            }
        }
        item {
            Spacer(Modifier.height(32.dp))
            val count = 1 + chosen.size
            SlideToConfirm(
                if (count == 1) "Slide to block" else "Slide to block $count apps",
                Modifier.padding(horizontal = ListGutter),
            ) {
                val targets = listOf(packageName) + chosen
                appScope.launch { rt.repo.block(targets, until) }
                rt.toast(if (count == 1) "$label is blocked until $untilText." else "$count apps are blocked until $untilText.")
                onBack()
            }
        }
    }
}
