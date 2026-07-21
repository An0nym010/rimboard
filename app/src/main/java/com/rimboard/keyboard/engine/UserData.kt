package com.rimboard.keyboard.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Learned words and next-word n-grams. Stored only in app-private storage
 * (filesDir), never backed up, never transmitted (the app has no internet
 * permission). Learning is skipped entirely in incognito contexts.
 */
class UserData(context: Context) {

    companion object {
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

    private val learnedFile = File(dataDir(context), "learned.txt")
    private val blockedFile = File(dataDir(context), "blocked.txt")
    private val bigramFile = File(dataDir(context), "bigrams.txt")
    private val trigramFile = File(dataDir(context), "trigrams.txt")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val learned = ConcurrentHashMap<String, Int>()
    private val blocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
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

    fun isKnown(word: String): Boolean = (learned[word] ?: 0) >= 2

    fun isBlocked(word: String): Boolean = blocked.contains(word)

    /** Hide a word from all suggestions; also forgets it if learned. */
    fun blockWord(word: String) {
        val w = word.lowercase()
        blocked.add(w)
        learned.remove(w)
        io.execute {
            flushLearned()
            flushBlocked()
        }
    }

    fun learnedEntries(): List<Pair<String, Int>> =
        learned.entries.sortedByDescending { it.value }.map { it.key to it.value }

    fun removeLearned(word: String) {
        if (learned.remove(word.lowercase()) != null) io.execute { flushLearned() }
    }

    /** Explicitly added words start at the suggestion threshold. */
    fun addUserWord(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return
        blocked.remove(w)
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
            learnedFile.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun flushBlocked() {
        try {
            blockedFile.writeText(blocked.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    fun recordBigram(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        bigrams.getOrPut(prev) { ConcurrentHashMap() }.merge(next, 1) { a, b -> a + b }
        dirty = true
    }

    /** Records both the bigram prev1->next and (when prev2 is known) the trigram. */
    fun recordNgram(prev2: String, prev1: String, next: String) {
        recordBigram(prev1, next)
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
    fun predictNext(prev2: String, prev1: String, limit: Int): List<String> {
        val scores = HashMap<String, Int>()
        bigrams[prev1]?.forEach { (w, c) ->
            if (!blocked.contains(w)) scores.merge(w, c) { a, b -> a + b }
        }
        if (prev2.isNotEmpty()) {
            trigrams["$prev2 $prev1"]?.forEach { (w, c) ->
                if (!blocked.contains(w)) scores.merge(w, c * 4) { a, b -> a + b }
            }
        }
        if (scores.isEmpty()) return emptyList()
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun glideCandidates(first: Char, last: Char, nearLast: Char, limit: Int): List<Pair<String, Int>> =
        learned.entries.asSequence()
            .filter {
                val k = it.key
                k.length >= 2 && k[0] == first &&
                    (k[k.length - 1] == last || k[k.length - 1] == nearLast)
            }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
            .toList()

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
                learnedFile.writeText(
                    learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }
                )
                val sb = StringBuilder()
                for ((a, m) in bigrams) for ((b, c) in m) {
                    sb.append(a).append('\t').append(b).append('\t').append(c).append('\n')
                }
                bigramFile.writeText(sb.toString())
                sb.setLength(0)
                for ((ctx, m) in trigrams) for ((b, c) in m) {
                    sb.append(ctx).append('\t').append(b).append('\t').append(c).append('\n')
                }
                trigramFile.writeText(sb.toString())
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

    private fun pruneIfNeeded() {
        if (learned.size > 8000) {
            learned.entries.filter { it.value <= 1 }.forEach { learned.remove(it.key) }
        }
        if (bigrams.size > 4000) {
            val excess = bigrams.size - 4000
            bigrams.keys.take(excess).forEach { bigrams.remove(it) }
        }
        if (trigrams.size > 6000) {
            val excess = trigrams.size - 6000
            trigrams.keys.take(excess).forEach { trigrams.remove(it) }
        }
    }

    fun clearAll() {
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
