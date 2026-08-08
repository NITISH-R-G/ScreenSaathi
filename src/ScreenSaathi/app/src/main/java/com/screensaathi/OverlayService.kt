package com.screensaathi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screensaathi.overlay.HighlightView
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillLabels
import com.screensaathi.overlay.PillState
import com.screensaathi.sarvam.Language
import com.screensaathi.session.SessionController

/**
 * The overlay renderer. Owns two WindowManager windows:
 *   1. a full-screen, touch-through highlight layer
 *   2. the interactive pill / assistant card
 *
 * It renders an OverlayCommand (contracts/overlay.schema.json) and nothing more
 * — all reasoning lives in SessionController. UI taps are forwarded to the
 * controller; the controller pushes commands back.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var highlightView: HighlightView
    private lateinit var pillRoot: View
    private lateinit var cardBody: LinearLayout
    private lateinit var pillLabel: TextView
    private lateinit var instructionText: TextView
    private lateinit var stateDot: View
    private lateinit var languageChip: TextView
    private lateinit var debugPanel: TextView
    private lateinit var choiceRow: LinearLayout
    private lateinit var choiceButtons: List<TextView>

    private var expanded = false
    private var debugVisible = false

    private lateinit var controller: SessionController

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addHighlightWindow()
        addPillWindow()
        controller = SessionController(applicationContext) { cmd -> main.post { render(cmd) } }
        controller.debugSink = SessionController.DebugSink { text -> main.post { updateDebug(text) } }
        render(OverlayCommand(PillState.IDLE, expanded = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Rehearsal entry point: run a task without speaking. Same engine,
        // overlay and cursor as a spoken request — only STT is skipped.
        when (intent?.action) {
            ACTION_RUN_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en-IN"
                main.post { controller.startTaskById(taskId, language) }
            }
            // Pick an option without touching the screen. Same code path as the
            // button, so a rehearsal exercises the real thing.
            ACTION_CHOOSE -> {
                val index = intent.getIntExtra(EXTRA_CHOICE, -1)
                if (index >= 0) main.post { controller.onChoiceTapped(index) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Window setup ---------------------------------------------------------

    private fun addHighlightWindow() {
        highlightView = HighlightView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(highlightView, lp)
    }

    private fun addPillWindow() {
        pillRoot = LayoutInflater.from(this).inflate(R.layout.overlay_pill, null)
        cardBody = pillRoot.findViewById(R.id.card_body)
        pillLabel = pillRoot.findViewById(R.id.pill_label)
        instructionText = pillRoot.findViewById(R.id.instruction_text)
        stateDot = pillRoot.findViewById(R.id.state_dot)
        languageChip = pillRoot.findViewById(R.id.language_chip)
        debugPanel = pillRoot.findViewById(R.id.debug_panel)
        choiceRow = pillRoot.findViewById(R.id.choice_row)
        choiceButtons = listOf(R.id.choice_0, R.id.choice_1, R.id.choice_2)
            .map { pillRoot.findViewById<TextView>(it) }
        choiceButtons.forEachIndexed { i, b ->
            b.setOnClickListener { controller.onChoiceTapped(i) }
        }

        pillRoot.findViewById<View>(R.id.pill_row).setOnClickListener { toggleExpanded() }
        pillRoot.findViewById<View>(R.id.pill_row).setOnLongClickListener {
            debugVisible = !debugVisible
            debugPanel.visibility = if (debugVisible) View.VISIBLE else View.GONE
            true
        }
        pillRoot.findViewById<View>(R.id.mic_button).setOnClickListener { controller.onMicTapped() }
        pillRoot.findViewById<View>(R.id.next_button).setOnClickListener { controller.onNextTapped() }
        pillRoot.findViewById<View>(R.id.stop_button).setOnClickListener { controller.onStopTapped() }
        pillRoot.findViewById<View>(R.id.close_button).setOnClickListener { stopSelf() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Not focusable, so the keyboard on the screen underneath still works,
            // but still touchable so the mic/next buttons receive taps.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // Anchored at the bottom so the expanded card grows upward and never
        // covers the form fields it is pointing at (the highlight must stay
        // visible — that is the whole product).
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(28)
        wm.addView(pillRoot, lp)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // --- Rendering (the whole job of this class) ------------------------------

    private fun render(cmd: OverlayCommand) {
        val dotColor = when (cmd.pillState) {
            PillState.IDLE -> Color.parseColor("#4D8DFF")
            PillState.LISTENING -> Color.parseColor("#FF5A5A")
            PillState.THINKING -> Color.parseColor("#FFC24D")
            PillState.SPEAKING -> Color.parseColor("#00E5A0")
            PillState.GUIDING -> Color.parseColor("#00E5A0")
            PillState.ERROR -> Color.parseColor("#FF5A5A")
        }
        stateDot.background.setTint(dotColor)
        // The pill's own label speaks the user's language too — an English
        // "Listening…" above a Hindi instruction breaks the illusion instantly.
        pillLabel.text = PillLabels.forState(cmd.pillState, cmd.language)
        languageChip.text = Language.nativeName(cmd.language)

        cmd.instruction?.let { instructionText.text = it }
        renderChoices(cmd.choices)

        if (cmd.expanded != expanded) setExpanded(cmd.expanded)

        // Keep the cursor's launch point under the pill, so it always flies out
        // of the assistant rather than appearing from nowhere.
        publishHomePosition()

        val h = cmd.highlight
        if (h == null) {
            highlightView.clear()
        } else {
            highlightView.show(h.left, h.top, h.right, h.bottom, h.shape, h.pulse)
        }
    }

    /**
     * One button per option, up to the three the row holds. More installed ride
     * apps than that is not a case worth a scrolling list on a demo overlay —
     * the first three in a curated order are the ones anyone will pick.
     */
    private fun renderChoices(choices: List<String>) {
        choiceRow.visibility = if (choices.isEmpty()) View.GONE else View.VISIBLE
        choiceButtons.forEachIndexed { i, button ->
            val label = choices.getOrNull(i)
            button.visibility = if (label == null) View.GONE else View.VISIBLE
            if (label != null) button.text = label
        }
    }

    /** Screen position of the pill, handed to the cursor layer as its home. */
    private fun publishHomePosition() {
        val loc = IntArray(2)
        pillRoot.findViewById<View>(R.id.pill_row).getLocationOnScreen(loc)
        val row = pillRoot.findViewById<View>(R.id.pill_row)
        highlightView.setHome(
            loc[0] + row.width / 2f,
            loc[1] + row.height / 2f,
        )
    }

    /** Debug panel content. Visibility stays user-controlled (long-press the pill). */
    fun updateDebug(text: String) {
        debugPanel.text = text
    }

    private fun toggleExpanded() = setExpanded(!expanded)

    private fun setExpanded(value: Boolean) {
        expanded = value
        cardBody.visibility = if (value) View.VISIBLE else View.GONE
    }

    // --- Foreground plumbing --------------------------------------------------

    private fun startAsForeground() {
        val channelId = "saathi_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "ScreenSaathi", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif: Notification =
            androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("ScreenSaathi is ready")
                .setContentText("Tap the floating pill to start.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()

        val hasMic = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Only claim the microphone FGS type when the permission is actually
        // held — otherwise Android 14+ throws at startForeground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.dispose()
        runCatching { wm.removeView(highlightView) }
        runCatching { wm.removeView(pillRoot) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIF_ID = 42

        const val ACTION_RUN_TASK = "com.screensaathi.RUN_TASK"
        const val ACTION_CHOOSE = "com.screensaathi.CHOOSE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_CHOICE = "choice"

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Pick option [index] from the card, as if the button were tapped. */
        fun choose(context: Context, index: Int) {
            val i = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CHOOSE)
                .putExtra(EXTRA_CHOICE, index)
            ContextCompat.startForegroundService(context, i)
        }

        /** Start the overlay if needed, then run [taskId] without speech. */
        fun runTask(context: Context, taskId: String, language: String) {
            val i = Intent(context, OverlayService::class.java)
                .setAction(ACTION_RUN_TASK)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_LANGUAGE, language)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
