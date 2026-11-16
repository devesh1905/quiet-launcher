package app.quiet.launcher.feature.focus

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.quiet.launcher.container
import app.quiet.launcher.feature.focus.content.ContentMatcher
import app.quiet.launcher.feature.focus.content.DomainMatcher
import app.quiet.launcher.feature.focus.content.NodeView
import app.quiet.launcher.feature.focus.ui.FocusCopy

/**
 * Precision mode. Optional; everything works without it through usage-event polling.
 *
 * - Reports the app in front from TYPE_WINDOW_STATE_CHANGED (exact and immediate).
 * - Short-video blocking: when a rule from focus_rules.json matches the window tree of
 *   YouTube, Instagram, Facebook or Snapchat, presses Back and shows a toast.
 * - Website blocking: reads the URL bar of supported browsers; when the domain belongs to an
 *   app that is blocked right now, presses Back and shows the blocked screen for that app.
 * - Locks the screen for double-tap to lock.
 *
 * Nothing it observes is stored or leaves the device. Runs on the main thread, like the engine.
 */
class QuietAccessibilityService : AccessibilityService() {

    private val activityCache = HashMap<String, Boolean>()
    private var lastScanAt = 0L
    private var lastScanPkg: String? = null
    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tracker()?.setAccessibilityConnected(true)
        FocusRuntime.get(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A payment app opened some other way (notification, QR intent): step aside at once.
                if (pkg != packageName && PaymentGuard.isPayment(this, pkg) && PaymentGuard.pause(this)) return
                val isActivity = isActivity(pkg, e.className?.toString())
                tracker()?.onAccessibilityWindow(pkg, isActivity)
                scan(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> scan(pkg, force = false)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    private fun disconnect() {
        if (instance === this) instance = null
        tracker()?.setAccessibilityConnected(false)
    }

    /** GLOBAL_ACTION_LOCK_SCREEN, API 28+. */
    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    private fun tracker(): ForegroundTrackerImpl? = applicationContext.container.foreground as? ForegroundTrackerImpl

    /** Window-state events also fire for dialogs and popups; only activities replace the app in front. */
    private fun isActivity(pkg: String, cls: String?): Boolean {
        if (cls == null) return false
        val key = "$pkg/$cls"
        return activityCache.getOrPut(key) {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun scan(pkg: String, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && pkg == lastScanPkg && now - lastScanAt < SCAN_INTERVAL_MS) return
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return
        lastScanAt = now
        lastScanPkg = pkg
        val rt = FocusRuntime.get(this)
        if (!rt.ready.value) return
        val rules = rt.contentRules
        val snap = rt.snapshot

        rules.browserFor(pkg)?.let { browser ->
            val url = urlBarText(browser.urlBarIds) ?: return@let
            val target = DomainMatcher.packagesFor(url, rules.domains)
                .firstNotNullOfOrNull { app -> rt.engine.blockFor(app, snap)?.let { app to it } }
            if (target != null) {
                lastActionAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                rt.showBlocked(target.first, target.second)
            }
            return
        }

        if (!snap.shortVideo.enabled) return
        val candidates = rules.shortVideoRulesFor(pkg).filter { it.id !in snap.shortVideo.disabledRules }
        if (candidates.isEmpty()) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val hit = ContentMatcher.findShortVideo(NodeAdapter(root), candidates) ?: return
        lastActionAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        rt.toast(FocusCopy.shortVideoToast(hit.label))
    }

    private fun urlBarText(ids: List<String>): String? {
        val root = rootInActiveWindow ?: return null
        for (id in ids) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) {
                null
            } ?: continue
            val bar = nodes.firstOrNull() ?: continue
            // While the user is typing, the bar holds a draft, not the page.
            if (bar.isFocused) return null
            return bar.text?.toString()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** [NodeView] over the live accessibility tree. */
    private class NodeAdapter(private val node: AccessibilityNodeInfo) : NodeView {
        override val viewId: String? get() = node.viewIdResourceName
        override val className: String? get() = node.className?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val text: String? get() = node.text?.toString()
        override val isSelected: Boolean get() = node.isSelected
        override val isFocused: Boolean get() = node.isFocused
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): NodeView? = try {
            node.getChild(index)?.let(::NodeAdapter)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 450L
        private const val ACTION_COOLDOWN_MS = 1_500L

        /** The connected service, or null when precision mode is off. */
        @Volatile var instance: QuietAccessibilityService? = null
            private set
    }
}
