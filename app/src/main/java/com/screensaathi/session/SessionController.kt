package com.screensaathi.session

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.screensaathi.ScreenReaderService
import com.screensaathi.overlay.HighlightBounds
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillState
import com.screensaathi.sarvam.AudioPlayer
import com.screensaathi.sarvam.Sarvam
import com.screensaathi.sarvam.SarvamPlanner
import com.screensaathi.sarvam.SarvamStt
import com.screensaathi.sarvam.SarvamTts
import com.screensaathi.sarvam.WavRecorder
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskRepository
import java.io.File

/**
 * The orchestration layer. Turns UI taps into OverlayCommands, driving the
 * deterministic StepEngine plus (when a key is present and calls succeed) the
 * Sarvam voice loop: Saaras STT -> Sarvam-30B planner -> highlight -> Bulbul TTS.
 *
 * Every network step has a deterministic fallback. If the key is missing or any
 * call fails, mic-tap starts the task and Next advances in order — the overlay
 * is never left frozen.
 */
class SessionController(
    private val context: Context,
    private val render: (OverlayCommand) -> Unit,
) {

    fun interface DebugSink { fun update(text: String) }
    var debugSink: DebugSink? = null

    private val worker = HandlerThread("saathi-session").apply { start() }
    private val bg = Handler(worker.looper)

    private val tasks: TaskRepository = TaskRepository.load(context)
    private var engine: StepEngine? = null

    private val recorder = WavRecorder()
    private val stt = SarvamStt()
    private val planner = SarvamPlanner(context)
    private val tts = SarvamTts()
    private val player = AudioPlayer(context)

    @Volatile private var isRecording = false
    @Volatile private var lastLanguage = "hi-IN"

    /**
     * The highlight currently on screen. Every subsequent render must carry it
     * forward: OverlayCommand.highlight defaults to null, so a state-only
     * update (e.g. flipping to SPEAKING) would otherwise erase the ring the
     * user is being pointed at.
     */
    @Volatile private var currentHighlight: HighlightBounds? = null

    // --- UI events (main thread) ----------------------------------------------

    fun onMicTapped() {
        if (!isRecording) startListening() else stopAndProcess()
    }

    fun onNextTapped() {
        val e = engine
        if (e == null) {
            // No task yet — start the default one deterministically.
            startDefaultTask()
            return
        }
        if (e.isOnLastStep) {
            render(OverlayCommand(PillState.IDLE, expanded = true,
                instruction = "That's the last step — you're all done!", highlight = null))
            return
        }
        e.advance()
        presentCurrentStep(e, speak = true)
    }

    // --- Voice loop -----------------------------------------------------------

    private fun startListening() {
        if (!Sarvam.hasKey()) {
            // No key: skip STT entirely, just run the task deterministically.
            startDefaultTask()
            return
        }
        val f = File(context.cacheDir, "saathi_input.wav")
        val ok = recorder.start(f)
        if (!ok) {
            startDefaultTask()
            return
        }
        isRecording = true
        currentHighlight = null
        render(OverlayCommand(PillState.LISTENING, expanded = true,
            instruction = "Listening… tap the mic again when you're done."))
    }

    private fun stopAndProcess() {
        isRecording = false
        render(OverlayCommand(PillState.THINKING, expanded = true, instruction = "One moment…"))
        bg.post {
            val t0 = SystemClock.uptimeMillis()
            recorder.stop()
            val wav = File(context.cacheDir, "saathi_input.wav")

            val sttResult = stt.transcribe(wav)
            val sttMs = SystemClock.uptimeMillis() - t0
            if (sttResult == null || sttResult.transcript.isBlank()) {
                // Couldn't hear — fall back without dead-ending.
                fallbackAfterFailedSpeech()
                return@post
            }
            sttResult.languageCode?.let { lastLanguage = it }

            val task = pickTask(sttResult.transcript)
            if (task == null) {
                fallbackAfterFailedSpeech()
                return@post
            }
            val e = engine?.takeIf { it.task.id == task.id } ?: StepEngine(task).also { engine = it }

            val snap = ScreenReaderService.instance?.snapshot() ?: ScreenSnapshot.EMPTY
            val tp0 = SystemClock.uptimeMillis()
            val plan = planner.plan(sttResult.transcript, task, snap)
            val planMs = SystemClock.uptimeMillis() - tp0

            if (plan != null && plan.confidence >= CONFIDENCE_FLOOR && e.jumpTo(plan.step)) {
                pushDebug(sttResult.transcript, plan.intent, plan.step, plan.targetResourceId, sttMs, planMs, plan.confidence)
                presentStep(e, plan.instruction, speak = true)
            } else {
                // Planner unsure or unavailable — deterministic order wins.
                pushDebug(sttResult.transcript, task.id, e.currentStep.id, e.currentStep.resourceId, sttMs, planMs, plan?.confidence ?: -1.0)
                presentCurrentStep(e, speak = true)
            }
        }
    }

    private fun fallbackAfterFailedSpeech() {
        val e = engine
        if (e != null) {
            presentCurrentStep(e, speak = true)
        } else {
            val task = tasks.byId("pay_bill") ?: tasks.tasks.firstOrNull()
            if (task == null) {
                render(OverlayCommand(PillState.ERROR, expanded = true,
                    instruction = "No tasks are installed."))
            } else {
                val ne = StepEngine(task); engine = ne
                presentStep(ne, "I didn't catch that — let's start here.", speak = true)
            }
        }
    }

    // --- Deterministic path ---------------------------------------------------

    private fun startDefaultTask() {
        val task = tasks.byId("pay_bill") ?: tasks.tasks.firstOrNull()
        if (task == null) {
            render(OverlayCommand(PillState.ERROR, expanded = true,
                instruction = "No tasks are installed."))
            return
        }
        val e = StepEngine(task); engine = e
        presentCurrentStep(e, speak = true)
    }

    private fun pickTask(transcript: String): GuidedTask? =
        tasks.matchByUtterance(transcript) ?: tasks.byId("pay_bill") ?: tasks.tasks.firstOrNull()

    private fun presentCurrentStep(e: StepEngine, speak: Boolean) {
        presentStep(e, e.currentStep.instruction, speak)
    }

    /**
     * Show instruction immediately, resolve the highlight off-thread, speak the
     * instruction. Rendering is always pushed back through the callback.
     */
    private fun presentStep(e: StepEngine, instruction: String, speak: Boolean) {
        val step = e.currentStep
        render(OverlayCommand(PillState.GUIDING, expanded = true, instruction = instruction, highlight = null))

        bg.post {
            val snap = ScreenReaderService.instance?.snapshot()
            val bounds = resolveBounds(step.resourceId)
            val hl = bounds?.let {
                HighlightBounds(it.left, it.top, it.right, it.bottom, step.highlight.shape, step.highlight.pulse)
            }
            // Diagnostic: what did the reader actually see?
            val diag = buildString {
                append("want: ").append(step.resourceId).append("\n")
                append("reader: ").append(if (ScreenReaderService.instance == null) "NULL" else "ok").append("\n")
                append("pkg: ").append(snap?.packageName ?: "-").append(" settled=").append(snap?.settled).append("\n")
                append("elems: ").append(snap?.elements?.size ?: 0).append("\n")
                append("ids: ").append(snap?.elements?.filter { it.resourceId.isNotEmpty() }?.joinToString(",") { it.resourceId }?.take(80) ?: "-").append("\n")
                append("bounds: ").append(bounds?.toShortString() ?: "NOT FOUND")
            }
            debugSink?.update(diag)
            currentHighlight = hl
            render(OverlayCommand(PillState.GUIDING, expanded = true, instruction = instruction, highlight = hl))
            if (speak) speak(instruction)
        }
    }

    private fun speak(text: String) {
        if (!Sarvam.hasKey()) return
        val bytes = tts.synthesize(text, languageCode = lastLanguage) ?: return
        // Carry currentHighlight through both renders — the ring must survive
        // the speaking state, not blink out while the instruction is read.
        player.play(
            bytes,
            onStart = {
                render(OverlayCommand(PillState.SPEAKING, expanded = true,
                    instruction = text, highlight = currentHighlight))
            },
            onDone = {
                render(OverlayCommand(PillState.GUIDING, expanded = true,
                    instruction = text, highlight = currentHighlight))
            },
        )
    }

    private fun resolveBounds(resourceId: String): Rect? {
        repeat(RESOLVE_ATTEMPTS) {
            val snap = ScreenReaderService.instance?.snapshot()
            val bounds = snap?.boundsForResourceId(resourceId)
            if (bounds != null) return bounds
            try { Thread.sleep(RESOLVE_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private fun pushDebug(
        transcript: String, intent: String, step: String, rid: String,
        sttMs: Long, planMs: Long, confidence: Double,
    ) {
        val text = buildString {
            append("heard: ").append(transcript.take(40)).append("\n")
            append("intent: ").append(intent).append("  step: ").append(step).append("\n")
            append("target: ").append(rid).append("\n")
            append("stt: ").append(sttMs).append("ms  plan: ").append(planMs).append("ms\n")
            append("conf: ").append(if (confidence < 0) "fallback" else String.format("%.2f", confidence))
        }
        Log.d(TAG, text.replace("\n", " | "))
        debugSink?.update(text)
    }

    fun dispose() {
        player.stop()
        recorder.stop()
        worker.quitSafely()
    }

    companion object {
        private const val TAG = "SessionController"
        private const val RESOLVE_ATTEMPTS = 12
        private const val RESOLVE_INTERVAL_MS = 120L
        private const val CONFIDENCE_FLOOR = 0.5
    }
}
