package com.screensaathi.task

import android.content.Context
import android.util.Log
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

    private fun normalize(s: String): Set<String> =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()

    private fun overlap(a: Set<String>, b: Set<String>): Int = a.count { it in b }

    companion object {
        private const val TAG = "TaskRepository"
        private const val DIR = "tasks"

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
