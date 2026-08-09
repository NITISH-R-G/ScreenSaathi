package com.screensaathi.overlay

/**
 * The one source of truth for how the floating assistant presents itself.
 *
 * Replaces a set of independent booleans (`expanded`, `voiceActive`,
 * `isListening`, `minimized`) that could contradict each other — "expanded but
 * minimised", "listening but collapsed" were all reachable, and each new flag
 * multiplied the states nobody had tested. A single enum means an impossible
 * combination cannot be represented at all.
 *
 * Presentation only. It says nothing about whether the recorder is running or
 * what the planner is doing; [com.screensaathi.session.SessionController]
 * still owns that, and drives this through PillState.
 */
enum class AssistantUiState {
    /** Dormant. Compact pill, no card, no waveform. A tap starts listening. */
    COLLAPSED,

    /** Card open showing an instruction or response, no voice surface. */
    EXPANDED,

    /** Capturing. The voice surface is the waveform, driven by real mic RMS. */
    LISTENING,

    /** Request received, working. Same surface, low travelling ripple. */
    THINKING,

    /** Assistant is talking back. */
    SPEAKING,

    /** Pointing at a target. Deliberately compact — the highlight is the hero. */
    GUIDING,

    /**
     * Parked out of the way so the user can work in the app underneath. The
     * smallest touch footprint we render; a tap restores the previous state.
     */
    MINIMIZED,

    /** Being moved. Suppresses tap handling so a drag never opens the mic. */
    DRAGGING,
    ;

    /** Whether the expandable card is visible in this state. */
    val showsCard: Boolean
        get() = this == EXPANDED || this == LISTENING || this == THINKING ||
            this == SPEAKING || this == GUIDING

    /** Whether the waveform owns the voice slot (vs the transport row). */
    val showsWaveform: Boolean
        get() = this == LISTENING || this == THINKING || this == SPEAKING

    /** Minimised is a dot: no card, no chrome, minimum interception. */
    val isMinimized: Boolean
        get() = this == MINIMIZED
}
