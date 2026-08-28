package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What is allowed into the clipboard history, and what is only a handle.
 *
 * `coerceToText` is a lossy question asked of a clipboard that holds more than
 * text. For anything it cannot read it answers with the URI's own string, and
 * that answer is indistinguishable from a real clip until you look at where it
 * came from -- so an image copied out of a gallery became a paste chip reading
 * `content://media/external/images/media/40213`, and tapping it typed that into
 * the message.
 */
class ClipCaptureTest {

    @Test
    fun `ordinary text is kept`() {
        assertTrue(ClipboardStore.isText("see you at six", null))
        assertTrue(ClipboardStore.isText("multi\nline\ntext", null))
    }

    @Test
    fun `a content handle is not text`() {
        val u = "content://media/external/images/media/40213"
        assertFalse(ClipboardStore.isText(u, u))
    }

    @Test
    fun `nor is a file handle`() {
        val u = "file:///storage/emulated/0/Download/cat.gif"
        assertFalse(ClipboardStore.isText(u, u))
    }

    @Test
    fun `the GIF this keyboard inserts is not offered back as words`() {
        // The fallback path in GifInsert puts a content URI on the clipboard
        // whenever the field will not take commitContent, which is most
        // fields. The next focus change used to capture it.
        val u = "content://com.rimboard.keyboard.online.gifs/gif_1756312800000.gif"
        assertFalse(ClipboardStore.isText(u, u))
    }

    @Test
    fun `a link is text and stays text`() {
        // "Copy link" produces a bare uri-list clip, which a MIME-type test
        // would have thrown away with the images. A URL is words: people paste
        // them constantly and they mean the same thing off the device.
        val u = "https://example.org/a/b?c=d"
        assertTrue(ClipboardStore.isText(u, u))
        assertTrue(ClipboardStore.isText("mailto:someone@example.org", "mailto:someone@example.org"))
    }

    @Test
    fun `a provider that really hands over text is text`() {
        // Coercion returned something other than the handle, which means it
        // read the thing. Whatever the scheme was, there is text here.
        assertTrue(
            ClipboardStore.isText("the contents of the note", "content://notes/7")
        )
    }

    @Test
    fun `the scheme is matched however it is written`() {
        assertFalse(ClipboardStore.isText("CONTENT://x/y", "CONTENT://x/y"))
    }

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun src(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `the capture asks before it stores`() {
        val svc = src().resolve("com/rimboard/keyboard/RimBoardService.kt").readText()
        val fn = svc.substring(svc.indexOf("private fun captureClip()"))
            .substringBefore("clips.add(")
        assertTrue(
            "captureClip stores whatever coerceToText returned, so an image is " +
                "a paste chip again",
            fn.contains("ClipboardStore.isText(")
        )
        assertTrue(
            "the rule is asked without the clip's URI, so it can never say no",
            fn.contains("item.uri")
        )
    }
}
