package app.quiet.launcher.feature.focus.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.engine.FocusSession
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * Focus session (frame `screen-focus`): a large Fraunces countdown and the sentence the user
 * wrote. Ending early needs a 1.5 s press and hold. Back goes home; the session keeps running.
 */
class FocusSessionActivity : InterstitialActivity() {

    @Composable
    override fun BoxScope.Content() {
        val snap by runtime.repo.snapshot.collectAsStateWithLifecycle()
        val session = snap.session
        // Remember the last session so the screen does not blank while it closes.
        var last by remember { mutableStateOf(session) }
        if (session != null) last = session
        val ready by runtime.ready.collectAsStateWithLifecycle()
        LaunchedEffect(session, ready) {
            if (ready && session == null) {
                delay(400L)
                if (runtime.snapshot.session == null) close()
            }
        }
        val s = last ?: return
        FocusSessionView(
            s,
            onEndEarly = {
                runtime.endSession(early = true)
                close()
            },
            onHome = { onBack() },
        )
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, FocusSessionActivity::class.java)

        /** Open the running session screen (for example from a Home affordance). */
        fun open(context: Context) {
            context.startActivity(intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
internal fun FocusSessionView(session: FocusSession, onEndEarly: () -> Unit, onHome: () -> Unit) {
    val c = Q.colors
    val ctx = LocalContext.current
    val is24h = remember { DateFormat.is24HourFormat(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.endsMs) {
        while (now < session.endsMs) {
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
            now = System.currentTimeMillis()
        }
    }
    val total = (session.endsMs - session.startedMs).coerceAtLeast(1L)
    val left = (session.endsMs - now).coerceAtLeast(0L)
    val elapsed = 1f - left.toFloat() / total

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.7f))
        Txt(countdown(left), QType.clock(if (left >= 3_600_000L) 92.sp else 118.sp), c.ink, maxLines = 1)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.edge)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(elapsed.coerceIn(0f, 1f)).background(c.ink))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Focus session, ${FocusCopy.duration(total)}", QType.secondary(14.sp), c.muted, Modifier.weight(1f))
            Txt("until ${FocusCopy.clock(session.endsMs, ZoneId.systemDefault(), is24h)}", QType.secondary(14.sp), c.muted)
        }
        if (session.intention.isNotBlank()) {
            Txt("“${session.intention}”", QType.question(30.sp), c.ink, Modifier.padding(top = 40.dp))
        }
        Txt(
            if (session.dnd) "Notifications wait until you finish."
            else "Only the apps you chose can open.",
            QType.secondary(), c.muted, Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.weight(1f))
        HoldButton("Hold to end early", "Keep holding", onComplete = onEndEarly)
        TextAction("Go home, keep going", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), onClick = onHome)
        Spacer(Modifier.height(16.dp))
    }
}

private fun countdown(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
