package com.screensaathi.evals

import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.sarvam.SarvamPlanner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs eval cases through the planner and scores them.
 *
 * Two modes, deliberately separated (docs/evals/README.md §cost):
 *
 *  OFFLINE (default, CI)  replays a recorded response through the REAL
 *                         SarvamPlanner.parse(). Zero API cost, byte-identical
 *                         every run. Measures the parse + policy layer: step
 *                         validation, language reconciliation, DSL precedence,
 *                         rejection of malformed output.
 *
 *  LIVE (opt-in)          calls Sarvam for real. Measures the MODEL. Requires
 *                         SARVAM_API_KEY and SCREENSAATHI_EVAL_LIVE=1.
 *
 * Parsing is shared with production in both modes — only HTTP transport is
 * reproduced here. If SarvamPlanner's request payload changes, update
 * LiveRunner to match (see docs/evals/FAILURES.md §harness-drift).
 */
interface PlannerRunner {
    val mode: String
    fun run(case: EvalCase): Pair<PlannerResult?, Long>
}

/** Replays the dataset's recorded response. Deterministic, free. */
class ReplayRunner : PlannerRunner {
    override val mode = "OFFLINE"
    override fun run(case: EvalCase): Pair<PlannerResult?, Long> {
        val raw = case.recordedResponse
            ?: error("case ${case.caseId} has no recorded_response; OFFLINE mode requires one")
        val started = System.nanoTime()
        val plan = SarvamPlanner.parse(raw, case.task.toGuidedTask(), case.spokenLanguage)
        return plan to (System.nanoTime() - started) / 1_000_000
    }
}

/** Calls the real planner. Opt-in; costs money. */
class LiveRunner(private val apiKey: String, private val model: String) : PlannerRunner {
    override val mode = "LIVE"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun run(case: EvalCase): Pair<PlannerResult?, Long> {
        val task = case.task.toGuidedTask()
        val steps = task.steps.joinToString("\n") {
            val marker = if (it.id == case.currentStepId) "  <- CURRENT" else ""
            "- ${it.id} (resource_id=${it.resourceId})$marker"
        }
        val user = buildString {
            append("User said: \"").append(case.transcript).append("\"\n")
            append("Detected spoken language: ").append(case.spokenLanguage)
            append(" — reply in this language, in its own script.\n\n")
            append("Task: ").append(task.id).append(" — ").append(task.title).append("\n")
            append("Steps:\n").append(steps).append("\n\n")
            append(case.screen.toPromptText())
        }

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
                    .put("language", JSONObject().put("type", "string"))
                    .put("confidence", JSONObject().put("type", "number"))
                    .put("reason", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray().put("intent").put("step").put("target")
                    .put("instruction").put("language").put("confidence").put("reason")
            )
        val tool = JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", "set_plan")
                .put("description", "Set the next guided step and the element to point at.")
                .put("parameters", params)
        )

        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", "required")
            .put("parallel_tool_calls", false)
            .put("reasoning_effort", JSONObject.NULL)
            .put("temperature", 0.1)
            .put("max_tokens", 300)
            .toString()

        val req = Request.Builder()
            .url("https://api.sarvam.ai/v1/chat/completions")
            .addHeader("api-subscription-key", apiKey)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val started = System.nanoTime()
        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val ms = (System.nanoTime() - started) / 1_000_000
                if (!resp.isSuccessful) null to ms
                else SarvamPlanner.parse(raw, task, case.spokenLanguage) to ms
            }
        } catch (e: Exception) {
            null to (System.nanoTime() - started) / 1_000_000
        }
    }

    /** Reads the production prompt off disk so live evals test the real thing. */
    private fun systemPrompt(): String =
        EvalPaths.resolve("app/src/main/assets/prompts/planner_v1.md")
            ?.readText()
            ?: "You are ScreenSaathi's planner. Call set_plan once."
}

/** Locates repo files regardless of whether Gradle runs from the module or root. */
object EvalPaths {
    fun resolve(relative: String): File? {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            dir?.let { d ->
                val direct = File(d, relative)
                if (direct.exists()) return direct
                // Gradle's working dir is the module (app/); repo files sit above it.
                val stripped = relative.removePrefix("app/")
                val alt = File(d, stripped)
                if (alt.exists()) return alt
            }
            dir = dir?.parentFile
        }
        return null
    }
}

data class EvalSummary(
    val results: List<CaseResult>,
    val mode: String,
    val datasetVersion: String,
    val promptVersion: String,
    val model: String,
) {
    val total get() = results.size
    val passed get() = results.count { it.passedAll }
    val passRate get() = if (total == 0) 0.0 else passed.toDouble() / total
    val criticalFailures get() = results.flatMap { it.criticalFailures }
    val p50Latency: Long
        get() = results.map { it.latencyMs }.sorted().let {
            if (it.isEmpty()) 0 else it[it.size / 2]
        }

    /** Per-check pass rate, e.g. selects_acceptable_step -> 0.92. */
    fun byCheck(): Map<String, Double> =
        results.flatMap { it.checks }.filter { !it.skipped }
            .groupBy { it.name }
            .mapValues { (_, v) -> v.count { c -> c.passed }.toDouble() / v.size }

    fun byCategory(): Map<String, Int> =
        results.flatMap { it.failed }.groupingBy { it.category }.eachCount()
}
