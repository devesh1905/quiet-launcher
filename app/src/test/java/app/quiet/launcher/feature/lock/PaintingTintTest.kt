package app.quiet.launcher.feature.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintingTintTest {
    private val black = 0xFF000000.toInt()
    private val ink = 0xFFEDEBE6.toInt()
    private val lightBg = 0xFFF3F1EB.toInt()
    private val lightInk = 0xFF141311.toInt()

    @Test fun clockKeepsNineToOneOnBlackForAnySwatch() {
        val swatches = intArrayOf(0xFF1E2A6B.toInt(), 0xFF8B0000.toInt(), 0xFF2E7D32.toInt(), 0xFFEED9B4.toInt(), black, 0xFF0000FF.toInt())
        for (s in swatches) {
            val t = PaintingTint.clock(s, ink, black)
            assertTrue("%08x -> %08x".format(s, t), PaintingTint.contrast(t, black) >= 9.0)
        }
    }

    @Test fun clockKeepsContrastOnTheLightWall() {
        val t = PaintingTint.clock(0xFFFFE08A.toInt(), lightInk, lightBg)
        assertTrue(PaintingTint.contrast(t, lightBg) >= 9.0)
    }

    @Test fun aLightWarmSwatchActuallyTints() {
        val s = 0xFFEED9B4.toInt()
        val t = PaintingTint.clock(s, ink, black)
        assertTrue(t != ink)
    }

    @Test fun contrastMatchesWcag() {
        assertEquals(21.0, PaintingTint.contrast(0xFFFFFFFF.toInt(), black), 0.01)
        assertEquals(1.0, PaintingTint.contrast(ink, ink), 0.0001)
    }

    @Test fun breathRestsAtZeroAndPeaksHalfway() {
        assertEquals(0f, Breath.at(0), 1e-4f)
        assertEquals(1f, Breath.at(Breath.PERIOD_MS / 2), 1e-4f)
        assertEquals(0f, Breath.at(Breath.PERIOD_MS), 1e-4f)
    }

    @Test fun breathResumesAtTheSameLevel() {
        for (l in floatArrayOf(0f, 0.2f, 0.5f, 0.9f, 1f)) {
            assertEquals(l, Breath.at(Breath.msFor(l)), 0.01f)
        }
    }
}
