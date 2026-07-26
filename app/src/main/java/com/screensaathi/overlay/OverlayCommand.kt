package com.screensaathi.overlay

/**
 * The only input the overlay renderer accepts (contracts/overlay.schema.json).
 * Everything above the overlay speaks to it exclusively through this. The
 * renderer never reasons — it only draws what it is told.
 */

enum class PillState { IDLE, LISTENING, THINKING, SPEAKING, GUIDING, ERROR }

data class HighlightBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val shape: String = "rect",
    val pulse: Boolean = true,
)

data class OverlayCommand(
    val pillState: PillState,
    val expanded: Boolean = false,
    val instruction: String? = null,
    val highlight: HighlightBounds? = null,
    /**
     * Language of [instruction], BCP-47. Optional addition to the v1 overlay
     * contract. The renderer uses it for the pill's own labels ("Listening…")
     * and the language chip, so the chrome speaks the user's language too
     * rather than staying English around a Hindi sentence.
     */
    val language: String = "en-IN",
    /**
     * Options for the user to pick from, e.g. the ride apps on this phone.
     * Empty for an ordinary guiding step. The renderer shows one button per
     * entry and reports back the index that was tapped.
     */
    val choices: List<String> = emptyList(),
)
