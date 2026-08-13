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

        /** Shown on the dimmed selection surface before anything is drawn. */
        CIRCLE_HINT,

        /** After a selection resolved to a named element. Takes its label. */
        CIRCLE_FOUND,

        /** After a selection that covered text but no single control. */
        CIRCLE_SELECTED_TEXT,

        /**
         * A purely visual selection with no accessibility semantics. This is
         * the honest-limitation phrase — it must never be replaced by a guess
         * about what the region contains.
         */
        CIRCLE_NEEDS_VISION,

        /** The selection landed on nothing resolvable at all. */
        CIRCLE_NOTHING_FOUND,

        /** Invitation to ask about what was just selected. */
        CIRCLE_ASK,

        /**
         * The request did not clearly land in any intent. Asks rather than
         * guessing — guessing ACTION wrong is the expensive direction. Takes
         * the selected element's label.
         */
        CIRCLE_CLARIFY,
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
        Key.CIRCLE_HINT to "Draw around anything on the screen",
        Key.CIRCLE_FOUND to "I found \"%s\".",
        Key.CIRCLE_SELECTED_TEXT to "I read \"%s\".",
        Key.CIRCLE_NEEDS_VISION to "I can see the area you selected, but I'd need visual understanding to identify what's in it.",
        Key.CIRCLE_NOTHING_FOUND to "I couldn't find anything I can work with there. Try drawing around a button or some text.",
        Key.CIRCLE_ASK to "Tap the mic and ask me about this.",
        Key.CIRCLE_CLARIFY to "I've got \"%s\". Would you like to know about it, or shall I help you use it?",
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
        Key.CIRCLE_HINT to "स्क्रीन पर किसी भी चीज़ के चारों ओर घेरा बनाइए",
        Key.CIRCLE_FOUND to "मुझे \"%s\" मिला।",
        Key.CIRCLE_SELECTED_TEXT to "मैंने \"%s\" पढ़ा।",
        Key.CIRCLE_NEEDS_VISION to "आपने जो हिस्सा चुना है वह मुझे दिख रहा है, लेकिन उसमें क्या है यह पहचानने के लिए मुझे दृश्य समझ चाहिए।",
        Key.CIRCLE_NOTHING_FOUND to "वहाँ मुझे ऐसा कुछ नहीं मिला जिस पर काम कर सकूँ। किसी बटन या लिखे हुए के चारों ओर घेरा बनाइए।",
        Key.CIRCLE_ASK to "माइक दबाकर इसके बारे में पूछिए।",
        Key.CIRCLE_CLARIFY to "मेरे पास \"%s\" है। आप इसके बारे में जानना चाहते हैं, या इसे इस्तेमाल करने में मदद चाहिए?",
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
        Key.CIRCLE_HINT to "திரையில் எதைச் சுற்றியும் வரையுங்கள்",
        Key.CIRCLE_FOUND to "எனக்கு \"%s\" கிடைத்தது.",
        Key.CIRCLE_SELECTED_TEXT to "நான் \"%s\" படித்தேன்.",
        Key.CIRCLE_NEEDS_VISION to "நீங்கள் தேர்ந்தெடுத்த பகுதி எனக்குத் தெரிகிறது, ஆனால் அதில் என்ன இருக்கிறது என அறிய எனக்குப் பார்வைத் திறன் தேவை.",
        Key.CIRCLE_NOTHING_FOUND to "அங்கே என்னால் பயன்படுத்தக்கூடிய எதுவும் கிடைக்கவில்லை. ஒரு பொத்தான் அல்லது எழுத்தைச் சுற்றி வரையுங்கள்.",
        Key.CIRCLE_ASK to "மைக்கைத் தட்டி இதைப் பற்றிக் கேளுங்கள்.",
        Key.CIRCLE_CLARIFY to "என்னிடம் \"%s\" இருக்கிறது. அதைப் பற்றி அறிய வேண்டுமா, அல்லது அதைப் பயன்படுத்த உதவ வேண்டுமா?",
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
