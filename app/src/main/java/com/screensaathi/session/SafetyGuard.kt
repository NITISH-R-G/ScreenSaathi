package com.screensaathi.session

import com.screensaathi.task.GuidedTask

/**
 * Two deterministic refusals that sit between the planner and the user.
 *
 * Both exist because the eval suite caught the planner doing something a
 * confidence threshold alone cannot prevent (docs/evals/FAILURES.md GAP-1,
 * GAP-2). Deliberately pure — no Android types, no model call — so production
 * and the eval harness run the identical logic, and so both are unit-testable.
 */
object SafetyGuard {

    /**
     * Refuses a jump to an irreversible step while an earlier step is still
     * blank.
     *
     * The screen itself is the evidence: a step counts as done when the element
     * it targets currently holds text. So "skip to the payment" on a filled
     * form is allowed, and the same words on an empty form are not.
     *
     * @param textOf current on-screen text for a resource id, or null when the
     *   element is not present.
     * @return true when the jump must be blocked.
     */
    fun blocksIrreversibleJump(
        task: GuidedTask,
        targetStepId: String,
        textOf: (String) -> String?,
    ): Boolean {
        val target = task.stepById(targetStepId) ?: return false
        if (!target.irreversible) return false

        val targetIndex = task.indexOfStep(targetStepId)
        if (targetIndex <= 0) return false

        // Every preceding step that points at a real field must be filled in.
        return task.steps.take(targetIndex).any { prior ->
            prior.resourceId.isNotEmpty() && textOf(prior.resourceId).isNullOrBlank()
        }
    }

    /**
     * Refuses a plan the current screen cannot support.
     *
     * The planner reports its own confidence, and it will happily report 0.95
     * about a screen with nothing readable on it. Self-reported confidence is
     * not evidence; the snapshot is. If there is nothing to point at, saying so
     * beats confidently pointing at nothing.
     *
     * @return true when the plan must not be acted on.
     */
    fun blocksUngroundedPlan(
        elementCount: Int,
        targetResolves: Boolean,
    ): Boolean = elementCount == 0 || !targetResolves
}
