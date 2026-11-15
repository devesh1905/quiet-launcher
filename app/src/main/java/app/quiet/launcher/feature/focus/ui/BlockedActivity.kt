package app.quiet.launcher.feature.focus.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.engine.BlockInfo
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * Blocked (frame `screen-blocked`): the end time is the headline, the reason is the schedule's
 * own name (or "Your own block", "Focus session"). The only way out is home.
 */
class BlockedActivity : InterstitialActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    @Composable
    override fun BoxScope.Content() {
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val label = remember(pkg) { runtime.labelFor(pkg) }
        Blocked(label, until, title, detail) { onBack() }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"

        fun intent(context: Context, pkg: String, info: BlockInfo): Intent {
            val (title, detail) = FocusCopy.blockedReason(info, ZoneId.systemDefault())
            return Intent(context, BlockedActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_UNTIL, info.untilMs)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL, detail)
        }
    }
}

@Composable
internal fun Blocked(label: String, untilMs: Long, title: String, detail: String, onHome: () -> Unit) {
    val c = Q.colors
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val is24h = remember { DateFormat.is24HourFormat(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    val over = now >= untilMs

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(56.dp))
        if (over) {
            Txt("$label is open again.", QType.question(34.sp), c.ink)
        } else {
            Txt(FocusCopy.blockedHeadline(label), QType.body(18.sp), c.ink)
            Spacer(Modifier.height(10.dp))
            Txt(FocusCopy.clock(untilMs, zone, is24h), QType.clock(if (is24h) 132.sp else 104.sp), c.ink, maxLines = 1)
            FocusCopy.blockedDate(untilMs, now, zone)?.let { Txt(it, QType.body(18.sp), c.ink, Modifier.padding(top = 8.dp)) }
            Txt(FocusCopy.fromNow(untilMs, now), QType.secondary(), c.muted, Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(36.dp))
            if (title.isNotEmpty()) Txt(title, QType.labelTitle(24.sp), c.ink)
            if (detail.isNotEmpty()) Txt(detail, QType.secondary(), c.muted, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        SolidButton("Go home", onClick = onHome)
        Spacer(Modifier.height(24.dp))
    }
}
