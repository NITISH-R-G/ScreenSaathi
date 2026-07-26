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

    /**
     * Bounds for a step that targets visible text rather than a view id.
     *
     * Third-party apps obfuscate their view ids, so the only stable handle on
     * an Uber or Ola field is the label the user can see. Matching is
     * case-insensitive and substring-based because these labels carry
     * punctuation and trailing hints ("Where to?", "Enter drop location").
     *
     * Prefers an interactive element over a plain label: tapping "Where to?"
     * means tapping the field, and the same words often appear on both a
     * TextView and the EditText behind it.
     */
    fun boundsForText(candidates: List<String>): Rect? {
        if (candidates.isEmpty()) return null
        val matches = elements.filter { e ->
            e.text.isNotEmpty() && candidates.any { e.text.contains(it, ignoreCase = true) }
        }
        if (matches.isEmpty()) return null
        return (matches.firstOrNull { it.editable || it.clickable } ?: matches.first()).bounds
    }

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
