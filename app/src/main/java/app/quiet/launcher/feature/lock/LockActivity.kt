package app.quiet.launcher.feature.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.quiet.launcher.container
import app.quiet.launcher.core.model.ThemeMode
import app.quiet.launcher.designsystem.QuietTheme
import app.quiet.launcher.feature.media.NowPlayingRepositoryImpl

/**
 * Our lock screen, drawn over the system keyguard (DESIGN-NOTES §8.1). It never unlocks
 * anything itself: swipe up asks the system to dismiss the keyguard, which shows the user's
 * own PIN, pattern or fingerprint. A fingerprint unlock underneath (USER_PRESENT) plays the
 * same exit animation.
 */
class LockActivity : ComponentActivity() {

    private var unlockSignal by mutableIntStateOf(0)

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) { LockTimeout.restore(context); unlockSignal++ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Never turn the screen on ourselves: the service pre-launches this while the screen is
            // off, and turning it on there woke the phone straight back up.
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)

        val startInPicker = intent.getBooleanExtra(EXTRA_PICKER, false)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            QuietTheme(dark = settings.theme != ThemeMode.LIGHT, reducedMotion = settings.reducedMotion) {
                LockRoot(
                    startInPicker = startInPicker,
                    unlockSignal = unlockSignal,
                    onUnlock = ::requestUnlock,
                    onPickerDone = { if (!isLocked()) finishQuietly() },
                )
            }
        }
    }

    // A ringing or active call always wins: step aside at once so the system call screen shows.
    private val modeListener = android.media.AudioManager.OnModeChangedListener { stepAsideIfCall() }
    private val callPoll = object : Runnable {
        override fun run() {
            if (stepAsideIfCall()) return
            window.decorView.postDelayed(this, 700)
        }
    }

    private fun stepAsideIfCall(): Boolean {
        if (isFinishing || !app.quiet.launcher.core.platform.CallState.isCallActive(this)) return false
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        return true
    }

    override fun onStart() {
        super.onStart()
        if (stepAsideIfCall()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(android.media.AudioManager::class.java)
                .addOnModeChangedListener(mainExecutor, modeListener)
        }
        // Belt and braces (and the only path below API 31): a light poll while visible.
        window.decorView.postDelayed(callPoll, 700)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { getSystemService(android.media.AudioManager::class.java).removeOnModeChangedListener(modeListener) }
        }
        window.decorView.removeCallbacks(callPoll)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (stepAsideIfCall()) return
        if (isLocked()) LockTimeout.apply(this)
        (container.nowPlaying as? NowPlayingRepositoryImpl)?.recheck()
    }

    /** Turn the screen off (and keep the phone locked) through precision mode or device admin. */
    private fun sleepScreen() {
        if (!isLocked()) return
        app.quiet.launcher.feature.focus.LockController.lockNow(this)
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { finishQuietly(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finishQuietly()
            override fun onDismissCancelled() = recreate()
            override fun onDismissError() = recreate()
        })
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }

    override fun onPause() {
        super.onPause()
        // Back to the user's own timeout whenever our lock screen is not the thing in front.
        if (!isLocked() || isFinishing) LockTimeout.restore(this)
    }

    override fun onDestroy() {
        LockTimeout.restore(this)
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PICKER = "picker"

        fun openPicker(context: Context) {
            context.startActivity(
                Intent(context, LockActivity::class.java).putExtra(EXTRA_PICKER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
