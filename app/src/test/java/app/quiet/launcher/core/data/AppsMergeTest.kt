package app.quiet.launcher.core.data

import app.quiet.launcher.core.data.db.AppMetaEntity
import app.quiet.launcher.core.model.AppKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsMergeTest {
    private fun raw(pkg: String, label: String, serial: Long = 0L, work: Boolean = false) =
        RawApp(AppKey(pkg, "$pkg.Main", serial), label, work, 1000L)

    // Plain case-insensitive order keeps the tests independent of the JVM default locale.
    private val order = String.CASE_INSENSITIVE_ORDER

    @Test fun sortsByLabelIgnoringCase() {
        val result = mergeApps(listOf(raw("c", "cherry"), raw("a", "Apple"), raw("b", "banana")), emptyList(), order)
        assertEquals(listOf("Apple", "banana", "cherry"), result.map { it.label })
    }

    @Test fun defaultCollatorIgnoresCaseAndAccents() {
        val result = mergeApps(listOf(raw("z", "Zed"), raw("e", "Éclair"), raw("a", "apple")), emptyList())
        assertEquals(listOf("apple", "Éclair", "Zed"), result.map { it.label })
    }

    @Test fun customLabelOverridesSystemLabelAndAffectsOrder() {
        val a = raw("a", "Alpha")
        val b = raw("b", "Beta")
        val meta = listOf(AppMetaEntity(a.key.id, customLabel = "Zulu"))
        val result = mergeApps(listOf(a, b), meta, order)
        assertEquals(listOf("Beta", "Zulu"), result.map { it.label })
        assertEquals("Alpha", result.last().systemLabel)
    }

    @Test fun blankCustomLabelFallsBackToSystemLabel() {
        val a = raw("a", "Alpha")
        val result = mergeApps(listOf(a), listOf(AppMetaEntity(a.key.id, customLabel = "  ")), order)
        assertEquals("Alpha", result.single().label)
    }

    @Test fun hiddenAndFolderAreCarriedAndHiddenAppsStayInList() {
        val a = raw("a", "Alpha")
        val b = raw("b", "Beta")
        val result = mergeApps(listOf(a, b), listOf(AppMetaEntity(a.key.id, hidden = true, folder = "Work")), order)
        assertEquals(2, result.size)
        assertTrue(result[0].hidden)
        assertEquals("Work", result[0].folder)
        assertFalse(result[1].hidden)
        assertNull(result[1].folder)
    }

    @Test fun sameLabelAcrossProfilesIsStableByKey() {
        val personal = raw("p", "Chat", serial = 0L)
        val work = raw("p", "Chat", serial = 10L, work = true)
        val one = mergeApps(listOf(work, personal), emptyList(), order)
        val two = mergeApps(listOf(personal, work), emptyList(), order)
        assertEquals(one.map { it.key }, two.map { it.key })
        assertTrue(one.single { it.key.userSerial == 10L }.isWorkProfile)
    }

    @Test fun metaForUninstalledAppsIsIgnored() {
        val result = mergeApps(listOf(raw("a", "Alpha")), listOf(AppMetaEntity("gone/x#0", hidden = true)), order)
        assertEquals(1, result.size)
    }

    @Test fun favouritesKeepOrderAndSkipMissingApps() {
        val apps = mergeApps(listOf(raw("a", "Alpha"), raw("b", "Beta"), raw("c", "Gamma")), emptyList(), order)
        val ids = listOf(apps[2].key.id, "gone/x#0", apps[0].key.id)
        assertEquals(listOf("Gamma", "Alpha"), mergeFavourites(ids, apps).map { it.label })
    }

    @Test fun reorderMovesVisibleItemAndKeepsMissingRowsAtEnd() {
        val all = listOf("a", "ghost", "b", "c")
        val visible = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b", "ghost"), reorderFavourites(all, visible, 2, 0))
        assertEquals(listOf("b", "a", "c", "ghost"), reorderFavourites(all, visible, 0, 1))
    }

    @Test fun reorderRejectsInvalidOrNoopMoves() {
        val ids = listOf("a", "b")
        assertNull(reorderFavourites(ids, ids, 0, 0))
        assertNull(reorderFavourites(ids, ids, -1, 1))
        assertNull(reorderFavourites(ids, ids, 0, 2))
    }
}
