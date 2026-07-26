package com.screensaathi.sarvam

import android.content.Context
import android.util.Log
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.task.GuidedTask
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sarvam-30B planner. Forces a single structured tool call (tool_choice
 * required) so the model always returns the FROZEN planner schema, never prose.
 * reasoning_effort is disabled — this is a fast routing decision, not a
 * chain-of-thought task, and reasoning tokens would blow the latency budget.
 *
 * Returns null on any failure (no key, timeout, malformed) so the caller falls
 * back to deterministic step ordering. The overlay is never left frozen.
 *
 * Latency budget: < 700 ms.
 */
class SarvamPlanner(context: Context) {

    private val systemPrompt: String = runCatching {
        context.assets.open(PROMPT).bufferedReader().use { it.readText() }
    }.getOrDefault("You are ScreenSaathi's planner. Call set_plan once.")

    /**
     * Blocking. Call off the main thread.
     *
     * @param spokenLanguage what Saaras detected the user speaking; the planner
     *   is asked to answer in it.
     * @param currentStepId where the user is right now. Without it the prompt's
     *   "if unclear, stay on the current step" rule was unfollowable — the model
     *   was never told which step that was.
     */
    fun plan(
        transcript: String,
        task: GuidedTask,
        screen: ScreenSnapshot,
        spokenLanguage: String,
        currentStepId: String?,
    ): PlannerResult? {
        if (!Sarvam.hasKey()) {
            Log.w(TAG, "No Sarvam key — planner unavailable")
            return null
        }

        val userContent = buildUserContent(transcript, task, screen, spokenLanguage, currentStepId)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))

        val payload = JSONObject()
            .put("model", Sarvam.PLANNER_MODEL)
            .put("messages", messages)
            .put("tools", JSONArray().put(toolSpec(task)))
            .put("tool_choice", "required")
            .put("parallel_tool_calls", false)
            .put("reasoning_effort", JSONObject.NULL) // disable thinking → fast
            .put("temperature", 0.1)
            .put("max_tokens", 300)
            .toString()

        val req = Request.Builder()
            .url(Sarvam.CHAT_URL)
            .addHeader(Sarvam.AUTH_HEADER, Sarvam.apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            Sarvam.plannerHttp.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Planner ${resp.code}: $raw")
                    return null
                }
                parse(raw, task, spokenLanguage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Planner failed: ${e.message}")
            null
        }
    }

    private fun buildUserContent(
        transcript: String,
        task: GuidedTask,
        screen: ScreenSnapshot,
        spokenLanguage: String,
        currentStepId: String?,
    ): String {
        // Marking CURRENT is what makes "if unclear, stay put" and "go back a
        // step" answerable at all. Listing the ids alone left the model
        // guessing where the user already was.
        val steps = task.steps.joinToString("\n") {
            val marker = if (it.id == currentStepId) "  <- CURRENT" else ""
            "- ${it.id} (resource_id=${it.resourceId})$marker"
        }
        return buildString {
            append("User said: \"").append(transcript).append("\"\n")
            append("Detected spoken language: ").append(Language.normalize(spokenLanguage))
            append(" — reply in this language, in its own script.\n\n")
            append("Task: ").append(task.id).append(" — ").append(task.title).append("\n")
            append("Steps:\n").append(steps).append("\n\n")
            append(screen.toPromptText())
        }
    }

    private fun toolSpec(task: GuidedTask): JSONObject {
        val stepIds = JSONArray().apply { task.steps.forEach { put(it.id) } }
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("intent", JSONObject().put("type", "string"))
                    .put("step", JSONObject().put("type", "string").put("enum", stepIds))
                    .put(
                        "target",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("resource_id", JSONObject().put("type", "string"))
                                .put("index", JSONObject().put("type", "integer"))
                        ).put("required", JSONArray().put("resource_id").put("index"))
                    )
                    .put(
                        "instruction",
                        JSONObject().put("type", "string")
                            .put("description", "One short sentence, in the user's own language and script.")
                    )
                    .put(
                        "language",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray().apply { Language.SUPPORTED.forEach { put(it) } })
                            .put("description", "BCP-47 code of the language `instruction` is written in.")
                    )
                    .put("confidence", JSONObject().put("type", "number"))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray().put("intent").put("step").put("target")
                    .put("instruction").put("language").put("confidence").put("reason")
            )
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "set_plan")
                    .put("description", "Set the next guided step and the element to point at.")
                    .put("parameters", params)
            )
    }

    fun planOpenEnded(
        transcript: String,
        screen: ScreenSnapshot,
        spokenLanguage: String
    ): PlannerResult? {
        if (!Sarvam.hasKey()) {
            Log.w(TAG, "No Sarvam key — planner unavailable")
            return null
        }

        val userContent = buildString {
            append("User intent: \"").append(transcript).append("\"\n")
            append("Detected spoken language: ").append(Language.normalize(spokenLanguage))
            append(" — reply in this language, in its own script.\n\n")
            append(screen.toPromptText())
        }

        val openEndedSystemPrompt = "You are an autonomous Screen assistant. Given the user's intent and the current screen, identify the next step. Set action_type to 'launch_app' to open an app (and set action_payload to the app name), 'type_text' to type text into a field (set action_payload to the text), 'click' to automatically tap an element, 'guide' to point at it, or 'answer' to simply answer a user's question based on the screen context. If the user's intent is fully resolved or you are answering a question, set is_done=true. In your instruction, narrate the action or provide the direct answer to the user in their language."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", openEndedSystemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))

        val payload = JSONObject()
            .put("model", Sarvam.PLANNER_MODEL)
            .put("messages", messages)
            .put("tools", JSONArray().put(toolSpecOpenEnded()))
            .put("tool_choice", "required")
            .put("parallel_tool_calls", false)
            .put("reasoning_effort", JSONObject.NULL)
            .put("temperature", 0.1)
            .put("max_tokens", 300)
            .toString()

        val req = Request.Builder()
            .url(Sarvam.CHAT_URL)
            .addHeader(Sarvam.AUTH_HEADER, Sarvam.apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            Sarvam.plannerHttp.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Planner ${resp.code}: $raw")
                    return null
                }
                parseOpenEnded(raw, spokenLanguage, transcript)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Planner failed: ${e.message}")
            null
        }
    }

    private fun toolSpecOpenEnded(): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("is_done", JSONObject().put("type", "boolean"))
                    .put("action_type", JSONObject().put("type", "string").put("enum", JSONArray().put("guide").put("click").put("launch_app").put("answer").put("type_text")))
                    .put("action_payload", JSONObject().put("type", "string").put("description", "If action_type is launch_app, the app name. If action_type is type_text, the text to type."))
                    .put("target_resource_id", JSONObject().put("type", "string"))
                    .put("target_text", JSONObject().put("type", "string"))
                    .put(
                        "instruction",
                        JSONObject().put("type", "string")
                            .put("description", "A short phrase narrating the action being taken, or the direct answer to the user's question, in the user's own language and script.")
                    )
                    .put(
                        "language",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray().apply { Language.SUPPORTED.forEach { put(it) } })
                            .put("description", "BCP-47 code of the language `instruction` is written in.")
                    )
                    .put("confidence", JSONObject().put("type", "number"))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray().put("is_done").put("action_type").put("instruction").put("language").put("confidence").put("reason")
            )
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "set_next_step")
                    .put("description", "Set the next guided step and the element to point at.")
                    .put("parameters", params)
            )
    }

    companion object {
        private const val TAG = "SarvamPlanner"
        private const val PROMPT = "prompts/planner_v1.md"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Visible for testing: needs no Context, so the parse is unit-testable. */
        fun parse(raw: String, task: GuidedTask, spokenLanguage: String): PlannerResult? {
            val message = JSONObject(raw)
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return null
            val call = message.optJSONArray("tool_calls")?.optJSONObject(0)
                ?: return null
            val argsStr = call.optJSONObject("function")?.optString("arguments") ?: return null
            val args = JSONObject(argsStr)

            val step = args.optString("step").takeIf { it.isNotBlank() } ?: return null
            // Guard: the model must pick a real step. If not, fail to fallback.
            if (task.indexOfStep(step) < 0) {
                Log.w(TAG, "Planner returned unknown step '$step'")
                return null
            }
            val dslStep = task.stepById(step)
            val target = args.optJSONObject("target") ?: JSONObject()
            // Trust the DSL's resource_id for the step over the model's echo —
            // the step id is authoritative, the model only chooses which step.
            val rid = dslStep?.resourceId ?: target.optString("resource_id")

            // If the model gave us nothing to say, fall back to the DSL wording
            // for the user's language rather than to an empty utterance.
            val spokenText = args.optString("instruction").takeIf { it.isNotBlank() }
                ?: dslStep?.spokenFor(spokenLanguage)?.text.orEmpty()

            return PlannerResult(
                version = 1,
                intent = args.optString("intent", task.id),
                step = step,
                targetResourceId = rid,
                targetIndex = target.optInt("index", -1),
                instruction = spokenText,
                confidence = args.optDouble("confidence", 0.5),
                reason = args.optString("reason", "").take(80),
                // The model's own label is only a hint. Reconcile it against the
                // script it actually wrote in: a wrong code is a 400 from Bulbul
                // and the app going quietly mute.
                language = Language.reconcile(
                    spokenText,
                    args.optString("language").takeIf { it.isNotBlank() } ?: spokenLanguage,
                ),
            )
        }
        fun parseOpenEnded(raw: String, spokenLanguage: String, transcript: String): PlannerResult? {
            val message = JSONObject(raw)
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return null
            val call = message.optJSONArray("tool_calls")?.optJSONObject(0)
                ?: return null
            val argsStr = call.optJSONObject("function")?.optString("arguments") ?: return null
            val args = JSONObject(argsStr)

            val isDone = args.optBoolean("is_done", false)
            val actionType = args.optString("action_type", "guide")
            val actionPayload = args.optString("action_payload", "")
            val spokenText = args.optString("instruction")
            
            return PlannerResult(
                version = 1,
                intent = transcript,
                step = "open_ended_step",
                targetResourceId = args.optString("target_resource_id", ""),
                targetIndex = -1,
                instruction = spokenText,
                confidence = args.optDouble("confidence", 0.5),
                reason = args.optString("reason", "").take(80),
                language = Language.reconcile(
                    spokenText,
                    args.optString("language").takeIf { it.isNotBlank() } ?: spokenLanguage,
                ),
                isDone = isDone,
                targetText = args.optString("target_text", ""),
                actionType = actionType,
                actionPayload = actionPayload
            )
        }
    }
}
