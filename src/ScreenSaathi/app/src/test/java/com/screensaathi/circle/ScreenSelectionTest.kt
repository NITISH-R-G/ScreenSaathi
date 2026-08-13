package com.screensaathi.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection geometry is deliberately Android-free, so all of it is
 * exercised here on the JVM. These are the cases that decide whether "what did
 * the user circle" is right or subtly wrong.
 */
class ScreenSelectionTest {

    private fun lasso(points: List<SelectionPoint>) = ScreenSelection.fromPath(
        shape = SelectionShape.FREEFORM,
        path = points,
        packageName = "com.example",
        screenSignature = "sig",
        capturedAtMs = 0L,
    )

    @Test
    fun `bounding box wraps the whole drawn path`() {
        val s = lasso(
            listOf(
                SelectionPoint(100, 200),
                SelectionPoint(400, 180),
                SelectionPoint(380, 500),
                SelectionPoint(120, 520),
            )
        )

        assertEquals(100, s.bounds.left)
        assertEquals(180, s.bounds.top)
        assertEquals(400, s.bounds.right)
        assertEquals(520, s.bounds.bottom)
    }

    @Test
    fun `point inside a drawn loop is inside the selection`() {
        val s = lasso(
            listOf(
                SelectionPoint(0, 0),
                SelectionPoint(100, 0),
                SelectionPoint(100, 100),
                SelectionPoint(0, 100),
            )
        )

        assertTrue(s.containsPoint(50, 50))
    }

    /**
     * The case a bounding box gets wrong. A V-shaped lasso has a bounding box
     * covering the notch between its arms, but the notch is not selected.
     */
    @Test
    fun `point in the notch of a concave lasso is not selected`() {
        val v = lasso(
            listOf(
                SelectionPoint(0, 0),
                SelectionPoint(40, 0),
                SelectionPoint(50, 80),
                SelectionPoint(60, 0),
                SelectionPoint(100, 0),
                SelectionPoint(100, 100),
                SelectionPoint(0, 100),
            )
        )

        // Inside the bounding box, but up in the notch between the two arms.
        assertTrue(v.bounds.contains(50, 20))
        assertFalse(v.containsPoint(50, 20))

        // Below the notch, genuinely enclosed.
        assertTrue(v.containsPoint(50, 95))
    }

    @Test
    fun `a rectangle selection uses its box even when given few points`() {
        val rect = ScreenSelection(
            shape = SelectionShape.RECTANGLE,
            path = listOf(SelectionPoint(10, 10), SelectionPoint(90, 90)),
            bounds = SelectionBox(10, 10, 90, 90),
            packageName = "com.example",
            screenSignature = "sig",
            capturedAtMs = 0L,
        )

        assertTrue(rect.containsPoint(50, 50))
        assertFalse(rect.containsPoint(5, 5))
    }

    @Test
    fun `a tap becomes a box big enough to intersect something`() {
        val tap = ScreenSelection.fromTap(
            x = 500,
            y = 900,
            radiusPx = 24,
            packageName = "com.example",
            screenSignature = "sig",
            capturedAtMs = 0L,
        )

        assertFalse(tap.bounds.isEmpty)
        assertTrue(tap.containsPoint(500, 900))
        assertTrue(tap.containsPoint(euclideanEdge(500, 24), 900))
    }

    private fun euclideanEdge(center: Int, radius: Int) = center + radius - 1

    @Test
    fun `intersection area is zero for disjoint boxes`() {
        val a = SelectionBox(0, 0, 100, 100)
        val b = SelectionBox(200, 200, 300, 300)

        assertEquals(0L, a.intersectionArea(b))
    }

    @Test
    fun `intersection area is computed for overlapping boxes`() {
        val a = SelectionBox(0, 0, 100, 100)
        val b = SelectionBox(50, 50, 150, 150)

        assertEquals(2500L, a.intersectionArea(b))
    }

    @Test
    fun `area is a Long so large regions do not overflow`() {
        val fullScreen = SelectionBox(0, 0, 1080, 2392)
        assertEquals(1080L * 2392L, fullScreen.area)

        // Int arithmetic would wrap negative here; the container penalty in
        // SelectionResolver divides by this, so a negative area would invert
        // the whole ranking.
        val huge = SelectionBox(0, 0, 100_000, 100_000)
        assertEquals(10_000_000_000L, huge.area)
        assertTrue(huge.area > Int.MAX_VALUE)
    }

    @Test
    fun `selection without a crop reports that it has no pixels`() {
        val s = lasso(listOf(SelectionPoint(0, 0), SelectionPoint(10, 0), SelectionPoint(10, 10)))

        assertFalse(s.hasPixels)
    }
}
