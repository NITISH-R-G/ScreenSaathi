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
)
