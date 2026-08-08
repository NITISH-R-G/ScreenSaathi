package com.screensaathi.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.screensaathi.AppLauncher
import androidx.core.content.ContextCompat
import com.screensaathi.ScreenReaderService
import com.screensaathi.overlay.HighlightBounds
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillState
import com.screensaathi.sarvam.AudioPlayer
import com.screensaathi.sarvam.Language
import com.screensaathi.sarvam.Sarvam
import com.screensaathi.sarvam.Spoken
import com.screensaathi.sarvam.SarvamPlanner
import com.screensaathi.sarvam.SarvamStt
import com.screensaathi.sarvam.SarvamTts
import com.screensaathi.sarvam.WavRecorder
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.task.GuidedTask
import com.screensaathi.task.RideApps
import com.screensaathi.task.StepKind
import com.screensaathi.task.TaskRepository
import com.screensaathi.task.TaskStep
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The orchestration layer. Turns UI taps into OverlayCommands, driving the
 * deterministic StepEngine plus (when a key is present and calls succeed) the
 * Sarvam voice loop: Saaras STT -> Sarvam-105B planner -> highlight -> Bulbul TTS.
 *
 * Every network step has a deterministic fallback. If the key is missing or any
 * call fails, mic-tap starts the task and Next advances in order — the overlay
 * is never left frozen.
 *
 * ## Turns
 *
 * Every user action opens a numbered *turn*. Background work captures its turn
 * number and drops itself if a newer turn has started since. Without this, a
 * mic tap during the ~1.5 s "Thinking…" window starts a fresh recording, then
 * the older in-flight plan lands and repaints the pill to "Guiding you" while
 * the user is still speaking. The pill has to tell the truth about its state,
 * so stale work is discarded rather than rendered.
 */
class SessionController(
    private val context: Context,
    private val render: (OverlayCommand) -> Unit,
) {

    fun interface DebugSink { fun update(text: String) }
    var debugSink: DebugSink? = null

    private val worker = HandlerThread("saathi-session").apply { start() }
    private val bg = Handler(worker.looper)

    /**
     * Recorder start/stop run here and nowhere else, so they are serialized in
     * tap order. Sharing [bg] with the network work meant a re-tap during the
     * "Thinking…" window could call start() before the previous stop() had run
     * off the back of a 1.5 s STT call — start() saw the recorder still busy,
     * returned false, and the turn silently fell back to the offline path.
     */
    private val captureWorker = HandlerThread("saathi-capture").apply { start() }
    private val capture = Handler(captureWorker.looper)

    /**
     * Speech synthesis and playback. Separate from [bg] so Bulbul's ~1.2 s —
     * the slowest layer in the loop — never sits in front of the next
     * highlight resolution. The visual path is the demo; it must never queue
     * behind audio.
     */
    private val speechWorker = HandlerThread("saathi-speech").apply { start() }
    private val speech = Handler(speechWorker.looper)

    private val tasks: TaskRepository = TaskRepository.load(context)
    private var engine: StepEngine? = null

    private val recorder = WavRecorder()
    private val stt = SarvamStt()
    private val planner = SarvamPlanner(context)
    private val tts = SarvamTts()
    private val player = AudioPlayer(context)

    @Volatile private var isRecording = false

    /**
     * The language the user last spoke, as detected by Saaras. Everything the
     * assistant says is chosen for this. It starts at [Language.DEFAULT] rather
     * than a guessed "hi-IN": before anyone has spoken we have no evidence, and
     * English is the one language every authored string exists in.
     */
    @Volatile private var lastLanguage = Language.DEFAULT

    /** Set by [onStopTapped]; cleared as soon as the user engages again. */
    @Volatile private var stopped = false

    /** Turn that owns the in-progress capture, so stop() reads the right file. */
    @Volatile private var recordingTurn = -1

    /**
     * Monotonic turn counter. Bumped by every user action; background work that
     * finds itself outdated renders nothing.
     */
    private val turnId = AtomicInteger(0)

    private fun newTurn(): Int {
        // Diagnostics belong to one turn. Without this the previous turn's note
        // ("stopped by user at step amount") sits under the next turn's
        // reading and describes something that is no longer happening.
        debug = VoiceDebug()
        return turnId.incrementAndGet()
    }
    private fun isCurrent(turn: Int): Boolean = turnId.get() == turn

    /** Render only if [turn] is still the live one. */
    private fun renderIfCurrent(turn: Int, cmd: OverlayCommand) {
        if (isCurrent(turn)) render(cmd)
    }

    /**
     * The highlight currently on screen. Every subsequent render must carry it
     * forward: OverlayCommand.highlight defaults to null, so a state-only
     * update (e.g. flipping to SPEAKING) would otherwise erase the ring the
     * user is being pointed at.
     */
    @Volatile private var currentHighlight: HighlightBounds? = null

    /**
     * Options currently on the card, carried forward for the same reason as
     * [currentHighlight]: OverlayCommand.choices defaults to empty, so the
     * SPEAKING render issued while the question is being read aloud would
     * otherwise wipe the very buttons the user is being asked to press.
     */
    @Volatile private var currentChoices: List<String> = emptyList()

    /** Diagnostics for the live turn, shown by long-pressing the pill. */
    @Volatile private var debug = VoiceDebug()

    private fun publishDebug(turn: Int, update: (VoiceDebug) -> VoiceDebug) {
        if (!isCurrent(turn)) return
        val next = update(debug)
        debug = next
        Log.d(TAG, next.toPanel().replace("\n", " | "))
        debugSink?.update(next.toPanel())
    }

    // --- UI events (main thread) ----------------------------------------------

    fun onMicTapped() {
        if (!isRecording) startListening() else stopAndProcess()
    }

    fun onNextTapped() {
        // Opening a turn here invalidates any plan still in flight, so a slow
        // planner response cannot yank the user back a step after they advance.
        val turn = newTurn()
        abandonRecording(turn)
        stopped = false
        val e = engine
        if (e == null) {
            startDefaultTask(turn)
            return
        }
        if (e.task.id == "open_ended") {
            bg.post {
                processOpenEndedNext(turn, e.task.title, null)
            }
            return
        }
        if (e.isOnLastStep) {
            currentHighlight = null
            val done = Phrases.get(Phrases.Key.ALL_DONE, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                instruction = done.text, highlight = null))
            bg.post { speak(done, turn) }
            return
        }
        e.advance()
        presentCurrentStep(e, turn, speak = true)
    }

    /**
     * Stop cleanly: silence speech, drop any in-flight turn, clear the ring,
     * and keep the step position so the next mic tap resumes rather than
     * restarts. The user must be able to call it off mid-sentence without
     * leaving the pill stuck in "Listening…" or the ring orphaned on screen.
     */
    fun onStopTapped() {
        val turn = newTurn()
        abandonRecording(turn)
        stopped = true
        player.stop()
        currentHighlight = null
        currentChoices = emptyList()
        // Drop the app pin: after a stop the user may pick a different app, and
        // a stale pin would make guidance wait forever for the old one.
        chosenPackage = ""
        val phrase = Phrases.get(Phrases.Key.STOPPED, lastLanguage)
        render(OverlayCommand(PillState.IDLE, expanded = true,
            instruction = phrase.text, highlight = null))
        publishDebug(turn) { it.copy(note = "stopped by user at step ${engine?.currentStep?.id ?: "-"}") }
    }

    /** True once the user has stopped and before they resume. */
    val isStopped: Boolean get() = stopped

    /**
     * Run a task by id with no speech involved.
     *
     * This is the rehearsal path: it exercises the identical engine, overlay and
     * cursor code as a spoken request, minus STT. Useful on stage when the room
     * is too loud to trust a microphone, and the only way to regression-test the
     * flow without a human voice. [language] lets a rehearsal show the Hindi or
     * Tamil rendering without anyone having to speak it.
     */
    fun startTaskById(taskId: String, language: String = lastLanguage) {
        val turn = newTurn()
        abandonRecording(turn)
        stopped = false
        chosenPackage = ""
        lastLanguage = Language.normalize(language)
        val task = tasks.byId(taskId)
        if (task == null) {
            val none = Phrases.get(Phrases.Key.NO_TASKS, lastLanguage)
            render(OverlayCommand(PillState.ERROR, expanded = true,
                instruction = none.text, language = none.language))
            return
        }
        val e = StepEngine(task); engine = e
        publishDebug(turn) { it.copy(intent = task.id, language = lastLanguage, note = "rehearsal (no STT)") }
        presentCurrentStep(e, turn, speak = true)
    }

    private fun abandonRecording(turn: Int) {
        if (!isRecording) return
        // The capture belongs to a turn the user has just walked away from.
        isRecording = false
        capture.post { recorder.stop(); purgeStaleCaptures(turn) }
    }

    // --- Voice loop -----------------------------------------------------------

    private fun startListening() {
        val turn = newTurn()
        stopped = false

        // A denied microphone used to fail silently: recorder.start() returned
        // false and the app quietly ran the deterministic task, so the user was
        // never told why the voice loop does nothing. Say it, then keep guiding.
        if (!hasMicPermission()) {
            startDefaultTask(
                turn,
                lead = Phrases.get(Phrases.Key.MIC_OFF, lastLanguage),
                note = "RECORD_AUDIO denied",
            )
            return
        }
        if (!Sarvam.hasKey()) {
            // No key: skip STT entirely, just run the task deterministically.
            startDefaultTask(turn, note = "no Sarvam key — deterministic path")
            return
        }
        // Flip the pill first: the user must see "Listening…" the instant they
        // tap, not after the capture thread has drained a pending stop().
        isRecording = true
        recordingTurn = turn
        currentHighlight = null
        renderIfCurrent(turn, OverlayCommand(PillState.LISTENING, expanded = true,
            instruction = Phrases.get(Phrases.Key.LISTENING, lastLanguage).text,
            language = lastLanguage))

        capture.post {
            purgeStaleCaptures(turn)
            if (recorder.start(inputFileFor(turn))) return@post
            isRecording = false
            startDefaultTask(turn, note = "recorder failed to start")
        }
    }

    private fun stopAndProcess() {
        isRecording = false
        // The capture's own turn, not whatever is current — they diverge if
        // anything opened a turn while the user was still speaking.
        val turn = recordingTurn
        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = Phrases.get(Phrases.Key.THINKING, lastLanguage).text,
            language = lastLanguage))

        capture.post {
            recorder.stop()
            val heldMs = recorder.recordedMs
            bg.post { process(turn, heldMs) }
        }
    }

    /** Runs on [bg]. The whole network half of a voice turn. */
    private fun process(turn: Int, heldMs: Long) {
        val wav = inputFileFor(turn)

        // A double-tapped mic leaves a header-only WAV. Sending it costs a full
        // round trip to be told nothing was said, which reads on stage as the
        // app hanging.
        if (heldMs < MIN_SPEECH_MS) {
            publishDebug(turn) { it.copy(note = "too short (${heldMs}ms) — no STT call") }
            wav.delete()
            fallbackAfterFailedSpeech(turn, Phrases.Key.HOLD_LONGER)
            return
        }

        // Timed around the network call alone. Measuring from before
        // recorder.stop() folded in its writer-thread join (up to 1.5 s) and
        // made STT look far over budget when it was not.
        val t0 = SystemClock.uptimeMillis()
        val sttResult = stt.transcribe(wav)
        val sttMs = SystemClock.uptimeMillis() - t0
        wav.delete()

        if (!isCurrent(turn)) return

        if (sttResult == null || sttResult.transcript.isBlank()) {
            publishDebug(turn) { it.copy(sttMs = sttMs, note = "STT returned nothing") }
            fallbackAfterFailedSpeech(turn, Phrases.Key.DIDNT_CATCH)
            return
        }
        // Everything the assistant says from here on is chosen for this.
        lastLanguage = sttResult.language
        publishDebug(turn) {
            it.copy(heard = sttResult.transcript, sttMs = sttMs, language = lastLanguage)
        }

        // Persistent memory: if we're waiting for an app choice, see if they spoke it.
        val activeApps = offeredApps
        if (activeApps.isNotEmpty()) {
            val transcriptLower = sttResult.transcript.lowercase()
            val matchIdx = activeApps.indexOfFirst { app ->
                transcriptLower.contains(app.label.lowercase())
            }
            if (matchIdx >= 0) {
                publishDebug(turn) { it.copy(note = "matched spoken choice: ${activeApps[matchIdx].label}") }
                offeredApps = emptyList() // Clear so we don't trap future turns
                // onChoiceTapped starts a new turn internally, so we don't need to post it to bg
                // since we are already on bg, but it's safe to call directly.
                onChoiceTapped(matchIdx)
                return
            }
        }

        val task = pickTask(sttResult.transcript)
        // Clear offeredApps if we are starting/continuing a task to prevent stale state
        offeredApps = emptyList()

        val snap = ScreenReaderService.instance?.snapshot() ?: ScreenSnapshot.EMPTY
        if (task.id == "open_ended") {
            processOpenEndedNext(turn, task.title, snap)
            return
        }

        val e = engine?.takeIf { it.task.id == task.id } ?: StepEngine(task).also { engine = it }

        val tp0 = SystemClock.uptimeMillis()
        val plan = planner.plan(
            transcript = sttResult.transcript,
            task = task,
            screen = snap,
            spokenLanguage = lastLanguage,
            currentStepId = e.currentStep.id,
        )
        val planMs = SystemClock.uptimeMillis() - tp0

        if (!isCurrent(turn)) return

        if (plan != null && plan.confidence >= CONFIDENCE_FLOOR && e.jumpTo(plan.step)) {
            publishDebug(turn) {
                it.copy(
                    intent = plan.intent, step = plan.step,
                    wantResourceId = plan.targetResourceId,
                    planMs = planMs, confidence = plan.confidence,
                    language = plan.language,
                )
            }
            presentStep(e, plan.spoken, turn, speak = true)
        } else {
            // Planner unsure or unavailable — deterministic order wins.
            publishDebug(turn) {
                it.copy(
                    intent = task.id, step = e.currentStep.id,
                    wantResourceId = e.currentStep.resourceId,
                    planMs = planMs, confidence = plan?.confidence ?: -1.0,
                    note = if (plan == null) "planner unavailable" else "below confidence floor",
                )
            }
            presentCurrentStep(e, turn, speak = true)
        }
    }

    /**
     * Speech failed. Keep the user moving in their own language rather than
     * dead-ending, and resume where they were if a task is already running.
     */
    private fun fallbackAfterFailedSpeech(turn: Int, key: Phrases.Key) {
        val e = engine
        if (e != null) {
            presentCurrentStep(e, turn, speak = true)
        } else {
            startDefaultTask(turn, lead = Phrases.get(key, lastLanguage))
        }
    }

    private fun processOpenEndedNext(turn: Int, intent: String, snapshot: ScreenSnapshot?) {
        val snap = snapshot ?: ScreenReaderService.instance?.snapshot() ?: ScreenSnapshot.EMPTY
        
        renderIfCurrent(turn, OverlayCommand(PillState.THINKING, expanded = true,
            instruction = Phrases.get(Phrases.Key.THINKING, lastLanguage).text,
            language = lastLanguage))

        val tp0 = SystemClock.uptimeMillis()
        val plan = planner.planOpenEnded(intent, snap, lastLanguage)
        val planMs = SystemClock.uptimeMillis() - tp0
        
        if (!isCurrent(turn)) return
        
        if (plan != null) {
            if (plan.isDone || plan.actionType == "answer") {
                currentHighlight = null
                val textToSpeak = if (plan.instruction.isNotBlank()) {
                    Spoken(plan.instruction, plan.language)
                } else {
                    Phrases.get(Phrases.Key.ALL_DONE, lastLanguage)
                }
                renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = true,
                    instruction = textToSpeak.text, highlight = null))
                speech.post { speak(textToSpeak, turn) }
                return
            }
            
            // Create a single step guided task so we can reuse the presentation logic
            val openEndedStep = TaskStep(
                id = "open_ended_step",
                resourceId = plan.targetResourceId,
                instruction = plan.instruction,
                textAny = if (plan.targetText.isNotEmpty()) listOf(plan.targetText) else emptyList(),
                actionType = plan.actionType
            )
            val newTask = GuidedTask(1, "open_ended", intent, emptyList(), listOf(openEndedStep))
            val e = StepEngine(newTask)
            engine = e
            
            publishDebug(turn) {
                it.copy(
                    intent = intent, step = "open_ended_step",
                    wantResourceId = plan.targetResourceId.ifEmpty { "text:${plan.targetText}" },
                    planMs = planMs, confidence = plan.confidence,
                    language = plan.language,
                )
            }
            presentCurrentStep(e, turn, speak = true)
        } else {
            publishDebug(turn) {
                it.copy(note = "planner unavailable or failed open-ended")
            }
            fallbackAfterFailedSpeech(turn, Phrases.Key.DIDNT_CATCH)
        }
    }

    // --- Deterministic path ---------------------------------------------------

    /**
     * Start (or restart) the default task with no network involved. [lead] lets
     * the caller explain *why* we are on this path instead of silently
     * pretending the voice loop ran.
     */
    private fun startDefaultTask(turn: Int, lead: Spoken? = null, note: String? = null) {
        note?.let { n -> publishDebug(turn) { it.copy(note = n) } }
        val task = tasks.byId(DEFAULT_TASK) ?: tasks.tasks.firstOrNull()
        if (task == null) {
            val none = Phrases.get(Phrases.Key.NO_TASKS, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                instruction = none.text, language = none.language))
            return
        }
        val e = StepEngine(task); engine = e
        presentStep(e, lead ?: e.currentStep.spokenFor(lastLanguage), turn, speak = true)
    }

    private fun pickTask(transcript: String): GuidedTask {
        return GuidedTask(1, "open_ended", transcript, emptyList(), emptyList())
    }

    private fun presentCurrentStep(e: StepEngine, turn: Int, speak: Boolean) {
        presentStep(e, e.currentStep.spokenFor(lastLanguage), turn, speak)
    }

    /**
     * Show instruction immediately, resolve the highlight off-thread, speak the
     * instruction. Rendering is always pushed back through the callback, and
     * always gated on [turn] still being live.
     */
    private fun presentStep(e: StepEngine, instruction: Spoken, turn: Int, speak: Boolean) {
        val step = e.currentStep

        // A choose-app step has no on-screen element to point at — it asks a
        // question. Render the options and wait for a tap.
        if (step.kind == StepKind.CHOOSE_APP) {
            presentAppChoice(instruction, turn, speak)
            return
        }

        // A guiding step has no options; clear any left over from the question.
        currentChoices = emptyList()
        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
            instruction = instruction.text, language = instruction.language, highlight = null))

        // Speech is kicked off in parallel with bounds resolution, on its own
        // thread. Bulbul's ~1.2 s is the slowest layer we have; running it here
        // meant the *next* interaction's highlight queued behind it, so an
        // impatient second tap looked like a frozen pill.
        if (speak) speech.post { speak(instruction, turn) }

        bg.post {
            if (!isCurrent(turn)) return@post
            // The package is only known once the user has picked an app, so it
            // is pinned here rather than in the task JSON.
            val target = if (chosenPackage.isNotEmpty() && step.expectPackage.isEmpty()) {
                step.copy(expectPackage = chosenPackage)
            } else {
                step
            }
            val bounds = resolveBounds(target)
            val snap = ScreenReaderService.instance?.snapshot()
            val hl = bounds?.let {
                HighlightBounds(it.left, it.top, it.right, it.bottom, step.highlight.shape, step.highlight.pulse)
            }
            publishDebug(turn) {
                it.copy(
                    wantResourceId = step.resourceId.ifEmpty {
                        "text:" + step.textAny.firstOrNull().orEmpty()
                    },
                    readerBound = ScreenReaderService.instance != null,
                    screenPackage = snap?.packageName ?: "-",
                    settled = snap?.settled,
                    elementCount = snap?.elements?.size ?: 0,
                    visibleIds = snap?.elements
                        ?.filter { el -> el.resourceId.isNotEmpty() }
                        ?.joinToString(",") { el -> el.resourceId } ?: "-",
                    bounds = bounds?.toShortString() ?: "NOT FOUND",
                )
            }
            if (!isCurrent(turn)) return@post
            currentHighlight = hl
            render(OverlayCommand(PillState.GUIDING, expanded = true,
                instruction = instruction.text, language = instruction.language, highlight = hl))

            if (step.actionType == "click") {
                bg.postDelayed({
                    if (!isCurrent(turn) || stopped) return@postDelayed
                    ScreenReaderService.instance?.performClick(step.resourceId, step.textAny)
                    
                    bg.postDelayed({
                        if (!isCurrent(turn) || stopped) return@postDelayed
                        onNextTapped()
                    }, 1000)
                }, 300)
            } else if (step.actionType == "type_text") {
                bg.postDelayed({
                    if (!isCurrent(turn) || stopped) return@postDelayed
                    ScreenReaderService.instance?.performSetText(step.resourceId, step.textAny, step.actionPayload)
                    
                    bg.postDelayed({
                        if (!isCurrent(turn) || stopped) return@postDelayed
                        onNextTapped()
                    }, 1000)
                }, 300)
            } else if (step.actionType == "launch_app") {
                bg.postDelayed({
                    if (!isCurrent(turn) || stopped) return@postDelayed
                    AppLauncher.launchApp(context, step.actionPayload)
                    
                    bg.postDelayed({
                        if (!isCurrent(turn) || stopped) return@postDelayed
                        onNextTapped()
                    }, 1000)
                }, 300)
            }
        }
    }

    /** Runs on [speech]. Never on the thread that resolves the highlight. */
    private fun speak(spoken: Spoken, turn: Int) {
        if (!Sarvam.hasKey()) return
        val bytes = tts.synthesize(spoken) ?: return
        if (!isCurrent(turn) || stopped) return
        // Carry currentHighlight through both renders — the ring must survive
        // the speaking state, not blink out while the instruction is read.
        player.play(
            bytes,
            onStart = {
                renderIfCurrent(turn, OverlayCommand(PillState.SPEAKING, expanded = true,
                    instruction = spoken.text, language = spoken.language,
                    highlight = currentHighlight, choices = currentChoices))
            },
            onDone = {
                renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                    instruction = spoken.text, language = spoken.language,
                    highlight = currentHighlight, choices = currentChoices))
            },
        )
    }

    /**
     * Poll the live tree until the step's target shows up.
     *
     * Matches a resource id when the step has one (our own demo screen) and
     * visible text when it does not (Uber, Ola, Rapido, whose view ids are
     * obfuscated). When the step names a package, wait for that package to be
     * in front first — a ride app takes a second or two to draw, and without
     * this the ring lands on whatever was still on screen.
     */
    private fun resolveBounds(step: TaskStep): Rect? {
        val attempts = if (step.expectPackage.isNotEmpty()) CROSS_APP_ATTEMPTS else RESOLVE_ATTEMPTS
        repeat(attempts) {
            val snap = ScreenReaderService.instance?.snapshot()
            if (snap != null && step.expectPackage.let { it.isEmpty() || snap.packageName == it }) {
                val bounds = when {
                    step.resourceId.isNotEmpty() -> snap.boundsForResourceId(step.resourceId)
                    else -> null
                } ?: snap.boundsForText(step.textAny)
                if (bounds != null) return bounds
            }
            try { Thread.sleep(RESOLVE_INTERVAL_MS) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    // --- App choice -----------------------------------------------------------

    /** The apps currently offered, parallel to OverlayCommand.choices. */
    @Volatile private var offeredApps: List<RideApps.Installed> = emptyList()

    private fun presentAppChoice(instruction: Spoken, turn: Int, speak: Boolean) {
        val apps = RideApps.installed(context)
        offeredApps = apps
        currentHighlight = null
        currentChoices = apps.map { it.label }

        if (apps.isEmpty()) {
            val none = Phrases.get(Phrases.Key.NO_RIDE_APPS, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                instruction = none.text, language = none.language))
            if (speak) speech.post { speak(none, turn) }
            return
        }

        publishDebug(turn) { it.copy(note = "offering ${apps.joinToString(",") { a -> a.label }}") }
        renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
            instruction = instruction.text, language = instruction.language,
            highlight = null, choices = apps.map { it.label }))
        if (speak) speech.post { speak(instruction, turn) }
    }

    /**
     * The user picked a ride app. Launch it, then guide inside it — the next
     * step is pinned to that package so the ring waits for the app to draw.
     */
    fun onChoiceTapped(index: Int) {
        val apps = offeredApps
        val app = apps.getOrNull(index) ?: return
        val turn = newTurn()
        stopped = false

        val opening = Phrases.get(Phrases.Key.OPENING_APP, lastLanguage)
        val spoken = Spoken(String.format(opening.text, app.label), opening.language)
        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = spoken.text, language = spoken.language))

        if (!RideApps.launch(context, app.packageName)) {
            val failed = Phrases.get(Phrases.Key.APP_WONT_OPEN, lastLanguage)
            render(OverlayCommand(PillState.ERROR, expanded = true,
                instruction = failed.text, language = failed.language,
                choices = apps.map { it.label }))
            speech.post { speak(failed, turn) }
            return
        }

        speech.post { speak(spoken, turn) }

        val e = engine ?: return
        if (!e.advance()) return
        // Pin the remaining steps to the app we just opened, so guidance waits
        // for it instead of pointing at our own screen mid-launch.
        chosenPackage = app.packageName
        bg.post {
            if (!isCurrent(turn)) return@post
            presentCurrentStep(e, turn, speak = true)
        }
    }

    @Volatile private var chosenPackage: String = ""

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One capture file per turn. A shared filename let a new recording truncate
     * the very file the previous turn's STT upload was still streaming.
     */
    private fun inputFileFor(turn: Int) = File(context.cacheDir, "$CAPTURE_PREFIX$turn.wav")

    /** Abandoned turns leave their capture behind; don't grow the cache all demo. */
    private fun purgeStaleCaptures(keep: Int) {
        val keepName = inputFileFor(keep).name
        context.cacheDir.listFiles { f ->
            f.name.startsWith(CAPTURE_PREFIX) && f.name != keepName
        }?.forEach { it.delete() }
    }

    fun dispose() {
        turnId.incrementAndGet() // invalidate anything still in flight
        player.stop()
        recorder.stop()
        worker.quitSafely()
        captureWorker.quitSafely()
        speechWorker.quitSafely()
    }

    companion object {
        private const val TAG = "SessionController"
        private const val RESOLVE_ATTEMPTS = 12
        /** A cold ride app can take a couple of seconds to draw its first screen. */
        private const val CROSS_APP_ATTEMPTS = 40
        private const val RESOLVE_INTERVAL_MS = 120L
        private const val CONFIDENCE_FLOOR = 0.5
        private const val DEFAULT_TASK = "pay_bill"
        private const val CAPTURE_PREFIX = "saathi_input_"

        /** Below this the clip is a mis-tap, not speech. */
        private const val MIN_SPEECH_MS = 400L
    }
}
