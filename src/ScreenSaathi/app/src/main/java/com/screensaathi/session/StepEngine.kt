package com.screensaathi.session

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep

/**
 * Deterministic driver over a task's steps. This is the reliable core — the
 * planner may reorder or jump, but if it is unavailable this alone completes the
 * task in order. Priority #1 is reliability, so element location and step order
 * never depend on the network.
 */
class StepEngine(val task: GuidedTask) {

    var currentIndex: Int = 0
        private set

    val currentStep: TaskStep
        get() = task.steps[currentIndex]

    val isOnLastStep: Boolean
        get() = currentIndex >= task.steps.lastIndex

    fun advance(): Boolean {
        if (isOnLastStep) return false
        currentIndex++
        return true
    }

    /** Jump to a step by id (used when the planner resolves a different step). */
    fun jumpTo(stepId: String): Boolean {
        val idx = task.indexOfStep(stepId)
        if (idx < 0) return false
        currentIndex = idx
        return true
    }

    fun reset() {
        currentIndex = 0
    }
}
