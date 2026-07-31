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
    // sarvam-30b (the model this app was originally built and verified
    // against) was retired by Sarvam sometime between 2026-07-26 and
    // 2026-07-31 - a live call now returns a hard 400 ("Model 'sarvam-30b' has
    // been deprecated... use sarvam-105b"), which the planner correctly turns
    // into a null and falls back to the deterministic StepEngine, but that
    // means the planner was silently dead for every real user in the
    // meantime. Re-verified live against sarvam-105b before switching this
    // constant - see scripts/planner_case.ps1.
    const val PLANNER_MODEL = "sarvam-105b"

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

    /**
     * The planner gets a hard ceiling on the whole call, not just on socket
     * idle time.
     *
     * Its budget is 700 ms and it normally answers in ~600. But an overlong
     * system prompt was observed making sarvam-30b stall well past 40 s
     * without ever tripping the read timeout, because bytes kept trickling.
     * On stage that is a frozen pill. The step engine can answer instantly and
     * for free, so anything beyond a few seconds is strictly worse than
     * falling back — share the connection pool, cap the call.
     */
    val plannerHttp: OkHttpClient by lazy {
        http.newBuilder()
            .callTimeout(PLANNER_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private const val PLANNER_CALL_TIMEOUT_S = 5L
}
