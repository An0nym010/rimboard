package com.rimboard.keyboard.model

/**
 * Which of a bilingual user's two languages they are writing in *now*.
 *
 * The engine ranks one language above the other — the second is discounted and
 * put on the first's frequency scale — so which one holds the primary slot is
 * worth something on every keystroke. The layout cannot answer it: the whole
 * point of a second language is that you type it without switching. So the
 * words themselves have to.
 *
 * Each committed word is evidence, and only unambiguous evidence counts:
 *
 *  - known in the second language and *not* the first — that is a word the user
 *    could only have meant in the other language, and [ALT_RUN] of them in a row
 *    swap the two;
 *  - known in the first — evidence to stay, or to swap back, after [PRIM_RUN];
 *  - known in neither — a name, a typo, a word neither list has. It says nothing
 *    about which language is being written and is deliberately not counted
 *    either way, so a passage full of proper nouns does not drift.
 *
 * A word in *both* lists counts for the primary, which matters more than it
 * sounds: these dictionaries are built from subtitle corpora and overlap
 * heavily, so "in both" is a common case rather than a curiosity.
 *
 * ## Why the two runs differ, and why they used to differ the other way
 *
 * They are not symmetric, and the asymmetry follows from how good each kind of
 * evidence is rather than from which language the layout is drawn in.
 *
 * "Known only to the second language" is *unambiguous*: there is no reading of
 * that word in the first language, so one of them is worth acting on. "Known to
 * the first language" is *weak*, because these dictionaries are built from
 * subtitle corpora and overlap enormously — **60% of the words in a mixed
 * English/Turkish passage are in both lists** — so most of what counts as
 * evidence to stay is really evidence of nothing.
 *
 * The runs were 3 and 2, which is that backwards: three repetitions demanded of
 * the strong signal, two of the weak one. And because any shared word resets the
 * alt run, three *consecutive* unambiguous words is a demanding thing to ask.
 * Measured against labelled prose, en primary and tr second:
 *
 *                              3/2 (was)      1/3 (now)
 *     slot matches the                72%            88%
 *       language being written
 *     pure Turkish: wrong             37%             4%
 *     pure Turkish: flips/100w       13.2            4.8
 *     pure English: wrong              0%             0%
 *     engages within a sentence   81/120        120/120
 *     words before it engages        4.6            1.8
 *
 * The median sentence in the corpus is six words, so the old setting arrived
 * after most of the sentence was typed and never arrived at all in a third of
 * them. It also *flapped more*, which is the thing a high threshold is normally
 * for: it would engage, take two shared words for the primary, and drop out
 * again. The eager setting is both steadier and more often right, and neither
 * setting ever fires on monolingual English — the case that must not regress.
 *
 * ## What it is worth, which is not much
 *
 * Being in the wrong slot cannot destroy a word: a word known to *either*
 * dictionary is never autocorrected, so destruction measured 0% in every
 * setting including "always boosted" and "never boost". What is left is
 * ranking, and ranking is worth little here — over a long mixed passage,
 * perfect knowledge of the language being written saves 32.90% of keystrokes
 * against 32.54% for never boosting at all. The whole mechanism has a ceiling
 * of about a third of a point, and glide top-1 moves half a point.
 *
 * That is worth writing down so nobody spends real effort on this again. It is
 * fixed rather than deleted because the fix is a constant, and because "wrong
 * about which language you are writing" is a bad thing for a keyboard to be
 * even when the cost is small.
 */
class LanguageBoost {

    /** Whether the second language currently holds the primary slot. */
    var boosted = false
        private set

    private var altRun = 0
    private var primRun = 0

    /** Back to the layout's own language, for a new field or a new layout. */
    fun reset() {
        boosted = false
        altRun = 0
        primRun = 0
    }

    /**
     * Record one committed word, given whether each dictionary knows it.
     *
     * Returns whether [boosted] changed, so a caller can avoid recomputing
     * anything when nothing moved.
     */
    fun note(inPrimary: Boolean, inAlt: Boolean): Boolean {
        val was = boosted
        when {
            inAlt && !inPrimary -> {
                altRun++
                primRun = 0
                if (altRun >= ALT_RUN) boosted = true
            }
            inPrimary -> {
                primRun++
                altRun = 0
                if (primRun >= PRIM_RUN) boosted = false
            }
        }
        return boosted != was
    }

    companion object {
        /**
         * Words known only to the second language before it takes the slot.
         *
         * One, because one is already unambiguous — see the note above. Three
         * consecutive such words is a demanding thing to ask of a corpus where
         * 60% of words are in both lists, and asking for it made the mechanism
         * inert for most of a sentence.
         */
        const val ALT_RUN = 1

        /**
         * Words known to the first language before it takes the slot back.
         *
         * Three, because this is the weak signal: most words that satisfy it
         * are in both dictionaries and mean nothing either way.
         */
        const val PRIM_RUN = 3
    }
}
