package com.screensaathi.sarvam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Language handling is the difference between the assistant answering a Hindi
 * speaker in Hindi and the app going silently mute.
 *
 * The mute failure is real and verified: Bulbul rejects a text/language
 * mismatch with 400 "Text must contain at least one character from the allowed
 * languages", and a rejected synthesis is indistinguishable from the assistant
 * having nothing to say. [Language.reconcile] is the guard, so it is tested
 * hardest.
 */
class LanguageTest {

    // --- normalize ------------------------------------------------------------

    @Test
    fun `a supported code passes through`() {
        assertEquals("hi-IN", Language.normalize("hi-IN"))
        assertEquals("ta-IN", Language.normalize("ta-IN"))
    }

    @Test
    fun `a bare tag is widened to the full code`() {
        // The planner is a language model; it shortens codes sometimes.
        assertEquals("hi-IN", Language.normalize("hi"))
        assertEquals("bn-IN", Language.normalize("bn"))
    }

    @Test
    fun `case and underscore variants are accepted`() {
        assertEquals("hi-IN", Language.normalize("HI-in"))
        assertEquals("hi-IN", Language.normalize("hi_IN"))
        assertEquals("hi-IN", Language.normalize("  hi-IN "))
    }

    @Test
    fun `anything unspeakable becomes the default rather than reaching Bulbul`() {
        assertEquals(Language.DEFAULT, Language.normalize(null))
        assertEquals(Language.DEFAULT, Language.normalize(""))
        assertEquals(Language.DEFAULT, Language.normalize("klingon"))
        assertEquals(Language.DEFAULT, Language.normalize("fr-FR"))
    }

    @Test
    fun `isSupported does not silently widen`() {
        assertTrue(Language.isSupported("hi-IN"))
        assertFalse(Language.isSupported("hi"))
        assertFalse(Language.isSupported("fr-FR"))
        assertFalse(Language.isSupported(null))
    }

    // --- reconcile: the anti-mute guard --------------------------------------

    @Test
    fun `an honest claim is kept`() {
        assertEquals("hi-IN", Language.reconcile("इस बॉक्स में रकम भरिए।", "hi-IN"))
        assertEquals("en-IN", Language.reconcile("Enter the amount here.", "en-IN"))
    }

    @Test
    fun `English text claimed as Hindi is corrected to English`() {
        // This is the exact shape of the bug: detection said hi-IN, the text
        // stayed English, Bulbul 400s, the app goes quiet.
        assertEquals("en-IN", Language.reconcile("Enter the bill amount in this box.", "hi-IN"))
    }

    @Test
    fun `romanised Hindi is treated as English, because that is what Bulbul accepts`() {
        assertEquals("en-IN", Language.reconcile("bijli ka bill bhariye", "hi-IN"))
    }

    @Test
    fun `Devanagari text mislabelled as English is corrected to Hindi`() {
        assertEquals("hi-IN", Language.reconcile("इस बॉक्स में रकम भरिए।", "en-IN"))
    }

    @Test
    fun `code-switched text keeps the claimed Indic language`() {
        // "amount यहाँ भरिए" — Hindi structure with an English noun the user
        // already knows. Bulbul only needs one character of the language, and
        // reads the embedded English fine, so this must stay hi-IN.
        //
        // Counting letters would get this wrong: Devanagari vowel signs are
        // combining marks rather than letters, so this scores 6 Latin to 5
        // Devanagari and would be mislabelled English.
        assertEquals("hi-IN", Language.reconcile("amount यहाँ भरिए", "hi-IN"))
        assertEquals("hi-IN", Language.reconcile("submit बटन दबाइए", "hi-IN"))
        assertEquals("ta-IN", Language.reconcile("amount இங்கே உள்ளிடவும்", "ta-IN"))
    }

    @Test
    fun `English with no Indic characters at all is English, whatever is claimed`() {
        assertEquals("en-IN", Language.reconcile("Please enter the amount here now", "hi-IN"))
    }

    @Test
    fun `each script maps to its own language`() {
        assertEquals("ta-IN", Language.reconcile("தொகையை உள்ளிடவும்.", "en-IN"))
        assertEquals("te-IN", Language.reconcile("మొత్తాన్ని నమోదు చేయండి.", "en-IN"))
        assertEquals("bn-IN", Language.reconcile("পরিমাণ লিখুন।", "en-IN"))
        assertEquals("kn-IN", Language.reconcile("ಮೊತ್ತವನ್ನು ನಮೂದಿಸಿ.", "en-IN"))
        assertEquals("ml-IN", Language.reconcile("തുക നൽകുക.", "en-IN"))
        assertEquals("gu-IN", Language.reconcile("રકમ ભરો.", "en-IN"))
        assertEquals("pa-IN", Language.reconcile("ਰਕਮ ਭਰੋ।", "en-IN"))
    }

    @Test
    fun `Devanagari keeps a Marathi claim, since Marathi shares the script`() {
        // Correcting mr-IN to hi-IN here would be wrong: both are speakable and
        // both are written in Devanagari, so the claim is the only signal.
        assertEquals("mr-IN", Language.reconcile("या बॉक्समध्ये रक्कम भरा.", "mr-IN"))
    }

    @Test
    fun `text with no letters at all keeps the claim rather than guessing`() {
        assertEquals("hi-IN", Language.reconcile("123 ...", "hi-IN"))
    }

    @Test
    fun `native names are endonyms`() {
        assertEquals("हिन्दी", Language.nativeName("hi-IN"))
        assertEquals("தமிழ்", Language.nativeName("ta-IN"))
        assertEquals("English", Language.nativeName("en-IN"))
    }

    @Test
    fun `every supported language has a native name and a script`() {
        for (code in Language.SUPPORTED) {
            assertTrue("$code has no native name", Language.nativeName(code) != code)
            // A supported code must be reconcilable with its own text, which
            // only holds if the script table knows about it.
            assertEquals(code, Language.reconcile("123", code))
        }
    }
}
