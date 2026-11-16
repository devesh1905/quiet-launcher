package app.quiet.launcher.feature.streaks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.FocusRuntime
import app.quiet.launcher.feature.focus.data.FocusDatabase
import app.quiet.launcher.feature.receipt.ReceiptMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Five steps of ink in one hue (lightness only), for dark and light. Level 0 is a hairline square. */
private val DarkInk = listOf(Color(0xFF121211), Color(0xFF3A3833), Color(0xFF6E6B64), Color(0xFFAAA69D), Color(0xFFEDEBE6))
private val LightInk = listOf(Color(0xFFECE9E1), Color(0xFFCFCAC0), Color(0xFF9A958B), Color(0xFF5E5A52), Color(0xFF141311))
private val DarkEmptyEdge = Color(0xFF2A2925)
private val LightEmptyEdge = Color(0xFFD6D2C8)

private class MapData(
    val start: LocalDate,
    val todayIndex: Int,
    val minutes: IntArray,
    val spans: List<Span>,
    val streaks: Streaks,
)

/**
 * Focus streaks for the Focus page: current and best run, a 20-week heat map of focused minutes
 * (Monday first), and the day under your finger. Drawn from the recorded sessions; recomputed
 * only when a session is added, the day changes or a session is running (once a minute).
 */
@Composable
fun FocusStreaks(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = Q.colors
    val reduced = Q.reducedMotion
    val zone = remember { ZoneId.systemDefault() }
    val rt = remember { FocusRuntime.get(ctx) }
    val stored by remember {
        FocusDatabase.get(ctx).dao().observeSessions()
            .map { list -> list.map { Span(it.startMs, it.endMs) } }
            .flowOn(Dispatchers.IO)
    }.collectAsStateWithLifecycle(emptyList())
    val snap by rt.repo.snapshot.collectAsStateWithLifecycle()
    val minute by ctx.container.device.minuteTick.collectAsStateWithLifecycle()
    val running = snap.session
    val now = maxOf(minute, System.currentTimeMillis())
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // Only a running session needs the minute; otherwise the key stays put and nothing recomputes.
    val liveKey = if (running != null) now / 60_000L else 0L

    val data = remember(stored, today, running?.startedMs, liveKey) {
        val spans = if (running != null) stored + Span(running.startedMs, minOf(now, running.endsMs)) else stored
        val start = StreakMath.gridStart(today)
        val todayIdx = StreakMath.dayIndex(start, today)
        val minutes = StreakMath.dailyMinutes(spans.filter { it.endMs >= start.atStartOfDay(zone).toInstant().toEpochMilli() }, start, todayIdx + 1, zone)
        // Best counts every recorded day, not just the 20 weeks on screen.
        val first = spans.minOfOrNull { it.startMs }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val streaks = if (first != null && first.isBefore(start)) {
            val all = StreakMath.dailyMinutes(spans, first, StreakMath.dayIndex(first, today) + 1, zone)
            StreakMath.streaks(all, all.size - 1)
        } else StreakMath.streaks(minutes, todayIdx)
        MapData(start, todayIdx, minutes, spans, streaks)
    }

    var selected by remember { mutableIntStateOf(-1) }
    val sel = if (selected in 0..data.todayIndex) selected else data.todayIndex

    // Entrance: columns ink in from the oldest week, numbers count up. Runs once, then stops.
    val intro = remember { Animatable(if (reduced) 1f else 0f) }
    val count = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        launch { intro.animateTo(1f, tween(INTRO_MS, easing = LinearEasing)) }
        count.animateTo(1f, tween(900, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)))
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText("Current streak", style = QType.secondary(14.sp).copy(color = c.muted))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText("${(data.streaks.current * count.value).toInt()}", style = QType.number(88.sp).copy(color = c.ink))
                    BasicText("days", style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                BasicText("Best", style = QType.secondary(14.sp).copy(color = c.muted))
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText("${(data.streaks.best * count.value).toInt()}", style = QType.number(48.sp).copy(color = c.muted))
                    BasicText("days", style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                }
            }
        }
        val weekStart = data.todayIndex - data.todayIndex % 7
        val met = (weekStart..data.todayIndex).count { data.minutes[it] >= StreakMath.GOAL_MIN }
        BasicText(
            "Goal ${StreakMath.GOAL_MIN} min a day. $met of ${data.todayIndex - weekStart + 1} days this week.",
            style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.height(22.dp))
        HeatMap(data, sel, intro.value) { selected = it }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val total = data.minutes.sum()
            BasicText(
                if (total == 0) "Sessions you finish show here." else (if (total >= 60) "${total / 60}h" else "$total min") + " focused in 20 weeks",
                style = QType.small().copy(color = c.muted), modifier = Modifier.weight(1f),
            )
            Legend()
        }
        DayDetail(data, sel, zone)
    }
}

private const val INTRO_MS = 120 + 19 * 16 + 360

@Composable
private fun HeatMap(data: MapData, sel: Int, intro: Float, onSelect: (Int) -> Unit) {
    val c = Q.colors
    val ink = if (c.isDark) DarkInk else LightInk
    val emptyEdge = if (c.isDark) DarkEmptyEdge else LightEmptyEdge
    val labelW = 22.dp
    val weeks = StreakMath.WEEKS
    val monthFmt = remember { DateTimeFormatter.ofPattern("MMM", Locale.getDefault()) }
    val desc = "Focused minutes per day, last 20 weeks. Current streak ${data.streaks.current} days, best ${data.streaks.best}."
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 13 dp squares with 4 dp gaps at 20 columns, scaled to the width.
        val pitch = (maxWidth - labelW + 4.dp) / weeks
        val cell = pitch * (13f / 17f)
        Column {
            // Month labels over the first column whose Monday falls in a new month (no crowding).
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                var last = -1
                val labels = ArrayList<Pair<Int, String>>()
                for (w in 0 until weeks) {
                    val d = data.start.plusWeeks(w.toLong())
                    if (d.monthValue != last) { labels += w to d.format(monthFmt); last = d.monthValue }
                }
                labels.filterIndexed { k, l -> if (k + 1 < labels.size) labels[k + 1].first - l.first >= 3 else l.first <= weeks - 2 }
                    .forEach { (w, t) ->
                        BasicText(t, style = QType.small().copy(color = c.muted), modifier = Modifier.offset(x = labelW + pitch * w))
                    }
            }
            Row {
                Column(Modifier.width(labelW)) {
                    listOf("", "M", "", "W", "", "F", "").forEach {
                        Box(Modifier.height(pitch), contentAlignment = Alignment.CenterStart) {
                            BasicText(it, style = QType.small(11.sp).copy(color = c.muted))
                        }
                    }
                }
                Canvas(
                    Modifier.width(pitch * weeks).height(pitch * 7)
                        .semantics { contentDescription = desc }
                        .pointerInput(data.todayIndex) {
                            fun hit(o: Offset): Int {
                                val p = pitch.toPx()
                                val col = (o.x / p).toInt().coerceIn(0, weeks - 1)
                                val row = (o.y / p).toInt().coerceIn(0, 6)
                                return (col * 7 + row).coerceAtMost(data.todayIndex)
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                onSelect(hit(down.position))
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    // Scrubbing sideways owns the gesture; vertical drags still scroll the page.
                                    if (kotlin.math.abs(ch.positionChange().x) > kotlin.math.abs(ch.positionChange().y)) ch.consume()
                                    onSelect(hit(ch.position))
                                }
                            }
                        },
                ) {
                    val p = pitch.toPx()
                    val s = cell.toPx()
                    val r = CornerRadius(3.dp.toPx())
                    val elapsed = intro * INTRO_MS
                    for (i in 0..data.todayIndex) {
                        val w = i / 7
                        val a = ((elapsed - 120 - w * 16) / 360f).coerceIn(0f, 1f)
                        if (a <= 0f) continue
                        val k = (1f - a) * 0.4f * s / 2f
                        val tl = Offset(w * p + k, (i % 7) * p + k)
                        val sz = Size(s - 2 * k, s - 2 * k)
                        val lvl = StreakMath.level(data.minutes[i])
                        drawRoundRect(ink[lvl], tl, sz, r, alpha = a)
                        if (lvl == 0) drawRoundRect(emptyEdge, tl, sz, r, style = Stroke(1.dp.toPx()), alpha = a)
                    }
                    fun ring(i: Int, gap: Float, width: Float, color: Color) {
                        val o = Offset((i / 7) * p - gap, (i % 7) * p - gap)
                        drawRoundRect(color, o, Size(s + 2 * gap, s + 2 * gap), CornerRadius(3.dp.toPx() + gap), style = Stroke(width))
                    }
                    if (intro >= 1f) {
                        ring(data.todayIndex, 2.dp.toPx(), 1.dp.toPx(), c.muted)
                        ring(sel, 2.75.dp.toPx(), 1.5.dp.toPx(), c.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val c = Q.colors
    val ink = if (c.isDark) DarkInk else LightInk
    val emptyEdge = if (c.isDark) DarkEmptyEdge else LightEmptyEdge
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("Less", style = QType.small().copy(color = c.muted), modifier = Modifier.padding(end = 4.dp))
        Canvas(Modifier.size(width = (11 * 5 + 4 * 4).dp, height = 11.dp)) {
            val s = 11.dp.toPx(); val g = 4.dp.toPx(); val r = CornerRadius(2.5.dp.toPx())
            for (l in 0..4) {
                drawRoundRect(ink[l], Offset(l * (s + g), 0f), Size(s, s), r)
                if (l == 0) drawRoundRect(emptyEdge, Offset(0f, 0f), Size(s, s), r, style = Stroke(1.dp.toPx()))
            }
        }
        BasicText("More", style = QType.small().copy(color = c.muted), modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun DayDetail(data: MapData, sel: Int, zone: ZoneId) {
    val c = Q.colors
    val day = data.start.plusDays(sel.toLong())
    val isToday = sel == data.todayIndex
    val m = data.minutes[sel]
    val spans = remember(data, sel) { StreakMath.spansOn(data.spans, day, zone) }
    val dayName = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val date = "$dayName ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).padding(0.dp)) { Canvas(Modifier.fillMaxSize()) { drawRect(c.line) } }
        BasicText(if (isToday) "Today, $date" else date, style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(top = 20.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            BasicText(if (m == 0) "No focus" else ReceiptMath.hm(m), style = QType.number(48.sp).copy(color = c.ink))
            if (m in 1 until StreakMath.GOAL_MIN) {
                BasicText("under the goal", style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            }
        }
        val longest = spans.maxOfOrNull { it.endMs - it.startMs } ?: 0L
        val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        BasicText(
            when {
                spans.isEmpty() && isToday -> "Nothing yet today."
                spans.isEmpty() -> "A day off. The streak counts from the next one."
                spans.size == 1 -> "1 session, from ${app.quiet.launcher.core.platform.Format.clock(spans[0].startMs, is24)}"
                else -> "${spans.size} sessions, longest ${ReceiptMath.hm((longest / 60_000L).toInt())}, from ${app.quiet.launcher.core.platform.Format.clock(spans[0].startMs, is24)}"
            },
            style = QType.secondary(15.sp).copy(color = c.muted), modifier = Modifier.padding(top = 8.dp),
        )
        // When the sessions happened, 6:00 to 24:00.
        val from = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        Canvas(Modifier.fillMaxWidth().padding(top = 18.dp).height(28.dp)) {
            val span = (to - from).toFloat()
            drawRect(c.edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            for (h in listOf(6, 12, 18, 24)) {
                val x = ((h - 6) / 18f) * size.width
                drawRect(c.edge, Offset(x.coerceAtMost(size.width - 1.dp.toPx()), size.height - 6.dp.toPx()), Size(1.dp.toPx(), 6.dp.toPx()))
            }
            spans.forEach { s ->
                val a = ((maxOf(s.startMs, from) - from) / span).coerceIn(0f, 1f)
                val b = ((minOf(s.endMs, to) - from) / span).coerceIn(0f, 1f)
                if (b > a) drawRect(c.ink, Offset(a * size.width, 0f), Size(maxOf(2.dp.toPx(), (b - a) * size.width), size.height - 1.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("6:00", "12:00", "18:00", "24:00").forEach { BasicText(it, style = QType.small().copy(color = c.muted)) }
        }
    }
}
