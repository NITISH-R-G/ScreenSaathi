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
import com.screensaathi.device.DeviceContextProvider
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
import com.screensaathi.screen.TargetResolver
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
    private val clearHighlightInstant: () -> Unit,
) {

    init { instance = this }

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

    /**
     * Live mic loudness, 0..1, for the voice waveform. Read-only pass-through
     * to the one recorder that owns the microphone — the overlay never opens
     * its own capture.
     */
    fun micLevel(): Float = if (isRecording) recorder.level else 0f

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
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        currentChoices = emptyList()
        // Drop the app pin: after a stop the user may pick a different app, and
        // a stale pin would make guidance wait forever for the old one.
        chosenPackage = ""
        lastUserRequest = ""
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
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
        // A rehearsal is a fresh intent. Never let an earlier spoken request or
        // highlight query trigger screen-change replanning in this task.
        lastUserRequest = ""
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
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
            // Without a key we cannot transcribe the recording, so do not
            // pretend that the user asked for the hardcoded demo task. Keep
            // the deterministic rehearsal buttons available and explain the
            // setup issue instead.
            val unavailable = Spoken(
                "Live voice is unavailable. Add a Sarvam API key, or use a rehearsal button.",
                "en-IN",
            )
            publishDebug(turn) { it.copy(note = "no Sarvam key — voice not started") }
            renderIfCurrent(turn, OverlayCommand(
                PillState.ERROR,
                expanded = true,
                instruction = unavailable.text,
                language = unavailable.language,
            ))
            speech.post { speak(unavailable, turn) }
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

        // Close the turn on our own clock as well as on a second tap.
        //
        // A pure toggle mic has two failure modes and both read to the user as
        // "it isn't listening": tap once and speak, and the capture never ends
        // so nothing is ever transcribed; tap twice quickly and the clip lands
        // under MIN_SPEECH_MS, which skips the STT call entirely and answers
        // with the spoken fallback. Measured on device: a 265 ms hold, no STT
        // request, assistant talks anyway.
        //
        // A ceiling makes one tap sufficient. Tapping again still stops early,
        // so this only ever adds an ending where there wasn't one.
        bg.postDelayed({
            if (isRecording && recordingTurn == turn) stopAndProcess()
        }, MAX_UTTERANCE_MS)
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
        // The authorisation layer must see what the user ACTUALLY said, not a
        // task title from the DSL. Without this, a guided task authorises
        // launches against a string like "Book a taxi", which names no app, so
        // every launch is refused for the wrong reason.
        lastUserRequest = sttResult.transcript
        // A fresh spoken request supersedes any earlier list selection.
        userSelectedPackage = ""
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

        // Two refusals the confidence floor cannot make on its own. Evaluated
        // before jumpTo() so a blocked plan never moves the user.
        // See docs/evals/FAILURES.md GAP-1, GAP-2.
        val blockedReason: String? = plan?.let { p ->
            when {
                SafetyGuard.blocksIrreversibleJump(task, p.step) { rid ->
                    snap.elementForResourceId(rid)?.text
                } -> "blocked: irreversible step with earlier fields still blank"

                SafetyGuard.blocksUngroundedPlan(
                    elementCount = snap.elements.size,
                    targetResolves = snap.boundsForResourceId(p.targetResourceId) != null ||
                        snap.boundsForText(task.stepById(p.step)?.textAny ?: emptyList()) != null,
                ) -> "blocked: screen shows no evidence for this plan"

                else -> null
            }
        }

        if (plan != null && blockedReason == null &&
            plan.confidence >= CONFIDENCE_FLOOR && e.jumpTo(plan.step)
        ) {
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
                    note = when {
                        plan == null -> "planner unavailable"
                        blockedReason != null -> blockedReason
                        else -> "below confidence floor"
                    },
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

            // The open-ended path can tap, type and launch apps. Until now the
            // only gate was `plan != null` above, so a confident plan acted
            // unconditionally. Validate against the screen BEFORE building the
            // step, and degrade to `guide` when the plan cannot be justified —
            // the assistant still points and speaks, it just does not act.
            val targetResolves = snap.boundsForResourceId(plan.targetResourceId) != null ||
                (plan.targetText.isNotEmpty() && snap.boundsForText(listOf(plan.targetText)) != null)
            val verdict = SafetyGuard.validateOpenEndedAction(
                userRequest = intent,
                actionType = plan.actionType,
                targetResourceId = plan.targetResourceId,
                targetText = plan.targetText,
                actionPayload = plan.actionPayload,
                elementCount = snap.elements.size,
                settled = snap.settled,
                targetResolves = targetResolves,
            )
            // A launch needs authorisation as well as validity. The evaluator
            // caught "find an app for booking a cab" being answered with a
            // confident launch of Uber on a phone that also had Ola: a valid
            // target, but not one the user chose. Naming no app authorises no
            // launch, which also covers content requests ("find my downloaded
            // PDF") — opening Files is not finding the file.
            val launchVerdict = if (plan.actionType == "launch_app") {
                SafetyGuard.validateLaunchAuthorization(
                    userRequest = intent,
                    resolution = DeviceContextProvider.snapshot(context)
                        .resolveApp(plan.actionPayload),
                    userSelectedPackage = userSelectedPackage.ifBlank { null },
                )
            } else {
                SafetyGuard.Verdict.Allow
            }

            val blockedReason = (verdict as? SafetyGuard.Verdict.Block)?.reason
                ?: (launchVerdict as? SafetyGuard.Verdict.Block)?.reason
            val safeActionType = if (blockedReason != null) "guide" else plan.actionType

            // Create a single step guided task so we can reuse the presentation logic
            val openEndedStep = TaskStep(
                id = "open_ended_step",
                resourceId = plan.targetResourceId,
                instruction = plan.instruction,
                textAny = if (plan.targetText.isNotEmpty()) listOf(plan.targetText) else emptyList(),
                actionType = safeActionType,
                actionPayload = plan.actionPayload,
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
                    note = blockedReason?.let { "action blocked -> guide: $it" },
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
        // If the planner is unavailable after STT succeeds, retain the user's
        // intent instead of falling through to the default taxi task. This is
        // deterministic and uses the utterances authored in the task assets.
        return tasks.matchByUtterance(transcript) ?:
            GuidedTask(1, "open_ended", transcript, emptyList(), emptyList())
    }

    private fun presentCurrentStep(e: StepEngine, turn: Int, speak: Boolean) {
        presentStep(e, e.currentStep.spokenFor(lastLanguage), turn, speak)
    }

    /**
     * Show instruction immediately, resolve the highlight off-thread, speak the
     * instruction. Rendering is always pushed back through the callback, and
     * always gated on [turn] still being live.
     */
    /**
     * Find the element this step is talking about, without relying on the
     * planner to have named it.
     *
     * The ring used to be drawn only from step.resourceId / step.textAny, both
     * of which come straight out of the model. When the model returned neither
     * — which it frequently does — bounds were null and nothing was
     * highlighted, so the same request highlighted on one run and not the next.
     * That is the "randomness": the visual half of the product was conditional
     * on a field the LLM could silently omit.
     *
     * So the target is derived in falling order of evidence, and the last two
     * steps need no model cooperation at all:
     *   1. an explicit resource id
     *   2. the planner's candidate texts, ranked rather than substring-matched
     *   3. the words of the instruction the user is actually being told to act
     *      on — "Tap \"Where to?\"" names its own target
     *
     * Ranked matching via TargetResolver throughout, so a near-tie is reported
     * rather than resolved by tree order. Entirely app-agnostic: no package
     * checks, no per-app tables.
     */
    private fun resolveStepBounds(target: TaskStep, instruction: String, snap: ScreenSnapshot): Rect? {
        if (target.resourceId.isNotEmpty()) {
            snap.boundsForResourceId(target.resourceId)?.let { return it }
        }
        for (t in target.textAny) {
            if (t.isBlank()) continue
            val r = TargetResolver.resolve(t, snap)
            if (r is TargetResolver.Result.Found) return r.candidate.bounds
        }
        for (q in quotedPhrases(instruction)) {
            val r = TargetResolver.resolve(q, snap)
            if (r is TargetResolver.Result.Found) return r.candidate.bounds
        }
        return null
    }

    /**
     * Phrases the instruction puts in quotes. Guidance copy names its target
     * that way ('Tap "Rentals"'), which makes the spoken sentence and the ring
     * agree by construction instead of by the model filling in two fields
     * consistently.
     */
    private fun quotedPhrases(instruction: String): List<String> =
        QUOTED.findAll(instruction)
            .map { it.groupValues[1].trim().trimEnd('?', '.', '!', ',') }
            .filter { it.length in 2..40 }
            .distinct()
            .toList()

    private fun presentStep(e: StepEngine, instruction: Spoken, turn: Int, speak: Boolean) {
        val step = e.currentStep
        // Drop the previous step's ring before resolving this one, so a stale
        // box never lingers over an element that is no longer the target.
        currentHighlight = null

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

            // Resolve continuously rather than once.
            //
            // A single resolve fires whenever it happens to fire — often before
            // the target screen has finished drawing — and then draws a ring
            // around where the element was half a second ago, or nothing at all.
            // Polling re-reads the live tree until the target actually appears,
            // and only re-renders when the resolved bounds CHANGE, so a stable
            // target does not restart its pulse animation every tick.
            //
            // Reconciled from Aswin's highlight work on
            // feature/screensaathi-integration (6f35e36, 6ef64a9, 61c96b6,
            // 20ad685). Those commits predate the SafetyGuard/DeviceContext
            // layer and call AppLauncher unguarded; the polling/persistence
            // behaviour is taken, the unguarded launch is NOT — the gating below
            // is the version from this branch.
            var launchAttempted = false
            val startedAt = SystemClock.uptimeMillis()

            // What the screen looked like when this step was planned. If the
            // user walks somewhere else, the plan behind this step describes a
            // screen that is no longer in front of them, and continuing to
            // recite it is worse than saying nothing.
            var plannedFor = ScreenReaderService.instance?.snapshot()?.signature().orEmpty()
            var replannedFor = ""

            while (isCurrent(turn) && !stopped) {
                // The package is only known once the user has picked an app, so
                // it is pinned here rather than in the task JSON.
                val target = if (chosenPackage.isNotEmpty() && step.expectPackage.isEmpty()) {
                    step.copy(expectPackage = chosenPackage)
                } else {
                    step
                }

                val snap = ScreenReaderService.instance?.snapshot()
                val currentPkg = snap?.packageName.orEmpty()
                val expectedPkg = target.expectPackage.ifEmpty { chosenPackage }

                // Don't hunt for a target while the launcher or system UI is in
                // front — the element cannot be there, and matching against it
                // produces a ring on an unrelated home-screen icon.
                // The launcher is a screen too.
                //
                // This used to hard-block any match while the launcher was in
                // front, so a user still on the app drawer got spoken guidance
                // and no ring at all — the assistant naming an app it would not
                // point at. The block exists to stop a stray ring landing on an
                // unrelated home-screen icon, and that risk only exists when we
                // do not yet know which app we want.
                //
                // Once a package IS known, its icon is a legitimate target: the
                // step's own candidate texts carry the app's label, so the icon
                // resolves by label through the same ranked matcher as anything
                // else. Language-independent by construction — it matches the
                // launcher's label, not the words the user spoke, so a Tamil
                // request still lands on "Uber".
                val packageMatches = when {
                    expectedPkg.isNotEmpty() ->
                        currentPkg == expectedPkg || isLauncherPackage(currentPkg)
                    target.actionType != "launch_app" -> !isLauncherPackage(currentPkg)
                    else -> true
                }

                val bounds = if (packageMatches && snap?.settled == true) {
                    resolveStepBounds(target, instruction.text, snap)
                } else {
                    null
                }

                val hl = bounds?.let {
                    HighlightBounds(it.left, it.top, it.right, it.bottom,
                        target.highlight.shape, target.highlight.pulse)
                }

                // Re-plan when the screen moves out from under the step.
                //
                // The step engine walks a plan that was written for the screen
                // that was in front of the user when it was made. Someone else
                // driving the phone — a judge opening a different page — leaves
                // the assistant guiding a screen that is gone, confidently and
                // wrongly. The snapshot is already being polled here, so the
                // change is detectable; this reacts to it by asking the planner
                // again with the CURRENT screen rather than continuing.
                //
                // Guarded so it fires once per screen: replanning on every tick
                // would put the planner in a loop and never settle.
                val sig = snap?.signature().orEmpty()
                if (sig.isNotEmpty() && sig != plannedFor && sig != replannedFor &&
                    bounds == null && lastUserRequest.isNotBlank() &&
                    SystemClock.uptimeMillis() - startedAt > REPLAN_SETTLE_MS
                ) {
                    replannedFor = sig
                    plannedFor = sig
                    Log.d(TAG, "SCREEN_CHANGED replanning pkg=$currentPkg intent='$lastUserRequest'")
                    currentHighlight = null
                    bg.post { processOpenEndedNext(turn, lastUserRequest, snap) }
                    return@post
                }

                if (hl != currentHighlight) {
                    currentHighlight = hl
                    renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                        instruction = instruction.text, language = instruction.language, highlight = hl))
                }

                publishDebug(turn) {
                    it.copy(
                        wantResourceId = target.resourceId.ifEmpty {
                            "text:" + target.textAny.firstOrNull().orEmpty()
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

                // click/type_text are deliberately NOT auto-executed here. The
                // ring persists and the user taps the real element themselves;
                // onUserClicked() advances the step. That keeps a model-proposed
                // tap from becoming a device action the user never asked for —
                // the open gap flagged in the safety audit.
                if (!launchAttempted && target.actionType == "launch_app") {
                    launchAttempted = true
                    performGuardedLaunch(target, instruction, turn)
                }

                if (SystemClock.uptimeMillis() - startedAt > TARGET_POLL_TIMEOUT_MS) break
                try { Thread.sleep(TARGET_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    /**
     * A launcher/system surface, where a task's target element cannot be.
     *
     * Our own package is explicitly NOT one: the guided demo screen lives in
     * com.screensaathi, so treating it as launcher chrome would make the one
     * screen we ship impossible to point at.
     */
    private fun isLauncherPackage(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val lower = packageName.lowercase()
        if (lower == context.packageName.lowercase()) return false
        return lower.contains("launcher") || lower == "com.android.systemui" || lower == "android"
    }

    /**
     * Launch, but only when device evidence and the user's own words authorise
     * it, and never report a launch that did not happen.
     */
    private fun performGuardedLaunch(target: TaskStep, instruction: Spoken, turn: Int) {
        // Prefer the real utterance; fall back to the task title only when
        // nothing has been spoken yet (e.g. a rehearsal trigger).
        val requestText = lastUserRequest.ifBlank { engine?.task?.title.orEmpty() }
        val verdict = SafetyGuard.validateLaunchAuthorization(
            userRequest = requestText,
            resolution = DeviceContextProvider.snapshot(context).resolveApp(target.actionPayload),
            userSelectedPackage = userSelectedPackage.ifBlank { null },
        )
        if (verdict is SafetyGuard.Verdict.Block) {
            publishDebug(turn) { it.copy(note = "launch blocked: ${verdict.reason}") }
            val msg = Phrases.get(Phrases.Key.APP_WONT_OPEN, lastLanguage)
            renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                instruction = msg.text, language = msg.language, highlight = null))
            speech.post { speak(msg, turn) }
            return
        }

        AppLauncher.resolvePackageName(context, target.actionPayload)
            ?.takeIf { it.isNotEmpty() }
            ?.let { chosenPackage = it }

        bg.postDelayed({
            if (!isCurrent(turn) || stopped) return@postDelayed
            // The boolean used to be discarded, so a launch that never happened
            // was announced as if it had.
            val launched = AppLauncher.launchApp(context, target.actionPayload)
            if (!launched) {
                publishDebug(turn) {
                    it.copy(note = "launch failed (not resolvable): ${target.actionPayload}")
                }
                val msg = Phrases.get(Phrases.Key.APP_WONT_OPEN, lastLanguage)
                renderIfCurrent(turn, OverlayCommand(PillState.ERROR, expanded = true,
                    instruction = msg.text, language = msg.language, highlight = null))
                speech.post { speak(msg, turn) }
                return@postDelayed
            }
            // The ring belonged to the previous screen; the launched app draws
            // its own, and the poll loop above will find the next target.
            currentHighlight = null
            renderIfCurrent(turn, OverlayCommand(PillState.GUIDING, expanded = true,
                instruction = instruction.text, language = instruction.language, highlight = null))
        }, 300)
    }

    /**
     * Point at whatever the user just named, on whatever screen is in front of
     * them right now. No task, no DSL, no demo — the live accessibility tree
     * and the user's words are the only inputs.
     *
     * Re-resolves on a poll so the ring follows a target that scrolls or moves,
     * and drops it the moment the target stops existing.
     */
    /** Drop any ring and end the current turn. */
    fun clearHighlight() {
        val turn = newTurn()
        currentHighlight = null
        lastHighlightQuery = null
        lastHighlightScreenSig = ""
        awaitingResettle = false
        renderIfCurrent(turn, OverlayCommand(PillState.IDLE, expanded = false, highlight = null))
    }

    fun highlightTarget(query: String) {
        val turn = newTurn()
        stopped = false
        lastUserRequest = query
        lastHighlightQuery = query
        currentHighlight = null
        lastHighlightScreenSig = ""

        renderIfCurrent(turn, OverlayCommand(PillState.THINKING, expanded = true,
            instruction = "Looking for \"$query\"…", language = lastLanguage))

        bg.post { runHighlightPollLoop(turn, query) }
    }

    /** [highlightTarget]'s resolve/render loop, factored out so a screen
     * transition (see [onWindowStateChanged]) can restart it on a fresh
     * generation without duplicating the loop body. */
    private fun runHighlightPollLoop(turn: Int, query: String) {
        val startedAt = SystemClock.uptimeMillis()
        var announced = false

        while (isCurrent(turn) && !stopped) {
            val snap = ScreenReaderService.instance?.snapshot()
            if (snap == null) {
                publishDebug(turn) { it.copy(note = "OVERLAY_CLEAR reason=no_accessibility_service") }
                break
            }
            if (!snap.settled) {
                publishDebug(turn) { it.copy(note = "TARGET_WAIT reason=screen_unsettled") }
                try { Thread.sleep(TARGET_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                continue
            }
            val result = TargetResolver.resolve(query, snap)
            val hl = when (result) {
                is TargetResolver.Result.Found -> {
                    val b = result.candidate.bounds
                    HighlightBounds(b.left, b.top, b.right, b.bottom, "rect", true)
                }
                else -> null
            }

            if (hl != currentHighlight) {
                val reason = when (result) {
                    is TargetResolver.Result.Found ->
                        "OVERLAY_RENDER x=${hl?.left} y=${hl?.top} w=${(hl?.right ?: 0) - (hl?.left ?: 0)} " +
                            "h=${(hl?.bottom ?: 0) - (hl?.top ?: 0)} why=${result.candidate.why}"
                    is TargetResolver.Result.Ambiguous ->
                        "OVERLAY_CLEAR reason=ambiguous(${result.candidates.size})"
                    is TargetResolver.Result.NotFound ->
                        "OVERLAY_CLEAR reason=not_found"
                }
                Log.d(TAG, reason)
                currentHighlight = hl
                // Keep the signature of the screen that owns the visible ring.
                // Updating it on every poll can make a destination screen look
                // current before the transition callback clears the old target.
                if (hl != null) lastHighlightScreenSig = snap.signature()
                val say = when (result) {
                    is TargetResolver.Result.Found -> "Here it is."
                    is TargetResolver.Result.Ambiguous ->
                        "I found ${result.candidates.size} things matching \"$query\" — which one?"
                    is TargetResolver.Result.NotFound -> "I can't see \"$query\" on this screen."
                }
                renderIfCurrent(turn, OverlayCommand(
                    if (hl != null) PillState.GUIDING else PillState.ERROR,
                    expanded = true, instruction = say,
                    language = lastLanguage, highlight = hl))
                if (!announced) { announced = true; speech.post { speak(Spoken(say, lastLanguage), turn) } }
            }

            publishDebug(turn) {
                it.copy(
                    wantResourceId = "q:" + query,
                    readerBound = true,
                    screenPackage = snap.packageName,
                    settled = snap.settled,
                    elementCount = snap.elements.size,
                    bounds = currentHighlight?.let { h -> "${h.left},${h.top},${h.right},${h.bottom}" }
                        ?: "NOT FOUND",
                )
            }

            if (SystemClock.uptimeMillis() - startedAt > TARGET_POLL_TIMEOUT_MS) break
            try { Thread.sleep(TARGET_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    /** Last query passed to [highlightTarget], so a mid-flow screen
     * transition knows what to keep looking for. Null once the highlight is
     * dismissed for any other reason — a transition must never resurrect a
     * highlight the user (or [onStopTapped]) already cancelled. */
    @Volatile private var lastHighlightQuery: String? = null

    /** The screen signature the current highlight was last confirmed against. */
    @Volatile private var lastHighlightScreenSig: String = ""

    /** Set the instant a transition is detected; consumed once the new
     * screen settles and a fresh resolve actually runs. Lets clearing and
     * resolving happen on two different events instead of one. */
    @Volatile private var awaitingResettle = false
    @Volatile private var invalidationTurn = 0

    /**
     * Fast invalidation signal for a screen transition, called from
     * [ScreenReaderService] on every TYPE_WINDOW_STATE_CHANGED.
     *
     * TYPE_VIEW_CLICKED cannot be this signal — measured on device, Uber
     * never emits it, on a plain button as much as a bare text row — so
     * nothing upstream of an actual screen change can be trusted to fire
     * reliably. TYPE_WINDOW_STATE_CHANGED is what the platform sends when the
     * window itself changes, which is the one thing the demo's "tap advances
     * the screen" case is guaranteed to produce.
     *
     * Two separate decisions, deliberately not one:
     *
     *  - CLEAR must not wait for the new screen to settle. Gating it on
     *    `settled` was the first cut of this — measured on device, it held
     *    the stale ring on screen for ~1.5s after the tap, because a
     *    mid-transition tree reports unsettled for several ticks. But an
     *    unsettled read is still real evidence of change (elems 73→64 shows
     *    up on the very first unsettled read, well before `settled` flips) —
     *    so the clear compares signatures on whatever snapshot is available,
     *    settled or not, and fires the moment they diverge.
     *  - RESOLVE still waits for `settled`, so the next target is read off a
     *    finished layout rather than a half-drawn one.
     *
     * Runs on [bg] — same thread as the poll loop it invalidates — so there
     * is no race between reading currentHighlight here and the loop's own
     * writes to it.
     */
    fun onWindowStateChanged() {
        bg.post {
            if (stopped) return@post
            val query = lastHighlightQuery ?: return@post
            if (currentHighlight == null && !awaitingResettle) return@post

            val snap = ScreenReaderService.instance?.snapshot() ?: return@post
            val sig = snap.signature()
            // Nothing readable yet on this event — not evidence either way,
            // wait for a later one rather than clear on noise.
            if (sig.isEmpty()) return@post

            if (currentHighlight != null && sig != lastHighlightScreenSig) {
                // Real transition. Cut immediately — no fly-home, no tether,
                // no trail — on THIS event, not the one where the new screen
                // finally settles.
                currentHighlight = null
                lastHighlightScreenSig = ""
                clearHighlightInstant()
                invalidationTurn = newTurn()
                awaitingResettle = true
            }

            if (awaitingResettle && snap.settled) {
                awaitingResettle = false
                runHighlightPollLoop(invalidationTurn, query)
            }
        }
    }

    /**
     * The user physically tapped something. That is the signal to advance —
     * not a timer, and not an action we performed on their behalf.
     */
    fun onUserClicked() {
        bg.post {
            val turn = turnId.get()
            if (stopped || !isCurrent(turn)) return@post
            val now = SystemClock.uptimeMillis()
            // A single tap can produce several accessibility events.
            if (now - lastUserClickAt < USER_CLICK_DEBOUNCE_MS) return@post
            lastUserClickAt = now

            if (engine != null) {
                onNextTapped()
                return@post
            }

            // The ad-hoc highlight loop (highlightTarget) has no step engine
            // and so was invisible to this click handler entirely — a tap
            // never told it anything. It kept re-matching the same query
            // text against whatever screen came next, and if that label
            // still existed there (e.g. "Where to?" surviving as a
            // placeholder on the next screen too), the cursor flew to the
            // new position and the in-flight tether read as a leftover line
            // from the screen the user had already left.
            //
            // Tied to the real TYPE_VIEW_CLICKED event, not a delay: the
            // instant a tap is accepted, drop the old geometry and stop
            // chasing that query. bumping the turn is what actually halts
            // the poll loop — its own isCurrent(turn) check exits on the
            // next iteration.
            if (currentHighlight != null) {
                currentHighlight = null
                val t = newTurn()
                // instruction = null: OverlayCommand leaves existing pill text
                // alone (render() only replaces it when non-null) — this call
                // exists solely to drop the highlight, not to say anything new.
                render(OverlayCommand(PillState.GUIDING, expanded = true,
                    instruction = null, language = lastLanguage, highlight = null))
                publishDebug(t) { it.copy(note = "OVERLAY_CLEAR reason=user_tapped") }
            }
        }
    }

    @Volatile private var lastUserClickAt: Long = 0L

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

        // Tapping an app in a list the assistant presented IS authorisation.
        // Recorded before the launch so the guard can see it.
        userSelectedPackage = app.packageName

        val opening = Phrases.get(Phrases.Key.OPENING_APP, lastLanguage)
        val spoken = Spoken(String.format(opening.text, app.label), opening.language)
        render(OverlayCommand(PillState.THINKING, expanded = true,
            instruction = spoken.text, language = spoken.language))

        // Route the explicit choice through the guard too, rather than around
        // it. The selection authorises the launch (userSelectedPackage above),
        // but device evidence — installed, enabled, launchable — is still
        // checked, and a failure is still reported honestly.
        val choiceVerdict = SafetyGuard.validateLaunchAuthorization(
            userRequest = lastUserRequest,
            resolution = DeviceContextProvider.snapshot(context).resolveApp(app.label),
            userSelectedPackage = app.packageName,
        )
        if (choiceVerdict is SafetyGuard.Verdict.Block) {
            publishDebug(turn) { it.copy(note = "choice launch blocked: ${choiceVerdict.reason}") }
        }

        if (choiceVerdict is SafetyGuard.Verdict.Block || !RideApps.launch(context, app.packageName)) {
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

    /** The user's most recent actual utterance — the authorisation evidence. */
    @Volatile private var lastUserRequest: String = ""

    /** A package the user explicitly picked from a presented list. */
    @Volatile private var userSelectedPackage: String = ""

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
        @Volatile var instance: SessionController? = null
        /** Re-resolve cadence for the live target. */
        private const val TARGET_POLL_INTERVAL_MS = 300L
        /** Stop hunting for a target that never appears. */
        private const val TARGET_POLL_TIMEOUT_MS = 30_000L
        /** One physical tap can emit several accessibility events. */
        private const val USER_CLICK_DEBOUNCE_MS = 800L
        private const val TAG = "SessionController"
        private const val RESOLVE_ATTEMPTS = 12
        /** A cold ride app can take a couple of seconds to draw its first screen. */
        private const val CROSS_APP_ATTEMPTS = 40
        private const val RESOLVE_INTERVAL_MS = 120L
        private const val CONFIDENCE_FLOOR = 0.5
        private const val DEFAULT_TASK = "pay_bill"
        private const val CAPTURE_PREFIX = "saathi_input_"

        /** Below this the clip is a mis-tap, not speech. */
        private val QUOTED = Regex("[\"'“”‘’]([^\"'“”‘’]+)[\"'“”‘’]")

        /** Let a screen finish drawing before treating it as a real change. */
        private const val REPLAN_SETTLE_MS = 1_200L

        private const val MIN_SPEECH_MS = 400L

        /** Longest single-tap capture before it is closed automatically. */
        private const val MAX_UTTERANCE_MS = 7_000L
    }
}
