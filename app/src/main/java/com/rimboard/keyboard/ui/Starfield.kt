package com.rimboard.keyboard.ui

import kotlin.math.hypot
import kotlin.random.Random

/**
 * A drifting starfield that leans away from wherever the last key was pressed.
 *
 * Pure arithmetic, with no Android type anywhere in it, for two reasons. It
 * runs on every animation frame behind a keyboard, so the cost of being wrong
 * is paid sixty times a second while someone is typing — and a simulation is
 * exactly the kind of code that looks right and drifts, accumulates, or
 * quietly stops. Neither of those can be checked from inside a View.
 *
 * Written from scratch rather than adapted from any of the particle demos this
 * resembles: the technique is common property, but somebody's specific code is
 * theirs, and this project ships MIT.
 */
class Starfield(
    private val count: Int = 90,
    seed: Int = 1
) {

    /** Parallax layers. Distant stars are dimmer, smaller and slower, which is
     *  what reads as depth rather than as confetti. */
    class Star(
        var x: Float,
        var y: Float,
        val depth: Float,      // 0 far .. 1 near
        val radius: Float,
        val baseAlpha: Float,
        var driftX: Float,
        var driftY: Float
    )

    private val rng = Random(seed)
    private val stars = ArrayList<Star>(count)
    private var w = 0f
    private var h = 0f

    /** Where the finger last was, and how much of the push is left. */
    private var pushX = 0f
    private var pushY = 0f
    private var pushLife = 0f

    fun stars(): List<Star> = stars

    /**
     * Called on size change. Rebuilds only when the size really changed, so a
     * layout pass that reports the same bounds does not restart the sky.
     */
    fun resize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (width == w && height == h && stars.isNotEmpty()) return
        w = width
        h = height
        stars.clear()
        repeat(count) {
            val depth = rng.nextFloat()
            stars.add(
                Star(
                    x = rng.nextFloat() * w,
                    y = rng.nextFloat() * h,
                    depth = depth,
                    radius = 0.4f + depth * 1.6f,
                    baseAlpha = 0.25f + depth * 0.6f,
                    // Slow, and mostly sideways: a vertical drift behind a
                    // keyboard reads as the whole panel sliding.
                    driftX = (rng.nextFloat() - 0.5f) * 6f * (0.3f + depth),
                    driftY = (rng.nextFloat() - 0.5f) * 2f * (0.3f + depth)
                )
            )
        }
    }

    /** A key was pressed at [x], [y]: the stars nearby lean away from it. */
    fun touch(x: Float, y: Float) {
        pushX = x
        pushY = y
        pushLife = 1f
    }

    /**
     * Advances the field by [dt] seconds.
     *
     * [dt] is clamped rather than trusted. A frame callback that has been
     * paused — the keyboard hidden, the device asleep — resumes with an enormous
     * delta, and an unclamped step would teleport every star across the screen
     * in the first frame after it comes back.
     */
    fun step(dt: Float) {
        if (stars.isEmpty()) return
        val t = dt.coerceIn(0f, 0.05f)
        if (pushLife > 0f) pushLife = (pushLife - t * 1.6f).coerceAtLeast(0f)
        for (s in stars) {
            s.x += s.driftX * t
            s.y += s.driftY * t
            if (pushLife > 0f) {
                val dx = s.x - pushX
                val dy = s.y - pushY
                val d = hypot(dx, dy)
                // A fixed radius rather than one scaled to the view: the push
                // should feel the same size on a tablet as on a phone.
                if (d in 0.001f..PUSH_RADIUS) {
                    val falloff = (1f - d / PUSH_RADIUS) * pushLife
                    val force = falloff * PUSH_STRENGTH * (0.4f + s.depth) * t
                    s.x += dx / d * force
                    s.y += dy / d * force
                }
            }
            // Wrapped rather than respawned: a star leaving one edge is the
            // same star arriving at the other, so the count never changes and
            // no allocation happens on the animation path.
            if (s.x < -MARGIN) s.x = w + MARGIN
            if (s.x > w + MARGIN) s.x = -MARGIN
            if (s.y < -MARGIN) s.y = h + MARGIN
            if (s.y > h + MARGIN) s.y = -MARGIN
        }
    }

    /** How lit a star is right now, 0..1, including its share of the push. */
    fun brightness(s: Star): Float {
        if (pushLife <= 0f) return s.baseAlpha
        val d = hypot(s.x - pushX, s.y - pushY)
        if (d > PUSH_RADIUS) return s.baseAlpha
        val lift = (1f - d / PUSH_RADIUS) * pushLife * 0.5f
        return (s.baseAlpha + lift).coerceAtMost(1f)
    }

    /** True while the push is still moving things, so the view can stop
     *  redrawing once the field is back to its steady drift. */
    fun settling(): Boolean = pushLife > 0f

    companion object {
        const val PUSH_RADIUS = 220f
        const val PUSH_STRENGTH = 90f
        private const val MARGIN = 4f
    }
}
