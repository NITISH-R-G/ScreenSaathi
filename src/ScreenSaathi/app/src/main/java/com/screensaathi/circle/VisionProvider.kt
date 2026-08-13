package com.screensaathi.circle

/**
 * Understanding a selected region from its pixels.
 *
 * Deliberately provider-neutral: nothing here names OpenAI, Gemini or Sarvam,
 * and nothing in the signature assumes a chat API, a particular auth scheme,
 * or streaming. Adding a real provider later should be writing one class that
 * implements this — not touching the selection view, the resolver, the session
 * controller, or the overlay.
 *
 * There is no vision model wired into ScreenSaathi today. [NoOpVisionProvider]
 * is what actually runs, and it says so rather than inventing a description.
 */
interface VisionProvider {

    /** Whether this provider can currently answer at all. */
    val isAvailable: Boolean

    /** Shown to the user when explaining why visual understanding is missing. */
    val displayName: String

    /**
     * Describe or answer a question about the selected region.
     *
     * [frame] carries the pixels, [selection] the geometry that says which
     * part of them the user meant, and [userPrompt] what they asked. The
     * accessibility-derived text is deliberately *not* a parameter: when the
     * accessibility tree can answer, the caller should not be asking a vision
     * provider at all. This is the fallback for regions the tree cannot
     * explain.
     *
     * Callback-based to match [ScreenCaptureProvider] and the app's existing
     * `HandlerThread` style. May be invoked on a background thread.
     */
    fun analyzeSelection(
        frame: ScreenFrame,
        selection: ScreenSelection,
        userPrompt: String,
        onResult: (VisionResult) -> Unit,
    )
}

/** Outcome of a vision request. */
sealed interface VisionResult {

    /** A real description, from a real model. */
    data class Understood(
        val description: String,
        /** Language code of [description], so TTS is not asked to lie. */
        val languageCode: String,
        val confidence: Int,
    ) : VisionResult

    /**
     * No visual understanding is available.
     *
     * The reason is carried so the assistant can say something specific and
     * true. This is a first-class outcome, not an error path — for now it is
     * the *only* outcome, and the UI is built around it being normal.
     */
    data class Unavailable(val reason: Reason) : VisionResult {
        enum class Reason {
            /** No provider is configured. The current state of the world. */
            NO_PROVIDER,

            /** A provider exists but has no credential. */
            NOT_CONFIGURED,

            /** Capture produced no pixels, so there is nothing to look at. */
            NO_PIXELS,

            /** The provider was asked and failed. */
            PROVIDER_ERROR,
        }
    }
}

/**
 * The provider that ships today.
 *
 * It never guesses. Circling a photograph gets an honest "I'd need visual
 * understanding for that" rather than a plausible-sounding hallucination —
 * which for an accessibility tool aimed at people who cannot easily verify the
 * answer themselves is the difference between useful and dangerous.
 */
object NoOpVisionProvider : VisionProvider {

    override val isAvailable: Boolean get() = false

    override val displayName: String get() = "none configured"

    override fun analyzeSelection(
        frame: ScreenFrame,
        selection: ScreenSelection,
        userPrompt: String,
        onResult: (VisionResult) -> Unit,
    ) {
        val reason = if (!frame.hasPixels) {
            VisionResult.Unavailable.Reason.NO_PIXELS
        } else {
            VisionResult.Unavailable.Reason.NO_PROVIDER
        }
        onResult(VisionResult.Unavailable(reason))
    }
}
