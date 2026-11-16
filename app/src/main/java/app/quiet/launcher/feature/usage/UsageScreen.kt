package app.quiet.launcher.feature.usage

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.UsageToday
import app.quiet.launcher.core.model.WeekUsage
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN = 60_000L

internal fun formatDuration(ms: Long): String {
    val totalMin = (ms / MIN).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

internal fun weekSentence(week: WeekUsage?): String? {
    if (week == null) return null
    val last = week.lastWeekTotalMs
    val now = week.thisWeekTotalMs
    if (last <= 0L) return "Not enough history yet to compare with last week."
    val pct = (abs(now - last).toDouble() / last * 100).roundToInt()
    return when {
        pct < 3 -> "About the same as last week."
        now < last -> "You used your phone $pct% less than last week."
        else -> "You used your phone $pct% more than last week."
    }
}

@Composable
fun UsageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val usage = remember { ctx.container.usage }
    val today by usage.today.collectAsState()
    val week by usage.week.collectAsState()
    val hasPermission by usage.hasPermission.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onBack)

    // Refresh on resume, then every 60 s while visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                usage.refresh()
                delay(60_000)
            }
        }
    }

    val c = Q.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        BackChevron(onBack)
        Text24("Screen time")
        if (!hasPermission) PermissionNote() else Content(today, week)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Text24(title: String) {
    androidx.compose.material3.Text(
        title, style = QType.title(), color = Q.colors.ink,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
}

@Composable
private fun T(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) = androidx.compose.material3.Text(text, style = style, color = color, modifier = modifier, textAlign = align)

@Composable
private fun BackChevron(onBack: () -> Unit) {
    val ink = Q.colors.ink
    Box(
        Modifier
            .size(44.dp)
            .offset(x = (-12).dp)
            .semantics { contentDescription = "Back" }
            .clickable(role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = 1.5.dp.toPx()
            val s = size
            drawLine(ink, Offset(s.width * 0.62f, s.height * 0.2f), Offset(s.width * 0.34f, s.height * 0.5f), w, StrokeCap.Round)
            drawLine(ink, Offset(s.width * 0.34f, s.height * 0.5f), Offset(s.width * 0.62f, s.height * 0.8f), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun PermissionNote() {
    val ctx = LocalContext.current
    val c = Q.colors
    T(
        "Quiet needs usage access to show how long you spend on your phone. " +
            "Everything stays on this device.",
        QType.body(), c.muted,
    )
    Box(
        Modifier
            .padding(top = 12.dp)
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        contentAlignment = Alignment.CenterStart,
    ) { T("Open usage access settings", QType.body(), c.ink) }
}

@Composable
private fun Content(today: UsageToday, week: WeekUsage?) {
    val c = Q.colors
    weekSentence(week)?.let {
        T(it, QType.labelTitle(), c.ink, Modifier.padding(bottom = 20.dp))
    }

    Row(verticalAlignment = Alignment.Bottom) {
        T(formatDuration(today.totalMs), QType.number(64.sp), c.ink)
        T(
            "of ${formatDuration(today.budgetMs)} budget", QType.secondary(), c.muted,
            Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
    }
    if (today.budgetMs > 0 && today.totalMs > today.budgetMs) {
        T("${formatDuration(today.totalMs - today.budgetMs)} over what you set.", QType.secondary(), c.muted, Modifier.padding(top = 8.dp))
    }

    if (week != null) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            T("7-day average ${formatDuration(week.thisWeekTotalMs / 7)}", QType.secondary(), c.muted)
            T("Last week ${formatDuration(week.lastWeekTotalMs / 7)} a day", QType.secondary(), c.muted)
        }
        Spacer(Modifier.height(20.dp))
        WeekChart(week)
    }

    SectionHeader("Today by app")
    val apps = today.perApp.filter { it.millis >= MIN }.take(10)
    if (apps.isEmpty()) {
        T("Nothing to show yet.", QType.secondary(), c.muted)
    } else {
        val top = apps.maxOf { it.millis }.coerceAtLeast(1L)
        apps.forEach { a ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T(a.label, QType.body(), c.ink, Modifier.weight(1f, fill = false))
                    T(formatDuration(a.millis), QType.secondary(), c.muted, Modifier.padding(start = 12.dp))
                }
                HairBar(a.millis.toFloat() / top, Modifier.padding(top = 8.dp))
            }
        }
    }

    SectionHeader("Pickups")
    T(
        if (today.pickups == 1) "1 pickup today" else "${today.pickups} pickups today",
        QType.body(), c.ink,
    )

    SectionHeader("Day receipt")
    app.quiet.launcher.feature.receipt.ReceiptEntry()
}

@Composable
private fun SectionHeader(text: String) {
    T(text, QType.small(), Q.colors.muted, Modifier.padding(top = 32.dp, bottom = 10.dp))
}

@Composable
private fun HairBar(fraction: Float, modifier: Modifier = Modifier) {
    val line = Q.colors.line
    val fill = Q.colors.muted
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(line, size = size)
        drawRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

@Composable
private fun WeekChart(week: WeekUsage) {
    val c = Q.colors
    val days = week.days
    val max = (days.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val desc = days.indices.joinToString(", ") { "${week.dayLabels.getOrElse(it) { "" }} ${formatDuration(days[it])}" }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Last 7 days. $desc" }) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val n = days.size.coerceAtLeast(1)
            val col = size.width / n
            val barW = 3.dp.toPx()
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            days.forEachIndexed { i, v ->
                val h = (size.height * v / max).coerceAtLeast(1.dp.toPx())
                val x = col * i + col / 2
                val color = if (i == n - 1) c.ink else c.edge
                drawLine(color, Offset(x, size.height), Offset(x, size.height - h), barW, StrokeCap.Butt)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            days.indices.forEach { i ->
                T(
                    week.dayLabels.getOrElse(i) { "" }, QType.small(),
                    if (i == days.size - 1) c.ink else c.muted,
                    Modifier.weight(1f), TextAlign.Center,
                )
            }
        }
    }
}
