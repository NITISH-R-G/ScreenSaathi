package com.screensaathi.launcher

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screensaathi.OverlayService
import com.screensaathi.R
import com.screensaathi.device.DeviceApp
import com.screensaathi.device.DeviceContextProvider

/**
 * ScreenSaathi as the device home screen.
 *
 * Why a launcher at all: the assistant's hardest problem is being *reachable*.
 * An overlay can only be summoned from inside our own app or a notification;
 * the home screen is the one surface the user returns to constantly and the
 * system hands us for free. Owning it turns "invoke the assistant" from a
 * multi-tap detour into the default state of the phone.
 *
 * What this deliberately does NOT do:
 *  - It does not replace the AccessibilityService. A launcher can enumerate and
 *    start apps, but it cannot read or act inside another app's UI; only the
 *    accessibility layer can. The two are complementary, not alternatives.
 *  - It does not become the only entry point. MainActivity remains the setup
 *    surface, so the app is still usable without granting the home role.
 *
 * Registering as HOME is opt-in: the manifest declares the capability, and
 * Android only routes HOME here if the user picks ScreenSaathi as their
 * default launcher. Nothing changes for a user who does not.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        status = findViewById(R.id.launcher_status)
        grid = findViewById(R.id.launcher_grid)
        grid.layoutManager = GridLayoutManager(this, 4)

        findViewById<LinearLayout>(R.id.assistant_bar).setOnClickListener {
            // The assistant is the point of the launcher; make it the most
            // prominent affordance rather than one icon among many.
            OverlayService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every resume: apps are installed and removed while the
        // launcher sits in the background, and a stale grid is a launcher that
        // lies about what the phone has.
        refresh()
    }

    private fun refresh() {
        val snapshot = DeviceContextProvider.snapshot(this)
        val apps = snapshot.apps
            .filter { it.launchable }
            .sortedBy { it.label.lowercase() }

        status.text = when {
            snapshot.discoveryFailed -> getString(R.string.launcher_discovery_failed)
            apps.isEmpty() -> getString(R.string.launcher_no_apps)
            else -> getString(R.string.launcher_app_count, apps.size)
        }

        grid.adapter = AppGridAdapter(apps) { app -> launch(app) }
    }

    /**
     * Direct user taps are self-authorising — the user picked this icon, from a
     * grid built out of real PackageManager evidence. The agent path is the one
     * that must go through SafetyGuard; a person tapping their own home screen
     * is not something to second-guess.
     *
     * A failed start is still surfaced rather than swallowed.
     */
    private fun launch(app: DeviceApp) {
        val intent: Intent? = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            status.text = getString(R.string.launcher_cannot_open, app.label)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            status.text = getString(R.string.launcher_cannot_open, app.label)
        }
    }

    /**
     * HOME means "go back to the launcher", so the usual finish-on-back is
     * wrong here — there is nothing behind the home screen to return to.
     */
    @Deprecated("Back on a launcher is intentionally a no-op")
    override fun onBackPressed() {
        // Deliberately empty.
    }
}
