package com.screensaathi.task

import com.screensaathi.sarvam.Language
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline path has to be multilingual too. When the planner is unavailable
 * the DSL wording is all the user gets, so it has to exist in their language —
 * and, when it does not, has to be honest about being English.
 */
class MultilingualTaskTest {

    private val json = """
        {
          "version": 1, "id": "pay_bill", "title": "Pay Electricity Bill",
          "utterances": ["help me pay this bill", "बिजली का बिल भरना है"],
          "steps": [
            { "id": "amount", "resource_id": "amount_field",
              "instruction": "Enter the bill amount in this box.",
              "instructions": { "hi-IN": "इस बॉक्स में बिल की रकम भरिए।", "fr-FR": "Entrez le montant." } }
          ]
        }
    """.trimIndent()

    private fun step() = TaskRepository.parse(JSONObject(json)).steps[0]

    @Test
    fun `a translated step is spoken in the user's language`() {
        val spoken = step().spokenFor("hi-IN")
        assertEquals("hi-IN", spoken.language)
        assertEquals("इस बॉक्स में बिल की रकम भरिए।", spoken.text)
    }

    @Test
    fun `an untranslated language gets English text labelled English`() {
        // Not "English text labelled ta-IN" — that combination is a 400 from
        // Bulbul and the app goes mute.
        val spoken = step().spokenFor("ta-IN")
        assertEquals(Language.DEFAULT, spoken.language)
        assertEquals("Enter the bill amount in this box.", spoken.text)
    }

    @Test
    fun `an unspeakable instruction language is dropped at parse time`() {
        // fr-FR is in the fixture but Bulbul cannot speak it, so it must never
        // reach synthesis.
        assertNull(step().instructions["fr-FR"])
        assertNotNull(step().instructions["hi-IN"])
    }

    @Test
    fun `a bare tag in the DSL is normalized`() {
        val j = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "go",
                  "instructions": { "hi-IN": "चलिए" } } ] }
        """.trimIndent()
        val s = TaskRepository.parse(JSONObject(j)).steps[0]
        assertEquals("चलिए", s.spokenFor("hi").text)
    }

    @Test
    fun `steps with no instructions block still work`() {
        val j = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "go" } ] }
        """.trimIndent()
        val s = TaskRepository.parse(JSONObject(j)).steps[0]
        assertTrue(s.instructions.isEmpty())
        assertEquals(Language.DEFAULT, s.spokenFor("hi-IN").language)
        assertEquals("go", s.spokenFor("hi-IN").text)
    }

    // --- utterance matching ---------------------------------------------------

    private fun repo() = TaskRepository.of(listOf(TaskRepository.parse(JSONObject(json))))

    @Test
    fun `a Devanagari transcript matches a Devanagari utterance`() {
        // The bug this pins: normalize() used to strip with [^a-z0-9 ], which
        // deletes every Devanagari character. Saaras returns Hindi speech AS
        // Devanagari, so the matcher scored zero on the primary demo language
        // and silently matched nothing at all.
        assertEquals("pay_bill", repo().matchByUtterance("बिजली का बिल भरना है")?.id)
    }

    @Test
    fun `an English transcript still matches`() {
        assertEquals("pay_bill", repo().matchByUtterance("help me pay this bill")?.id)
    }

    @Test
    fun `an unrelated transcript matches nothing`() {
        assertNull(repo().matchByUtterance("what is the weather tomorrow"))
    }
}
