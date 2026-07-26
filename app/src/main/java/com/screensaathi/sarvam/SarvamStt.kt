package com.screensaathi.sarvam

import android.util.Log
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * Saaras v3 speech-to-text (contracts: the STT surface).
 * POST multipart to /speech-to-text. Returns transcript + detected language,
 * or null on any failure so the caller can fall back cleanly.
 *
 * Latency budget: < 800 ms. Keep the recorded clip short.
 */
class SarvamStt {

    data class Result(val transcript: String, val languageCode: String?) {
        /** Detected language, or the safe default when Saaras did not say. */
        val language: String get() = Language.normalize(languageCode)
    }

    /** Blocking. Call off the main thread. */
    fun transcribe(wav: File, mode: String = "transcribe"): Result? {
        if (!Sarvam.hasKey()) {
            Log.w(TAG, "No Sarvam key set — STT unavailable")
            return null
        }
        if (!wav.exists() || wav.length() == 0L) {
            Log.w(TAG, "Empty audio file")
            return null
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", Sarvam.STT_MODEL)
            .addFormDataPart("mode", mode)
            .addFormDataPart(
                "file", "audio.wav",
                wav.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val req = Request.Builder()
            .url(Sarvam.STT_URL)
            .addHeader(Sarvam.AUTH_HEADER, Sarvam.apiKey)
            .post(body)
            .build()

        return try {
            Sarvam.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "STT ${resp.code}: $raw")
                    return null
                }
                val json = JSONObject(raw)
                val transcript = json.optString("transcript", "")
                if (transcript.isBlank()) return null
                // Saaras returns the language in the speaker's own script
                // ("hi-IN" for Devanagari output). Normalize immediately so a
                // code we cannot speak never travels further into the app.
                val detected = json.optString("language_code")
                    .takeIf { it.isNotBlank() }
                    ?.let { Language.normalize(it) }
                Result(transcript, detected)
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT failed: ${e.message}")
            null
        }
    }

    companion object { private const val TAG = "SarvamStt" }
}
