package com.screensaathi.ai

import com.screensaathi.circle.SelectionResolver

/**
 * Decides how much machinery a selection actually needs.
 *
 * ScreenSaathi's advantage over a pixels-only circle-to-search is that the
 * accessibility tree already knows the label, the role, and whether a thing is
 * interactive. When the tree can answer, sending a screenshot to a
 * vision-language model is worse on every axis that matters:
 *
 *  - **Privacy** — the screen never leaves the device.
 *  - **Cost** — a VLM call is orders of magnitude more expensive than a tree read.
 *  - **Latency** — the tree is already in memory; a VLM is a network round trip.
 *  - **Explainability** — "I matched the button labelled *Book now*" is
 *    checkable. "The model said so" is not.
 *
 * So escalation is a decision, not a default. This object makes that decision
 * from the resolved selection alone, with no Android or network dependency, so
 * it can be reasoned about and unit tested.
 */
object PerceptionStrategy {

    /**
     * Confidence at or above which the accessibility tree is trusted alone.
     *
     * Below this the element was found but the match was weak enough that a
     * second opinion is worth paying for, when one is available.
     */
    const val TRUSTED_CONFIDENCE = 55

    enum class Mode {
        /**
         * The tree is enough. No pixels leave the device, no model is called.
         * This is the expected mode for the common case — circling a labelled
         * control — and keeping it common is the point.
         */
        ACCESSIBILITY_ONLY,

        /**
         * The tree found something but is unsure, or found text without a
         * control. Pixels *and* tree context go to the model together, so it
         * can arbitrate rather than start from nothing.
         */
        HYBRID,

        /**
         * The tree has nothing useful — an image, an icon, a canvas, a photo.
         * Only pixels can answer. If no vision provider is configured this
         * becomes an honest refusal rather than a guess.
         */
        VISION_ONLY,
    }

    /**
     * What was decided and why.
     *
     * [reason] is surfaced in the debug panel and logs. A routing decision
     * nobody can explain after the fact is one nobody can tune.
     */
    data class Decision(
        val mode: Mode,
        val reason: String,
    ) {
        /** True when this decision requires pixels to be sent off-device. */
        val needsPixels: Boolean get() = mode != Mode.ACCESSIBILITY_ONLY
    }

    /**
     * Choose a mode for [target].
     *
     * [visionAvailable] does **not** change the decision — a selection that
     * genuinely needs vision still reports [Mode.VISION_ONLY] when no provider
     * is configured, so the caller can say precisely what is missing. Folding
     * availability in here would turn "I can't see images" into the
     * indistinguishable "I found nothing".
     */
    fun decide(target: SelectionResolver.SelectedTarget): Decision {
        val element = target.element

        // A confidently resolved, labelled control. The overwhelmingly common
        // case, and the one that must stay free.
        if (element != null &&
            element.text.isNotBlank() &&
            target.confidence >= TRUSTED_CONFIDENCE &&
            !target.ambiguous
        ) {
            return Decision(
                Mode.ACCESSIBILITY_ONLY,
                "resolved \"${element.text.trim()}\" at confidence ${target.confidence}",
            )
        }

        // Found something, but weakly, or two candidates tied. Pixels can break
        // the tie — the tree context still goes along so the model is not
        // guessing from a crop alone.
        if (element != null) {
            val why = if (target.ambiguous) "ambiguous match" else "low confidence ${target.confidence}"
            return Decision(Mode.HYBRID, "$why — pixels may disambiguate")
        }

        // No element, but the region carried readable text. That text is real
        // evidence; a VLM would mostly be re-reading what we already have.
        if (target.selectedText.isNotBlank()) {
            return Decision(
                Mode.ACCESSIBILITY_ONLY,
                "no single control, but ${target.selectedText.length} chars of text were readable",
            )
        }

        // Nothing in the tree at all. Pixels are the only remaining signal.
        return Decision(
            Mode.VISION_ONLY,
            "no accessibility semantics in the selected region",
        )
    }

    /**
     * Whether [decision] can actually be carried out right now.
     *
     * Separated from [decide] so the *need* and the *ability* are reported
     * independently: "this needs vision, and vision is unavailable" is a
     * different sentence to the user than "I found nothing".
     */
    fun isSatisfiable(decision: Decision, visionAvailable: Boolean): Boolean =
        when (decision.mode) {
            Mode.ACCESSIBILITY_ONLY -> true
            // Hybrid degrades: the tree half still works without a model.
            Mode.HYBRID -> true
            Mode.VISION_ONLY -> visionAvailable
        }
}
