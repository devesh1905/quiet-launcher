package app.quiet.launcher.feature.focus.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data-driven detection rules for precision mode, loaded from `assets/focus_rules.json` so view
 * ids can be updated when apps change without touching code. Matching works on [NodeView], an
 * Android-free view of the accessibility tree, so it is tested on recorded fixtures.
 */
@Serializable
data class ContentRules(
    val version: Int = 1,
    val shortVideo: List<ShortVideoRule> = emptyList(),
    val browsers: List<BrowserRule> = emptyList(),
    /** Domain (no scheme, no www) -> app packages it stands for. */
    val domains: Map<String, List<String>> = emptyMap(),
) {
    fun shortVideoRulesFor(pkg: String): List<ShortVideoRule> = shortVideo.filter { pkg in it.packages }
    fun browserFor(pkg: String): BrowserRule? = browsers.firstOrNull { it.pkg == pkg }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): ContentRules = json.decodeFromString(serializer(), text)
    }
}

/**
 * One short-video surface. It matches when any node has one of [viewIds] (short id, without the
 * "pkg:id/" prefix), or a class name in [classNames], or a content description in
 * [descriptions] (case-insensitive equality), or a *selected* node whose description or text
 * is in [selectedDescriptions] (a selected bottom tab such as "Reels").
 */
@Serializable
data class ShortVideoRule(
    val id: String,
    val label: String,
    val packages: List<String>,
    val viewIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val selectedDescriptions: List<String> = emptyList(),
)

@Serializable
data class BrowserRule(
    val pkg: String,
    val label: String = "",
    /** Full view ids, e.g. "com.android.chrome:id/url_bar". */
    val urlBarIds: List<String>,
)

/** Minimal read-only view of an accessibility node. */
interface NodeView {
    val viewId: String?
    val className: String?
    val contentDescription: String?
    val text: String?
    val isSelected: Boolean
    val isFocused: Boolean
    val childCount: Int
    fun child(index: Int): NodeView?
}

object ContentMatcher {
    /** Hard cap on visited nodes per scan; feeds are deep, and scans run on content changes. */
    const val MAX_NODES = 700

    /** First matching rule in [rules] for the tree under [root], or null. */
    fun findShortVideo(root: NodeView, rules: List<ShortVideoRule>): ShortVideoRule? {
        if (rules.isEmpty()) return null
        var found: ShortVideoRule? = null
        walk(root) { node ->
            for (r in rules) {
                if (matches(node, r)) {
                    found = r
                    return@walk false
                }
            }
            true
        }
        return found
    }

    private fun matches(node: NodeView, r: ShortVideoRule): Boolean {
        val id = node.viewId?.substringAfter(":id/", node.viewId ?: "")
        if (id != null && id in r.viewIds) return true
        val cls = node.className
        if (cls != null && r.classNames.any { it == cls }) return true
        val desc = node.contentDescription?.trim()
        if (desc != null && r.descriptions.any { it.equals(desc, ignoreCase = true) }) return true
        if (node.isSelected && r.selectedDescriptions.isNotEmpty()) {
            val label = desc ?: node.text?.trim()
            if (label != null && r.selectedDescriptions.any { it.equals(label, ignoreCase = true) }) return true
        }
        return false
    }

    /** Depth-first walk, stopping when [visit] returns false or after [MAX_NODES]. */
    fun walk(root: NodeView, visit: (NodeView) -> Boolean) {
        val stack = ArrayDeque<NodeView>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val n = stack.removeLast()
            seen++
            if (!visit(n)) return
            for (i in n.childCount - 1 downTo 0) n.child(i)?.let { stack.addLast(it) }
        }
    }

    /** Text of the browser's URL bar, unless the user is typing in it. */
    fun urlBarText(root: NodeView, browser: BrowserRule): String? {
        var out: String? = null
        walk(root) { n ->
            if (n.viewId != null && n.viewId in browser.urlBarIds) {
                if (!n.isFocused) out = n.text
                return@walk false
            }
            true
        }
        return out?.takeIf { it.isNotBlank() }
    }
}

object DomainMatcher {
    /** "https://m.instagram.com/reel/x" -> "m.instagram.com". Accepts bare hosts as shown by Chrome. */
    fun host(url: String): String? {
        var s = url.trim().lowercase()
        if (s.isEmpty() || s.contains(' ')) return null
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("www.")
        return s.takeIf { it.contains('.') }
    }

    /** Packages for the host, matching the domain itself or any subdomain of it. */
    fun packagesFor(url: String, domains: Map<String, List<String>>): List<String> {
        val h = host(url) ?: return emptyList()
        var best: String? = null
        for (d in domains.keys) {
            if (h == d || h.endsWith(".$d")) {
                if (best == null || d.length > best.length) best = d
            }
        }
        return best?.let { domains[it] } ?: emptyList()
    }
}
