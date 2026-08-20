package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asynchronous half of inline autofill, which is the half that can be wrong.
 *
 * Each suggestion inflates on its own callback, so they come back out of order,
 * some do not come back, and any of them can arrive after the user has moved on
 * to a different field. The last of those is the one that matters: a stale
 * arrival would put one form's credentials on top of another form.
 */
class InlineAutofillTest {

    @Test
    fun `views come back in the order the autofill service ranked them`() {
        // Inflation order is not response order. Filling the slots backwards is
        // exactly what the platform is free to do.
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 3)
        b.accept(2, "third")
        b.accept(0, "first")
        b.accept(1, "second")
        assertEquals(listOf("first", "second", "third"), b.views())
    }

    @Test
    fun `a batch is only complete once every slot has answered`() {
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 2)
        assertFalse(b.complete)
        b.accept(0, "a")
        assertFalse("one of two is not complete", b.complete)
        b.accept(1, "b")
        assertTrue(b.complete)
    }

    @Test
    fun `a suggestion that fails to inflate costs its own place and no more`() {
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 3)
        b.accept(0, "a")
        b.accept(1, null)
        b.accept(2, "c")
        assertTrue("a failure still answers its slot", b.complete)
        assertEquals(listOf("a", "c"), b.views())
    }

    @Test
    fun `answering the same slot twice does not fake completion`() {
        // The platform is not obliged to call back exactly once per suggestion.
        // Counting a repeat would declare the batch done while a real slot was
        // still outstanding, and the row would be drawn a chip short.
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 2)
        b.accept(0, "a")
        b.accept(0, "a again")
        assertFalse("slot 1 has still not answered", b.complete)
        assertEquals(listOf("a"), b.views())
        b.accept(1, "b")
        assertTrue(b.complete)
        assertEquals(listOf("a", "b"), b.views())
    }

    @Test
    fun `an index outside the batch is ignored rather than crashing`() {
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 1)
        b.accept(5, "nowhere")
        b.accept(-1, "nowhere")
        assertFalse(b.complete)
        assertEquals(emptyList<String>(), b.views())
    }

    @Test
    fun `an empty response is complete immediately and shows nothing`() {
        val b = InlineAutofill.Batch<String>(generation = 1, expected = 0)
        assertTrue(b.complete)
        assertEquals(emptyList<String>(), b.views())
    }

    @Test
    fun `the generation is carried so a late arrival can be recognised`() {
        // The batch does not decide staleness -- the service compares this
        // against the field it is currently on -- but it has to carry the
        // number, because the callback that arrives late has nothing else on it
        // to say which field it was for.
        val old = InlineAutofill.Batch<String>(generation = 7, expected = 1)
        val now = InlineAutofill.Batch<String>(generation = 8, expected = 1)
        assertEquals(7, old.generation)
        assertEquals(8, now.generation)
    }
}
