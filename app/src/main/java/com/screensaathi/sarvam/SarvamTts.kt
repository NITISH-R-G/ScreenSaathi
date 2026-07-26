package com.screensaathi.sarvam

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bulbul v3 text-to-speech (the TTS surface).
 * POST JSON to /text-to-speech; the response carries base64 WAV clips in an
 * `audios` array. Returns decoded WAV bytes, or null on failure.
 *
 * Latency budget: < 900 ms to first audio. Keep instructions short.
 */
class SarvamTts {

    /**
     * Blocking. Call off the main thread. Returns WAV bytes ready for playback.
     *
     * Takes a [Spoken] rather than a loose text+code pair on purpose. Bulbul
     * rejects a mismatch outright —
     * "Text must contain at least one character from the allowed languages" —
     * and a rejected call is indistinguishable from the app having nothing to
     * say. The language is reconciled against the text one last time here, so
     * no caller can make the app go mute by mislabelling a string.
     */
    fun synthesize(spoken: Spoken, speaker: String = DEFAULT_SPEAKER): ByteArray? {
        if (!Sarvam.hasKey()) {
            Log.w(TAG, "No Sarvam key set — TTS unavailable")
            return null
        }
        if (spoken.text.isBlank()) return null
        val languageCode = Language.reconcile(spoken.text, spoken.language)
        if (languageCode != spoken.language) {
            Log.w(TAG, "Language '${spoken.language}' does not match the text; sending $languageCode")
        }

        val payload = JSONObject()
            .put("text", spoken.text)
            .put("target_language_code", languageCode)
            .put("speaker", speaker)
            .put("model", Sarvam.TTS_MODEL)
            .toString()

        val req = Request.Builder()
            .url(Sarvam.TTS_URL)
            .addHeader(Sarvam.AUTH_HEADER, Sarvam.apiKey)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            Sarvam.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "TTS ${resp.code}: $raw")
                    return null
                }
                val audios: JSONArray = JSONObject(raw).optJSONArray("audios") ?: return null
                if (audios.length() == 0) return null
                Base64.decode(audios.getString(0), Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SarvamTts"
        // Verified Bulbul v3 speaker. 'anand' = Warm & Reassuring, which fits the
        // calm-guide persona. 'ishita' is the documented safe cross-language
        // fallback if 'anand' underperforms on a given language.
        private const val DEFAULT_SPEAKER = "anand"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
