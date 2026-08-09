package com.screensaathi.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * The pill's left-side state mark — a direct port of Tappr's StateSignalView,
 * drawing [OrbSignal]'s dotted thought-orb instead of a flat colored circle.
 *
 * Ported because it is the single biggest legible difference between "an
 * Android card with a status dot" and "a system surface that visibly thinks":
 * the dot only tells you a mode string changed, the orb's motion tells you
 * the assistant is doing something right now, at a glance, before you read
 * any text.
 *
 * Needs the (Context, AttributeSet) constructor, not just (Context) — XML
 * inflation calls it via LayoutInflater, which requires the two-arg form.
 * Missing this crashed OverlayService.onCreate() outright: an accessibility
 * service marked "crashed" and an overlay stuck at 0 windows, no matter how
 * many times the app was relaunched.
 */
class StateOrbView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { HIDDEN, IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = Mode.HIDDEN
    private var targetLevel = 0f
    private var shownLevel = 0f
    private var startMs = SystemClock.uptimeMillis()

    fun setMode(value: Mode) {
        if (mode != value) startMs = SystemClock.uptimeMillis()
        mode = value
        invalidate()
    }

    /** 0..1 amplitude. There is no real mic-level tap into WavRecorder yet, so
     * the caller feeds a smoothly oscillating synthetic value during
     * LISTENING — the orb's own roll animation is real Tappr math either way;
     * only the amplitude driving it is currently synthetic rather than
     * measured from the live audio buffer. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    private val ticker = object : Runnable {
        override fun run() {
            // No live tap into WavRecorder's buffer yet, so LISTENING drives
            // its own gentle breathing curve rather than sitting flat at
            // whatever setLevel() last received — the roll animation itself
            // is OrbSignal's real math either way, only this amplitude input
            // is synthetic.
            if (mode == Mode.LISTENING) {
                val breathe = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() % 1400L / 1400f * Math.PI * 2).toFloat()
                targetLevel = 0.25f + 0.35f * breathe
            }
            val smoothing = if (targetLevel > shownLevel) 0.52f else 0.18f
            shownLevel += (targetLevel - shownLevel) * smoothing
            if (mode != Mode.HIDDEN && mode != Mode.IDLE) invalidate()
            postDelayed(this, 16L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.HIDDEN) return
        val cx = width / 2f
        val cy = height / 2f
        val size = minOf(width, height).toFloat()

        if (mode == Mode.IDLE) {
            // Resting brand mark: same frame OrbSignal's THINKING settles on
            // at t=0 — a full, evenly dotted sphere, not moving.
            OrbSignal.draw(canvas, cx, cy, size, 0f, OrbSignal.State.THINKING, paint)
            return
        }
        if (mode == Mode.ERROR) {
            val glow = 0.55f + 0.45f * sin(SystemClock.uptimeMillis() % 1000L / 1000f * Math.PI * 2).toFloat()
            paint.color = Color.argb((150 + 90 * glow).toInt(), 255, 90, 90)
            canvas.drawCircle(cx, cy, size * 0.18f * (0.8f + glow * 0.2f), paint)
            return
        }

        val state = when (mode) {
            Mode.LISTENING -> OrbSignal.State.LISTENING
            Mode.THINKING -> OrbSignal.State.THINKING
            Mode.SPEAKING -> OrbSignal.State.COMPOSING
            else -> return
        }
        val t = (SystemClock.uptimeMillis() - startMs) / 1000f * OrbSignal.speed(state)
        val level = when (mode) {
            Mode.SPEAKING -> maxOf(shownLevel, 0.24f)
            Mode.LISTENING -> maxOf(shownLevel, 0.16f)
            else -> 0f
        }
        OrbSignal.draw(canvas, cx, cy, size, t, state, paint, level)
    }
}
