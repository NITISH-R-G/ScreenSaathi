package com.screensaathi.circle

/**
 * What the user wants done with the thing they circled.
 *
 * This is the fork that separates ScreenSaathi's circle mode from an ordinary
 * circle-to-search: "what is this?" and "help me pay this" are the same
 * gesture and the same selection, but only one of them should start the agent
 * loop.
 */
enum class CircleIntent {
    /** A fact about the selection. "How much is this?" */
    INFORMATION,

    /** Do the thing. "Book this." Routes into the existing agent loop. */
    ACTION,

    /** Get somewhere. "Open this", "where do I change this setting?" */
    NAVIGATION,

    /** Set against something else. "Is this cheaper than last month?" */
    COMPARISON,

    /** Why/what does it mean. "Explain this charge." */
    EXPLANATION,

    /** Render it in another language. */
    TRANSLATION,

    /** Teach me to do it myself. "How do I use this?" */
    GUIDANCE,

    /** Genuinely unclear — ask rather than guess. */
    UNKNOWN;

    /**
     * Does this intent hand off to the agent loop rather than being answered
     * in place? [NAVIGATION] does, because getting somewhere on a live screen
     * is target resolution plus a highlight, not a sentence.
     */
    val isAgentic: Boolean get() = this == ACTION || this == NAVIGATION || this == GUIDANCE
}

/**
 * Deterministic intent classification, no network required.
 *
 * The planner is the real classifier — it sees the selection, the screen and
 * the conversation. This exists for the same reason
 * [com.screensaathi.session.StepEngine] does: every network call in this app
 * is allowed to fail, and the feature still has to do something sensible.
 * Treat it as a floor, not a ceiling.
 *
 * Deliberately multilingual. English-only keyword matching would have made
 * English a hidden requirement for a feature that is supposed to work in the
 * language the user actually speaks.
 */
object IntentClassifier {

    /**
     * Interrogatives are checked before imperatives, because "how do I book
     * this?" is a request to be taught, not an instruction to book — and it
     * contains a perfectly good action verb that would otherwise win.
     */
    private val TRANSLATION = listOf(
        "translate", "translation", "in english", "in hindi", "in tamil",
        "अनुवाद", "ट्रांसलेट", "मतलब क्या",
        "மொழிபெயர்", "தமிழில்",
    )

    private val COMPARISON = listOf(
        "compare", "cheaper", "better than", "difference between", "versus", " vs ",
        "तुलना", "सस्ता", "से बेहतर",
        "ஒப்பிட", "மலிவான",
    )

    private val EXPLANATION = listOf(
        "explain", "why is", "why does", "why am", "what does this mean",
        "समझाओ", "समझाइए", "क्यों",
        "விளக்க", "ஏன்",
    )

    private val GUIDANCE = listOf(
        "how do i", "how can i", "how to use", "how does this work", "teach me",
        "कैसे करूँ", "कैसे इस्तेमाल", "कैसे उपयोग",
        "எப்படி பயன்படுத்த", "எப்படி செய்",
    )

    private val INFORMATION = listOf(
        "what is", "what's", "what are", "how much", "how many", "when is",
        "who is", "where is this from", "price of", "cost of",
        "क्या है", "कितना", "कब है", "कौन",
        "என்ன", "எவ்வளவு", "எப்போது",
    )

    private val NAVIGATION = listOf(
        "open this", "open it", "go to", "take me to", "find where", "show me where",
        "खोलो", "खोलिए", "ले चलो", "कहाँ है",
        "திற", "எங்கே",
    )

    private val ACTION = listOf(
        "book", "pay", "buy", "order", "apply", "send", "submit", "recharge",
        "cancel", "confirm", "checkout", "purchase", "fill",
        "बुक", "भुगतान", "पैसे", "खरीद", "ऑर्डर", "आवेदन", "भेज", "भर",
        "புக்", "பணம்", "வாங்க", "ஆர்டர்", "அனுப்ப", "நிரப்ப",
    )

    /**
     * Classify [request]. Returns [CircleIntent.UNKNOWN] for anything that
     * does not clearly land, so the caller asks instead of guessing wrong —
     * guessing ACTION wrong is the expensive direction.
     */
    fun classify(request: String): CircleIntent {
        val q = request.lowercase().trim()
        if (q.isEmpty()) return CircleIntent.UNKNOWN

        // Order matters. Each of these would otherwise be swallowed by a
        // broader bucket below it.
        if (TRANSLATION.any { q.contains(it) }) return CircleIntent.TRANSLATION
        if (COMPARISON.any { q.contains(it) }) return CircleIntent.COMPARISON
        if (GUIDANCE.any { q.contains(it) }) return CircleIntent.GUIDANCE
        if (EXPLANATION.any { q.contains(it) }) return CircleIntent.EXPLANATION
        if (INFORMATION.any { q.contains(it) }) return CircleIntent.INFORMATION
        if (NAVIGATION.any { q.contains(it) }) return CircleIntent.NAVIGATION
        if (ACTION.any { q.contains(it) }) return CircleIntent.ACTION

        return CircleIntent.UNKNOWN
    }
}
