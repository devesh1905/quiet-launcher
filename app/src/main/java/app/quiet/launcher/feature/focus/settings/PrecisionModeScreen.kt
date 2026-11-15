package app.quiet.launcher.feature.focus.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import app.quiet.launcher.feature.focus.LockController
import app.quiet.launcher.feature.focus.ui.*
import kotlinx.coroutines.launch

/**
 * Precision mode: what it does, the prominent disclosure and consent (stored) shown before the
 * user is sent to Accessibility settings, service status, short-video switches per app, and
 * the device-admin fallback for double-tap to lock.
 */
@Composable
fun PrecisionModeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Q.colors
    val rt = remember { FocusRuntime.get(ctx) }
    val appScope = ctx.container.appScope
    val prefs by rt.repo.prefs.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(accessibilityEnabled(ctx)) }
    var admin by remember { mutableStateOf(LockController.isAdminActive(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = accessibilityEnabled(ctx)
        admin = LockController.isAdminActive(ctx)
    }
    BackHandler(onBack = onBack)
    val contentRules by rt.contentRulesFlow.collectAsStateWithLifecycle()
    val rules = contentRules.shortVideo

    FocusScreen("Precision mode", onBack) {
        item {
            Note(
                "Precision mode uses Android's accessibility service to see which app opens the moment it opens. " +
                    "Pauses and blocks then start without delay, short-video feeds can be closed, and websites of blocked apps stay closed too.",
            )
            Note("Without it, Quiet checks the app in front about once a second. Everything still works, a little later.")
            RowBase(onClick = null) {
                Txt("Status", QType.body(), c.ink, Modifier.weight(1f))
                Txt(if (enabled) "On" else "Off", QType.secondary(), if (enabled) c.ink else c.muted)
            }
        }

        if (!prefs.precisionConsent) item {
            Disclosure(
                onAgree = {
                    appScope.launch { rt.repo.setPrecisionConsent(true) }
                    open(ctx, accessibilitySettingsIntent(ctx))
                },
                onNotNow = onBack,
            )
        } else item {
            Column(Modifier.padding(horizontal = ListGutter).padding(top = 20.dp)) {
                if (!enabled) {
                    SolidButton("Open accessibility settings") { open(ctx, accessibilitySettingsIntent(ctx)) }
                    Txt(
                        if (Permissions.isXiaomi()) "On Xiaomi phones: Settings, Additional settings, Accessibility, Downloaded apps, then Quiet."
                        else "In Accessibility, open Downloaded apps or Installed apps, then Quiet.",
                        QType.secondary(14.sp), c.muted, Modifier.padding(top = 10.dp),
                    )
                } else {
                    LineButton("Turn off in accessibility settings") { open(ctx, accessibilitySettingsIntent(ctx)) }
                }
                TextAction("Withdraw consent", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                    appScope.launch {
                        rt.repo.setPrecisionConsent(false)
                        rt.repo.setShortVideoEnabled(false)
                    }
                    if (enabled) open(ctx, accessibilitySettingsIntent(ctx))
                }
            }
        }

        item {
            SectionTitle("Short videos")
            SwitchRow(
                "Close short-video feeds", prefs.shortVideo.enabled,
                if (enabled) "Goes back when a feed opens" else "Needs precision mode",
                enabled = prefs.precisionConsent,
            ) { v -> appScope.launch { rt.repo.setShortVideoEnabled(v) } }
            rules.forEach { r ->
                SwitchRow(
                    r.label, r.id !in prefs.shortVideo.disabledRules,
                    enabled = prefs.precisionConsent && prefs.shortVideo.enabled,
                ) { v -> appScope.launch { rt.repo.setShortVideoRule(r.id, v) } }
            }
            Note("Detection follows each app's current layout. After a big app update it may miss a feed until Quiet's rules catch up.")
        }

        item {
            SectionTitle("Double-tap to lock")
            Note(
                if (enabled) "Locks through precision mode, so your fingerprint or face still unlocks."
                else "With precision mode off, Quiet can lock as a device admin. Android then asks for your PIN at the next unlock instead of your fingerprint.",
            )
            NavRow("Lock as device admin", if (admin) "On" else "Off", if (admin) "Turn off before uninstalling Quiet" else null) {
                if (admin) {
                    LockController.removeAdmin(ctx)
                    admin = false
                } else open(ctx, LockController.adminIntent(ctx))
            }
        }
    }
}

@Composable
private fun Disclosure(onAgree: () -> Unit, onNotNow: () -> Unit) {
    val c = Q.colors
    Column(
        Modifier.padding(horizontal = ListGutter).padding(top = 24.dp).fillMaxWidth()
            .border(1.dp, c.edge, RoundedCornerShape(14.dp)).padding(20.dp),
    ) {
        Txt("Before you turn it on", QType.labelTitle(24.sp), c.ink, Modifier.semantics { heading() })
        Txt(
            "Quiet will use the accessibility service to read which app is open. In YouTube, Instagram, Facebook, Snapchat " +
                "and supported browsers it also reads what is on screen, to find short-video feeds and the address of the page you are on.",
            QType.secondary(), c.ink, Modifier.padding(top = 12.dp),
        )
        Txt(
            "Quiet uses this only to show your reminders and to block what you chose. It keeps none of it, and nothing leaves this phone.",
            QType.secondary(), c.ink, Modifier.padding(top = 10.dp),
        )
        Txt("You can turn it off at any time in Accessibility settings.", QType.secondary(), c.muted, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(20.dp))
        SolidButton("I agree, open settings", onClick = onAgree)
        TextAction("Not now", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), onClick = onNotNow)
    }
}
