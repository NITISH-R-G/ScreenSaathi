package com.screensaathi.circle

/**
 * What the user circled, and everything since.
 *
 * This is what makes the second turn work. "What is this?" followed by "okay,
 * help me use it" has to resolve "it" without making the user draw the circle
 * again — so the selection, the resolved target and the conversation live here
 * for the length of the interaction rather than in the view that drew them.
 *
 * Immutable; each turn produces a new instance. The alternative — mutable
 * state read from the UI thread and written from the capture and network
 * threads — is exactly the shape that produced stale-render bugs in the voice
 * path (see docs/DECISIONS.md on turn/generation invalidation).
 */
data class CircleContext(
    val selection: ScreenSelection,
    val target: SelectionResolver.SelectedTarget,
    /**
     * Pixels for the selection, when capture succeeded. Retained so a vision
     * provider can be added later without re-capturing — by then the screen
     * will have moved on.
     */
    val frame: ScreenFrame?,
    /** Everything asked and answered about this selection, oldest first. */
    val turns: List<Turn> = emptyList(),
    /** Language the interaction is being conducted in, e.g. "hi-IN". */
    val languageCode: String,
    /** Set once an agentic request hands off to the session's task loop. */
    val activeTaskId: String? = null,
) {
    data class Turn(
        val request: String,
        val intent: CircleIntent,
        val response: String,
        val atMs: Long,
    )

    val packageName: String get() = selection.packageName

    /** Did the accessibility tree explain the selection? */
    val hasAccessibilityTarget: Boolean get() = target.isResolved

    /**
     * A selection with pixels but nothing the tree can name — a photo, an
     * icon, a product image. This is the case that needs a vision provider,
     * and the case where the assistant must say so instead of guessing.
     */
    val needsVision: Boolean
        get() = !target.isResolved && target.selectedText.isBlank()

    val isAgentActive: Boolean get() = activeTaskId != null

    fun withTurn(request: String, intent: CircleIntent, response: String, atMs: Long) =
        copy(turns = turns + Turn(request, intent, response, atMs))

    fun withTask(taskId: String?) = copy(activeTaskId = taskId)

    /**
     * Is this context still about the screen the user is looking at?
     *
     * After navigating away, a selection describes a screen that is no longer
     * there — continuing to answer about it would be quietly wrong. The caller
     * decides whether to drop it or re-anchor.
     */
    fun matchesScreen(packageName: String, signature: String): Boolean =
        selection.packageName == packageName && selection.screenSignature == signature

    /**
     * The selection rendered for a text model.
     *
     * Names the element when the tree resolved one, falls back to the raw text
     * the selection covered, and is explicit when neither exists — the model
     * must not be handed an empty string that reads as "nothing was selected"
     * when the truth is "something was selected that we cannot describe".
     */
    fun toPromptText(): String = buildString {
        append("The user selected a region of ").append(packageName.ifEmpty { "the current app" }).append(".\n")

        val element = target.element
        if (element != null) {
            append("Selected element: ")
            if (element.text.isNotBlank()) append("\"").append(element.text.trim()).append("\" ")
            append("(").append(element.className.substringAfterLast('.'))
            if (element.resourceId.isNotBlank()) append(", id=").append(element.resourceId)
            append(")")
            if (target.possibleActions.isNotEmpty()) {
                append(" — can be ")
                append(
                    target.possibleActions.joinToString(" / ") {
                        when (it) {
                            SelectionResolver.Action.TAP -> "tapped"
                            SelectionResolver.Action.TYPE -> "typed into"
                            SelectionResolver.Action.READ -> "read"
                        }
                    }
                )
            }
            append("\n")
        } else if (target.selectedText.isNotBlank()) {
            append("Selected text: \"").append(target.selectedText).append("\"\n")
        } else {
            append("The selection contains no readable UI elements — it is a purely visual region.\n")
        }

        if (target.surroundingContext.isNotBlank()) {
            append("Nearby on screen: ").append(target.surroundingContext).append("\n")
        }

        if (turns.isNotEmpty()) {
            append("Conversation so far:\n")
            for (t in turns) {
                append("  user: ").append(t.request).append("\n")
                if (t.response.isNotBlank()) append("  assistant: ").append(t.response).append("\n")
            }
        }
    }
}
