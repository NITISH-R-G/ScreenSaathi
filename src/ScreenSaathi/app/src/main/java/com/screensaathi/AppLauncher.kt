package com.screensaathi

import android.content.Context
import android.content.Intent

object AppLauncher {
    /**
     * Resolves an app name to a package and launches it natively.
     */
    fun launchApp(context: Context, appName: String): Boolean {
        if (appName.isBlank()) return false
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val apps = pm.queryIntentActivities(mainIntent, 0)
        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            }
        }
        return false
    }
}
