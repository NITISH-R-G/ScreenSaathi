package com.screensaathi.sarvam

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The planner is a language model behind a forced tool call, so the parser is
 * the boundary where its output stops being a suggestion and starts driving the
 * UI. These pin what happens when it answers imperfectly.
 */
class SarvamPlannerParseTest {

    private val task = GuidedTask(
        version = 1, id = "pay_bill", title = "Pay Electricity Bill",
        utterances = emptyList(),
        steps = listOf(
            TaskStep(
                id = "amount", resourceId = "amount_field",
                instruction = "Enter the bill amount in this box.",
                instructions = mapOf("hi-IN" to "इस बॉक्स में बिल की रकम भरिए।"),
            ),
            TaskStep(id = "submit", resourceId = "submit_button", instruction = "Tap to pay."),
        ),
    )

    private fun response(args: String): String =
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"set_plan","arguments":${
            org.json.JSONObject.quote(args)
        }}}]}}]}"""

    @Test
    fun `a well-formed Hindi plan is parsed whole`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":4},
                   "instruction":"इस बॉक्स में बिल की रकम भरिए।",
                   "language":"hi-IN","confidence":0.97,"reason":"matches amount request"}"""
            ),
            task, "hi-IN",
        )
        assertNotNull(plan)
        assertEquals("pay_bill", plan!!.intent)
        assertEquals("amount", plan.step)
        assertEquals("amount_field", plan.targetResourceId)
        assertEquals(4, plan.targetIndex)
        assertEquals("hi-IN", plan.language)
        assertEquals(0.97, plan.confidence, 0.001)
        assertEquals("hi-IN", plan.spoken.language)
    }

    @Test
    fun `a mislabelled language is corrected to the script actually written`() {
        // The model answered in English but claimed the user's Hindi. Trusting
        // the claim would 400 at Bulbul and the app would go mute.
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"Enter the bill amount in this box.",
                   "language":"hi-IN","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("en-IN", plan!!.language)
    }

    @Test
    fun `a missing language falls back to the language the user spoke`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"इस बॉक्स में रकम भरिए।","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("hi-IN", plan!!.language)
    }

    @Test
    fun `an empty instruction falls back to the DSL wording in the user's language`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"","language":"hi-IN","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("इस बॉक्स में बिल की रकम भरिए।", plan!!.instruction)
        assertEquals("hi-IN", plan.language)
    }

    @Test
    fun `an invented step is rejected so the caller falls back deterministically`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"teleport",
                   "target":{"resource_id":"x","index":1},
                   "instruction":"go","language":"en-IN","confidence":0.99,"reason":"ok"}"""
            ),
            task, "en-IN",
        )
        assertNull(plan)
    }

    @Test
    fun `the DSL resource id wins over the model's echo`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"submit",
                   "target":{"resource_id":"hallucinated_id","index":2},
                   "instruction":"Tap to pay.","language":"en-IN","confidence":0.8,"reason":"ok"}"""
            ),
            task, "en-IN",
        )
        assertEquals("submit_button", plan!!.targetResourceId)
    }

    @Test
    fun `prose instead of a tool call is rejected`() {
        val raw = """{"choices":[{"message":{"content":"Sure! Let me help you pay."}}]}"""
        assertNull(SarvamPlanner.parse(raw, task, "en-IN"))
    }

    @Test
    fun `an empty response is rejected`() {
        assertNull(SarvamPlanner.parse("{}", task, "en-IN"))
    }
}
