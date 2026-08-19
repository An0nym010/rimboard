package com.rimboard.keyboard.spell

import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.rimboard.keyboard.engine.SuggestionEngine
import com.rimboard.keyboard.engine.UserData
import com.rimboard.keyboard.model.Languages
import com.rimboard.keyboard.model.SpellCache
import com.rimboard.keyboard.model.SpellCandidacy
import com.rimboard.keyboard.model.SpellTokens
import com.rimboard.keyboard.settings.Prefs
import java.util.Locale

/**
 * The red underlines, in other apps.
 *
 * A keyboard only ever sees what is typed *into* it. The squiggles under a
 * misspelling in Gmail or Chrome come from whichever spell checker the system
 * has bound, which until now was never this one — so RimBoard's dictionaries,
 * its Turkish suffix handling and the words it has learned from you all stopped
 * at the edge of its own suggestion strip.
 *
 * This is the same APK and the same engine, exposed through the second
 * component Android offers for it. Nothing is installed separately and no new
 * permission is requested; the user picks RimBoard under Settings → Languages
 * and input → Spell checker, and that binding is the whole of the consent.
 *
 * **Two rules this service holds itself to, both stricter than it has to be:**
 *
 * 1. **It never writes.** The keyboard learns as you type; this does not. A
 *    spell checker is handed text from every app on the phone — including text
 *    that was pasted, autofilled, or already sitting in the field before you
 *    arrived — and quietly folding all of that into your personal dictionary
 *    would turn "RimBoard learns as you type" into something much broader than
 *    anyone agreed to. It reads your learned words so they stop being
 *    underlined; it adds nothing to them.
 *
 * 2. **It never reaches the network.** Not a policy so much as a fact: the
 *    engine is entirely local and this class calls nothing else. On the
 *    `offline` build there is no permission to reach it with anyway.
 */
class RimSpellService : SpellCheckerService() {

    // One engine for the process, shared across sessions. Dictionaries are
    // several hundred KB each and the system creates a session per text field.
    private val userDataLazy = lazy {
        // loadAsync is not optional. UserData reads nothing at construction, so
        // without this the learned words, the blocked words and the n-grams are
        // all empty — which would have meant the spell checker underlining
        // every word the keyboard had ever learned from this person, and
        // offering corrections they had explicitly blocked. Exactly the
        // opposite of what this service is documented to do.
        UserData(this).also { it.loadAsync() }
    }

    private val userData: UserData by userDataLazy

    private val engineLazy = lazy { SuggestionEngine(this, userData) }

    private val engine: SuggestionEngine by engineLazy

    /**
     * Starts loading the dictionary before anything asks for a word.
     *
     * A cold load is ~350ms of parsing on a desktop JVM and several times that
     * on a phone, and without this it happens inside the first
     * [Session.onGetSuggestions] — on a binder thread, with the framework
     * waiting. That is the difference between underlines appearing as you type
     * and the first paragraph you write going unchecked.
     *
     * The keyboard warms on the same principle when it opens; this is the same
     * call, for the other entry point into the same engine.
     */
    override fun onCreate() {
        super.onCreate()
        // A guess, and the only one available this early: no field has been
        // bound yet, so the user's first keyboard language is the best
        // available stand-in for the language of the first thing they will
        // type in. Each session warms its own locale as well {EM} see
        // [RimSession.onCreate] {EM} because this guess is wrong whenever the
        // field is in the other language, and being wrong here used to mean
        // the parse this call exists to avoid happened anyway, on a binder
        // thread, inside the first check.
        val lang = Prefs.languages(this).firstOrNull() ?: "en"
        engine.warm(lang, Languages.byCode(lang).locale, null, null)
    }

    override fun createSession(): Session = RimSession(engine, this)

    /**
     * Pick up anything written to the learned data since this service started.
     *
     * Guarded on the store existing: a service not yet asked about a word has
     * nothing to refresh, and building one here purely to refresh it would
     * undo the point of the lazy.
     */
    internal fun refreshLearned() {
        if (userDataLazy.isInitialized()) userData.reloadIfChanged()
    }

    /**
     * The platform asking for memory back.
     *
     * The keyboard has answered this for as long as the cache has been shared,
     * and it was the only thing that ever did — so the languages this
     * service loads were reclaimed only while an IME, which may not even be
     * RimBoard, was alive to hear the callback. A spell checker is selected
     * separately from a keyboard and its sessions outlive the keyboard being
     * dismissed, so the process could hold a dictionary for every locale it had
     * ever been bound to, with nothing left running that would let one go.
     *
     * Same policy as the keyboard's, including keeping what any live component
     * still wants rather than only what this one does.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        val keep = if (level >= TRIM_MEMORY_COMPLETE) emptySet()
        else SuggestionEngine.neededLanguages()
        SuggestionEngine.trimDictionaries(keep)
    }

    companion object {
        /**
         * "No opinion": neither in the dictionary nor a typo, so the framework
         * draws nothing — the right answer for a URL or a version number.
         *
         * A new instance every time, and that is the whole point. This was a
         * shared `val`, which looked like an obvious saving on an immutable
         * constant. [SuggestionsInfo] is not immutable: the framework calls
         * `setCookieAndSequence` on whatever a session returns, to tag the
         * answer with the word it belongs to. `onGetSuggestionsMultiple` walks
         * a batch of words calling this method and tagging each result in turn,
         * so returning one object for every unjudged word in a batch left them
         * all as the same object, carrying whichever cookie was written last —
         * and the framework matches answers to words by that cookie. Sessions
         * also run on binder threads, so the shared instance was being mutated
         * from several at once.
         */
        internal fun notJudged() = infoFor(0, emptyList())

        /**
         * Every answer this service hands back, built here and only here.
         *
         * The rule above is not about the not-judged case, it is about every
         * case, and it used to be enforced by [notJudged] being the only
         * constructor call anyone thought about. That stopped being true when
         * verdicts started being cached as data and rebuilt per answer: the
         * real construction moved to the session, [notJudged] stopped being
         * called by anything but its own test, and the guard was left watching
         * a door the code no longer used.
         *
         * The array is rebuilt too, not only the [SuggestionsInfo]. A cached
         * [Verdict] holds one list for as many answers as it is asked for, and
         * handing the same array to several of them would recreate the shared
         * mutable object in the one place still worth worrying about.
         */
        internal fun infoFor(attrs: Int, words: List<String>): SuggestionsInfo =
            SuggestionsInfo(attrs, if (words.isEmpty()) null else words.toTypedArray())
    }

    /**
     * The store owns a background thread. Nothing here ever writes — this
     * service is read-only by design — so there is nothing to flush, but the
     * executor still has to be released when the system unbinds us.
     */
    override fun onDestroy() {
        // Guarded, so unbinding a service that never checked a word does not
        // build the store purely in order to tear it down.
        // Nothing here needs a dictionary any more, and saying so is what
        // lets the next trim from anywhere actually reclaim them.
        SuggestionEngine.declareNeeded(SuggestionEngine.NEEDED_SPELL, emptySet())
        if (engineLazy.isInitialized()) engine.shutdown()
        if (userDataLazy.isInitialized()) userData.shutdown()
        super.onDestroy()
    }

    /**
     * One bound text field.
     *
     * Only [onGetSuggestions] is implemented. The framework's own sentence
     * splitter calls it per word, and it handles the tokenisation edge cases —
     * scripts without spaces, punctuation, sentence boundaries — that this
     * would otherwise have to reimplement badly.
     */
    /** How many expensive answers are left in the call being served. */
    private class Budget(private var left: Int) {
        fun take(): Boolean {
            if (left <= 0) return false
            left--
            return true
        }
    }

    private class RimSession(
        private val engine: SuggestionEngine,
        private val service: RimSpellService
    ) : Session() {

        private var lang = "en"
        private var loc: Locale = Locale.ENGLISH
        private var altLang: String? = null
        private var altLoc: Locale? = null

        override fun onCreate() {
            // The system hands over the locale it bound this session for, which
            // is the language of the *field*, not of the keyboard. Honoured as
            // given: someone writing German in one app and Turkish in another
            // should get each judged on its own terms.
            val requested = locale?.replace('_', '-')?.let { Locale.forLanguageTag(it) }
                ?: Locale.getDefault()
            val code = requested.language.lowercase(Locale.ROOT)
            lang = if (code in Languages.codes) code else "en"
            loc = Languages.byCode(lang).locale

            // The user's other enabled keyboard language, so a bilingual writer
            // does not get every English word in a Turkish message underlined.
            // Read from the same setting the keyboard uses, for the same reason.
            altLang = Prefs.languages(service).firstOrNull { it != lang }
            altLoc = altLang?.let { Languages.byCode(it).locale }

            // Now that the field's language is known, warm *that*. The service
            // warmed its best guess at creation, and a guess is what it was:
            // someone whose first keyboard language is Turkish, typing into a
            // German field, got a cold dictionary parse on a binder thread
            // with the framework waiting {EM} several hundred milliseconds on a
            // phone, inside the first onGetSuggestions, which is precisely the
            // stall warming exists to prevent.
            //
            // Cheap to call when the guess was right: `warm` hands the work to
            // its own executor and the dictionary cache returns immediately for
            // a language already loaded. Sessions are created per text field,
            // so this runs often and must stay that way.
            // A new field is the natural moment to notice that the personal
            // dictionary has changed since this service started. Four stats,
            // and a queued re-read only when something actually moved.
            service.refreshLearned()

            // The engine defaults this on and the keyboard sets it from the
            // preference on every focus change. This engine is a different
            // instance and nothing ever set it, so "block offensive words"
            // applied to half the app: turn it off and the keyboard offers the
            // word while the spell checker goes on refusing to, leaving an
            // underline that cannot be fixed from the popup that put it there.
            //
            // Read per session rather than once, for the same reason the
            // keyboard reads it per focus change — the setting can be
            // changed at any moment and a spell checker service outlives a
            // great many trips to the settings screen.
            engine.blockOffensive = Prefs.blockOffensive(service)

            SuggestionEngine.declareNeeded(
                SuggestionEngine.NEEDED_SPELL, setOfNotNull(lang, altLang)
            )
            engine.warm(lang, loc, altLang, altLoc)
        }

        /**
         * A whole sentence, tokenised here rather than by the framework.
         *
         * The default implementation splits the sentence and calls
         * [onGetSuggestions] once per word, which is why this service used to
         * judge every word in isolation. The engine has always been able to
         * re-rank corrections by what the preceding word predicts — the
         * keyboard's own strip has used it for as long as it has existed —
         * and this was the one caller that could not supply it, because a
         * single [TextInfo] holds one word and no way to ask what came before.
         *
         * "the stroe" is one edit from both "store" and "stone". Frequency
         * alone picks by popularity; the preceding word picks the shop.
         *
         * The cookie and sequence of the sentence are copied onto every answer
         * derived from it, which is how the framework matches each result back
         * to the span it belongs to — the same mechanism, and the same
         * failure if it is skipped, as the shared-instance bug that
         * [notJudged] carries a comment about.
         */
        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<out TextInfo>?,
            suggestionsLimit: Int
        ): Array<SentenceSuggestionsInfo> {
            val infos = textInfos ?: return emptyArray()
            return Array(infos.size) { k -> judgeSentence(infos[k], suggestionsLimit) }
        }

        private fun judgeSentence(info: TextInfo, limit: Int): SentenceSuggestionsInfo {
            val text = info.text.orEmpty()
            val tokens = SpellTokens.of(text)
            // One budget for the sentence, not for each word in it.
            val budget = Budget(CORRECTION_BUDGET)
            val out = arrayOfNulls<SuggestionsInfo>(tokens.size)
            val offsets = IntArray(tokens.size)
            val lengths = IntArray(tokens.size)
            // Two words of context, the same depth the keyboard's own ranking
            // uses. Taken from the tokens rather than from the raw text so
            // that punctuation between them is already gone.
            var prev2 = ""
            var prev = ""
            for ((i, t) in tokens.withIndex()) {
                offsets[i] = t.start
                lengths[i] = t.length
                // A full stop ends the evidence as well as the sentence: the
                // word before it is not context for the word after it. Ranking
                // across that boundary is the mistake SentenceContext exists to
                // prevent, and a TextInfo holding exactly one sentence is a
                // convention of the caller rather than a guarantee.
                if (t.startsSentence) {
                    prev = ""
                    prev2 = ""
                }
                // Only a word opening a sentence has a capital that means "a
                // sentence starts here" rather than "this is a name".
                out[i] = judge(
                    t.text, prev2, prev, SpellTokens.followerOf(tokens, i),
                    limit, sentenceInitial = t.startsSentence, budget = budget
                )
                    .also { it.setCookieAndSequence(info.cookie, info.sequence) }
                prev2 = prev
                prev = t.text
            }
            return SentenceSuggestionsInfo(out, offsets, lengths)
        }

        /**
         * The word-at-a-time entry point, kept because the framework still uses
         * it for callers that ask for word-level checking. It simply has no
         * context to offer.
         */
        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo =
            // null, not false and not true: this path is handed one word with
            // no neighbours and genuinely does not know where it sits. The two
            // questions that depend on position want opposite answers when it
            // is unknown — the capital gets the lenient reading, so
            // capitalised words are still judged rather than silently declined,
            // while the ranking gets no sentence-opener context, because
            // "might be the first word" is not evidence that it is.
            judge(
                textInfo?.text.orEmpty(), "", "", "", suggestionsLimit,
                sentenceInitial = null,
                // One word asked about on its own is never the pathological
                // case this bounds, so it gets exactly what it needs.
                budget = Budget(1)
            )

        /**
         * The question a verdict answers. Everything that can change the
         * answer is in here, so a hit is a hit for the right reasons.
         */
        private data class Ask(
            val word: String,
            val prev: String,
            val prev2: String,
            val next: String,
            val sentenceInitial: Boolean?,
            val limit: Int,
            /**
             * Whether the prediction model was loaded when this was answered.
             *
             * The verdict depends on it {EM} without the model there is no
             * curated context to rank by {EM} so leaving it out of the key
             * meant a verdict reached during the seconds before warm() lands
             * was served for the rest of the session, unranked, long after the
             * evidence for ranking it had arrived. That is the same fault, in
             * the same shape, as the app-palette cache keyed on the package
             * but not on the polarity it was read under.
             *
             * Safe to read once and trust because it only ever goes from false
             * to true: a model is never unloaded, so a key claiming context
             * cannot outlive it.
             */
            val contextual: Boolean
        )

        /**
         * A verdict as data rather than as a [SuggestionsInfo].
         *
         * The framework writes a cookie into whatever a session returns, so
         * handing out the same instance twice is a bug this class has already
         * had once — see [notJudged]. Caching the ingredients and building a
         * fresh answer from them each time keeps the saving without keeping
         * the hazard.
         */
        private data class Verdict(
            val attrs: Int,
            val words: List<String>,
            /**
             * False when the correction search was skipped for budget rather
             * than finished. The word is still reported as a typo — that
             * much was known cheaply — but the empty suggestion list is a
             * deferral and not an answer, so it must not be cached as one.
             */
            val complete: Boolean = true
        )

        /**
         * Sixty-four is a long sentence's worth of distinct words. The point
         * is not to remember much, it is to stop re-judging the word the user
         * is still in the middle of getting wrong.
         */
        private val verdicts = SpellCache<Ask, Verdict>(64)

        private fun judge(
            word: String,
            prev2: String,
            prev: String,
            next: String,
            suggestionsLimit: Int,
            sentenceInitial: Boolean?,
            budget: Budget
        ): SuggestionsInfo {
            val ask = Ask(
                word, prev, prev2, next, sentenceInitial, suggestionsLimit,
                contextual = engine.predictionsReady(lang)
            )
            var v = verdicts.get(ask)
            if (v == null) {
                v = verdictFor(word, prev2, prev, next, suggestionsLimit, sentenceInitial, budget)
                // Only once there is a dictionary to have judged against. Until
                // the load finishes every word is unknown and every correction
                // list is empty, and caching that would pin "everything in this
                // field is a typo, and there is nothing to be done about it"
                // for the life of the session.
                if (v.complete && engine.cachedDictionary(lang) != null) verdicts.put(ask, v)
            }
            // A new instance per answer, always — and the only place in the
            // service that builds one, so the rule has somewhere to live and
            // something to test. See [infoFor].
            return infoFor(v.attrs, v.words)
        }

        private fun verdictFor(
            word: String,
            prev2: String,
            prev: String,
            next: String,
            suggestionsLimit: Int,
            sentenceInitial: Boolean?,
            budget: Budget
        ): Verdict {
            // Unknown position reads as a sentence start here: that is the
            // lenient direction, and declining every capitalised word on a
            // guess would be worse than judging one name too many.
            if (!SpellCandidacy.worthChecking(word, sentenceInitial ?: true, lang, loc)) {
                return Verdict(0, emptyList())
            }

            if (engine.acceptedWord(word, lang, loc, altLang, altLoc)) {
                return Verdict(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyList())
            }

            // Everything above this line is a lookup. Everything below it
            // walks every dictionary word within the edit budget, and this is
            // where a sentence can turn expensive: paste a paragraph in a
            // language that is not enabled and every word of it is unknown, so
            // every word of it pays for a full scan, on a binder thread, in one
            // call. The cache absorbs repeats and does nothing for a wall of
            // words that are each wrong once.
            //
            // Past the budget the word is still underlined — being unknown
            // was established cheaply, and saying so costs nothing — but no
            // corrections are worked out for it. An underline with no
            // suggestions is a shape the API has and this code already
            // produces when nothing is found.
            if (!budget.take()) {
                return Verdict(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, emptyList(), complete = false
                )
            }

            // Same ranking the suggestion strip uses, so the fix offered by a
            // long-press on the underline is the fix the keyboard would have
            // made. Contractions first: "dont" is a missing apostrophe rather
            // than a mistyped word, and edit distance does not know that.
            val contraction = engine.contractionFor(word, lang, loc)?.first
            // What the preceding word predicts, as a rank map, exactly as the
            // keyboard builds it for the word being typed. It re-ranks the
            // dictionary's own candidates and never invents one, and the bonus
            // is bounded below the spatial term, so context breaks a near-tie
            // without pulling a distant word past an obvious adjacent-key fix.
            // mayLoad = false rather than a readiness check of its own: this
            // runs on a binder thread and predictions() would otherwise parse
            // the model here, which is the stall the warm fix removed and this
            // ranking had reintroduced by another door. Passing the flag says
            // it once, in the place that cannot afford it, and still gets the
            // learned bigrams — which a readiness check would have thrown
            // away along with the model.
            // An empty preceding word is two different situations and only
            // one of them is "nothing to go on". A word that *opens* a sentence
            // has a context of its own: the engine keys that under
            // UserData.START, and en, tr, de, es and ru each ship twenty
            // curated openers for it. The keyboard has always used them —
            // predictions() says so in as many words, having once returned
            // nothing there and left the strip blank — and the spell checker
            // was throwing them away, so the first word of every sentence was
            // ranked on frequency alone. Where the position is unknown there is
            // still nothing to go on, and this asks for nothing.
            val contextRank =
                if (prev.isEmpty() && sentenceInitial != true) emptyMap()
                else engine.predictions(prev2, prev, lang, loc, CONTEXT_DEPTH, mayLoad = false)
                    .withIndex().associate { (i, w) -> w.lowercase(loc) to i }
            val cap = suggestionsLimit.coerceIn(1, MAX_SUGGESTIONS)
            // A few more than will be shown, so the word after the typo has
            // something to promote from. Re-ranking a list that has already
            // been cut cannot recover a candidate the cut removed, and the
            // extra ones cost nothing: the scan behind this runs over every
            // candidate regardless and the count only decides where to stop.
            val pool = engine.correctionCandidates(
                word, lang, loc, altLang, altLoc,
                limit = cap + RIGHT_CONTEXT_POOL,
                contextRank = contextRank
            )
            // The word *after* the typo, which the n-grams can only be asked
            // about one way round: not "what precedes this" but "does this
            // candidate usually come before it". A stable sort on a yes/no, so
            // a candidate that fits the following word rises above one that
            // does not while the engine's own ordering survives inside each
            // group {EM} the same restraint the left-hand context is held to,
            // which is that evidence breaks ties rather than overruling the
            // channel model.
            val corrections =
                if (next.isEmpty()) pool
                else pool.sortedByDescending { engine.continues(it, next, lang, loc) }
            // A run-together pair. Last, because it is the largest change: the
            // others fix a word, this one adds a boundary between two.
            val split = engine.splitFor(word, lang, loc)
            val out = (listOfNotNull(contraction) + corrections + listOfNotNull(split))
                .distinct()
                // The same bound as the correction query above. Without it
                // MAX_SUGGESTIONS held only the corrections, and a framework
                // limit above five let the contraction and the split past it
                // — seven entries in a popup documented to hold five.
                .take(suggestionsLimit.coerceIn(1, MAX_SUGGESTIONS))

            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (out.isNotEmpty()) {
                attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            }
            return Verdict(attrs, out)
        }


        private companion object {


            /** More than this and the popup is a menu rather than a fix. */
            const val MAX_SUGGESTIONS = 5

            /**
             * How far down the prediction list a word still counts as
             * "expected here". Deep enough to catch an ordinary continuation,
             * shallow enough that the tail of the list — which is barely
             * ranked at all — does not start nudging corrections about.
             */
            const val CONTEXT_DEPTH = 12

            /**
             * Extra candidates fetched so the following word has something to
             * promote from. Four is enough for the right answer to be sitting
             * just below the cut, which is the case this exists for, without
             * turning the popup's shortlist into a long tail of near-misses.
             */
            const val RIGHT_CONTEXT_POOL = 4

            /**
             * How many words in one sentence may have corrections worked out.
             *
             * Far above any real sentence {EM} twenty-four misspellings in one
             * is not writing, it is a paste {EM} so this never fires on the
             * case it is not for. What it bounds is the case where every word
             * is unknown, which is a paragraph in a language the user has not
             * enabled, and which without a bound is hundreds of full-dictionary
             * scans in a single call the framework is waiting on.
             */
            const val CORRECTION_BUDGET = 24
        }
    }
}
