package com.screensaathi.evals

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep
import org.json.JSONObject
import java.io.File

/**
 * One evaluation case, loaded from a versioned JSONL dataset under /evals.
 *
 * The dataset is ground truth and is NEVER rewritten from model output — a
 * failing case means the system regressed or the case is wrong, and deciding
 * which is a human's job. See docs/evals/DATASET.md.
 */
data class EvalCase(
    val caseId: String,
    val dataset: String,
    val taskId: String,
    val workflowStage: String,
    val transcript: String,
    val spokenLanguage: String,
    val currentStepId: String?,
    val task: EvalTask,
    val screen: EvalScreen,
    val expected: Expected,
    val safety: Safety,
    val difficulty: String,
    val failureCategory: String,
    /**
     * A recorded planner response replayed in OFFLINE mode. Present on every
     * golden/adversarial case so the deterministic suite runs at zero API cost
     * and produces byte-identical results on every machine and in CI.
     *
     * These are hand-authored fixtures representing observed model behaviour,
     * not captured production traffic. Marked synthetic in the dataset.
     */
    val recordedResponse: String?,
) {
    data class Expected(
        /** Any of these steps counts as correct. Empty = no step expectation. */
        val acceptableSteps: List<String>,
        val targetResourceId: String?,
        val language: String?,
        /** The chosen target must exist in the screen snapshot. */
        val mustResolveTarget: Boolean,
        val maxInstructionWords: Int,
        /** parse() must reject this response so deterministic fallback engages. */
        val expectRejection: Boolean,
    )

    data class Safety(
        /**
         * Steps the planner must never select for this input. The real hazard in
         * this app: sending an elderly user to an irreversible submit/pay action
         * they did not ask for.
         */
        val mustNotStep: List<String>,
        /** Model should signal low confidence rather than answer confidently. */
        val requiresUncertainty: Boolean,
        val maxConfidence: Double?,
    )

    companion object {
        fun load(file: File): List<EvalCase> =
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("//") }
                .map { parse(JSONObject(it)) }

        private fun parse(o: JSONObject): EvalCase {
            val exp = o.getJSONObject("expected")
            val saf = o.optJSONObject("safety") ?: JSONObject()
            return EvalCase(
                caseId = o.getString("case_id"),
                dataset = o.optString("dataset", "golden"),
                taskId = o.optString("task_id", ""),
                workflowStage = o.optString("workflow_stage", ""),
                transcript = o.optString("transcript", ""),
                spokenLanguage = o.optString("spoken_language", "en-IN"),
                currentStepId = o.optString("current_step_id").takeIf { it.isNotBlank() },
                task = EvalTask.parse(o.getJSONObject("task")),
                screen = EvalScreen.parse(o.getJSONObject("screen")),
                expected = Expected(
                    acceptableSteps = exp.optJSONArray("acceptable_steps")
                        ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    targetResourceId = exp.optString("target_resource_id").takeIf { it.isNotBlank() },
                    language = exp.optString("language").takeIf { it.isNotBlank() },
                    mustResolveTarget = exp.optBoolean("must_resolve_target", false),
                    maxInstructionWords = exp.optInt("max_instruction_words", 0),
                    expectRejection = exp.optBoolean("expect_rejection", false),
                ),
                safety = Safety(
                    mustNotStep = saf.optJSONArray("must_not_step")
                        ?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    requiresUncertainty = saf.optBoolean("requires_uncertainty", false),
                    maxConfidence = saf.optDouble("max_confidence").takeIf { !it.isNaN() },
                ),
                difficulty = o.optString("difficulty", "unknown"),
                failureCategory = o.optString("failure_category", "OTHER"),
                recordedResponse = recordedResponse(o),
            )
        }

        /**
         * Datasets store the tool-call arguments as a plain object
         * (`recorded_args`) and the harness builds the API envelope around it —
         * hand-escaping JSON inside JSON inside JSONL is a reliable way to
         * author broken fixtures. Malformed-response cases use `recorded_raw`
         * verbatim, since their whole point is a wrong envelope.
         */
        private fun recordedResponse(o: JSONObject): String? {
            o.optString("recorded_raw").takeIf { it.isNotBlank() }?.let { return it }
            val args = o.optJSONObject("recorded_args") ?: return null
            val call = JSONObject().put(
                "function",
                JSONObject().put("name", "set_plan").put("arguments", args.toString()),
            )
            return JSONObject().put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("tool_calls", org.json.JSONArray().put(call)),
                    )
                )
            ).toString()
        }
    }
}

/** The task DSL a case is evaluated against, inlined so cases are self-contained. */
data class EvalTask(val id: String, val title: String, val steps: List<Step>) {
    data class Step(val id: String, val resourceId: String, val irreversible: Boolean)

    fun toGuidedTask(): GuidedTask = GuidedTask(
        version = 1,
        id = id,
        title = title,
        utterances = emptyList(),
        steps = steps.map {
            TaskStep(
                id = it.id,
                resourceId = it.resourceId,
                instruction = "Step ${it.id}",
                irreversible = it.irreversible,
            )
        },
    )

    companion object {
        fun parse(o: JSONObject): EvalTask {
            val arr = o.getJSONArray("steps")
            return EvalTask(
                id = o.getString("id"),
                title = o.optString("title", o.getString("id")),
                steps = (0 until arr.length()).map {
                    val s = arr.getJSONObject(it)
                    Step(
                        id = s.getString("id"),
                        resourceId = s.optString("resource_id", ""),
                        irreversible = s.optBoolean("irreversible", false),
                    )
                },
            )
        }
    }
}

/**
 * The accessibility snapshot for a case.
 *
 * Deliberately NOT com.screensaathi.screen.ScreenSnapshot: that type holds
 * android.graphics.Rect, which is a stub under JVM unit tests. Bounds are kept
 * as plain ints here so cases stay pure-JVM and CI-safe, and the prompt text is
 * rendered with the same layout SarvamPlanner sends.
 */
data class EvalScreen(
    val packageName: String,
    val settled: Boolean,
    val elements: List<Element>,
) {
    data class Element(
        val index: Int,
        val resourceId: String,
        val text: String,
        val className: String,
        val bounds: List<Int>,
        val editable: Boolean,
        val clickable: Boolean,
    )

    fun hasResourceId(rid: String): Boolean = elements.any { it.resourceId == rid }

    /** Mirrors ScreenSnapshot.toPromptText() so live evals send the real prompt. */
    fun toPromptText(): String {
        if (elements.isEmpty()) return "Screen: $packageName (no readable elements)"
        val sb = StringBuilder("Screen: $packageName\nElements:\n")
        for (e in elements) {
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

    companion object {
        fun parse(o: JSONObject): EvalScreen {
            val arr = o.optJSONArray("elements")
            return EvalScreen(
                packageName = o.optString("package_name", ""),
                settled = o.optBoolean("settled", true),
                elements = (0 until (arr?.length() ?: 0)).map {
                    val e = arr!!.getJSONObject(it)
                    val b = e.optJSONArray("bounds")
                    Element(
                        index = e.optInt("index", it),
                        resourceId = e.optString("resource_id", ""),
                        text = e.optString("text", ""),
                        className = e.optString("class_name", "View"),
                        bounds = (0 until (b?.length() ?: 0)).map { i -> b!!.getInt(i) },
                        editable = e.optBoolean("editable", false),
                        clickable = e.optBoolean("clickable", false),
                    )
                },
            )
        }
    }
}
