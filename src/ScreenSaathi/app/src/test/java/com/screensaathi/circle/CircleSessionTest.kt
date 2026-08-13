package com.screensaathi.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CircleSession] with the accessibility service absent, which is also the
 * on-device failure case worth being correct about: the selection still
 * resolves to *something* honest rather than throwing.
 *
 * The generation discipline here mirrors the turn invalidation in the voice
 * path (docs/DECISIONS.md) — a slow capture landing after the user has drawn
 * again must not overwrite the newer selection.
 */
class CircleSessionTest {

    /** Holds the callback so the test controls when capture "completes". */
    private class DeferredCapture(override val isAvailable: Boolean = true) : ScreenCaptureProvider {
        val pending = mutableListOf<(Result<ScreenFrame>) -> Unit>()

        override fun captureCurrentScreen(onResult: (Result<ScreenFrame>) -> Unit) {
            pending += onResult
        }

        fun completeAll(packageName: String = "com.example.app") {
            val frames = pending.toList()
            pending.clear()
            frames.forEach {
                it(
                    Result.success(
                        ScreenFrame(
                            bitmap = null,
                            widthPx = 1080,
                            heightPx = 2392,
                            capturedAtMs = 0L,
                            packageName = packageName,
                            screenSignature = "sig",
                        )
                    )
                )
            }
        }
    }

    private fun session(capture: ScreenCaptureProvider) = CircleSession(
        capture = capture,
        vision = NoOpVisionProvider,
        readerProvider = { null },
        cacheDirProvider = { null },
        clock = { 1_000L },
    )

    private fun path(offsetY: Int = 0) = listOf(
        SelectionPoint(100, 500 + offsetY),
        SelectionPoint(900, 500 + offsetY),
        SelectionPoint(900, 600 + offsetY),
        SelectionPoint(100, 600 + offsetY),
    )

    @Test
    fun `a selection resolves even with no accessibility service`() {
        val s = session(DeferredCapture(isAvailable = false))
        var resolved: CircleContext? = null

        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") { resolved = it }

        assertNotNull(resolved)
        assertNull(resolved!!.target.element)
        assertTrue(resolved!!.target.reason.contains("accessibility service"))
        assertEquals("en-IN", resolved!!.languageCode)
    }

    @Test
    fun `capture is not attempted when the provider is unavailable`() {
        val capture = DeferredCapture(isAvailable = false)
        val s = session(capture)

        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") {}

        assertTrue(capture.pending.isEmpty())
    }

    /**
     * The stale-capture case. Two selections drawn in quick succession; the
     * first capture completes last and must be discarded.
     */
    @Test
    fun `a late capture from a previous selection does not overwrite the current one`() {
        val capture = DeferredCapture()
        val s = session(capture)

        s.onSelectionDrawn(path(offsetY = 0), SelectionShape.FREEFORM, "en-IN") {}
        val firstSelectionTop = s.context!!.selection.bounds.top

        s.onSelectionDrawn(path(offsetY = 800), SelectionShape.FREEFORM, "en-IN") {}
        val secondSelectionTop = s.context!!.selection.bounds.top

        // Both captures now land, oldest first.
        capture.completeAll()

        assertEquals(secondSelectionTop, s.context!!.selection.bounds.top)
        assertTrue(secondSelectionTop != firstSelectionTop)
    }

    @Test
    fun `turns accumulate against the live selection`() {
        val s = session(DeferredCapture(isAvailable = false))
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") {}

        s.addTurn("What is this?", CircleIntent.INFORMATION, "A region of the screen.")
        s.addTurn("Okay, help me use it", CircleIntent.GUIDANCE, "")

        assertEquals(2, s.context!!.turns.size)
        assertEquals(CircleIntent.GUIDANCE, s.context!!.turns.last().intent)
    }

    @Test
    fun `clear drops the context`() {
        val s = session(DeferredCapture(isAvailable = false))
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") {}
        assertNotNull(s.context)

        s.clear()

        assertNull(s.context)
    }

    @Test
    fun `vision analysis reports no pixels when capture never produced any`() {
        val s = session(DeferredCapture(isAvailable = false))
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") {}

        var result: VisionResult? = null
        s.analyzeVisually("What is this?") { result = it }

        assertEquals(
            VisionResult.Unavailable.Reason.NO_PIXELS,
            (result as VisionResult.Unavailable).reason,
        )
    }

    @Test
    fun `active task id is tracked for handoff to the agent loop`() {
        val s = session(DeferredCapture(isAvailable = false))
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "en-IN") {}

        s.setActiveTask("pay_bill")
        assertTrue(s.context!!.isAgentActive)

        s.setActiveTask(null)
        assertTrue(!s.context!!.isAgentActive)
    }
}
