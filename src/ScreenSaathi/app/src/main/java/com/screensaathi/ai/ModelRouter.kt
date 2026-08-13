package com.screensaathi.ai

/**
 * Picks which provider serves a given task.
 *
 * There is no universally best model, so none is hardcoded. Routing is by
 * *task shape* — what the work actually needs — and the registry is supplied
 * by the caller, so swapping Gemini for Claude for a local model is
 * configuration rather than a code change.
 *
 * The most important routing rule is the one that calls no model at all:
 * target resolution and intent classification are handled on-device today
 * ([Task.TARGET_RESOLUTION], [Task.INTENT_CLASSIFICATION]), and routing them
 * to a frontier model would be slower, costlier, less private and — for
 * ranked text matching against a known element list — less accurate.
 *
 * Android-free so the policy can be unit tested.
 */
class ModelRouter(
    private val registry: ProviderRegistry,
) {

    /** What the caller is trying to do. */
    enum class Task {
        /** Find a named control on screen. On-device; no model. */
        TARGET_RESOLUTION,

        /** Question vs action. On-device keyword classifier today. */
        INTENT_CLASSIFICATION,

        /** Explain a screen or a selection in words. */
        SCREEN_REASONING,

        /** Identify something only visible as pixels. */
        VISUAL_IDENTIFICATION,

        /** Multi-step task planning against a live UI. */
        AGENT_PLANNING,

        /** Speech in, speech out, latency-critical. */
        VOICE_INTERACTION,
    }

    /** Constraints that change the answer. */
    data class Requirements(
        val needsVision: Boolean = false,
        val needsDeepReasoning: Boolean = false,
        val latencySensitive: Boolean = false,
        val offline: Boolean = false,
    )

    /** Where a task was routed, and why. */
    sealed interface Route {
        /**
         * Handled on-device with no model call. The preferred outcome: free,
         * instant, private, and explainable.
         */
        data class OnDevice(val reason: String) : Route

        data class Provider(val provider: AIProvider, val reason: String) : Route

        data class Unavailable(
            val reason: ProviderUnavailable,
            val detail: String,
        ) : Route
    }

    fun route(task: Task, requirements: Requirements = Requirements()): Route {
        // Offline short-circuit. Everything that can run on-device still does;
        // everything else is honestly unavailable rather than silently hanging
        // on a network call that cannot succeed.
        if (requirements.offline && task !in ON_DEVICE_TASKS) {
            return Route.Unavailable(
                ProviderUnavailable.OFFLINE,
                "device is offline and $task needs a network provider",
            )
        }

        return when (task) {
            Task.TARGET_RESOLUTION -> Route.OnDevice(
                "ranked matching against the accessibility tree beats a model here on " +
                    "accuracy, latency, cost and privacy"
            )

            Task.INTENT_CLASSIFICATION -> {
                // Keyword classification is deterministic and instant. A text
                // provider is a genuine upgrade for phrasing the classifier
                // does not cover, but only when one is actually configured.
                val text = registry.text()
                if (text.isAvailable) {
                    Route.Provider(text, "text provider available for open-vocabulary intent")
                } else {
                    Route.OnDevice("on-device keyword classifier; no text provider configured")
                }
            }

            Task.VISUAL_IDENTIFICATION -> requireVision("visual identification needs pixels")

            Task.SCREEN_REASONING ->
                if (requirements.needsVision) {
                    requireVision("screen reasoning requested with vision")
                } else {
                    requireText("screen reasoning over accessibility text")
                }

            Task.AGENT_PLANNING -> {
                // Planning is where a stronger model genuinely pays for itself:
                // a wrong plan costs the user a wrong tap on their own device.
                val provider = if (requirements.needsVision) {
                    registry.vision() as? AIProvider
                } else {
                    registry.text() as? AIProvider
                }
                if (provider?.isAvailable == true) {
                    Route.Provider(provider, "agent planning")
                } else {
                    // The deterministic StepEngine still completes scripted
                    // tasks with no network at all — see docs/ARCHITECTURE.md.
                    Route.OnDevice("no planner provider; deterministic StepEngine handles scripted tasks")
                }
            }

            Task.VOICE_INTERACTION -> {
                val realtime = registry.realtime()
                if (realtime?.isAvailable == true) {
                    Route.Provider(realtime, "realtime provider available")
                } else {
                    // Sarvam STT/TTS is the shipping voice path and is not a
                    // realtime provider; it is driven directly by
                    // SessionController rather than through this router.
                    Route.OnDevice("existing Sarvam STT/TTS path")
                }
            }
        }
    }

    private fun requireVision(why: String): Route {
        val vision = registry.vision()
        return if (vision.isAvailable) {
            Route.Provider(vision, why)
        } else {
            Route.Unavailable(
                ProviderUnavailable.NO_PROVIDER,
                "$why, but no vision provider is configured",
            )
        }
    }

    private fun requireText(why: String): Route {
        val text = registry.text()
        return if (text.isAvailable) {
            Route.Provider(text, why)
        } else {
            Route.Unavailable(
                ProviderUnavailable.NO_PROVIDER,
                "$why, but no text provider is configured",
            )
        }
    }

    private companion object {
        /** Tasks that need no network and therefore survive being offline. */
        val ON_DEVICE_TASKS = setOf(
            Task.TARGET_RESOLUTION,
            Task.INTENT_CLASSIFICATION,
        )
    }
}

/**
 * The set of providers currently configured.
 *
 * Defaults to [UnavailableProvider] throughout, which is the shipping
 * configuration: no credentials, no network AI, full accessibility-only
 * functionality.
 */
open class ProviderRegistry(
    private val textProvider: TextProvider = UnavailableProvider,
    private val visionProvider: VisionCapableProvider = UnavailableProvider,
    private val realtimeProvider: RealtimeProvider? = null,
    private val searchProvider: SearchProvider = UnavailableProvider,
) {
    open fun text(): TextProvider = textProvider
    open fun vision(): VisionCapableProvider = visionProvider
    open fun realtime(): RealtimeProvider? = realtimeProvider
    open fun search(): SearchProvider = searchProvider

    /** Ids of everything actually available, for the debug panel and evals. */
    fun availableIds(): List<String> = listOfNotNull(
        textProvider.takeIf { it.isAvailable }?.id,
        visionProvider.takeIf { it.isAvailable }?.id,
        realtimeProvider?.takeIf { it.isAvailable }?.id,
        searchProvider.takeIf { it.isAvailable }?.id,
    ).distinct()
}
