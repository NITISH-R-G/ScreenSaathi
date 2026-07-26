package com.screensaathi.sarvam

/**
 * The languages ScreenSaathi will speak, and the rules for choosing one.
 *
 * The list is not aspirational — every code here was verified end to end
 * against live Sarvam on 2026-07-26 by `scripts/smoke_languages.ps1`: Bulbul v3
 * synthesised the language with speaker `anand`, and Saaras v3 detected it back
 * correctly from the resulting audio. Re-run that script before adding a code.
 */
object Language {

    /**
     * Used when nothing has been detected yet, and for any text we only
     * authored in English. English is the safe default because an English
     * string sent with a non-English `target_language_code` is rejected by
     * Bulbul outright:
     *
     *     "Text must contain at least one character from the allowed languages."
     *
     * That is the whole reason [Spoken] carries its own language: the code sent
     * to TTS has to describe the *text*, not the user's spoken language.
     */
    const val DEFAULT = "en-IN"

    /** Verified Bulbul-speakable / Saaras-detectable. See the smoke script. */
    val SUPPORTED = linkedSetOf(
        "en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN",
        "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN",
    )

    /** Endonyms — what a speaker calls their own language. Used in the UI. */
    private val NATIVE_NAME = mapOf(
        "en-IN" to "English",
        "hi-IN" to "हिन्दी",
        "bn-IN" to "বাংলা",
        "gu-IN" to "ગુજરાતી",
        "kn-IN" to "ಕನ್ನಡ",
        "ml-IN" to "മലയാളം",
        "mr-IN" to "मराठी",
        "pa-IN" to "ਪੰਜਾਬੀ",
        "ta-IN" to "தமிழ்",
        "te-IN" to "తెలుగు",
    )

    /**
     * Coerce whatever Saaras or the planner hands us into a code we know we can
     * speak. Accepts a bare tag ("hi") as well as a full one ("hi-IN"), because
     * the planner is a language model and will occasionally shorten it.
     *
     * An unrecognised code silently became a 400 from Bulbul and the app went
     * mute with no explanation, so everything funnels through here.
     */
    fun normalize(code: String?): String {
        val raw = code?.trim()?.replace('_', '-') ?: return DEFAULT
        if (raw.isEmpty()) return DEFAULT
        SUPPORTED.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        val base = raw.substringBefore('-').lowercase()
        return SUPPORTED.firstOrNull { it.substringBefore('-') == base } ?: DEFAULT
    }

    fun isSupported(code: String?): Boolean =
        code != null && SUPPORTED.any { it.equals(code.trim(), ignoreCase = true) }

    /** For the debug panel and the language chip on the card. */
    fun nativeName(code: String): String = NATIVE_NAME[normalize(code)] ?: code

    /** Script each language is actually written in. Hindi and Marathi share one. */
    private val SCRIPT = mapOf(
        "en-IN" to Character.UnicodeScript.LATIN,
        "hi-IN" to Character.UnicodeScript.DEVANAGARI,
        "mr-IN" to Character.UnicodeScript.DEVANAGARI,
        "bn-IN" to Character.UnicodeScript.BENGALI,
        "gu-IN" to Character.UnicodeScript.GUJARATI,
        "kn-IN" to Character.UnicodeScript.KANNADA,
        "ml-IN" to Character.UnicodeScript.MALAYALAM,
        "pa-IN" to Character.UnicodeScript.GURMUKHI,
        "ta-IN" to Character.UnicodeScript.TAMIL,
        "te-IN" to Character.UnicodeScript.TELUGU,
    )

    /** First supported code written in each script; the inverse of [SCRIPT]. */
    private val CODE_FOR_SCRIPT: Map<Character.UnicodeScript, String> =
        SCRIPT.entries.reversed().associate { (code, script) -> script to code }

    /**
     * Scripts present in [text], with a count of characters in each.
     *
     * Counts every character of the script, not just `isLetter` ones. Devanagari
     * vowel signs like "ा" and "ि" are combining marks, not letters, so a
     * letters-only count undercounts Devanagari badly — "amount यहाँ भरिए" comes
     * out 6 Latin to 5 Devanagari and gets called English.
     */
    private fun scriptsIn(text: String): Map<Character.UnicodeScript, Int> {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            if (script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.UNKNOWN ||
                script == Character.UnicodeScript.INHERITED
            ) continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        return counts
    }

    /**
     * The language code it is actually safe to send to Bulbul for [text].
     *
     * Bulbul rejects a text/code mismatch outright —
     * "Text must contain at least one character from the allowed languages" —
     * and a rejected call means the app just goes silent with no explanation.
     * The planner is a language model, so it will sometimes label romanised
     * Hindi as `hi-IN`, or answer in English while claiming the user's language.
     *
     * The rule follows Bulbul's own: **presence, not majority**.
     *  - If the claimed language's script appears at all, keep the claim. This
     *    is what makes code-switching work — "amount यहाँ भरिए" stays `hi-IN`,
     *    and Bulbul reads the embedded English word naturally.
     *  - Otherwise fall to whichever Indic script is actually there, so
     *    Devanagari labelled `en-IN` is corrected rather than mispronounced.
     *  - Failing that, romanised or English text becomes `en-IN`.
     */
    fun reconcile(text: String, claimed: String?): String {
        val claim = normalize(claimed)
        val present = scriptsIn(text)
        if (present.isEmpty()) return claim // digits or punctuation only

        SCRIPT[claim]?.let { if (present.containsKey(it)) return claim }

        val indic = present.entries
            .filter { it.key != Character.UnicodeScript.LATIN }
            .maxByOrNull { it.value }
            ?.key
        if (indic != null) CODE_FOR_SCRIPT[indic]?.let { return it }

        return DEFAULT
    }
}

/**
 * A piece of text together with the language it is actually written in.
 *
 * Language detection used to be propagated to TTS while the *text* stayed
 * English, so a Hindi speaker heard English words requested as `hi-IN`. Pairing
 * the two makes that mismatch unrepresentable: whatever produced the string —
 * the task DSL, the planner, or a UI phrase — says which language it wrote.
 */
data class Spoken(val text: String, val language: String = Language.DEFAULT)
