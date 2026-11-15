package app.quiet.launcher.feature.focus

import app.quiet.launcher.feature.focus.content.ContentMatcher
import app.quiet.launcher.feature.focus.content.ContentRules
import app.quiet.launcher.feature.focus.content.DomainMatcher
import app.quiet.launcher.feature.focus.content.NodeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A recorded-tree fixture node. */
private data class N(
    override val viewId: String? = null,
    override val className: String? = "android.view.View",
    override val contentDescription: String? = null,
    override val text: String? = null,
    override val isSelected: Boolean = false,
    override val isFocused: Boolean = false,
    val children: List<N> = emptyList(),
) : NodeView {
    override val childCount: Int get() = children.size
    override fun child(index: Int): NodeView? = children.getOrNull(index)
}

class ContentRulesTest {
    /** The shipped rules file; unit tests run with the module directory as working directory. */
    private val rules: ContentRules by lazy {
        val f = listOf(File("src/main/assets/focus_rules.json"), File("app/src/main/assets/focus_rules.json")).first { it.exists() }
        ContentRules.parse(f.readText())
    }

    private fun feed(vararg leaves: N) = N(viewId = "com.x:id/root", children = listOf(N(children = leaves.toList())))

    @Test
    fun shippedRulesParseAndCoverTheFourApps() {
        val ids = rules.shortVideo.map { it.id }.toSet()
        assertEquals(setOf("youtube_shorts", "instagram_reels", "facebook_reels", "snapchat_spotlight"), ids)
        assertTrue(rules.browsers.any { it.pkg == "com.android.chrome" })
        assertTrue(rules.browsers.any { it.pkg == "org.mozilla.firefox" })
        assertTrue(rules.browsers.any { it.pkg == "com.microsoft.emmx" })
        assertTrue(rules.domains.getValue("instagram.com").contains("com.instagram.android"))
    }

    @Test
    fun youtubeShortsMatchesByViewId() {
        val yt = rules.shortVideoRulesFor("com.google.android.youtube")
        val tree = feed(N(viewId = "com.google.android.youtube:id/reel_recycler"))
        assertEquals("youtube_shorts", ContentMatcher.findShortVideo(tree, yt)?.id)
    }

    @Test
    fun youtubeHomeFeedDoesNotMatch() {
        val yt = rules.shortVideoRulesFor("com.google.android.youtube")
        val tree = feed(
            N(viewId = "com.google.android.youtube:id/results"),
            N(contentDescription = "Shorts", isSelected = false),
            N(contentDescription = "Home", isSelected = true),
        )
        assertNull(ContentMatcher.findShortVideo(tree, yt))
    }

    @Test
    fun selectedReelsTabMatchesInstagram() {
        val ig = rules.shortVideoRulesFor("com.instagram.android")
        val tree = feed(N(contentDescription = "Reels", isSelected = true))
        assertEquals("instagram_reels", ContentMatcher.findShortVideo(tree, ig)?.id)
    }

    @Test
    fun rulesAreScopedToTheirPackages() {
        assertTrue(rules.shortVideoRulesFor("com.example.notes").isEmpty())
        assertEquals(listOf("snapchat_spotlight"), rules.shortVideoRulesFor("com.snapchat.android").map { it.id })
    }

    @Test
    fun walkStopsAtTheNodeCap() {
        var deep = N(viewId = "com.google.android.youtube:id/reel_recycler")
        repeat(ContentMatcher.MAX_NODES + 50) { deep = N(children = listOf(deep)) }
        assertNull(ContentMatcher.findShortVideo(deep, rules.shortVideoRulesFor("com.google.android.youtube")))
    }

    @Test
    fun urlBarIsReadUnlessFocused() {
        val chrome = rules.browserFor("com.android.chrome")!!
        val bar = N(viewId = "com.android.chrome:id/url_bar", text = "instagram.com/reels")
        assertEquals("instagram.com/reels", ContentMatcher.urlBarText(feed(bar), chrome))
        assertNull(ContentMatcher.urlBarText(feed(bar.copy(isFocused = true)), chrome))
    }

    @Test
    fun hostExtraction() {
        assertEquals("m.instagram.com", DomainMatcher.host("https://m.instagram.com/reel/abc?x=1"))
        assertEquals("youtube.com", DomainMatcher.host("www.youtube.com/watch?v=1"))
        assertEquals("x.com", DomainMatcher.host("X.com"))
        assertNull(DomainMatcher.host("how to stop scrolling"))
        assertNull(DomainMatcher.host(""))
    }

    @Test
    fun domainsMapToAppsIncludingSubdomains() {
        assertEquals(listOf("com.google.android.youtube"), DomainMatcher.packagesFor("m.youtube.com/shorts/1", rules.domains))
        // The more specific domain wins.
        assertEquals(listOf("com.google.android.apps.youtube.music"), DomainMatcher.packagesFor("music.youtube.com", rules.domains))
        assertTrue(DomainMatcher.packagesFor("notinstagram.com", rules.domains).isEmpty())
        assertNotNull(DomainMatcher.packagesFor("https://www.reddit.com/r/android", rules.domains).firstOrNull())
    }
}
