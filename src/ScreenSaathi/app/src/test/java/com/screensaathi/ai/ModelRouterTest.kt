package com.screensaathi.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing policy.
 *
 * The load-bearing assertion is the first one: the hot path must stay
 * on-device. Everything else is about degrading honestly when no provider is
 * configured, which is the shipping state.
 */
class ModelRouterTest {

    private class FakeText(override val isAvailable: Boolean) : TextProvider {
        override val id = "fake-text"
        override val displayName = "Fake text"
        override fun complete(
            prompt: String,
            languageCode: String,
            onResult: (ProviderResult<String>) -> Unit,
        ) = onResult(ProviderResult.Ok("ok"))
    }

    private class FakeVision(override val isAvailable: Boolean) : VisionCapableProvider {
        override val id = "fake-vision"
        override val displayName = "Fake vision"
        override fun analyze(
            request: VisionRequest,
            onResult: (ProviderResult<VisionAnswer>) -> Unit,
        ) = onResult(
            ProviderResult.Ok(VisionAnswer("a scooter", "en-IN", 80, usedPixels = true))
        )
    }

    private fun router(
        text: TextProvider = UnavailableProvider,
        vision: VisionCapableProvider = UnavailableProvider,
    ) = ModelRouter(ProviderRegistry(textProvider = text, visionProvider = vision))

    /**
     * Target resolution must never be routed to a model, configured or not.
     * A frontier model here would be slower, costlier, less private, and less
     * accurate than ranked matching against a known element list.
     */
    @Test
    fun `target resolution stays on device even when every provider is available`() {
        val route = router(text = FakeText(true), vision = FakeVision(true))
            .route(ModelRouter.Task.TARGET_RESOLUTION)

        assertTrue(route is ModelRouter.Route.OnDevice)
    }

    @Test
    fun `intent classification stays on device with no text provider`() {
        val route = router().route(ModelRouter.Task.INTENT_CLASSIFICATION)

        assertTrue(route is ModelRouter.Route.OnDevice)
    }

    @Test
    fun `intent classification uses a text provider when one exists`() {
        val route = router(text = FakeText(true))
            .route(ModelRouter.Task.INTENT_CLASSIFICATION)

        assertTrue(route is ModelRouter.Route.Provider)
        assertEquals("fake-text", (route as ModelRouter.Route.Provider).provider.id)
    }

    @Test
    fun `visual identification is unavailable without a vision provider`() {
        val route = router().route(ModelRouter.Task.VISUAL_IDENTIFICATION)

        assertTrue(route is ModelRouter.Route.Unavailable)
        assertEquals(
            ProviderUnavailable.NO_PROVIDER,
            (route as ModelRouter.Route.Unavailable).reason,
        )
    }

    @Test
    fun `visual identification routes to a configured vision provider`() {
        val route = router(vision = FakeVision(true))
            .route(ModelRouter.Task.VISUAL_IDENTIFICATION)

        assertTrue(route is ModelRouter.Route.Provider)
        assertEquals("fake-vision", (route as ModelRouter.Route.Provider).provider.id)
    }

    /**
     * With no planner configured the deterministic StepEngine still completes
     * scripted tasks — so this degrades on-device rather than reporting
     * unavailable.
     */
    @Test
    fun `agent planning falls back to the on device step engine`() {
        val route = router().route(ModelRouter.Task.AGENT_PLANNING)

        assertTrue(route is ModelRouter.Route.OnDevice)
        assertTrue((route as ModelRouter.Route.OnDevice).reason.contains("StepEngine"))
    }

    @Test
    fun `offline still resolves targets but cannot reason about the screen`() {
        val r = router(text = FakeText(true), vision = FakeVision(true))
        val offline = ModelRouter.Requirements(offline = true)

        assertTrue(r.route(ModelRouter.Task.TARGET_RESOLUTION, offline) is ModelRouter.Route.OnDevice)

        val reasoning = r.route(ModelRouter.Task.SCREEN_REASONING, offline)
        assertTrue(reasoning is ModelRouter.Route.Unavailable)
        assertEquals(
            ProviderUnavailable.OFFLINE,
            (reasoning as ModelRouter.Route.Unavailable).reason,
        )
    }

    @Test
    fun `voice interaction uses the existing sarvam path with no realtime provider`() {
        val route = router().route(ModelRouter.Task.VOICE_INTERACTION)

        assertTrue(route is ModelRouter.Route.OnDevice)
        assertTrue((route as ModelRouter.Route.OnDevice).reason.contains("Sarvam"))
    }

    @Test
    fun `an unconfigured registry reports nothing available`() {
        assertTrue(ProviderRegistry().availableIds().isEmpty())
    }

    @Test
    fun `the unavailable provider degrades instead of throwing`() {
        var result: ProviderResult<String>? = null
        UnavailableProvider.complete("anything", "en-IN") { result = it }

        assertTrue(result is ProviderResult.Unavailable)
        assertEquals(
            ProviderUnavailable.NO_PROVIDER,
            (result as ProviderResult.Unavailable).reason,
        )
    }
}
