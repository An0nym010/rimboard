package com.rimboard.keyboard.engine

import android.content.Context
import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import com.rimboard.keyboard.model.WordCase
import java.util.Locale
import kotlin.math.ln

class SuggestionsResult(
    val items: List<String>,
    val autocorrectIndex: Int   // index that would be committed on space, or -1
)

/**
 * Which generation of word lists the process is holding.
 *
 * Part of every dictionary cache key, so moving it is the whole of the
 * invalidation when a word list changes underneath — a personal-dictionary
 * import, an extended dictionary installed or removed.
 */
object DictVersion {

    private val n = java.util.concurrent.atomic.AtomicInteger()

    /** Read on every dictionary lookup; a volatile read and nothing more. */
    val v: Int get() = n.get()

    /**
     * Every word list in the process is stale from here.
     *
     * `v++` on a plain field is a read, an add and a write. Two installs
     * finishing together could produce one increment between them, leaving a
     * cache keyed to a generation neither of them wrote and serving the old
     * list until something else moved it. Nothing here is hot enough for that
     * to be worth a plain field.
     */
    fun bump(): Int = n.incrementAndGet()
}

class SuggestionEngine private constructor(
    private val assets: Assets,
    private val userDir: java.io.File?,
    private val userData: UserData,
    /**
     * Whether this engine reads the app's own bundled assets, and so may share
     * loaded dictionaries with every other engine that does. False for the test
     * seam, whose assets are per-instance and must not leak between cases.
     */
    shared: Boolean
) {

    /**
     * Where the engine reads its data from.
     *
     * The engine only ever touched a [Context] to open bundled assets and to
     * find the user-dictionary directory. Naming that as a seam is what lets
     * the ranking be tested with a handful of in-memory words instead of the
     * 200k-word shipping assets and a real device — the context-aware
     * completion and correction ranking was previously untestable for exactly
     * this reason, and shipped without a test as a result.
     */
    fun interface Assets {
        /** The stream for [path] under assets/, or null if there is none. */
        fun open(path: String): java.io.InputStream?
    }

    /** App path: read bundled assets, and the learned words from the context. */
    constructor(context: Context, userData: UserData) : this(
        Assets { path ->
            // A downloaded dictionary shadows the bundled one. The store
            // answers for `dictionaries/<lang>.txt` and nothing else, so every
            // other asset here is still the APK's own -- see [DictionaryStore].
            DictionaryStore.open(context, path)
                ?: try { context.assets.open(path) } catch (_: Exception) { null }
        },
        UserData.dataDir(context),
        userData,
        shared = true
    )

    companion object {

        /**
         * How many shape-matched words the dictionary is asked for.
         *
         * Deeper than the four the strip can show, because context re-ranks
         * them afterwards and needs something to promote from. Asking for four
         * would let context reorder only what shape already liked best, which
         * is most of the way to not consulting it at all.
         */
        private const val GLIDE_DEPTH = 24

        /**
         * How many words a swipe is allowed to offer.
         *
         * Three, because the suggestion strip has three slots and every caller
         * of this takes three. Returning a fourth computed a candidate the user
         * had no way to reach and quietly made the accuracy this is measured at
         * look better than the accuracy anyone could use.
         */
        private const val GLIDE_OFFERED = 3

        /**
         * How far below an attested completion a joined elision ranks.
         *
         * The same argument as [MORPH_PENALTY] and the same number: the join is
         * grammatical rather than counted, so an attested word of the same
         * prefix -- if the corpus happens to hold one -- is the better guess.
         */
        private const val ELISION_PENALTY = 0.55

        /** The learned list is small; this is a bound, not a filter. */
        private const val GLIDE_PERSONAL_DEPTH = 12

        /**
         * How far below the primary language a word from the other one sits,
         * once both are on the same scale.
         *
         * Applied *after* dividing out corpus size — see the blend in
         * [suggestionsFor]. Before that division this number was doing almost
         * no work: a 0.85 discount on a figure five times too large is still
         * four times too large, so the "secondary" language outranked the
         * primary among exactly the common words completions come from.
         *
         * **Swept, and kept at the value it already had.** Keystrokes saved,
         * over English and Turkish prose, in the four configurations a
         * bilingual user actually meets:
         *
         *                    en typing en   tr typing tr   en typing tr   tr typing en
         *     no second lang     37.7           31.2            1.4            8.7
         *     0.85 unnormalised  37.3           28.0           25.7           34.8
         *     1.00 normalised    36.0           29.9           28.0           32.8
         *     0.85 normalised    36.2           30.1           27.7           32.8  <- here
         *     0.60 normalised    36.5           30.2           27.3           31.7
         *     0.45 normalised    36.7           30.7           26.9           29.8
         *     0.30 normalised    37.3           30.8           25.7           28.8
         *
         * Normalising moves ground toward whichever language was built from
         * *less* text, in both configurations — which is the whole point, since
         * corpus size is a fact about asset construction and not about the user.
         * The column that matters is the second: **turning on a second language
         * used to cost Turkish 3.2 points of its own typing and now costs 1.1.**
         *
         * Lowering the weight further keeps buying the primary a little more,
         * and costs the other language about twice as much each time. Averaged
         * over the four columns the best value is 0.85 or 1.00, and 0.85 is
         * already what the code said, so it stays. The number was never the
         * problem; the scale it multiplied was.
         */
        private const val ALT_WEIGHT = 0.85

        /**
         * Where a word the user taught the keyboard sits on the dictionary's
         * own frequency scale, before its use count is added.
         *
         * A learned word arrives with a use count, usually a handful. Scoring
         * it as `ln(count + 1)` puts it around 1.4 on a scale where an ordinary
         * dictionary word sits at 6 and "the" sits at 17, so it would lose to
         * everything, always. The rule this replaced dodged that by adding a
         * billion to the score, which put every learned word above every
         * dictionary word unconditionally — fine while the match test was exact
         * and wrong the moment it became a fit, because a barely-fitting name
         * would then beat the word actually swiped.
         *
         * Anchored against the real distribution instead. In the shipped
         * lists, `ln(freq + 1)` is 2.8 at the median English word, 6.0 at the
         * 90th percentile, 9.5 at the 99th and 17.2 at the top. At 7.0 a word
         * the user has typed twice scores 8.1 — inside the top few percent, so
         * it beats the long tail of words they have never used, and nowhere
         * near the function words. It still has to fit the path: this moves a
         * learned word up the frequency axis, it does not exempt it from the
         * shape one.
         */
        private const val PERSONAL_GLIDE_LN_FREQ = 7.0
        /**
         * Test seam: back the engine with in-memory data and no Context.
         *
         * [shared] defaults to false so fixtures cannot leak between cases. It
         * is opt-in for the one thing that cannot be tested without it — the
         * eviction of the process-wide cache, which by definition is not
         * per-instance. A case that passes true owes the next case a
         * [trimLanguageCaches] with an empty set.
         */
        internal fun forTesting(userData: UserData, shared: Boolean = false, assets: Assets) =
            SuggestionEngine(assets, null, userData, shared = shared)

        /**
         * Dictionaries, shared by every engine reading the bundled assets.
         *
         * There are two such engines in this process — the keyboard and the
         * system spell checker — and they were loading their own copy of the
         * same word list. A shipped language is on the order of fifteen
         * megabytes once parsed into its strings, frequency array and length
         * buckets, so turning the spell checker on quietly doubled the memory
         * the keyboard needs to stay alive. They are immutable once built and
         * keyed by content version, so there is nothing to gain from separate
         * copies.
         */
        private val sharedDictionaries = java.util.concurrent.ConcurrentHashMap<String, Dictionary>()

        /**
         * Drop cached dictionaries, keeping the ones for [keep].
         *
         * Sharing the cache made it `static`, and that quietly removed the only
         * thing that ever reclaimed this memory. Before, the maps belonged to
         * the engines and died with them; now they belong to the class, so they
         * outlive the keyboard being dismissed, the spell checker being turned
         * off, and the service itself being destroyed — the process keeps every
         * language it has ever loaded, at roughly fifteen megabytes each, until
         * the process is killed. A multilingual user who has cycled through four
         * languages is holding sixty megabytes that nothing will ever release.
         *
         * Called from the platform's own memory-pressure callback, which is the
         * signal that the process is a candidate for being killed. Rebuilding a
         * dictionary costs one asset parse on the warm thread; being killed
         * costs the user their keyboard mid-sentence.
         */
        /**
         * Languages a live component still needs, by component.
         *
         * The cache is one map for the whole process, and the two components
         * that fill it want different languages: the keyboard wants the two the
         * user selected, the spell checker wants whatever locale the field it
         * was bound to declares, which is frequently neither. Trimming to "what
         * I need" therefore meant evicting what the other one was using, and
         * the eviction is invisible until the next word arrives and the
         * dictionary is parsed again to answer it — on a binder thread, in
         * the spell checker's case, with the framework waiting.
         *
         * So the question a trim asks is not "what do I need" but "what does
         * anything still need", and each component answers for itself.
         */
        private val needed = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

        const val NEEDED_KEYBOARD = "keyboard"
        const val NEEDED_SPELL = "spell"

        /** Declare what [owner] still needs; an empty set means "nothing". */
        fun declareNeeded(owner: String, langs: Set<String>) {
            if (langs.isEmpty()) needed.remove(owner) else needed[owner] = langs
        }

        /** Every language some live component still needs. */
        fun neededLanguages(): Set<String> = needed.values.flatMapTo(HashSet()) { it }

        /**
         * Prediction models, shared by every engine reading the bundled
         * assets — for the same reason the dictionaries are.
         *
         * They were per-instance, which meant the keyboard and the spell
         * checker each parsed and held their own copy of the same language:
         * two loads of the same asset and two live maps of it. That cost
         * little when a model was 200 KB of bigrams. It is 2.6 to 4.9 MB now
         * that they carry two-word contexts, and the two components are
         * usually on the same language.
         */
        private val sharedModels =
            java.util.concurrent.ConcurrentHashMap<String, Map<String, List<String>>>()

        /**
         * Drops every language-keyed cache except [keep].
         *
         * Both halves, because both are per-language, both are megabytes, and
         * a caller that has decided it is short of memory means both. The
         * models used to be missed here entirely: at TRIM_MEMORY_COMPLETE the
         * process gave up every dictionary and held on to every model.
         */
        fun trimLanguageCaches(keep: Set<String>) {
            val live = keep.map { it + "#" + DictVersion.v }.toSet()
            sharedDictionaries.keys.removeAll { it !in live }
            sharedModels.keys.removeAll { it !in keep }
        }

        /** How many dictionaries the process is holding. Test-only. */
        internal fun cachedCount() = sharedDictionaries.size

        /** How many prediction models the process is holding. Test-only. */
        internal fun cachedModelCount() = sharedModels.size

        private const val TAG = "RimBoard"

        /**
         * How many prefix matches the dictionary is asked for per keystroke.
         *
         * Swept -- see StripAccuracyTest.
         */
        const val COMPLETION_FETCH = 12

        /** How many next-word predictions feed completion re-ranking. */
        const val CONTEXT_COMPLETION_DEPTH = 12

        /**
         * Strength of the completion boost. The top-predicted completion has
         * its frequency multiplied by 1 + this; the effect fades with rank. At
         * 6, a strongly-predicted word overtakes one up to seven times commoner
         * — enough to matter, not so much that context drowns frequency.
         */
        const val CONTEXT_COMPLETION_WEIGHT = 6.0

        /**
         * Additive tie-break for corrections; see [contextBonus].
         *
         * Came down from 1.5 on 2026-08-28, alongside `MIN_PAIR` falling to 2
         * in `tools/build_ngrams.py`. The two are one decision: that constant
         * decides how many context rows exist, this one decides how loudly they
         * speak on the correction path, and changing either alone moves the
         * damage this file's ceiling is set against. Swept jointly, worst
         * damage across all four arms with the denser model:
         *
         *     1.50   25.2%   28 rescued, 7 broken   over the 25% ceiling
         *     1.25   20.2%   24 rescued, 7 broken   here
         *     1.00   17.1%   19 rescued, 5 broken
         *
         * 1.25 holds the shipped behaviour to within noise -- 19.9% and 22/4
         * with the sparser model -- so the strip's extra coverage costs the
         * corrections nothing measurable. 1.0 is quieter still and gives up
         * five rescues for it; the ceiling has margin at 1.25, which is what
         * 1.75 was rejected for lacking when this was last swept.
         */
        const val CONTEXT_CORRECTION_WEIGHT = 1.25


        /**
         * How thin the completion list has to get before near-miss prefixes are
         * consulted. Above this there is plenty to show and the extra lookups
         * would only add noise; at or below it the strip is nearly empty, which
         * is the symptom of a typo already in the prefix.
         */
        private const val FUZZY_TRIGGER = 3

        /**
         * A generated inflection ranks below the corpus words it sits with. It
         * is grammatically certain but not attested, and an attested form of
         * the same stem is the better guess when both fit.
         */
        private const val MORPH_PENALTY = 0.55

        /** Fallback weight when the prefix matched no corpus word at all. */
        private const val MORPH_BASE_SCORE = 10_000L

        /**
         * What the top entry of the curated prediction list is worth, on the
         * same scale as a count of how many times the user has typed something.
         *
         * At 3, the hand-written first guess holds its place against a pair the
         * user has typed once or twice and gives way at three — which is about
         * where a repetition stops looking like an accident. A single trigram
         * hit outranks it outright, because an exact two-word context is much
         * more specific than a curated single-word one.
         */
        private const val STATIC_WEIGHT = 3.0

        /**
         * How many extra correction candidates to pull before filtering, so
         * that dropping one leaves something behind it. See the call site.
         */
        private const val CORRECTION_POOL = 20
    }

    /** Multiplier applied to a completion's frequency for its context rank. */
    private fun completionFactor(word: String, contextRank: Map<String, Int>): Double {
        val r = contextRank[word] ?: return 1.0
        return 1.0 + CONTEXT_COMPLETION_WEIGHT / (r + 1.0)
    }

    private val cache =
        if (shared) sharedDictionaries else java.util.concurrent.ConcurrentHashMap()

    /**
     * One reusable thread for warming, rather than a fresh one per call.
     *
     * [warm] runs on every focus change — every app switch, every rotation, and
     * every settings change that reconfigures the keyboard — and each call used
     * to construct and start a `Thread`. Switching between two apps repeatedly
     * is an ordinary thing to do while typing, and it made a thread each time.
     * Worse, before the first load finished they did not return quickly: each
     * new one blocked on the same dictionary lock, so a cold start plus a burst
     * of focus changes left a pile of threads waiting to discover there was
     * nothing left to do. Serialised on one daemon thread, a warm that is
     * already done costs a queue entry.
     */
    private val warmer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RimBoard-warm").apply { isDaemon = true }
    }

    /**
     * Releases the warming thread.
     *
     * Every engine owns one, two engines exist in this process (the keyboard
     * and the spell checker), and neither service released it — the same
     * discipline [UserData.shutdown] documents for its own executor, missed
     * here. The thread is a daemon so it never blocked process exit; it simply
     * sat idle for the life of the process.
     */
    fun shutdown() {
        warmer.shutdown()
    }

    /** Preload dictionaries on a background thread so the first keystroke never stalls. */
    fun warm(lang: String, locale: Locale, altLang: String?, altLocale: Locale?) {
        warmer.execute {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                dictionary(lang, locale)
                predictionModel(lang)
                if (altLang != null && altLocale != null) {
                    dictionary(altLang, altLocale)
                    // Its n-grams too: without them somebody typing their
                    // second language gets no context ranking at all.
                    predictionModel(altLang)
                }
                // End to end: how long after opening the keyboard suggestions
                // are actually ready. Nothing here had ever been measured.
                android.util.Log.i(
                    TAG, "warm($lang) ready in " +
                        "${android.os.SystemClock.elapsedRealtime() - started}ms"
                )
            } catch (e: Exception) {
                // This runs on a background thread, so a throw here vanishes
                // and the whole engine just appears to have no data.
                android.util.Log.w(TAG, "warm($lang) failed", e)
            }
        }
    }

    /**
     * The dictionary for [lang], loading it if this is the first ask.
     *
     * Locked on the cache rather than on `this`, because the cache may be
     * shared with the other engine in the process: two instances synchronizing
     * on themselves would each hold their own monitor and could both decide the
     * dictionary was missing and load it at the same time. The first read is
     * outside the lock so the common case — already loaded — never waits behind
     * somebody else's parse.
     */
    fun dictionary(lang: String, locale: Locale): Dictionary {
        val key = lang + "#" + DictVersion.v
        cache[key]?.let { return it }
        synchronized(cache) {
            cache[key]?.let { return it }
            val started = android.os.SystemClock.elapsedRealtime()
            // A missing asset yields an empty dictionary — never an exception,
            // and never another language's words standing in for this one.
            val dictStream = assets.open("dictionaries/$lang.txt")
            if (dictStream == null) {
                // A null here means no suggestions at all for this language,
                // which is indistinguishable from the engine being broken.
                android.util.Log.w(TAG, "no dictionary asset for $lang")
            }
            val userStream = try {
                val f = userDir?.let { java.io.File(it, "userdict_" + lang + ".txt") }
                if (f != null && f.exists()) f.inputStream() else null
            } catch (_: Exception) {
                null
            }
            // Anything for this language under an older version is now
            // unreachable — the key carries the version — and holding it only
            // costs the memory of a whole word list. Editing the personal
            // dictionary a few times used to leave every previous copy behind.
            val stale = "$lang#"
            cache.keys.removeAll { it.startsWith(stale) && it != key }
            // Covers parsing, the character-transition model and the length
            // buckets — everything on the warm path. `adb logcat -s RimBoard`.
            val d = Dictionary(dictStream, userStream, locale)
            cache[key] = d
            android.util.Log.i(
                TAG,
                "dictionary $lang: ${d.size} words in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms"
            )
            return d
        }
    }

    /**
     * The dictionary for [lang] only if it is already loaded, via a lock-free
     * read. Safe to call on the UI thread (per-keystroke tap arbitration): it
     * never blocks behind the warm thread's synchronous asset load, and simply
     * returns null (skip arbitration) until the load has finished.
     */
    fun cachedDictionary(lang: String): Dictionary? = cache[lang + "#" + DictVersion.v]

    /**
     * Whether the prediction model for [lang] is already in memory, via a
     * lock-free read.
     *
     * [predictionModel] parses an asset on the calling thread when the answer
     * is no, and blocks behind [warm]'s parse when one is already running.
     * That is fine on the warm thread and not fine on a binder thread with the
     * framework waiting — it is the same stall the spell checker's warm fix
     * removed, and the context ranking would have walked straight back into it
     * by asking for predictions before anything had loaded them.
     *
     * A caller that wants ranking rather than a stall asks this first and does
     * without the context when the answer is no. The first sentence typed in a
     * cold field is ranked on the channel model alone, which is what it was
     * ranked on before context existed at all.
     */
    fun predictionsReady(lang: String): Boolean = predictionModels.containsKey(lang)

    /** Languages whose model a non-blocking caller has already asked for. */
    private val modelQueued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The prediction model for [lang], loading it only if [mayLoad].
     *
     * When it may not, a missing model is not fetched on this thread and is not
     * simply given up on either: the warm thread is asked for it, so the next
     * keystroke has it. That matters because the alternative to blocking is
     * otherwise "this language quietly never gets curated predictions", which
     * is the kind of silence that survives for a release — [warm] loads the
     * model for the language it was called with, and a caller can be asking
     * about another one.
     */
    private fun modelFor(lang: String, mayLoad: Boolean): Map<String, List<String>> {
        if (mayLoad) return predictionModel(lang)
        predictionModels[lang]?.let { return it }
        // add() is the guard: one queued load per language, not one per
        // keystroke spent waiting for it.
        if (modelQueued.add(lang)) {
            warmer.execute {
                try {
                    predictionModel(lang)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "prediction model($lang) failed", e)
                }
            }
        }
        return emptyMap()
    }

    /**
     * Whether [next] is a known continuation of [word].
     *
     * The n-grams only run forwards, so this is the one direction that can be
     * asked cheaply: given a candidate, is the word that actually follows it in
     * the text one that usually follows it in the language? That turns the word
     * *after* a typo into evidence about it, which nothing could use before —
     * "the stroe was shut" has "was" sitting right there, and "store was" is a
     * pair while "stone was" is not.
     *
     * Two lookups and no allocation beyond the case folding: a map get on the
     * learned bigrams, and a get plus a short scan of the curated model's list.
     * Deliberately not [predictions], which builds and merges two score maps,
     * filters both lists and sorts the result — all of it thrown away to
     * answer a yes/no, once per candidate, on a binder thread.
     */
    fun continues(
        word: String,
        next: String,
        lang: String,
        locale: Locale,
        // No default, deliberately. This is the third reader of the learned
        // store and the only one that had no flag to forget -- so incognito
        // withheld the user's words from the candidate list and then ordered
        // what was left by the pairs they had typed. See SpellRightContextTest.
        personalized: Boolean
    ): Boolean {
        if (word.isEmpty() || next.isEmpty()) return false
        val a = word.lowercase(locale)
        val b = next.lowercase(locale)
        // A membership test, not a count: one typed pair moves a candidate,
        // which is the right bar for evidence and a very low one for a leak.
        if (personalized && userData.follows(a, b)) return true
        // The learned bigrams above are a concurrent map and always safe to
        // ask. The curated model is not loaded on demand here: doing so would
        // parse an asset on a binder thread, and a missing answer is only a
        // missed tie-break.
        if (!predictionsReady(lang)) return false
        val known = predictionModels[lang]?.get(a) ?: return false
        // Folded with the locale rather than equalsIgnoreCase, which is
        // locale-blind and would fold Turkish dotted and dotless i together.
        return known.any { it.lowercase(locale) == b }
    }

    @Volatile
    var blockOffensive = true

    /**
     * Whether autocorrect should hold to the stricter bar. User setting.
     *
     * The second settable property on this class, and it carries the same
     * obligation as the first: **both services have to set it.** The keyboard
     * and the system spell checker each build their own engine, and a
     * preference wired into one of them governs half the app — which has
     * already happened once here, with `blockOffensive`. Set from
     * `RimBoardService` on focus change and from `RimSpellService` per
     * session, for the same reason: a service outlives many trips to the
     * settings screen.
     */
    @Volatile
    var cautiousAutocorrect = false

    /**
     * Names from the address book, or empty when the user has not asked for
     * them — which is the default, and stays the default until they both
     * turn the setting on and grant the permission.
     *
     * Settable rather than read from here, for the same reason
     * [blockOffensive] is: this class has no Context and no business acquiring
     * one. Both services set it, so both stop flagging the people you write to.
     *
     * Empty is not a special case anywhere below; an empty set simply never
     * matches.
     */
    @Volatile
    var contactNames: Set<String> = emptySet()

    /**
     * Words from Android's own personal dictionary, or empty. Same contract as
     * [contactNames], separate property because they answer to separate
     * settings and separate permissions — turning one off must not silently
     * take the other with it.
     */
    @Volatile
    var userDictionaryWords: Set<String> = emptySet()
    /**
     * Per instance, unlike the dictionaries and the prediction models, and
     * deliberately so.
     *
     * Two engines exist in this process and each holds its own copy of these,
     * which is the exact duplication that was worth removing for the models —
     * at two to five megabytes each. An offensive list is a kilobyte or two
     * per language and the emoji table sixty kilobytes in total, so sharing
     * them would buy back less than a tenth of a megabyte in exchange for a
     * second process-wide cache to keep straight. Sized, not overlooked.
     */
    private val offensiveSets = HashMap<String, Set<String>>()

    /** See [predictionModelLock] for why this is not `@Synchronized`. */
    private fun offensive(lang: String): Set<String> = synchronized(offensiveLock) {
        offensiveSets.getOrPut(lang) {
            try {
                val out = HashSet<String>()
                (assets.open("offensive/$lang.txt") ?: return@getOrPut emptySet())
                    .bufferedReader().readLines()
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
                    .forEach {
                        out.add(it)
                        // German capitalises ß as SS and lowercasing never puts
                        // it back, so "scheiße" completed under caps lock
                        // arrives at the filter as "scheisse". Both readings are
                        // stored, because the round trip through upper case is
                        // lossy in a way no amount of care at the call site can
                        // undo.
                        if (it.indexOf('ß') >= 0) out.add(it.replace("ß", "ss"))
                    }
                out
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no offensive list for $lang", e)
                emptySet()
            }
        }
    }

    /**
     * Whether [word] is on the blocked list, judged in [locale].
     *
     * The word arriving here is already *cased for display* — this filter is the
     * last thing between the strip and the user, so [matchCase] has applied the
     * language's own rules to it. Folding that back with a locale-less
     * `lowercase()` undoes it wrongly wherever case is not a straight one-to-one
     * map: Turkish "İbne" folds to `i` + U+0307 rather than "ibne", so an
     * auto-capitalised slur went straight past a filter the user had switched
     * on. English, Spanish and Russian never showed it because their case
     * mapping happens to be locale-independent.
     */
    private fun isOffensive(word: String, lang: String, locale: Locale): Boolean {
        if (!blockOffensive) return false
        val here = word.lowercase(locale)
        if (listed(here, lang)) return true
        // A candidate from the user's *other* enabled language was cased with
        // that language's locale, so the primary one's rules are the wrong ones
        // to read it back with — English "PIC" folds to "pıc" under Turkish.
        // Both readings are tried: this filter may only ever err strict.
        val root = word.lowercase(Locale.ROOT)
        return root != here && listed(root, lang)
    }

    /**
     * The language's own list first, then English behind it -- except where the
     * word is ordinary vocabulary in the language being typed.
     *
     * The order matters and is the reason the exemption is safe: a word its own
     * language calls offensive is caught above, so
     * [com.rimboard.keyboard.model.FalseFriends] can only ever soften the
     * English fallback, never a native judgement.
     */
    private fun listed(lower: String, lang: String): Boolean =
        lower in offensive(lang) ||
            (lang != "en" &&
                !com.rimboard.keyboard.model.FalseFriends.ordinaryHere(lang, lower) &&
                lower in offensive("en"))

    private val emojiMaps = HashMap<String, Map<String, String>>()

    /**
     * The emoji a word suggests, or null.
     *
     * The current language first and English behind it, so a language with no
     * list of its own still answers for the English words people mix in — and
     * so adding a list for a new language is additive rather than a
     * prerequisite.
     */
    fun emojiFor(wordLower: String, lang: String): String? {
        emojiMap(lang)[wordLower]?.let { return it }
        if (lang == "en") return null
        // ...but only for words that do not already mean something here. See
        // FalseFriends.emojiMeansSomethingElse: Danish "fire" is the number
        // four and was being offered a flame.
        if (com.rimboard.keyboard.model.FalseFriends.emojiMeansSomethingElse(lang, wordLower)) {
            return null
        }
        return emojiMap("en")[wordLower]
    }

    /** See [predictionModelLock] for why this is not `@Synchronized`. */
    private fun emojiMap(lang: String): Map<String, String> = synchronized(emojiLock) {
        emojiMaps.getOrPut(lang) {
            try {
                (assets.open("emoji/$lang.txt") ?: return@getOrPut emptyMap())
                    .bufferedReader().readLines()
                    .mapNotNull { line ->
                        val p = line.split('\t')
                        if (p.size == 2) p[0] to p[1] else null
                    }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    /**
     * Whether [lang] accounts for [wordLower] — in the list, or built by that
     * language's own rules.
     *
     * This is what decides, one committed word at a time, which of two enabled
     * languages somebody is actually typing. Asking only whether the list
     * contains the word made every Turkish suffixed form and every German
     * compound evidence for *neither* language: about 7% of Turkish tokens are
     * absent from a 200,000-word list, so a Turkish typist's own words did not
     * count as Turkish, and the streak that switches the boost back off never
     * advanced.
     *
     * Deliberately not [acceptedWord], which is a wider question: it says yes
     * to contact names and hand-added words, and those are evidence for no
     * language in particular. The two rules used here are language-specific by
     * construction — Turkish morphology, German compounding — so English and
     * everything else are unaffected.
     */
    fun knownIn(wordLower: String, lang: String, locale: Locale): Boolean {
        val dict = dictionary(lang, locale)
        return dict.contains(wordLower) || wellFormedWord(wordLower, lang, dict)
    }

    /**
     * Ranked corrections the keyboard would offer for [typed] (best first, case
     * matched to what was typed). Applies the same guards as autocorrect: skips
     * short words, digits, mid-word capitals, words already known, and words
     * valid in the user's other enabled language, and filters out offensive or
     * blocked results. Ranking is keyboard-proximity aware (see [Dictionary]).
     */
    fun correctionCandidates(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null,
        limit: Int = 1,
        contextRank: Map<String, Int> = emptyMap(),
        touch: FloatArray? = null,
        /**
         * False in incognito, exactly as it is for [suggestionsFor],
         * [glideFor] and [predictions]. This was the one of the four that
         * never took the flag, so the user's own vocabulary went on correcting
         * their typos in a mode whose whole promise is that it does not.
         */
        personalized: Boolean = true
    ): List<String> {
        if (typed.length < 3) return emptyList()
        if (typed.any { it.isDigit() }) return emptyList()
        // A capital *inside* a word — iPhone, McDonald — is deliberate and must
        // not be corrected. A word that is entirely capitals is not that: it is
        // someone typing under caps lock, and refusing to correct them meant
        // TEH stayed TEH. Acronyms are safe without a special case, because a
        // real one is in the dictionary and returns above this line; NASA is
        // "nasa" folded, and it is a word.
        if (typed.drop(1).any { it.isUpperCase() } && typed != typed.uppercase(locale)) {
            return emptyList()
        }
        val dict = dictionary(lang, locale)
        val lower = typed.lowercase(locale)
        // A word the user added by hand is never corrected, whatever it looks
        // like. The dictionary containing it is a weaker statement: it contains
        // "hellooo" too, so an elongation still gets corrections — led by the
        // spelling it is an elongation of.
        if (userData.isKnown(lower)) return emptyList()
        val elongated = elongationBase(lower, dict)?.takeIf {
            !isOffensive(it, lang, locale) && !userData.isBlocked(it)
        }
        // The accented word this spells, where the corpus actually holds one.
        // Asked once and carried, because three places below want the same
        // answer and each of them was asking again.
        val attested = dict.accentedFormOf(lower)
        // Being in the dictionary is no longer the end of it: a bare-key
        // spelling of a far commoner accented word is in there too, and is not
        // what the user meant. See [Dictionary.accentedFormOf].
        if (dict.contains(lower) && elongated == null && attested == null) return emptyList()
        // Bare-letter spelling of an accented word: "cafe" -> "café",
        // "gunaydin" -> "günaydın". High confidence, because the query is not
        // itself a word and folds exactly onto a dictionary entry — so it leads
        // the list rather than competing as an edit-distance guess. It still
        // passes through the filters and case-matching below.
        val accented = accentedFormFor(lower, lang, dict, attested)?.takeIf {
            // Never the word that was typed. This exists to turn a bare-letter
            // spelling into its accented one, so a result identical to the
            // query means there was nothing to accent and no correction to
            // make — and because it leads the list, returning it put the
            // typo at the head of its own suggestions. Turkish reaches that
            // through consonant doubling: "bennce" parses as a stem and a
            // suffix, rebuilds to itself, and was offered as the fix for
            // itself ahead of "bence".
            // Not a word the corpus counted and ranked below the bar every
            // edit-distance candidate has to clear. A *generated* inflection
            // is absent rather than rare and stays welcome; see
            // [Dictionary.tooRareToOffer].
            it != lower && !dict.tooRareToOffer(it) &&
                !isOffensive(it, lang, locale) && !userData.isBlocked(it)
        }
        // Agglutinative languages build endless valid surface forms a frequency
        // dictionary cannot list. If the word peels down to a known stem
        // through recognised suffixes, it is a real word the corpus merely
        // never saw — do not "correct" it. See [Morphology]. Skipped when the
        // bare form spells an accented word, which is a correction, not a stem.
        // Skipped when the bare form spells an accented word, and equally
        // when it spells an elongation: both are corrections rather than
        // stems. Only the first of those was excluded, so an elongation whose
        // extra letters happen to peel off as a suffix was declared a valid
        // agglutinated form and offered nothing at all. Turkish "tabiii" peels
        // its last "i" and lands on "tabii", which is a real word, so the
        // engine concluded the typo was fine and stayed silent.
        // The attested accent form suppresses this, a built one does not --
        // the same precedence [acceptedWord] uses, because the doc on that
        // function promises the two agree about what counts as a word and a
        // split here would make it a liar.
        //
        // [wellFormedWord] rather than the morphology rule alone, because
        // German compounds are the same statement about the same word and
        // were missed when they arrived: "Nervenzelle" was accepted by the
        // underline and *overwritten by the space bar* with "Nervenzellen",
        // and "Landtiere" was offered "Landeier". Three callers, one
        // definition, so the promise above is structural rather than a habit.
        if (attested == null && elongated == null && wellFormedWord(lower, lang, dict)) {
            return emptyList()
        }
        // Bilingual typing: never "correct" a word that is valid in the
        // user's other enabled language (e.g. English words in Turkish mode).
        if (altLang != null && altLocale != null &&
            dictionary(altLang, altLocale).contains(typed.lowercase(altLocale))
        ) return emptyList()
        // A pool, not the answer. Everything below still has to survive the
        // offensive list, the blocked list and the apostrophe-less contraction
        // forms — and those are filtered *after* this, so asking for exactly
        // what is wanted meant a word could be dropped with nothing to take its
        // place and the strip would offer no correction at all. The words most
        // likely to be filtered are common ones, which is precisely what ranks
        // high here. Costs nothing: the scan and the sort behind this run over
        // every candidate regardless, and the count only decides where the list
        // is cut.
        // Touch offsets are indexed by position in the typed word, so they
        // only apply if lowercasing did not move the positions. It normally
        // cannot, but "İ" outside a Turkish locale lowercases to two characters
        // (i plus a combining dot), and a trail read one place out would argue
        // confidently for the wrong word — the one failure mode this data has.
        val fitted = if (touch != null && lower.length == typed.length) touch else null
        val scored = dict.correctionsScored(
            lower, KeyProximity.forLang(lang), limit + CORRECTION_POOL, fitted)
        // Context re-ranks the dictionary's own candidates but never invents
        // one: only words that were already valid edit-distance corrections can
        // move. The bonus is bounded below the spatial term's reach, so context
        // breaks a near-tie ("the stroe" -> store over stone) without ever
        // pulling a distant word past an obvious adjacent-key fix.
        val fromDict = if (contextRank.isEmpty()) scored.map { it.first }
        else scored
            .map { (w, s) -> w to s + contextBonus(w, contextRank) }
            .sortedByDescending { it.second }
            .map { it.first }
        // The user's own vocabulary corrects too: a typo of a name this
        // keyboard has learned now has a fix, where before only the static
        // dictionary was consulted. Appended after the dictionary's candidates
        // on purpose — a personal word must never displace an obvious fix like
        // teh -> the, but it fills in when the dictionary has nothing to say.
        val personal =
            if (!personalized) emptyList()
            else userData.correctionCandidates(lower, Dictionary.maxEditDistance(lower.length))
        return (listOfNotNull(elongated, accented) + fromDict + personal)
            .distinct()
            .asSequence()
            // Never correct one word *toward* a corpus bare form: "don" must
            // not be fixed to "dont" (an insertion away), because "dont" is not
            // a word — its apostrophe form is what the contraction path offers.
            .filter {
                !isOffensive(it, lang, locale) && !userData.isBlocked(it) &&
                    !com.rimboard.keyboard.model.Contractions.isAutoBareForm(lang, it)
            }
            .map { matchCase(typed, it, locale) }
            .take(limit)
            .toList()
    }

    /**
     * The properly accented word a bare-keys spelling stands for, or null.
     *
     * Two routes, tried in that order. The direct one is a lookup: the
     * dictionary is indexed by the accent-stripped form of every accented word,
     * so "gunaydin" finds "günaydın". That covers every language with accents
     * and every word a corpus contains.
     *
     * The second exists because in an agglutinative language the corpus cannot
     * contain the word. "kitaplarimizdan" is ordinary Turkish and appears in no
     * frequency list, so there is nothing to look it up in — the accented form
     * has to be *built*, from a stem that is known, by rules that are
     * deterministic. See [com.rimboard.keyboard.model.TurkishMorph].
     *
     * Only asked when the query carries no accents of its own, so a correctly
     * accented word is never second-guessed.
     *
     * [attested] is the first route's answer, passed in rather than fetched
     * because the caller has already had to ask: the same lookup decides
     * whether a word in the dictionary is a word at all, and doing it twice
     * meant a Unicode normalisation twice for every accented query.
     */
    private fun accentedFormFor(
        lower: String, lang: String, dict: Dictionary, attested: String?
    ): String? = attested ?: accentedBuilt(lower, lang, dict)

    /**
     * The second route on its own: an accented form *constructed* rather than
     * found.
     *
     * Kept separate because the two routes are not equally strong evidence,
     * and one place cared about the difference. A lookup means the accented
     * word is attested — the corpus contains it, and the bare spelling in front
     * of us is a bare spelling of something real. A built form means only that
     * a known stem plus deterministic rules *could* produce one, which is a
     * hypothesis, and a hypothesis should not outrank a fact.
     *
     * The fact it was outranking is morphology. "tuzcu" is ordinary Turkish —
     * "tuz" is in the dictionary 3,273 times, and the agent suffix agrees with
     * it in both vowel and consonant — but [acceptedWord] asked for an accented
     * form first, this route built one, and the word was declared a bare-key
     * spelling and corrected away. Only this route can do that: the lookup
     * route is only reached for words a corpus actually holds.
     *
     * Turkish only, since it is gated on agglutination, so no other language's
     * accent restoration is touched by where this sits.
     */
    private fun accentedBuilt(lower: String, lang: String, dict: Dictionary): String? {
        if (!com.rimboard.keyboard.model.Morphology.isAgglutinative(lang)) return null
        if (Dictionary.foldDiacritics(lower) != lower) return null
        return com.rimboard.keyboard.model.TurkishMorph.accentedInflection(
            lower,
            fold = { Dictionary.foldDiacritics(it) },
            accentedStem = { bare -> dict.accentedFormOf(bare) ?: bare.takeIf(dict::contains) }
        )
    }

    /**
     * Whether [typed] is a real word, by the same standard autocorrect uses.
     *
     * Split out for the system spell checker, which has to answer "is this
     * misspelled" as its own question rather than inferring it from an empty
     * suggestion list — [correctionCandidates] also returns nothing for words
     * it declines to *judge* (too short, contains a digit), and underlining
     * those would be wrong.
     *
     * The four ways a word can be real are deliberately the same four that stop
     * autocorrect touching it, so the keyboard and the underlines cannot
     * disagree about what counts as a word:
     *
     *  1. it is in the dictionary;
     *  2. the user has typed it enough times to have learned it;
     *  3. it peels down to a known stem through recognised suffixes, which is
     *     the only way an agglutinative language can work at all;
     *  4. it is valid in the user's other enabled language.
     *
     * The exception is a bare-key spelling of an accented word — "gunaydin",
     * "cafe". Those are *not* accepted even when the folding is unambiguous,
     * because they are exactly the case the accent restoration exists to fix.
     */
    fun acceptedWord(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null
    ): Boolean {
        val lower = typed.lowercase(locale)
        val dict = dictionary(lang, locale)
        // Asked before the dictionary, because the dictionary says yes: the
        // frequency lists are built from web text and "hellooo", "helloooo" and
        // "hellooooo" all clear the cutoff. A word the user has added by hand
        // still wins — that is an explicit statement about their own spelling.
        if (!userData.isKnown(lower) && isElongation(lower, dict)) return false
        // The user's own word list is an explicit statement and outranks
        // everything; the corpus is not, and a bare-key spelling sitting in it
        // at a few hundredths of the real word's frequency is not evidence
        // that this is the word meant. Same order as [correctionCandidates],
        // so the underlines and the keyboard cannot disagree.
        if (userData.isKnown(lower)) return true
        val attested = dict.accentedFormOf(lower)
        if (dict.contains(lower) && attested == null) return true
        // Before the morphology and the other language, because a name is a
        // name in any of them: "Yilmaz" is not a Turkish stem with a suffix on
        // it and not an English word, and both of those would be the wrong
        // question to ask about somebody's surname.
        if (com.rimboard.keyboard.model.PersonalWords.contains(contactNames, typed)) return true
        // The list the user typed by hand to say "this is a word". It outranks
        // every guess below and is the closest thing here to being told.
        if (com.rimboard.keyboard.model.PersonalWords.contains(userDictionaryWords, typed)) {
            return true
        }
        // An *attested* accented word still wins: the corpus holds it, so the
        // bare spelling in front of us is a spelling of it.
        if (attested != null) return false
        if (wellFormedWord(lower, lang, dict)) return true
        // A *built* one does not, and is asked last. See [accentedBuilt]: it
        // says an accented form could be constructed, not that anybody has
        // ever written one, and that is weaker than the word in hand being
        // well-formed Turkish over a stem the corpus knows.
        if (accentedBuilt(lower, lang, dict) != null) return false
        // The other enabled language, asked the *same* question the primary was
        // asked — not merely whether the word is in its list.
        //
        // Word-formation rules belong to a language, not to whichever slot the
        // user happens to have put it in. Turkish stacks suffixes whether it is
        // first or second; so "kitaplarımızda" was a word with Turkish selected
        // and a misspelling with English selected, though the user typed the
        // same Turkish and the same list was open in both cases. The same went
        // for German compounds, French elision and both apostrophe rules: the
        // second language got a bare `contains` and the first got five rules.
        if (altLang == null || altLocale == null) return false
        val altLower = typed.lowercase(altLocale)
        val altDict = dictionary(altLang, altLocale)
        return altDict.contains(altLower) || wellFormedWord(altLower, altLang, altDict)
    }

    /**
     * Whether the language's own word-building rules make [lower] one word.
     *
     * Two rules, one per shape of language, and neither is about the word
     * being *in* the list: Turkish stacks suffixes on a stem the list holds,
     * German joins two words the list holds. Both mean the same thing — this
     * is a word the list could not have been expected to contain.
     *
     * It is one function because two callers need the same answer.
     * [acceptedWord] uses it to decide not to underline, and [splitFor] uses
     * it to decide not to offer a space in the middle. When only the first one
     * asked, the strip spent its time offering to turn "hekimlerin" into "he
     * kimlerin" — 94 of the 168 ordinary Turkish words in
     * `fixtures/tr_unlisted.txt` that the underline had just accepted.
     */
    private fun wellFormedWord(lower: String, lang: String, dict: Dictionary): Boolean =
        com.rimboard.keyboard.model.Morphology.stemIsKnown(lang, lower) {
            dict.frequency(it) >= Dictionary.STEM_MIN_FREQ
        } ||
        // The same question of a language whose endings were counted rather
        // than written. Turkish answers above and never reaches here; every
        // other inflecting language answered "no" to everything until this
        // existed, and a word it cannot vouch for is a word autocorrect is
        // free to overwrite.
        com.rimboard.keyboard.model.Morphology.stemIsKnown(lower, suffixesFor(lang)) {
            dict.frequency(it) >= Dictionary.STEM_MIN_FREQ
        } ||
        // The same question at the other end of the word. Every clause here
        // read a word's tail until this one, because the walk was written for
        // Turkish, which has no prefixes -- so "verschuldigde" and
        // "angeschlichen" were words no rule could vouch for and autocorrect
        // was free to overwrite.
        com.rimboard.keyboard.model.Morphology.prefixedStemIsKnown(
            lower, prefixesFor(lang), suffixesFor(lang)
        ) {
            dict.frequency(it) >= Dictionary.STEM_MIN_FREQ
        } || com.rimboard.keyboard.model.Compounds.splitOf(
            lang, lower, Dictionary.STEM_MIN_FREQ
        ) { dict.frequency(it) } != null ||
        com.rimboard.keyboard.model.Elision.splitOf(
            lower, Dictionary.STEM_MIN_FREQ
        ) { dict.frequency(it) } != null ||
        // "Paris'e", "ABD'de" — a proper noun carrying its case ending across
        // an apostrophe. A fourth shape of the same question and so a fourth
        // clause here, rather than a fourth opinion somewhere else about
        // whether something is a word.
        com.rimboard.keyboard.model.Morphology.apostropheSuffixed(lang, lower) {
            dict.frequency(it) >= Dictionary.STEM_MIN_FREQ
        } ||
        // "комп'ютер" — Ukrainian, where the apostrophe is a letter and the
        // word is indivisible. A fifth shape of the same question.
        com.rimboard.keyboard.model.InnerApostrophe.isWord(lower) { dict.frequency(it) }

    /**
     * Whether [lower] is a known word with a letter held down.
     *
     * Only counts when collapsing the run lands on a word the dictionary
     * already has: "hellooo" gives "hello" and is one, while "brrr" gives "br"
     * and "brr" and, if neither is known, is left to be an ordinary unknown
     * word rather than being called a misspelling of something.
     */
    internal fun isElongation(lower: String, dict: Dictionary): Boolean =
        elongationBase(lower, dict) != null

    /**
     * The word [lower] is an elongation of, or null.
     *
     * Collapsing gives two candidates — the run cut to one letter and to two —
     * and both can be real words: "hellooo" could be "hello" or "helloo", and
     * "coool" could be "col" or "cool". Frequency decides, because the common
     * spelling is overwhelmingly the intended one and the rare one is usually
     * the same corpus noise that put the elongation in the list to begin with.
     */
    internal fun elongationBase(lower: String, dict: Dictionary): String? =
        com.rimboard.keyboard.model.Elongation.collapsed(lower)
            .filter { dict.contains(it) }
            .maxByOrNull { dict.frequency(it) }

    /**
     * Additive score bonus for a word the preceding context predicts, fading
     * with its rank. Capped well under the 3.5-per-key spatial penalty in
     * [Dictionary], so it settles ties rather than overriding the geometry of
     * what was actually typed.
     */
    private fun contextBonus(word: String, contextRank: Map<String, Int>): Double {
        val r = contextRank[word] ?: return 0.0
        return CONTEXT_CORRECTION_WEIGHT / (r + 1.0)
    }

    /**
     * Whether [candidate] is a confident enough repair of [typed] to be acted
     * on without the user choosing it.
     *
     * Two callers, and they are asking the same question about two different
     * kinds of "without choosing it": the keyboard means committing on the
     * space bar, and the spell checker means telling the platform its first
     * suggestion is the *recommended* one, which is a claim an editor may act
     * on by itself. Both were previously answered by "is there a candidate at
     * all".
     *
     * Case-folded first, because the strip carries a correction cased to match
     * what was typed and the cost model works in lower case.
     */
    fun autoCommitConfident(
        typed: String, candidate: String, lang: String, locale: Locale
    ): Boolean = dictionary(lang, locale).autoCommitConfident(
        typed.lowercase(locale), candidate.lowercase(locale), KeyProximity.forLang(lang),
        cautiousAutocorrect
    )

    /** Correction the keyboard would apply on a separator, or null. */
    /**
     * The rank map [suggestionsFor] ranks completions and corrections by.
     *
     * Extracted so [correctionFor] can be handed the same one. The two used to
     * build their answers from different evidence — this was inline here and
     * `correctionFor` simply had none — which meant the strip bolded the word
     * context preferred and the space bar committed the word it did not. The
     * bold on the strip is a promise about what the separator will do, so
     * anything that moves one has to move the other.
     *
     * mayLoad = false: this runs per keystroke on the UI thread, and
     * predictionModel parses an asset when the model is missing.
     */
    /**
     * [personalized] false answers from the bundled model alone, exactly as it
     * does for the strip.
     *
     * It was missing, so this took the default and ranked corrections by the
     * user's own n-grams in incognito. The candidates were correctly stripped
     * of their vocabulary and then put in an order their typing had chosen --
     * and the order is the answer here, because the word at the top is the one
     * the separator commits. `IncognitoContextTest` holds the case: "pkay"
     * corrects to "okay" on the corpus, and to "play" for someone who has
     * written "the play" before.
     */
    private fun contextRankFor(
        prevWord2: String, prevWord: String, lang: String, locale: Locale,
        altLang: String? = null, altLocale: Locale? = null,
        // No default. Every caller has the answer in scope, and the one that
        // silently took a default is what this comment is about.
        personalized: Boolean
    ): Map<String, Int> {
        if (prevWord.isEmpty()) return emptyMap()
        val primary = predictions(
            prevWord2, prevWord, lang, locale, CONTEXT_COMPLETION_DEPTH,
            personalized = personalized, mayLoad = false
        )
        if (altLang == null || altLocale == null) {
            return primary.withIndex().associate { (i, w) -> w.lowercase(locale) to i }
        }
        // The other enabled language gets to answer too.
        //
        // Context is worth six to nine points of keystroke savings, and someone
        // typing their second language was getting none of it: the word before
        // is in that language, the primary language's n-grams have never seen
        // it, so the map came back empty and every completion fell back to raw
        // frequency. Measured, typing the other language cost 3.7 points in
        // Turkish and 4.9 in English, and this is where nearly all of it was.
        //
        // Primary first and the other language appended after it, so a word
        // both languages predict keeps the primary's ordering — the same
        // precedence [ALT_WEIGHT] applies to the candidates themselves.
        val out = LinkedHashMap<String, Int>(primary.size * 2)
        for ((i, w) in primary.withIndex()) out[w.lowercase(locale)] = i
        var next = primary.size
        for (w in predictions(
            prevWord2, prevWord, altLang, altLocale, CONTEXT_COMPLETION_DEPTH,
            personalized = personalized, mayLoad = false
        )) {
            val k = w.lowercase(altLocale)
            if (!out.containsKey(k)) out[k] = next++
        }
        return out
    }

    /**
     * The correction the keyboard would apply on a separator, or null.
     *
     * [prevWord2], [prevWord] and [touch] exist so this answers with the same
     * evidence [suggestionsFor] used to decide what to put in bold. They
     * default to nothing, which is what the tests and any caller with no
     * context to offer get, and is what this had for all callers before.
     */
    fun correctionFor(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null,
        prevWord2: String = "",
        prevWord: String = "",
        touch: FloatArray? = null,
        personalized: Boolean = true
    ): String? {
        // An unambiguous contraction fires even though its bare form is
        // (wrongly) in the dictionary, and takes priority over any edit-
        // distance fix: "dont" is a missing apostrophe, not a mistyped word.
        contractionFor(typed, lang, locale, altLang, altLocale)
            ?.let { if (it.second) return it.first }
        val best = correctionCandidates(
            typed, lang, locale, altLang, altLocale, 1,
            contextRankFor(
                prevWord2, prevWord, lang, locale, altLang, altLocale, personalized
            ),
            touch,
            personalized
        ).firstOrNull() ?: return null
        // Offered is not the same as applied. See [Dictionary.autoCommitConfident]:
        // without this the strip's best guess was committed on the space bar
        // however far it sat from what was actually typed, which destroyed most
        // correctly-typed words the dictionary happens not to contain.
        return if (autoCommitConfident(typed, best, lang, locale)) best else null
    }

    /**
     * The contraction for [typed] and whether it is safe to auto-commit, or
     * null. Cased to match what was typed, so "Dont" -> "Don't".
     */
    /**
     * The two words a run-together typing splits into, or null.
     *
     * Deliberately not part of [correctionCandidates], and so never the
     * autocorrect target: inserting a space changes the shape of the sentence
     * rather than the spelling of a word, and doing that on the user's behalf
     * while they are still typing is a much bigger intervention than fixing
     * "helko". It goes on the strip as something to tap, and the spell checker
     * offers it under the underline. Both are choices; neither is a decision
     * made for the user.
     */
    fun splitFor(typed: String, lang: String, locale: Locale): String? {
        if (typed.length < 4 || typed.any { it.isDigit() }) return null
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase(locale)
        if (userData.isKnown(lower)) return null
        val dict = dictionary(lang, locale)
        val (a, b) = dict.splitInto(lower) ?: return null
        // A word the language's own rules build is one word, so offering a
        // space inside it is offering to misspell it — "Bananenkuchen" as
        // "Bananen Kuchen", "hekimlerin" as "he kimlerin".
        //
        // Asked here rather than at the top, so the suffix walk only runs on
        // the rare word that splitInto found a split for, and never on every
        // keystroke. Note this is *not* the same as "accepted": "alot" is in
        // the English list and accepted, and is exactly the word this feature
        // exists to split.
        if (wellFormedWord(lower, lang, dict)) return null
        if (isOffensive(a, lang, locale) || isOffensive(b, lang, locale)) return null
        if (userData.isBlocked(a) || userData.isBlocked(b)) return null
        // The pair as it is shown, so long-pressing the chip to remove it
        // actually removes it. Blocking stores what was on screen; checking only
        // the halves here made that control silently do nothing, which is worse
        // than not offering it — and blocking "a" to be rid of "a lot" would be
        // a much larger thing to ask for than the user meant.
        if (userData.isBlocked("$a $b")) return null
        return matchCase(typed, "$a $b", locale)
    }

    /**
     * The apostrophe form of a bare contraction, and whether it may be
     * committed without being asked.
     *
     * [altLang] and [altLocale] carry no default on purpose. The bare forms
     * this fires on are English words with the apostrophe missing, and several
     * of them are ordinary words in somebody else's language -- "im" is German
     * for "in the" at 2,620 per million, and "dont" is the French relative
     * pronoun at 263. This ran on the effective language alone, so a bilingual
     * user with English enabled had those turned into "I'm" and "don't"
     * whenever the boost was sitting on English, which for anybody who drops a
     * German phrase into English writing is most of the time.
     *
     * The demotion is auto to suggest, never to nothing: the chip still
     * appears and can be tapped, because somebody typing English on a German
     * keyboard still means "I'm". What stops is the space bar committing it
     * for them.
     *
     * ## The test, and why it has no constant in it
     *
     * A word being *present* in the other dictionary is not enough. These
     * lists come from subtitle corpora with English in them, so Turkish holds
     * "dont" at 39 occurrences and Spanish holds it at 41 -- and refusing on
     * that would break the case the whole feature exists for. A threshold
     * would work and would be a number somebody had to choose.
     *
     * Instead: **is the word more common there than it is here?** Both sides
     * divided by their own [Dictionary.tokenTotal] first, because the corpora
     * are different sizes and a raw count across two of them means nothing --
     * the trap `CORRECTION_TARGET_CAP` and the bilingual blend both document.
     * The comparison calibrates itself against the very artefact this object
     * exists to work around: the bare form's own English frequency is the bar,
     * so a language where the word is real clears it by two orders of
     * magnitude and one where it is corpus noise does not come close.
     *
     * Measured over all 44 auto forms against all 21 other shipped
     * dictionaries -- 924 pairs. Nine are demoted:
     *
     *     de im   2619.63 ppm vs    9.00     hr im    608.63 vs 9.00
     *     sk im    551.79        vs 9.00     pl im    546.56 vs 9.00
     *     fr dont  262.69        vs 13.07    tr im     38.47 vs 9.00
     *     id im     12.66        vs 9.00     da yall    0.11 vs 0.05
     *     ro hadnt   0.13        vs 0.09
     *
     * The first six are the bug. The last three are both sides being noise,
     * and cost a chip that has to be tapped instead of one that commits
     * itself.
     */
    fun contractionFor(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String?,
        altLocale: Locale?
    ): Pair<String, Boolean>? {
        if (typed.isEmpty() || typed.any { it.isDigit() }) return null
        val lower = typed.lowercase(locale)
        val e = com.rimboard.keyboard.model.Contractions.expand(lang, lower) ?: return null
        return matchCase(typed, e.canonical, locale) to
            (e.auto && !commonerInAlt(lower, lang, locale, altLang, altLocale))
    }

    /**
     * Whether [lower] is a bigger share of the other language than of this one.
     *
     * Shares, not counts: see [contractionFor] for why, and for the measured
     * table this rule was checked against.
     */
    private fun commonerInAlt(
        lower: String,
        lang: String,
        locale: Locale,
        altLang: String?,
        altLocale: Locale?
    ): Boolean {
        if (altLang == null || altLocale == null || altLang == lang) return false
        val here = dictionary(lang, locale)
        val there = dictionary(altLang, altLocale)
        if (here.tokenTotal <= 0L || there.tokenTotal <= 0L) return false
        val thereFreq = there.frequency(lower.lowercase(altLocale))
        if (thereFreq <= 0) return false
        return thereFreq.toDouble() / there.tokenTotal >
            here.frequency(lower).toDouble() / here.tokenTotal
    }

    fun suggestionsFor(
        composing: String,
        lang: String,
        locale: Locale,
        allowAutocorrect: Boolean,
        personalized: Boolean,
        altLang: String? = null,
        altLocale: Locale? = null,
        prevWord2: String = "",
        prevWord: String = "",
        /** Where each letter was tapped; see [com.rimboard.keyboard.model.TouchTrail]. */
        touch: FloatArray? = null
    ): SuggestionsResult {
        if (composing.isEmpty()) return SuggestionsResult(emptyList(), -1)
        val dict = dictionary(lang, locale)
        val lower = composing.lowercase(locale)

        // What the preceding word predicts should come next, as a rank map. The
        // completion ranking was previously blind to context: typing "am"
        // after "I" scored no better than after any other word, so a rarer but
        // contextually-right completion sat below a common irrelevant one. This
        // is the same signal the strip already shows once a word is committed;
        // here it reorders the completions of the word being typed.
        val contextRank =
            contextRankFor(prevWord2, prevWord, lang, locale, altLang, altLocale, personalized)

        val merged = LinkedHashMap<String, Long>() // lowercase word -> score
        if (personalized) {
            for ((w, c) in userData.userMatches(lower, 8)) {
                // Learned words earn a place: suggest only after 3+ uses.
                if (c < 3 || userData.isBlocked(w)) continue
                merged[w] = 1_000_000_000L + c * 1000L
            }
        }
        for ((w, f) in dict.byPrefix(lower, COMPLETION_FETCH)) {
            if (userData.isBlocked(w)) continue
            // "hello" must not offer "hellooo" and "helloooo" as completions;
            // they are in the corpus but they are not spellings anyone wants
            // offered. Skipped unless that is what is being typed, since
            // suppressing a completion of the very word in the field would
            // leave the strip arguing with what is already on screen.
            if (w != lower && isElongation(w, dict)) continue
            // The corpus's apostrophe-less "dont"/"youre" sit in the dictionary
            // with huge frequencies; without this they would be offered as
            // completions over the real spelling. The contraction restores the
            // apostrophe instead — see the display assembly below.
            if (com.rimboard.keyboard.model.Contractions.isAutoBareForm(lang, w)) continue
            // A completion the context predicts is multiplied up rather than
            // given a flat bump, so it stays on the same scale as the frequency
            // it is competing with — a strongly-predicted word overtakes a
            // moderately more common one without a rare word leapfrogging
            // everything.
            val score = (f * completionFactor(w, contextRank)).toLong()
            val existing = merged[w]
            if (existing == null || existing < score) merged[w] = score
        }

        // A word with an apostrophe in it cannot be completed from the word
        // list either, and for a related reason: the lists come from a corpus
        // that split at the apostrophe, so they hold the halves and never the
        // join. Typing "l'h" matched nothing at all, which is most of a French
        // sentence.
        //
        // Both halves are looked up in whichever form the list stores them, so
        // this generates nothing the dictionary cannot vouch for:
        //
        //  - **The elided article**, French and Italian: `l'`, `qu'`, `dell'`
        //    are entries, so the tail is an ordinary prefix lookup and the two
        //    are joined. Nothing is curated and nothing names a language.
        //  - **The English contraction**, which cannot work that way. `don` and
        //    `'t` are both entries and so are `don` and `'s`, and a corpus that
        //    counted the suffixes separately makes `'s` the commoner -- so
        //    generating would offer "don's" ahead of "don't". Which suffix goes
        //    with which stem is knowledge the lists do not contain, so it comes
        //    from [Contractions], which is curated and already existed for the
        //    opposite direction.
        val apos = lower.indexOfFirst { it == '\'' || it == '’' }
        if (apos > 0 && apos < lower.length) {
            val head = lower.substring(0, apos + 1)
            val tail = lower.substring(apos + 1)
            // A bare article with nothing after it -- "l'" on its own -- offers
            // nothing. Completing it would mean ranking the whole dictionary
            // behind an apostrophe, which is a different feature and a worse
            // one: the two commonest nouns in the language are not a guess
            // about what this particular sentence wants. The previous shape of
            // this said the same thing by asking byPrefix for an empty prefix
            // and breaking out of a loop that could never run, which is a
            // harder way to read it.
            if (tail.isNotEmpty() && dict.frequency(head) >= Dictionary.STEM_MIN_FREQ) {
                for ((w, f) in dict.byPrefix(tail, COMPLETION_FETCH)) {
                    val joined = head + w
                    if (userData.isBlocked(joined)) continue
                    // Below an attested completion of the same prefix, on the
                    // rare occasions there is one -- the join is grammatical
                    // rather than counted.
                    val score = (f * ELISION_PENALTY).toLong()
                    if (merged[joined] == null) merged[joined] = maxOf(1L, score)
                }
            }
            for (canonical in
                com.rimboard.keyboard.model.Contractions.completionsFor(lang, lower)) {
                if (userData.isBlocked(canonical)) continue
                val stem = canonical.substringBefore('\'')
                val f = dict.frequency(stem).toLong()
                if (f > 0 && merged[canonical] == null) {
                    merged[canonical] = maxOf(1L, (f * ELISION_PENALTY).toLong())
                }
            }
        }

        // An agglutinative language cannot be completed from a word list: the
        // form being typed is usually not in it. Generated from a stem that is,
        // and only ever forms that continue what has been typed so far — so
        // this adds candidates and can never change the word in front of the
        // user. Scored just under the corpus hits, which are attested.
        if (com.rimboard.keyboard.model.Morphology.isAgglutinative(lang)) {
            // Below the *weakest* attested completion, not below the
            // strongest. This line used to read maxOrNull, which put a
            // generated form second overall -- ahead of every corpus word but
            // one -- and so contradicted the sentence above it. The intent was
            // always the one written down: an attested form of the same stem is
            // the better guess when both fit, and a generated one is there for
            // when nothing else is.
            //
            // Measured over Turkish prose, where the dictionary already holds
            // the form 99% of the time: at maxOrNull the generated candidates
            // cost 0.6 points of keystroke savings by displacing attested ones,
            // and the cost rose with the weight. Anchoring to the floor keeps
            // all of the coverage -- an empty [merged] still falls to
            // [MORPH_BASE_SCORE], which is the case the feature exists for --
            // and stops it being paid for by every word the corpus does know.
            val stemFreq = merged.values.minOrNull() ?: MORPH_BASE_SCORE
            com.rimboard.keyboard.model.TurkishMorph
                .completionsFor(lower, 4) { dict.contains(it) }
                .forEachIndexed { i, form ->
                    if (userData.isBlocked(form) || isOffensive(form, lang, locale)) {
                        return@forEachIndexed
                    }
                    val score = (stemFreq * MORPH_PENALTY / (i + 1)).toLong()
                    if (merged[form] == null) merged[form] = maxOf(1L, score)
                }
        }

        // Nothing yet, and the word is long enough that the silence is telling:
        // the prefix itself probably has a typo in it. Exact prefix search can
        // never recover from that, so the strip stays blank for the rest of the
        // word — which is exactly when suggestions are wanted most.
        if (merged.size < FUZZY_TRIGGER && lower.length >= 3) {
            for ((w, f) in dict.byPrefixFuzzy(lower, KeyProximity.forLang(lang), 6)) {
                if (userData.isBlocked(w)) continue
                if (com.rimboard.keyboard.model.Contractions.isAutoBareForm(lang, w)) continue
                val score = (f * completionFactor(w, contextRank)).toLong()
                if (merged[w] == null) merged[w] = score
            }
        }
        val altWords = HashSet<String>()
        if (altLang != null && altLocale != null) {
            val altDict = dictionary(altLang, altLocale)
            // Secondary-language candidates rank slightly below primary ones —
            // but only once the two lists are on the same scale, which they are
            // not. See [Dictionary.tokenTotal]: English was counted from 728
            // million tokens and Turkish from 215 million, so at rank 100 the
            // English word carries a number 5.2 times larger for being no more
            // common in its own language.
            //
            // Multiplying by ALT_WEIGHT alone therefore did not put the second
            // language slightly below the first; it put it comfortably above.
            // Measured over real prose, adding English as a second language cost
            // Turkish 3.2 points of keystroke savings, while adding Turkish cost
            // English 0.4 — an asymmetry that is nothing to do with the
            // languages and everything to do with how much text each list was
            // built from.
            val scale = if (altDict.tokenTotal <= 0L) 1.0
                else dict.tokenTotal.toDouble() / altDict.tokenTotal
            for ((w, f) in altDict.byPrefix(lower, 6)) {
                if (userData.isBlocked(w)) continue
                // The context multiplier every other candidate source gets.
                // Without it the second language's words were ranked on raw
                // frequency alone however strongly the sentence predicted them,
                // so loading its n-grams changed nothing: the map was built and
                // then never consulted for the only candidates it could speak
                // about.
                val score = (f * scale * ALT_WEIGHT * completionFactor(w, contextRank)).toLong()
                val existing = merged[w]
                if (existing == null && !dict.contains(w)) altWords.add(w)
                if (existing == null || existing < score) merged[w] = score
            }
        }
        val ranked = merged.entries.sortedByDescending { it.value }
            .map { it.key }
            .toMutableList()

        // Up to two corrections, best first, promoted to the front of the strip.
        var corrs = correctionCandidates(
            composing, lang, locale, altLang, altLocale, 2, contextRank, touch, personalized)
        var crossLanguage = false
        if (corrs.isEmpty() && altLang != null && altLocale != null) {
            // The current language has nothing to offer for this word. Before
            // giving up, ask the user's other enabled language: typing English
            // on the Turkish layout, "helko" should still put "hello" on the
            // strip. Display only — the chip is there to tap, but a guess from
            // the other language is never bold enough to commit on space.
            corrs = correctionCandidates(
                composing, altLang, altLocale, lang, locale, 1,
                personalized = personalized
            )
            crossLanguage = true
        }
        val corrLocale = if (crossLanguage) altLocale ?: locale else locale
        for (c in corrs.asReversed()) {
            val cl = c.lowercase(corrLocale)
            ranked.remove(cl)
            ranked.add(0, cl)
            // Cased with its own language's rules below (Turkish dotted i).
            if (crossLanguage) altWords.add(cl)
        }
        // A contraction sits ahead of an ordinary correction: "dont" is a
        // missing apostrophe, and if it is auto-eligible it is what commits on
        // space. Suggest-only contractions still take the front chip but never
        // become the autocorrect target.
        val contraction = contractionFor(composing, lang, locale, altLang, altLocale)
        val contractionWord = contraction?.first?.takeIf { it != composing }
        val correction = when {
            contraction != null && contraction.second -> contractionWord
            crossLanguage -> null
            // Same gate as [correctionFor], and it has to be applied here too:
            // this is the other route to committing on space, and a threshold
            // enforced on one of two paths is not a threshold.
            else -> corrs.firstOrNull()
                ?.takeIf { autoCommitConfident(composing, it, lang, locale) }
        }

        val display = mutableListOf(composing) // slot 0: verbatim
        if (contractionWord != null) display.add(contractionWord)
        // The accented spelling of what was typed, offered and never taken
        // automatically -- the same bargain the contraction above makes, for
        // the same reason.
        //
        // [Dictionary.accentedFormOf] can already reject a bare spelling
        // outright, and where it fires the accented form arrives as an ordinary
        // correction and this adds nothing. But it is held to a ratio of fifty,
        // and the languages whose speakers habitually drop accents never reach
        // it -- the habit is what puts the bare form in the corpus. So Spanish
        // "aqui" was offered "aqui, aquiles, aquino" and had no route to
        // "aquí" by any tap, while Turkish "icin" was corrected to "için"
        // outright. The feature worked in inverse proportion to how much a
        // language needed it.
        //
        // Asked here at a tenth of that ratio, because the cost of being wrong
        // is one chip rather than a word nobody meant. "sto" is Croatian for a
        // hundred and still commits as "sto"; it is merely offered "što" too.
        val accented = dict.accentedSuggestionFor(lower)
            ?.takeIf { !userData.isBlocked(it) && !isOffensive(it, lang, locale) }
            ?.let { matchCase(composing, it, locale) }
        if (accented != null && accented != composing && !display.contains(accented)) {
            display.add(accented)
        }
        // A missing space, offered but never taken automatically: "alot" almost
        // certainly wanted "a lot", but adding a word boundary on somebody's
        // behalf is not a thing to do without a tap.
        //
        // It fills a chip nobody else wanted rather than claiming one, which is
        // the same argument as the reserved continuation below and the same
        // fault it fixes. Mid-word, a prefix of a long word splits into two
        // short real words alarmingly often -- "airpo" into "air po", "aujo"
        // into "au jo", "kita" into "ki ta" -- and offering that as the *first*
        // chip while somebody is plainly still typing "airport" is noise in the
        // most prominent slot on the strip.
        //
        // A word that really is two words run together has no continuations to
        // lose to: nothing in the dictionary follows "alot", so the split still
        // gets its chip in exactly the case it exists for.
        val split = if (contractionWord == null) splitFor(composing, lang, locale) else null
        for (w in ranked) {
            // Case foreign words with their own locale (Turkish dotted I, etc.)
            val caseLocale = if (w in altWords && altLocale != null) altLocale else locale
            val cased = matchCase(composing, w, caseLocale)
            if (cased != composing && !display.contains(cased)) display.add(cased)
            if (display.size >= 3) break
        }

        if (split != null && display.size < 3 && !display.contains(split)) display.add(split)

        // One of the two free slots is kept for finishing the word.
        //
        // Everything above ranks repairs of what was typed ahead of
        // continuations of it, and mid-word that is the wrong way round: a
        // prefix is not a misspelling, it is an unfinished word. Typing
        // "airport", the strip at "airp" offered "air" and "airs" -- two ways
        // of deleting what had just been typed -- while "airport", the
        // commonest completion in the dictionary by a distance, did not appear
        // at all. "abro" offered "a bro", proposing a space in the middle of a
        // word still being written.
        //
        // Measured over real prose: the top dictionary completion was crowded
        // off the strip in 3% of English probes and 20% of Turkish ones, which
        // is 20% of the time the keyboard was in the best position it will ever
        // be in to save the user the rest of the word.
        //
        // The rule is only that a continuation gets *one* slot, never that
        // repairs lose theirs. The best repair keeps its place, so a typo
        // already in the prefix is still offered a fix -- which is the case
        // `byPrefixFuzzy` exists for and must not be undone here. It fires only
        // when a continuation exists at all, so a finished word that is simply
        // wrong ("helko", "alot") is unaffected: nothing in the dictionary
        // continues those, and the slots stay with the repairs.
        if (display.size >= 3) {
            val continues = { w: String ->
                val l = w.lowercase(locale)
                l.length > lower.length && l.startsWith(lower)
            }
            if (display.drop(1).none(continues)) {
                // First in [ranked], which is score order with corrections
                // dragged to the front -- so a candidate that both repairs and
                // continues the prefix is taken ahead of a better-scoring pure
                // continuation. Tried the other way and it measured 0.1 points
                // worse on Turkish and identical on English: a word carrying
                // both kinds of evidence is not the wrong answer, and the
                // extra ordering to keep was not paying for itself.
                ranked.firstOrNull(continues)?.let { best ->
                    val caseLocale =
                        if (best in altWords && altLocale != null) altLocale else locale
                    val cased = matchCase(composing, best, caseLocale)
                    // Never displace what the space bar is going to commit:
                    // a chip that is not on the strip cannot be the one the
                    // separator silently applies.
                    val victim = display.indices.reversed()
                        .firstOrNull { it > 0 && display[it] != correction }
                    if (!display.contains(cased) && victim != null) display[victim] = cased
                }
            }
        }

        // A run-together typing suppresses autocorrect entirely.
        //
        // "alot" is edit-distance 1 from "lot", so without this the keyboard
        // silently deleted a word on the space bar — the correction was not
        // merely worse than "a lot", it destroyed information. Whenever two
        // explanations this different both fit, neither is confident enough to
        // apply without a tap, and both are on the strip to choose from.
        var acIndex = -1
        if (allowAutocorrect && split == null && correction != null) {
            val idx = display.indexOf(correction)
            if (idx >= 0) acIndex = idx
        }
        var outWords: List<String> = display
        var outAc = acIndex
        if (blockOffensive) {
            val acWord = display.getOrNull(acIndex)
            // Slot 0 is the verbatim word and is never filtered: the point is to
            // stop the keyboard *offering* offensive words, not to censor one
            // the user deliberately typed and can already see in the field.
            outWords = listOf(display.first()) +
                display.drop(1).filter { !isOffensive(it, lang, locale) }
            outAc = if (acWord != null && !isOffensive(acWord, lang, locale))
                outWords.indexOf(acWord) else -1
        }
        return SuggestionsResult(outWords, outAc)
    }

    /**
     * Ranked words for a swiped path, best first, lowercase.
     *
     * Three sources of evidence, in the order they are allowed to matter:
     *
     *  1. **The shape**, from [Dictionary.glideScored] -- how closely the
     *     finger tracked the letters of each candidate, traded against how
     *     common the word is.
     *  2. **The user's own vocabulary**, scored by the same shape model and
     *     placed on the same frequency scale — see [PERSONAL_GLIDE_LN_FREQ],
     *     which is where the two scales are reconciled. A learned word competes
     *     on the shape of the swipe like any other; what it gets is a better
     *     starting position on the axis it would otherwise always lose.
     *  3. **What the preceding words predict**, exactly as tapping already
     *     uses it, and capped the same way so it settles ties without
     *     overruling geometry.
     *
     * That third source is new to gliding and was the odder gap of the two:
     * the n-grams were being consulted for every tapped word and for no swiped
     * one, so the keyboard grew less sure of itself the moment you swiped.
     *
     * All three read [altLang] too, which they did not until it was measured.
     * A swipe is a shape over the keys the layout draws, and nothing about that
     * shape belongs to one language — but the decoder only ever asked the
     * primary dictionary what the shape might be, so a bilingual user could tap
     * a word in their other language and not swipe it. Over the same generated
     * corpus the tapped strip already uses, English primary and Turkish second,
     * swiping Turkish words offered the right one **5% of the time** where
     * Turkish alone offers 100%.
     *
     * The second language is discounted here exactly as it is in the strip, and
     * for the same reason has to be put on the primary's frequency scale first.
     */
    fun glideFor(
        path: GlidePath,
        lang: String,
        locale: Locale,
        personalized: Boolean,
        prevWord2: String = "",
        prevWord: String = "",
        altLang: String? = null,
        altLocale: Locale? = null
    ): List<String> {
        val dict = dictionary(lang, locale)
        val merged = LinkedHashMap<String, Double>()
        for ((w, score) in dict.glideScored(path, GLIDE_DEPTH)) {
            merged[w] = score
        }
        if (altLang != null && altLocale != null) {
            val altDict = dictionary(altLang, altLocale)
            // The same normalisation the tapped strip does, in log space: the
            // two lists were counted from corpora of different sizes, so the
            // discount [ALT_WEIGHT] is meant to apply has to be measured
            // against a scaled count rather than a raw one. Folded into one
            // multiplier because [Dictionary.glideScored] must apply it inside
            // the logarithm, where the shape term cannot reach it.
            val scale = if (altDict.tokenTotal <= 0L) 1.0
                else dict.tokenTotal.toDouble() / altDict.tokenTotal
            for ((w, score) in altDict.glideScored(
                path, GLIDE_DEPTH, scale * ALT_WEIGHT
            )) {
                merged[w] = maxOf(merged[w] ?: Double.NEGATIVE_INFINITY, score)
            }
        }
        if (personalized) {
            for ((w, count, fit) in userData.glideCandidates(path, GLIDE_PERSONAL_DEPTH)) {
                val score = PERSONAL_GLIDE_LN_FREQ + ln(count + 1.0) -
                    Dictionary.GLIDE_SHAPE_WEIGHT * fit
                merged[w] = maxOf(merged[w] ?: Double.NEGATIVE_INFINITY, score)
            }
        }
        if (merged.isEmpty()) return emptyList()
        val contextRank =
            contextRankFor(
                prevWord2, prevWord, lang, locale, altLang, altLocale, personalized
            )
        return merged.entries
            .sortedByDescending { it.value + contextBonus(it.key, contextRank) }
            // The same two refusals every other path applies, and applied here
            // last because a swipe has three sources -- the primary list, the
            // second language, and what the user has typed before -- and a
            // filter on one of them is a filter on one of them.
            //
            // This is the path where they matter most rather than least. A
            // completion is offered and waits to be chosen; a swipe's first
            // candidate is committed on the lift, with no keystroke in between.
            // So the one input method that puts a word into the message without
            // being asked was, until this, the one that ignored both "Block
            // offensive words" -- whose own summary reads "Never suggest or
            // autocorrect to profanity" -- and the user's own blocked list.
            .filter { !userData.isBlocked(it.key) && !isOffensive(it.key, lang, locale) }
            .take(GLIDE_OFFERED)
            .map { it.key }
    }

    /**
     * Concurrent so that [predictionsReady] can be answered without the lock.
     * The lock below is still what stops two threads parsing the same asset;
     * it is no longer also what stops a read seeing a half-resized map.
     */
    private val predictionModels =
        if (shared) sharedModels else java.util.concurrent.ConcurrentHashMap()

    /**
     * One lock per lazily-loaded map, rather than `@Synchronized` on the engine.
     *
     * Locking is still required, though for one reason rather than two now
     * that [predictionModels] is concurrent: without it, two threads that both
     * find a model missing would both parse the asset. What it no longer
     * guards is the read — see [predictionsReady], which has to be
     * answerable without waiting behind a parse, since the whole point of
     * asking is to avoid starting one.
     *
     * But `@Synchronized` put all three loaders behind *one* monitor, and that
     * monitor is held for the whole of a parse. So [warm] — whose entire purpose
     * is to keep the first keystroke off the slow path — would take the engine's
     * monitor to parse the prediction model, and the first keystroke needing an
     * emoji or the offensive list would then block on it until that parse
     * finished. Three separate locks means a load only ever waits for the *same*
     * map being loaded, which is the only case where waiting is the point.
     */
    private val predictionModelLock = Any()
    private val emojiLock = Any()
    private val offensiveLock = Any()

    private val suffixSets = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val suffixLock = Any()

    private val prefixSets = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val prefixLock = Any()

    /**
     * Endings [lang] builds words with, counted from its own dictionary by
     * `tools/derive_suffixes.py` and shipped as assets/suffixes/<lang>.txt.
     *
     * Absent for most languages and that is the answer, not an oversight: a
     * language ships one only where the trade was measured and earned. Greek
     * and Ukrainian derive nothing at all, because their endings are one and
     * two characters and the derivation will not believe those without the
     * vowel harmony that only Turkish has.
     *
     * Longest first, so the walk strips the most specific ending it can before
     * falling back on a shorter one that happens to be its tail.
     */
    private fun suffixesFor(lang: String): List<String> = synchronized(suffixLock) {
        suffixSets.getOrPut(lang) {
            try {
                val stream = assets.open("suffixes/$lang.txt") ?: return@getOrPut emptyList()
                stream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .sortedByDescending { it.length }
                        .toList()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no suffix inventory for $lang", e)
                emptyList()
            }
        }
    }

    /**
     * Prefixes [lang] builds words with, counted from its own dictionary by
     * `tools/derive_prefixes.py` and shipped as assets/prefixes/<lang>.txt.
     *
     * Six languages have one against eighteen with endings, and that gap is
     * the measurement rather than an unfinished job: a prefix inventory is
     * held to the same point of destruction prevented, and priced together
     * with the endings against the same ceiling, so a language whose endings
     * already spend the budget cannot have one however well its prefixes
     * count. See [com.rimboard.keyboard.model.Morphology.prefixedStemIsKnown].
     *
     * Longest first, for the same reason the endings are: strip the most
     * specific prefix before a shorter one it begins with -- Czech `nevy-`
     * before `ne-`.
     */
    private fun prefixesFor(lang: String): List<String> = synchronized(prefixLock) {
        prefixSets.getOrPut(lang) {
            try {
                val stream = assets.open("prefixes/$lang.txt") ?: return@getOrPut emptyList()
                stream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .sortedByDescending { it.length }
                        .toList()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no prefix inventory for $lang", e)
                emptyList()
            }
        }
    }

    /** Bundled starter next-word model for [lang] (assets/predictions/<lang>.txt). */
    private fun predictionModel(lang: String): Map<String, List<String>> =
        synchronized(predictionModelLock) {
        predictionModels.getOrPut(lang) {
            try {
                val m = HashMap<String, List<String>>()
                // One String per distinct continuation, not one per row it
                // appears in. These are the commonest words in the language
                // and they repeat enormously -- English holds 62,537
                // continuations drawn from 4,870 distinct words -- so without
                // a pool the model is mostly duplicate copies of "the".
                //
                // Measured, holding one language's model: en 5.5 MB -> 2.8,
                // tr 4.6 -> 2.6, ru 7.1 -> 4.0. The pool itself is garbage the
                // moment this returns. Keys are not pooled because they are
                // distinct by construction.
                val pool = HashMap<String, String>()
                val stream = assets.open("predictions/$lang.txt") ?: return@getOrPut emptyMap()
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val prev = line.substring(0, tab)
                            val nexts = line.substring(tab + 1).trim()
                                .split(' ').filter { it.isNotEmpty() }
                                .map { w -> pool.getOrPut(w) { w } }
                            if (prev.isNotEmpty() && nexts.isNotEmpty()) m[prev] = nexts
                        }
                    }
                }
                m
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no prediction model for $lang", e)
                emptyMap()
            }
        }
    }

    /**
     * Next-word predictions after the two-word context (prevWord2, prevWord).
     * The user's own learned n-grams come first (trigram evidence outranks
     * bigram — see [UserData.predictNext]), then the bundled starter model
     * fills any remaining slots so predictions work from the very first word.
     */
    /**
     * [personalized] false leaves the learned n-grams out entirely and answers
     * from the bundled model alone. That is what lets the strip keep working in
     * incognito without breaking the promise attached to it: incognito says
     * nothing is learned or suggested *from history*, and a curated model that
     * shipped with the app is not history. Nothing about the user reaches the
     * strip on this path.
     */
    @JvmOverloads
    fun predictions(
        prevWord2: String, prevWord: String, lang: String, locale: Locale, limit: Int,
        personalized: Boolean = true,
        mayLoad: Boolean = true
    ): List<String> {
        // No preceding word means the start of a message or of a new sentence,
        // which is a context in its own right rather than the absence of one:
        // "hi", "thanks", "I" and "the" are all far likelier openings than they
        // are continuations. It used to return nothing here, so the strip was
        // blank until the first word had been typed in full.
        val key = if (prevWord.isEmpty()) UserData.START else prevWord.lowercase(locale)
        val key2 = prevWord2.lowercase(locale)

        // Both sources scored on one scale and then merged, rather than one
        // source winning outright. A hard cascade meant a word pair typed once
        // by accident sat in front of the curated model for that context until
        // it decayed; and where the user had strong evidence for a second and
        // third word, the curated list could not fill the remaining slots
        // alongside it.
        // Scored under a case-folded key, but shown in the form the curated
        // model spells it.
        //
        // The two sources disagree about capitals and only one of them can be
        // right. Learned n-grams are always lower case — [UserData] is fed
        // `lowercase(locale)` — so they carry no case information at all, while
        // the bundled model spells a word the way the language does. In German,
        // where every noun is capitalised, that is the difference between the
        // strip offering "vielen Dank" and offering "vielen dank"; and scoring
        // them as two separate words would have put both on the strip at once,
        // competing for the same slot.
        val scores = HashMap<String, Double>()
        val surface = HashMap<String, String>()
        if (personalized) {
            for ((w, s) in userData.predictScores(key2, key)) {
                val k = w.lowercase(locale)
                scores.merge(k, s) { a, b -> a + b }
                surface.putIfAbsent(k, w)
            }
        }
        curated(modelFor(lang, mayLoad), key2, key).forEachIndexed { i, w ->
            // Fades with rank, so the curated model's own ordering survives the
            // merge. At the top it is worth a few repeated user sightings; by
            // the end of the list it only breaks ties.
            val k = w.lowercase(locale)
            scores.merge(k, STATIC_WEIGHT / (i + 1.0)) { a, b -> a + b }
            // The curated spelling wins outright: it is the only one that knows
            // whether this language capitalises the word.
            surface[k] = w
        }
        // Blocked and offensive are both checked against the folded key, which
        // is how those lists are stored — a capitalised surface form must not
        // be a way past either of them.
        return scores.entries
            .asSequence()
            .filter { !userData.isBlocked(it.key) && !isOffensive(it.key, lang, locale) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { surface[it.key] ?: it.key }
            .toList()
    }

    /**
     * The bundled model's answer for a two-word context: what follows both
     * words, then what follows the last one, with nothing listed twice.
     *
     * The shipped rows used to be bigrams alone. [predictions] has always
     * taken two preceding words, but only the *learned* model was ever asked
     * about both -- the curated one was looked up by the last word, so the
     * second word reached nothing that shipped with the app.
     *
     * **Behind, not instead of.** A trigram row is the more specific evidence
     * and goes first, but it is built from a rarer context and so is often
     * shorter and thinner than the bigram row it sits in front of. Replacing
     * the bigram list with it measurably lost depth in Turkish -- hit rate at
     * six fell as trigram rows were added, because a sparse three-word row was
     * displacing a good two-word one. Merged, it rises instead. English does
     * not care either way, which is exactly why one language is not enough to
     * design this against.
     *
     * Measured on held-out sentences, first suggestion correct: en 16.3% ->
     * 19.6%, tr 13.7% -> 15.0%.
     */
    private fun curated(
        model: Map<String, List<String>>, key2: String, key: String
    ): List<String> {
        val bigram = model[key].orEmpty()
        if (key2.isEmpty()) return bigram
        val trigram = model["$key2 $key"] ?: return bigram
        if (bigram.isEmpty()) return trigram
        val out = ArrayList<String>(trigram.size + bigram.size)
        out.addAll(trigram)
        // Both lists are at most six long, so this stays a scan of twelve.
        for (w in bigram) if (w !in out) out.add(w)
        return out
    }

    /**
     * Kept as a name the six call sites below already use; the rule itself now
     * lives in [WordCase], because the one replacement that happens outside
     * this class -- a text shortcut, looked up by the service -- needs the
     * same answer and was giving a different one.
     */
    private fun matchCase(typed: String, candidate: String, locale: Locale): String =
        WordCase.match(typed, candidate, locale)
}
