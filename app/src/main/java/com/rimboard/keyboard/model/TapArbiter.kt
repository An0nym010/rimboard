package com.rimboard.keyboard.model

/**
 * Which key an ambiguous tap fired.
 *
 * A thumb lands somewhere near a boundary and two keys have a claim on it. The
 * geometry says how near each one it was; the language says which letter would
 * make a word. Weighing those two is adaptive touch targeting, and it runs
 * before anything else in this engine — before the corrector, before the strip
 * — because it decides what was typed at all. Everything downstream is repairing
 * whatever this hands it.
 *
 * It was split across two places that neither of them could be run from: the
 * candidate geometry sat in `KeyboardView`, which is a `View`, and the weighing
 * sat in `RimBoardService`, which is an `InputMethodService` and needs a bound
 * text field to exist. Four constants decided every keystroke and none of them
 * had ever been executed by a test.
 *
 * The two halves stay where they were called from — the view knows where the
 * keys are and the service knows what the language thinks — but the rules are
 * here, together, where they can be checked and where the constants can be read
 * next to each other.
 */
object TapArbiter {

    /**
     * How far past its own edge a key still competes for a touch, as a fraction
     * of the key.
     *
     * Wider sideways than vertically because keys are wider than they are tall
     * *relative to how a thumb misses*: a slip along the row is the common one,
     * and a slip into the row above lands on a key the finger was nowhere near.
     * Nothing outside this ever contends, so it is also the hard bound on how
     * far arbitration can move a tap — the language prior can only choose among
     * keys the finger was already touching the edge of.
     */
    const val EXPAND_X = 0.18f
    const val EXPAND_Y = 0.15f

    /**
     * Width of the Gaussian around the touch point, in fractions of a key.
     *
     * At 0.40 a tap one key width away scores about three log units worse than
     * one dead centre, which is what makes [LANGUAGE_WEIGHT] a tie-breaker
     * rather than a vote.
     */
    const val SIGMA = 0.40f

    /**
     * How loudly the language may argue with the finger.
     *
     * The character model returns log probabilities floored at
     * [Dictionary.LN_UNSEEN] (-6), so the widest gap the language can open
     * between two candidates is six log units, and this scales it to about
     * three. Read that against [SIGMA]: three log units is roughly one key
     * width of spatial evidence, so a tap dead in the middle of a key cannot be
     * moved, and a tap on the boundary can. That is the whole design, and
     * `TapArbiterTest` measures the displacement rather than asserting it.
     */
    const val LANGUAGE_WEIGHT = 0.55

    /**
     * Whether a key whose rect is [kx, ky, kw, kh] competes for a touch at
     * [x], [y].
     */
    fun contends(
        x: Float, y: Float, kx: Float, ky: Float, kw: Float, kh: Float
    ): Boolean {
        val ex = kw * EXPAND_X
        val ey = kh * EXPAND_Y
        return x >= kx - ex && x < kx + kw + ex && y >= ky - ey && y < ky + kh + ey
    }

    /**
     * How well a touch at [x], [y] is explained by the key at that rect, as a
     * log probability with its maximum at the key's centre.
     *
     * Unnormalised: only differences between candidates are ever read, and the
     * constant that would make this a density is the same for all of them.
     */
    fun spatialLogP(
        x: Float, y: Float, kx: Float, ky: Float, kw: Float, kh: Float
    ): Double {
        val dx = (x - (kx + kw / 2f)) / (kw * SIGMA)
        val dy = (y - (ky + kh / 2f)) / (kh * SIGMA)
        return -0.5 * (dx * dx + dy * dy).toDouble()
    }

    /**
     * The candidate best explained by the finger and the language together, or
     * -1 when there is nothing to choose between.
     *
     * [language] is the log probability of each candidate letter following what
     * has been typed so far. The caller supplies it because only the caller
     * knows the language, the dictionary and whether the field is one where a
     * language prior is welcome at all — a password is not.
     */
    fun pick(
        spatial: DoubleArray,
        language: DoubleArray,
        weight: Double = LANGUAGE_WEIGHT
    ): Int {
        if (spatial.isEmpty() || spatial.size != language.size) return -1
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in spatial.indices) {
            val s = spatial[i] + weight * language[i]
            if (s > bestScore) {
                bestScore = s
                best = i
            }
        }
        return best
    }
}
