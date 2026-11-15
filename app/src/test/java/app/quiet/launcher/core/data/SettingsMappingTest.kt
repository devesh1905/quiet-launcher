package app.quiet.launcher.core.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.quiet.launcher.core.model.LauncherSettings
import app.quiet.launcher.core.model.LockFont
import app.quiet.launcher.core.model.LockStyle
import app.quiet.launcher.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMappingTest {
    @Test fun emptyPreferencesYieldDefaults() {
        assertEquals(LauncherSettings(), mutablePreferencesOf().toSettings())
    }

    @Test fun everyFieldRoundTrips() {
        val custom = LauncherSettings(
            theme = ThemeMode.LIGHT, lockEnabled = false, lockOnlyWithMusic = true,
            lockStyle = LockStyle.TYPESET, lockFont = LockFont.SHOULDERS,
            favouriteArtists = setOf("A", "B"), dailyBudgetMinutes = 45, showUsageOnHome = false,
            showNowPlayingOnHome = false, ambientStrength = 0.4f, swipeUpSearch = false,
            swipeDownNotifications = false, doubleTapLock = false, autoKeyboard = false,
            oneThingToday = "Write", cityName = "Delhi", latitude = -12.5, longitude = 99.125,
            reducedMotion = true,
        )
        val prefs = mutablePreferencesOf().also { it.writeSettings(custom) }
        assertEquals(custom, prefs.toSettings())
    }

    @Test fun unknownEnumNamesFallBackToDefaults() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("theme") to "NEON",
            stringPreferencesKey("lockStyle") to "???",
            stringPreferencesKey("lockFont") to "",
        )
        val s = prefs.toSettings()
        val d = LauncherSettings()
        assertEquals(d.theme, s.theme)
        assertEquals(d.lockStyle, s.lockStyle)
        assertEquals(d.lockFont, s.lockFont)
    }
}
