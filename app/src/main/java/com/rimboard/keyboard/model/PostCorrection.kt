package com.rimboard.keyboard.model

/**
 * Whether the word *before* the one just committed should be repaired now.
 *
 * Autocorrect decides with the left half of the sentence, because at the
 * moment the space bar is pressed the right half does not exist yet. That is
 * not a limitation of the ranking, it is a limitation of *when* the ranking is
 * asked: one word later the follower is known, and the follower is exactly the
 * evidence [com.rimboard.keyboard.engine.SuggestionEngine.continues] was
 * written to read. The spell checker has had it since right context landed;
 * the keyboard threw it away because by then the word was already committed.
 *
 * So this is not a second opinion about a word the keyboard already ruled on.
 * It is the *same* ruling, asked again at the first moment there is anything
 * new to say — and it fires only where the first asking said "there is a fix
 * here and I am not confident enough to apply it."
 *
 * ## What it can reach, and why that is the whole safety story
 *
 * The candidates come from `correctionCandidates`, which returns an empty list
 * for any word the engine considers real: in the dictionary, in the user's own
 * list, a well-formed compound or agglutinated form, or valid in the other
 * enabled language. So a correctly-typed word cannot be reached by this code at
 * all, whatever the n-grams say. That matters more here than anywhere else in
 * the keyboard, because this rewrites text the user has already *seen and moved
 * past* — the one edit that cannot be caught by watching the composing region.
 *
 * It also means the famous case this feature looks like it should fix —
 * "form"/"from", a real word that is the wrong real word — is out of reach by
 * construction. Fixing that needs a model that can say a word is wrong *while
 * being a word*, which is [com.rimboard.keyboard.spell.SpellJudge]'s question
 * and not this one. Do not widen the candidate source to get at it: the same
 * change would put every rare correctly-typed word back in range of a single
 * bigram hit, which is the failure the `rare words destroyed` column exists to
 * count.
 *
 * ## The rules, and what each one is protecting
 *
 * Every one of these is a refusal, and the order does not matter; they are
 * written in rough order of how cheap they are to ask.
 *
 *  - **The word must have been committed exactly as typed.** Where autocorrect
 *    already acted, it acted with evidence and armed a revert chip the user can
 *    still see. Correcting a correction is churn: the text moves twice for one
 *    word, the chip promises to undo something that is no longer what happened,
 *    and the user watches the keyboard argue with itself.
 *  - **The separator between the two words must be a single space.** A comma,
 *    a full stop or a newline puts the follower in a different clause, and the
 *    n-grams are counted over adjacent words in running text. Reading across a
 *    boundary is asking a question the model was not built to answer.
 *  - **The word just committed must not itself have been corrected.** One
 *    silent change per commit. Two would leave the revert chip able to undo
 *    only one of them, and a chip that undoes half of what just happened is
 *    worse than no chip.
 *  - **The typed word must not already fit the follower.** If the pair is
 *    attested then there is no new evidence and this has nothing to add. The
 *    user's own typing counts here, but only as a **habit** and not as a
 *    sighting: the keyboard files the pair as it is typed, so a bar of one
 *    would mean making a mistake is what proves the mistake was meant. Two,
 *    the same bar `UserData.isKnown` holds a learned word to. See
 *    [ContextError], where the same asymmetry cost a shipped feature its
 *    whole purpose.
 *  - **Some candidate must fit the follower.** The whole trigger. A boolean, as
 *    it is everywhere else this signal is read: evidence breaks ties, it does
 *    not overrule the channel model.
 *  - **That candidate must still clear the distance bar**, at [SLACK] times its
 *    usual width — and [SLACK] is 1.0, which is the finding below.
 *
 * ## What this actually contributes, which is not what it was built to
 *
 * The design premise was that right context is new evidence and ought to buy a
 * *wider* distance bar: `autoCommitConfident` refused this repair a word ago,
 * the follower now agrees with it, so let it through at some multiple of the
 * usual width. That premise is wrong, and the sweep in
 * `PostCorrectionAccuracyTest` says so in both columns at once — widening the
 * bar rescues **fewer** typos and mis-repairs three to nine times as many:
 *
 *     en  slack 1.0   rescued 345/428 (81%)   mis-repaired  17 (4%)
 *         slack 1.3   rescued 347/428 (81%)   mis-repaired  75 (18%)
 *         slack 1.6   rescued 315/428 (74%)   mis-repaired 107 (25%)
 *         slack 2.0   rescued 270/428 (63%)   mis-repaired 152 (36%)
 *     tr  slack 1.0   rescued 276/346 (80%)   mis-repaired   6 (2%)
 *         slack 1.3   rescued 300/346 (87%)   mis-repaired  40 (12%)
 *         slack 2.0   rescued 268/346 (77%)   mis-repaired  73 (21%)
 *
 * Rescues falling as the bar loosens looks impossible until you read the rule
 * above that picks the winner: *first* candidate that fits, in the engine's
 * order. The bar is not only a gate on the answer, it is a **filter on the
 * race** — a near-but-wrong candidate sitting above the right one is refused at
 * 1.0 and admitted at 1.6, and once admitted it wins, because it comes first.
 * Loosening the bar does not add the right answer, it adds competitors to it.
 *
 * So what this feature contributes is **not a correction the keyboard was not
 * confident enough to make. It is a different choice among corrections it was
 * already confident about.** `correctionFor` weighs the channel model and the
 * left context, takes the single best candidate, and applies the bar to that
 * one alone; if it fails, the word stands as typed and the other candidates —
 * every one of them within the same distance — are never asked about. One word
 * later the follower can say which of them the sentence wanted.
 *
 * That is a much narrower claim than the one this was built on, and it is the
 * reason the safety story holds: nothing is applied here that the space bar's
 * own bar would have refused. The only thing that changes is which of the words
 * it would have accepted gets picked.
 */
object PostCorrection {

    /**
     * How much wider the auto-commit distance bar is allowed to be once the
     * following word agrees with the candidate.
     *
     * **1.0 — no wider at all**, which is a measured result and not a decision
     * to be conservative. See the sweep table in the class doc: every value
     * above this one costs repairs *and* multiplies mis-repairs, because the
     * bar filters the race as well as gating the answer.
     *
     * It stays a parameter rather than being deleted for two reasons. It keeps
     * `PostCorrectionAccuracyTest`'s sweep runnable, so the conclusion can be
     * re-derived rather than believed; and the shoulder is a property of *this*
     * dictionary and *these* n-grams, so regenerating either is a reason to
     * sweep again. Raise it only against that test, and only if both columns
     * move the right way.
     */
    const val SLACK = 1.0

    /**
     * The replacement for the previous word, or null to leave it alone.
     *
     * Pure, and deliberately so: every input is a value or a predicate, so the
     * whole decision is exercisable without a dictionary, an
     * `InputConnection`, or a thumb. The engine work — building candidates,
     * asking the n-grams, measuring the distance — is behind the three lambdas
     * and is the caller's to supply, which is also what keeps the expensive
     * parts from running when a cheap rule has already refused.
     *
     * @param typed      what the user actually typed for the earlier word.
     * @param committed  what went into the field for it. Must equal [typed].
     * @param separator  what was typed between the two words.
     * @param follower   the word just committed, as it went into the field.
     * @param followerCorrected whether that word was itself changed on commit.
     * @param candidates the earlier word's corrections, best first, from
     *                   `correctionCandidates`. Empty for any word the engine
     *                   considers real, which is the guarantee above.
     * @param continues  whether the n-grams have this word before [follower].
     *                   Used to promote a candidate, where one sighting is
     *                   evidence worth acting on.
     * @param writtenFits whether the word *as typed* is attested strongly
     *                   enough to call this off. Held to a higher bar than
     *                   [continues] for the reason [ContextError] documents:
     *                   the pair on file is very often the mistake itself.
     * @param confident  whether the candidate is close enough to [typed] to be
     *                   applied — the auto-commit distance bar, at [SLACK].
     */
    fun replacementFor(
        typed: String,
        committed: String,
        separator: String,
        follower: String,
        followerCorrected: Boolean,
        candidates: List<String>,
        continues: (String) -> Boolean,
        writtenFits: (String) -> Boolean,
        confident: (String) -> Boolean
    ): String? {
        if (typed.isEmpty() || follower.isEmpty()) return null
        if (committed != typed) return null
        if (separator != " ") return null
        if (followerCorrected) return null
        if (candidates.isEmpty()) return null
        // Asked before the candidates are walked, not after: this is one map
        // lookup and it refuses the whole question, where the loop below costs
        // a lookup and a distance measurement per candidate.
        if (writtenFits(typed)) return null
        // First fit in the engine's own order, rather than the best fit. The
        // ordering is the channel model's, and taking the fittest candidate
        // instead would let the follower promote a distant word over an
        // adjacent-key one -- which is exactly the line the right-context work
        // drew when it made this signal a stable sort on a boolean.
        return candidates.firstOrNull { it != typed && continues(it) && confident(it) }
    }
}
