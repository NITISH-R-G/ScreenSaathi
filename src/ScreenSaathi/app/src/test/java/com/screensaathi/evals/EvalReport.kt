package com.screensaathi.evals

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes machine-readable and human-readable eval reports, and enforces the
 * quality gate against a recorded baseline (docs/evals/README.md §quality-gate).
 *
 * Individual failures are always listed. A single headline score is never
 * allowed to hide a critical safety regression.
 */
object EvalReport {

    private const val OUT_DIR = "eval-results"

    fun write(summary: EvalSummary, baseline: Baseline?): File {
        val root = EvalPaths.resolve("evals") ?.parentFile ?: File("").absoluteFile
        val dir = File(root, OUT_DIR).apply { mkdirs() }

        File(dir, "latest.json").writeText(toJson(summary, baseline).toString(2))
        val md = toMarkdown(summary, baseline)
        File(dir, "latest.md").writeText(md)
        return dir
    }

    private fun toJson(s: EvalSummary, baseline: Baseline?): JSONObject {
        val cases = JSONArray()
        s.results.forEach { r ->
            val checks = JSONArray()
            r.checks.forEach { c ->
                checks.put(
                    JSONObject()
                        .put("name", c.name).put("category", c.category)
                        .put("severity", c.severity.name)
                        .put("passed", c.passed).put("skipped", c.skipped)
                        .put("detail", c.detail)
                )
            }
            cases.put(
                JSONObject()
                    .put("case_id", r.case.caseId)
                    .put("dataset", r.case.dataset)
                    .put("workflow_stage", r.case.workflowStage)
                    .put("difficulty", r.case.difficulty)
                    .put("transcript", r.case.transcript)
                    .put("expected_steps", JSONArray(r.case.expected.acceptableSteps))
                    .put("actual_step", r.plan?.step ?: JSONObject.NULL)
                    .put("actual_target", r.plan?.targetResourceId ?: JSONObject.NULL)
                    .put("actual_language", r.plan?.language ?: JSONObject.NULL)
                    .put("actual_instruction", r.plan?.instruction ?: JSONObject.NULL)
                    .put("confidence", r.plan?.confidence ?: JSONObject.NULL)
                    .put("latency_ms", r.latencyMs)
                    .put("passed", r.passedAll)
                    .put("checks", checks)
            )
        }

        return JSONObject()
            .put("mode", s.mode)
            .put("model", s.model)
            .put("prompt_version", s.promptVersion)
            .put("dataset_version", s.datasetVersion)
            .put("total_cases", s.total)
            .put("passed_cases", s.passed)
            .put("pass_rate", round(s.passRate))
            .put("critical_failures", s.criticalFailures.size)
            .put("p50_latency_ms", s.p50Latency)
            .put("by_check", JSONObject(s.byCheck().mapValues { round(it.value) }))
            .put("failures_by_category", JSONObject(s.byCategory()))
            .put("baseline", baseline?.let {
                JSONObject()
                    .put("pass_rate", round(it.passRate))
                    .put("critical_failures", it.criticalFailures)
            } ?: JSONObject.NULL)
            .put("gate", JSONObject(gate(s, baseline).toMap()))
            .put("cases", cases)
    }

    private fun toMarkdown(s: EvalSummary, baseline: Baseline?): String = buildString {
        val g = gate(s, baseline)
        appendLine("# ScreenSaathi eval report")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Mode | ${s.mode} |")
        appendLine("| Model | ${s.model} |")
        appendLine("| Prompt | ${s.promptVersion} |")
        appendLine("| Dataset | ${s.datasetVersion} |")
        appendLine("| Cases | ${s.total} |")
        appendLine("| Pass rate | ${pct(s.passRate)} |")
        appendLine("| Critical failures | ${s.criticalFailures.size} |")
        appendLine("| p50 latency | ${s.p50Latency} ms |")
        appendLine("| **Gate** | **${if (g.passed) "PASS" else "FAIL"}** |")
        appendLine()

        if (baseline != null) {
            val delta = s.passRate - baseline.passRate
            val dir = when {
                delta > 0.001 -> "IMPROVEMENT"
                delta < -0.001 -> "REGRESSION"
                else -> "NO CHANGE"
            }
            appendLine("## vs baseline")
            appendLine()
            appendLine("- pass rate ${pct(baseline.passRate)} → ${pct(s.passRate)} (**$dir**)")
            appendLine("- critical failures ${baseline.criticalFailures} → ${s.criticalFailures.size}")
            appendLine()
        }

        appendLine("## Per-check results")
        appendLine()
        appendLine("| Check | Pass rate |")
        appendLine("|---|---|")
        s.byCheck().toSortedMap().forEach { (k, v) -> appendLine("| $k | ${pct(v)} |") }
        appendLine()

        val failures = s.results.filter { !it.passedAll }
        appendLine("## Failures (${failures.size})")
        appendLine()
        if (failures.isEmpty()) {
            appendLine("None.")
        } else {
            failures.forEach { r ->
                appendLine("### ${r.case.caseId} — ${r.case.workflowStage} (${r.case.difficulty})")
                appendLine()
                appendLine("- transcript: `${r.case.transcript}`")
                appendLine("- expected step: ${r.case.expected.acceptableSteps}")
                appendLine("- actual step: `${r.plan?.step}` target `${r.plan?.targetResourceId}`")
                appendLine("- instruction: `${r.plan?.instruction}`")
                r.failed.forEach { c ->
                    appendLine("- **${c.severity} ${c.category} / ${c.name}** — ${c.detail}")
                }
                appendLine()
            }
        }

        if (g.reasons.isNotEmpty()) {
            appendLine("## Gate failures")
            appendLine()
            g.reasons.forEach { appendLine("- $it") }
        }
    }

    // --- Quality gate --------------------------------------------------------

    data class Gate(val passed: Boolean, val reasons: List<String>) {
        fun toMap(): Map<String, Any> =
            mapOf("passed" to passed, "reasons" to JSONArray(reasons))
    }

    /**
     * A change is rejected on ANY of:
     *  - a critical (safety / schema) failure exists at all
     *  - pass rate regressed more than 2 points against baseline
     * Improving one metric never buys a critical regression.
     */
    fun gate(s: EvalSummary, baseline: Baseline?): Gate {
        val reasons = mutableListOf<String>()
        if (s.criticalFailures.isNotEmpty()) {
            reasons += "${s.criticalFailures.size} CRITICAL failure(s): " +
                s.criticalFailures.joinToString { "${it.name} (${it.detail})" }
        }
        if (baseline != null && s.passRate < baseline.passRate - 0.02) {
            reasons += "pass rate regressed ${pct(baseline.passRate)} → ${pct(s.passRate)}"
        }
        return Gate(reasons.isEmpty(), reasons)
    }

    private fun round(d: Double) = Math.round(d * 1000.0) / 1000.0
    private fun pct(d: Double) = "${Math.round(d * 1000.0) / 10.0}%"
}

/** A recorded baseline run. See evals/baselines/. */
data class Baseline(
    val passRate: Double,
    val criticalFailures: Int,
    val model: String,
    val promptVersion: String,
    val datasetVersion: String,
) {
    companion object {
        fun load(file: File): Baseline? {
            if (!file.exists()) return null
            val o = JSONObject(file.readText())
            return Baseline(
                passRate = o.optDouble("pass_rate", 0.0),
                criticalFailures = o.optInt("critical_failures", 0),
                model = o.optString("model", "unknown"),
                promptVersion = o.optString("prompt_version", "unknown"),
                datasetVersion = o.optString("dataset_version", "unknown"),
            )
        }
    }
}
