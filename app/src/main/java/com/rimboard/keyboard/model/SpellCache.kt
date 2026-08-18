package com.rimboard.keyboard.model

/**
 * A small least-recently-used cache, sized in entries.
 *
 * Judging a misspelled word is not cheap. `Dictionary.correctionsScored` walks
 * every eligible word whose length is within the edit budget and runs an edit
 * distance against each: for a seven-letter typo in English that is tens of
 * thousands of comparisons. The spell checker pays that on a binder thread
 * with the framework waiting, once per misspelled word — and again for the
 * whole sentence every time the sentence is re-checked, which is as the user
 * keeps typing. The same wrong word is therefore judged over and over while it
 * sits there being wrong, which is exactly as long as it is on screen.
 *
 * Keyed on a value object rather than a joined string, so there is no
 * separator to pick and no way for two different questions to collide by
 * spelling the same key.
 *
 * Synchronised because sessions are called on binder threads and nothing
 * promises they are the same one twice. The lock is uncontended in practice
 * and a `LinkedHashMap` in access order is not safe to read while another
 * thread reorders it.
 */
class SpellCache<K : Any, V : Any>(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // accessOrder = true is what makes this least-*recently-used* rather than
    // least-recently-inserted: a hit moves the entry to the young end, so a
    // word being re-judged on every keystroke never ages out from under itself.
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size

    /** Keys from least to most recently used. For tests. */
    @Synchronized
    fun keys(): List<K> = map.keys.toList()
}
