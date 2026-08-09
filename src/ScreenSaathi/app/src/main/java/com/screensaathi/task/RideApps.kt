package com.screensaathi.task

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Which ride-hailing apps are actually on this phone, and how to open them.
 *
 * Deliberately a lookup over a known package list rather than a category scan:
 * Android has no reliable "ride sharing" category, and a heuristic sweep over
 * every launchable package would put Zoom next to Uber on stage. The list is
 * data — adding an app is adding a line, not writing code.
 */
object RideApps {

    /** Package -> the name a user would recognise. Order is presentation order. */
    private val KNOWN = linkedMapOf(
        "com.ubercab" to "Uber",
        "com.olacabs.customer" to "Ola",
        "com.rapido.passenger" to "Rapido",
        "in.indrive.client" to "inDrive",
        "com.blusmart.rider" to "BluSmart",
        "app.nammayatri.passenger" to "Namma Yatri",
        "com.meru.mgt" to "Meru",
    )

    data class Installed(val packageName: String, val label: String)

    /**
     * The known ride apps present on this device, in [KNOWN] order.
     *
     * Uses the launch intent rather than getPackageInfo: an app can be
     * installed but have no launcher entry (disabled, or a stub), and offering
     * the user something that cannot be opened is worse than not offering it.
     */
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        return KNOWN.mapNotNull { (pkg, label) ->
            if (pm.getLaunchIntentForPackage(pkg) != null) Installed(pkg, label) else null
        }
    }

    /** Brings [packageName] to the front. Returns false if it cannot be opened. */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $packageName")
            return false
        }
        // Bringing an existing task forward beats starting a cold copy: the user
        // may already be signed in and part-way through.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot launch $packageName: ${e.message}")
            false
        }
    }

    fun labelFor(packageName: String): String = KNOWN[packageName] ?: packageName

    /** The declared package set — must stay in sync with AndroidManifest's <queries>. */
    val KNOWN_PACKAGES: Set<String> get() = KNOWN.keys

    /** Label -> package, for resolving a spoken app name to a declared package. */
    fun labelToPackage(): Map<String, String> = KNOWN.entries.associate { (pkg, label) -> label to pkg }

    private const val TAG = "RideApps"
}
