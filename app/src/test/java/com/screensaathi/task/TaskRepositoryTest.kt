package com.screensaathi.task

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task DSL is data, not code — a task is a JSON file dropped into assets.
 * These tests pin the parser's contract (contracts/task.schema.json) so adding
 * task #2 in M3 cannot silently change how task #1 is read.
 */
class TaskRepositoryTest {

    private val payBill = """
        {
          "version": 1,
          "id": "pay_bill",
          "title": "Pay Electricity Bill",
          "utterances": ["help me pay this bill", "bijli ka bill bharna hai"],
          "steps": [
            { "id": "amount", "resource_id": "amount_field",
              "instruction": "Enter the bill amount in this box.",
              "expects_value": true, "highlight": { "shape": "rect", "pulse": true } },
            { "id": "submit", "resource_id": "submit_button",
              "instruction": "Tap this button to pay the bill.",
              "expects_value": false, "highlight": { "shape": "circle", "pulse": false } }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses every frozen field of the task DSL`() {
        val t = TaskRepository.parse(JSONObject(payBill))
        assertEquals(1, t.version)
        assertEquals("pay_bill", t.id)
        assertEquals("Pay Electricity Bill", t.title)
        assertEquals(2, t.utterances.size)
        assertEquals(2, t.steps.size)

        val amount = t.steps[0]
        assertEquals("amount", amount.id)
        assertEquals("amount_field", amount.resourceId)
        assertEquals("Enter the bill amount in this box.", amount.instruction)
        assertTrue(amount.expectsValue)
        assertEquals("rect", amount.highlight.shape)
        assertTrue(amount.highlight.pulse)

        val submit = t.steps[1]
        assertEquals("circle", submit.highlight.shape)
        assertFalse(submit.highlight.pulse)
    }

    @Test
    fun `highlight defaults to a pulsing rect when the block is absent`() {
        val json = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "go" } ] }
        """.trimIndent()
        val step = TaskRepository.parse(JSONObject(json)).steps[0]
        assertEquals("rect", step.highlight.shape)
        assertTrue(step.highlight.pulse)
        assertFalse(step.expectsValue)
    }

    @Test
    fun `utterances are optional`() {
        val json = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "go" } ] }
        """.trimIndent()
        assertTrue(TaskRepository.parse(JSONObject(json)).utterances.isEmpty())
    }

    @Test
    fun `step lookup by id resolves and rejects`() {
        val t = TaskRepository.parse(JSONObject(payBill))
        assertEquals(1, t.indexOfStep("submit"))
        assertEquals("submit_button", t.stepById("submit")?.resourceId)
        assertEquals(-1, t.indexOfStep("nope"))
        assertEquals(null, t.stepById("nope"))
    }
}
