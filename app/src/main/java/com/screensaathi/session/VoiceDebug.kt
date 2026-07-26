package com.screensaathi.session

/**
 * One voice turn's diagnostics, accumulated and rendered as a single panel.
 *
 * This exists because the debug panel used to be written twice per turn from
 * two different places — transcript/intent/latency first, then the highlight
 * resolution diagnostics — milliseconds apart on the same background thread.
 * The second write always destroyed the first, so the panel could never show
 * what the app actually heard. Long-pressing the pill is the only usable triage
 * tool on device (logcat is far too noisy), so half a panel meant no triage.
 *
 * Everything is nullable and rendered only when set, so the panel stays short
 * on the deterministic path and grows into the full picture on a voice turn.
 */
data class VoiceDebug(
    val heard: String? = null,
    val intent: String? = null,
    val step: String? = null,
    val wantResourceId: String? = null,
    val sttMs: Long? = null,
    val planMs: Long? = null,
    /** Negative means "planner not consulted / rejected", rendered as `fallback`. */
    val confidence: Double? = null,
    val readerBound: Boolean? = null,
    val screenPackage: String? = null,
    val settled: Boolean? = null,
    val elementCount: Int? = null,
    val visibleIds: String? = null,
    val bounds: String? = null,
    /** Free-text reason the turn took the path it did. */
    val note: String? = null,
) {

    fun toPanel(): String = buildString {
        heard?.let { line("heard", it.take(40)) }
        if (intent != null || step != null) {
            line("intent", "${intent ?: "-"}  step: ${step ?: "-"}")
        }
        wantResourceId?.let { line("want", it) }
        if (sttMs != null || planMs != null) {
            line("latency", "stt ${sttMs ?: "-"}ms  plan ${planMs ?: "-"}ms")
        }
        confidence?.let {
            line("conf", if (it < 0) "fallback" else String.format("%.2f", it))
        }
        readerBound?.let { line("reader", if (it) "bound" else "NULL — re-toggle in Settings") }
        if (screenPackage != null || elementCount != null) {
            line("screen", "${screenPackage ?: "-"} settled=${settled ?: "-"} elems=${elementCount ?: 0}")
        }
        visibleIds?.let { line("ids", it.take(80)) }
        bounds?.let { line("bounds", it) }
        note?.let { line("note", it) }
    }.trimEnd()

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append(": ").append(value).append('\n')
    }
}
