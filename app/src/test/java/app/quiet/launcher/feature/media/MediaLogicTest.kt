package app.quiet.launcher.feature.media

import org.junit.Assert.*
import org.junit.Test

class MediaLogicTest {
    private data class Fake(override val packageName: String, override val isActivelyPlaying: Boolean) : SessionInfo

    @Test fun picksPlayingOverOthers() {
        val l = listOf(Fake("a", false), Fake("b", true), Fake("c", true))
        assertEquals("b", MediaLogic.select(l, "a")!!.packageName)
    }

    @Test fun fallsBackToLastPlayingThenFirst() {
        val l = listOf(Fake("a", false), Fake("b", false))
        assertEquals("b", MediaLogic.select(l, "b")!!.packageName)
        assertEquals("a", MediaLogic.select(l, "zzz")!!.packageName)
        assertEquals("a", MediaLogic.select(l, null)!!.packageName)
        assertNull(MediaLogic.select(emptyList<Fake>(), "a"))
    }

    @Test fun trackIdStableAndDistinct() {
        val a = MediaLogic.trackId("p", "T", "A", 1000)
        assertEquals(a, MediaLogic.trackId("p", "T", "A", 1000))
        assertNotEquals(a, MediaLogic.trackId("p", "T2", "A", 1000))
        assertNotEquals(a, MediaLogic.trackId("q", "T", "A", 1000))
        assertNotEquals(a, MediaLogic.trackId("p", "T", "A", 1001))
    }

    @Test fun expiry() {
        assertFalse(MediaLogic.isExpired(true, 0, 10 * MediaLogic.PAUSED_TTL_MS))
        assertFalse(MediaLogic.isExpired(false, 0, MediaLogic.PAUSED_TTL_MS))
        assertTrue(MediaLogic.isExpired(false, 0, MediaLogic.PAUSED_TTL_MS + 1))
    }

    @Test fun year() {
        assertEquals("1999", MediaLogic.yearOf(1999, null))
        assertEquals("2019", MediaLogic.yearOf(0, "2019-05-01"))
        assertNull(MediaLogic.yearOf(0, "n/a"))
    }

    @Test fun scaling() {
        assertEquals(640 to 320, MediaLogic.scaledSize(1280, 640))
        assertEquals(300 to 200, MediaLogic.scaledSize(300, 200))
    }

    @Test fun hslRoundTrip() {
        val c = 0xFF3366CC.toInt()
        val hsl = PaletteMath.rgbToHsl(c)
        val back = PaletteMath.hslToRgb(hsl[0], hsl[1], hsl[2])
        for (shift in listOf(16, 8, 0)) assertEquals(((c shr shift) and 0xFF).toDouble(), ((back shr shift) and 0xFF).toDouble(), 1.0)
    }

    @Test fun paletteFallbacks() {
        assertArrayEquals(PaletteMath.Neutral, PaletteMath.build(null, null, null, null, null))
        val p = PaletteMath.build(null, null, null, null, 0xFFCC3333.toInt())
        assertEquals(4, p.size)
        assertTrue(PaletteMath.rgbToHsl(p[0])[2] < 0.2f)
        assertTrue(PaletteMath.rgbToHsl(p[3])[2] > 0.6f)
        assertEquals(0xFF112233.toInt(), PaletteMath.build(0xFF112233.toInt(), null, null, null, 0xFFCC3333.toInt())[0])
    }
}
