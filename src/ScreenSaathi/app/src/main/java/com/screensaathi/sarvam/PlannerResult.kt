package com.screensaathi.sarvam

/**
 * In-memory form of the FROZEN planner contract (contracts/planner.schema.json).
 * No field is ever removed after M1; only optional fields may be added.
 */
data class PlannerResult(
    val version: Int,
    val intent: String,
    val step: String,
    val targetResourceId: String,
    val targetIndex: Int,
    val instruction: String,
    val confidence: Double,
    val reason: String,
    /**
     * BCP-47 code of the language [instruction] is written in — an optional
     * addition to the v1 contract, which permits new optional fields but no
     * removals. Always normalized to a code we can actually speak, so the pair
     * can go straight to Bulbul.
     */
    val language: String = Language.DEFAULT,
    val isDone: Boolean = false,
    val targetText: String = "",
    val actionType: String = "guide",
    val actionPayload: String = "",
) {
    /** The instruction together with the language it is written in. */
    val spoken: Spoken get() = Spoken(instruction, language)
}
