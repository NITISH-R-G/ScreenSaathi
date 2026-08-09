package com.screensaathi.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.screensaathi.task.RideApps

/**
 * Builds a [DeviceContext] from what Android will actually tell us.
 *
 * Deliberately does NOT request QUERY_ALL_PACKAGES. Under the manifest's
 * <queries> allow-list this returns a handful of apps, and that is the honest
 * answer — the point of [DeviceContext] is to report the limit rather than
 * paper over it, so anything outside the allow-list resolves to
 * UNKNOWN_DUE_TO_PACKAGE_VISIBILITY instead of being reported as missing.
 *
 * Two evidence sources feed [DeviceContext], and they answer different
 * questions:
 *  - `queryIntentActivities` enumerates what happens to be found. It can only
 *    ever prove presence — a package that's absent simply doesn't appear, with
 *    nothing to distinguish "checked and absent" from "never asked about".
 *  - [RideApps]'s declared package list (the same list mirrored into
 *    AndroidManifest's <queries>) is checked BY NAME via
 *    `getLaunchIntentForPackage`, so a null result for a declared package is
 *    real, authoritative absence, not a visibility gap.
 */
object DeviceContextProvider {

    fun snapshot(context: Context): DeviceContext {
        val pm = context.packageManager
        var discoveryFailed = false

        val discovered = try {
            val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(main, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null // never ourselves
                DeviceApp(
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                    packageName = pkg,
                    launchable = pm.getLaunchIntentForPackage(pkg) != null,
                    enabled = true, // only enabled activities are returned here
                )
            }.distinctBy { it.packageName }
        } catch (e: Exception) {
            // Distinguishable from a genuinely app-free device: logged (class
            // name and declared-package count only — never a package/app
            // listing, which would be device inventory data) and carried on
            // discoveryFailed rather than silently reported as apps=[].
            discoveryFailed = true
            Log.w(TAG, "PackageManager query failed (${e.javaClass.simpleName}); " +
                "device evidence unavailable this snapshot")
            emptyList()
        }

        // With QUERY_ALL_PACKAGES the enumeration above is the whole launchable
        // inventory, so absence within it is authoritative on its own — an app
        // the user names that is not here really is not installed, rather than
        // merely hidden. `checkedAbsent` is retained only for the ride packages
        // the guided taxi task still resolves by package name, which may have no
        // launcher entry (disabled or stub) and so never appear in `discovered`.
        val checkedAbsent = if (discoveryFailed) {
            emptySet()
        } else {
            RideApps.KNOWN_PACKAGES
                .filter { pkg -> discovered.none { it.packageName == pkg } }
                .filter { pkg -> runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() == null }
                .toSet()
        }

        return DeviceContext(
            apps = discovered,
            visiblePackages = discovered.map { it.packageName }.toSet() + checkedAbsent,
            evidenceSource = when {
                discoveryFailed -> Evidence.UNKNOWN
                discovered.isEmpty() && checkedAbsent.isEmpty() -> Evidence.UNKNOWN
                else -> Evidence.PACKAGE_MANAGER
            },
            timestampMs = SystemClock.uptimeMillis(),
            discoveryFailed = discoveryFailed,
            knownLabelToPackage = RideApps.labelToPackage(),
            // Enumeration is now complete, so "not in the list" is real evidence
            // of absence rather than a visibility gap.
            inventoryIsComplete = !discoveryFailed,
        )
    }

    private const val TAG = "DeviceContextProvider"
}
