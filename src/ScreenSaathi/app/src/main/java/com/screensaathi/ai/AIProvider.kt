package com.screensaathi.ai

/**
 * Provider-neutral AI capability surface.
 *
 * ScreenSaathi must not become permanently coupled to one vendor. Nothing in
 * this file names OpenAI, Gemini, Claude, Cohere, Bedrock or Sarvam, and
 * nothing assumes a chat-completions shape, a particular auth scheme, or
 * streaming — those are all vendor accidents, and encoding them here is how a
 * codebase ends up unable to switch.
 *
 * Capabilities are separate interfaces rather than one fat one because real
 * providers cover different subsets: a provider may do vision but not realtime
 * audio, or embeddings but not tool calling. A caller depends on the narrow
 * capability it needs, so a provider is never forced to stub methods it cannot
 * honour.
 *
 * **Nothing here requires an API key to compile or run.** Every capability has
 * an unavailable implementation, and the app is expected to work with all of
 * them unconfigured — that is the shipping configuration today.
 */

/** Common identity and readiness for every provider. */
interface AIProvider {
    /** Stable id used in configuration and eval reports, e.g. `gemini`. */
    val id: String

    /** Human-readable, shown in the debug panel. */
    val displayName: String

    /**
     * Whether this provider can serve a request right now: implemented,
     * configured, and credentialled. False is normal, not an error.
     */
    val isAvailable: Boolean
}

/** Why a capability could not serve a request. */
enum class ProviderUnavailable {
    /** No provider registered for this capability. */
    NO_PROVIDER,

    /** Provider exists but has no credential configured. */
    NOT_CONFIGURED,

    /** Interface exists, implementation does not. Honest placeholder state. */
    NOT_IMPLEMENTED,

    /** Configured and tried, but the call failed. */
    PROVIDER_ERROR,

    /** Device is offline and this provider needs the network. */
    OFFLINE,
}

/** Result of any provider call. */
sealed interface ProviderResult<out T> {
    data class Ok<T>(val value: T) : ProviderResult<T>
    data class Unavailable(
        val reason: ProviderUnavailable,
        val detail: String = "",
    ) : ProviderResult<Nothing>
}

/** Plain text in, plain text out. Intent classification, phrasing, summaries. */
interface TextProvider : AIProvider {
    fun complete(
        prompt: String,
        languageCode: String,
        onResult: (ProviderResult<String>) -> Unit,
    )
}

/**
 * Multimodal understanding of a screen selection.
 *
 * Takes a [VisionRequest], never a bare crop — see that type for why.
 */
interface VisionCapableProvider : AIProvider {
    fun analyze(
        request: VisionRequest,
        onResult: (ProviderResult<VisionAnswer>) -> Unit,
    )
}

/** A grounded answer about a selection. */
data class VisionAnswer(
    val text: String,
    /** Language of [text], so TTS is never asked to speak a mislabelled string. */
    val languageCode: String,
    val confidence: Int,
    /**
     * True when the provider believes it answered from the image rather than
     * from the supplied accessibility context. Used to measure how often
     * vision was actually load-bearing — see `docs/IMPACT.md`.
     */
    val usedPixels: Boolean,
)

/** Low-latency streaming voice. Not implemented by anything today. */
interface RealtimeProvider : AIProvider {
    fun isSupported(): Boolean = false
}

/** Vector embeddings. Reserved; nothing in ScreenSaathi needs these yet. */
interface EmbeddingProvider : AIProvider {
    fun embed(
        text: String,
        onResult: (ProviderResult<FloatArray>) -> Unit,
    )
}

/**
 * Web search, for citations.
 *
 * No implementation exists, which is why the assistant shows no source chips —
 * fabricating a plausible domain would be worse than showing none.
 */
interface SearchProvider : AIProvider {
    fun search(
        query: String,
        onResult: (ProviderResult<List<SearchHit>>) -> Unit,
    )
}

data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * The provider used when nothing is configured — which is the shipping state.
 *
 * It reports [ProviderUnavailable.NO_PROVIDER] rather than throwing, so every
 * call site degrades instead of crashing, and the app remains fully functional
 * on the accessibility-only path with no credentials at all.
 */
object UnavailableProvider :
    TextProvider,
    VisionCapableProvider,
    EmbeddingProvider,
    SearchProvider {

    override val id: String get() = "none"
    override val displayName: String get() = "none configured"
    override val isAvailable: Boolean get() = false

    private val unavailable = ProviderResult.Unavailable(
        ProviderUnavailable.NO_PROVIDER,
        "no AI provider is configured; ScreenSaathi is running accessibility-only",
    )

    override fun complete(
        prompt: String,
        languageCode: String,
        onResult: (ProviderResult<String>) -> Unit,
    ) = onResult(unavailable)

    override fun analyze(
        request: VisionRequest,
        onResult: (ProviderResult<VisionAnswer>) -> Unit,
    ) = onResult(unavailable)

    override fun embed(
        text: String,
        onResult: (ProviderResult<FloatArray>) -> Unit,
    ) = onResult(unavailable)

    override fun search(
        query: String,
        onResult: (ProviderResult<List<SearchHit>>) -> Unit,
    ) = onResult(unavailable)
}
