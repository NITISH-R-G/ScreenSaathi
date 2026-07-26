package com.screensaathi.task

import android.content.Context
import android.util.Log
import com.screensaathi.sarvam.Language
import org.json.JSONObject

/**
 * Loads every task in assets/tasks/ into memory. Generic over the DSL from M1,
 * so adding a task (M3) is dropping a JSON file here — no code change.
 */
class TaskRepository private constructor(val tasks: List<GuidedTask>) {

    fun byId(id: String): GuidedTask? = tasks.firstOrNull { it.id == id }

    /**
     * Deterministic keyword fallback used only when the planner is unavailable.
     * Picks the task whose example utterances best overlap the transcript.
     * Returns null if nothing matches, so the caller can stay silent rather
     * than guess.
     */
    fun matchByUtterance(transcript: String): GuidedTask? {
        val words = normalize(transcript)
        if (words.isEmpty()) return null
        var best: GuidedTask? = null
        var bestScore = 0
        for (task in tasks) {
            val score = task.utterances.maxOfOrNull { overlap(words, normalize(it)) } ?: 0
            if (score > bestScore) {
                bestScore = score
                best = task
            }
        }
        return if (bestScore > 0) best else null
    }

    /**
     * Lowercase, strip punctuation, split into words of 3+ characters.
     *
     * Punctuation is removed by *category*, not by an `[^a-z0-9 ]` allowlist.
     * That allowlist deleted every non-Latin character, so a Saaras transcript
     * of Hindi speech — which comes back in Devanagari, e.g.
     * "बिजली का बिल भरना है" — normalized to the empty set and matched nothing
     * at all. Hindi is the primary demo language, so the matcher was silently
     * dead on the path that matters most.
     */
    private fun normalize(s: String): Set<String> =
        s.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.length >= MIN_WORD_LENGTH }
            .toSet()

    private fun overlap(a: Set<String>, b: Set<String>): Int = a.count { it in b }

    companion object {
        private const val TAG = "TaskRepository"
        private const val DIR = "tasks"

        /** Drops "a"/"is"/"का" style filler without dropping real content words. */
        private const val MIN_WORD_LENGTH = 3

        /** Visible for testing: build a repository without an AssetManager. */
        fun of(tasks: List<GuidedTask>): TaskRepository = TaskRepository(tasks)

        fun load(context: Context): TaskRepository {
            val out = mutableListOf<GuidedTask>()
            val am = context.assets
            val files = try {
                am.list(DIR)?.filter { it.endsWith(".json") } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot list $DIR", e)
                emptyList()
            }
            for (name in files) {
                try {
                    val json = am.open("$DIR/$name").bufferedReader().use { it.readText() }
                    out.add(parse(JSONObject(json)))
                } catch (e: Exception) {
                    Log.e(TAG, "Skipping malformed task asset: $name", e)
                }
            }
            Log.d(TAG, "Loaded ${out.size} task(s): ${out.map { it.id }}")
            return TaskRepository(out)
        }

        /**
         * Optional `"instructions": { "hi-IN": "…" }` block on a step. Unknown
         * or unspeakable codes are dropped here rather than at synthesis time,
         * where they would surface as a silent 400 from Bulbul.
         */
        private fun parseInstructions(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in o.keys()) {
                val text = o.optString(key)
                if (text.isBlank()) continue
                if (!Language.isSupported(key)) {
                    Log.w(TAG, "Ignoring unsupported instruction language '$key'")
                    continue
                }
                out[Language.normalize(key)] = text
            }
            return out
        }

        fun parse(o: JSONObject): GuidedTask {
            val stepsJson = o.getJSONArray("steps")
            val steps = ArrayList<TaskStep>(stepsJson.length())
            for (i in 0 until stepsJson.length()) {
                val s = stepsJson.getJSONObject(i)
                val h = s.optJSONObject("highlight")
                steps.add(
                    TaskStep(
                        id = s.getString("id"),
                        resourceId = s.getString("resource_id"),
                        instruction = s.getString("instruction"),
                        instructions = parseInstructions(s.optJSONObject("instructions")),
                        expectsValue = s.optBoolean("expects_value", false),
                        highlight = Highlight(
                            shape = h?.optString("shape", "rect") ?: "rect",
                            pulse = h?.optBoolean("pulse", true) ?: true,
                        ),
                    )
                )
            }
            val utt = o.optJSONArray("utterances")
            val utterances = if (utt != null) {
                (0 until utt.length()).map { utt.getString(it) }
            } else {
                emptyList()
            }
            return GuidedTask(
                version = o.getInt("version"),
                id = o.getString("id"),
                title = o.getString("title"),
                utterances = utterances,
                steps = steps,
            )
        }
    }
}
