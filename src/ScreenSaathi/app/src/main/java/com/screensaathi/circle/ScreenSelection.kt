package com.screensaathi.circle

/**
 * What the user circled, as data.
 *
 * Deliberately Android-free arithmetic — same reasoning as
 * [com.screensaathi.overlay.AssistantPlacement]: the geometry that decides
 * *what was selected* is the part most likely to be wrong in a subtle way, so
 * it has to be reasonable about and unit-testable without a device. The
 * Android boundary (Rect, Path, MotionEvent) is converted at the edges.
 *
 * A selection is not a centre point. A lasso drawn around one row of a list
 * has to exclude the rows above and below it, and a bounding box alone cannot
 * express that — so freeform selections keep their full path and test
 * containment against the actual polygon.
 */

/** A point in screen pixels. */
data class SelectionPoint(val x: Int, val y: Int)

/** An axis-aligned box in screen pixels. */
data class SelectionBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** Long, because a full-screen box on a modern phone overflows Int. */
    val area: Long get() = width.toLong() * height.toLong()

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean =
        x in left..right && y in top..bottom

    /** Area shared with [other]; 0 when they do not overlap. */
    fun intersectionArea(other: SelectionBox): Long {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        if (r <= l || b <= t) return 0L
        return (r - l).toLong() * (b - t).toLong()
    }

    companion object {
        val EMPTY = SelectionBox(0, 0, 0, 0)

        /** Bounding box of a drawn path. */
        fun around(points: List<SelectionPoint>): SelectionBox {
            if (points.isEmpty()) return EMPTY
            var l = points[0].x
            var t = points[0].y
            var r = points[0].x
            var b = points[0].y
            for (p in points) {
                if (p.x < l) l = p.x
                if (p.x > r) r = p.x
                if (p.y < t) t = p.y
                if (p.y > b) b = p.y
            }
            return SelectionBox(l, t, r, b)
        }
    }
}

/** How the user drew the selection. */
enum class SelectionShape {
    /** Two-corner drag. */
    RECTANGLE,

    /** A closed-ish loop, treated as its polygon. */
    CIRCLE,

    /** An arbitrary lasso, treated as its polygon. */
    FREEFORM,

    /** A single tap — "what is this thing under my finger". */
    POINT,
}

/**
 * A completed selection: the geometry, plus enough context to know which
 * screen it was taken from.
 *
 * [cropPath] is the on-disk PNG of the selected region when a screenshot was
 * available, and null when it was not — capture can genuinely fail (pre-API-30
 * device, secure window, capture refused). Callers must treat it as optional
 * rather than assuming pixels exist.
 *
 * [screenSignature] is [com.screensaathi.screen.ScreenSnapshot.signature] at
 * selection time, so a stale selection can be detected after the user
 * navigates away instead of silently resolving against the wrong screen.
 */
data class ScreenSelection(
    val shape: SelectionShape,
    val path: List<SelectionPoint>,
    val bounds: SelectionBox,
    val packageName: String,
    val screenSignature: String,
    val capturedAtMs: Long,
    val cropPath: String? = null,
) {
    val hasPixels: Boolean get() = cropPath != null

    /**
     * Is [x],[y] inside the selection as drawn?
     *
     * Rectangles and points use the box. Loops use ray casting against the
     * real path, so a lasso around one list row does not swallow its
     * neighbours just because they share a bounding box.
     */
    fun containsPoint(x: Int, y: Int): Boolean = when (shape) {
        SelectionShape.RECTANGLE, SelectionShape.POINT -> bounds.contains(x, y)
        SelectionShape.CIRCLE, SelectionShape.FREEFORM ->
            if (path.size < 3) bounds.contains(x, y) else polygonContains(x, y)
    }

    /**
     * Standard even-odd ray cast. Counts crossings of a ray going +x from
     * (x, y); an odd count means inside. The path is treated as implicitly
     * closed, which is what a hand-drawn "circle" always is — nobody closes
     * the loop exactly.
     */
    private fun polygonContains(x: Int, y: Int): Boolean {
        var inside = false
        var j = path.size - 1
        for (i in path.indices) {
            val xi = path[i].x.toDouble()
            val yi = path[i].y.toDouble()
            val xj = path[j].x.toDouble()
            val yj = path[j].y.toDouble()
            val straddles = (yi > y) != (yj > y)
            if (straddles) {
                val crossingX = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < crossingX) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        /** Freeform/circle selection from a drawn path. */
        fun fromPath(
            shape: SelectionShape,
            path: List<SelectionPoint>,
            packageName: String,
            screenSignature: String,
            capturedAtMs: Long,
            cropPath: String? = null,
        ) = ScreenSelection(
            shape = shape,
            path = path,
            bounds = SelectionBox.around(path),
            packageName = packageName,
            screenSignature = screenSignature,
            capturedAtMs = capturedAtMs,
            cropPath = cropPath,
        )

        /**
         * A tap, expanded into a small box.
         *
         * A zero-area selection intersects nothing, so a bare tap would
         * resolve to no element at all. [radiusPx] should come from
         * `ViewConfiguration.scaledTouchSlop` at the call site rather than
         * being guessed here.
         */
        fun fromTap(
            x: Int,
            y: Int,
            radiusPx: Int,
            packageName: String,
            screenSignature: String,
            capturedAtMs: Long,
            cropPath: String? = null,
        ) = ScreenSelection(
            shape = SelectionShape.POINT,
            path = listOf(SelectionPoint(x, y)),
            bounds = SelectionBox(x - radiusPx, y - radiusPx, x + radiusPx, y + radiusPx),
            packageName = packageName,
            screenSignature = screenSignature,
            capturedAtMs = capturedAtMs,
            cropPath = cropPath,
        )
    }
}
