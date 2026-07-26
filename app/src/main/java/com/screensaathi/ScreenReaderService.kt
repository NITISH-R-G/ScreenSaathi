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
