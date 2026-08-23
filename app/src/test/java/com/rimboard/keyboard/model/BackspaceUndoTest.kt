package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The round-trip: sliding right must put back exactly what sliding left took.
 *
 * These read like a transcript of the gesture on purpose. The bug this class
 * was written for is invisible in any single step and only shows up when the
 * whole gesture is replayed.
 */
class BackspaceUndoTest {

    @Test
    fun `the character deleted on key-down is not orphaned`() {
        // The fault. Backspace repeats, so the finger going down deletes "d"
        // from "hello world" before the swipe can arm at 30dp of travel. The
        // swipe then removes "worl". Sliding back once used to return "worl"
        // and leave the "d" gone for good.
        val u = BackspaceUndo()
        u.noteDeleted("d")
        u.noteWordDeleted("worl")
        assertEquals("world", u.restore())
        assertNull("one word removed is one restore", u.restore())
    }

    @Test
    fun `deletions accumulate in the order they stood in the text`() {
        // Auto-repeat can remove several characters before the swipe arms.
        // Backspacing walks leftwards while this accumulates, so "d" then "l"
        // then "r" has to reassemble as "rld" and not "dlr".
        val u = BackspaceUndo()
        u.noteDeleted("d")
        u.noteDeleted("l")
        u.noteDeleted("r")
        u.noteWordDeleted("wo")
        assertEquals("world", u.restore())
    }

    @Test
    fun `a whole gesture round-trips word by word`() {
        val u = BackspaceUndo()
        u.noteDeleted("g")                 // key-down
        u.noteWordDeleted("swipin")        // arms, first word
        u.noteWordDeleted("keeps ")        // one step further left
        u.noteWordDeleted("this ")
        assertEquals("this ", u.restore())
        assertEquals("keeps ", u.restore())
        assertEquals("swiping", u.restore())
        assertNull(u.restore())
    }

    @Test
    fun `a new press makes the last gesture unrestorable`() {
        // Sliding right can only reach what this gesture removed; anything
        // earlier has scrolled out of the user's mental model too.
        val u = BackspaceUndo()
        u.noteDeleted("d")
        u.noteWordDeleted("worl")
        u.reset()
        assertTrue(u.isEmpty())
        assertNull(u.restore())
    }

    @Test
    fun `pending text does not survive a reset`() {
        // The subtler half of the same rule: a character deleted and then
        // abandoned must not attach itself to the next gesture's first word.
        val u = BackspaceUndo()
        u.noteDeleted("d")
        u.reset()
        u.noteWordDeleted("hello")
        assertEquals("hello", u.restore())
    }

    @Test
    fun `restoring more times than words were deleted yields nothing`() {
        // The view counts steps of travel and the service counts words it
        // actually found; the two can disagree when the text runs out. The
        // extra restores have to be inert rather than reaching further back.
        val u = BackspaceUndo()
        u.noteWordDeleted("one ")
        assertEquals("one ", u.restore())
        assertNull(u.restore())
        assertNull(u.restore())
    }

    @Test
    fun `a word delete with nothing pending is unchanged`() {
        val u = BackspaceUndo()
        u.noteWordDeleted("plain ")
        assertEquals("plain ", u.restore())
    }

    @Test
    fun `noting empty text changes nothing`() {
        val u = BackspaceUndo()
        u.noteDeleted("")
        u.noteWordDeleted("word")
        assertEquals("word", u.restore())
    }

    @Test
    fun `an empty word delete with nothing pending stores no chunk`() {
        // Otherwise a restore would commit an empty string and the user would
        // have to slide right twice to get one word back.
        val u = BackspaceUndo()
        u.noteWordDeleted("")
        assertTrue(u.isEmpty())
        assertNull(u.restore())
    }

    @Test
    fun `the cap drops the oldest chunk, never the newest`() {
        // Sliding right restores most-recent-first, so trimming the wrong end
        // would make the next slide return a word from far earlier in the text.
        val u = BackspaceUndo(cap = 3)
        u.noteWordDeleted("a ")
        u.noteWordDeleted("b ")
        u.noteWordDeleted("c ")
        u.noteWordDeleted("d ")
        assertEquals("d ", u.restore())
        assertEquals("c ", u.restore())
        assertEquals("b ", u.restore())
        assertNull("the oldest must be the one dropped", u.restore())
    }
}
