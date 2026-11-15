package app.quiet.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.feature.apps.AppActionsSheet
import app.quiet.launcher.feature.apps.AppsScreen
import app.quiet.launcher.feature.home.HomeScreen
import app.quiet.launcher.feature.lock.LockActivity
import app.quiet.launcher.feature.media.NowPlayingRepositoryImpl
import app.quiet.launcher.feature.settings.SettingsScreen
import app.quiet.launcher.feature.usage.UsageScreen
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private enum class Overlay { None, Settings, Usage, Notifications, Monochrome, Hidden, Focus, Schedules, Precision, FocusSession, BlockApp }

class MainActivity : ComponentActivity() {
    /** Bumped when Home is pressed while we are already in front: return to the Home page. */
    private var homeSignal by mutableIntStateOf(0)
    /** Bumped when the held-notifications summary is tapped. */
    private var openNotifications by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        importFavourites(intent)
        handleDeepLink(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            QuietTheme(dark = dark, reducedMotion = settings.reducedMotion) {
                LauncherRoot(homeSignal, openNotifications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
        handleDeepLink(intent)
        importFavourites(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent != null && app.quiet.launcher.feature.notifications.NotificationFilter.wantsNotifications(intent)) {
            openNotifications++
            app.quiet.launcher.feature.notifications.NotificationFilter.consume(intent)
        }
    }

    /**
     * Debug builds only: `adb shell am start -n app.quiet.launcher/.MainActivity --esa import_favourites "A,B"`
     * matches labels (case-insensitive) and adds them to Home in that order.
     */
    private fun importFavourites(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val labels = intent?.getStringArrayExtra("import_favourites") ?: return
        intent.removeExtra("import_favourites")
        val repo = container.apps
        container.appScope.launch {
            repo.refresh()
            val apps = repo.apps.value
            labels.map { it.trim() }.filter { it.isNotEmpty() }.forEach { label ->
                val match = apps.firstOrNull { it.label.equals(label, ignoreCase = true) }
                    ?: apps.firstOrNull { it.systemLabel.equals(label, ignoreCase = true) }
                if (match != null) repo.setFavourite(match.key, true)
                else android.util.Log.w("Quiet", "Import: no app called $label")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (container.nowPlaying as? NowPlayingRepositoryImpl)?.recheck()
        // Permission changes (usage access) and fresh numbers show the moment Home is back.
        container.appScope.launch { container.usage.refresh() }
    }
}

@Composable
private fun LauncherRoot(homeSignal: Int, openNotifications: Int) {
    val scope = rememberCoroutineScope()
    // Pages: 0 Focus, 1 Home, 2 All apps. Home is in the middle.
    val pager = rememberPagerState(initialPage = 1) { 3 }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var focusSearch by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var blockPkg by remember { mutableStateOf<String?>(null) }
    var overlayBack by remember { mutableStateOf(Overlay.None) }
    val ctx = LocalContext.current

    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            overlay = Overlay.None; playerOpen = false; sheetApp = null
            pager.animateScrollToPage(1, animationSpec = spring(dampingRatio = 1f, stiffness = 500f))
        }
    }
    LaunchedEffect(openNotifications) { if (openNotifications > 0) overlay = Overlay.Notifications }
    val settings by ctx.container.settings.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.lockEnabled) { app.quiet.launcher.service.LockService.startIfEnabled(ctx) }
    BackHandler(enabled = pager.currentPage != 1 && overlay == Overlay.None) {
        scope.launch { pager.animateScrollToPage(1) }
    }
    fun openFrom(o: Overlay, back: Overlay = Overlay.None) { overlayBack = back; overlay = o }

    Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize(), userScrollEnabled = !playerOpen) { page ->
            // Pages slide with a gentle depth and fade so Home and the list feel layered.
            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
            Box(Modifier.fillMaxSize().graphicsLayer {
                val o = offset.absoluteValue.coerceIn(0f, 1f)
                alpha = lerp(1f, 0.2f, o)
                val s = lerp(1f, 0.96f, o)
                scaleX = s; scaleY = s
            }) {
                if (page == 0) {
                    app.quiet.launcher.feature.quick.FocusPage(
                        onOpenFocusSetup = { openFrom(Overlay.FocusSession) },
                        onOpenUsage = { openFrom(Overlay.Usage) },
                        onOpenSchedules = { openFrom(Overlay.Schedules) },
                    )
                } else if (page == 1) {
                    HomeScreen(
                        onOpenSearch = { focusSearch = true; scope.launch { pager.animateScrollToPage(2) } },
                        onOpenUsage = { overlay = Overlay.Usage },
                        onOpenFocusSetup = { openFrom(Overlay.FocusSession) },
                        onAppLongPress = { sheetApp = it },
                        playerOpen = playerOpen,
                        onPlayerOpenChange = { playerOpen = it },
                        isActive = pager.targetPage == 1,
                    )
                } else {
                    AppsScreen(
                        isActive = pager.settledPage == 2,
                        focusSearch = focusSearch && pager.currentPage == 2,
                        onFocusConsumed = { focusSearch = false },
                        onOpenSettings = { overlay = Overlay.Settings },
                        onBack = { scope.launch { pager.animateScrollToPage(1) } },
                        onAppLongPress = { sheetApp = it },
                    )
                }
            }
        }

        AnimatedContent(
            targetState = overlay,
            transitionSpec = {
                val sp = spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold)
                if (targetState != Overlay.None) (slideInHorizontally(sp) { it } + fadeIn(tween(200))) togetherWith fadeOut(tween(200))
                else fadeIn(tween(1)) togetherWith (slideOutHorizontally(sp) { it } + fadeOut(tween(260)))
            },
            label = "overlay",
        ) { o ->
            when (o) {
                Overlay.None -> Box(Modifier)
                Overlay.Settings -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    SettingsScreen(
                        onBack = { overlay = Overlay.None },
                        onOpenLockPicker = { LockActivity.openPicker(ctx) },
                        onOpenUsage = { overlay = Overlay.Usage },
                        onOpenNotifications = { overlay = Overlay.Notifications },
                        onOpenMonochrome = { overlay = Overlay.Monochrome },
                        onOpenHidden = { overlay = Overlay.Hidden },
                        onOpenFocus = { overlay = Overlay.Focus },
                        onOpenSchedules = { openFrom(Overlay.Schedules, Overlay.Settings) },
                        onOpenFocusSession = { openFrom(Overlay.FocusSession, Overlay.Settings) },
                        onOpenPrecision = { overlay = Overlay.Precision },
                    )
                }
                Overlay.Focus -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.focus.settings.FocusSettingsScreen(
                        onBack = { overlay = Overlay.Settings },
                        onOpenSchedules = { openFrom(Overlay.Schedules, Overlay.Settings) },
                        onOpenBlockApp = { p -> blockPkg = p; overlayBack = Overlay.Focus; overlay = Overlay.BlockApp },
                        onOpenPrecisionMode = { overlay = Overlay.Precision },
                        onOpenFocusSession = { openFrom(Overlay.FocusSession, Overlay.Settings) },
                    )
                }
                Overlay.Schedules -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.focus.settings.SchedulesScreen(onBack = { overlay = if (overlayBack == Overlay.None) Overlay.None else Overlay.Settings; overlayBack = Overlay.Settings })
                }
                Overlay.Precision -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.focus.settings.PrecisionModeScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.FocusSession -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.focus.settings.FocusSessionStart(onBack = { overlay = if (overlayBack == Overlay.None) Overlay.None else Overlay.Settings; overlayBack = Overlay.Settings }, onStarted = { overlay = Overlay.None; overlayBack = Overlay.Settings })
                }
                Overlay.BlockApp -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    blockPkg?.let { p -> app.quiet.launcher.feature.focus.settings.BlockAppScreen(p, onBack = { overlay = overlayBack }) }
                }
                Overlay.Notifications -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.notifications.NotificationFilterScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Monochrome -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.monochrome.MonochromeScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Hidden -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    app.quiet.launcher.feature.hidden.HiddenAppsScreen(onBack = { overlay = Overlay.Settings })
                }
                Overlay.Usage -> Box(Modifier.fillMaxSize().background(Q.colors.bg)) {
                    UsageScreen(onBack = { overlay = Overlay.None })
                }
            }
        }

        AppActionsSheet(app = sheetApp, onDismiss = { sheetApp = null }, onBlock = { p -> blockPkg = p; overlayBack = Overlay.None; overlay = Overlay.BlockApp })
        // Keeps the system lock-screen wallpaper in step with the chosen lock style.
        app.quiet.launcher.feature.lock.LockWallpaperSync()
    }
}
