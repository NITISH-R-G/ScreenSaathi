package com.screensaathi.session

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two refusals that stand between a confident planner and an elderly
 * user's money. Both were found by the adversarial eval suite
 * (docs/evals/FAILURES.md GAP-1, GAP-2); these keep them fixed.
 */
class SafetyGuardTest {

    private val payBill = GuidedTask(
        version = 1, id = "pay_bill", title = "Pay Electricity Bill",
        utterances = emptyList(),
        steps = listOf(
            TaskStep(id = "amount", resourceId = "amount_field", instruction = "amount"),
            TaskStep(id = "account", resourceId = "account_field", instruction = "account"),
            TaskStep(
                id = "submit", resourceId = "submit_button",
                instruction = "pay", irreversible = true,
            ),
        ),
    )

    private fun screen(vararg filled: Pair<String, String>): (String) -> String? {
        val map = filled.toMap()
        return { rid -> map[rid] }
    }

    // --- GAP-1: irreversible jumps -------------------------------------------

    @Test
    fun `blocks payment while earlier fields are still blank`() {
        val blocked = SafetyGuard.blocksIrreversibleJump(
            payBill, "submit",
            screen("amount_field" to "", "account_field" to ""),
        )
        assertTrue("an empty form must never route to Pay Bill", blocked)
    }

    @Test
    fun `blocks payment when only some earlier fields are filled`() {
        val blocked = SafetyGuard.blocksIrreversibleJump(
            payBill, "submit",
            screen("amount_field" to "450", "account_field" to ""),
        )
        assertTrue(blocked)
    }

    @Test
    fun `allows payment once the form is complete`() {
        val blocked = SafetyGuard.blocksIrreversibleJump(
            payBill, "submit",
            screen("amount_field" to "450", "account_field" to "100234567"),
        )
        assertFalse("a filled form is exactly when Pay Bill is correct", blocked)
    }

    @Test
    fun `never blocks an ordinary step`() {
        val blocked = SafetyGuard.blocksIrreversibleJump(
            payBill, "account",
            screen("amount_field" to "", "account_field" to ""),
        )
        assertFalse("only irreversible steps are guarded", blocked)
    }

    @Test
    fun `a missing element counts as unfilled rather than as permission`() {
        // The field is not on screen at all. Treating "can't see it" as "it's
        // fine" is how a guard silently stops guarding.
        val blocked = SafetyGuard.blocksIrreversibleJump(payBill, "submit") { null }
        assertTrue(blocked)
    }

    // --- GAP-2: ungrounded plans ---------------------------------------------

    @Test
    fun `blocks any plan when the screen has nothing readable`() {
        assertTrue(
            SafetyGuard.blocksUngroundedPlan(elementCount = 0, targetResolves = false),
        )
    }

    @Test
    fun `blocks a plan whose target is not on screen`() {
        assertTrue(
            "pointing at an element that isn't there highlights nothing",
            SafetyGuard.blocksUngroundedPlan(elementCount = 5, targetResolves = false),
        )
    }

    @Test
    fun `allows a plan whose target is actually present`() {
        assertFalse(
            SafetyGuard.blocksUngroundedPlan(elementCount = 5, targetResolves = true),
        )
    }
}
