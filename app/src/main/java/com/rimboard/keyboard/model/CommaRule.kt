package com.rimboard.keyboard.model

/**
 * Whether a comma belongs before the word just committed.
 *
 * Yandex ships a punctuation half to its Neurocorrector and this is RimBoard's
 * answer to it. The note that stood against it for a week said missing commas
 * need clause structure no shipped asset carries -- true of the assets, and not
 * of the corpus they are built from, which has its punctuation intact and was
 * simply never counted for this.
 *
 * What is counted is not "where do commas go", which needs a parse. It is the
 * one question a keyboard can act on: **for each word, how often is the
 * character before it a comma?** In the languages whose comma placement is
 * rule-shaped the answer is nearly always the same word list -- the
 * subordinating conjunctions and relative pronouns -- and it is nearly
 * deterministic: cs `že` 99%, de `dass` 98%, pl `że` 97%, ru `чтобы` 95%,
 * hu `hogy`, uk `що`, sk `aby`. `tools/build_commas.py` counts them and writes
 * `assets/commas/<lang>.txt`, ten to eighteen words a language, 733 bytes for
 * all seven that ship.
 *
 * **Seven, and the other fifteen are a refusal rather than an omission.** A
 * language ships a list only if a model built from nine tenths of its corpus
 * gets 95% of its commas right on the tenth it has never seen, and only if it
 * fires often enough to be worth having:
 *
 *     ships   sk 100.0%  cs 96.9%  de 96.5%  ru 96.3%  uk 95.5%  hu 95.4%  pl 95.4%
 *     no      fi 91.5%   pt 91.1%  da 90.9%  ro 84.4%
 *     no      it 97.4% but fires once in 547 sentences
 *     no      en, tr, and eight more: nothing reaches the share bar at all
 *
 * English has four candidate words and fires once in 2,749 sentences; Turkish
 * has none. This is a feature for the languages that have it.
 *
 * ## Every rule here is a refusal
 *
 *  - **The share must clear [MIN_SHARE].** Swept on held-out text: 0.80 leaves
 *    Russian at 85.7% and German at 91.9%, and 0.95 costs five to ten points of
 *    recall everywhere for one or two of precision. 0.90 is the lowest value at
 *    which every shipping language is at or above 95%.
 *  - **Not at the start of a sentence.** A sentence cannot open with a comma
 *    before its first word, and the shares were counted over mid-sentence
 *    occurrences only -- so firing there would be acting on a number measured
 *    over a population that excludes it, which is this project's most
 *    frequently repeated mistake.
 *  - **There must be a previous word.** A comma goes *after* something.
 *  - **The word must be a word.** Digits and punctuation are not comma sites,
 *    and the lists cannot contain them, but the caller's word is whatever was
 *    typed.
 *
 * What this object does not decide, because it cannot see it, is whether the
 * text in the field still reads the way the commit left it. That check belongs
 * to the caller and is the same one post-correction makes: read back
 * `" " + word + separator` and refuse unless it matches exactly. It is what
 * stops a comma landing after a full stop, after an existing comma, across a
 * newline, or anywhere the cursor has since moved.
 */
object CommaRule {

    /**
     * How often a word must follow a comma before one is inserted for it, in
     * parts per thousand -- the same scale the asset's second column is
     * written in.
     *
     * 900. The sweep is in `tools/build_commas.py`, and the shape of it is
     * worth knowing: Russian gains ten points of precision and loses
     * twenty-six of recall in the single step from 0.80 to 0.85, German does
     * the same between 0.85 and 0.90, and Czech and Polish are flat and high
     * throughout. One high-volume word in each of the first two carries most
     * of the recall and nearly all of the errors. A per-language threshold was
     * therefore considered and refused: it would be four constants to move one
     * language from good to slightly better.
     */
    const val MIN_SHARE = 900

    /**
     * Whether to put a comma in front of [word].
     *
     * @param share how often a comma precedes this word, per thousand, or null
     *        when the word is not in the language's list -- which is the
     *        answer for almost every word, in almost every language.
     * @param sentenceInitial whether this word opened a sentence.
     * @param previousWord the word before it, empty when there is none.
     */
    fun applies(
        word: String,
        share: Int?,
        sentenceInitial: Boolean,
        previousWord: String
    ): Boolean {
        if (share == null || share < MIN_SHARE) return false
        if (sentenceInitial) return false
        if (previousWord.isEmpty()) return false
        if (word.isEmpty()) return false
        return word.all { it.isLetter() || it == '\'' }
    }
}
