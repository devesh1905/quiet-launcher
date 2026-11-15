package app.quiet.launcher.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private val own = "app.quiet.launcher"
    private val on = FilterSettings(enabled = true)
    private val defaults = NotificationRules.defaultAllowed

    private fun facts(
        pkg: String = "com.example.social",
        ongoing: Boolean = false,
        fgs: Boolean = false,
        noClear: Boolean = false,
        clearable: Boolean = true,
        media: Boolean = false,
        call: Boolean = false,
        category: String? = null,
        summary: Boolean = false,
    ) = NotificationFacts(pkg, ongoing, fgs, noClear, clearable, media, call, category, summary)

    private fun decide(f: NotificationFacts, s: FilterSettings = on) =
        NotificationRules.decide(f, s, defaults, own)

    @Test fun offLeavesEverything() {
        assertEquals(Verdict.LEAVE, decide(facts(), FilterSettings(enabled = false)))
    }

    @Test fun holdsPlainNotificationFromUnallowedApp() {
        assertEquals(Verdict.HOLD, decide(facts()))
    }

    @Test fun defaultsAllowMessagingAndCalls() {
        assertEquals(Verdict.LEAVE, decide(facts(pkg = "com.whatsapp")))
        assertEquals(Verdict.LEAVE, decide(facts(pkg = "com.google.android.dialer")))
    }

    @Test fun userDenyOverridesDefault() {
        val s = on.copy(deny = setOf("com.whatsapp"))
        assertEquals(Verdict.HOLD, decide(facts(pkg = "com.whatsapp"), s))
    }

    @Test fun userAllowOverridesFilter() {
        val s = on.copy(allow = setOf("com.example.social"))
        assertEquals(Verdict.LEAVE, decide(facts(), s))
    }

    @Test fun ownAndSystemNeverHeld() {
        assertEquals(Verdict.LEAVE, decide(facts(pkg = own)))
        assertEquals(Verdict.LEAVE, decide(facts(pkg = "android")))
        assertEquals(Verdict.LEAVE, decide(facts(pkg = "com.android.systemui")))
    }

    @Test fun mediaCallAndProtectedCategoriesPass() {
        assertEquals(Verdict.LEAVE, decide(facts(media = true)))
        assertEquals(Verdict.LEAVE, decide(facts(call = true)))
        assertEquals(Verdict.LEAVE, decide(facts(category = "alarm")))
        assertEquals(Verdict.LEAVE, decide(facts(category = "navigation")))
    }

    @Test fun ongoingIsLeftUnlessHideOngoingThenSnoozed() {
        assertEquals(Verdict.LEAVE, decide(facts(ongoing = true)))
        assertEquals(Verdict.LEAVE, decide(facts(fgs = true)))
        val hide = on.copy(hideOngoing = true)
        assertEquals(Verdict.SNOOZE, decide(facts(ongoing = true), hide))
        assertEquals(Verdict.SNOOZE, decide(facts(fgs = true), hide))
        assertEquals(Verdict.SNOOZE, decide(facts(clearable = false), hide))
        assertEquals(Verdict.LEAVE, decide(facts(media = true, ongoing = true), hide))
    }

    @Test fun settingsIsAllowedResolution() {
        val s = FilterSettings(allow = setOf("a"), deny = setOf("b"))
        assertTrue(s.isAllowed("a", emptySet()))
        assertFalse(s.isAllowed("b", setOf("b")))
        assertTrue(s.isAllowed("c", setOf("c")))
        assertFalse(s.isAllowed("d", setOf("c")))
    }

    @Test fun extractPrefersBigTextThenLinesThenText() {
        val a = NotificationRules.extract("T", "short", "the long body", listOf("l1"), null, null)
        assertEquals("the long body", a.text)
        val b = NotificationRules.extract("T", "short", null, listOf(" one ", "", "two"), null, null)
        assertEquals("one\ntwo", b.text)
        val c = NotificationRules.extract("T", "short", " ", null, null, null)
        assertEquals("short", c.text)
        assertEquals("T", c.title)
    }

    @Test fun extractKeepsSubAndInfoAsMeta() {
        val e = NotificationRules.extract("T", "body", null, null, "Inbox", "3")
        assertEquals("body", e.text)
        assertEquals("Inbox  3", e.meta)
    }

    @Test fun extractFallsBackToMetaWhenNoBody() {
        val e = NotificationRules.extract("T", null, null, null, "Sub", null)
        assertEquals("Sub", e.text)
        assertEquals("", e.meta)
    }

    @Test fun extractTruncates() {
        val e = NotificationRules.extract("t".repeat(500), "x".repeat(5000), null, null, null, null)
        assertEquals(300, e.title.length)
        assertEquals(2000, e.text.length)
    }

    @Test fun summaryTitleCapsAt99() {
        assertEquals("1 notification held", NotificationRules.summaryTitle(1))
        assertEquals("12 notifications held", NotificationRules.summaryTitle(12))
        assertEquals("99 notifications held", NotificationRules.summaryTitle(99))
        assertEquals("99+ notifications held", NotificationRules.summaryTitle(100))
    }

    @Test fun pruneKeepsSevenDays() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertEquals(3L * 24 * 60 * 60 * 1000, NotificationRules.pruneCutoff(now))
    }

    @Test fun relativeTime() {
        val now = 100L * 60 * 60 * 1000
        assertEquals("Now", NotificationRules.relativeTime(now, now - 20_000))
        assertEquals("5 min", NotificationRules.relativeTime(now, now - 5 * 60_000))
        assertEquals("3 h", NotificationRules.relativeTime(now, now - 3 * 3_600_000))
        assertEquals("Yesterday", NotificationRules.relativeTime(now, now - 30 * 3_600_000))
        assertEquals("3 days", NotificationRules.relativeTime(now, now - 80 * 3_600_000))
    }
}
