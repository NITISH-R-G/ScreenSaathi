package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug panel is the only usable triage tool on device. These tests pin the
 * behaviour that was actually broken: a turn's fields must *accumulate*, not
 * replace each other, so the panel still shows what was heard after the
 * highlight resolution writes its own diagnostics.
 */
class VoiceDebugTest {

    @Test
    fun `accumulating a turn keeps every earlier field`() {
        var d = VoiceDebug()
        d = d.copy(heard = "bijli ka bill bharna hai", sttMs = 729)
        d = d.copy(intent = "pay_bill", step = "amount", planMs = 606, confidence = 0.97)
        // The highlight resolution used to overwrite the panel at this point.
        d = d.copy(readerBound = true, elementCount = 7, bounds = "[40,220][680,300]")

        val panel = d.toPanel()
        assertTrue("transcript must survive the bounds write", panel.contains("bijli ka bill"))
        assertTrue(panel.contains("stt 729ms"))
        assertTrue(panel.contains("plan 606ms"))
        assertTrue(panel.contains("pay_bill"))
        assertTrue(panel.contains("0.97"))
        assertTrue(panel.contains("[40,220][680,300]"))
    }

    @Test
    fun `an empty panel is empty rather than a wall of dashes`() {
        assertEquals("", VoiceDebug().toPanel())
    }

    @Test
    fun `negative confidence renders as fallback`() {
        assertTrue(VoiceDebug(confidence = -1.0).toPanel().contains("conf: fallback"))
        assertTrue(VoiceDebug(confidence = 0.42).toPanel().contains("conf: 0.42"))
    }

    @Test
    fun `an unbound reader is called out, because that is the demo-day landmine`() {
        val panel = VoiceDebug(readerBound = false).toPanel()
        assertTrue(panel.contains("NULL"))
        assertTrue("must say what to do about it", panel.contains("Settings"))
    }

    @Test
    fun `a long transcript is truncated so the panel stays readable`() {
        val panel = VoiceDebug(heard = "x".repeat(200)).toPanel()
        assertTrue(panel.length < 80)
    }

    @Test
    fun `a partial turn renders only what it knows`() {
        val panel = VoiceDebug(note = "no Sarvam key — deterministic path").toPanel()
        assertEquals("note: no Sarvam key — deterministic path", panel)
    }
}
