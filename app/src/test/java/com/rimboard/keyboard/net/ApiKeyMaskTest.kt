package com.rimboard.keyboard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen is allowed to show of a credential.
 *
 * `ApiKeys` had no tests at all, which is a thin place for the one file in
 * this project that handles a secret. The redaction is the part that is pure
 * and therefore the part that can be pinned here; the storage decisions above
 * it are about which Android context is asked for a file and cannot be.
 */
class ApiKeyMaskTest {

    @Test
    fun `a real key shows its ends and hides its middle`() {
        // The shape these features actually take. A key like this is over a
        // hundred characters and the eight shown are a rounding error, while
        // being enough to tell one key from another at a glance.
        val key = "sk-ant-api03-" + "x".repeat(95) + "AA99"
        assertEquals("sk-a…AA99", ApiKeys.masked(key))
    }

    @Test
    fun `a short key is not partly published`() {
        // The defect. Nine characters used to come back as eight of them plus
        // an ellipsis: a redaction that redacted one character.
        val nine = "abcdefghi"
        val out = ApiKeys.masked(nine)!!
        assertTrue("a short key still shows its characters: $out", !out.contains("abcd"))
        assertTrue("a short key still shows its characters: $out", !out.contains("fghi"))
        assertEquals("nothing but bullets is the only safe answer here",
            0, out.count { it != '\u2022' })
    }

    @Test
    fun `nothing under the bar reveals anything`() {
        // Walked rather than sampled, so the boundary cannot be moved by one
        // without this saying so.
        for (n in 1..19) {
            val out = ApiKeys.masked("k".repeat(n))!!
            assertEquals("length $n leaked: $out", 0, out.count { it != '\u2022' })
        }
        val at20 = ApiKeys.masked("k".repeat(20))!!
        assertTrue("the bar never opens: $at20", at20.contains("\u2026"))
    }

    @Test
    fun `the bullets do not say how long the secret is`() {
        val a = ApiKeys.masked("k".repeat(3))
        val b = ApiKeys.masked("k".repeat(17))
        assertEquals("the mask is a different width for a different key", a, b)
    }

    @Test
    fun `absent is absent, not empty`() {
        // The caller distinguishes "no key set" from "a key it may not show"
        // by null, and substitutes its own copy for the first.
        assertNull(ApiKeys.masked(null))
        assertNull(ApiKeys.masked(""))
    }
}
