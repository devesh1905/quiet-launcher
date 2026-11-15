package app.quiet.launcher.core.data

import app.quiet.launcher.core.data.db.AppMetaEntity
import app.quiet.launcher.core.model.AppEntry
import app.quiet.launcher.core.model.AppKey
import java.text.Collator

/** A launchable activity as reported by the system (or restored from the cold-start cache). */
internal data class RawApp(
    val key: AppKey,
    val systemLabel: String,
    val isWorkProfile: Boolean,
    val firstInstallTime: Long,
)

/** Locale-aware, accent/case-insensitive label ordering. */
internal fun labelCollator(): Comparator<String> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * Merges the system app list with user metadata. Label = custom label (if non-blank) else system
 * label; the result is sorted by label, with the key id as a stable tie-breaker. Hidden apps are
 * kept, flagged `hidden = true`.
 */
internal fun mergeApps(
    raw: List<RawApp>,
    meta: Collection<AppMetaEntity>,
    labelOrder: Comparator<String> = labelCollator(),
): List<AppEntry> {
    val byId = meta.associateBy { it.id }
    val entries = raw.map { r ->
        val m = byId[r.key.id]
        AppEntry(
            key = r.key,
            label = (m?.customLabel?.takeIf { it.isNotBlank() } ?: r.systemLabel).trim(),
            systemLabel = r.systemLabel,
            isWorkProfile = r.isWorkProfile,
            firstInstallTime = r.firstInstallTime,
            hidden = m?.hidden ?: false,
            folder = m?.folder,
        )
    }
    return entries.sortedWith(
        Comparator<AppEntry> { a, b -> labelOrder.compare(a.label, b.label) }.thenBy { it.key.id }
    )
}

/** Resolves favourite ids (already in position order) to current apps, skipping uninstalled ones. */
internal fun mergeFavourites(orderedFavouriteIds: List<String>, apps: List<AppEntry>): List<AppEntry> {
    val byId = apps.associateBy { it.key.id }
    return orderedFavouriteIds.mapNotNull { byId[it] }
}

/**
 * Computes the new full ordering of favourite rows after moving visible item [from] to [to].
 * [allIds] are all stored rows in order; [visibleIds] the subset currently resolvable to apps.
 * Rows whose app is missing keep their relative order and are placed after the visible ones.
 * Returns null if the indices are invalid or nothing changes.
 */
internal fun reorderFavourites(allIds: List<String>, visibleIds: List<String>, from: Int, to: Int): List<String>? {
    if (from !in visibleIds.indices || to !in visibleIds.indices || from == to) return null
    val visible = visibleIds.toMutableList()
    visible.add(to, visible.removeAt(from))
    val visibleSet = visibleIds.toSet()
    return visible + allIds.filterNot { it in visibleSet }
}
