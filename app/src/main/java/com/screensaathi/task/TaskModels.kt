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

data class TaskStep(
    val id: String,
    val resourceId: String,
    /** Base wording, authored in [Language.DEFAULT]. Always present. */
    val instruction: String,
    /** Optional per-language wording, keyed by full code ("hi-IN"). */
    val instructions: Map<String, String> = emptyMap(),
    val expectsValue: Boolean = false,
    val highlight: Highlight = Highlight(),
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
