package com.screensaathi.circle

import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot

/**
 * Turns a drawn selection into "the user circled *this element*".
 *
 * This is the difference between ScreenSaathi's circle mode and an ordinary
 * circle-to-search: rather than shipping a crop of pixels off to a model and
 * hoping, the selection is first resolved against the live accessibility tree,
 * which already knows the label, the role, and whether the thing is
 * interactive. A circle around a button should produce "the Book now button",
 * not a JPEG.
 *
 * Two failure modes drive most of the scoring here:
 *
 *  - **Containers swallow everything.** The root ViewGroup intersects every
 *    selection perfectly, so raw overlap alone always picks the whole screen.
 *    Elements much larger than what the user actually drew are penalised.
 *  - **Bounding boxes over-select.** A lasso around one row of a list has a
 *    bounding box covering its neighbours, so polygon containment is checked
 *    against the real drawn path, not the box.
 *
 * Like [com.screensaathi.screen.TargetResolver], this reports ambiguity rather
 * than inventing a winner.
 */
object SelectionResolver {

    /** Below this, the element is only clipped by the selection edge. */
    private const val MIN_COVERAGE = 0.30

    /** Below this final score, we claim nothing. */
    private const val MIN_SCORE = 35

    /** Two candidates this close together are a genuine tie. */
    private const val AMBIGUITY_MARGIN = 12

    /**
     * How many times larger than the selection an element may be before it is
     * treated as a container the user did not mean. A little slack, because a
     * hand-drawn circle usually sits just inside the button it surrounds.
     */
    private const val OVERSIZE_TOLERANCE = 2.5

    /** What the user could plausibly do with the thing they selected. */
    enum class Action {
        /** It is clickable — the agent can guide the user to tap it. */
        TAP,

        /** It is a text field — the agent can guide the user to fill it. */
        TYPE,

        /** It carries text — it can be read, explained, or translated. */
        READ,
    }

    data class Candidate(
        val element: ScreenElement,
        val box: SelectionBox,
        val score: Int,
        /** Fraction of the element that fell inside the selection, 0f..1f. */
        val coverage: Float,
    )

    /**
     * The resolved selection.
     *
     * [element] is null when nothing scored well enough — a real outcome, not
     * an error. The crop and the surrounding text are still available in that
     * case, so the caller can fall back to describing the region instead of
     * pretending to have found a control.
     */
    data class SelectedTarget(
        val element: ScreenElement?,
        val confidence: Int,
        val alternatives: List<ScreenElement>,
        /** Visible text of everything the selection covered, reading order. */
        val selectedText: String,
        val possibleActions: Set<Action>,
        /** Nearby text that gives the selection meaning, for model context. */
        val surroundingContext: String,
        val ambiguous: Boolean,
        val reason: String,
    ) {
        val isResolved: Boolean get() = element != null
    }

    /**
     * An element paired with its Android-free bounds.
     *
     * The resolver works on these rather than reading [ScreenElement.bounds]
     * directly, so every geometric decision below can be unit tested — the
     * stubbed `android.graphics.Rect` in the JVM test runtime reports zeros,
     * which would silently make the scoring untestable.
     */
    data class Placed(val element: ScreenElement, val box: SelectionBox)

    fun resolve(selection: ScreenSelection, snapshot: ScreenSnapshot): SelectedTarget =
        resolvePlaced(selection, snapshot.elements.map { Placed(it, it.toSelectionBox()) })

    internal fun resolvePlaced(
        selection: ScreenSelection,
        placed: List<Placed>,
    ): SelectedTarget {
        val isTap = selection.shape == SelectionShape.POINT

        // A tap covers only a few percent of the button it lands on, so the
        // coverage rule that works for a drawn loop would reject every hit.
        // For a tap the question is "which element is under the finger", and
        // the answer is the smallest one that contains it.
        val smallestUnderTap: Long = if (!isTap) 0L else placed
            .filter { !it.box.isEmpty && it.box.contains(selection.bounds.centerX, selection.bounds.centerY) }
            .minOfOrNull { it.box.area } ?: 0L

        val covered = placed.mapNotNull { (element, box) ->
            if (box.isEmpty) return@mapNotNull null

            // Something we can neither name nor act on is not a target, however
            // squarely it sits inside the selection — claiming it would only
            // let the assistant say "I found a View".
            if (element.text.isBlank() && !element.clickable && !element.editable) {
                return@mapNotNull null
            }

            val overlapArea = selection.bounds.intersectionArea(box)
            if (overlapArea <= 0L) return@mapNotNull null

            val coverage = overlapArea.toDouble() / box.area.toDouble()

            if (isTap) {
                if (!box.contains(selection.bounds.centerX, selection.bounds.centerY)) {
                    return@mapNotNull null
                }
                return@mapNotNull Candidate(
                    element,
                    box,
                    tapScore(box, element, smallestUnderTap),
                    coverage.toFloat(),
                )
            }

            if (coverage < MIN_COVERAGE) return@mapNotNull null

            // For a drawn loop the bounding box is only a first pass; the
            // element also has to actually sit inside the path.
            if (!insideDrawnPath(selection, box)) return@mapNotNull null

            Candidate(element, box, score(selection, box, element, coverage), coverage.toFloat())
        }

        val selectedText = covered
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .map { it.element.text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")

        val ranked = covered.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }

        if (ranked.isEmpty()) {
            return SelectedTarget(
                element = null,
                confidence = 0,
                alternatives = emptyList(),
                selectedText = selectedText,
                possibleActions = if (selectedText.isNotEmpty()) setOf(Action.READ) else emptySet(),
                surroundingContext = surroundingContext(selection, placed),
                ambiguous = false,
                reason = if (covered.isEmpty()) {
                    "nothing in the accessibility tree fell inside the selection"
                } else {
                    "only partial overlaps, none specific enough to name"
                },
            )
        }


        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)

        // A tie between an element and its own container is not a real tie —
        // the smaller one is what the user drew around.
        val genuineTie = runnerUp != null &&
            (best.score - runnerUp.score) <= AMBIGUITY_MARGIN &&
            !encloses(best.box, runnerUp.box) &&
            !encloses(runnerUp.box, best.box)

        return SelectedTarget(
            element = best.element,
            confidence = best.score.coerceIn(0, 100),
            alternatives = ranked.drop(1).take(3).map { it.element },
            selectedText = selectedText,
            possibleActions = actionsFor(best.element),
            surroundingContext = surroundingContext(selection, placed),
            ambiguous = genuineTie,
            reason = if (genuineTie) {
                "two elements scored within $AMBIGUITY_MARGIN of each other"
            } else {
                "covered ${(best.coverage * 100).toInt()}% of the element"
            },
        )
    }

    /**
     * Scoring for a tap, where "smallest thing under the finger" is the whole
     * question. [smallestArea] is the area of the tightest element containing
     * the tap; everything larger is scored down in proportion, so the root
     * container never beats the button the user actually touched.
     */
    private fun tapScore(box: SelectionBox, element: ScreenElement, smallestArea: Long): Int {
        var score = 70

        if (smallestArea > 0L) {
            val ratio = box.area.toDouble() / smallestArea.toDouble()
            if (ratio > 1.0) score -= (20 * ratio).toInt().coerceAtMost(90)
        }

        if (element.clickable || element.editable) score += 12
        if (element.text.isNotBlank()) score += 8

        return score
    }

    private fun score(
        selection: ScreenSelection,
        box: SelectionBox,
        element: ScreenElement,
        coverage: Double,
    ): Int {
        var score = (coverage * 70).toInt()

        if (selection.containsPoint(box.centerX, box.centerY)) score += 20

        // The user usually circles something they want to act on.
        if (element.clickable || element.editable) score += 12
        if (element.text.isNotBlank()) score += 8

        // Containers. An element several times the size of the drawn selection
        // is almost never what was meant, however perfectly it overlaps.
        val selectionArea = selection.bounds.area.coerceAtLeast(1L)
        val ratio = box.area.toDouble() / selectionArea.toDouble()
        if (ratio > OVERSIZE_TOLERANCE) {
            val penalty = (25 * (ratio / OVERSIZE_TOLERANCE)).toInt()
            score -= penalty.coerceAtMost(90)
        }

        return score
    }

    /**
     * For a drawn loop, require the element's centre or a decent share of its
     * corners to be inside the path. Rectangles and taps already agree with
     * their bounding box, so they pass trivially.
     */
    private fun insideDrawnPath(selection: ScreenSelection, box: SelectionBox): Boolean {
        if (selection.shape == SelectionShape.RECTANGLE ||
            selection.shape == SelectionShape.POINT
        ) return true

        if (selection.containsPoint(box.centerX, box.centerY)) return true

        val corners = listOf(
            box.left to box.top,
            box.right to box.top,
            box.left to box.bottom,
            box.right to box.bottom,
        )
        return corners.count { (x, y) -> selection.containsPoint(x, y) } >= 2
    }

    /** Does [outer] fully contain [inner]? Used to spot container/child ties. */
    private fun encloses(outer: SelectionBox, inner: SelectionBox): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom &&
            outer.area > inner.area

    private fun actionsFor(element: ScreenElement): Set<Action> = buildSet {
        if (element.clickable) add(Action.TAP)
        if (element.editable) add(Action.TYPE)
        if (element.text.isNotBlank()) add(Action.READ)
    }

    /**
     * Text near the selection, for model context.
     *
     * A circled price means little on its own; the heading above it is what
     * makes it answerable. Kept to elements vertically close to the selection
     * so the whole screen does not get pasted in.
     */
    private fun surroundingContext(
        selection: ScreenSelection,
        placed: List<Placed>,
        maxItems: Int = 8,
    ): String {
        val band = selection.bounds.height.coerceAtLeast(200)
        val top = selection.bounds.top - band
        val bottom = selection.bounds.bottom + band

        return placed.asSequence()
            .filter { it.element.text.isNotBlank() }
            .filter { it.box.top in top..bottom || it.box.bottom in top..bottom }
            .map { it.element.text.trim() }
            .distinct()
            .take(maxItems)
            .joinToString(" · ")
    }
}

/**
 * Android [android.graphics.Rect] to the Android-free box used above.
 *
 * Isolated here so the scoring stays testable on the JVM.
 */
internal fun ScreenElement.toSelectionBox(): SelectionBox =
    SelectionBox(bounds.left, bounds.top, bounds.right, bounds.bottom)
