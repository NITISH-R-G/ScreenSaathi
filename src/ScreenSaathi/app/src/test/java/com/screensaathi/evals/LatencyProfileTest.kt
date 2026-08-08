package com.screensaathi.evals

import com.screensaathi.sarvam.SarvamPlanner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Latency + token profiler for the production planner call.
 *
 * Splits the round trip into payload construction / HTTP / parse so the
 * bottleneck is measured rather than guessed, and reads the OpenAI-compatible
 * `usage` block so token cost stops being an unknown.
 *
 * LIVE only, opt-in:
 *   SCREENSAATHI_EVAL_LIVE=1 SARVAM_API_KEY=sk_... \
 *     ./gradlew :app:testDebugUnitTest --tests "*LatencyProfileTest*" --rerun-tasks
 *
 * Reads REPEATS (default 5) so the sample is a distribution, not one number.
 */
class LatencyProfileTest {

    private data class Sample(
        val caseId: String,
        val variant: String,
        val buildMs: Long,
        val httpMs: Long,
        val parseMs: Long,
        val promptChars: Int,
        val screenChars: Int,
        val stepsChars: Int,
        val elements: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val ok: Boolean,
    ) {
        val totalMs get() = buildMs + httpMs + parseMs
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Test
    fun `profile the live planner call`() {
        val live = System.getenv("SCREENSAATHI_EVAL_LIVE") == "1"
        val key = System.getenv("SARVAM_API_KEY").orEmpty()
        assumeTrue("LIVE profiling not enabled", live && key.isNotBlank())

        val file = EvalPaths.resolve("evals/datasets/golden/planner_v1.jsonl")
        assumeTrue("golden dataset missing", file != null)
        val cases = EvalCase.load(file!!).filterNot { it.replayOnly }
        val repeats = System.getenv("REPEATS")?.toIntOrNull() ?: 5

        val samples = mutableListOf<Sample>()
        // Variants are measured in the SAME run against the SAME cases so the
        // comparison is not confounded by network drift between runs.
        // Round 2. trimmed_screen and capped_output are dropped: the first
        // changed nothing (these screens are 2-3 elements, all interactive) and
        // the second truncated the tool call at 14/25 success. What is left is
        // the only lever the profile actually supports — the model itself,
        // since 99.9% of the time is server-side.
        val variants = listOf("baseline", "model_conversations")

        for (v in variants) {
            repeat(repeats) {
                cases.forEach { c -> samples += callOnce(c, key, v) }
            }
        }

        report(samples, repeats, cases.size)
    }

    // --- one instrumented call ------------------------------------------------

    private fun callOnce(case: EvalCase, key: String, variant: String): Sample {
        val t0 = System.nanoTime()

        val task = case.task.toGuidedTask()
        val stepsText = task.steps.joinToString("\n") {
            val marker = if (it.id == case.currentStepId) "  <- CURRENT" else ""
            "- ${it.id} (resource_id=${it.resourceId})$marker"
        }

        // A: send only elements a step could plausibly target, instead of the
        // whole tree. Nothing else about the request changes.
        val screenText = when (variant) {
            "trimmed_screen" -> trimmedScreen(case)
            else -> case.screen.toPromptText()
        }

        val user = buildString {
            append("User said: \"").append(case.transcript).append("\"\n")
            append("Detected spoken language: ").append(case.spokenLanguage)
            append(" — reply in this language, in its own script.\n\n")
            append("Task: ").append(task.id).append(" — ").append(task.title).append("\n")
            append("Steps:\n").append(stepsText).append("\n\n")
            append(screenText)
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

        val payload = JSONObject()
            .put("model", if (variant == "model_conversations") "sarvam-105b-conversations" else "sarvam-105b")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", "set_plan")
                            .put("description", "Set the next guided step and the element to point at.")
                            .put("parameters", params)
                    )
                )
            )
            .put("tool_choice", "required")
            .put("parallel_tool_calls", false)
            .put("reasoning_effort", JSONObject.NULL)
            .put("temperature", 0.1)
            .put("max_tokens", 300)
            .toString()

        val buildMs = (System.nanoTime() - t0) / 1_000_000

        val req = Request.Builder()
            .url("https://api.sarvam.ai/v1/chat/completions")
            .addHeader("api-subscription-key", key)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        var raw = ""
        val h0 = System.nanoTime()
        val ok = try {
            http.newCall(req).execute().use { r ->
                raw = r.body?.string().orEmpty()
                r.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
        val httpMs = (System.nanoTime() - h0) / 1_000_000

        val p0 = System.nanoTime()
        val plan = if (ok) SarvamPlanner.parse(raw, task, case.spokenLanguage) else null
        val parseMs = (System.nanoTime() - p0) / 1_000_000

        val usage = runCatching { JSONObject(raw).optJSONObject("usage") }.getOrNull()

        return Sample(
            caseId = case.caseId,
            variant = variant,
            buildMs = buildMs,
            httpMs = httpMs,
            parseMs = parseMs,
            promptChars = payload.length,
            screenChars = screenText.length,
            stepsChars = stepsText.length,
            elements = case.screen.elements.size,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            ok = ok && plan != null,
        )
    }

    /**
     * Candidate A+D: send only elements that are interactive or carry text a
     * step could match. Purely a payload reduction — the element indices and
     * ids the planner answers with are unchanged.
     */
    private fun trimmedScreen(case: EvalCase): String {
        val keep = case.screen.elements.filter {
            it.editable || it.clickable || it.resourceId.isNotEmpty()
        }
        if (keep.isEmpty()) return "Screen: ${case.screen.packageName} (no readable elements)"
        val sb = StringBuilder("Screen: ${case.screen.packageName}\nElements:\n")
        for (e in keep) {
            sb.append("[").append(e.index).append("] ").append(e.className)
            if (e.resourceId.isNotEmpty()) sb.append(" id=").append(e.resourceId)
            if (e.text.isNotEmpty()) sb.append(" \"").append(e.text).append("\"")
            val flags = buildString {
                if (e.editable) append("E")
                if (e.clickable) append("C")
            }
            if (flags.isNotEmpty()) sb.append(" ").append(flags)
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun systemPrompt(): String =
        EvalPaths.resolve("app/src/main/assets/prompts/planner_v1.md")?.readText()
            ?: "You are ScreenSaathi's planner. Call set_plan once."

    // --- reporting ------------------------------------------------------------

    private fun pct(sorted: List<Long>, p: Double): Long =
        if (sorted.isEmpty()) 0
        else sorted[minOf(sorted.size - 1, Math.ceil(p / 100.0 * sorted.size).toInt() - 1).coerceAtLeast(0)]

    private fun report(all: List<Sample>, repeats: Int, caseCount: Int) {
        println()
        println("=== PLANNER LATENCY PROFILE ===")
        println("cases=$caseCount  repeats=$repeats  samples/variant=${caseCount * repeats}")
        println()

        all.groupBy { it.variant }.forEach { (variant, s) ->
            val tot = s.map { it.totalMs }.sorted()
            val httpOnly = s.map { it.httpMs }.sorted()
            val okCount = s.count { it.ok }
            println("--- variant: $variant  (ok $okCount/${s.size}) ---")
            println("  total   p50=${pct(tot,50.0)}  p75=${pct(tot,75.0)}  p90=${pct(tot,90.0)}  p95=${pct(tot,95.0)}  min=${tot.first()}  max=${tot.last()}")
            println("  http    p50=${pct(httpOnly,50.0)}  p95=${pct(httpOnly,95.0)}")
            println("  build   avg=${s.map { it.buildMs }.average().let { "%.2f".format(it) }} ms")
            println("  parse   avg=${s.map { it.parseMs }.average().let { "%.2f".format(it) }} ms")
            println("  share   http=${"%.1f".format(100.0 * s.sumOf { it.httpMs } / s.sumOf { it.totalMs }.coerceAtLeast(1))}%")
            println("  payload avg=${s.map { it.promptChars }.average().toInt()} chars  (screen=${s.map { it.screenChars }.average().toInt()}, steps=${s.map { it.stepsChars }.average().toInt()})")
            println("  tokens  in=${s.map { it.promptTokens }.average().toInt()}  out=${s.map { it.completionTokens }.average().toInt()}  total=${s.map { it.promptTokens + it.completionTokens }.average().toInt()}")
            println()
        }

        println("--- per-case (baseline) ---")
        all.filter { it.variant == "baseline" }.groupBy { it.caseId }.toSortedMap()
            .forEach { (id, s) ->
                val t = s.map { it.totalMs }.sorted()
                println("  $id  elements=${s.first().elements}  inTok=${s.map{it.promptTokens}.average().toInt()}  outTok=${s.map{it.completionTokens}.average().toInt()}  p50=${pct(t,50.0)}ms")
            }
        println()
    }
}
