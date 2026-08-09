package com.screensaathi

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.session.SessionController

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
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> lastEventUptime = SystemClock.uptimeMillis()

            // The one signal a screen transition is guaranteed to send. A tap
            // can visibly change the app without ever firing TYPE_VIEW_CLICKED
            // (measured on Uber: zero click events, on a clickable button as
            // much as a bare text row) — so this, not the click, is what tells
            // a stale highlight to drop.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastEventUptime = SystemClock.uptimeMillis()
                SessionController.instance?.onWindowStateChanged()
                OverlayService.onSystemWindowsChanged()
            }

            // The keyboard opening or closing arrives here, not as an inset on
            // our own non-focusable window. This is what lets the assistant get
            // out of the IME's way.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                OverlayService.onSystemWindowsChanged()
            }

            // The user physically tapped something. That — not a timer, and not
            // an action taken on their behalf — is what advances a guided step.
            //
            // Our own overlay pill is a real Android View, so its own button
            // taps (mic, next, stop) ALSO fire a genuine TYPE_VIEW_CLICKED —
            // system-wide, this service sees clicks in any window, including
            // its own. That click has already been handled directly by the
            // button's own onClickListener; routing it through onUserClicked()
            // as well fires onNextTapped(), whose first line is
            // abandonRecording() — killing a recording within milliseconds of
            // the very mic tap that started it, if a guided task happened to
            // be mid-flight. Measured on device: mic recording observed
            // starting and stopping 237ms later with no user action in
            // between. Ignoring self-originated events is the fix: a tap
            // inside a third-party app is the only thing this signal should
            // ever mean.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                lastEventUptime = SystemClock.uptimeMillis()
                if (event.packageName != packageName) {
                    SessionController.instance?.onUserClicked()
                }
            }
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

    /**
     * Top edge of the on-screen keyboard in screen pixels, or 0 when no IME is
     * showing.
     *
     * Read from the accessibility window list rather than from WindowInsets:
     * the assistant overlay is FLAG_NOT_FOCUSABLE, so the IME is attached to
     * the *app's* window and never reports an ime() inset to ours — asking our
     * own window would always answer "no keyboard". The accessibility service
     * sees every window on the display regardless of who has focus, which is
     * the only vantage point that can answer this for an overlay.
     */
    fun imeTopPx(): Int = try {
        windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .minOfOrNull { w -> Rect().also { w.getBoundsInScreen(it) }.top }
            ?: 0
    } catch (e: Exception) {
        // The window list throws while the display is changing.
        0
    }

    /** True when no UI change events have arrived for [quietMs]. */
    private fun isSettled(quietMs: Long = 250L): Boolean =
        SystemClock.uptimeMillis() - lastEventUptime >= quietMs

    /**
     * One snapshot of the current screen. Own-package nodes are skipped so the
     * assistant never observes its own overlay.
     */
    /**
     * The node tree to read.
     *
     * `rootInActiveWindow` alone is not enough: it returns null whenever the
     * focused window is not the one the user is looking at — which is routine
     * while an app is settling, when a bottom sheet or dialog owns focus, or
     * when our own overlay is in play. Measured on Swiggy: snapshot() returned
     * elements=0 for that reason, and the resolver was blamed for a screen it
     * had never been given.
     *
     * Falls back to the window list, preferring real application windows over
     * system ones and topmost over lower layers.
     */
    private fun resolveRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return try {
            windows
                .filter {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                }
                .sortedWith(
                    compareBy<android.view.accessibility.AccessibilityWindowInfo> {
                        if (it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1
                    }.thenByDescending { it.layer }
                )
                .firstNotNullOfOrNull { it.root }
        } catch (e: Exception) {
            // The window list can throw while the screen is changing.
            null
        }
    }


    fun snapshot(): ScreenSnapshot {
        val root = resolveRoot() ?: return ScreenSnapshot.EMPTY
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
        val rawText = node.text?.toString()?.trim().orEmpty()
        val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
        // An empty field is labelled ONLY by its hint ("Search for 'Sweets'",
        // "Where to?"). Without this every blank input on the phone is
        // invisible to the resolver — which is exactly the element the user is
        // most likely to be asking about.
        val rawHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val text = when {
            rawText.isNotEmpty() -> rawText
            rawDesc.isNotEmpty() -> rawDesc
            else -> rawHint
        }
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
        /**
         * Real app screens are far bigger than the old 120 cap suggested:
         * Swiggy's home screen alone reports 304 nodes, and its search bar sits
         * at #280 — so the depth-first walk truncated before ever reaching the
         * one control the user was asking for, and the resolver was blamed for
         * a target it had never been shown.
         *
         * Kept bounded (a runaway tree would stall the poll loop), but high
         * enough to cover ordinary consumer apps.
         */
        private const val MAX_ELEMENTS = 600
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
