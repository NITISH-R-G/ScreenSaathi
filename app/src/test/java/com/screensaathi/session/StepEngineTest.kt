package com.screensaathi.session

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StepEngine is the deterministic core — the thing that still completes the task
 * when the network, the key, or the planner is gone. It is the one component
 * that must never regress, so it gets the closest tests in the repo.
 */
class StepEngineTest {

    private fun task(vararg ids: String) = GuidedTask(
        version = 1,
        id = "pay_bill",
        title = "Pay Electricity Bill",
        utterances = emptyList(),
        steps = ids.map { TaskStep(id = it, resourceId = "${it}_field", instruction = "do $it") },
    )

    @Test
    fun `starts on the first step`() {
        val e = StepEngine(task("amount", "account", "submit"))
        assertEquals(0, e.currentIndex)
        assertEquals("amount", e.currentStep.id)
        assertFalse(e.isOnLastStep)
    }

    @Test
    fun `advance walks the steps in order`() {
        val e = StepEngine(task("amount", "account", "submit"))
        assertTrue(e.advance())
        assertEquals("account", e.currentStep.id)
        assertTrue(e.advance())
        assertEquals("submit", e.currentStep.id)
    }

    @Test
    fun `advance stops at the last step instead of running off the end`() {
        val e = StepEngine(task("amount", "submit"))
        e.advance()
        assertTrue(e.isOnLastStep)
        assertFalse("advance past the end must be a no-op, not a crash", e.advance())
        assertEquals("submit", e.currentStep.id)
    }

    @Test
    fun `a single-step task is immediately on its last step`() {
        val e = StepEngine(task("submit"))
        assertTrue(e.isOnLastStep)
        assertFalse(e.advance())
    }

    @Test
    fun `jumpTo moves to a real step`() {
        val e = StepEngine(task("amount", "account", "submit"))
        assertTrue(e.jumpTo("submit"))
        assertEquals(2, e.currentIndex)
    }

    @Test
    fun `jumpTo rejects an unknown step and leaves position untouched`() {
        val e = StepEngine(task("amount", "account", "submit"))
        e.advance()
        assertFalse(e.jumpTo("nonexistent"))
        assertEquals("a bad planner step must not move the user", 1, e.currentIndex)
    }

    @Test
    fun `reset returns to the first step`() {
        val e = StepEngine(task("amount", "account", "submit"))
        e.jumpTo("submit")
        e.reset()
        assertEquals(0, e.currentIndex)
    }
}
