package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetResolverTest {

    @Test
    fun `does not resolve a natural language query to a non interactive id container`() {
        val snapshot = ScreenSnapshot(
            packageName = "com.google.android.calculator",
            settled = true,
            elements = listOf(
                ScreenElement(
                    index = 0,
                    resourceId = "main_calculator",
                    text = "",
                    className = "ViewGroup",
                    bounds = Rect(0, 0, 1080, 2392),
                    editable = false,
                    clickable = false,
                ),
            ),
        )

        val result = TargetResolver.resolve("Calculator", snapshot)

        assertTrue(result is TargetResolver.Result.NotFound)
    }
}
