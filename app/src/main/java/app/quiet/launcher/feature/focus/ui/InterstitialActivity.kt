package app.quiet.launcher.feature.focus.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.designsystem.Q
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.feature.focus.FocusRuntime

/**
 * Base for the full-screen focus interstitials: true black (or paper in the light theme),
 * edge to edge, its own task (manifest: taskAffinity, excludeFromRecents). Back runs [onBack],
 * which by default sends the user home: an interstitial is never dismissed into the app.
 */
abstract class InterstitialActivity : ComponentActivity() {

    protected val runtime: FocusRuntime get() = FocusRuntime.get(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            QuietTheme(dark = dark, reducedMotion = settings.reducedMotion) {
                Box(
                    Modifier.fillMaxSize().background(Q.colors.bg)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = InterGutter),
                ) { Content() }
            }
        }
    }

    @Composable
    protected abstract fun BoxScope.Content()

    protected open fun onBack() {
        runtime.goHome()
        finish()
    }

    /** Finish without the default window animation. */
    protected fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
