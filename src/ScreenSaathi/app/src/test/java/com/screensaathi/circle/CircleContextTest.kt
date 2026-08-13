package com.screensaathi.circle

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context persistence across turns, and the honesty rules around a selection
 * that has no accessibility semantics.
 */
class CircleContextTest {

    private fun selection(pkg: String = "com.example.app", sig: String = "sig-1") =
        ScreenSelection.fromPath(
            shape = SelectionShape.CIRCLE,
            path = listOf(
                SelectionPoint(100, 500),
                SelectionPoint(900, 500),
                SelectionPoint(900, 600),
                SelectionPoint(100, 600),
            ),
            packageName = pkg,
            screenSignature = sig,
            capturedAtMs = 1_000L,
        )

    private fun resolvedTarget(
        text: String = "Electricity Bill",
        clickable: Boolean = true,
    ) = SelectionResolver.SelectedTarget(
        element = ScreenElement(
            index = 0,
            resourceId = "bill_row",
            text = text,
            className = "android.widget.Button",
            bounds = Rect(),
            editable = false,
            clickable = clickable,
        ),
        confidence = 88,
        alternatives = emptyList(),
        selectedText = text,
        possibleActions = setOf(SelectionResolver.Action.TAP, SelectionResolver.Action.READ),
        surroundingContext = "Pay bills · Electricity Bill · Water Bill",
        ambiguous = false,
        reason = "covered 95% of the element",
    )

    private fun unresolvedTarget() = SelectionResolver.SelectedTarget(
        element = null,
        confidence = 0,
        alternatives = emptyList(),
        selectedText = "",
        possibleActions = emptySet(),
        surroundingContext = "",
        ambiguous = false,
        reason = "nothing in the accessibility tree fell inside the selection",
    )

    private fun context(
        target: SelectionResolver.SelectedTarget = resolvedTarget(),
        frame: ScreenFrame? = null,
    ) = CircleContext(
        selection = selection(),
        target = target,
        frame = frame,
        languageCode = "en-IN",
    )

    /** The behaviour the whole feature is judged on: "it" survives turn two. */
    @Test
    fun `selection context survives a follow up turn`() {
        val first = context().withTurn(
            request = "What is this?",
            intent = CircleIntent.INFORMATION,
            response = "It's the Electricity Bill row.",
            atMs = 2_000L,
        )

        val second = first.withTurn(
            request = "Okay, help me use it",
            intent = CircleIntent.GUIDANCE,
            response = "",
            atMs = 3_000L,
        )

        assertEquals(2, second.turns.size)
        assertEquals("Electricity Bill", second.target.element!!.text)
        // The original selection geometry is untouched by later turns.
        assertEquals(first.selection, second.selection)
    }

    @Test
    fun `prompt text names the resolved element and its actions`() {
        val prompt = context().toPromptText()

        assertTrue(prompt.contains("Electricity Bill"))
        assertTrue(prompt.contains("tapped"))
        assertTrue(prompt.contains("com.example.app"))
    }

    @Test
    fun `prompt text carries the conversation so far`() {
        val prompt = context()
            .withTurn("What is this?", CircleIntent.INFORMATION, "The electricity bill row.", 2_000L)
            .toPromptText()

        assertTrue(prompt.contains("user: What is this?"))
        assertTrue(prompt.contains("assistant: The electricity bill row."))
    }

    /**
     * A purely visual selection must be described to the model as such. An
     * empty string would read as "nothing was selected", which is a different
     * and wrong claim.
     */
    @Test
    fun `a purely visual selection is described as visual rather than empty`() {
        val prompt = context(target = unresolvedTarget()).toPromptText()

        assertTrue(prompt.contains("no readable UI elements"))
        assertFalse(prompt.contains("Selected element:"))
    }

    @Test
    fun `a selection with no accessibility semantics needs vision`() {
        assertTrue(context(target = unresolvedTarget()).needsVision)
        assertFalse(context().needsVision)
    }

    @Test
    fun `a selection with text but no element does not need vision`() {
        val textOnly = unresolvedTarget().copy(selectedText = "Total 1,240")

        assertFalse(context(target = textOnly).needsVision)
    }

    @Test
    fun `context knows when the user has navigated away`() {
        val ctx = context()

        assertTrue(ctx.matchesScreen("com.example.app", "sig-1"))
        assertFalse(ctx.matchesScreen("com.example.app", "sig-2"))
        assertFalse(ctx.matchesScreen("com.other.app", "sig-1"))
    }

    @Test
    fun `agent task id is tracked so the loop can be resumed or cleared`() {
        val ctx = context()
        assertFalse(ctx.isAgentActive)

        val running = ctx.withTask("pay_bill")
        assertTrue(running.isAgentActive)
        assertEquals("pay_bill", running.activeTaskId)

        assertFalse(running.withTask(null).isAgentActive)
    }
}
