package com.screensaathi

import android.content.Context
import android.content.Intent

object AppLauncher {

    /**
     * Resolves a spoken app name to a package name, without launching it.
     *
     * Split from [launchApp] so the caller can pin the resolved package (for
     * subsequent screen matching) and run it past the safety guard BEFORE
     * anything starts.
     *
     * Exact label match wins over a substring one: "Uber" must not resolve to
     * "Uber Eats" just because that happened to be enumerated first.
     */
    fun resolvePackageName(context: Context, appName: String): String? {
        if (appName.isBlank()) return null
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (e: Exception) {
            return null
        }

        val labelled = apps.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: return@mapNotNull null
            label to pkg
        }

        labelled.firstOrNull { (label, _) -> label.equals(appName, ignoreCase = true) }
            ?.let { return it.second }

        return labelled.firstOrNull { (label, _) -> label.contains(appName, ignoreCase = true) }?.second
    }

    /**
     * Brings the named app to the front. Returns false when it could not be
     * resolved or started — the caller must not report success on false.
     */
    fun launchApp(context: Context, appName: String): Boolean {
        val pkg = resolvePackageName(context, appName) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        // Resume an existing task rather than starting a cold copy: the user may
        // already be signed in and part-way through.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
