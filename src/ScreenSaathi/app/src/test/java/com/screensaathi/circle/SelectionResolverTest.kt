package com.screensaathi.circle

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolution of a drawn selection against the accessibility tree.
 *
 * These drive [SelectionResolver.resolvePlaced] with explicit
 * [SelectionBox] values rather than the public `resolve(selection, snapshot)`
 * overload, because `android.graphics.Rect` is stubbed to zeros in the JVM
 * unit test runtime — going through it would make every assertion here pass
 * vacuously against empty geometry.
 */
class SelectionResolverTest {

    /** Bounds live in the [SelectionBox]; the Rect on the element is unused. */
    private fun placed(
        box: SelectionBox,
        text: String = "",
        id: String = "",
        cls: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        index: Int = 0,
    ) = SelectionResolver.Placed(
        element = ScreenElement(
            index = index,
            resourceId = id,
            text = text,
            className = cls,
            bounds = Rect(),
            editable = editable,
            clickable = clickable,
        ),
        box = box,
    )

    private fun circleOver(box: SelectionBox) = ScreenSelection.fromPath(
        shape = SelectionShape.CIRCLE,
        path = listOf(
            SelectionPoint(box.left, box.top),
            SelectionPoint(box.right, box.top),
            SelectionPoint(box.right, box.bottom),
            SelectionPoint(box.left, box.bottom),
        ),
        packageName = "com.example.app",
        screenSignature = "sig",
        capturedAtMs = 0L,
    )

    /**
     * The dominant failure mode. The root container overlaps every selection
     * perfectly, so naive overlap scoring always returns the whole screen.
     */
    @Test
    fun `circling a button resolves to the button and not the root container`() {
        val elements = listOf(
            placed(SelectionBox(0, 0, 1080, 2392), id = "root", cls = "android.widget.FrameLayout", index = 0),
            placed(SelectionBox(100, 900, 500, 1000), text = "Book now", cls = "android.widget.Button", clickable = true, index = 1),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(90, 890, 510, 1010)), elements)

        assertNotNull(result.element)
        assertEquals("Book now", result.element!!.text)
        assertTrue(result.possibleActions.contains(SelectionResolver.Action.TAP))
    }

    @Test
    fun `an editable field offers a type action`() {
        val elements = listOf(
            placed(SelectionBox(40, 300, 1040, 460), text = "Where to?", cls = "android.widget.EditText", editable = true),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(30, 290, 1050, 470)), elements)

        assertNotNull(result.element)
        assertTrue(result.possibleActions.contains(SelectionResolver.Action.TYPE))
    }

    /**
     * A lasso around one list row must not pick up its neighbours just because
     * the bounding box reaches them.
     */
    @Test
    fun `a lasso around one row does not select the row below it`() {
        val elements = listOf(
            placed(SelectionBox(100, 500, 900, 600), text = "Electricity Bill", clickable = true, index = 0),
            placed(SelectionBox(100, 620, 900, 720), text = "Water Bill", clickable = true, index = 1),
        )

        val lasso = ScreenSelection.fromPath(
            shape = SelectionShape.FREEFORM,
            path = listOf(
                SelectionPoint(80, 480),
                SelectionPoint(920, 480),
                SelectionPoint(920, 610),
                SelectionPoint(80, 610),
            ),
            packageName = "com.example.app",
            screenSignature = "sig",
            capturedAtMs = 0L,
        )

        val result = SelectionResolver.resolvePlaced(lasso, elements)

        assertNotNull(result.element)
        assertEquals("Electricity Bill", result.element!!.text)
        assertFalse(result.selectedText.contains("Water Bill"))
    }

    @Test
    fun `selecting empty space resolves to nothing rather than guessing`() {
        val elements = listOf(
            placed(SelectionBox(0, 0, 200, 100), text = "Far away"),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(800, 1800, 1000, 2000)), elements)

        assertNull(result.element)
        assertFalse(result.isResolved)
        assertEquals(0, result.confidence)
    }

    @Test
    fun `selected text is collected in reading order`() {
        val elements = listOf(
            placed(SelectionBox(100, 580, 400, 640), text = "1,240", index = 1),
            placed(SelectionBox(100, 500, 400, 560), text = "Total", index = 0),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(80, 480, 420, 660)), elements)

        assertEquals("Total 1,240", result.selectedText)
    }

    @Test
    fun `surrounding context brings in nearby labels for the model`() {
        val elements = listOf(
            placed(SelectionBox(100, 400, 500, 460), text = "Amount due", index = 0),
            placed(SelectionBox(100, 500, 400, 560), text = "1,240", index = 1),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(90, 490, 410, 570)), elements)

        assertTrue(result.surroundingContext.contains("Amount due"))
    }

    @Test
    fun `an unlabelled non interactive box is not claimed as a target`() {
        val elements = listOf(
            placed(SelectionBox(100, 100, 300, 300), cls = "android.view.View"),
        )

        val result = SelectionResolver.resolvePlaced(circleOver(SelectionBox(90, 90, 310, 310)), elements)

        assertNull(result.element)
    }

    @Test
    fun `a tap resolves the control under the finger`() {
        val elements = listOf(
            placed(SelectionBox(0, 0, 1080, 2392), id = "root", cls = "android.widget.FrameLayout", index = 0),
            placed(SelectionBox(400, 1000, 700, 1120), text = "Pay", cls = "android.widget.Button", clickable = true, index = 1),
        )

        val tap = ScreenSelection.fromTap(
            x = 550, y = 1060, radiusPx = 24,
            packageName = "com.example.app", screenSignature = "sig", capturedAtMs = 0L,
        )

        val result = SelectionResolver.resolvePlaced(tap, elements)

        assertNotNull(result.element)
        assertEquals("Pay", result.element!!.text)
    }
}
