package com.rimboard.keyboard.model

/**
 * A correctly-spelled word that the sentence around it contradicts.
 *
 * Every other correction in this app starts from a word that is not in the
 * dictionary. This one starts from a word that is — "form" for "from", "than"
 * for "then", "of" for "or" — the class of mistake a spell checker built on
 * a word list cannot see at all, because nothing is misspelled. It is also the
 * class that survives proofreading, since the eye reads the word it expected.
 *
 * **This underlines; it never rewrites.** That distinction is the whole reason
 * the bar here can be lower than autocorrect's, and it is the project's own
 * rule: a squiggle asks the reader to look, a replacement overrules them. So
 * this lives in the spell checker and has no path into
 * [com.rimboard.keyboard.RimBoardService]. `PostCorrection` deliberately cannot
 * reach these words — `correctionCandidates` returns nothing for a word the
 * engine considers real — and that boundary is on purpose: the same evidence
 * that is good enough to draw a line under a word is not good enough to swap it.
 *
 * ## The rule, and why each half is needed
 *
 * Both neighbours have to agree, and the measurement is unambiguous that this
 * is not belt-and-braces. From `ContextErrorAccuracyTest`, over prose fixtures
 * with a real-word error injected one word at a time:
 *
 *     right context only    en 5.06% false positives    caught 29%
 *     both neighbours       en 0.12% false positives    caught 11%
 *
 * The right-hand test alone flags one correctly-typed word in twenty. That is
 * not a spell checker, it is confetti. Adding the requirement that the
 * *preceding* context also predicts the candidate — and does not predict what
 * was actually written — divides the false positives by forty and keeps a third
 * of the catches.
 *
 * ## Why it walks the predictions and not the dictionary
 *
 * The obvious shape is: find the near neighbours of the word, keep the ones the
 * context predicts. That works — 0.09% false positives, 10% caught — and it is
 * unshippable, because finding near neighbours means `correctionsScored`, which
 * walks every dictionary word within the edit budget. The spell checker runs on
 * a binder thread with the framework waiting, and it currently pays that walk
 * only for words that *fail* `acceptedWord`, a small minority. Asking it of
 * every correctly-spelled word instead is the same scan an order of magnitude
 * more often.
 *
 * So the loop is inverted: walk the context's own short list of predictions and
 * keep the ones that are near neighbours. At most [DEPTH] edit distances
 * against short words, no scan at all — and measured *better*, 11% caught
 * against 10%, because the candidate no longer has to survive the channel
 * model's ranking before the context is allowed an opinion.
 *
 * The closeness test is deliberately [withinOneEdit] and not
 * `Dictionary.autoCommitConfident`. The confident bar is tuned for silently
 * replacing a word and is far stricter than "one keystroke away"; using it here
 * measured 1% caught in English against 11%. A tenth of the feature, bought
 * with a bar whose whole purpose is to guard an act this code does not perform.
 */
object ContextError {

    /**
     * How far down the preceding context's predictions to look.
     *
     * Doubling it is a wash — English gains nine catches and loses a false
     * positive, Turkish gains none and gains a false positive — so the smaller
     * call wins on cost. See the sweep in `ContextErrorAccuracyTest`.
     */
    const val DEPTH = 12

    /**
     * The word the context wanted, or null to leave [word] alone.
     *
     * Everything is a value or a predicate, so the rule runs without a
     * dictionary, an engine or a bound text field. [predictions] is called at
     * most once and only after the two cheap refusals above it, because it is
     * the one input here that costs anything to produce.
     *
     * All strings are expected already folded to lower case in the locale being
     * checked; casing the answer back onto what the user typed is the caller's,
     * since only the caller knows the locale's rules.
     *
     * @param word  the word as written, lower case. Known to be a real word.
     * @param next  the word after it. Empty means "unknown", not "none".
     * @param predictions what the preceding context predicts, best first.
     * @param continues   whether the n-grams have seen `a` before `b`. Used to
     *                    promote a candidate, where one sighting is evidence.
     * @param writtenFits whether the pair as *written* is attested strongly
     *                    enough to call this rule off. A separate predicate
     *                    because it holds to a higher bar; see below.
     */
    fun suggest(
        word: String,
        next: String,
        predictions: () -> List<String>,
        continues: (String, String) -> Boolean,
        writtenFits: (String, String) -> Boolean
    ): String? {
        if (word.isEmpty() || next.isEmpty()) return null
        // The sentence supports the word as written. Nothing to say, and this
        // is one map lookup, so it is asked before anything is built.
        //
        // **A different predicate from [continues], and the difference is a
        // bug that shipped.** With the user's own n-grams in play, this asked
        // whether the pair had ever been typed -- and the pair had, because
        // typing it is what put it on file. The keyboard records the mistake as
        // it is made and then reads its own record back as proof the mistake
        // was intended, so the errors a user actually makes are the ones this
        // goes blind to. Verified in `ContextErrorPersonalTest`: flagged on a
        // fresh install, silent ever after one slip.
        //
        // The bar is now a habit rather than a sighting. The signal is right --
        // somebody who really does write "form data" should stop being asked --
        // it was the threshold that could not tell them apart.
        if (writtenFits(word, next)) return null
        val left = predictions()
        if (left.isEmpty()) return null
        // The preceding context predicts it too. Two independent signals both
        // saying the word belongs is the end of the question -- and this is
        // the guard that separates "the context prefers something else" from
        // "the context is happy and something else is merely also plausible".
        if (left.contains(word)) return null
        for (c in left) {
            if (c == word) continue
            if (!withinOneEdit(word, c)) continue
            if (!continues(c, next)) continue
            return c
        }
        return null
    }

    /**
     * Whether [a] and [b] differ by exactly one substitution, insertion,
     * deletion or adjacent transposition.
     *
     * Written out rather than reusing `Dictionary.spatialCost` because the
     * question is different: that one asks *how far* two words are on the key
     * grid and is used to rank, this asks the yes/no a real-word error implies
     * — one keystroke. It answers in a single pass with no allocation, which is
     * what lets it be asked [DEPTH] times per word on a binder thread.
     *
     * Identical strings are **false**: a word is not a correction of itself,
     * and returning true for that would let the loop above offer the word it
     * was asked about.
     */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return false
        if (la - lb > 1 || lb - la > 1) return false
        var i = 0
        var j = 0
        var diff = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            diff++
            if (diff > 1) return false
            when {
                la == lb -> {
                    // A substitution, unless the next pair is the same two
                    // letters the other way round, which is a transposition
                    // and one edit rather than two.
                    if (i + 1 < la && a[i] == b[j + 1] && a[i + 1] == b[j]) {
                        i += 2; j += 2
                    } else {
                        i++; j++
                    }
                }
                la > lb -> i++
                else -> j++
            }
        }
        // Whatever is left over on either side is the insertion or deletion.
        if (i < la || j < lb) diff++
        return diff <= 1
    }
}
