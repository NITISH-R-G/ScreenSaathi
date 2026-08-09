package com.screensaathi.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tappr thought-orb, drawn on a Canvas — the same dotted animations as
 * the in-app/RN brand mark (a math port of `thinking-orbs` by Jakub Antalik
 * & Alex Brinza, MIT). Uses the library's hand-tuned 20px "inline" designs,
 * scaled to whatever box the pill gives us; dot counts stay tiny (~30-200)
 * so a 60fps ValueAnimator tick is effortless.
 *
 * States used by the notch:
 *  - LISTENING  — a waveform rolls through latitude rings
 *  - THINKING   — particles on tilted orbits
 *  - SOLVING    — bands scramble in quarter turns, then click back
 *  - COMPOSING  — an undulating multi-band sash
 */
object OrbSignal {
  enum class State { LISTENING, THINKING, SOLVING, COMPOSING }

  /** Preset speed multiplier: t (seconds) is multiplied by this before drawing. */
  fun speed(state: State): Float =
    when (state) {
      State.LISTENING -> 3.998f
      State.THINKING -> 3.9f
      State.SOLVING -> 1.95f
      State.COMPOSING -> 3.12f
    }

  private class Dot(val x: Float, val y: Float, val z: Float, val r: Float, val white: Float, val a: Float = 1f)

  private const val DESIGN = 20f

  private fun hash(i: Int, seed: Float): Float {
    val s = sin(i * 12.9898 + seed * 78.233) * 43758.5453
    return (s - floor(s)).toFloat()
  }

  private fun fibSphere(i: Int, n: Int): FloatArray {
    val golden = PI * (3 - sqrt(5.0))
    val y = 1 - 2 * (i + 0.5f) / n
    val rad = sqrt(max(0f, 1 - y * y))
    val ang = i * golden
    return floatArrayOf((rad * cos(ang)).toFloat(), y, (rad * sin(ang)).toFloat())
  }

  /** Yaw+tilt rotation followed by an orthographic projection onto (cx, cy). */
  private class Projector(yaw: Float, tilt: Float, val cx: Float, val cy: Float, val scale: Float) {
    val sinT = sin(tilt); val cosT = cos(tilt)
    val sinY = sin(yaw); val cosY = cos(yaw)
    fun project(x: Float, y: Float, z: Float): FloatArray {
      val u = x * cosY + z * sinY
      val h = -x * sinY + z * cosY
      val v = y * cosT - h * sinT
      val depth = y * sinT + h * cosT
      return floatArrayOf(cx + u * scale, cy - v * scale, depth)
    }
  }

  private val rs = (DESIGN / 300f).pow(0.6f)

  // --- listening: wave (rings 5, lon 13, rBase .96, rDepth 2.72) ------------

  private fun wave(t: Float, level: Float, out: MutableList<Dot>) {
    val half = DESIGN / 2f
    val base = half * 0.874f
    val proj = Projector(t * 0.18f, 0.38f, half, half, 1f)
    val rings = 5
    val lon = 13
    for (li in 0..rings) {
      val lat = (-PI / 2 + li.toDouble() / rings * PI).toFloat()
      val cosLat = cos(lat)
      val sinLat = sin(lat)
      // Mic level feeds the roll amplitude so louder speech visibly swells.
      val amp = 1f + 0.6f * level
      val w = (0.62f * sin(t * 2.1f - li * 0.52f) + 0.38f * sin(t * 1.27f + li * 0.83f)) * amp
      val ringR = base * (0.88f + 0.105f * w)
      val count = max(1, (abs(cosLat) * lon).roundToInt())
      for (k in 0 until count) {
        val lonAng = (k.toFloat() / count) * 2f * PI.toFloat()
        val p = proj.project(cosLat * cos(lonAng) * ringR, sinLat * ringR, cosLat * sin(lonAng) * ringR)
        val depth = (p[2] / base + 1f) / 2f
        val lift = max(0f, w)
        out.add(Dot(p[0], p[1], p[2], (0.96f + 2.72f * depth) * (1f + 0.4f * lift) * rs, 0.66f - 0.56f * depth - 0.1f * lift))
      }
    }
  }

  // --- thinking: orbits (orbitN 3, ghostN 10, particles 3) ------------------

  private fun orbits(t: Float, out: MutableList<Dot>) {
    val half = DESIGN / 2f
    val maxR = half * 0.82f
    val proj = Projector(t * 0.12f, 0.3f, half, half, 1f)
    val orbitN = 3
    val ghostN = 10
    val particles = 3
    for (b in 0 until orbitN) {
      val h1 = hash(b, 1.7f)
      val h2 = hash(b, 5.2f)
      val h3 = hash(b, 8.9f)
      val orbitR = maxR * (0.45f + 0.52f * h1)
      val phi = h1 * 2f * PI.toFloat()
      val theta = acos(2f * h2 - 1f)
      val nx = sin(theta) * cos(phi)
      val ny = cos(theta)
      val nz = sin(theta) * sin(phi)
      var ux = -ny
      var uy = nx
      val uz = 0f
      val norm = max(1e-6f, sqrt(ux * ux + uy * uy))
      ux /= norm
      uy /= norm
      val vx = ny * uz - nz * uy
      val vy = nz * ux - nx * uz
      val vz = nx * uy - ny * ux
      val speed = (0.25f + 0.55f * h3) * (if (h3 > 0.5f) 1f else -1f)
      for (g in 0 until ghostN) {
        val ang = (g.toFloat() / ghostN) * 2f * PI.toFloat()
        val p = proj.project(
          (ux * cos(ang) + vx * sin(ang)) * orbitR,
          (uy * cos(ang) + vy * sin(ang)) * orbitR,
          (uz * cos(ang) + vz * sin(ang)) * orbitR,
        )
        val depth = (p[2] / orbitR + 1f) / 2f
        out.add(Dot(p[0], p[1], p[2], 2.16f * rs, 0.72f, 0.5f * (0.4f + 0.6f * depth)))
      }
      for (q in 0 until particles) {
        val ang = t * speed + (q.toFloat() / particles) * 2f * PI.toFloat() + h2 * 6f
        val p = proj.project(
          (ux * cos(ang) + vx * sin(ang)) * orbitR,
          (uy * cos(ang) + vy * sin(ang)) * orbitR,
          (uz * cos(ang) + vz * sin(ang)) * orbitR,
        )
        val depth = (p[2] / orbitR + 1f) / 2f
        out.add(Dot(p[0], p[1], p[2], (2.88f + 3.84f * depth) * rs, 0.3f - 0.22f * depth))
      }
    }
  }

  // --- solving: rubik (latRings 4, lon 12, 14 moves) ------------------------

  private class Move(val axis: Int, val lo: Float, val hi: Float, val ang: Float)

  private val rubikMoves: List<Move> = (0 until 14).map { i ->
    val axis = min(2, (hash(i, 2.3f) * 3).toInt())
    val lo = -1f + 0.5f * min(3, (hash(i, 5.9f) * 4).toInt())
    val dir = if (hash(i, 7.7f) < 0.5f) 1f else -1f
    Move(axis, lo, lo + 0.5f, dir * PI.toFloat() / 2f)
  }

  private fun rubik(t: Float, out: MutableList<Dot>) {
    val half = DESIGN / 2f
    val radius = half * 0.82f
    val proj = Projector(t * 0.55f, 0.35f + 0.1f * sin(t * 0.9f), half, half, radius)
    val n = rubikMoves.size
    val step = 0.42f
    val total = 2f * n * step + 1.2f
    val local = ((t % total) + total) % total
    val amount = FloatArray(n)
    var active = -1
    if (local < 2 * n * step) {
      val i = (local / step).toInt()
      val frac = (local - i * step) / step
      val eased = 1f - (1f - min(1f, frac / 0.7f)).pow(3)
      if (i < n) {
        for (k in 0 until i) amount[k] = 1f
        amount[i] = eased
        active = i
      } else {
        val u = 2 * n - 1 - i
        for (k in 0 until u) amount[k] = 1f
        amount[u] = 1f - eased
        active = u
      }
    }
    val latRings = 4
    val lon = 12
    for (li in 0..latRings) {
      val lat = (-PI / 2 + li.toDouble() / latRings * PI).toFloat()
      val cosLat = cos(lat)
      val sinLat = sin(lat)
      val count = max(1, (abs(cosLat) * lon).roundToInt())
      for (k in 0 until count) {
        val lonAng = (k.toFloat() / count) * 2f * PI.toFloat()
        var x = cosLat * cos(lonAng)
        var y = sinLat
        var z = cosLat * sin(lonAng)
        var isActive = false
        for (m in rubikMoves.indices) {
          if (amount[m] <= 0f) continue
          val mv = rubikMoves[m]
          val coord = if (mv.axis == 0) x else if (mv.axis == 1) y else z
          if (coord < mv.lo || coord >= mv.hi) continue
          if (m == active) isActive = true
          val ang = mv.ang * amount[m]
          val c = cos(ang)
          val s = sin(ang)
          when (mv.axis) {
            0 -> { val ny2 = y * c - z * s; z = y * s + z * c; y = ny2 }
            1 -> { val nx2 = x * c + z * s; z = -x * s + z * c; x = nx2 }
            else -> { val nx2 = x * c - y * s; y = x * s + y * c; x = nx2 }
          }
        }
        val p = proj.project(x, y, z)
        val depth = (p[2] + 1f) / 2f
        out.add(
          Dot(
            p[0], p[1], p[2],
            (1.14f + 3.23f * depth + (if (isActive) 0.57f else 0f)) * rs,
            0.62f - 0.54f * depth - (if (isActive) 0.14f else 0f),
          ),
        )
      }
    }
  }

  // --- composing: ribbon (2 lanes ×4.94 bands, 20 segs, 8 ghosts, spin 0) ---

  private fun ribbon(t: Float, out: MutableList<Dot>) {
    val half = DESIGN / 2f
    val radius = half * 0.78f
    val proj = Projector(0f, 0.3f, half, half, 1f)
    val ghostN = 8
    for (i in 0 until ghostN) {
      val f = fibSphere(i, ghostN)
      val p = proj.project(f[0] * radius, f[1] * radius, f[2] * radius)
      val depth = (p[2] / radius + 1f) / 2f
      out.add(Dot(p[0], p[1], p[2], 0.8f * rs, 0.78f, 0.1f + 0.22f * depth))
    }
    val tiltT = 0.55f
    val ax = 1f; val ay = 0f; val az = 0f
    val bx = -az * sin(tiltT); val by = cos(tiltT); val bz = ax * sin(tiltT)
    val nx = ay * bz - az * by
    val ny = az * bx - ax * bz
    val nz = ax * by - ay * bx
    val segs = 20
    val bands = 10
    for (f in 0 until bands) {
      val offset = (f - (bands - 1) / 2f) * 0.075f
      val edge = abs(f - (bands - 1) / 2f) / max(1f, (bands - 1) / 2f)
      for (s in 0 until segs) {
        val ang = (s.toFloat() / segs) * 2f * PI.toFloat()
        val wobble = 0.16f * sin(ang * 3f - t * 1.7f + f * 0.22f) + 0.07f * sin(ang * 5f + t * 1.1f)
        val lift = offset + wobble
        val px = ax * cos(ang) + bx * sin(ang) + nx * lift
        val py = ay * cos(ang) + by * sin(ang) + ny * lift
        val pz = az * cos(ang) + bz * sin(ang) + nz * lift
        val mag = sqrt(px * px + py * py + pz * pz)
        val p = proj.project(px / mag * radius, py / mag * radius, pz / mag * radius)
        val depth = (p[2] / radius + 1f) / 2f
        out.add(
          Dot(
            p[0], p[1], p[2],
            (1.18f + 1.824f * depth) * (1f - 0.25f * edge) * rs,
            0.52f - 0.44f * depth + 0.18f * edge,
            0.4f + 0.6f * depth,
          ),
        )
      }
    }
  }

  /**
   * Draw one frame into a square box of `size` px centered at (cx, cy).
   * `t` is animation seconds (already speed-multiplied via [speed]).
   * `level` (0..1) is the mic level; only LISTENING uses it. Light ink,
   * for the dark pill.
   */
  fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, t: Float, state: State, paint: Paint, level: Float = 0f) {
    val dots = ArrayList<Dot>(220)
    when (state) {
      State.LISTENING -> wave(t, level, dots)
      State.THINKING -> orbits(t, dots)
      State.SOLVING -> rubik(t, dots)
      State.COMPOSING -> ribbon(t, dots)
    }
    dots.sortBy { it.z }
    val scale = size / DESIGN
    val left = cx - size / 2f
    val top = cy - size / 2f
    for (d in dots) {
      if (d.a < 0.02f) continue
      val ink = min(1f, max(0f, d.white))
      val g = ((1f - ink) * 255).toInt()
      paint.color = Color.argb((d.a * 255).toInt(), g, g, g)
      canvas.drawCircle(left + d.x * scale, top + d.y * scale, max(0.3f, d.r) * scale, paint)
    }
  }

}
