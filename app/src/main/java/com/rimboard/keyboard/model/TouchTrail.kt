package com.rimboard.keyboard.model

/**
 * Where the finger actually landed for each letter of the word being typed.
 *
 * The keyboard already knows this and already uses it: [KeyProximity] places
 * every key on a grid, and the view combines a Gaussian around the touch point
 * with a character bigram to decide *which key* an ambiguous tap fired. That
 * decision is then final, and the word-level corrector sees only the winning
 * letter — so a tap that sat a hair over the k/l boundary and a tap dead in the
 * middle of k arrive at [Dictionary.correctionsScored] as the same keystroke.
 *
 * This keeps the margin. Each entry is how far the tap sat from the centre of
 * the key it fired, in key widths and rows, which is the same unit the
 * proximity grid uses — so the correction cost for reading that letter as a
 * neighbour becomes the real measured distance instead of the generic
 * key-to-key one. A tap on the very edge of `k` is nearly free to re-read as
 * `l`; a tap in the middle of `k` is not.
 *
 * **The one invariant that matters is length.** A trail that has drifted out of
 * step with the composing buffer would attach one letter's measurement to
 * another letter's position, and wrong touch data is worse than none —
 * it would confidently argue for the wrong word. [offsetsFor] therefore
 * refuses to answer unless the trail is exactly as long as the word it is being
 * asked about, which turns every possible desync into a silent fall back to the
 * behaviour this had before touch data existed. Callers get null and rank on
 * key identity alone, which is what the spell checker does permanently: it is
 * handed finished text by other apps and has no touch to report.
 */
class TouchTrail(private val cap: Int = 48) {

    private val dx = ArrayList<Float>(cap)
    private val dy = ArrayList<Float>(cap)

    val size: Int get() = dx.size

    /**
     * Records a tap [ddx] key widths and [ddy] rows from its key's centre.
     *
     * NaN is the honest entry for a character that arrived without a tap — a
     * popup pick, a glide, an accent chosen from a long press. It keeps the
     * trail in step with the buffer, which is what [offsetsFor] checks, while
     * telling the cost model there is nothing measured at that position.
     */
    fun add(ddx: Float, ddy: Float) {
        // Bounded for the same reason everything else on this path is: a word
        // longer than any real word is a runaway, and the trail must not grow
        // with it. Past the cap the measurements stop rather than the trail
        // going out of step, so the length check still holds.
        if (dx.size >= cap) {
            dx.add(Float.NaN)
            dy.add(Float.NaN)
            return
        }
        dx.add(ddx)
        dy.add(ddy)
    }

    fun removeLast() {
        if (dx.isNotEmpty()) {
            dx.removeAt(dx.size - 1)
            dy.removeAt(dy.size - 1)
        }
    }

    fun clear() {
        dx.clear()
        dy.clear()
    }

    /**
     * The trail as a flat (dx, dy) array for a word of [length] characters, or
     * null when it cannot be trusted for that word.
     *
     * Allocates one small array per call. That is deliberate: the alternative
     * is handing out a reusable internal buffer, and the caller here walks tens
     * of thousands of dictionary entries against it — a buffer that could be
     * mutated underneath that scan is a far worse trade than a few hundred
     * bytes once per strip update.
     */
    fun offsetsFor(length: Int): FloatArray? {
        if (length <= 0 || dx.size != length) return null
        val out = FloatArray(length * 2)
        for (i in 0 until length) {
            out[i * 2] = dx[i]
            out[i * 2 + 1] = dy[i]
        }
        return out
    }
}
