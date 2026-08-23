package com.rimboard.keyboard.model

import kotlin.math.abs

/**
 * What a finger on a key has turned into.
 *
 * One touch can become six different things — a tap, a swipe, a popup pick, a
 * cursor drag, a word delete, or nothing at all — and which one it is was
 * decided by a handful of comparisons and a `when` inside `KeyboardView`, which
 * is a `View` and cannot be run by a test. The decisions are here; the timers,
 * the listener calls and the invalidation stay there, because those are the
 * view's job and are not what goes wrong.
 *
 * What goes wrong is ordering and thresholds, and neither is visible by reading
 * a `when` block: the order of its branches is a priority list that nobody
 * wrote down as one, and the two distance rules are measured from *different
 * origins*, which is the sort of thing that reads as symmetric and is not.
 */
object PointerGesture {

    /**
     * What a release should do, in the order the cases are decided.
     *
     * The order is the content. A pointer can be several of these at once — a
     * glide that was also cancelled, a popup that was also handled on the way
     * down — and the first match wins, so writing them as an ordered list is
     * writing down the rule rather than leaving it implicit in how a `when`
     * happens to be arranged.
     */
    enum class Release {
        /** Slid off a key that was not gliding: the touch is abandoned. */
        NOTHING,

        /** A swipe: the path is decoded into a word. */
        GLIDE,

        /** A long-press popup was open: whatever it was pointing at is taken. */
        POPUP,

        /** The space bar was being used to move the caret. */
        CURSOR,

        /** Already acted on when the finger went down — a repeat, or a hold. */
        ALREADY_DONE,

        /** An ordinary tap, which is the only case that types a letter. */
        TAP
    }

    fun releaseAction(
        cancelled: Boolean,
        glide: Boolean,
        popupOpen: Boolean,
        cursorMode: Boolean,
        handledOnDown: Boolean
    ): Release = when {
        cancelled -> Release.NOTHING
        glide -> Release.GLIDE
        popupOpen -> Release.POPUP
        cursorMode -> Release.CURSOR
        handledOnDown -> Release.ALREADY_DONE
        else -> Release.TAP
    }

    /**
     * Whether a press that has travelled this far should become a swipe.
     *
     * Measured from where the finger went **down**, not from the key. A key is
     * wider than this threshold in both directions, so a thumb that rolls while
     * pressing can arm a glide without ever leaving the key it started on —
     * which is deliberate, and is why `KeyboardView` has a fallback that types
     * the letter when a "glide" turns out to name no word.
     */
    fun armsGlide(x: Float, y: Float, downX: Float, downY: Float, threshold: Float): Boolean =
        abs(x - downX) > threshold || abs(y - downY) > threshold

    /**
     * Whether a press has slid far enough off its key to be abandoned.
     *
     * Measured from the **key's edges**, not from where the finger went down,
     * and with more room vertically than horizontally because the rows are
     * closer together than the columns are wide.
     *
     * This and [armsGlide] look like two halves of one rule and are not: one is
     * travel from a point, the other is distance from a rectangle. A press can
     * satisfy both, neither, or either, and the caller resolves that by asking
     * this only for keys that cannot glide.
     */
    fun slidesOff(
        x: Float, y: Float,
        keyX: Float, keyY: Float, keyW: Float, keyH: Float,
        slopX: Float, slopY: Float
    ): Boolean =
        x < keyX - slopX || x > keyX + keyW + slopX ||
            y < keyY - slopY || y > keyY + keyH + slopY
}
