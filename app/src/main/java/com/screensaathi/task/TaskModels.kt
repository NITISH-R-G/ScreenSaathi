package com.screensaathi.task

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
    val instruction: String,
    val expectsValue: Boolean = false,
    val highlight: Highlight = Highlight(),
)

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
