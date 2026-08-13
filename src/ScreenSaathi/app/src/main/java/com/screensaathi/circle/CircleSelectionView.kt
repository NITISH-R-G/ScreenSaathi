package com.screensaathi.circle

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/**
 * The draw-around-something surface.
 *
 * Full-screen, touchable, and only present while selecting — it is added to
 * the WindowManager on entry and removed on exit rather than living alongside
 * the pill, because a full-screen touchable window that stayed resident would
 * swallow every tap meant for the app underneath.
 *
 * Rendering is a dim scrim with the drawn region punched out of it, so the
 * user sees the real app clearly inside their selection and dimmed outside.
 * That is what makes the gesture legible as "you are choosing this part of
 * *this* screen" rather than "you are drawing on a grey rectangle".
 *
 * Written natively for ScreenSaathi. The reference implementation studied for
 * this feature (AKS-Labs/CircleToSearch) is GPL-3.0 and MIT-licensed
 * ScreenSaathi cannot take code from it; only the platform-level mechanism
 * (that an AccessibilityService can screenshot without MediaProjection) was
 * reused, which is a fact about Android, not an expression.
 */
class CircleSelectionView(
    context: Context,
    private val onSelectionComplete: (List<SelectionPoint>, SelectionShape) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val points = mutableListOf<SelectionPoint>()
    private val livePath = Path()

    private var drawing = false
    private var committed = false

    /** 0f while drawing, animates to 1f on release. Drives the settle polish. */
    private var settle = 0f
    private var settleAnimator: ValueAnimator? = null

    /** Fades the scrim in so the mode change does not flash. */
    private var scrimAlpha = 0f
    private var entryAnimator: ValueAnimator? = null

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
    }

    /** Punches the selection out of the scrim. */
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF00E5A0")
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5500E5A0")
        strokeWidth = 12f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
    }

    private var hintText: String = ""

    init {
        // Required for PorterDuff.CLEAR to punch through rather than compose
        // against the window's own black.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusableInTouchMode = true
        entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrimAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Localised prompt, supplied by the caller — never hardcoded English. */
    fun setHint(text: String) {
        hintText = text
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (committed) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                livePath.reset()
                drawing = true
                settleAnimator?.cancel()
                settle = 0f
                addPoint(event.x, event.y, force = true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                // Coalesce the batched historical samples too, otherwise a
                // fast stroke is recorded as a few long straight segments and
                // the polygon test resolves against a shape the user did not
                // draw.
                for (i in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                addPoint(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!drawing) return true
                drawing = false
                addPoint(event.x, event.y, force = true)
                complete()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Back cancels selection mode.
     *
     * Needs the host window to be focusable; see the flags used when this view
     * is added in [com.screensaathi.OverlayService].
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onCancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Discard the current stroke and let the user draw again. */
    fun redo() {
        committed = false
        drawing = false
        settleAnimator?.cancel()
        settle = 0f
        reset()
    }

    private fun reset() {
        points.clear()
        livePath.reset()
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, force: Boolean = false) {
        val px = x.toInt()
        val py = y.toInt()
        val last = points.lastOrNull()

        // Thin the stream: raw touch samples are far denser than the polygon
        // test needs, and every retained point costs on every containsPoint().
        if (!force && last != null) {
            if (hypot((px - last.x).toFloat(), (py - last.y).toFloat()) < MIN_POINT_SPACING_DP * density) {
                return
            }
        }

        points.add(SelectionPoint(px, py))
        if (points.size == 1) livePath.moveTo(x, y) else livePath.lineTo(x, y)
    }

    private fun complete() {
        val shape = classify()
        if (shape == null) {
            // Too small to be a deliberate selection — treat as a miss and
            // let the caller decide (usually: cancel), rather than resolving
            // a stray fingerprint into a confident answer.
            onCancel()
            return
        }

        committed = true
        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                settle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        onSelectionComplete(points.toList(), shape)
    }

    /**
     * Decide what the user drew.
     *
     * A tap is a tap. Anything with real extent is treated as a lasso and
     * resolved against its polygon — distinguishing a "circle" from a
     * "freeform" shape would be a cosmetic label with no behavioural
     * difference, since both resolve identically.
     */
    private fun classify(): SelectionShape? {
        if (points.isEmpty()) return null

        val box = SelectionBox.around(points)
        val movedFar = box.width > touchSlop || box.height > touchSlop

        if (!movedFar) {
            return if (points.size <= MAX_TAP_POINTS) SelectionShape.POINT else null
        }

        val minPx = (MIN_SELECTION_DP * density).toInt()
        if (box.width < minPx && box.height < minPx) return null

        return SelectionShape.FREEFORM
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        scrimPaint.alpha = (SCRIM_ALPHA * scrimAlpha).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (points.isEmpty()) {
            drawHint(canvas)
            return
        }

        if (points.size == 1) {
            // A tap: reveal a small disc so the user sees what they picked.
            val p = points.first()
            val r = TAP_REVEAL_DP * density
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, clearPaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, glowPaint)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, strokePaint)
            return
        }

        // Punch the drawn region out of the scrim. The path is closed for the
        // fill so an open hand-drawn loop still reveals its interior — nobody
        // closes a circle exactly.
        val fillPath = Path(livePath).apply { close() }
        canvas.drawPath(fillPath, clearPaint)

        // On settle, tighten to the bounding box edges with a soft rounded
        // frame — reads as "locked in" without hiding what was chosen.
        if (committed && settle > 0f) {
            val box = SelectionBox.around(points)
            val inset = (1f - settle) * 6f * density
            val rect = RectF(
                box.left + inset,
                box.top + inset,
                box.right - inset,
                box.bottom - inset,
            )
            val radius = 14f * density
            glowPaint.alpha = (85 * settle).toInt()
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
        }

        glowPaint.alpha = 85
        canvas.drawPath(livePath, glowPaint)
        canvas.drawPath(livePath, strokePaint)
    }

    private fun drawHint(canvas: Canvas) {
        if (hintText.isEmpty()) return
        hintPaint.alpha = (200 * scrimAlpha).toInt()
        canvas.drawText(
            hintText,
            width / 2f,
            height * 0.42f,
            hintPaint,
        )
    }

    override fun onDetachedFromWindow() {
        entryAnimator?.cancel()
        settleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SCRIM_ALPHA = 179f
        const val MIN_POINT_SPACING_DP = 3f
        const val MIN_SELECTION_DP = 24f
        const val TAP_REVEAL_DP = 34f
        const val MAX_TAP_POINTS = 6
    }
}
