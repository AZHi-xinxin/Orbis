package me.rerere.rikkahub.data.orbis.agentwork

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

/** On-demand UI access only. No event text is collected and no actions run on service connection. */
class PhoneControlService : AccessibilityService() {
    companion object { @Volatile internal var instance: PhoneControlService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun unlocked() { check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "phone_locked" } }
    @Suppress("DEPRECATION")
    internal fun page(allowed: Set<String>): PhonePage {
        unlocked()
        val root = rootInActiveWindow ?: error("phone_screen_unavailable")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            check(pkg in allowed && PhoneControlPolicy.allowedPackage(pkg, packageName)) { "phone_app_not_allowed" }
            val elements = mutableListOf<PhoneElement>()
            var visited = 0
            var truncated = false
            fun visit(node: AccessibilityNodeInfo, path: List<Int>) {
                if (visited >= 2048) { truncated = true; return }
                visited++
                if (path.size > 64) { truncated = true; return }
                // Do not expose password nodes or their descendants, even as actions/coordinates.
                if (node.isPassword || !node.isVisibleToUser) return
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                elements += PhoneElement(elements.size, path, node.text?.toString().orEmpty().take(500),
                    node.contentDescription?.toString().orEmpty().take(300), node.viewIdResourceName.orEmpty().take(200),
                    bounds.flattenToString(), node.isClickable, node.isEditable, node.isScrollable,
                    node.isEnabled, node.isChecked, node.isSelected)
                val children = node.childCount
                for (i in 0 until children) {
                    if (visited >= 2048) { truncated = true; break }
                    node.getChild(i)?.let { child -> try { visit(child, path + i) } finally { child.recycle() } }
                }
            }
            visit(root, emptyList())
            return PhonePageProjection.project(PhonePage(UUID.randomUUID().toString(), pkg,
                android.os.SystemClock.elapsedRealtime(), elements, truncated))
        } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    internal fun act(page: PhonePage, token: String, allowed: Set<String>, action: String, id: Int, text: String): Boolean {
        unlocked()
        val current = page(allowed)
        PhoneControlPolicy.validate(page, token, current, allowed, android.os.SystemClock.elapsedRealtime())
        if (action == "back") return performGlobalAction(GLOBAL_ACTION_BACK)
        val element = page.elements.firstOrNull { it.id == id } ?: error("phone_element_missing")
        var node = rootInActiveWindow ?: error("phone_screen_unavailable")
        try {
            check(node.packageName?.toString() == page.packageName) { "phone_page_changed_read_again" }
            for (index in element.path) {
                val child = node.getChild(index) ?: error("phone_page_changed_read_again")
                node.recycle(); node = child
            }
            check(!node.isPassword && node.isVisibleToUser && node.isEnabled) { "phone_element_unavailable" }
            // Recheck the target immediately before dispatch, in addition to the full-tree lease.
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            check(node.text?.toString().orEmpty().take(500) == element.text &&
                node.viewIdResourceName.orEmpty().take(200) == element.viewId && bounds.flattenToString() == element.bounds) {
                "phone_page_changed_read_again"
            }
            return when (action) {
                "click" -> { check(node.isClickable); node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                "input" -> { check(node.isEditable && text.length <= 2000)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }) }
                "scroll_forward" -> { check(node.isScrollable); node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                "scroll_backward" -> { check(node.isScrollable); node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                else -> error("phone_action_invalid")
            }
        } finally { node.recycle() }
    }
}
