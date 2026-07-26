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
        CHOOSE_APP,
        /** Takes the app's name, e.g. "Opening Uber…". */
        OPENING_APP,
        APP_WONT_OPEN,
        NO_RIDE_APPS,
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
        Key.CHOOSE_APP to "Which app would you like to use?",
        Key.OPENING_APP to "Opening %s…",
        Key.APP_WONT_OPEN to "I couldn't open that app. Pick another one.",
        Key.NO_RIDE_APPS to "There are no taxi apps on this phone.",
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
        Key.CHOOSE_APP to "आप कौन सा ऐप इस्तेमाल करना चाहेंगे?",
        Key.OPENING_APP to "%s खोल रहा हूँ…",
        Key.APP_WONT_OPEN to "मैं वह ऐप नहीं खोल पाया। कोई दूसरा चुनिए।",
        Key.NO_RIDE_APPS to "इस फ़ोन में कोई टैक्सी ऐप नहीं है।",
    )

    private val TA = mapOf(
        Key.LISTENING to "கேட்டுக்கொண்டிருக்கிறேன்… முடிந்ததும் மைக்கை மீண்டும் தட்டுங்கள்.",
        Key.THINKING to "ஒரு நிமிடம்…",
        Key.DIDNT_CATCH to "எனக்குப் புரியவில்லை — இங்கிருந்து தொடங்குவோம்.",
        Key.HOLD_LONGER to "எனக்குக் கேட்கவில்லை — மைக்கை இன்னும் சிறிது நேரம் பிடியுங்கள்.",
        Key.MIC_OFF to "மைக் அணைக்கப்பட்டுள்ளது, அதனால் உங்கள் குரல் கேட்கவில்லை. படிப்படியாக வழிகாட்டுகிறேன்.",
        Key.ALL_DONE to "அதுதான் கடைசி படி — முடிந்துவிட்டது!",
        Key.STOPPED to "நிறுத்திவிட்டேன். தொடர விரும்பினால் மைக்கைத் தட்டுங்கள்.",
        Key.RESUMED to "நாம் நிறுத்திய இடத்திலிருந்து தொடர்வோம்.",
        Key.NO_TASKS to "எந்தப் பணியும் இல்லை.",
        Key.TAP_MIC to "மைக்கைத் தட்டி, நீங்கள் என்ன செய்ய விரும்புகிறீர்கள் என்று சொல்லுங்கள்.",
        Key.CHOOSE_APP to "எந்த ஆப்பைப் பயன்படுத்த விரும்புகிறீர்கள்?",
        Key.OPENING_APP to "%s திறக்கிறேன்…",
        Key.APP_WONT_OPEN to "என்னால் அந்த ஆப்பைத் திறக்க முடியவில்லை. வேறு ஒன்றைத் தேர்வுசெய்யுங்கள்.",
        Key.NO_RIDE_APPS to "இந்த ஃபோனில் டாக்ஸி ஆப் எதுவும் இல்லை.",
    )

    private val BY_LANGUAGE = mapOf(
        "en-IN" to EN,
        "hi-IN" to HI,
        "ta-IN" to TA,
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
