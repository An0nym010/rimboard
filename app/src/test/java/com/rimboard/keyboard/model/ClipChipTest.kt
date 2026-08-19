package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- what the chip previews -------------------------------------------

    @Test
    fun `a copied link previews as its host, not its scheme`() {
        // Twelve characters of a URL is "https://www.…", which identifies
        // nothing: every link previews identically, so the chip answers "did I
        // copy the right thing" with "you copied a link".
        assertEquals("github.com", label("https://github.com"))
        assertEquals("github.com", label("https://www.github.com"))
        assertEquals("example.com", label("http://example.com/"))
    }

    @Test
    fun `a link with a path says so`() {
        // Otherwise the host alone would make a link to a page and a link to
        // the site itself look like the same clip.
        assertEquals("github.com/…", label("https://github.com/a/b"))
        assertEquals("example.com/…", label("https://www.example.com/?q=1"))
    }

    @Test
    fun `a link inside a sentence keeps the sentence`() {
        // Here the words are what identifies the clip, and the host is the
        // part that would be cut off. Only a clip that *is* a URL is folded.
        assertEquals("look at http…", label("look at https://example.com/x"))
    }

    @Test
    fun `a very long host is still cut to the limit`() {
        val out = label("https://averyveryverylongsubdomain.example.com/path")
        assertTrue(out, out.length <= ClipChip.MAX + 1)
        assertTrue(out, out.endsWith("…"))
    }

    // ---- whether the chip is offered at all --------------------------------

    private val minute = ClipChip.WINDOW_MS

    private fun offer(
        clip: String? = "hello",
        sensitive: Boolean = false,
        copiedAt: Long = 1_000_000L,
        now: Long = 1_000_000L,
        pastedAt: Long = 0L,
        fieldEmpty: Boolean = true,
        clipboardHasText: Boolean = true,
        password: Boolean = false
    ) = ClipChip.offer(
        clip, sensitive, copiedAt, now, pastedAt,
        fieldEmpty, clipboardHasText, password, paste
    )

    @Test
    fun `something just copied is offered in an empty field`() {
        assertEquals("hello", offer())
        assertEquals("hello", offer(now = 1_000_000L + minute / 2))
    }

    @Test
    fun `the offer expires`() {
        // The whole point of the change: the chip used to be shown whenever the
        // field was empty and the clipboard held text, so something copied on
        // Monday was still being offered on Friday.
        assertNull(offer(now = 1_000_000L + minute + 1))
        assertNull(offer(now = 1_000_000L + 60 * minute))
        // The boundary itself is still inside the window.
        assertEquals("hello", offer(now = 1_000_000L + minute))
    }

    @Test
    fun `a copy of unknown age is not offered`() {
        // A zero stamp means nothing could say when this was copied, and an
        // offer with no expiry is exactly the behaviour being replaced. The
        // one path where the clock is missing is the one nobody would test, so
        // it refuses rather than falls back to forever.
        assertNull(offer(copiedAt = 0L))
        assertNull(offer(copiedAt = -1L))
    }

    @Test
    fun `a clip stamped in the future is not offered`() {
        // A clock that moved backwards, not a copy that has not happened yet.
        // Left unguarded it would park the chip on the strip until the clock
        // caught up.
        assertNull(offer(now = 999_000L))
    }

    @Test
    fun `a clip that was already pasted is not offered again`() {
        // Without this the chip returns the moment the field is cleared,
        // offering to paste the thing that is already in the message.
        assertNull(offer(pastedAt = 1_000_000L))
    }

    @Test
    fun `copying the same text again brings the offer back`() {
        // A fresh copy carries a fresh timestamp, so retiring one clip never
        // retires the text.
        assertEquals("hello", offer(copiedAt = 1_050_000L, now = 1_050_000L, pastedAt = 1_000_000L))
    }

    @Test
    fun `nothing is offered when there is somewhere for it to go wrong`() {
        assertNull("field has text in it", offer(fieldEmpty = false))
        assertNull("clipboard holds no text", offer(clipboardHasText = false))
    }

    @Test
    fun `an unidentified clip is still offered, just not previewed`() {
        // The caller could not say what is on the clipboard — a copy made
        // while incognito was on is never recorded — which is not the same as
        // the clipboard being empty. The chip is still worth offering; it goes
        // back to naming the action.
        assertEquals(paste, offer(clip = null))
    }

    @Test
    fun `an offered clip still obeys the preview rules`() {
        assertEquals(paste, offer(sensitive = true))
        assertEquals(paste, offer(password = true))
    }
}
