package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy assertions. These encode "the model proposes, policy decides" — if a
 * change here makes a payment button guidable without confirmation, that is a
 * safety regression and the build should fail.
 */
class ActionPolicyTest {

    @Test
    fun `pointing at an ordinary control is safe`() {
        val ruling = ActionPolicy.evaluate("Where to?")

        assertEquals(ActionPolicy.Level.SAFE_GUIDE, ruling.level)
        assertFalse(ruling.requiresConfirmation)
        assertTrue(ruling.isPermitted)
    }

    @Test
    fun `a payment button is high risk and needs confirmation`() {
        val ruling = ActionPolicy.evaluate("Pay now")

        assertEquals(ActionPolicy.Level.HIGH_RISK_ACTION, ruling.level)
        assertTrue(ruling.requiresConfirmation)
    }

    @Test
    fun `high risk detection works in hindi and tamil`() {
        assertEquals(
            ActionPolicy.Level.HIGH_RISK_ACTION,
            ActionPolicy.evaluate("अभी भुगतान करें").level,
        )
        assertEquals(
            ActionPolicy.Level.HIGH_RISK_ACTION,
            ActionPolicy.evaluate("பணம் செலுத்து").level,
        )
    }

    @Test
    fun `sending something to another person requires confirmation`() {
        val ruling = ActionPolicy.evaluate("Send")

        assertEquals(ActionPolicy.Level.USER_CONFIRMATION_REQUIRED, ruling.level)
        assertTrue(ruling.requiresConfirmation)
    }

    /**
     * Talking a user through granting permissions is a social-engineering
     * pattern. Blocked outright rather than confirmable — a confirmation
     * prompt for something that should never happen only trains people to
     * approve prompts.
     */
    @Test
    fun `permission and security surfaces are blocked, not merely confirmed`() {
        val permission = ActionPolicy.evaluate("Grant permission")
        assertEquals(ActionPolicy.Level.BLOCKED_ACTION, permission.level)
        assertFalse(permission.isPermitted)
        assertFalse(permission.requiresConfirmation)

        assertEquals(
            ActionPolicy.Level.BLOCKED_ACTION,
            ActionPolicy.evaluate("Factory reset").level,
        )
        assertEquals(
            ActionPolicy.Level.BLOCKED_ACTION,
            ActionPolicy.evaluate("Show recovery phrase").level,
        )
    }

    @Test
    fun `blocked outranks high risk when a label matches both`() {
        // "delete account" is high-risk wording, but account deletion via a
        // security surface must take the stricter ruling.
        val ruling = ActionPolicy.evaluate("Disable security and delete account")

        assertEquals(ActionPolicy.Level.BLOCKED_ACTION, ruling.level)
    }

    /**
     * The asymmetry that matters: an unrecognised label is not evidence of
     * safety. Pointing at it is fine because the user still decides; tapping
     * it on their behalf is not.
     */
    @Test
    fun `an unrecognised label is guidable but not executable without confirmation`() {
        val pointing = ActionPolicy.evaluate("Blorp", isExecuting = false)
        assertEquals(ActionPolicy.Level.SAFE_GUIDE, pointing.level)

        val executing = ActionPolicy.evaluate("Blorp", isExecuting = true)
        assertEquals(ActionPolicy.Level.USER_CONFIRMATION_REQUIRED, executing.level)
    }

    @Test
    fun `an empty label falls back to pointing`() {
        assertEquals(ActionPolicy.Level.SAFE_GUIDE, ActionPolicy.evaluate("").level)
    }

    @Test
    fun `reading content aloud is always safe`() {
        val ruling = ActionPolicy.evaluateRead()

        assertEquals(ActionPolicy.Level.SAFE_READ, ruling.level)
        assertFalse(ruling.requiresConfirmation)
    }

    @Test
    fun `the matched policy term is reported for eval attribution`() {
        val ruling = ActionPolicy.evaluate("Confirm payment")

        assertTrue(ruling.trigger.isNotEmpty())
        assertTrue(ruling.reason.contains("Confirm payment"))
    }
}
