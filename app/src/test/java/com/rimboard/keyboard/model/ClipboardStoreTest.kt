package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardStoreTest {

    private val minute = 60_000L

    @Test
    fun `the newest clip comes first and a repeat moves rather than duplicates`() {
        val s = ClipboardStore()
        s.add("one", 0, false)
        s.add("two", 1, false)
        s.add("one", 2, false)
        assertEquals(listOf("one", "two"), s.history(2, 0).map { it.text })
    }

    @Test
    fun `the history is capped and drops the oldest`() {
        val s = ClipboardStore(cap = 3)
        for (i in 1..5) s.add("clip$i", i.toLong(), false)
        assertEquals(listOf("clip5", "clip4", "clip3"), s.history(5, 0).map { it.text })
    }

    @Test
    fun `a pinned clip is not added to the history again`() {
        val s = ClipboardStore()
        s.add("keep", 0, false)
        s.pin("keep", 1)
        assertFalse("re-copying a pinned clip duplicated it", s.add("keep", 2, false))
        assertTrue(s.history(2, 0).isEmpty())
        assertEquals(listOf("keep"), s.pinnedTexts())
    }

    @Test
    fun `pinning and unpinning keeps the do-not-preview flag`() {
        // The first fault this extraction found. Unpinning rebuilt the entry
        // from its text alone, so a password a manager had explicitly marked
        // as not-for-preview came back unmarked — and the suggestion strip
        // then showed it.
        val s = ClipboardStore()
        s.add("hunter2", 0, sensitive = true)
        s.pin("hunter2", 1)
        assertTrue("the flag was lost on pinning", s.pinnedEntries().first().sensitive)
        s.unpin("hunter2", 2)
        assertTrue(
            "the flag was lost on unpinning",
            s.history(2, 0).first().sensitive
        )
    }

    @Test
    fun `reading the history prunes expired clips`() {
        // The second fault. The panel pruned before drawing and the paste chip
        // did not, so the chip kept previewing a clip past the auto-clear time
        // the user had set. Pruning on read is what makes both obey it.
        val s = ClipboardStore()
        s.add("old", 0, false)
        s.add("fresh", 14 * minute, false)
        assertEquals(listOf("fresh"), s.history(16 * minute, 15).map { it.text })
    }

    @Test
    fun `a clip exactly at the cutoff survives`() {
        // Written down because the test above originally assumed the opposite
        // and failed on it. The comparison is strict, so a clip is dropped once
        // it is *older* than the timeout rather than as it reaches it. A
        // millisecond either way is not worth changing behaviour over, but it
        // should be a decision rather than something nobody ever looked at.
        val s = ClipboardStore()
        s.add("edge", 0, false)
        assertEquals(1, s.history(15 * minute, 15).size)
        assertEquals(0, s.history(15 * minute + 1, 15).size)
    }

    @Test
    fun `the newest clip is also pruned, not just the list`() {
        val s = ClipboardStore()
        s.add("old", 0, false)
        assertNull("an expired clip was still offered", s.latest(20 * minute, 15))
    }

    @Test
    fun `a timeout of zero never expires anything`() {
        val s = ClipboardStore()
        s.add("forever", 0, false)
        assertEquals("forever", s.latest(400 * minute, 0)?.text)
        assertEquals("forever", s.latest(400 * minute, -5)?.text)
    }

    @Test
    fun `pinned clips do not expire`() {
        // Pinning is an explicit "keep this", which outranks a timeout meant
        // for things that arrived by accident.
        val s = ClipboardStore()
        s.add("kept", 0, false)
        s.pin("kept", 0)
        s.history(400 * minute, 15)
        assertEquals(listOf("kept"), s.pinnedTexts())
    }

    @Test
    fun `clearing the history leaves the pinned clips alone`() {
        val s = ClipboardStore()
        s.add("temp", 0, false)
        s.pin("kept", 0)
        s.clearHistory()
        assertTrue(s.history(0, 0).isEmpty())
        assertEquals(listOf("kept"), s.pinnedTexts())
    }

    @Test
    fun `blank text is never stored`() {
        val s = ClipboardStore()
        assertFalse(s.add("", 0, false))
        assertFalse(s.add("   ", 0, false))
        assertTrue(s.history(0, 0).isEmpty())
    }

    @Test
    fun `unpinning something that was never pinned still works`() {
        val s = ClipboardStore()
        s.unpin("stray", 5)
        assertEquals(listOf("stray"), s.history(5, 0).map { it.text })
    }
}
