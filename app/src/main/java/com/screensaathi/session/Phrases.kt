package com.screensaathi.session

import com.screensaathi.sarvam.Language
import com.screensaathi.sarvam.Spoken

/**
 * The assistant's own words — everything it says that does not come from the
 * task DSL or the planner.
 *
 * These were hardcoded English string literals scattered through
 * SessionController, so even after the language was correctly detected the app
 * still said "I didn't catch that" to a Hindi speaker. They live here instead,
 * keyed by language, and each lookup reports which language it actually found
 * so TTS is never asked to speak English text as `hi-IN`.
 *
 * English and Hindi are authored for the demo; any other detected language
 * falls back to English text *labelled as English*, which is degraded but
 * always speakable. Adding a language is adding a column here.
 */
object Phrases {

    enum class Key {
        LISTENING,
        THINKING,
        DIDNT_CATCH,
        HOLD_LONGER,
        MIC_OFF,
        ALL_DONE,
        STOPPED,
        RESUMED,
        NO_TASKS,
        TAP_MIC,
    }

    private val EN = mapOf(
        Key.LISTENING to "Listening… tap the mic again when you're done.",
        Key.THINKING to "One moment…",
        Key.DIDNT_CATCH to "I didn't catch that — let's start here.",
        Key.HOLD_LONGER to "I didn't catch that — hold the mic a moment longer.",
        Key.MIC_OFF to "Microphone access is off, so I can't hear you. I'll guide you step by step.",
        Key.ALL_DONE to "That's the last step — you're all done!",
        Key.STOPPED to "Stopped. Tap the mic whenever you want to carry on.",
        Key.RESUMED to "Let's carry on from where we left off.",
        Key.NO_TASKS to "No tasks are installed.",
        Key.TAP_MIC to "Tap the mic and tell me what you want to do.",
    )

    private val HI = mapOf(
        Key.LISTENING to "सुन रहा हूँ… बोलकर फिर से माइक दबाइए।",
        Key.THINKING to "एक पल…",
        Key.DIDNT_CATCH to "मैं समझ नहीं पाया — चलिए यहाँ से शुरू करते हैं।",
        Key.HOLD_LONGER to "मैं सुन नहीं पाया — माइक थोड़ी देर और दबाए रखिए।",
        Key.MIC_OFF to "माइक बंद है, इसलिए मैं सुन नहीं सकता। मैं आपको कदम दर कदम बताता हूँ।",
        Key.ALL_DONE to "यही आख़िरी कदम था — काम पूरा हो गया!",
        Key.STOPPED to "रोक दिया। जब चाहें माइक दबाकर आगे बढ़िए।",
        Key.RESUMED to "चलिए वहीं से आगे बढ़ते हैं।",
        Key.NO_TASKS to "कोई काम उपलब्ध नहीं है।",
        Key.TAP_MIC to "माइक दबाइए और बताइए आप क्या करना चाहते हैं।",
    )

    private val BY_LANGUAGE = mapOf(
        "en-IN" to EN,
        "hi-IN" to HI,
    )

    /**
     * The phrase in [language] if we have it, otherwise the English text
     * *labelled English* — never English words wearing another language's code.
     */
    fun get(key: Key, language: String): Spoken {
        val code = Language.normalize(language)
        BY_LANGUAGE[code]?.get(key)?.let { return Spoken(it, code) }
        return Spoken(EN.getValue(key), Language.DEFAULT)
    }

    /** Languages we can speak our own words in, as opposed to merely detect. */
    val AUTHORED: Set<String> get() = BY_LANGUAGE.keys
}
