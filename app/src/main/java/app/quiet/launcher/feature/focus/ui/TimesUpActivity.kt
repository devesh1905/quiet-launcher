package app.quiet.launcher.feature.focus.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.engine.TimesUpParams
import app.quiet.launcher.feature.focus.engine.TimesUpReason

/**
 * Time's up (frame `screen-timesup`): one fact and one sentence of context. Leaving is the
 * filled button; more time costs the extension pause first. Back counts as leaving.
 */
class TimesUpActivity : InterstitialActivity() {

    private val pkg: String get() = intent.getStringExtra(EXTRA_PKG).orEmpty()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_PKG) != pkg) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onBack() {
        runtime.leave(pkg)
        close()
    }

    @Composable
    override fun BoxScope.Content() {
        val params = remember {
            TimesUpParams(
                reason = runCatching { TimesUpReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty()) }.getOrDefault(TimesUpReason.TIMER),
                grantedMs = intent.getLongExtra(EXTRA_GRANTED, 0L),
                usedTodayMs = intent.getLongExtra(EXTRA_USED, 0L),
                extensionDelaySeconds = intent.getIntExtra(EXTRA_EXT, 15),
                budgetMinutes = intent.getIntExtra(EXTRA_BUDGET, 0),
            )
        }
        val label = remember { runtime.labelFor(pkg) }
        TimesUp(
            label, params,
            onLeave = { onBack() },
            onMore = { minutes ->
                runtime.extend(pkg, minutes, params.reason)
                close()
            },
        )
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_GRANTED = "granted"
        private const val EXTRA_USED = "used"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_BUDGET = "budget"
        private const val EXTRA_LAUNCHER = "launcher"

        fun intent(context: Context, pkg: String, p: TimesUpParams, fromLauncher: Boolean): Intent =
            Intent(context, TimesUpActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_REASON, p.reason.name)
                .putExtra(EXTRA_GRANTED, p.grantedMs)
                .putExtra(EXTRA_USED, p.usedTodayMs)
                .putExtra(EXTRA_EXT, p.extensionDelaySeconds)
                .putExtra(EXTRA_BUDGET, p.budgetMinutes)
                .putExtra(EXTRA_LAUNCHER, fromLauncher)
    }
}

private enum class MoreStep { ASK, PAUSE, CHOOSE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimesUp(label: String, params: TimesUpParams, onLeave: () -> Unit, onMore: (Int) -> Unit) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    var step by rememberSaveable { mutableStateOf(MoreStep.ASK) }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(48.dp))
        Txt(FocusCopy.timesUpQuestion(label, params), QType.question(34.sp), c.ink)
        Txt(FocusCopy.timesUpDetail(params), QType.body(), c.muted, Modifier.padding(top = 16.dp))
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(if (reduced) 200 else 300, 60)) togetherWith fadeOut(tween(140)) },
            label = "moreStep",
        ) { s ->
            Column(Modifier.fillMaxWidth()) {
                when (s) {
                    MoreStep.ASK -> {
                        SolidButton("Close $label", onClick = onLeave)
                        Spacer(Modifier.height(12.dp))
                        LineButton("More time") {
                            step = if (params.extensionDelaySeconds > 0) MoreStep.PAUSE else MoreStep.CHOOSE
                        }
                        FocusCopy.pauseNote(params.extensionDelaySeconds)?.let {
                            Txt(it, QType.secondary(14.sp), c.muted, Modifier.fillMaxWidth().padding(top = 10.dp), TextAlign.Center)
                        }
                    }
                    MoreStep.PAUSE -> {
                        val pause = rememberPause(params.extensionDelaySeconds) { step = MoreStep.CHOOSE }
                        BreathRing(pause, Modifier.align(Alignment.CenterHorizontally), diameter = 200.dp)
                        Txt(
                            FocusCopy.breathWord(pause.inhale, reduced), QType.secondary(15.sp), c.muted,
                            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), TextAlign.Center,
                        )
                        SolidButton("Close $label instead", onClick = onLeave)
                    }
                    MoreStep.CHOOSE -> {
                        Txt("How much more?", QType.title(26.sp), c.ink, Modifier.padding(bottom = 14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(2, 5, 10, 15).forEach { m -> FocusChip("$m min") { onMore(m) } }
                        }
                        Spacer(Modifier.height(20.dp))
                        SolidButton("Close $label", onClick = onLeave)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
