package com.screensaathi.sarvam

import com.screensaathi.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Shared Sarvam config + a single tuned OkHttp client. The key is injected at
 * build time via BuildConfig (from local.properties) so it never lives in
 * source. All three surfaces (STT, TTS, planner) authenticate with the
 * `api-subscription-key` header.
 */
object Sarvam {
    const val BASE = "https://api.sarvam.ai"
    const val STT_URL = "$BASE/speech-to-text"
    const val TTS_URL = "$BASE/text-to-speech"
    const val CHAT_URL = "$BASE/v1/chat/completions"

    const val STT_MODEL = "saaras:v3"
    const val TTS_MODEL = "bulbul:v3"
    const val PLANNER_MODEL = "sarvam-30b"

    const val AUTH_HEADER = "api-subscription-key"

    val apiKey: String get() = BuildConfig.SARVAM_API_KEY

    fun hasKey(): Boolean = apiKey.isNotBlank()

    // Tight timeouts: this is a live voice loop, not a batch job. A stalled
    // call must fail fast so the deterministic fallback can take over.
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
