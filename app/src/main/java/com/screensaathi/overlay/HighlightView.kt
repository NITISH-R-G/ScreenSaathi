package com.screensaathi.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-screen transparent view that draws one pulsing highlight over the target
 * element's screen bounds. The pulse runs on its own ValueAnimator and the
 * bounds move on a separate one — keeping them independent is what makes the
 * pointer read as smooth rather than snapping (the trick from the reference app).
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

    private var shape = "rect"
    // Current drawn rect (animated toward target).
    private val current = RectF()
    private val from = RectF()
    private val target = RectF()
    private var hasTarget = false

    private var pulse = 0f
    private var pulseEnabled = true

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

    private var moveAnimator: ValueAnimator? = null

    init {
        // Transparent overlay; never intercepts touches (handled by WindowManager flags).
        setWillNotDraw(false)
    }

    private val originOnScreen = IntArray(2)

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

        if (!hasTarget) {
            current.set(target)
            hasTarget = true
        } else {
            from.set(current)
            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    current.set(
                        lerp(from.left, target.left, f),
                        lerp(from.top, target.top, f),
                        lerp(from.right, target.right, f),
                        lerp(from.bottom, target.bottom, f),
                    )
                    invalidate()
                }
                start()
            }
        }
        if (pulseEnabled && !pulseAnimator.isStarted) pulseAnimator.start()
        if (!pulseEnabled) pulseAnimator.cancel()
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        hasTarget = false
        pulseAnimator.cancel()
        moveAnimator?.cancel()
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasTarget) return
        val pad = dp(6f) + (if (pulseEnabled) pulse * dp(6f) else 0f)
        val rect = RectF(
            current.left - pad,
            current.top - pad,
            current.right + pad,
            current.bottom + pad,
        )
        glow.alpha = (60 + (if (pulseEnabled) pulse * 120 else 120f)).toInt().coerceIn(0, 255)
        val radius = if (shape == "circle") maxOf(rect.width(), rect.height()) / 2f else dp(14f)
        if (shape == "circle") {
            val cx = rect.centerX()
            val cy = rect.centerY()
            canvas.drawCircle(cx, cy, radius, glow)
            canvas.drawCircle(cx, cy, radius, stroke)
        } else {
            canvas.drawRoundRect(rect, radius, radius, glow)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
