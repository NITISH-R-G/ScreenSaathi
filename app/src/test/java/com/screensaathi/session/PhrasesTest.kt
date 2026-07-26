package com.screensaathi.session

import com.screensaathi.sarvam.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assistant's own words. The invariant that matters: a phrase is never
 * returned wearing a language it is not written in, because that combination
 * is a 400 from Bulbul and silence from the app.
 */
class PhrasesTest {

    @Test
    fun `Hindi is answered in Hindi`() {
        val p = Phrases.get(Phrases.Key.LISTENING, "hi-IN")
        assertEquals("hi-IN", p.language)
        assertTrue("expected Devanagari", p.text.any { it.code in 0x0900..0x097F })
    }

    @Test
    fun `English is answered in English`() {
        val p = Phrases.get(Phrases.Key.LISTENING, "en-IN")
        assertEquals("en-IN", p.language)
        assertEquals("Listening… tap the mic again when you're done.", p.text)
    }

    @Test
    fun `an unauthored language falls back to English text labelled English`() {
        // Tamil is speakable by Bulbul but we have not authored our own words in
        // it. The degraded answer must be honest English, never English text
        // tagged ta-IN.
        val p = Phrases.get(Phrases.Key.LISTENING, "ta-IN")
        assertEquals(Language.DEFAULT, p.language)
        assertEquals(Phrases.get(Phrases.Key.LISTENING, "en-IN").text, p.text)
    }

    @Test
    fun `an unknown language code does not throw`() {
        val p = Phrases.get(Phrases.Key.THINKING, "klingon")
        assertEquals(Language.DEFAULT, p.language)
        assertTrue(p.text.isNotBlank())
    }

    @Test
    fun `every key exists in every authored language`() {
        for (language in Phrases.AUTHORED) {
            for (key in Phrases.Key.values()) {
                val p = Phrases.get(key, language)
                assertEquals("$key/$language fell back", language, p.language)
                assertTrue("$key/$language is blank", p.text.isNotBlank())
            }
        }
    }

    @Test
    fun `the Hindi wording is actually translated, not copied English`() {
        for (key in Phrases.Key.values()) {
            assertNotEquals(
                "$key was never translated to Hindi",
                Phrases.get(key, "en-IN").text,
                Phrases.get(key, "hi-IN").text,
            )
        }
    }

    @Test
    fun `every authored phrase is safe to hand straight to Bulbul`() {
        // The end-to-end invariant: whatever we return, reconciling it against
        // its own text must not change the code.
        for (language in Phrases.AUTHORED) {
            for (key in Phrases.Key.values()) {
                val p = Phrases.get(key, language)
                assertEquals(
                    "$key/$language would be rejected or re-tagged by TTS",
                    p.language,
                    Language.reconcile(p.text, p.language),
                )
            }
        }
    }
}
