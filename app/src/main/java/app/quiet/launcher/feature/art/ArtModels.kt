package app.quiet.launcher.feature.art

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** A public-domain work offered by a museum API, before download. */
data class ArtCandidate(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val year: String?,
    val museum: String,
    val imageUrl: String,
    /** Museum classification / type ("Painting", "Prints" ...), used only for preference. */
    val type: String? = null,
) {
    /** File-safe, stable id. */
    val id: String get() = "${source}_${sourceId}".replace(Regex("[^A-Za-z0-9_-]"), "")
}

@Serializable
data class StoredWork(
    val id: String,
    val title: String,
    val artist: String,
    val year: String? = null,
    val museum: String? = null,
    /** File name inside filesDir/art. */
    val file: String,
    val isUser: Boolean = false,
    val addedAt: Long,
)

@Serializable
data class HistoryEntry(val id: String, val shownAt: Long)

@Serializable
data class ArtIndex(
    val works: List<StoredWork> = emptyList(),
    val currentId: String? = null,
    /** Local date (yyyy-MM-dd) on which [currentId] became current. */
    val currentDate: String? = null,
    val history: List<HistoryEntry> = emptyList(),
    val lastFailureAt: Long = 0,
)

/** Pure selection / rotation logic. No Android dependencies. */
object ArtLogic {
    const val MAX_CACHED = 7
    const val MIN_CACHED = 3
    const val NO_REPEAT_DAYS = 60L
    const val BACKOFF_MS = 30 * 60_000L
    const val MIN_LONGEST_SIDE = 1000
    const val MAX_LONGEST_SIDE = 2400
    private const val DAY_MS = 86_400_000L
    private const val HISTORY_RETENTION_DAYS = 180L

    val OTHER_MASTERS = listOf(
        "Johannes Vermeer", "Utagawa Hiroshige", "Paul Cézanne", "Georges Seurat", "Gustav Klimt",
        "Rembrandt van Rijn", "J. M. W. Turner", "Katsushika Hokusai", "Edgar Degas", "Camille Pissarro",
        "Edouard Manet", "Pierre-Auguste Renoir", "Paul Gauguin", "Winslow Homer", "John Singer Sargent",
        "Caspar David Friedrich", "Francisco Goya", "Utagawa Kuniyoshi",
    )

    fun localDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    fun millisUntilNextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        val next: ZonedDateTime = z.toLocalDate().plusDays(1).atStartOfDay(zone)
        return (next.toInstant().toEpochMilli() - now).coerceAtLeast(1_000)
    }

    /** Favourites 80%, other masters 20% (falls back to whichever list is non-empty). */
    fun pickArtist(favourites: Set<String>, others: List<String>, rnd: Random, favouriteWeight: Double = 0.8): String {
        val favs = favourites.map { it.trim() }.filter { it.isNotEmpty() }
        val rest = others.filter { o -> favs.none { it.equals(o, ignoreCase = true) } }
        return when {
            favs.isEmpty() -> rest.random(rnd)
            rest.isEmpty() -> favs.random(rnd)
            rnd.nextDouble() < favouriteWeight -> favs.random(rnd)
            else -> rest.random(rnd)
        }
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    /** True when the candidate's artist text contains the query's surname (last token). */
    fun artistMatches(candidateArtist: String, query: String): Boolean {
        val last = norm(query).split(Regex("\\s+")).lastOrNull { it.isNotEmpty() } ?: return true
        return norm(candidateArtist).contains(last)
    }

    fun isPreferredType(type: String?): Boolean {
        val t = type?.lowercase() ?: return false
        return listOf("paint", "print", "drawing", "watercolor", "woodcut").any { t.contains(it) }
    }

    /** Paintings first; only if an artist has none, colour woodblock prints (ukiyo-e) or watercolours. */
    fun isPainting(type: String?): Boolean = type?.lowercase()?.contains("paint") == true

    private fun isColourWork(type: String?): Boolean {
        val t = type?.lowercase() ?: return false
        return listOf("woodcut", "woodblock", "watercolor", "watercolour").any { t.contains(it) }
    }

    /** Keep artist matches; prefer paintings, then colour prints/watercolours, then anything typed. */
    fun shortlist(cands: List<ArtCandidate>, artist: String): List<ArtCandidate> {
        val matched = cands.filter { artistMatches(it.artist, artist) }
        return matched.filter { isPainting(it.type) }
            .ifEmpty { matched.filter { isColourWork(it.type) } }
            .ifEmpty { matched.filter { isPreferredType(it.type) } }
            .ifEmpty { matched }
    }

    fun recentIds(history: List<HistoryEntry>, now: Long, days: Long = NO_REPEAT_DAYS): Set<String> =
        history.filter { now - it.shownAt < days * DAY_MS }.map { it.id }.toSet()

    fun trimHistory(history: List<HistoryEntry>, now: Long): List<HistoryEntry> =
        history.filter { now - it.shownAt < HISTORY_RETENTION_DAYS * DAY_MS }.takeLast(500)

    /** Random candidate not in [excludeIds]. */
    fun selectCandidate(cands: List<ArtCandidate>, excludeIds: Set<String>, rnd: Random): ArtCandidate? =
        cands.filter { it.id !in excludeIds }.randomOrNull(rnd)

    fun cachedWorks(index: ArtIndex) = index.works.filter { !it.isUser }

    fun unseenCount(index: ArtIndex, now: Long): Int {
        val seen = recentIds(index.history, now)
        return cachedWorks(index).count { it.id !in seen }
    }

    /**
     * Next work to show: prefers unseen (not shown in the last 60 days), never the current one
     * unless it is the only one; among seen works picks the least recently shown.
     */
    fun pickNext(index: ArtIndex, now: Long): StoredWork? {
        val cached = cachedWorks(index)
        if (cached.isEmpty()) return null
        val others = cached.filter { it.id != index.currentId }.ifEmpty { return cached.first() }
        val lastShown = index.history.groupBy { it.id }.mapValues { e -> e.value.maxOf { it.shownAt } }
        val seen = recentIds(index.history, now)
        val unseen = others.filter { it.id !in seen }
        if (unseen.isNotEmpty()) return unseen.maxByOrNull { it.addedAt }
        return others.minByOrNull { lastShown[it.id] ?: 0L }
    }

    private fun makeCurrent(index: ArtIndex, work: StoredWork, now: Long, zone: ZoneId): ArtIndex =
        index.copy(
            currentId = work.id,
            currentDate = localDate(now, zone),
            history = trimHistory(index.history + HistoryEntry(work.id, now), now),
        )

    /** Advance to the next cached work right now. */
    fun skip(index: ArtIndex, now: Long, zone: ZoneId = ZoneId.systemDefault()): ArtIndex {
        val next = pickNext(index, now) ?: return index
        return makeCurrent(index, next, now, zone)
    }

    /** Rotate if the date changed or the current work is missing. */
    fun ensureToday(index: ArtIndex, now: Long, zone: ZoneId = ZoneId.systemDefault()): ArtIndex {
        val curOk = index.currentId != null && cachedWorks(index).any { it.id == index.currentId }
        if (curOk && index.currentDate == localDate(now, zone)) return index
        val next = pickNext(index, now) ?: return index
        return makeCurrent(index, next, now, zone)
    }

    fun needsRefresh(index: ArtIndex, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val cached = cachedWorks(index)
        val currentMissing = index.currentId == null || cached.none { it.id == index.currentId } ||
            index.currentDate != localDate(now, zone)
        return currentMissing || cached.size < MIN_CACHED || unseenCount(index, now) < MIN_CACHED
    }

    fun inBackoff(index: ArtIndex, now: Long): Boolean =
        index.lastFailureAt > 0 && now - index.lastFailureAt < BACKOFF_MS

    /** Works to delete so at most [keep] non-user works remain; seen ones go first, oldest first; never the current. */
    fun toEvict(index: ArtIndex, now: Long, keep: Int = MAX_CACHED): List<StoredWork> {
        val cached = cachedWorks(index)
        val excess = cached.size - keep
        if (excess <= 0) return emptyList()
        val seen = recentIds(index.history, now)
        return cached.filter { it.id != index.currentId }
            .sortedWith(compareBy<StoredWork> { it.id !in seen }.thenBy { it.addedAt })
            .take(excess)
    }

    /** How many new works a refresh run should try to add. */
    fun fetchTarget(index: ArtIndex): Int {
        val n = cachedWorks(index).size
        return if (n < MAX_CACHED) minOf(4, MAX_CACHED - n) else 3
    }
}
