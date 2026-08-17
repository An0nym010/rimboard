package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipChipTest {

    private val paste = "Paste"

    private fun label(
        clip: String?,
        sensitive: Boolean = false,
        password: Boolean = false
    ) = ClipChip.label(clip, sensitive, password, paste)

    @Test
    fun `the copied text is what the chip says`() {
        assertEquals("hello world", label("hello world"))
    }

    @Test
    fun `a sensitive clip is never previewed`() {
        // A password manager sets EXTRA_IS_SENSITIVE precisely so its content
        // is not put on screen. The strip sits in plain view above the keyboard
        // for as long as the field stays empty, which is the worst place to
        // ignore that.
        assertEquals(paste, label("hunter2", sensitive = true))
    }

    @Test
    fun `nothing is previewed into a password field`() {
        // The case the flag misses: the app that copied it may not have set
        // anything, but someone pasting into a password field is very likely
        // pasting a credential.
        assertEquals(paste, label("hunter2", password = true))
    }

    @Test
    fun `an empty or missing clip falls back to the label`() {
        assertEquals(paste, label(null))
        assertEquals(paste, label(""))
        assertEquals(paste, label("   "))
        assertEquals(paste, label("\n\t \n"))
    }

    @Test
    fun `a multi-line clip is flattened without running words together`() {
        // One line of display, so the newline has to become something. Dropping
        // it would render "endBegin", which is a worse preview than none.
        assertEquals("end Begin", label("end\nBegin"))
        assertEquals("a b c", label("a  \n\t b \n c"))
    }

    @Test
    fun `a long clip is cut short rather than built in full`() {
        val long = "x".repeat(500)
        val out = label(long)
        assertTrue("still 500 chars long", out.length <= ClipChip.MAX + 1)
        assertTrue("no sign it was cut", out.endsWith("…"))
    }

    @Test
    fun `a long clip is cut at twelve characters`() {
        // The chip is a notice that something was copied, not a preview of it:
        // long enough to recognise your own clipboard, short enough to leave
        // the suggestions beside it room.
        assertEquals("QWERTYUIOPAS…", label("QWERTYUIOPASDFGHJKL"))
        assertEquals("ZXCVBNMASDFG…", label("ZXCVBNMASDFGHJKL"))
    }

    @Test
    fun `a clip exactly at the limit is not marked as cut`() {
        val exact = "y".repeat(ClipChip.MAX)
        assertEquals(exact, label(exact))
    }
}
