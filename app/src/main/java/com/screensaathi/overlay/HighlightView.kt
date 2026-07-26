package com.screensaathi.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Full-screen transparent layer that draws two things:
 *
 *  1. a **cursor** that physically flies to whatever the user should touch next,
 *     leaving a short motion trail behind it, and
 *  2. the **ring** that blooms around that element once the cursor arrives.
 *
 * The ring alone was static: it teleported between fields, which read as a
 * highlight appearing rather than as something guiding you. Travel is what
 * makes it feel like a hand pointing — the eye follows the moving thing and
 * arrives at the target already looking at it.
 *
 * Everything is drawn; nothing here touches WindowManager, and the window is
 * touch-through, so motion can never intercept a tap meant for the app below.
 */
class HighlightView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF00E5A0")
        strokeWidth = dp(3.5f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5500E5A0")
        strokeWidth = dp(12f)
    }
    private val cursorCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFFFF")
    }
    private val cursorHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC00E5A0")
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8800E5A0")
    }
    private val tether = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3300E5A0")
        strokeWidth = dp(1.5f)
    }

    private var shape = "rect"
    private val current = RectF()
    private val from = RectF()
    private val target = RectF()
    private var hasTarget = false

    /** 0 while the cursor is still travelling, 1 once the ring is fully out. */
    private var ringReveal = 0f

    private var pulse = 0f
    private var pulseEnabled = true

    /** Where the cursor is right now, and the arc it is flying along. */
    private val cursor = PointF()
    private val flightStart = PointF()
    private val flightEnd = PointF()
    private val flightControl = PointF()
    private var cursorVisible = false

    /** Recent cursor positions, newest last — drawn as a fading comet tail. */
    private val trail = ArrayDeque<PointF>()

    /** The pill's own position; the cursor launches from and returns to it. */
    private val home = PointF()
    private var hasHome = false

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    private var flightAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    private val originOnScreen = IntArray(2)

    /**
     * Tell the layer where the pill sits, in screen pixels, so the cursor can
     * launch from it rather than materialising out of nowhere.
     */
    fun setHome(screenX: Float, screenY: Float) {
        getLocationOnScreen(originOnScreen)
        home.set(screenX - originOnScreen[0], screenY - originOnScreen[1])
        if (!hasHome) {
            cursor.set(home)
            hasHome = true
        }
    }

    fun show(l: Int, t: Int, r: Int, b: Int, shape: String, pulse: Boolean) {
        this.shape = shape
        this.pulseEnabled = pulse
        // Accessibility reports absolute screen pixels, but this view's canvas
        // origin may sit below the status bar. Convert screen -> view-local so
        // the ring lands on the real element instead of ~1 status bar too low.
        getLocationOnScreen(originOnScreen)
        val dx = originOnScreen[0].toFloat()
        val dy = originOnScreen[1].toFloat()
        target.set(l - dx, t - dy, r - dx, b - dy)

        // Park the cursor just off the element's leading edge: close enough to
        // read as pointing at it, outside it so it never hides the field.
        // Clamped inside the view, because full-width fields start close enough
        // to the screen edge that the halo would otherwise be sliced in half.
        val inset = dp(18f)
        val edgeGuard = dp(16f) + CURSOR_HALO_DP * resources.displayMetrics.density
        flightEnd.set(
            (target.left - inset).coerceAtLeast(edgeGuard),
            target.centerY().coerceIn(edgeGuard, (height - edgeGuard).coerceAtLeast(edgeGuard)),
        )
        flightStart.set(if (cursorVisible) cursor else if (hasHome) home else flightEnd)
        // Bow the path away from the straight line so it arcs instead of
        // sliding. A straight slide reads mechanical; an arc reads intentional.
        flightControl.set(
            (flightStart.x + flightEnd.x) / 2f,
            (flightStart.y + flightEnd.y) / 2f - dp(64f),
        )

        if (!hasTarget) {
            current.set(target)
            hasTarget = true
        } else {
            from.set(current)
        }
        cursorVisible = true
        trail.clear()

        flightAnimator?.cancel()
        ringReveal = 0f
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLIGHT_MS
            // Quick departure, long settle — the shape of a deliberate gesture.
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                quadTo(flightStart, flightControl, flightEnd, f, cursor)
                pushTrail(cursor)
                // The ring only starts blooming once the cursor is most of the
                // way there, so the two read as cause and effect.
                ringReveal = ((f - 0.55f) / 0.45f).coerceIn(0f, 1f)
                val ease = DECELERATE.getInterpolation(f)
                current.set(
                    lerp(from.left, target.left, ease),
                    lerp(from.top, target.top, ease),
                    lerp(from.right, target.right, ease),
                    lerp(from.bottom, target.bottom, ease),
                )
                invalidate()
            }
            start()
        }

        if (pulseEnabled && !pulseAnimator.isStarted) pulseAnimator.start()
        if (!pulseEnabled) pulseAnimator.cancel()
        visibility = VISIBLE
        invalidate()
    }

    /** Withdraw cleanly: the cursor flies home, then everything disappears. */
    fun clear() {
        flightAnimator?.cancel()
        pulseAnimator.cancel()
        if (!cursorVisible || !hasHome) {
            finishClear()
            return
        }
        flightStart.set(cursor)
        flightEnd.set(home)
        flightControl.set(
            (flightStart.x + flightEnd.x) / 2f,
            (flightStart.y + flightEnd.y) / 2f - dp(48f),
        )
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RETURN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                quadTo(flightStart, flightControl, flightEnd, f, cursor)
                pushTrail(cursor)
                ringReveal = 1f - f
                invalidate()
            }
            addListener(onEnd = { finishClear() })
            start()
        }
    }

    private fun finishClear() {
        hasTarget = false
        cursorVisible = false
        ringReveal = 0f
        trail.clear()
        flightAnimator = null
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasTarget && !cursorVisible) return

        if (hasTarget && ringReveal > 0f) {
            val pad = dp(6f) + (if (pulseEnabled) pulse * dp(6f) else 0f)
            val rect = RectF(
                current.left - pad,
                current.top - pad,
                current.right + pad,
                current.bottom + pad,
            )
            val revealAlpha = ringReveal
            glow.alpha = ((60 + (if (pulseEnabled) pulse * 120 else 120f)) * revealAlpha)
                .toInt().coerceIn(0, 255)
            stroke.alpha = (255 * revealAlpha).toInt().coerceIn(0, 255)
            val radius =
                if (shape == "circle") maxOf(rect.width(), rect.height()) / 2f else dp(14f)
            if (shape == "circle") {
                canvas.drawCircle(rect.centerX(), rect.centerY(), radius, glow)
                canvas.drawCircle(rect.centerX(), rect.centerY(), radius, stroke)
            } else {
                canvas.drawRoundRect(rect, radius, radius, glow)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
        }

        if (!cursorVisible) return

        // Tether back to the pill: a faint thread so the cursor always reads as
        // an extension of the assistant rather than a loose dot.
        if (hasHome) {
            tetherPath.reset()
            tetherPath.moveTo(home.x, home.y)
            tetherPath.quadTo(
                (home.x + cursor.x) / 2f,
                (home.y + cursor.y) / 2f + dp(24f),
                cursor.x, cursor.y,
            )
            canvas.drawPath(tetherPath, tether)
        }

        // Comet tail: oldest smallest and faintest.
        trail.forEachIndexed { i, p ->
            val f = (i + 1f) / (trail.size + 1f)
            trailPaint.alpha = (110 * f).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, dp(3f) + dp(3f) * f, trailPaint)
        }

        val breathe = if (pulseEnabled) pulse else 0.5f
        canvas.drawCircle(cursor.x, cursor.y, dp(CURSOR_HALO_DP) + dp(3f) * breathe, cursorHalo)
        canvas.drawCircle(cursor.x, cursor.y, dp(4.5f), cursorCore)
    }

    private val tetherPath = Path()

    private fun pushTrail(p: PointF) {
        trail.addLast(PointF(p.x, p.y))
        while (trail.size > TRAIL_LENGTH) trail.removeFirst()
    }

    /** Quadratic bezier, written into [out] to avoid allocating per frame. */
    private fun quadTo(a: PointF, c: PointF, b: PointF, t: Float, out: PointF) {
        val inv = 1f - t
        out.x = inv * inv * a.x + 2f * inv * t * c.x + t * t * b.x
        out.y = inv * inv * a.y + 2f * inv * t * c.y + t * t * b.y
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val FLIGHT_MS = 620L
        const val RETURN_MS = 380L
        const val TRAIL_LENGTH = 12
        const val CURSOR_HALO_DP = 11f
        val DECELERATE = DecelerateInterpolator(1.6f)
    }
}

/** Tiny helper so the animator listener stays readable. */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: android.animation.Animator) {
            cancelled = true
        }
        override fun onAnimationEnd(animation: android.animation.Animator) {
            if (!cancelled) onEnd()
        }
    })
}
