package com.screensaathi.ai

import android.graphics.Rect
import com.screensaathi.circle.SelectionResolver
import com.screensaathi.screen.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escalation policy. These assertions are the cost, privacy and latency
 * guarantees stated in [PerceptionStrategy]'s doc comment — if the common case
 * stops being free, that is a regression worth failing a build over.
 */
class PerceptionStrategyTest {

    private fun target(
        element: ScreenElement? = null,
        confidence: Int = 0,
        selectedText: String = "",
        surroundingContext: String = "",
        ambiguous: Boolean = false,
    ) = SelectionResolver.SelectedTarget(
        element = element,
        confidence = confidence,
        alternatives = emptyList(),
        selectedText = selectedText,
        possibleActions = emptySet(),
        surroundingContext = surroundingContext,
        ambiguous = ambiguous,
        reason = "test",
    )

    private fun element(text: String, clickable: Boolean = true) = ScreenElement(
        index = 0,
        resourceId = "",
        text = text,
        className = "android.widget.Button",
        bounds = Rect(),
        editable = false,
        clickable = clickable,
    )

    @Test
    fun `a confidently labelled control never leaves the device`() {
        val decision = PerceptionStrategy.decide(
            target(element = element("Book now"), confidence = 90)
        )

        assertEquals(PerceptionStrategy.Mode.ACCESSIBILITY_ONLY, decision.mode)
        assertFalse(decision.needsPixels)
    }

    @Test
    fun `a weak match escalates to hybrid so pixels can disambiguate`() {
        val decision = PerceptionStrategy.decide(
            target(element = element("Book"), confidence = 40)
        )

        assertEquals(PerceptionStrategy.Mode.HYBRID, decision.mode)
        assertTrue(decision.reason.contains("low confidence"))
    }

    @Test
    fun `an ambiguous match escalates even at high confidence`() {
        val decision = PerceptionStrategy.decide(
            target(element = element("Pay"), confidence = 95, ambiguous = true)
        )

        assertEquals(PerceptionStrategy.Mode.HYBRID, decision.mode)
        assertTrue(decision.reason.contains("ambiguous"))
    }

    @Test
    fun `readable text without a control still stays on device`() {
        val decision = PerceptionStrategy.decide(
            target(selectedText = "Amount due 1,240")
        )

        assertEquals(PerceptionStrategy.Mode.ACCESSIBILITY_ONLY, decision.mode)
        assertFalse(decision.needsPixels)
    }

    @Test
    fun `a region with no semantics at all requires vision`() {
        val decision = PerceptionStrategy.decide(target())

        assertEquals(PerceptionStrategy.Mode.VISION_ONLY, decision.mode)
        assertTrue(decision.needsPixels)
    }

    /**
     * The decision must not quietly change when no provider is configured —
     * "this needs vision and I have none" and "I found nothing" are different
     * things to tell a user, and collapsing them produces a misleading message.
     */
    @Test
    fun `vision availability does not alter the decision, only its satisfiability`() {
        val decision = PerceptionStrategy.decide(target())
        assertEquals(PerceptionStrategy.Mode.VISION_ONLY, decision.mode)

        assertFalse(PerceptionStrategy.isSatisfiable(decision, visionAvailable = false))
        assertTrue(PerceptionStrategy.isSatisfiable(decision, visionAvailable = true))
    }

    @Test
    fun `hybrid remains satisfiable without vision because the tree half still works`() {
        val decision = PerceptionStrategy.decide(
            target(element = element("Book"), confidence = 40)
        )

        assertTrue(PerceptionStrategy.isSatisfiable(decision, visionAvailable = false))
    }

    @Test
    fun `an unlabelled control is not trusted on its label alone`() {
        val decision = PerceptionStrategy.decide(
            target(element = element(""), confidence = 90)
        )

        // No text to match on, so confidence alone must not authorise the
        // accessibility-only path.
        assertEquals(PerceptionStrategy.Mode.HYBRID, decision.mode)
    }
}
