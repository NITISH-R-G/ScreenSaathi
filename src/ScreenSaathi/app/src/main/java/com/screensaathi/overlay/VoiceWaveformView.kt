package com.screensaathi.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The single voice surface: a horizontal bar waveform in the register modern
 * system assistants use.
 *
 * One view covers listening, thinking and speaking so the assistant reads as
 * one object changing state rather than a row of competing indicators — the
 * mic glyph, a status dot and an orb all meaning "audio" at once was the
 * clutter this replaces.
 *
 * Rendering only. It is handed a 0..1 level and never touches AudioRecord, so
 * the capture layer stays the sole owner of the microphone.
 */
class VoiceWaveformView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var mode = Mode.IDLE
    private var targetLevel = 0f
    private var shownLevel = 0f

    /** Per-bar heights, so the wave travels instead of pulsing as one block. */
    private val bars = FloatArray(BAR_COUNT)
    private var startMs = SystemClock.uptimeMillis()

    private val barRect = RectF()
    private var gradient: LinearGradient? = null

    fun setMode(value: Mode) {
        if (mode == value) return
        mode = value
        startMs = SystemClock.uptimeMillis()
        if (value == Mode.IDLE) {
            targetLevel = 0f
            shownLevel = 0f
            java.util.Arrays.fill(bars, 0f)
        }
        invalidate()
    }

    /** Latest normalised loudness. Smoothing happens on the render tick. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (mode != Mode.IDLE) {
                // Fast attack, slow release: the wave must jump the instant
                // someone speaks, then fall away smoothly rather than
                // snapping back to flat between syllables, which reads as
                // flicker.
                val smoothing = if (targetLevel > shownLevel) ATTACK else RELEASE
                shownLevel += (targetLevel - shownLevel) * smoothing
                advanceBars()
                invalidate()
            }
            postDelayed(this, FRAME_MS)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(ACCENT_EDGE, ACCENT_CORE, ACCENT_CORE, ACCENT_EDGE),
            floatArrayOf(0f, 0.28f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun advanceBars() {
        val t = (SystemClock.uptimeMillis() - startMs) / 1000f
        for (i in bars.indices) {
            // Centre-weighted: the middle of the bar carries the most travel,
            // so the shape reads as a voice rather than a level meter.
            val fromCentre = abs(i - (BAR_COUNT - 1) / 2f) / ((BAR_COUNT - 1) / 2f)
            val envelope = 1f - 0.72f * fromCentre * fromCentre
            bars[i] = when (mode) {
                Mode.LISTENING -> {
                    val wave = 0.62f * sin(t * 7.5f - i * 0.55f) + 0.38f * sin(t * 4.1f + i * 0.31f)
                    val amp = 0.10f + 0.90f * shownLevel
                    (MIN_BAR + (0.5f + 0.5f * wave) * amp * envelope).coerceIn(MIN_BAR, 1f)
                }
                Mode.SPEAKING -> {
                    // Rounder and more regular than listening — the same
                    // family, visibly not the same state.
                    val wave = sin(t * 5.2f - i * 0.42f)
                    val amp = 0.28f + 0.55f * shownLevel
                    (MIN_BAR + (0.5f + 0.5f * wave) * amp * envelope).coerceIn(MIN_BAR, 1f)
                }
                Mode.THINKING -> {
                    // A low travelling ripple: clearly alive, clearly not
                    // listening to anything.
                    val pulse = sin(t * 3.4f - i * 0.85f)
                    (MIN_BAR + 0.16f * (0.5f + 0.5f * pulse) * envelope).coerceIn(MIN_BAR, 1f)
                }
                Mode.IDLE -> 0f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.IDLE) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = gradient
        val slot = w / BAR_COUNT
        val barW = slot * 0.42f
        val radius = barW / 2f
        val cy = h / 2f
        val maxHalf = h / 2f - dp(2f)

        for (i in bars.indices) {
            val half = max(radius, bars[i] * maxHalf)
            val cx = slot * (i + 0.5f)
            barRect.set(cx - barW / 2f, cy - half, cx + barW / 2f, cy + half)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }
        paint.shader = null
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val BAR_COUNT = 27
        const val FRAME_MS = 16L
        const val ATTACK = 0.55f
        const val RELEASE = 0.12f
        /** Never fully flat while active — a dead bar row looks like a bug. */
        const val MIN_BAR = 0.06f
        val ACCENT_CORE = Color.parseColor("#FF8AB4F8")
        val ACCENT_EDGE = Color.parseColor("#668AB4F8")
    }
}
