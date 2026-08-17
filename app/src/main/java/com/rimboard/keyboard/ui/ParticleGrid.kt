package com.rimboard.keyboard.ui

import kotlin.math.sqrt

/**
 * A grid of particles that scatter away from a touch and spring back.
 *
 * The algorithm is Justin Windle's "30,000 Particles" — each particle
 * remembers where it belongs, is pushed by an inverse-square repulsion from
 * the pointer, and is drawn home again by a spring, with drag bleeding off the
 * velocity. Public CodePen pens are MIT licensed; the attribution is in
 * NOTICE, which is the condition of using it.
 *
 * Three things had to change to make it a keyboard background rather than a
 * demo, and each of them is the difference between a page you look at and a
 * surface you type on.
 *
 * The count. The original is thirty thousand particles at 3px spacing, which
 * is a full-screen desktop canvas; a keyboard is a few hundred pixels tall and
 * shares the phone with whatever the user is actually doing. The spacing here
 * is set in dp and the count falls out of the area, which lands in the
 * hundreds rather than the tens of thousands.
 *
 * The allocation. The original builds a fresh `ImageData` every rendered frame
 * and writes pixels into it. The equivalent here — a new bitmap per frame —
 * would be an allocation of that size sixty times a second behind a keyboard
 * whose memory problems this project has spent a week fixing. The positions
 * live in one `FloatArray`, reused, in the layout `Canvas.drawPoints` wants.
 *
 * The pointer. There is no cursor over a keyboard, and the original's
 * fallback traces a Lissajous figure so the demo animates on its own. Neither
 * applies: the push comes from where a key was pressed, and when nothing has
 * been pressed the field is still and costs nothing.
 */
class ParticleGrid(
    private val spacing: Float = 14f,
    private val margin: Float = 6f
) {

    private var w = 0f
    private var h = 0f
    private var count = 0

    /** Live positions, as x, y pairs — the layout `drawPoints` consumes. */
    private var pts = FloatArray(0)

    /** Rest positions, in the same order. */
    private var home = FloatArray(0)

    /** Velocities, in the same order. */
    private var vel = FloatArray(0)

    private var pushX = 0f
    private var pushY = 0f
    private var pushLife = 0f

    fun points(): FloatArray = pts
    fun size(): Int = count

    fun resize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (width == w && height == h && count > 0) return
        w = width
        h = height
        val cols = maxOf(1, ((width - margin * 2) / spacing).toInt() + 1)
        val rows = maxOf(1, ((height - margin * 2) / spacing).toInt() + 1)
        count = cols * rows
        pts = FloatArray(count * 2)
        home = FloatArray(count * 2)
        vel = FloatArray(count * 2)
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = margin + c * spacing
                val y = margin + r * spacing
                pts[i] = x; pts[i + 1] = y
                home[i] = x; home[i + 1] = y
                i += 2
            }
        }
    }

    fun touch(x: Float, y: Float) {
        pushX = x
        pushY = y
        pushLife = 1f
    }

    /**
     * Advances by [dt] seconds.
     *
     * The original steps once per frame with no time term at all, which is
     * fine at a locked 60Hz and wrong on a 120Hz phone, where it would run at
     * double speed. The constants are therefore rates and are scaled to real
     * time, and `dt` is clamped: the frame callback stops while the keyboard is
     * hidden, and an unclamped resume would apply a whole second of spring in
     * one step and fling the grid apart.
     */
    private var accumulator = 0f

    fun step(dt: Float) {
        // Decayed before the early return, or a push registered on a grid with
        // no size would never expire and [settling] would report motion
        // forever — the view would then redraw at full rate with nothing to
        // draw. Found by a test, which is the only place an empty grid ever
        // gets stepped.
        if (pushLife > 0f) pushLife = (pushLife - dt.coerceIn(0f, 0.25f) * 1.2f).coerceAtLeast(0f)
        if (count == 0) return
        // A fixed timestep, rather than scaling the constants by the frame
        // time. Scaling looks equivalent and is not: this is a spring
        // integrated by Euler, and halving the step twice over does not land
        // where one whole step lands — measured at nearly nine pixels of
        // divergence between 60Hz and 120Hz, which is a visibly different
        // animation on a phone with a fast display. Fixed substeps make the
        // result depend on elapsed time and nothing else.
        accumulator += dt.coerceIn(0f, 0.25f)
        var guard = 0
        while (accumulator >= FIXED && guard < MAX_SUBSTEPS) {
            substep()
            accumulator -= FIXED
            guard++
        }
        // Behind by more than the guard allows — the keyboard was hidden, or
        // the device stalled. Dropping the backlog is right: catching up would
        // spend the next frames replaying an animation nobody saw.
        if (guard >= MAX_SUBSTEPS) accumulator = 0f
    }

    private fun substep() {
        val t = FIXED * 60f   // in frames, so the constants read as the original's
        val drag = Math.pow(DRAG.toDouble(), t.toDouble()).toFloat()
        val ease = (EASE * t).coerceAtMost(1f)
        var i = 0
        while (i < count * 2) {
            var vx = vel[i]
            var vy = vel[i + 1]
            if (pushLife > 0f) {
                val dx = pushX - pts[i]
                val dy = pushY - pts[i + 1]
                val d2 = dx * dx + dy * dy
                if (d2 < THICKNESS && d2 > 0.0001f) {
                    // Inverse-square, away from the touch. Normalising by the
                    // distance is the same direction the original gets from
                    // atan2 and a sin/cos pair, without the trigonometry.
                    val d = sqrt(d2)
                    val f = -(THICKNESS / d2) * pushLife
                    vx += f * (dx / d) * t
                    vy += f * (dy / d) * t
                }
            }
            vx *= drag
            vy *= drag
            pts[i] += vx + (home[i] - pts[i]) * ease
            pts[i + 1] += vy + (home[i + 1] - pts[i + 1]) * ease
            vel[i] = vx
            vel[i + 1] = vy
            i += 2
        }
    }

    /**
     * Whether anything is still moving.
     *
     * The view stops asking for frames when this is false, which is what keeps
     * an idle keyboard off the CPU. Checked against the rest position rather
     * than against velocity alone: a particle at the top of its swing has no
     * velocity and is not at rest.
     */
    fun settling(): Boolean {
        if (count == 0) return false
        if (pushLife > 0f) return true
        var i = 0
        while (i < count * 2) {
            if (Math.abs(pts[i] - home[i]) > 0.35f ||
                Math.abs(pts[i + 1] - home[i + 1]) > 0.35f
            ) return true
            i += 2
        }
        return false
    }

    companion object {
        /** Repulsion radius, squared — the original's `Math.pow(80, 2)`, in dp. */
        const val THICKNESS = 80f * 80f
        private const val DRAG = 0.95f
        private const val EASE = 0.25f

        /** Simulation step. Finer than any display refresh, so a 120Hz phone
         *  and a 60Hz one both land on whole numbers of these. */
        private const val FIXED = 1f / 120f

        /** Most substeps one frame may run, so a stall cannot spiral into a
         *  frame that simulates a second and misses the next one too. */
        private const val MAX_SUBSTEPS = 8
    }
}
