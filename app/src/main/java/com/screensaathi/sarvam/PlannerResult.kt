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
)
