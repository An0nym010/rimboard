package com.rimboard.keyboard.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the word being typed is prose or part of an address.
 *
 * The case this exists for: typing "docs.gogle.com/teh" into an ordinary
 * message field, where the keyboard has no idea it is inside a URL because the
 * dots and slashes were separators, committed and forgotten. Everything that
 * made the token not-prose has already left the composing buffer by the time
 * the correction is judged.
 */
class ProseContextTest {

    @Test
    fun `a plain sentence is prose`() {
        assertFalse(ProseContext.isIdentifierPrefix("i went to the "))
        assertFalse(ProseContext.isIdentifierPrefix("hello "))
        assertFalse(ProseContext.isIdentifierPrefix(""))
        assertFalse(ProseContext.isIdentifierPrefix(null))
    }

    @Test
    fun `a domain being typed is not`() {
        assertTrue(ProseContext.isIdentifierPrefix("docs.gogle.com/"))
        assertTrue(ProseContext.isIdentifierPrefix("see docs.gogle.com/"))
        assertTrue(ProseContext.isIdentifierPrefix("https://"))
    }

    @Test
    fun `an email being typed is not`() {
        assertTrue(ProseContext.isIdentifierPrefix("user@"))
        assertTrue(ProseContext.isIdentifierPrefix("write to user@gogle."))
    }

    @Test
    fun `only the token at the cursor counts`() {
        // The sentence around a link is still prose. Judging the whole of the
        // text before the cursor would switch autocorrect off for the rest of
        // the message after one URL anywhere in it.
        assertFalse(ProseContext.isIdentifierPrefix("see http://x.com and then "))
        assertFalse(ProseContext.isIdentifierPrefix("v2 was fine but "))
    }

    @Test
    fun `a digit beside a word makes it an identifier`() {
        // Version numbers and hostnames: "v2.api" is not language, and the
        // engine already declines to correct a word with a digit inside it.
        assertTrue(ProseContext.isIdentifierPrefix("v2."))
        assertTrue(ProseContext.isIdentifierPrefix("run build2"))
    }

    @Test
    fun `an apostrophe or a hyphen is still prose`() {
        // These are the two commonest corrections there are to make, and
        // refusing them would give up far more than this rule can win.
        assertFalse(ProseContext.isIdentifierPrefix("well-"))
        assertFalse(ProseContext.isIdentifierPrefix("dont'"))
        assertFalse(ProseContext.isIdentifierPrefix("i haven't "))
    }

    @Test
    fun `the separator that ends a word can itself be the evidence`() {
        assertTrue(ProseContext.separatorEndsIdentifier("@"))
        assertTrue(ProseContext.separatorEndsIdentifier("/"))
        assertTrue(ProseContext.separatorEndsIdentifier(":"))
    }

    @Test
    fun `a full stop is deliberately not evidence`() {
        // It ends sentences. Treating it as a URL signal would switch
        // autocorrect off for the last word of everything anybody writes,
        // which is a far worse trade than the case it would catch. The known
        // consequence is that the first label of a bare domain is unprotected.
        assertFalse(ProseContext.separatorEndsIdentifier("."))
        assertFalse(ProseContext.separatorEndsIdentifier(" "))
        assertFalse(ProseContext.separatorEndsIdentifier(","))
    }
}
