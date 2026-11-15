package app.quiet.launcher.feature.monochrome

/** User choices for monochrome mode. Times are minutes after midnight. */
data class MonochromeConfig(
    val selectedEnabled: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    val windDownEnabled: Boolean = false,
    val windDownStartMin: Int = 22 * 60,
    val windDownEndMin: Int = 6 * 60,
    /** Grayscale on everywhere until this wall-clock time (quick action), 0 = off. */
    val manualUntilMs: Long = 0L,
)

/** True when [nowMin] lies in [startMin, endMin); the window may wrap past midnight. Equal bounds mean empty. */
fun inWindDown(startMin: Int, endMin: Int, nowMin: Int): Boolean = when {
    startMin == endMin -> false
    startMin < endMin -> nowMin in startMin until endMin
    else -> nowMin >= startMin || nowMin < endMin
}

/** Pure decision: should the display be grayscale right now? */
fun shouldBeMonochrome(config: MonochromeConfig, foreground: String?, nowMin: Int, nowMs: Long = 0L): Boolean {
    if (nowMs > 0L && nowMs < config.manualUntilMs) return true
    if (config.windDownEnabled && inWindDown(config.windDownStartMin, config.windDownEndMin, nowMin)) return true
    if (config.selectedEnabled && foreground != null && foreground in config.selectedPackages) return true
    return false
}

fun formatMinuteOfDay(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)
