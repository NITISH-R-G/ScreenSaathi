package com.screensaathi.overlay

import com.screensaathi.sarvam.Language

/**
 * The pill's own one-word status, in the user's language.
 *
 * These were English literals inside the renderer's `when`, so a Hindi speaker
 * got a Devanagari instruction under an English "Listening…". The chrome has to
 * switch language with the content or the illusion breaks at a glance.
 *
 * Kept beside the renderer rather than in session/Phrases because these are
 * render-time labels for a PillState, not things the assistant ever says aloud.
 */
object PillLabels {

    private val EN = mapOf(
        PillState.IDLE to "ScreenSaathi",
        PillState.LISTENING to "Listening…",
        PillState.THINKING to "Thinking…",
        PillState.SPEAKING to "Speaking…",
        PillState.GUIDING to "Guiding you",
        PillState.ERROR to "Let's try again",
    )

    private val HI = mapOf(
        PillState.IDLE to "स्क्रीन साथी",
        PillState.LISTENING to "सुन रहा हूँ…",
        PillState.THINKING to "सोच रहा हूँ…",
        PillState.SPEAKING to "बोल रहा हूँ…",
        PillState.GUIDING to "रास्ता दिखा रहा हूँ",
        PillState.ERROR to "फिर से कोशिश करें",
    )

    private val BY_LANGUAGE = mapOf("en-IN" to EN, "hi-IN" to HI)

    fun forState(state: PillState, language: String): String {
        val table = BY_LANGUAGE[Language.normalize(language)] ?: EN
        return table[state] ?: EN.getValue(state)
    }
}
