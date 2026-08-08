package com.screensaathi.session

import com.screensaathi.device.AppResolution
import com.screensaathi.device.Availability
import com.screensaathi.task.GuidedTask

/**
 * Two deterministic refusals that sit between the planner and the user.
 *
 * Both exist because the eval suite caught the planner doing something a
 * confidence threshold alone cannot prevent (docs/evals/FAILURES.md GAP-1,
 * GAP-2). Deliberately pure — no Android types, no model call — so production
 * and the eval harness run the identical logic, and so both are unit-testable.
 */
object SafetyGuard {

    /**
     * Refuses a jump to an irreversible step while an earlier step is still
     * blank.
     *
     * The screen itself is the evidence: a step counts as done when the element
     * it targets currently holds text. So "skip to the payment" on a filled
     * form is allowed, and the same words on an empty form are not.
     *
     * @param textOf current on-screen text for a resource id, or null when the
     *   element is not present.
     * @return true when the jump must be blocked.
     */
    fun blocksIrreversibleJump(
        task: GuidedTask,
        targetStepId: String,
        textOf: (String) -> String?,
    ): Boolean {
        val target = task.stepById(targetStepId) ?: return false
        if (!target.irreversible) return false

        val targetIndex = task.indexOfStep(targetStepId)
        if (targetIndex <= 0) return false

        // Every preceding step that points at a real field must be filled in.
        return task.steps.take(targetIndex).any { prior ->
            prior.resourceId.isNotEmpty() && textOf(prior.resourceId).isNullOrBlank()
        }
    }

    /**
     * Refuses a plan the current screen cannot support.
     *
     * The planner reports its own confidence, and it will happily report 0.95
     * about a screen with nothing readable on it. Self-reported confidence is
     * not evidence; the snapshot is. If there is nothing to point at, saying so
     * beats confidently pointing at nothing.
     *
     * @return true when the plan must not be acted on.
     */
    fun blocksUngroundedPlan(
        elementCount: Int,
        targetResolves: Boolean,
    ): Boolean = elementCount == 0 || !targetResolves

    // --- Open-ended execution gate -------------------------------------------

    /**
     * Action words that commit something the user cannot take back. Matched
     * against the target's label/id, so "Pay Now" and "pay_button" both hit.
     *
     * "save" is deliberately absent: saving a note is not irreversible, and
     * blocking it would break an ordinary request for no safety gain.
     */
    private val IRREVERSIBLE_HINTS = listOf(
        "pay", "send", "submit", "confirm", "delete", "remove", "buy", "order",
        "book", "transfer", "call", "checkout", "purchase", "withdraw",
    )

    /** Actions that actually touch the device. `guide`/`answer` only speak. */
    private val EXECUTING_ACTIONS = setOf("click", "type_text", "launch_app")

    sealed interface Verdict {
        object Allow : Verdict
        data class Block(val reason: String) : Verdict
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ")

    /**
     * The gate `planOpenEnded` never had.
     *
     * planOpenEnded can launch apps, tap and type on a real device, and
     * SessionController executed whatever it returned as long as it parsed
     * (SessionController.kt:454). The measured baseline showed the model
     * answering "do the usual thing" on a banking screen with a confident
     * `click` on Pay Now — so a confidence floor is not the fix. Confidence is
     * deliberately not an input here.
     *
     * Blocking downgrades the plan to `guide`, an existing safe behaviour: the
     * assistant still points and speaks, it just does not act.
     */
    fun validateOpenEndedAction(
        userRequest: String,
        actionType: String,
        targetResourceId: String,
        targetText: String,
        actionPayload: String,
        elementCount: Int,
        settled: Boolean,
        targetResolves: Boolean,
    ): Verdict {
        // guide/answer never touch the device — nothing to gate.
        if (actionType !in EXECUTING_ACTIONS) return Verdict.Allow

        val req = normalize(userRequest)

        // No usable screen evidence: acting would be guessing.
        if (elementCount == 0) return Verdict.Block("no readable elements on screen")
        if (!settled) return Verdict.Block("screen has not settled")

        // A tap or a keystroke needs a target that actually exists.
        if (actionType == "click" || actionType == "type_text") {
            if (!targetResolves) {
                return Verdict.Block("target not present on screen " +
                    "(id='$targetResourceId' text='$targetText')")
            }
        }

        // FIX 2: never type a value the user did not supply.
        // No structured slot extraction exists in this codebase, so containment
        // against the original request is the honest deterministic test. It is
        // conservative: it can reject a legitimate paraphrase, but it can never
        // let an invented value through.
        if (actionType == "type_text") {
            val payload = actionPayload.trim()
            if (payload.isEmpty()) {
                return Verdict.Block("type_text with an empty payload")
            }
            if (!req.contains(normalize(payload).trim())) {
                return Verdict.Block("typed value '$payload' was not supplied by the user")
            }
        }

        // FIX 1: an irreversible target must be named in the request.
        // "Go ahead" is not authorisation to press Submit.
        val targetLabel = normalize("$targetResourceId $targetText $actionPayload")
        val hint = IRREVERSIBLE_HINTS.firstOrNull { targetLabel.contains(it) }
        if (hint != null && !req.contains(hint)) {
            return Verdict.Block("irreversible action '$hint' was not explicitly requested")
        }

        return Verdict.Allow
    }

    /**
     * Whether a `launch_app` may run, given real device evidence.
     *
     * Deliberately refuses on UNKNOWN. The baseline showed the model confidently
     * announcing "launching instagram app" on a phone with no Instagram; the
     * cure is not a better prompt, it is refusing to act on an app nobody has
     * verified exists. Refusing is also honest in the other direction — we do
     * not tell the user it is missing, only that we could not verify it.
     */
    /**
     * Did the user actually name this app?
     *
     * Matched against the *label device evidence reports*, not a keyword list —
     * the same principle as the type_text provenance rule. So "open uber",
     * "launch Uber please" and "open the Uber app" all authorise Uber, while
     * "find an app for booking a cab" authorises nothing, because it names no
     * app at all.
     */
    private fun requestNamesApp(userRequest: String, label: String): Boolean {
        val req = " ${normalize(userRequest)} "
        val name = normalize(label).trim()
        if (name.isEmpty()) return false
        return req.contains(" $name ")
    }

    /**
     * Authorisation, as distinct from validity.
     *
     * `validateLaunch` answers "is this a launchable app?". This answers "did
     * the user ask for THIS app?" — and the evaluator caught the difference:
     * asked for "an app for booking a cab" on a phone with Uber and Ola, the
     * model proposed Uber and the evidence-only guard allowed it, because Uber
     * is a perfectly valid launch target. It just was not the one the user
     * chose.
     *
     * A capability request ("a taxi app"), a vague request ("open something")
     * and a content request ("find my downloaded PDF") all name no app, so all
     * three are refused here and fall back to `guide`. Selecting on the user's
     * behalf needs a ranking policy this product does not have yet.
     */
    fun validateLaunchAuthorization(userRequest: String, resolution: AppResolution): Verdict {
        val evidence = validateLaunch(resolution)
        if (evidence is Verdict.Block) return evidence

        val label = resolution.single?.label ?: return Verdict.Block("no single resolved app to authorise")
        if (!requestNamesApp(userRequest, label)) {
            return Verdict.Block("user did not name \"$label\" — asked: \"$userRequest\"")
        }
        return Verdict.Allow
    }

    fun validateLaunch(resolution: AppResolution): Verdict = when {
        resolution.availability == Availability.UNKNOWN_DUE_TO_PACKAGE_VISIBILITY ->
            Verdict.Block("cannot verify \"${resolution.query}\" on this device")

        resolution.availability == Availability.KNOWN_ABSENT_WITHIN_VISIBLE_SET ->
            Verdict.Block("\"${resolution.query}\" is not installed")

        resolution.matches.isEmpty() ->
            Verdict.Block("no installed app matches \"${resolution.query}\"")

        // Never pick for the user when several apps fit.
        resolution.isAmbiguous ->
            Verdict.Block("several apps match \"${resolution.query}\" — ask which one")

        resolution.single?.enabled == false ->
            Verdict.Block("\"${resolution.query}\" is disabled on this device")

        resolution.single?.launchable == false ->
            Verdict.Block("\"${resolution.query}\" cannot be opened directly")

        else -> Verdict.Allow
    }
}
