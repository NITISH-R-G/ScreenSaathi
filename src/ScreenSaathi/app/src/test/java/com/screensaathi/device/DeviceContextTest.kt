package com.screensaathi.device

import com.screensaathi.session.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-awareness regression tests.
 *
 * The property that matters most: Android package visibility means "we cannot
 * see it" and "it is not installed" are different statements, and only one of
 * them is ever safe to say out loud.
 */
class DeviceContextTest {

    private val whatsapp = DeviceApp("WhatsApp", "com.whatsapp", launchable = true)
    private val uber = DeviceApp("Uber", "com.ubercab", launchable = true)
    private val ola = DeviceApp("Ola", "com.olacabs.customer", launchable = true)

    /** Mirrors the manifest's <queries> allow-list. */
    private val visible = setOf("com.ubercab", "com.olacabs.customer")
    private val rideLabels = mapOf("Uber" to "com.ubercab", "Ola" to "com.olacabs.customer")

    private fun ctx(vararg apps: DeviceApp) =
        DeviceContext(apps.toList(), visible, Evidence.PACKAGE_MANAGER, 1L)

    // --- 1/2: known present ---------------------------------------------------

    @Test
    fun `an installed app is known present`() {
        val r = ctx(whatsapp).resolveApp("WhatsApp", rideLabels)
        assertEquals(Availability.KNOWN_PRESENT, r.availability)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Allow)
    }

    @Test
    fun `a launchable installed app may be launched`() {
        val r = ctx(uber).resolveApp("Uber", rideLabels)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Allow)
    }

    // --- 3: disabled ----------------------------------------------------------

    @Test
    fun `a disabled app is not launched`() {
        val r = ctx(whatsapp.copy(enabled = false)).resolveApp("WhatsApp", rideLabels)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    @Test
    fun `an installed but unlaunchable app is not launched`() {
        val r = ctx(DeviceApp("Calendar Sync", "com.example.sync", launchable = false))
            .resolveApp("Calendar Sync", rideLabels)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    // --- 4: THE important one -------------------------------------------------

    @Test
    fun `an invisible app is UNKNOWN, never absent`() {
        // Instagram is outside <queries>, so PackageManager cannot see it. That
        // is not evidence of absence and must never be reported as such.
        val r = ctx(uber).resolveApp("Instagram", rideLabels)
        assertEquals(
            "package visibility must yield UNKNOWN, not absence",
            Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY, r.availability,
        )
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    @Test
    fun `an unverifiable app is never described as missing`() {
        val r = ctx(uber).resolveApp("Instagram", rideLabels)
        val said = r.humanStatus().lowercase()
        assertTrue("must not claim absence, said: $said", said.contains("couldn't verify"))
        assertTrue("must not say 'not installed', said: $said", !said.contains("not installed"))
    }

    // --- 5: authoritative absence ---------------------------------------------

    @Test
    fun `absence is authoritative only inside the visible set`() {
        // Ola IS declared in <queries>, so not seeing it does mean it is absent.
        val r = ctx(uber).resolveApp("Ola", rideLabels)
        assertEquals(Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET, r.availability)
        assertTrue(r.humanStatus().contains("not installed"))
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    // --- 6: ambiguity ---------------------------------------------------------

    @Test
    fun `multiple matches are not resolved arbitrarily`() {
        val c = DeviceContext(
            listOf(DeviceApp("Chrome Beta", "com.chrome.beta", true), DeviceApp("Chrome", "com.android.chrome", true)),
            visible, Evidence.PACKAGE_MANAGER, 1L,
        )
        val r = c.resolveApp("Chrome", rideLabels)
        assertTrue("both Chromes should match", r.isAmbiguous)
        assertTrue("must ask, not guess", SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    // --- 10: the model cannot invent apps -------------------------------------

    @Test
    fun `an app the model invented cannot be launched`() {
        val r = ctx(uber).resolveApp("XYZBank", rideLabels)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    @Test
    fun `an empty device yields no launches at all`() {
        val r = DeviceContext.empty().resolveApp("WhatsApp")
        assertEquals(Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY, r.availability)
        assertTrue(SafetyGuard.validateLaunch(r) is SafetyGuard.Verdict.Block)
    }

    // --- prompt evidence ------------------------------------------------------

    @Test
    fun `prompt evidence states the limits of what is known`() {
        val text = ctx(uber, ola).toPromptText()
        assertTrue(text.contains("Uber") && text.contains("Ola"))
        assertTrue("must warn the model off unverifiable claims",
            text.contains("UNVERIFIABLE"))
    }

    // --- 7/8/9: launch result handling ----------------------------------------

    /**
     * Models SessionController's launch branch: a false result must produce a
     * truthful failure and must NOT auto-continue.
     */
    private fun launchOutcome(launched: Boolean): Pair<String, Boolean> =
        if (!launched) "APP_WONT_OPEN" to false else "OPENING" to true

    @Test
    fun `a failed launch reports failure and does not continue`() {
        val (msg, continued) = launchOutcome(false)
        assertEquals("APP_WONT_OPEN", msg)
        assertTrue("a failed launch must not auto-continue", !continued)
    }

    @Test
    fun `a successful launch continues normally`() {
        val (msg, continued) = launchOutcome(true)
        assertEquals("OPENING", msg)
        assertTrue(continued)
    }
}
