package com.rimboard.keyboard.ui

/**
 * Every duration and easing curve the keyboard draws with, in one place.
 *
 * The keyboard animates by hand inside `onDraw` rather than with `Animator`
 * objects — it is a single custom-drawn surface, so a frame-clock read is
 * cheaper and cannot fall out of step with the canvas. That is the same
 * approach the major keyboards take. What it costs is discoverability: the
 * timings used to be inline literals spread through a thousand lines, so
 * tuning the feel meant hunting for them. They live here instead.
 *
 * Pure math with no Android dependencies, so the curves are unit tested:
 * see `AnimTest`.
 */
object Anim {

    // Durations in milliseconds.
    /** Key springs back after the finger lifts. */
    const val PRESS_RELEASE_MS = 150f
    /** Radial highlight growing from the touch point. */
    const val RIPPLE_MS = 160f
    /** Key preview bubble scaling up out of the key. */
    const val PREVIEW_IN_MS = 90f
    /** Preview shrinking away once the key is released. */
    const val PREVIEW_OUT_MS = 80f
    /** Long-press alternatives popup. */
    const val POPUP_IN_MS = 140f
    /** Popup shrinking away after a selection or release. */
    const val POPUP_OUT_MS = 100f
    /** Key labels cross-fading when the layout plane changes (ABC ↔ ?123). */
    const val LAYOUT_FADE_MS = 110f

    /** How far the popup overshoots before settling. */
    const val POPUP_TENSION = 1.1f

    /**
     * Multiplier on every duration, mirroring the system animator scale so the
     * accessibility "remove animations" setting is honoured. At 0 every
     * animation reports itself finished on its first frame, which also stops
     * the redraw loops the animations drive.
     */
    @Volatile
    var durationScale: Float = 1f

    /**
     * Elapsed fraction of an animation started at [startedAt], clamped to 0..1.
     * Clamping matters: an unclamped value would drive scale and alpha past
     * their endpoints and leave visible artefacts.
     */
    fun progress(now: Long, startedAt: Long, durationMs: Float): Float {
        val d = durationMs * durationScale
        if (d <= 0f) return 1f
        return ((now - startedAt) / d).coerceIn(0f, 1f)
    }

    /** Decelerating: fast to start, easing into the endpoint. */
    fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    /** Accelerating: gentle to start, quickest at the end. */
    fun easeIn(t: Float): Float = t * t

    /**
     * Overshoots past 1 and settles back, matching Android's
     * OvershootInterpolator so the popup keeps the bounce it always had.
     */
    fun overshoot(t: Float, tension: Float = POPUP_TENSION): Float {
        val o = t - 1f
        return (tension + 1f) * o * o * o + tension * o * o + 1f
    }
}
