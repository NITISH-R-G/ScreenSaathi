package com.screensaathi.task

import com.screensaathi.sarvam.Language
import com.screensaathi.sarvam.Spoken

/**
 * In-memory form of the Task DSL (contracts/task.schema.json).
 * Plain data classes — no framework, so the step engine stays testable.
 */

data class Highlight(
    val shape: String = "rect",
    val pulse: Boolean = true,
)

/**
 * What a step asks of the user.
 *
 * GUIDE is the M1 behaviour: point at an element and say what to do. CHOOSE_APP
 * exists because the taxi flow has to ask *which* ride app before it can guide
 * inside one, and that question has no on-screen element to point at.
 */
enum class StepKind { GUIDE, CHOOSE_APP }

data class TaskStep(
    val id: String,
    val resourceId: String,
    /** Base wording, authored in [Language.DEFAULT]. Always present. */
    val instruction: String,
    /** Optional per-language wording, keyed by full code ("hi-IN"). */
    val instructions: Map<String, String> = emptyMap(),
    val expectsValue: Boolean = false,
    val highlight: Highlight = Highlight(),
    val kind: StepKind = StepKind.GUIDE,
    /**
     * Visible text to match on when [resourceId] cannot be used.
     *
     * Our own demo screen has stable ids; Uber, Ola and Rapido do not — their
     * view ids are obfuscated and change between releases. Matching the label
     * the user can actually see ("Where to?") is the only thing that survives,
     * so a third-party step targets text and leaves resourceId empty.
     */
    val textAny: List<String> = emptyList(),
    /**
     * Package this step expects to be looking at. Guidance waits for it, so the
     * ring never lands on our own launcher screen while the ride app is still
     * starting up.
     */
    val expectPackage: String = "",
    val actionType: String = "guide",
    val actionPayload: String = "",
    /**
     * This step commits something the user cannot take back — paying, sending,
     * confirming.
     *
     * Guarded by [com.screensaathi.session.SafetyGuard]: the planner may not
     * jump here while earlier steps are still blank. Without it, "how do I
     * start" on an empty form could put the ring on Pay Bill.
     */
    val irreversible: Boolean = false,
) {
    /**
     * The step's wording in [language] if the DSL carries it, otherwise the
     * base English text labelled as English.
     *
     * Returning a [Spoken] rather than a bare String is the point: the offline
     * path used to hand English DSL text to Bulbul tagged with the user's
     * detected language, which Bulbul rejects outright. The caller can no
     * longer lose track of which language the words are in.
     */
    fun spokenFor(language: String): Spoken {
        val code = Language.normalize(language)
        instructions[code]?.let { return Spoken(it, code) }
        return Spoken(instruction, Language.DEFAULT)
    }
}

data class GuidedTask(
    val version: Int,
    val id: String,
    val title: String,
    val utterances: List<String>,
    val steps: List<TaskStep>,
) {
    fun indexOfStep(stepId: String): Int = steps.indexOfFirst { it.id == stepId }

    fun stepById(stepId: String): TaskStep? = steps.firstOrNull { it.id == stepId }
}
