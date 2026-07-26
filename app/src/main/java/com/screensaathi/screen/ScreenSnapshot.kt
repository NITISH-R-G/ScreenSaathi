package com.screensaathi.screen

import android.graphics.Rect

/**
 * In-memory form of the accessibility snapshot (contracts/accessibility.schema.json).
 * A flat, own-package-filtered list of on-screen elements plus a settled flag.
 */

data class ScreenElement(
    val index: Int,
    val resourceId: String,
    val text: String,
    val className: String,
    val bounds: Rect,
    val editable: Boolean,
    val clickable: Boolean,
)

data class ScreenSnapshot(
    val packageName: String,
    val settled: Boolean,
    val elements: List<ScreenElement>,
) {
    /** Live bounds for a step's resource_id, or null if not currently on screen. */
    fun boundsForResourceId(resourceId: String): Rect? =
        elements.firstOrNull { it.resourceId == resourceId }?.bounds

    fun elementForResourceId(resourceId: String): ScreenElement? =
        elements.firstOrNull { it.resourceId == resourceId }

    /** Compact text form fed to the planner prompt. */
    fun toPromptText(): String {
        if (elements.isEmpty()) return "Screen: $packageName (no readable elements)"
        val sb = StringBuilder("Screen: $packageName\nElements:\n")
        for (e in elements) {
            sb.append("[").append(e.index).append("] ")
                .append(e.className)
            if (e.resourceId.isNotEmpty()) sb.append(" id=").append(e.resourceId)
            if (e.text.isNotEmpty()) sb.append(" \"").append(e.text).append("\"")
            val flags = buildString {
                if (e.editable) append("E")
                if (e.clickable) append("C")
            }
            if (flags.isNotEmpty()) sb.append(" ").append(flags)
            sb.append("\n")
        }
        return sb.toString()
    }

    companion object {
        val EMPTY = ScreenSnapshot("", settled = false, elements = emptyList())
    }
}
