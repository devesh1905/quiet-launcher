package app.quiet.launcher.core.model

import android.graphics.Bitmap

/** Identity of a launchable activity across work profiles. */
data class AppKey(val packageName: String, val activityName: String, val userSerial: Long) {
    /** Stable string form used as a database / DataStore key. */
    val id: String get() = "$packageName/$activityName#$userSerial"

    companion object {
        fun parse(id: String): AppKey? {
            val hash = id.lastIndexOf('#'); val slash = id.indexOf('/')
            if (hash < 0 || slash < 0 || slash > hash) return null
            return AppKey(id.substring(0, slash), id.substring(slash + 1, hash), id.substring(hash + 1).toLongOrNull() ?: return null)
        }
    }
}

data class AppEntry(
    val key: AppKey,
    /** Label shown to the user (custom rename if set, else system label). */
    val label: String,
    val systemLabel: String,
    val isWorkProfile: Boolean,
    val firstInstallTime: Long,
    val hidden: Boolean = false,
    val folder: String? = null,
)

/** Today's phone usage for the home usage block and the usage screen. */
data class UsageToday(
    val totalMs: Long,
    val budgetMs: Long,
    val pickups: Int,
    /** 18 entries: minutes used in each hour from 06:00 to 23:00 (index 0 = 06:00–07:00). */
    val hourlyMinutes: IntArray,
    val currentHourIndex: Int,
    val perApp: List<AppUsage>,
) {
    companion object {
        val Empty = UsageToday(0, 2 * 3_600_000L, 0, IntArray(18), -1, emptyList())
    }
}

data class AppUsage(val packageName: String, val label: String, val millis: Long)

data class WeekUsage(
    /** 7 entries, oldest first, total ms per day. */
    val days: LongArray,
    val dayLabels: List<String>,
    val lastWeekTotalMs: Long,
    val thisWeekTotalMs: Long,
)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val year: String?,
    val art: Bitmap?,
    val durationMs: Long,
)

data class NowPlaying(
    val track: Track,
    val isPlaying: Boolean,
    /** Position at [positionUpdatedAt] (elapsedRealtime ms). */
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
    val sourcePackage: String,
    /** Palette swatches for the ambient shader as ARGB ints: darkMuted, vibrant, muted, lightVibrant. */
    val palette: IntArray,
) {
    fun positionAt(nowElapsed: Long): Long =
        if (!isPlaying) positionMs else (positionMs + ((nowElapsed - positionUpdatedAt) * speed).toLong())
            .coerceIn(0, track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

data class Artwork(
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
    val museum: String?,
    /** Absolute path to a cached image file on device. */
    val imagePath: String,
    val isUserProvided: Boolean = false,
)

enum class LockStyle { MASTERPIECE, CANVAS, SKY, TYPESET, COVER }
enum class LockFont { BODONI, INSTRUMENT, SHOULDERS }
enum class ThemeMode { BLACK, LIGHT, SYSTEM }
/** The lock-screen player's control: one of the play orbs, or the classic three buttons. */
enum class PlayerControl { DROP, MARBLE, SUN, GLASS, BUTTONS }
/** What the lock screen shows while the phone charges. */
enum class ChargeAnim { OFF, GLASS, FLOOD, THREAD, CLOCK }

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.BLACK,
    /** Quiet's own interactive lock screen over the keyguard (fingerprint goes through the system prompt). */
    val lockEnabled: Boolean = false,
    /** Render the chosen lock style as the system lock-screen wallpaper (system keeps clock + fingerprint). */
    val lockWallpaper: Boolean = true,
    val lockOnlyWithMusic: Boolean = false,
    /** Show the music player (cover art) on Quiet's lock screen. */
    val lockShowMusic: Boolean = true,
    val lockStyle: LockStyle = LockStyle.MASTERPIECE,
    val lockFont: LockFont = LockFont.BODONI,
    val favouriteArtists: Set<String> = setOf("Vincent van Gogh", "Claude Monet", "Katsushika Hokusai"),
    val dailyBudgetMinutes: Int = 120,
    val showUsageOnHome: Boolean = true,
    val showNowPlayingOnHome: Boolean = true,
    /** The artwork's colour glow behind Home while music plays. */
    val homeGlow: Boolean = true,
    val ambientStrength: Float = 1f,
    val swipeUpSearch: Boolean = true,
    val swipeDownNotifications: Boolean = true,
    val doubleTapLock: Boolean = true,
    val autoKeyboard: Boolean = true,
    val oneThingToday: String = "",
    val cityName: String = "",
    val latitude: Double = 28.61,
    val longitude: Double = 77.21,
    val reducedMotion: Boolean = false,
    /** A quiet notification at [receiptMinute] (minutes after midnight) opens the day receipt. */
    val receiptEnabled: Boolean = true,
    val receiptMinute: Int = 22 * 60,
    val playerControl: PlayerControl = PlayerControl.DROP,
    /** Lock screen › Charging. */
    val lockCharging: ChargeAnim = ChargeAnim.FLOOD,
)
