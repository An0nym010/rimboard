package com.rimboard.keyboard.engine

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Learned words and next-word bigrams. Stored only in app-private storage
 * (filesDir), never backed up, never transmitted (the app has no internet
 * permission). Learning is skipped entirely in incognito contexts.
 */
class UserData(context: Context) {

    private val learnedFile = File(context.filesDir, "learned.txt")
    private val bigramFile = File(context.filesDir, "bigrams.txt")
    private val io = Executors.newSingleThreadExecutor()

    private val learned = ConcurrentHashMap<String, Int>()
    private val bigrams = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    @Volatile
    private var dirty = false

    fun loadAsync() {
        io.execute { load() }
    }

    private fun load() {
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

    fun recordBigram(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        bigrams.getOrPut(prev) { ConcurrentHashMap() }.merge(next, 1) { a, b -> a + b }
        dirty = true
    }

    fun predictNext(prev: String, limit: Int): List<String> {
        val m = bigrams[prev] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
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
                learnedFile.writeText(
                    learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }
                )
                val sb = StringBuilder()
                for ((a, m) in bigrams) for ((b, c) in m) {
                    sb.append(a).append('\t').append(b).append('\t').append(c).append('\n')
                }
                bigramFile.writeText(sb.toString())
            } catch (_: Exception) {
            }
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
    }

    fun clearAll() {
        learned.clear()
        bigrams.clear()
        dirty = false
        io.execute {
            try {
                learnedFile.delete()
                bigramFile.delete()
            } catch (_: Exception) {
            }
        }
    }
}
