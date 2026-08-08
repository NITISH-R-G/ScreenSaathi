package com.screensaathi.evals

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The eval suite entry point. Runs with `./gradlew :app:testDebugUnitTest`, so
 * it is already wired into the existing CI job — no new toolchain.
 *
 * OFFLINE by default: replays recorded responses through the production
 * SarvamPlanner.parse(). Zero API cost, deterministic, safe on every commit.
 *
 * LIVE mode calls Sarvam for real and is opt-in:
 *     SCREENSAATHI_EVAL_LIVE=1 SARVAM_API_KEY=sk_... ./gradlew :app:testDebugUnitTest
 */
class PlannerEvalTest {

    private val golden get() = EvalPaths.resolve("evals/datasets/golden/planner_v1.jsonl")
    private val adversarial get() = EvalPaths.resolve("evals/datasets/adversarial/planner_v1.jsonl")
    private val baselineFile get() = EvalPaths.resolve("evals/baselines/planner_baseline_v1.json")

    private fun runner(): PlannerRunner {
        val live = System.getenv("SCREENSAATHI_EVAL_LIVE") == "1"
        val key = System.getenv("SARVAM_API_KEY").orEmpty()
        return if (live && key.isNotBlank()) LiveRunner(key, MODEL) else ReplayRunner()
    }

    private fun runSuite(file: File, datasetVersion: String): EvalSummary {
        val runner = runner()
        val results = EvalCase.load(file).map { case ->
            val (plan, ms) = runner.run(case)
            Evaluators.evaluate(case, plan, ms, runner.mode)
        }
        return EvalSummary(results, runner.mode, datasetVersion, PROMPT_VERSION, MODEL)
    }

    /**
     * The gated suite. A critical failure here means the parse/policy layer let
     * through something that would mislead a user — it fails the build.
     */
    @Test
    fun `golden planner suite meets the quality gate`() {
        val file = golden
        assumeTrue("golden dataset not found — skipping", file != null)

        val summary = runSuite(file!!, "golden_v1")
        val baseline = baselineFile?.let { Baseline.load(it) }
        val dir = EvalReport.write(summary, baseline)
        val gate = EvalReport.gate(summary, baseline)

        println(buildString {
            appendLine()
            appendLine("=== ScreenSaathi golden eval (${summary.mode}) ===")
            appendLine("cases        : ${summary.total}")
            appendLine("passed       : ${summary.passed} (${Math.round(summary.passRate * 1000) / 10.0}%)")
            appendLine("critical     : ${summary.criticalFailures.size}")
            appendLine("p50 latency  : ${summary.p50Latency} ms")
            appendLine("report       : ${dir.absolutePath}")
            summary.results.filter { !it.passedAll }.forEach { r ->
                appendLine("FAIL ${r.case.caseId}: " + r.failed.joinToString { "${it.name}(${it.detail})" })
            }
        })

        assertTrue(
            "Quality gate failed:\n" + gate.reasons.joinToString("\n") { " - $it" },
            gate.passed,
        )
    }

    /**
     * Reported, never gated. Some cases here are known-failing on purpose; see
     * docs/evals/FAILURES.md §known-gaps. Asserting on them would either hide
     * the gap or permanently red the build.
     */
    @Test
    fun `adversarial suite is reported without gating the build`() {
        val file = adversarial
        assumeTrue("adversarial dataset not found — skipping", file != null)

        val summary = runSuite(file!!, "adversarial_v1")
        println(buildString {
            appendLine()
            appendLine("=== ScreenSaathi adversarial eval (${summary.mode}) — REPORT ONLY ===")
            appendLine("cases   : ${summary.total}")
            appendLine("passed  : ${summary.passed}")
            appendLine("failures by category: ${summary.byCategory()}")
            summary.results.filter { !it.passedAll }.forEach { r ->
                appendLine("GAP  ${r.case.caseId} [${r.case.failureCategory}]: " +
                    r.failed.joinToString { "${it.severity} ${it.name} — ${it.detail}" })
            }
        })

        // Deliberately no assertion on pass rate. The one invariant that must
        // hold: every case produced a verdict rather than throwing.
        assertTrue("every adversarial case must produce a result", summary.total > 0)
    }

    companion object {
        /** Kept in sync with Sarvam.PLANNER_MODEL; recorded in every report. */
        private const val MODEL = "sarvam-105b"
        private const val PROMPT_VERSION = "planner_v1"
    }
}
