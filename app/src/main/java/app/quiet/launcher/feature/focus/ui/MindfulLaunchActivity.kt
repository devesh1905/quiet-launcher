package app.quiet.launcher.feature.focus.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QType
import app.quiet.launcher.feature.focus.engine.GateParams

/**
 * Mindful launch (frame `screen-launch`): optional intention question, then the breathing
 * pause whose length grows with today's opens, then "How long?". "Not now" goes home at any
 * moment. Choosing a time starts the visit timer and brings the app forward.
 */
class MindfulLaunchActivity : InterstitialActivity() {

    private val pkg: String get() = intent.getStringExtra(EXTRA_PKG).orEmpty()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_PKG) != pkg) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onBack() {
        runtime.decline(pkg)
        close()
    }

    @Composable
    override fun BoxScope.Content() {
        val params = remember {
            GateParams(
                delaySeconds = intent.getIntExtra(EXTRA_DELAY, 15),
                askIntention = intent.getBooleanExtra(EXTRA_ASK, true),
                opensBefore = intent.getIntExtra(EXTRA_OPENS, 0),
                budgetLeftMs = intent.getLongExtra(EXTRA_BUDGET, -1L).takeIf { it >= 0 },
                coolingDown = intent.getBooleanExtra(EXTRA_COOLING, false),
            )
        }
        val label = remember { runtime.labelFor(pkg) }
        MindfulLaunch(
            label = label,
            params = params,
            onChoose = { minutes, intention ->
                runtime.grant(pkg, minutes, intention)
                close()
            },
            onNotNow = { onBack() },
        )
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_ASK = "ask"
        private const val EXTRA_OPENS = "opens"
        private const val EXTRA_BUDGET = "budget"
        private const val EXTRA_COOLING = "cooling"
        private const val EXTRA_LAUNCHER = "launcher"

        fun intent(context: Context, pkg: String, p: GateParams, fromLauncher: Boolean): Intent =
            Intent(context, MindfulLaunchActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_DELAY, p.delaySeconds)
                .putExtra(EXTRA_ASK, p.askIntention)
                .putExtra(EXTRA_OPENS, p.opensBefore)
                .putExtra(EXTRA_BUDGET, p.budgetLeftMs ?: -1L)
                .putExtra(EXTRA_COOLING, p.coolingDown)
                .putExtra(EXTRA_LAUNCHER, fromLauncher)
    }
}

private enum class LaunchStep { INTENTION, PAUSE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MindfulLaunch(
    label: String,
    params: GateParams,
    onChoose: (minutes: Int, intention: String?) -> Unit,
    onNotNow: () -> Unit,
) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    var step by rememberSaveable { mutableStateOf(if (params.askIntention) LaunchStep.INTENTION else LaunchStep.PAUSE) }
    var intention by rememberSaveable { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // At least one screen tall, so the weighted spacer pins "Not now" to the bottom.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
        Spacer(Modifier.height(48.dp))
        Txt(FocusCopy.gateQuestion(label, params.askIntention), QType.question(34.sp), c.ink)
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val inMs = if (reduced) 200 else 320
                fadeIn(tween(inMs, delayMillis = 80)) togetherWith fadeOut(tween(160))
            },
            label = "launchStep",
        ) { s ->
            when (s) {
                LaunchStep.INTENTION -> Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(28.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FocusCopy.intentions.forEach { option ->
                            FocusChip(option) {
                                intention = option
                                step = LaunchStep.PAUSE
                            }
                        }
                    }
                    FocusCopy.gateNote(params)?.let { Txt(it, QType.secondary(), c.muted, Modifier.padding(top = 20.dp)) }
                }
                LaunchStep.PAUSE -> PauseAndChoose(params, intention, onChoose)
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        TextAction("Not now, go home", Modifier.align(Alignment.CenterHorizontally), onClick = onNotNow)
        Spacer(Modifier.height(16.dp))
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseAndChoose(params: GateParams, intention: String?, onChoose: (Int, String?) -> Unit) {
    val c = Q.colors
    val reduced = Q.reducedMotion
    val pause = rememberPause(params.delaySeconds) {}
    var custom by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableIntStateOf(20) }

    Column(Modifier.fillMaxWidth()) {
        if (intention != null) {
            Txt("“$intention.”", QType.labelTitle(20.sp), c.muted, Modifier.padding(top = 12.dp))
            FocusCopy.intentionEcho(intention)?.let { Txt(it, QType.secondary(), c.muted, Modifier.padding(top = 4.dp)) }
        } else {
            FocusCopy.gateNote(params)?.let { Txt(it, QType.secondary(), c.muted, Modifier.padding(top = 12.dp)) }
        }
        if (params.delaySeconds > 0) {
            Spacer(Modifier.height(24.dp))
            BreathRing(pause, Modifier.align(Alignment.CenterHorizontally), diameter = 220.dp)
            Txt(
                if (pause.done) "" else FocusCopy.breathWord(pause.inhale, reduced), QType.secondary(15.sp), c.muted,
                Modifier.fillMaxWidth().padding(top = 12.dp), TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Txt("How long?", QType.title(26.sp), c.ink)
        val note = when {
            !pause.done -> "You can choose when the pause ends."
            else -> FocusCopy.budgetLine(params.budgetLeftMs) ?: "Choose a time for this visit."
        }
        Txt(note, QType.secondary(), c.muted, Modifier.padding(top = 4.dp, bottom = 14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(5, 10, 15).forEach { m ->
                FocusChip("$m min", enabled = pause.done) { onChoose(m, intention) }
            }
            FocusChip("Other", selected = custom, enabled = pause.done) { custom = !custom }
        }
        if (custom && pause.done) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(plus = false, label = "Fewer minutes") { customMinutes = (customMinutes - 5).coerceAtLeast(1) }
                Txt("$customMinutes min", QType.number(32.sp), c.ink, Modifier.width(120.dp), TextAlign.Center)
                StepButton(plus = true, label = "More minutes") { customMinutes = (customMinutes + 5).coerceAtMost(180) }
            }
            SolidButton("Use $customMinutes min", Modifier.padding(top = 12.dp)) { onChoose(customMinutes, intention) }
        }
    }
}
