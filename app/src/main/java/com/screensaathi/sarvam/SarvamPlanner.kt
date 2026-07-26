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

    /** Blocking. Call off the main thread. */
    fun plan(transcript: String, task: GuidedTask, screen: ScreenSnapshot): PlannerResult? {
        if (!Sarvam.hasKey()) {
            Log.w(TAG, "No Sarvam key — planner unavailable")
            return null
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", buildUserContent(transcript, task, screen)))

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
            Sarvam.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Planner ${resp.code}: $raw")
                    return null
                }
                parse(raw, task)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Planner failed: ${e.message}")
            null
        }
    }

    private fun buildUserContent(transcript: String, task: GuidedTask, screen: ScreenSnapshot): String {
        val steps = task.steps.joinToString("\n") { "- ${it.id} (resource_id=${it.resourceId})" }
        return buildString {
            append("User said: \"").append(transcript).append("\"\n\n")
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
                    .put("instruction", JSONObject().put("type", "string"))
                    .put("confidence", JSONObject().put("type", "number"))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray().put("intent").put("step").put("target")
                    .put("instruction").put("confidence").put("reason")
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

    private fun parse(raw: String, task: GuidedTask): PlannerResult? {
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
        val target = args.optJSONObject("target") ?: JSONObject()
        val declaredRid = target.optString("resource_id")
        // Trust the DSL's resource_id for the step over the model's echo — the
        // step id is authoritative, the model only chooses which step.
        val rid = task.stepById(step)?.resourceId ?: declaredRid

        return PlannerResult(
            version = 1,
            intent = args.optString("intent", task.id),
            step = step,
            targetResourceId = rid,
            targetIndex = target.optInt("index", -1),
            instruction = args.optString("instruction", task.stepById(step)?.instruction ?: ""),
            confidence = args.optDouble("confidence", 0.5),
            reason = args.optString("reason", "").take(80),
        )
    }

    companion object {
        private const val TAG = "SarvamPlanner"
        private const val PROMPT = "prompts/planner_v1.md"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
