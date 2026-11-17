package app.quiet.launcher.feature.focus.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.platform.Format
import app.quiet.launcher.feature.focus.FocusRuntime

internal data class AppItem(val pkg: String, val label: String)

/** Launchable apps, one row per package (work-profile twins merged), our own app left out. */
@Composable
internal fun rememberApps(): List<AppItem> {
    val ctx = LocalContext.current
    val apps by ctx.container.apps.apps.collectAsStateWithLifecycle()
    return remember(apps) {
        apps.asSequence()
            .filter { it.key.packageName != ctx.packageName }
            .distinctBy { it.key.packageName }
            .map { AppItem(it.key.packageName, it.label) }
            .toList()
    }
}

/** Per-package foreground time over the last 7 days (empty without usage access). */
@Composable
internal fun rememberWeekUsage(): Map<String, Long> {
    val ctx = LocalContext.current
    var map by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) { map = FocusRuntime.get(ctx).weekUsage.load() }
    return map
}

internal fun weekLine(ms: Long?): String? = ms?.takeIf { it >= 60_000 }?.let { "${Format.duration(it)} in the last 7 days" }

internal fun open(ctx: Context, intent: Intent) {
    app.quiet.launcher.core.platform.Permissions.launch(ctx, intent)
}

internal fun notificationPolicyIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

/** Xiaomi / HyperOS autostart manager, with app details as the fallback. */
internal fun xiaomiAutostartIntent(ctx: Context): Intent =
    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        .putExtra("package_name", ctx.packageName)

internal fun appDetails(ctx: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
