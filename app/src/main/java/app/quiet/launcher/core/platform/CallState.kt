package app.quiet.launcher.core.platform

import android.content.Context
import android.media.AudioManager

/**
 * True while the phone is ringing or a call (cellular or VoIP) is active. Uses the audio mode,
 * which needs no phone permission and covers WhatsApp/Meet-style calls as well.
 */
object CallState {
    fun isCallActive(ctx: Context): Boolean {
        val mode = ctx.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION || mode == 4 /* MODE_CALL_SCREENING, API 30 */
    }
}
