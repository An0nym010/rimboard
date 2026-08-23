package com.rimboard.keyboard.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rimboard.keyboard.model.GlidePath
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Learned words and next-word n-grams. Stored only in app-private storage
 * (filesDir), never backed up, never transmitted (the app has no internet
 * permission). Learning is skipped entirely in incognito contexts.
 */
class UserData private constructor(dir: File) {

    /** App path: device-protected storage, migrated once. */
    constructor(context: Context) : this(dataDir(context))

    companion object {
        /**
         * Test seam: back the store with a plain directory and no Context, so
         * the learning and prediction logic can be exercised on a temp folder.
         */
        internal fun inDir(dir: File): UserData = UserData(dir)

        /**
         * Stands in for "start of a sentence" as a bigram context.
         *
         * A control character, so it can never collide with a word. Before
         * this the first word of every message had no context at all and the
         * strip simply sat empty — at the one moment there is most to predict
         * and least typed to go on.
         */
        const val START = "\u0001"

        /** Hard caps enforced when the data is written out. */
        private const val BIGRAM_CAP = 4000
        private const val TRIGRAM_CAP = 6000

        /**
         * Contexts held before the counts are halved.
         *
         * Must stay *below* [BIGRAM_CAP] + [TRIGRAM_CAP], or decay never
         * happens: the prune on save would cut the tables back under this
         * threshold first and the halving would be unreachable code. It was
         * set to 20,000 against a hard cap of 10,000 when first written, which
         * is exactly that mistake — the decay was dead, and only the unit test
         * (which never saves) could see it run.
         */
        private const val NGRAM_CONTEXT_CAP = 6000

        /**
         * Most halvings one decay may run.
         *
         * A bound rather than a plain `while`: every count is a positive Int,
         * so halving terminates, but the loop should not be the only thing
         * standing between a pathological table and a stalled keyboard. Ten
         * passes reduce a count of a thousand to zero, which is far past any
         * count this ever holds.
         */
        private const val MAX_DECAY_PASSES = 10

        /** Exposed so a test can assert the two limits stay compatible. */
        internal fun decayThreshold(): Int = NGRAM_CONTEXT_CAP

        internal fun hardCapTotal(): Int = BIGRAM_CAP + TRIGRAM_CAP

        /**
         * Words held before the least-used are dropped.
         *
         * Sized against what it is protecting rather than against a feeling:
         * an active typing vocabulary runs to a few thousand distinct words, so
         * twelve thousand keeps everything anyone actually uses and still bounds
         * a file that is read at every cold start. The n-gram tables next to it
         * are capped at four and six thousand contexts for the same reason.
         */
        private const val LEARNED_CAP = 12000

        /** Above this, words seen only once are dropped whatever the cap says. */
        private const val LEARNED_SOFT = 8000

        internal fun learnedCap(): Int = LEARNED_CAP

        /**
         * A word seen after this exact two-word context is much stronger
         * evidence than one seen after the previous word alone — "see you"
         * predicts "soon" even where "you" alone mostly precedes "are".
         */
        private const val TRIGRAM_WEIGHT = 4.0

        /**
         * User data lives in device-protected storage (encrypted at rest,
         * available before first unlock) so the keyboard is fully functional
         * on the lock screen after a reboot. Old files are migrated once.
         */
        fun dataDir(context: Context): File {
            val dp = context.createDeviceProtectedStorageContext()
            for (n in listOf("learned.txt", "bigrams.txt", "trigrams.txt")) {
                val old = File(context.filesDir, n)
                val nw = File(dp.filesDir, n)
                if (old.exists() && !nw.exists()) {
                    try { old.copyTo(nw); old.delete() } catch (_: Exception) {}
                }
            }
            return dp.filesDir
        }
    }

    private val learnedFile = File(dir, "learned.txt")
    private val blockedFile = File(dir, "blocked.txt")
    private val pinnedFile = File(dir, "pinned.txt")
    private val bigramFile = File(dir, "bigrams.txt")
    private val trigramFile = File(dir, "trigrams.txt")
    private val io = Executors.newSingleThreadExecutor()
    // Lazy: only reload() posts to the main thread, and touching the Looper at
    // construction is what a plain JVM (the unit tests) cannot do. Building the
    // store must not depend on an Android main thread existing.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val learned = ConcurrentHashMap<String, Int>()
    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Words somebody typed into the personal dictionary screen by hand.
     *
     * [learned] is capped and evicts its least-used words, and without this
     * these would be evictable like any other: a hand-added word starts at
     * three uses and stays there, so it is among the *first* things a
     * use-ordered eviction would take. Losing it is not a trade-off, it is a
     * bug -- the entire point of that screen is that the keyboard stops
     * arguing about the word.
     *
     * Deliberately not in the backup, which matches [blocked]. A restore
     * therefore brings the word back (it is in learned.txt) without its pin,
     * and an unpinned word is exactly as evictable as it was before any of
     * this existed. Degrading to the old behaviour is a fair price for not
     * versioning a fifth file into the backup format.
     */
    private val pinned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val bigrams = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    // Two-word context -> next-word counts, keyed "prev2 prev1". Trigram hits
    // outrank plain bigrams in predictNext, so "see you" predicts "soon" even
    // if "you" alone most often precedes "are".
    private val trigrams = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    @Volatile
    private var dirty = false

    fun loadAsync() {
        io.execute { load() }
    }

    /**
     * Discard in-memory state and reload from disk (used after a backup import).
     *
     * The reload is queued, not performed: [onLoaded] is how a caller that
     * needs the data finds out it has arrived, and runs on the main thread.
     * Reading straight after this call sees the cleared maps, not the file —
     * which is what made the personal dictionary screen come up empty.
     */
    fun reload(onLoaded: (() -> Unit)? = null) {
        blocked.clear()
        pinned.clear()
        loadBlocked()
        io.execute {
            learned.clear()
            bigrams.clear()
            trigrams.clear()
            dirty = false
            load()
            onLoaded?.let { main.post(it) }
        }
    }

    private fun loadBlocked() {
        try {
            if (blockedFile.exists()) {
                blockedFile.readLines().forEach { if (it.isNotBlank()) blocked.add(it.trim()) }
            }
        } catch (_: Exception) {
        }
        try {
            // Absent on every install that predates pinning, which reads as
            // "nothing is pinned" -- the behaviour those installs already had.
            if (pinnedFile.exists()) {
                pinnedFile.readLines().forEach { if (it.isNotBlank()) pinned.add(it.trim()) }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * What the files looked like when they were last read.
     *
     * Cheap enough to ask on every text field: four stats and no open.
     */
    private fun diskStamp(): Long {
        var h = 17L
        for (f in listOf(learnedFile, blockedFile, pinnedFile, bigramFile, trigramFile)) {
            h = h * 31 + f.lastModified()
            h = h * 31 + f.length()
        }
        return h
    }

    @Volatile
    private var loadedStamp = 0L

    /**
     * Re-read the files if something has written to them since the last read.
     *
     * A no-op for the process doing the writing — its memory is already
     * ahead of its disk. It is for the *other* reader. The spell checker builds
     * its own [UserData] and held whatever was on disk when it started for as
     * long as it ran, so a word added in the personal dictionary stopped being
     * underlined in the keyboard's own strip and went on being underlined by
     * the spell checker in every other app. That is the reverse of what its
     * class comment promises.
     *
     * Not routed through the `pendingReload` preference the keyboard uses: that
     * flag is one-shot and the keyboard clears it, so a second consumer would
     * win or lose the race depending on which service saw it first. The files
     * carry their own answer and nothing can consume it.
     *
     * Fully queued, including the blocked list that [reload] reads inline,
     * because the caller here is a binder thread with no business touching a
     * file.
     */
    /**
     * Test seam: block until everything queued on the store's thread has run.
     *
     * The store answers reads from memory and does its file work on one
     * ordered executor, so anything queued behind a reload runs after it. A
     * test that asserts on a reload has no other way to know it has landed,
     * and sleeping for a guess is how a suite starts failing on a slow machine
     * for reasons that have nothing to do with the code.
     */
    internal fun awaitIdle(): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        return done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun reloadIfChanged() {
        if (diskStamp() == loadedStamp) return
        io.execute {
            blocked.clear()
            learned.clear()
            bigrams.clear()
            trigrams.clear()
            dirty = false
            load()
        }
    }

    private fun load() {
        loadBlocked()
        try {
            if (learnedFile.exists()) learnedFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 2) p[1].toIntOrNull()?.let { learned[p[0]] = it }
            }
            if (bigramFile.exists()) bigramFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) p[2].toIntOrNull()?.let {
                    bigrams.getOrPut(p[0]) { ConcurrentHashMap() }[p[1]] = it
                }
            }
            if (trigramFile.exists()) trigramFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) p[2].toIntOrNull()?.let {
                    trigrams.getOrPut(p[0]) { ConcurrentHashMap() }[p[1]] = it
                }
            }
        } catch (_: Exception) {
        }
        // Taken after the read and not before it: a stamp read first could be
        // older than the data now in memory, and the next check would re-read
        // for nothing.
        loadedStamp = diskStamp()
    }

    fun learnWord(word: String) {
        if (word.length < 2 || word.length > 24) return
        learned.merge(word, 1) { a, b -> a + b }
        dirty = true
    }

    /** Mark a word as known so it is never auto-corrected again (used on revert). */
    fun markKnown(word: String) {
        learned[word] = maxOf(learned[word] ?: 0, 2)
        dirty = true
    }

    /** How many words are held, for the growth test. */
    internal val learnedSize: Int get() = learned.size

    fun isKnown(word: String): Boolean = (learned[word] ?: 0) >= 2

    fun isBlocked(word: String): Boolean = blocked.contains(word)

    /**
     * Hide a word from all suggestions; also forgets it if learned.
     *
     * [word] is expected already lower case in its own language's rules, the
     * same contract as [learnWord], [markKnown] and [isKnown] — every key in
     * this store is written and read that way. Folding again here without a
     * locale could only ever disagree with the fold the caller already applied.
     */
    fun blockWord(word: String) {
        blocked.add(word)
        learned.remove(word)
        // A pin outliving the word it pinned is a promise nobody made. It would
        // also sit in the file for good, and quietly re-apply itself if the
        // word were ever typed again.
        pinned.remove(word)
        io.execute {
            flushLearned()
            flushBlocked()
        }
    }

    fun learnedEntries(): List<Pair<String, Int>> =
        learned.entries.sortedByDescending { it.value }.map { it.key to it.value }

    /**
     * [word] is a key as held here — what [learnedEntries] handed out.
     *
     * This is somebody deleting a word from the personal dictionary screen, so
     * it has to take the pin with it. Leaving one behind would mean the word
     * came back unevictable if it were ever typed again, having been pinned by
     * an act the user had since undone.
     */
    fun removeLearned(word: String) {
        val wasPinned = pinned.remove(word)
        if (learned.remove(word) != null || wasPinned) {
            io.execute {
                flushLearned()
                if (wasPinned) flushBlocked()
            }
        }
    }

    /**
     * Explicitly added words start at the suggestion threshold.
     *
     * [locale] is a parameter rather than an assumption because this is the one
     * entry point fed straight from a text field, and a locale-less fold here is
     * not merely a different answer but an unusable one: `"İstanbul".lowercase()`
     * yields `i` + U+0307 + `stanbul`, a key the typing path cannot produce at
     * all, and Turkish `I` folds to `ı` rather than `i`. Words added by hand were
     * therefore stored under a key nothing would ever look up — so a name added
     * precisely to stop autocorrect touching it went on being corrected.
     */
    fun addUserWord(word: String, locale: Locale) {
        val w = word.trim().lowercase(locale)
        if (w.isEmpty()) return
        blocked.remove(w)
        pinned.add(w)
        learned[w] = maxOf(learned[w] ?: 0, 3)
        io.execute {
            flushLearned()
            flushBlocked()
        }
    }

    private fun flushLearned() {
        try {
            val sb = StringBuilder()
            for ((w, c) in learned) sb.append(w).append('\t').append(c).append('\n')
            writeAtomically(learnedFile, sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun flushBlocked() {
        try {
            writeAtomically(blockedFile, blocked.joinToString("\n"))
            writeAtomically(pinnedFile, pinned.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    fun recordBigram(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        bigrams.getOrPut(prev) { ConcurrentHashMap() }.merge(next, 1) { a, b -> a + b }
        dirty = true
        maybeDecay()
    }

    /**
     * Halves every count once the model has grown past [NGRAM_CONTEXT_CAP]
     * contexts, dropping whatever falls to zero.
     *
     * Without this the tables only ever grow, and — worse — they only ever
     * remember. A phrase used constantly during one project stays top of the
     * predictions a year later, because nothing that was once counted is ever
     * counted down, and a new habit has to out-count a total accumulated over
     * the whole life of the install.
     *
     * Halving is the cheap form of exponential decay: recent evidence keeps its
     * full weight while everything older loses half of its own, so the model
     * tracks what someone is typing now rather than what they have ever typed.
     * Anything seen exactly once and not repeated since the last decay is
     * forgotten entirely, which is also what keeps a one-off typo from living
     * in the predictions forever.
     */
    private fun maybeDecay() {
        if (bigrams.size + trigrams.size <= NGRAM_CONTEXT_CAP) return
        synchronized(this) {
            if (bigrams.size + trigrams.size <= NGRAM_CONTEXT_CAP) return
            // Halved until the table is actually under the cap, rather than
            // once per call. Halving only *removes* a context whose counts were
            // all 1, and on a mature model most survivors are 2 or more —
            // precisely because the previous pass culled the singletons. So one
            // pass could leave the size unchanged, the next committed word
            // would cross the threshold again, and a full sweep of six thousand
            // contexts would run on consecutive keystrokes on the IME's main
            // thread until enough counts collapsed. Paying it once here also
            // makes the decay gradual, which is what the class comment claims.
            var passes = 0
            while (bigrams.size + trigrams.size > NGRAM_CONTEXT_CAP && passes < MAX_DECAY_PASSES) {
                passes++
                decayOnce()
            }
            dirty = true
        }
    }

    /** One halving of every count in both tables, dropping what reaches zero. */
    private fun decayOnce() {
        for (table in listOf(bigrams, trigrams)) {
            val emptied = ArrayList<String>()
            for ((ctx, counts) in table) {
                val gone = ArrayList<String>()
                for ((w, c) in counts) {
                    val half = c / 2
                    if (half <= 0) gone.add(w) else counts[w] = half
                }
                gone.forEach { counts.remove(it) }
                if (counts.isEmpty()) emptied.add(ctx)
            }
            emptied.forEach { table.remove(it) }
        }
    }

    /** Records both the bigram prev1->next and (when prev2 is known) the trigram. */
    fun recordNgram(prev2: String, prev1: String, next: String) {
        // No preceding word is itself a context — the opening of a message —
        // so it is recorded under [START] rather than dropped. This is how the
        // keyboard comes to know how *you* start a message rather than only how
        // the shipped model says people do.
        recordBigram(if (prev1.isEmpty()) START else prev1, next)
        if (prev2.isEmpty() || prev1.isEmpty() || next.isEmpty()) return
        trigrams.getOrPut("$prev2 $prev1") { ConcurrentHashMap() }
            .merge(next, 1) { a, b -> a + b }
        dirty = true
    }

    /**
     * Next-word candidates after the context (prev2, prev1), best first.
     * Trigram evidence counts 4x a bigram hit: a word seen after this exact
     * two-word context is a much stronger signal than one seen after prev1
     * alone.
     */
    fun predictNext(prev2: String, prev1: String, limit: Int): List<String> =
        predictScores(prev2, prev1).entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /**
     * The same evidence as [predictNext], but as scores rather than an order.
     *
     * The engine needs the strength, not just the ranking: it merges this with
     * a curated list, and merging two orderings can only ever be "one wins
     * outright". That is what used to happen, and it meant a single accidental
     * word pair — typed once, never again — displaced the whole hand-written
     * model for that context until it decayed away.
     */
    /**
     * Whether [next] has been seen following [prev], as a plain lookup.
     *
     * [predictScores] answers a richer question and builds a map to do it,
     * which is the right shape for ranking what to offer next and the wrong
     * one for asking a yes/no about a pair. The spell checker asks this once
     * per candidate correction, on a binder thread, so it allocates nothing.
     *
     * No blocked check, unlike [predictScores]: this is asked about a word the
     * user has actually typed, as evidence, not about a word being offered to
     * them.
     */
    fun follows(prev: String, next: String): Boolean =
        bigrams[prev]?.containsKey(next) == true

    fun predictScores(prev2: String, prev1: String): Map<String, Double> {
        val scores = HashMap<String, Double>()
        bigrams[prev1]?.forEach { (w, c) ->
            if (!blocked.contains(w)) scores.merge(w, c.toDouble()) { a, b -> a + b }
        }
        if (prev2.isNotEmpty()) {
            trigrams["$prev2 $prev1"]?.forEach { (w, c) ->
                if (!blocked.contains(w)) scores.merge(w, c * TRIGRAM_WEIGHT) { a, b -> a + b }
            }
        }
        return scores
    }

    /**
     * Learned words that fit a swiped [path], best fit first, with their use
     * counts.
     *
     * The fit is [GlidePath.costOf] and nothing else. This used to be a
     * separate rule -- first letter equal, last letter equal or one before --
     * which meant the keyboard held two opinions about what a swipe spells and
     * applied the weaker one to exactly the words the user cares most about.
     * The learned list is small enough to walk whole, so there is no reason for
     * a cheaper approximation to exist here.
     */
    fun glideCandidates(path: GlidePath, limit: Int): List<Triple<String, Int, Double>> {
        val startKeys = path.startKeys
        val endKeys = path.endKeys
        return learned.entries.asSequence()
            .filter {
                val w = it.key
                // The same gate the dictionary scan applies, and it has to be
                // the same one. Fit alone does not exclude anything -- every
                // word made of letters this layout draws has *some* finite
                // distance from *some* path -- so without this a learned word
                // could be offered for a swipe it has nothing to do with. It
                // was: teaching the keyboard "wolfram" put it on the strip
                // after swiping "helo".
                w.length >= 2 && startKeys.contains(w[0]) && endKeys.contains(w[w.length - 1])
            }
            .map { Triple(it.key, it.value, path.costOf(it.key)) }
            .filter { !it.third.isInfinite() }
            .sortedBy { it.third }
            .take(limit)
            .toList()
    }

    /**
     * Learned words within [maxDist] edits of [typedLower] — the user's own
     * vocabulary offered as typo corrections, so a name or word this keyboard
     * has learned can fix a typo of itself the way a dictionary word can.
     * Only words past the suggestion bar (3+ uses) qualify; nearest first,
     * then most used. A straight walk of the map with a cheap length gate in
     * front of the distance computation — [userMatches] already walks the same
     * map on the same keystrokes.
     */
    fun correctionCandidates(typedLower: String, maxDist: Int): List<String> {
        if (typedLower.isEmpty()) return emptyList()
        val n = typedLower.length
        var hits: ArrayList<Triple<String, Int, Int>>? = null
        for ((w, c) in learned) {
            if (c < 3 || w == typedLower) continue
            if (kotlin.math.abs(w.length - n) > maxDist) continue
            val d = Dictionary.editDistance(typedLower, w, maxDist)
            if (d in 1..maxDist) {
                (hits ?: ArrayList<Triple<String, Int, Int>>(4).also { hits = it })
                    .add(Triple(w, d, c))
            }
        }
        val found = hits ?: return emptyList()
        found.sortWith(compareBy({ it.second }, { -it.third }))
        return found.map { it.first }
    }

    fun userMatches(prefixLower: String, limit: Int): List<Pair<String, Int>> {
        if (prefixLower.isEmpty()) return emptyList()
        return learned.entries.asSequence()
            .filter { it.key.startsWith(prefixLower) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
            .toList()
    }

    fun saveIfDirty() {
        if (!dirty) return
        dirty = false
        io.execute {
            try {
                pruneIfNeeded()
                writeAtomically(
                    learnedFile,
                    learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }
                )
                val sb = StringBuilder()
                for ((a, m) in bigrams) for ((b, c) in m) {
                    sb.append(a).append('\t').append(b).append('\t').append(c).append('\n')
                }
                writeAtomically(bigramFile, sb.toString())
                sb.setLength(0)
                for ((ctx, m) in trigrams) for ((b, c) in m) {
                    sb.append(ctx).append('\t').append(b).append('\t').append(c).append('\n')
                }
                writeAtomically(trigramFile, sb.toString())
            } catch (e: Exception) {
                // Put the flag back: it was cleared optimistically before the
                // write, so a failure here would otherwise mean this data is
                // never attempted again.
                dirty = true
                android.util.Log.w("RimBoard", "saving learned data failed", e)
            }
        }
    }

    /**
     * Stops the writer thread, letting already-queued writes finish first.
     *
     * Every UserData owns a single-threaded executor whose thread never exits
     * on its own, so anything that constructs one for a screen or a one-off
     * call leaks a thread for the life of the process unless it calls this.
     */
    fun shutdown() {
        io.shutdown()
        try {
            io.awaitTermination(1500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Saves and waits for the write to reach disk.
     *
     * [saveIfDirty] only queues the write. onDestroy called it and returned
     * immediately, so the process could be killed before the executor thread
     * ran — losing learned words, bigrams and trigrams, which is everything the
     * keyboard knows about how this person types.
     *
     * The executor is single-threaded, so a task queued after the write runs
     * after it; waiting on that is enough, and avoids shutting the executor
     * down while callers may still submit to it.
     */
    fun flushBlocking(timeoutMs: Long = 1500) {
        saveIfDirty()
        val done = CountDownLatch(1)
        try {
            io.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
    }

    /**
     * Writes [text] to [f] through a temporary file and a rename.
     *
     * `writeText` truncates and then writes, so a reader arriving in between
     * sees an empty or half-written file, and a process death mid-write leaves
     * one on disk. That window matters here because there is more than one
     * writer: the personal-dictionary screen builds its own [UserData] over the
     * same files while the keyboard service holds another, and a flush from the
     * service landing between the screen's write and the service's reload
     * silently reverted the user's edit. A rename is atomic on the same
     * filesystem, so a reader sees either the old file or the new one.
     */
    private fun writeAtomically(f: java.io.File, text: String) {
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            // Rename can fail where the target exists on some filesystems.
            f.delete()
            if (!tmp.renameTo(f)) {
                // Last resort: the non-atomic path, which is still better than
                // losing the data entirely.
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    /**
     * Brings every table back under its cap, weakest first.
     *
     * The word table had no cap at all until this was measured: forty thousand
     * distinct words typed twice each were all still held, because the only
     * rule dropped words seen *once* and a word seen twice is not one. That is
     * a file read at startup and a map held for the life of the process, both
     * growing for as long as the keyboard is used -- the kind of fault that
     * surfaces as "it got slow after a year" on somebody else's phone.
     *
     * Once-only words still go first and go on their own terms, before the cap
     * is consulted: a word typed once is a typo or a proper noun passing
     * through, and dropping it is right even when there is room.
     */
    private fun pruneIfNeeded() {
        if (learned.size > LEARNED_SOFT) {
            learned.entries.filter { it.value <= 1 }.forEach { learned.remove(it.key) }
        }
        if (learned.size > LEARNED_CAP) {
            learned.entries
                .filter { it.key !in pinned }
                .sortedBy { it.value }
                .take(learned.size - LEARNED_CAP)
                .forEach { learned.remove(it.key) }
        }
        evictWeakest(bigrams, BIGRAM_CAP)
        evictWeakest(trigrams, TRIGRAM_CAP)
    }

    /**
     * Drops the least-used contexts until [cap] is met.
     *
     * This used to be `keys.take(excess)` — whichever contexts a hash map
     * happened to iterate first, which is arbitrary and has nothing to do with
     * usefulness. It could evict the phrase someone types every day and keep
     * one they typed once by accident, and being over the cap is exactly when
     * that matters most. Ordering by total evidence makes the loss the least
     * valuable data rather than an unlucky one.
     */
    private fun evictWeakest(
        table: ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>,
        cap: Int
    ) {
        if (table.size <= cap) return
        table.entries
            .sortedBy { e -> e.value.values.sum() }
            .take(table.size - cap)
            .forEach { table.remove(it.key) }
    }

    fun clearAll() {
        pinned.clear()
        learned.clear()
        bigrams.clear()
        trigrams.clear()
        dirty = false
        io.execute {
            try {
                learnedFile.delete()
                bigramFile.delete()
                trigramFile.delete()
            } catch (_: Exception) {
            }
        }
    }
}
