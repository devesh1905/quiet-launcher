package app.quiet.launcher.feature.monochrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeLogicTest {
    private val sel = MonochromeConfig(selectedEnabled = true, selectedPackages = setOf("a.b"))
    private val night = MonochromeConfig(windDownEnabled = true, windDownStartMin = 22 * 60, windDownEndMin = 6 * 60)

    @Test fun selectedAppOnly() {
        assertTrue(shouldBeMonochrome(sel, "a.b", 12 * 60))
        assertFalse(shouldBeMonochrome(sel, "c.d", 12 * 60))
        assertFalse(shouldBeMonochrome(sel, null, 12 * 60))
    }

    @Test fun selectedDisabledIgnoresSet() {
        assertFalse(shouldBeMonochrome(sel.copy(selectedEnabled = false), "a.b", 0))
    }

    @Test fun overnightWindow() {
        assertTrue(shouldBeMonochrome(night, null, 23 * 60))
        assertTrue(shouldBeMonochrome(night, null, 0))
        assertTrue(shouldBeMonochrome(night, null, 5 * 60 + 59))
        assertFalse(shouldBeMonochrome(night, null, 6 * 60))
        assertFalse(shouldBeMonochrome(night, null, 21 * 60 + 59))
        assertTrue(shouldBeMonochrome(night, null, 22 * 60))
    }

    @Test fun sameDayWindowAndEmpty() {
        assertTrue(inWindDown(9 * 60, 17 * 60, 9 * 60))
        assertFalse(inWindDown(9 * 60, 17 * 60, 17 * 60))
        assertFalse(inWindDown(600, 600, 600))
    }

    @Test fun windDownDisabled() {
        assertFalse(shouldBeMonochrome(night.copy(windDownEnabled = false), null, 23 * 60))
    }

    @Test fun combine() {
        val both = sel.copy(windDownEnabled = true, windDownStartMin = 22 * 60, windDownEndMin = 6 * 60)
        assertTrue(shouldBeMonochrome(both, "a.b", 12 * 60))
        assertTrue(shouldBeMonochrome(both, "x", 23 * 60))
        assertFalse(shouldBeMonochrome(both, "x", 12 * 60))
    }

    @Test fun format() = assertEquals("06:05", formatMinuteOfDay(6 * 60 + 5))
}
