package com.rimboard.keyboard.engine

import android.content.Context
import com.rimboard.keyboard.model.KeyProximity
import java.util.Locale

class SuggestionsResult(
    val items: List<String>,
    val autocorrectIndex: Int   // index that would be committed on space, or -1
)

object DictVersion {
    @Volatile
    var v = 0
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
        Assets { path -> try { context.assets.open(path) } catch (_: Exception) { null } },
        UserData.dataDir(context),
        userData,
        shared = true
    )

    companion object {
        /**
         * Test seam: back the engine with in-memory data and no Context.
         *
         * [shared] defaults to false so fixtures cannot leak between cases. It
         * is opt-in for the one thing that cannot be tested without it — the
         * eviction of the process-wide cache, which by definition is not
         * per-instance. A case that passes true owes the next case a
         * [trimDictionaries] with an empty set.
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
        fun trimDictionaries(keep: Set<String>) {
            val live = keep.map { it + "#" + DictVersion.v }.toSet()
            sharedDictionaries.keys.removeAll { it !in live }
        }

        /** How many dictionaries the process is holding. Test-only. */
        internal fun cachedCount() = sharedDictionaries.size

        private const val TAG = "RimBoard"

        /** How many next-word predictions feed completion re-ranking. */
        const val CONTEXT_COMPLETION_DEPTH = 12

        /**
         * Strength of the completion boost. The top-predicted completion has
         * its frequency multiplied by 1 + this; the effect fades with rank. At
         * 6, a strongly-predicted word overtakes one up to seven times commoner
         * — enough to matter, not so much that context drowns frequency.
         */
        const val CONTEXT_COMPLETION_WEIGHT = 6.0

        /** Additive tie-break for corrections; see [contextBonus]. */
        const val CONTEXT_CORRECTION_WEIGHT = 2.0

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
                if (altLang != null && altLocale != null) dictionary(altLang, altLocale)
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
     * framework waiting {EM} it is the same stall the spell checker's warm fix
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
    fun continues(word: String, next: String, lang: String, locale: Locale): Boolean {
        if (word.isEmpty() || next.isEmpty()) return false
        val a = word.lowercase(locale)
        val b = next.lowercase(locale)
        if (userData.follows(a, b)) return true
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

    private fun listed(lower: String, lang: String): Boolean =
        lower in offensive(lang) || (lang != "en" && lower in offensive("en"))

    private val emojiMaps = HashMap<String, Map<String, String>>()

    /**
     * The emoji a word suggests, or null.
     *
     * The current language first and English behind it, so a language with no
     * list of its own still answers for the English words people mix in — and
     * so adding a list for a new language is additive rather than a
     * prerequisite.
     */
    fun emojiFor(wordLower: String, lang: String): String? =
        emojiMap(lang)[wordLower] ?: if (lang != "en") emojiMap("en")[wordLower] else null

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

    fun knownIn(wordLower: String, lang: String, locale: Locale): Boolean =
        dictionary(lang, locale).contains(wordLower)

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
        contextRank: Map<String, Int> = emptyMap()
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
        if (dict.contains(lower) && elongated == null) return emptyList()
        // Bare-letter spelling of an accented word: "cafe" -> "café",
        // "gunaydin" -> "günaydın". High confidence, because the query is not
        // itself a word and folds exactly onto a dictionary entry — so it leads
        // the list rather than competing as an edit-distance guess. It still
        // passes through the filters and case-matching below.
        val accented = accentedFormFor(lower, lang, dict)?.takeIf {
            !isOffensive(it, lang, locale) && !userData.isBlocked(it)
        }
        // Agglutinative languages build endless valid surface forms a frequency
        // dictionary cannot list. If the word peels down to a known stem
        // through recognised suffixes, it is a real word the corpus merely
        // never saw — do not "correct" it. See [Morphology]. Skipped when the
        // bare form spells an accented word, which is a correction, not a stem.
        if (accented == null &&
            com.rimboard.keyboard.model.Morphology.stemIsKnown(lang, lower) { dict.contains(it) }
        ) {
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
        val scored = dict.correctionsScored(
            lower, KeyProximity.forLang(lang), limit + CORRECTION_POOL)
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
        val personal = userData.correctionCandidates(
            lower, Dictionary.maxEditDistance(lower.length))
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
     */
    private fun accentedFormFor(lower: String, lang: String, dict: Dictionary): String? {
        dict.accentedFormOf(lower)?.let { return it }
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
        if (dict.contains(lower) || userData.isKnown(lower)) return true
        if (accentedFormFor(lower, lang, dict) != null) return false
        if (com.rimboard.keyboard.model.Morphology.stemIsKnown(lang, lower) { dict.contains(it) }) {
            return true
        }
        return altLang != null && altLocale != null &&
            dictionary(altLang, altLocale).contains(typed.lowercase(altLocale))
    }

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

    /** Correction the keyboard would apply on a separator, or null. */
    fun correctionFor(
        typed: String,
        lang: String,
        locale: Locale,
        altLang: String? = null,
        altLocale: Locale? = null
    ): String? {
        // An unambiguous contraction fires even though its bare form is
        // (wrongly) in the dictionary, and takes priority over any edit-
        // distance fix: "dont" is a missing apostrophe, not a mistyped word.
        contractionFor(typed, lang, locale)?.let { if (it.second) return it.first }
        return correctionCandidates(typed, lang, locale, altLang, altLocale, 1).firstOrNull()
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
        val (a, b) = dictionary(lang, locale).splitInto(lower) ?: return null
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

    fun contractionFor(typed: String, lang: String, locale: Locale): Pair<String, Boolean>? {
        if (typed.isEmpty() || typed.any { it.isDigit() }) return null
        val e = com.rimboard.keyboard.model.Contractions.expand(lang, typed.lowercase(locale))
            ?: return null
        return matchCase(typed, e.canonical, locale) to e.auto
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
        prevWord: String = ""
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
        val contextRank = if (prevWord.isEmpty()) emptyMap()
        // mayLoad = false: this runs per keystroke on the UI thread, and
        // predictionModel parses an asset when the model is missing.
        else predictions(
            prevWord2, prevWord, lang, locale, CONTEXT_COMPLETION_DEPTH, mayLoad = false
        )
            .withIndex().associate { (i, w) -> w.lowercase(locale) to i }

        val merged = LinkedHashMap<String, Long>() // lowercase word -> score
        if (personalized) {
            for ((w, c) in userData.userMatches(lower, 8)) {
                // Learned words earn a place: suggest only after 3+ uses.
                if (c < 3 || userData.isBlocked(w)) continue
                merged[w] = 1_000_000_000L + c * 1000L
            }
        }
        for ((w, f) in dict.byPrefix(lower, 12)) {
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

        // An agglutinative language cannot be completed from a word list: the
        // form being typed is usually not in it. Generated from a stem that is,
        // and only ever forms that continue what has been typed so far — so
        // this adds candidates and can never change the word in front of the
        // user. Scored just under the corpus hits, which are attested.
        if (com.rimboard.keyboard.model.Morphology.isAgglutinative(lang)) {
            val stemFreq = merged.values.maxOrNull() ?: MORPH_BASE_SCORE
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
            // Secondary-language candidates rank slightly below primary ones.
            for ((w, f) in dictionary(altLang, altLocale).byPrefix(lower, 6)) {
                if (userData.isBlocked(w)) continue
                val score = (f * 0.85).toLong()
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
            composing, lang, locale, altLang, altLocale, 2, contextRank)
        var crossLanguage = false
        if (corrs.isEmpty() && altLang != null && altLocale != null) {
            // The current language has nothing to offer for this word. Before
            // giving up, ask the user's other enabled language: typing English
            // on the Turkish layout, "helko" should still put "hello" on the
            // strip. Display only — the chip is there to tap, but a guess from
            // the other language is never bold enough to commit on space.
            corrs = correctionCandidates(composing, altLang, altLocale, lang, locale, 1)
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
        val contraction = contractionFor(composing, lang, locale)
        val contractionWord = contraction?.first?.takeIf { it != composing }
        val correction = when {
            contraction != null && contraction.second -> contractionWord
            crossLanguage -> null
            else -> corrs.firstOrNull()
        }

        val display = mutableListOf(composing) // slot 0: verbatim
        if (contractionWord != null) display.add(contractionWord)
        // A missing space, offered but never taken automatically. Placed after
        // any contraction and ahead of the ordinary completions: "alot" almost
        // certainly wanted "a lot", but adding a word boundary on the user's
        // behalf is not something to do without a tap.
        val split = if (contractionWord == null) splitFor(composing, lang, locale) else null
        if (split != null) display.add(split)
        for (w in ranked) {
            // Case foreign words with their own locale (Turkish dotted I, etc.)
            val caseLocale = if (w in altWords && altLocale != null) altLocale else locale
            val cased = matchCase(composing, w, caseLocale)
            if (cased != composing && !display.contains(cased)) display.add(cased)
            if (display.size >= 3) break
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

    /** Ranked word candidates for a glide key sequence (lowercase results). */
    fun glideFor(seq: String, lang: String, locale: Locale, personalized: Boolean): List<String> {
        val s = seq.lowercase(locale)
        if (s.length < 2) return emptyList()
        val merged = LinkedHashMap<String, Double>()
        for ((w, score) in dictionary(lang, locale).glideCandidates(s, 20)) {
            merged[w] = score
        }
        if (personalized) {
            val last = s.last()
            val nearLast = if (s.length >= 3) s[s.length - 2] else last
            val floor = maxOf(2, kotlin.math.ceil(s.length / 4.5).toInt())
            for ((w, c) in userData.glideCandidates(s.first(), last, nearLast, 12)) {
                val cw = collapse(w)
                if (cw.length in floor..s.length && isSubsequence(cw, s)) {
                    merged[w] = maxOf(merged[w] ?: 0.0, 1.5e9 + c * 1000.0)
                }
            }
        }
        return merged.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private fun collapse(w: String): String {
        val sb = StringBuilder(w.length)
        for (ch in w) if (sb.isEmpty() || sb[sb.length - 1] != ch) sb.append(ch)
        return sb.toString()
    }

    private fun isSubsequence(needle: String, hay: String): Boolean {
        var i = 0
        for (ch in hay) if (i < needle.length && needle[i] == ch) i++
        return i == needle.length
    }

    /**
     * Concurrent so that [predictionsReady] can be answered without the lock.
     * The lock below is still what stops two threads parsing the same asset;
     * it is no longer also what stops a read seeing a half-resized map.
     */
    private val predictionModels =
        java.util.concurrent.ConcurrentHashMap<String, Map<String, List<String>>>()

    /**
     * One lock per lazily-loaded map, rather than `@Synchronized` on the engine.
     *
     * Locking is still required, though for one reason rather than two now
     * that [predictionModels] is concurrent: without it, two threads that both
     * find a model missing would both parse the asset. What it no longer
     * guards is the read {EM} see [predictionsReady], which has to be
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

    /** Bundled starter next-word model for [lang] (assets/predictions/<lang>.txt). */
    private fun predictionModel(lang: String): Map<String, List<String>> =
        synchronized(predictionModelLock) {
        predictionModels.getOrPut(lang) {
            try {
                val m = HashMap<String, List<String>>()
                val stream = assets.open("predictions/$lang.txt") ?: return@getOrPut emptyMap()
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val prev = line.substring(0, tab)
                            val nexts = line.substring(tab + 1).trim()
                                .split(' ').filter { it.isNotEmpty() }
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
        modelFor(lang, mayLoad)[key].orEmpty().forEachIndexed { i, w ->
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

    private fun matchCase(typed: String, candidate: String, locale: Locale): String {
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase(locale)
            typed.isNotEmpty() && typed.first().isUpperCase() ->
                candidate.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
            else -> candidate
        }
    }
}
