package com.screensaathi.device

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Builds a [DeviceContext] from what Android will actually tell us.
 *
 * Deliberately does NOT request QUERY_ALL_PACKAGES. Under the manifest's
 * <queries> allow-list this returns a handful of apps, and that is the honest
 * answer — the point of [DeviceContext] is to report the limit rather than
 * paper over it, so anything outside the allow-list resolves to
 * UNKNOWN_DUE_TO_PACKAGE_VISIBILITY instead of being reported as missing.
 */
object DeviceContextProvider {

    fun snapshot(context: Context): DeviceContext {
        val pm = context.packageManager
        val apps = try {
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
            emptyList()
        }

        return DeviceContext(
            apps = apps,
            visiblePackages = apps.map { it.packageName }.toSet(),
            evidenceSource = if (apps.isEmpty()) Evidence.UNKNOWN else Evidence.PACKAGE_MANAGER,
            timestampMs = SystemClock.uptimeMillis(),
        )
    }
}
