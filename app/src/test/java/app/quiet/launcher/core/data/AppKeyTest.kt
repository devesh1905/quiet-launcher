package app.quiet.launcher.core.data

import app.quiet.launcher.core.model.AppKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppKeyTest {
    @Test fun roundTrip() {
        val key = AppKey("com.example.app", "com.example.app.MainActivity", 10L)
        assertEquals(key, AppKey.parse(key.id))
    }

    @Test fun activityNameContainingHashRoundTrips() {
        // parse() splits on the LAST '#', so '#' inside the activity name survives.
        val key = AppKey("com.example", "com.example.Outer#Inner", 0L)
        assertEquals("com.example/com.example.Outer#Inner#0", key.id)
        assertEquals(key, AppKey.parse(key.id))
    }

    @Test fun activityNameContainingSlashRoundTrips() {
        // The package is everything before the FIRST '/'.
        val key = AppKey("com.example", "a/b.C", 5L)
        assertEquals(key, AppKey.parse(key.id))
    }

    @Test fun malformedIdsReturnNull() {
        assertNull(AppKey.parse(""))
        assertNull(AppKey.parse("pkg/activity"))
        assertNull(AppKey.parse("pkg#1"))
        assertNull(AppKey.parse("pkg/activity#notANumber"))
        assertNull(AppKey.parse("a#b/c"))
    }
}
