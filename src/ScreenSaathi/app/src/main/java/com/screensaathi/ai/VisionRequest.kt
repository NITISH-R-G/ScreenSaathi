package com.screensaathi.ai

import com.screensaathi.circle.ScreenFrame
import com.screensaathi.circle.ScreenSelection
import com.screensaathi.circle.SelectionResolver

/**
 * Everything a multimodal model should be told about a selection.
 *
 * The point of this type is what it refuses to be: a crop and a question. A
 * bare crop throws away the things ScreenSaathi uniquely has — that this
 * rectangle is a *button*, that it is *disabled*, that the heading above it
 * says "Amount due", that the user asked the same thing thirty seconds ago in
 * Tamil, and that an agentic task is already mid-flight.
 *
 * A model given all of that can ground its answer. A model given pixels alone
 * has to guess, and guessing is exactly the failure mode this product cannot
 * afford.
 *
 * Providers are free to ignore fields they cannot use.
 */
data class VisionRequest(
    /** Full-screen pixels. Null when capture failed or was refused. */
    val frame: ScreenFrame?,

    /** Geometry of what the user drew, including the full path for lassos. */
    val selection: ScreenSelection,

    /** What the accessibility tree made of that region. */
    val target: SelectionResolver.SelectedTarget,

    /** The user's own words. */
    val userPrompt: String,

    /** BCP-47 code the answer must come back in. */
    val languageCode: String,

    /** Foreground app, e.g. `com.ubercab`. Grounds "what app am I in". */
    val packageName: String,

    /**
     * Compact text rendering of the current screen, from
     * `ScreenSnapshot.toPromptText()`. Gives the model the surrounding UI, not
     * just the circled fragment.
     */
    val screenDescription: String,

    /**
     * Prior turns against this same selection, oldest first, already rendered
     * as short lines. Carries the "it" in "okay, help me use it".
     */
    val conversation: List<String>,

    /** Task id if an agentic task is already running, else null. */
    val activeTaskId: String?,

    /** What the strategy decided, so a provider can honour the mode. */
    val mode: PerceptionStrategy.Mode,
) {
    val hasPixels: Boolean get() = frame?.hasPixels == true

    /**
     * Whether the tree contributed anything the model can use. False means the
     * model really is working from pixels alone and should be told so.
     */
    val hasAccessibilityContext: Boolean
        get() = target.element != null ||
            target.selectedText.isNotBlank() ||
            target.surroundingContext.isNotBlank()

    /**
     * A text summary of the non-pixel context, for providers whose API takes a
     * single prompt string. Built here so every provider renders it the same
     * way and none of them quietly drops a field.
     */
    fun toGroundingText(): String = buildString {
        appendLine("App: ${packageName.ifBlank { "unknown" }}")
        appendLine("Selection: ${selection.shape}, ${selection.bounds.width}x${selection.bounds.height}px")

        target.element?.let { e ->
            append("Selected element: ${e.className}")
            if (e.text.isNotBlank()) append(" labelled \"${e.text.trim()}\"")
            if (e.resourceId.isNotBlank()) append(" id=${e.resourceId}")
            val flags = buildList {
                if (e.clickable) add("clickable")
                if (e.editable) add("editable")
            }
            if (flags.isNotEmpty()) append(" (${flags.joinToString()})")
            appendLine()
            appendLine("Match confidence: ${target.confidence}/100")
        }

        if (target.selectedText.isNotBlank()) {
            appendLine("Text inside selection: ${target.selectedText}")
        }
        if (target.surroundingContext.isNotBlank()) {
            appendLine("Nearby text: ${target.surroundingContext}")
        }
        if (screenDescription.isNotBlank()) {
            appendLine("Screen:")
            appendLine(screenDescription)
        }
        if (conversation.isNotEmpty()) {
            appendLine("Earlier in this selection:")
            conversation.forEach { appendLine("  $it") }
        }
        activeTaskId?.let { appendLine("Active task: $it") }
        appendLine("Answer in: $languageCode")

        if (!hasAccessibilityContext) {
            appendLine(
                "NOTE: the accessibility tree exposed nothing for this region — " +
                    "the image is the only evidence available."
            )
        }
    }
}
