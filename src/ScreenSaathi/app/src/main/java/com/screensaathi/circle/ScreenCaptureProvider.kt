package com.screensaathi.circle

import android.graphics.Bitmap

/**
 * A captured frame of the screen.
 *
 * [bitmap] may be null: capture genuinely fails on secure windows (banking
 * apps set `FLAG_SECURE`), below API 30, and when the platform simply refuses.
 * The rest of the frame is still useful in that case — the package name and
 * signature are what let a selection be resolved against the accessibility
 * tree, which is the primary path anyway. Callers must not assume pixels.
 */
data class ScreenFrame(
    val bitmap: Bitmap?,
    val widthPx: Int,
    val heightPx: Int,
    val capturedAtMs: Long,
    val packageName: String,
    val screenSignature: String,
) {
    val hasPixels: Boolean get() = bitmap != null
}

/**
 * Why a capture did not produce pixels.
 *
 * These are reported rather than swallowed so the assistant can say something
 * true ("I can't capture this screen") instead of silently degrading.
 */
enum class CaptureFailure {
    /** `takeScreenshot` needs API 30. */
    UNSUPPORTED_API,

    /** The accessibility service is not currently bound. */
    SERVICE_UNAVAILABLE,

    /** `FLAG_SECURE` window, or the platform declined. */
    REFUSED_BY_PLATFORM,

    /** Took too long — the caller should not block the UI on this. */
    TIMEOUT,

    UNKNOWN,
}

class CaptureException(
    val failure: CaptureFailure,
    message: String,
) : Exception(message)

/**
 * Obtains the current screen's pixels.
 *
 * Deliberately an interface with no Android-service coupling in its signature,
 * so the selection pipeline can be exercised against a fake. Capture is
 * on-demand only — never a continuous stream — because holding a screen
 * capture pipeline open on a device where an accessibility service can already
 * read the screen is both a battery cost and a privacy surface for no benefit.
 */
interface ScreenCaptureProvider {

    /** True when a capture could plausibly succeed right now. */
    val isAvailable: Boolean

    /**
     * Capture the current screen, delivering the outcome to [onResult].
     *
     * Callback rather than `suspend` to match the `HandlerThread` style the
     * rest of this app already uses ([com.screensaathi.session.SessionController]);
     * adding a coroutines dependency for one call site is not worth the
     * inconsistency.
     *
     * [onResult] carries a failed [Result] wrapping a [CaptureException]
     * rather than throwing, so a caller mid-gesture can degrade to
     * accessibility-only resolution instead of unwinding the interaction.
     * It may be invoked on a background thread.
     */
    fun captureCurrentScreen(onResult: (Result<ScreenFrame>) -> Unit)
}

/**
 * Used when no capture is possible at all. Keeps the selection pipeline
 * constructible (and testable) without a bound accessibility service.
 */
object UnavailableScreenCaptureProvider : ScreenCaptureProvider {
    override val isAvailable: Boolean get() = false

    override fun captureCurrentScreen(onResult: (Result<ScreenFrame>) -> Unit) {
        onResult(
            Result.failure(
                CaptureException(
                    CaptureFailure.SERVICE_UNAVAILABLE,
                    "no screen capture provider is configured",
                )
            )
        )
    }
}
