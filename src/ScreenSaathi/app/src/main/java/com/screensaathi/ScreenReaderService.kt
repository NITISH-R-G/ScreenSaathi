package com.screensaathi

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot

/**
 * Read-only screen context. Walks the live accessibility tree of the foreground
 * app into a flat indexed snapshot (contracts/accessibility.schema.json),
 * filtering out our own overlay package. No gestures, no screenshots — point
 * and speak only.
 *
 * `lastEventUptime` gives a debounced "screen settled" signal so the highlight
 * never lands mid-transition.
 */
class ScreenReaderService : AccessibilityService() {

    @Volatile
    private var lastEventUptime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> lastEventUptime = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** True when no UI change events have arrived for [quietMs]. */
    private fun isSettled(quietMs: Long = 250L): Boolean =
        SystemClock.uptimeMillis() - lastEventUptime >= quietMs

    /**
     * One snapshot of the current screen. Own-package nodes are skipped so the
     * assistant never observes its own overlay.
     */
    fun snapshot(): ScreenSnapshot {
        val root = rootInActiveWindow ?: return ScreenSnapshot.EMPTY
        val pkg = root.packageName?.toString() ?: ""
        val elements = ArrayList<ScreenElement>()
        val counter = intArrayOf(0)
        walk(root, elements, counter)
        return ScreenSnapshot(pkg, isSettled(), elements)
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        out: ArrayList<ScreenElement>,
        counter: IntArray,
    ) {
        if (node == null || counter[0] >= MAX_ELEMENTS) return

        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        // Skip our own overlay chrome. Filtering by package name would be wrong
        // here: the guided demo screen lives in this same package, so a
        // package-level filter throws away the very screen we must read.
        if (rid in OVERLAY_IDS) return
        val text = (node.text ?: node.contentDescription)?.toString() ?: ""
        val hasSignal = rid.isNotEmpty() || text.isNotEmpty() ||
            node.isClickable || node.isEditable

        if (hasSignal) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() > MIN_PX && b.height() > MIN_PX) {
                out.add(
                    ScreenElement(
                        index = counter[0]++,
                        resourceId = rid,
                        text = text,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        bounds = b,
                        editable = node.isEditable,
                        clickable = node.isClickable,
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, counter)
        }
    }

    /**
     * Finds a node by resource id or text and performs a click action.
     */
    fun performClick(resourceId: String, textAny: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, resourceId, textAny, true) ?: return false
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return success
    }

    fun performSetText(resourceId: String, textAny: List<String>, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, resourceId, textAny, false) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return success
    }

    private fun findNode(node: AccessibilityNodeInfo?, resourceId: String, textAny: List<String>, requiresClickable: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        
        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (rid in OVERLAY_IDS) return null
        
        val validAction = if (requiresClickable) node.isClickable else (node.isClickable || node.isEditable)
        if (resourceId.isNotEmpty() && rid == resourceId && validAction) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        val text = (node.text ?: node.contentDescription)?.toString() ?: ""
        if (textAny.isNotEmpty() && textAny.any { it.equals(text, ignoreCase = true) } && validAction) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // Search children
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), resourceId, textAny, requiresClickable)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val MAX_ELEMENTS = 120
        private const val MIN_PX = 4

        /** View ids belonging to the floating overlay itself — never guidance targets. */
        private val OVERLAY_IDS = setOf(
            "pill_root", "pill_row", "card_body", "state_dot", "pill_label",
            "instruction_text", "mic_button", "next_button", "debug_panel",
        )

        @Volatile
        var instance: ScreenReaderService? = null
            private set
    }
}
