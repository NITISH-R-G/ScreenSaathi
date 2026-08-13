package com.screensaathi.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-op vision provider is what actually ships. These pin the honesty
 * contract: it must report *why* it cannot answer, and must never produce a
 * description.
 */
class VisionProviderTest {

    private fun selection() = ScreenSelection.fromPath(
        shape = SelectionShape.FREEFORM,
        path = listOf(SelectionPoint(0, 0), SelectionPoint(100, 0), SelectionPoint(100, 100)),
        packageName = "com.example.app",
        screenSignature = "sig",
        capturedAtMs = 0L,
    )

    private fun frame(hasPixels: Boolean) = ScreenFrame(
        // A real Bitmap cannot be constructed in the JVM test runtime; the
        // provider only checks nullability, which is what is under test.
        bitmap = null,
        widthPx = if (hasPixels) 1080 else 0,
        heightPx = if (hasPixels) 2392 else 0,
        capturedAtMs = 0L,
        packageName = "com.example.app",
        screenSignature = "sig",
    )

    @Test
    fun `no op provider reports itself unavailable`() {
        assertFalse(NoOpVisionProvider.isAvailable)
    }

    @Test
    fun `no op provider never returns a description`() {
        var result: VisionResult? = null
        NoOpVisionProvider.analyzeSelection(frame(hasPixels = false), selection(), "What is this?") {
            result = it
        }

        assertTrue(result is VisionResult.Unavailable)
    }

    @Test
    fun `missing pixels are reported distinctly from a missing provider`() {
        var result: VisionResult? = null
        NoOpVisionProvider.analyzeSelection(frame(hasPixels = false), selection(), "What is this?") {
            result = it
        }

        assertEquals(
            VisionResult.Unavailable.Reason.NO_PIXELS,
            (result as VisionResult.Unavailable).reason,
        )
    }

    @Test
    fun `unavailable capture provider fails rather than returning an empty frame`() {
        var result: Result<ScreenFrame>? = null
        UnavailableScreenCaptureProvider.captureCurrentScreen { result = it }

        assertTrue(result!!.isFailure)
        val failure = result!!.exceptionOrNull() as CaptureException
        assertEquals(CaptureFailure.SERVICE_UNAVAILABLE, failure.failure)
        assertFalse(UnavailableScreenCaptureProvider.isAvailable)
    }
}
