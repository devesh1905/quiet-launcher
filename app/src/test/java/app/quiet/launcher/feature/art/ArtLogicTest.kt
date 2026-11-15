package app.quiet.launcher.feature.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class ArtLogicTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L
    private fun t(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun work(id: String, added: Long, user: Boolean = false) =
        StoredWork(id, id, "A", null, null, "$id.jpg", user, added)
    private fun cand(id: String, artist: String = "Claude Monet", type: String? = "Painting") =
        ArtCandidate("x", id, id, artist, null, "M", "https://u/$id", type)

    @Test fun artist_matching_ignores_case_and_diacritics() {
        assertTrue(ArtLogic.artistMatches("Paul Cezanne", "Paul Cézanne"))
        assertTrue(ArtLogic.artistMatches("Katsushika Hokusai (Japanese)", "Katsushika Hokusai"))
        assertFalse(ArtLogic.artistMatches("Claude Monet", "Vincent van Gogh"))
    }

    @Test fun shortlist_prefers_paintings_and_prints() {
        val s = ArtLogic.shortlist(listOf(cand("a", type = "Furniture"), cand("b", type = "Print"), cand("c", "Other")), "Claude Monet")
        assertEquals(listOf("x_b"), s.map { it.id })
        val fallback = ArtLogic.shortlist(listOf(cand("a", type = "Sculpture")), "Claude Monet")
        assertEquals(1, fallback.size)
    }

    @Test fun artist_pick_weighting() {
        val rnd = Random(42)
        val favs = setOf("Vincent van Gogh")
        val n = 4000
        val fav = (1..n).count { ArtLogic.pickArtist(favs, ArtLogic.OTHER_MASTERS, rnd) == "Vincent van Gogh" }
        assertTrue("fav share ${fav / n.toDouble()}", fav in (n * 0.76).toInt()..(n * 0.84).toInt())
        // empty favourites -> only masters; empty others -> only favourites
        assertTrue(ArtLogic.pickArtist(emptySet(), listOf("Z"), rnd) == "Z")
        assertEquals("F", ArtLogic.pickArtist(setOf("F"), emptyList(), rnd))
    }

    @Test fun recent_history_excludes_for_60_days() {
        val now = t(2026, 6, 1)
        val h = listOf(HistoryEntry("old", now - 61 * day), HistoryEntry("new", now - 10 * day))
        assertEquals(setOf("new"), ArtLogic.recentIds(h, now))
        val picked = ArtLogic.selectCandidate(listOf(cand("old"), cand("new")), setOf("x_new"), Random(1))
        assertEquals("x_old", picked!!.id)
        assertNull(ArtLogic.selectCandidate(listOf(cand("new")), setOf("x_new"), Random(1)))
    }

    @Test fun ensure_today_rotates_on_new_date_only() {
        val d1 = t(2026, 6, 1)
        val idx = ArtIndex(works = listOf(work("a", 1), work("b", 2), work("c", 3)))
        val first = ArtLogic.ensureToday(idx, d1, zone)
        assertNotNull(first.currentId)
        assertEquals(1, first.history.size)
        // same day: unchanged
        assertEquals(first, ArtLogic.ensureToday(first, d1 + 3_600_000, zone))
        // next day: a different work
        val second = ArtLogic.ensureToday(first, t(2026, 6, 2), zone)
        assertTrue(second.currentId != first.currentId)
        // third day: yet another (unseen preferred)
        val third = ArtLogic.ensureToday(second, t(2026, 6, 3), zone)
        assertEquals(setOf("a", "b", "c"), setOf(first.currentId, second.currentId, third.currentId))
    }

    @Test fun pick_next_falls_back_to_least_recently_shown_and_single_work() {
        val now = t(2026, 6, 10)
        val idx = ArtIndex(
            works = listOf(work("a", 1), work("b", 2), work("c", 3)),
            currentId = "c",
            history = listOf(HistoryEntry("a", now - 5 * day), HistoryEntry("b", now - 9 * day), HistoryEntry("c", now)),
        )
        assertEquals("b", ArtLogic.pickNext(idx, now)!!.id)
        val single = ArtIndex(works = listOf(work("a", 1)), currentId = "a")
        assertEquals("a", ArtLogic.pickNext(single, now)!!.id)
        assertNull(ArtLogic.pickNext(ArtIndex(works = listOf(work("u", 1, user = true))), now))
    }

    @Test fun skip_advances_immediately() {
        val now = t(2026, 6, 1)
        val idx = ArtLogic.ensureToday(ArtIndex(works = listOf(work("a", 1), work("b", 2))), now, zone)
        val s = ArtLogic.skip(idx, now, zone)
        assertTrue(s.currentId != idx.currentId)
    }

    @Test fun eviction_keeps_seven_never_current_never_user() {
        val now = t(2026, 6, 1)
        val works = (1..9).map { work("w$it", it.toLong()) } + work("mine", 0, user = true)
        val idx = ArtIndex(works = works, currentId = "w1", history = listOf(HistoryEntry("w2", now - day), HistoryEntry("w1", now)))
        val drop = ArtLogic.toEvict(idx, now).map { it.id }
        assertEquals(2, drop.size)
        assertFalse("w1" in drop)
        assertFalse("mine" in drop)
        assertTrue("w2" in drop) // seen goes first
    }

    @Test fun refresh_need_and_backoff() {
        val now = t(2026, 6, 1)
        assertTrue(ArtLogic.needsRefresh(ArtIndex(), now, zone))
        val full = ArtLogic.ensureToday(ArtIndex(works = (1..7).map { work("w$it", it.toLong()) }), now, zone)
        assertFalse(ArtLogic.needsRefresh(full, now, zone))
        assertTrue(ArtLogic.needsRefresh(full, t(2026, 6, 2), zone)) // current date stale
        assertTrue(ArtLogic.inBackoff(ArtIndex(lastFailureAt = now - 10 * 60_000), now))
        assertFalse(ArtLogic.inBackoff(ArtIndex(lastFailureAt = now - 31 * 60_000), now))
        assertFalse(ArtLogic.inBackoff(ArtIndex(), now))
    }

    @Test fun midnight_math() {
        val ms = ArtLogic.millisUntilNextMidnight(t(2026, 6, 1, 23), zone)
        assertEquals(3_600_000L, ms)
    }
}
