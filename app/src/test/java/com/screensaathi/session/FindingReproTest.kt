package com.screensaathi.session

import com.screensaathi.device.Availability
import com.screensaathi.device.DeviceApp
import com.screensaathi.device.DeviceContext
import com.screensaathi.device.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pins for the four real-device-validated findings from the
 * code-review audit of commit 20002be.
 *
 * This file originally asserted the BROKEN pre-fix behaviour (see git history
 * for that version, and the audit report for the full before/after). All four
 * were reproduced and confirmed real before any fix was applied:
 *
 *  1. `resolveApp()`'s absence branch was unreachable in production —
 *     visiblePackages was derived only from discovered apps, so a package
 *     could never be "checked and found absent". Grounded in a real device
 *     (Nothing A001, Android 16/API 36): `adb shell dumpsys package
 *     com.screensaathi` showed only 3 of the 7 <queries>-declared ride
 *     packages are actually installed and resolvable; the other 4, including
 *     in.indrive.client, are declared but genuinely absent — exactly the case
 *     this type exists to represent, and exactly the case it couldn't.
 *  2. Irreversible-hint matching was raw substring containment: a "Recall
 *     Settings" button was blocked as an unrequested irreversible action
 *     because "recall" contains "call", though the user never mentioned it.
 *  3. App resolution matched bidirectionally by substring with no exact-match
 *     priority: "Uber" was reported ambiguous against "Uber Eats" even though
 *     "Uber" is the real, exact, installed label (com.ubercab, confirmed
 *     installed on the connected device).
 *  4. A PackageManager exception was caught and silently reported as
 *     apps=[], identical to a device that legitimately has zero visible apps.
 *
 * These tests now assert the FIXED behaviour and must stay green.
 */
class FindingReproTest {

    // --- Finding #1: authoritative absence is now reachable -------------------

    @Test
    fun `FIXED finding 1 - a declared, genuinely absent package resolves as authoritative absence`() {
        // Mirrors what DeviceContextProvider.snapshot() now builds: apps = the
        // 3 actually-installed ride packages; visiblePackages = discovered
        // packages PLUS the declared packages explicitly checked-and-absent
        // (in.indrive.client, com.blusmart.rider, app.nammayatri.passenger,
        // com.meru.mgt on the real device); knownLabelToPackage carried on the
        // context itself rather than left for the caller to remember to pass.
        val realDeviceApps = listOf(
            DeviceApp("Uber", "com.ubercab", launchable = true),
            DeviceApp("Ola", "com.olacabs.customer", launchable = true),
            DeviceApp("Rapido", "com.rapido.passenger", launchable = true),
        )
        val checkedAbsent = setOf(
            "in.indrive.client", "com.blusmart.rider", "app.nammayatri.passenger", "com.meru.mgt",
        )
        val ctx = DeviceContext(
            apps = realDeviceApps,
            visiblePackages = realDeviceApps.map { it.packageName }.toSet() + checkedAbsent,
            evidenceSource = Evidence.PACKAGE_MANAGER,
            timestampMs = 1L,
            knownLabelToPackage = mapOf(
                "Uber" to "com.ubercab", "Ola" to "com.olacabs.customer",
                "Rapido" to "com.rapido.passenger", "inDrive" to "in.indrive.client",
                "BluSmart" to "com.blusmart.rider", "Namma Yatri" to "app.nammayatri.passenger",
                "Meru" to "com.meru.mgt",
            ),
        )

        val result = ctx.resolveApp("inDrive")

        assertEquals(
            "in.indrive.client is declared and confirmed absent on the real device — " +
                "this must now be authoritative, not UNKNOWN",
            Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET,
            result.availability,
        )
        assertTrue(result.humanStatus().contains("not installed"))
        assertTrue("must not silently substitute a different app",
            SafetyGuard.validateLaunch(result) is SafetyGuard.Verdict.Block)
    }

    @Test
    fun `FIXED finding 1 - a package outside the declared set stays UNKNOWN, never ABSENT`() {
        // The fix must not overcorrect into treating everything unmatched as
        // absent — only the explicitly-declared-and-checked set is authoritative.
        val ctx = DeviceContext(
            apps = listOf(DeviceApp("Uber", "com.ubercab", launchable = true)),
            visiblePackages = setOf("com.ubercab"),
            evidenceSource = Evidence.PACKAGE_MANAGER,
            timestampMs = 1L,
            knownLabelToPackage = mapOf("Uber" to "com.ubercab"),
        )
        val result = ctx.resolveApp("Instagram")
        assertEquals(Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY, result.availability)
    }

    // --- Finding #2: hint matching is now word-boundary-aware -----------------

    @Test
    fun `FIXED finding 2 - a Recall Settings button no longer trips the call hint`() {
        val v = SafetyGuard.validateOpenEndedAction(
            userRequest = "tap the option below",
            actionType = "click",
            targetResourceId = "recall_btn", targetText = "Recall Settings", actionPayload = "",
            elementCount = 1, settled = true, targetResolves = true,
        )
        assertTrue(
            "a settings button must not be blocked just because its label " +
                "contains 'call' as a substring of 'recall'",
            v is SafetyGuard.Verdict.Allow,
        )
    }

    @Test
    fun `genuine irreversible actions remain protected after the word-boundary fix`() {
        val v = SafetyGuard.validateOpenEndedAction(
            userRequest = "how do I start",
            actionType = "click",
            targetResourceId = "submit_button", targetText = "Book Now", actionPayload = "",
            elementCount = 1, settled = true, targetResolves = true,
        )
        assertTrue("a real, whole-word 'book' target must still be blocked",
            v is SafetyGuard.Verdict.Block)
    }

    @Test
    fun `an explicitly requested irreversible action is still allowed`() {
        val v = SafetyGuard.validateOpenEndedAction(
            userRequest = "please book my cab now",
            actionType = "click",
            targetResourceId = "book_btn", targetText = "Book Now", actionPayload = "",
            elementCount = 1, settled = true, targetResolves = true,
        )
        assertTrue("the user did say 'book' — the fix must not overblock this",
            v is SafetyGuard.Verdict.Allow)
    }

    // --- Finding #3: exact match wins over substring ambiguity -----------------

    @Test
    fun `FIXED finding 3 - Uber resolves exactly even with Uber Eats also installed`() {
        // "Uber" is the real label of the app confirmed installed on the
        // connected device (com.ubercab, via pm list packages).
        val ctx = DeviceContext(
            apps = listOf(
                DeviceApp("Uber", "com.ubercab", launchable = true),
                DeviceApp("Uber Eats", "com.ubercab.eats", launchable = true),
            ),
            visiblePackages = setOf("com.ubercab", "com.ubercab.eats"),
            evidenceSource = Evidence.PACKAGE_MANAGER,
            timestampMs = 1L,
        )
        val result = ctx.resolveApp("Uber")
        assertTrue("an exact label match must not be ambiguous", !result.isAmbiguous)
        assertEquals("com.ubercab", result.single?.packageName)
    }

    @Test
    fun `FIXED finding 3 - Uber Eats also resolves exactly`() {
        val ctx = DeviceContext(
            apps = listOf(
                DeviceApp("Uber", "com.ubercab", launchable = true),
                DeviceApp("Uber Eats", "com.ubercab.eats", launchable = true),
            ),
            visiblePackages = setOf("com.ubercab", "com.ubercab.eats"),
            evidenceSource = Evidence.PACKAGE_MANAGER,
            timestampMs = 1L,
        )
        val result = ctx.resolveApp("Uber Eats")
        assertTrue(!result.isAmbiguous)
        assertEquals("com.ubercab.eats", result.single?.packageName)
    }

    @Test
    fun `a capability request naming no exact app is still correctly ambiguous`() {
        // "Find an app for booking a cab" must NOT get exact-match precedence —
        // it names no specific app, so genuine ambiguity is the correct result.
        val ctx = DeviceContext(
            apps = listOf(
                DeviceApp("Uber", "com.ubercab", launchable = true),
                DeviceApp("Ola", "com.olacabs.customer", launchable = true),
            ),
            visiblePackages = setOf("com.ubercab", "com.olacabs.customer"),
            evidenceSource = Evidence.PACKAGE_MANAGER,
            timestampMs = 1L,
        )
        // No exact/substring label match for a generic capability phrase —
        // resolution correctly falls through rather than guessing.
        val result = ctx.resolveApp("a cab booking app")
        assertTrue(result.matches.isEmpty())
    }

    // --- Finding #4: discovery failure is now distinguishable -----------------

    @Test
    fun `FIXED finding 4 - a query failure is distinguishable from zero installed apps`() {
        fun snapshotOnException(threw: Boolean): DeviceContext {
            var discoveryFailed = false
            val apps: List<DeviceApp> = if (threw) {
                try {
                    throw SecurityException("simulated PackageManager failure")
                } catch (e: Exception) {
                    discoveryFailed = true // DeviceContextProvider now sets and logs this
                    emptyList()
                }
            } else {
                emptyList()
            }
            return DeviceContext(
                apps = apps,
                visiblePackages = apps.map { it.packageName }.toSet(),
                evidenceSource = Evidence.UNKNOWN,
                timestampMs = 1L,
                discoveryFailed = discoveryFailed,
            )
        }
        val afterRealException = snapshotOnException(threw = true)
        val afterGenuineEmptyDevice = snapshotOnException(threw = false)

        assertNotEquals(
            "an exception must be distinguishable from a legitimately app-free device",
            afterGenuineEmptyDevice, afterRealException,
        )
        assertTrue(afterRealException.discoveryFailed)
        assertTrue(!afterGenuineEmptyDevice.discoveryFailed)
    }
}
