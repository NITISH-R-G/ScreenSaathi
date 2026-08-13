package com.screensaathi.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic intent floor. The planner does the real classification;
 * these cover what has to still work when the network does not.
 */
class IntentClassifierTest {

    @Test
    fun `plain questions are information`() {
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("What is this?"))
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("How much does this cost?"))
    }

    @Test
    fun `imperatives are actions`() {
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("Book this."))
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("Help me pay this"))
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("Help me apply for this"))
    }

    /**
     * The case that breaks naive keyword matching: a perfectly good action
     * verb inside a request to be taught rather than helped.
     */
    @Test
    fun `how do I book this is guidance not action`() {
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("How do I book this?"))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("How do I use this?"))
    }

    @Test
    fun `translation beats the question it is phrased as`() {
        assertEquals(CircleIntent.TRANSLATION, IntentClassifier.classify("Translate this"))
        assertEquals(CircleIntent.TRANSLATION, IntentClassifier.classify("What is this in English?"))
    }

    @Test
    fun `comparison is distinguished from a price question`() {
        assertEquals(CircleIntent.COMPARISON, IntentClassifier.classify("Is this cheaper than last month?"))
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("How much is this?"))
    }

    @Test
    fun `navigation is distinguished from action`() {
        assertEquals(CircleIntent.NAVIGATION, IntentClassifier.classify("Open this"))
        assertEquals(CircleIntent.NAVIGATION, IntentClassifier.classify("Find where I can change this"))
    }

    @Test
    fun `explanation is recognised`() {
        assertEquals(CircleIntent.EXPLANATION, IntentClassifier.classify("Explain this bill"))
    }

    @Test
    fun `hindi requests classify without falling back to english`() {
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("इसे बुक करने में मदद करो"))
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("यह क्या है?"))
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("यह कितना है?"))
    }

    @Test
    fun `tamil requests classify without falling back to english`() {
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("இது என்ன?"))
        assertEquals(CircleIntent.INFORMATION, IntentClassifier.classify("இது எவ்வளவு?"))
    }

    @Test
    fun `unclear input is unknown rather than a wrong guess`() {
        assertEquals(CircleIntent.UNKNOWN, IntentClassifier.classify("hmm"))
        assertEquals(CircleIntent.UNKNOWN, IntentClassifier.classify(""))
        assertEquals(CircleIntent.UNKNOWN, IntentClassifier.classify("   "))
    }

    /**
     * Regression: found on device. "okay help me use it" is the canonical
     * follow-up after "what is this?", and it matched no keyword list at all —
     * so it fell through to UNKNOWN and the caller searched the screen for the
     * literal sentence, which can never match anything.
     */
    @Test
    fun `the canonical follow up phrasing is guidance, not unknown`() {
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("okay help me use it"))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("Okay, help me use it."))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("help me with this"))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("show me how"))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("walk me through this"))

        assertTrue(IntentClassifier.classify("okay help me use it").isAgentic)
    }

    @Test
    fun `follow up guidance phrasing works in hindi and tamil`() {
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("इसे इस्तेमाल करने में मदद कीजिए"))
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("இதைப் பயன்படுத்த உதவுங்கள்"))
    }

    /**
     * A bare "help me" is not guidance on its own — it pairs with whatever
     * verb follows. When a concrete action verb is present, that wins.
     */
    @Test
    fun `bare help plus an action verb stays an action`() {
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("इसे बुक करने में मदद करो"))
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("help me book this"))
        assertEquals(CircleIntent.ACTION, IntentClassifier.classify("help me pay this"))
    }

    /**
     * "how do I book this?" asks to be taught; it must not be executed as an
     * ACTION just because it contains "book".
     */
    @Test
    fun `an interrogative containing an action verb stays guidance`() {
        assertEquals(CircleIntent.GUIDANCE, IntentClassifier.classify("how do i book this"))
    }

    @Test
    fun `only agentic intents hand off to the agent loop`() {
        assertTrue(CircleIntent.ACTION.isAgentic)
        assertTrue(CircleIntent.NAVIGATION.isAgentic)
        assertTrue(CircleIntent.GUIDANCE.isAgentic)

        assertFalse(CircleIntent.INFORMATION.isAgentic)
        assertFalse(CircleIntent.TRANSLATION.isAgentic)
        assertFalse(CircleIntent.UNKNOWN.isAgentic)
    }
}
