package app.quiet.launcher

import android.app.Application
import android.content.Context
import app.quiet.launcher.core.data.*
import app.quiet.launcher.feature.art.ArtRepositoryImpl
import app.quiet.launcher.feature.media.NowPlayingRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class QuietApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        app.quiet.launcher.designsystem.Fonts.init(this)
        // A crash or reboot while the lock screen was up must not leave a 10 s timeout behind.
        if (!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) app.quiet.launcher.feature.lock.LockTimeout.restore(this)
        container = AppContainer(this)
        app.quiet.launcher.feature.art.ArtScheduler.schedule(this)
        app.quiet.launcher.service.LockService.startIfEnabled(this)
        app.quiet.launcher.feature.monochrome.MonochromeController.get(this).start()
        app.quiet.launcher.feature.focus.FocusService.sync(this)
    }
}

/** Manual DI. Every repository is a process-wide singleton. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ctx = context.applicationContext

    val settings: SettingsRepository by lazy { SettingsRepositoryImpl(ctx, appScope) }
    val apps: AppsRepository by lazy { AppsRepositoryImpl(ctx, appScope) }
    val usage: UsageRepository by lazy { UsageRepositoryImpl(ctx, appScope, settings) }
    val device: DeviceStateRepository by lazy { DeviceStateRepositoryImpl(ctx, appScope) }
    val nowPlaying: NowPlayingRepository by lazy { NowPlayingRepositoryImpl(ctx, appScope) }
    val art: ArtRepository by lazy { ArtRepositoryImpl(ctx, appScope, settings) }
    val foreground: ForegroundTracker by lazy { app.quiet.launcher.feature.focus.ForegroundTrackerImpl(ctx, appScope) }
}

val Context.container: AppContainer get() = (applicationContext as QuietApp).container
