package app.quiet.launcher.core.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Which app is in front right now. Fed by usage-event polling while the screen is on, or by the
 * accessibility service (precision mode) when it is enabled. Consumers: focus engine, monochrome.
 */
interface ForegroundTracker {
    /** Package in front; null when unknown, screen off, or our own launcher/lock screen is in front. */
    val foreground: StateFlow<String?>
    /** True while the accessibility service is connected (precision mode). */
    val precise: StateFlow<Boolean>
}
