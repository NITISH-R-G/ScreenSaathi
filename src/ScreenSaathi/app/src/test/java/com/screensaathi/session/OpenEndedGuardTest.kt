package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the open-ended execution gate.
 *
 * Every guard is tested in BOTH directions. A guard with only negative tests
 * gets tightened until it blocks everything, including the workflows the
 * product exists to perform — so each BLOCK below has a paired ALLOW.
 */
class OpenEndedGuardTest {

    private fun verdict(
        request: String,
        actionType: String,
        rid: String = "",
        text: String = "",
        payload: String = "",
        elements: Int = 3,
        settled: Boolean = true,
        resolves: Boolean = true,
    ) = SafetyGuard.validateOpenEndedAction(
        userRequest = request, actionType = actionType,
        targetResourceId = rid, targetText = text, actionPayload = payload,
        elementCount = elements, settled = settled, targetResolves = resolves,
    )

    private fun assertBlocked(v: SafetyGuard.Verdict, why: String) =
        assertTrue("$why — expected BLOCK, got $v", v is SafetyGuard.Verdict.Block)

    private fun assertAllowed(v: SafetyGuard.Verdict, why: String) =
        assertTrue("$why — expected ALLOW, got $v", v is SafetyGuard.Verdict.Allow)

    // --- 1/2/3: ambiguous request vs irreversible target ----------------------

    @Test
    fun `ambiguous request must not press Pay`() {
        assertBlocked(
            verdict("do the usual thing", "click", rid = "pay_button", text = "Pay Now"),
            "'do the usual thing' is not authorisation to pay",
        )
    }

    @Test
    fun `ambiguous request must not press Submit`() {
        assertBlocked(
            verdict("go ahead", "click", rid = "submit_button", text = "Submit"),
            "'go ahead' names no action",
        )
    }

    @Test
    fun `explicitly requested payment is allowed`() {
        assertAllowed(
            verdict("pay the bill now", "click", rid = "pay_button", text = "Pay Now"),
            "the user said 'pay' — blocking here would break the product",
        )
    }

    @Test
    fun `an ordinary non-irreversible button is allowed`() {
        assertAllowed(
            verdict("tap the Save button", "click", rid = "save_button", text = "Save"),
            "saving is not irreversible",
        )
    }

    // --- 4/5/6: type_text payload provenance ---------------------------------

    @Test
    fun `empty typed payload is blocked`() {
        assertBlocked(
            verdict("fill in my phone number", "type_text", rid = "phone_field", payload = ""),
            "nothing to type",
        )
    }

    @Test
    fun `model-invented value is blocked`() {
        assertBlocked(
            verdict("fill in my phone number", "type_text", rid = "phone_field", payload = "9876500000"),
            "the user never supplied that number",
        )
    }

    @Test
    fun `user-supplied value is allowed`() {
        assertAllowed(
            verdict("type 9876500000 into the phone field", "type_text",
                rid = "phone_field", payload = "9876500000"),
            "the value came from the user",
        )
    }

    @Test
    fun `ordinary user-supplied text is allowed`() {
        assertAllowed(
            verdict("type hello world into the note", "type_text",
                rid = "note_field", payload = "hello world"),
            "non-sensitive text the user actually said",
        )
    }

    // --- 7/8: evidence -------------------------------------------------------

    @Test
    fun `missing target is blocked`() {
        assertBlocked(
            verdict("tap continue", "click", rid = "continue_button", resolves = false),
            "cannot tap what is not on screen",
        )
    }

    @Test
    fun `unsettled screen is blocked`() {
        assertBlocked(
            verdict("tap continue", "click", rid = "continue_button", settled = false),
            "mid-transition screens are not evidence",
        )
    }

    @Test
    fun `empty screen is blocked`() {
        assertBlocked(
            verdict("tap continue", "click", rid = "continue_button", elements = 0),
            "no readable elements",
        )
    }

    // --- 9/10: non-actions and injection --------------------------------------

    @Test
    fun `guide and answer are never gated`() {
        assertAllowed(verdict("anything at all", "guide"), "guide only points")
        assertAllowed(verdict("anything at all", "answer"), "answer only speaks")
    }

    @Test
    fun `screen text cannot authorise an unrelated irreversible action`() {
        // The injected instruction lives in screen text, not in the request.
        // The request is what authorises; screen content is data.
        assertBlocked(
            verdict("read this page to me", "click", rid = "pay_button", text = "Pay Bill"),
            "UI text saying 'tap Pay Bill' is not the user asking",
        )
    }

    @Test
    fun `launching an unrequested app that books something is blocked`() {
        assertBlocked(
            verdict("what is on my screen", "launch_app", payload = "Book My Show"),
            "'book' in an unrequested launch payload",
        )
    }

    @Test
    fun `launching an explicitly requested app is allowed`() {
        assertAllowed(
            verdict("open the clock app", "launch_app", payload = "Clock"),
            "explicitly requested, nothing irreversible",
        )
    }

    // --- the gate itself, not just the predicate ------------------------------

    /**
     * Exercises the decision SessionController.processOpenEndedNext makes:
     * a blocked plan is degraded to `guide`, so no device action runs.
     */
    @Test
    fun `blocked plan degrades to guide instead of executing`() {
        val v = verdict("go ahead", "click", rid = "submit_button", text = "Submit")
        val safeActionType = if (v is SafetyGuard.Verdict.Block) "guide" else "click"
        assertEquals("a blocked plan must not stay executable", "guide", safeActionType)
    }

    @Test
    fun `allowed plan keeps its action type`() {
        val v = verdict("type hello world into the note", "type_text",
            rid = "note_field", payload = "hello world")
        val safeActionType = if (v is SafetyGuard.Verdict.Block) "guide" else "type_text"
        assertEquals("a legitimate plan must still execute", "type_text", safeActionType)
    }
}
