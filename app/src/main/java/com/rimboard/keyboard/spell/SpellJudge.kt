package com.rimboard.keyboard.spell

import android.view.textservice.SuggestionsInfo
import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.model.SpellCandidacy
import java.util.Locale

/** How many expensive answers are left in the call being served. */
internal class Budget(private var left: Int) {
    fun take(): Boolean {
        if (left <= 0) return false
        left--
        return true
    }
}

/**
 * A verdict as data rather than as a [SuggestionsInfo].
 *
 * The framework writes a cookie into whatever a session returns, so handing out
 * the same instance twice is a bug this service has already had once. Carrying
 * the ingredients and building a fresh answer from them keeps the saving
 * without keeping the hazard.
 */
internal data class Verdict(
    val attrs: Int,
    val words: List<String>,
    /**
     * False when the correction search was skipped for budget rather than
     * finished. The word is still reported as a typo — that much was known
     * cheaply — but the empty suggestion list is a deferral and not an answer,
     * so it must not be cached as one.
     */
    val complete: Boolean = true
)

/**
 * What the spell checker thinks of one word.
 *
 * Lifted out of the session so it can be run without one. A session needs a
 * bound text field to exist, which meant every ranking decision made this week
 * — sentence context, the word after the typo, sentence openers, proper nouns,
 * the correction budget — was verifiable only by reading it. The engine has had
 * a test seam backing it with in-memory assets all along, so the only thing
 * between those decisions and a test was that they lived in a private method of
 * a private inner class.
 *
 * Holds no state. The session owns the cache and the budget, which are facts
 * about a call and a session rather than about a word.
 */
internal class SpellJudge(
    private val engine: SuggestionEngine,
    private val lang: String,
    private val loc: Locale,
    private val altLang: String? = null,
    private val altLoc: Locale? = null,
    /**
     * False in incognito, exactly as it is for the keyboard's own four
     * callers into the engine.
     *
     * The switch says "Never learn or suggest from history", and this service
     * was the half of the app it did not reach: the keyboard withheld the
     * user's learned vocabulary while the spell checker went on offering it as
     * the fix for a typo, and ranking its answers by what that user's own
     * n-grams predicted. Both were history, surfaced under a promise not to.
     *
     * It does not stop the spell checker *knowing* those words. `acceptedWord`
     * and the `isKnown` short-circuit inside `correctionCandidates` are
     * deliberately outside this flag, so a word the user has taught the
     * keyboard is still never underlined. Incognito withholds what would be
     * suggested, not what is already on the screen.
     */
    private val personalized: Boolean = true
) {

    /**
     * [sentenceInitial] is null when the caller cannot know where the word
     * sits, which the word-at-a-time API never can. The two questions that
     * depend on position want opposite answers then: the capital takes the
     * lenient reading so capitalised words are still judged, and the ranking
     * gets no sentence-opener context, because "might be the first word" is not
     * evidence that it is.
     */
    fun verdictFor(
        word: String,
        prev2: String,
        prev: String,
        next: String,
        suggestionsLimit: Int,
        sentenceInitial: Boolean?,
        budget: Budget
    ): Verdict {
        if (!SpellCandidacy.worthChecking(word, sentenceInitial ?: true, lang, loc)) {
            return Verdict(0, emptyList())
        }

        if (engine.acceptedWord(word, lang, loc, altLang, altLoc)) {
            return Verdict(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyList())
        }

        // Everything above this line is a lookup. Everything below it walks
        // every dictionary word within the edit budget, and this is where a
        // sentence can turn expensive: paste a paragraph in a language that is
        // not enabled and every word of it is unknown, so every word of it pays
        // for a full scan, on a binder thread, in one call. The cache absorbs
        // repeats and does nothing for a wall of words each wrong once.
        //
        // Past the budget the word is still underlined — being unknown was
        // established cheaply and saying so costs nothing — but no corrections
        // are worked out for it. An underline with no suggestions is a shape
        // the API has and this code already returns when it finds nothing.
        if (!budget.take()) {
            return Verdict(
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, emptyList(), complete = false
            )
        }

        // Contractions first: "dont" is a missing apostrophe rather than a
        // mistyped word, and edit distance does not know that.
        val contraction = engine.contractionFor(word, lang, loc)?.first

        // What the context predicts, as a rank map, exactly as the keyboard
        // builds it for the word being typed. It re-ranks the dictionary's own
        // candidates and never invents one, and the bonus is bounded below the
        // spatial term, so context breaks a near-tie without pulling a distant
        // word past an obvious adjacent-key fix.
        //
        // An empty preceding word is two situations and only one of them is
        // "nothing to go on". A word that opens a sentence has a context of its
        // own, keyed under UserData.START, and five of the shipped languages
        // carry twenty curated openers for it. Where the position is unknown
        // there is genuinely nothing, and this asks for nothing.
        //
        // mayLoad = false because this runs on a binder thread and predictions
        // would otherwise parse the model here. It still consults the learned
        // bigrams, which a readiness check would have discarded along with it.
        val contextRank =
            if (prev.isEmpty() && sentenceInitial != true) emptyMap()
            else engine.predictions(
                prev2, prev, lang, loc, CONTEXT_DEPTH,
                personalized = personalized, mayLoad = false
            )
                .withIndex().associate { (i, w) -> w.lowercase(loc) to i }

        val cap = suggestionsLimit.coerceIn(1, MAX_SUGGESTIONS)
        // A few more than will be shown, so the word after the typo has
        // something to promote from: re-ranking a list already cut cannot
        // recover what the cut removed. They cost nothing, since the scan
        // behind them runs over every candidate regardless and the count only
        // decides where to stop.
        val pool = engine.correctionCandidates(
            word, lang, loc, altLang, altLoc,
            limit = cap + RIGHT_CONTEXT_POOL,
            contextRank = contextRank,
            personalized = personalized
        )

        // Nothing in the language of the field, and the user has another one
        // enabled. Ask again with the two swapped.
        //
        // The alternate language is already half in scope: a word that is
        // correct in it is not underlined, which is what stops a Turkish
        // message flagging every English word in it. Only half, though. A
        // *mis*typed English word was underlined and then offered nothing,
        // because candidates only ever came from the field's own dictionary
        // {EM} correctionCandidates takes the alternate and uses it solely to
        // decide not to correct. So the bilingual writer this exists for got
        // the underline and no way to act on it.
        //
        // Only when the first pass found nothing, which is what keeps this
        // free in the ordinary case and stops it ever displacing a fix in the
        // language actually being written. The budget was taken once for this
        // word and now covers two scans of it; that is a doubling of a bounded
        // number, not an unbounded one.
        val candidates =
            if (pool.isNotEmpty() || altLang == null || altLoc == null) pool
            else engine.correctionCandidates(
                word, altLang, altLoc, lang, loc,
                limit = cap + RIGHT_CONTEXT_POOL,
                contextRank = contextRank,
                personalized = personalized
            )

        // The word after the typo, which the n-grams can only be asked about
        // one way round: not "what precedes this" but "does this candidate
        // usually come before it". A stable sort on a yes/no, so a candidate
        // that fits the follower rises above one that does not while the
        // engine's own ordering survives inside each group — the same restraint
        // the left-hand context is held to, which is that evidence breaks ties
        // rather than overruling the channel model.
        val corrections =
            if (next.isEmpty()) candidates
            else candidates.sortedByDescending {
                engine.continues(it, next, lang, loc, personalized)
            }

        // A run-together pair. Last, because it is the largest change: the
        // others fix a word, this one adds a boundary between two.
        val split = engine.splitFor(word, lang, loc)

        val out = (listOfNotNull(contraction) + corrections + listOfNotNull(split))
            .distinct()
            // The same bound as the correction query above. Without it
            // MAX_SUGGESTIONS held only the corrections, and a framework limit
            // above five let the contraction and the split past it — seven
            // entries in a popup documented to hold five.
            .take(cap)

        // "Recommended" is a claim about the first suggestion, not a report
        // that one was produced — and this used to be the latter, raising the
        // flag whenever the list came back non-empty. The distinction is not
        // decorative: the platform documents this as the text service saying
        // these are *the* suggestions, and an editor may act on it without
        // asking, so a distant guess carried the same authority as an
        // adjacent-key fix. AOSP's own spell checker gates it on a normalized
        // score clearing `config_spellchecker_recommended_threshold`; this is
        // the same gate the keyboard commits on, which keeps one answer to one
        // question rather than two that can drift apart.
        //
        // Everything found is still in the popup either way. The gate decides
        // what is claimed about the first entry, never what is offered — a
        // suggestion the user chooses is their decision and needs no bar.
        val top = out.firstOrNull()
        val recommended = top != null &&
            // A contraction is a missing apostrophe rather than a guess at a
            // different word, and it is the one repair here that a spatial
            // cost reads as expensive while being nearly certain.
            (top == contraction || engine.autoCommitConfident(word, top, lang, loc))
        var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
        if (recommended) {
            attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
        }
        return Verdict(attrs, out)
    }

    companion object {

        /** More than this and the popup is a menu rather than a fix. */
        const val MAX_SUGGESTIONS = 5

        /**
         * How far down the prediction list a word still counts as "expected
         * here". Deep enough to catch an ordinary continuation, shallow enough
         * that the tail of the list — barely ranked at all — does not start
         * nudging corrections about.
         */
        const val CONTEXT_DEPTH = 12

        /**
         * Extra candidates fetched so the following word has something to
         * promote from. Four is enough for the right answer to be sitting just
         * below the cut, which is the case this exists for, without turning the
         * shortlist into a long tail of near-misses.
         */
        const val RIGHT_CONTEXT_POOL = 4

        /**
         * How many words in one sentence may have corrections worked out.
         *
         * Far above any real sentence — twenty-four misspellings in one is not
         * writing, it is a paste — so this never fires on the case it is not
         * for. What it bounds is the case where every word is unknown, which is
         * a paragraph in a language the user has not enabled, and which without
         * a bound is hundreds of full-dictionary scans in a single call the
         * framework is waiting on.
         */
        const val CORRECTION_BUDGET = 24
    }
}
