package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache that stops a misspelled word being re-judged on every keystroke.
 *
 * The property that matters is which entry leaves when it is full: a word the
 * user is still getting wrong is asked about constantly, and if a hit did not
 * keep it alive it could be evicted by the very sentence it sits in.
 */
class SpellCacheTest {

    @Test
    fun `a value comes back out`() {
        val c = SpellCache<String, Int>(4)
        c.put("teh", 1)
        assertEquals(1, c.get("teh"))
        assertNull(c.get("the"))
    }

    @Test
    fun `it never grows past its capacity`() {
        val c = SpellCache<Int, Int>(3)
        for (i in 1..100) c.put(i, i)
        assertEquals(3, c.size())
    }

    @Test
    fun `the oldest entry is the one that leaves`() {
        val c = SpellCache<String, Int>(3)
        c.put("a", 1); c.put("b", 2); c.put("c", 3)
        c.put("d", 4)
        assertNull("a was the oldest", c.get("a"))
        assertEquals(listOf("b", "c", "d"), c.keys())
    }

    @Test
    fun `reading an entry keeps it alive`() {
        // Least recently *used*, not least recently inserted. The word being
        // re-judged is the one that must never be evicted.
        val c = SpellCache<String, Int>(3)
        c.put("a", 1); c.put("b", 2); c.put("c", 3)
        c.get("a")
        c.put("d", 4)
        assertEquals(1, c.get("a"))
        assertNull("b had gone longest without a read", c.get("b"))
    }

    @Test
    fun `putting a key again refreshes rather than duplicates`() {
        val c = SpellCache<String, Int>(2)
        c.put("a", 1); c.put("b", 2); c.put("a", 9)
        assertEquals(2, c.size())
        assertEquals(9, c.get("a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cache that can hold nothing is a mistake, not a no-op`() {
        SpellCache<String, Int>(0)
    }
}
