package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules [PostCorrection] refuses on, one test each.
 *
 * Every case here is a refusal except two, and that ratio is the feature: this
 * is the only thing in the keyboard that rewrites text the user has already
 * read past, so the interesting question about it is never "does it fire" but
 * "does it decline". `PostCorrectionAccuracyTest` asks the other half — how
 * much it wins and what it costs — against the real dictionary and n-grams.
 *
 * The predicates are supplied, so nothing here needs an engine: `continues`
 * stands for the n-grams and `confident` for the distance bar, and each test
 * makes exactly one of them the reason.
 */
class PostCorrectionTest {

    /** Every argument at its firing value, so a test can move one thing. */
    private fun decide(
        typed: String = "hte",
        committed: String = "hte",
        separator: String = " ",
        follower: String = "cat",
        followerCorrected: Boolean = false,
        candidates: List<String> = listOf("the", "hate"),
        continues: (String) -> Boolean = { it == "the" },
        confident: (String) -> Boolean = { true }
    ): String? = PostCorrection.replacementFor(
        typed, committed, separator, follower, followerCorrected,
        candidates, continues, confident
    )

    @Test
    fun `the follower settles a correction the space bar declined`() {
        assertEquals("the", decide())
    }

    @Test
    fun `a word the keyboard already changed is left alone`() {
        // Autocorrect acted with evidence and armed a chip the user can still
        // see. Correcting a correction moves the text twice for one word.
        assertNull(decide(committed = "hate"))
    }

    @Test
    fun `a word not followed by a plain space is left alone`() {
        // The n-grams count adjacent words in running text, so a comma or a
        // full stop is a question they were not built to answer.
        for (sep in listOf(", ", ". ", "\n", "", "  ")) {
            assertNull("separator '$sep'", decide(separator = sep))
        }
    }

    @Test
    fun `nothing happens when the following word was itself corrected`() {
        // One silent change per commit: two would leave the revert chip able
        // to undo only one of them.
        assertNull(decide(followerCorrected = true))
    }

    @Test
    fun `a word the engine has no corrections for is left alone`() {
        // The candidate list is empty for every word the engine considers
        // real, which is what keeps correctly-typed words out of reach.
        assertNull(decide(candidates = emptyList()))
    }

    @Test
    fun `a word that already fits the follower is left alone`() {
        // "hte cat" has been seen before -- in the user's own typing, most
        // likely -- so the follower is not new evidence about anything.
        assertNull(decide(continues = { true }))
    }

    @Test
    fun `a candidate that does not fit the follower is not applied`() {
        assertNull(decide(continues = { false }))
    }

    @Test
    fun `a candidate that fits but sits too far away is not applied`() {
        // The distance bar refused this repair a word ago. New evidence buys a
        // wider bar, not the absence of one.
        assertNull(decide(confident = { false }))
    }

    @Test
    fun `the first candidate that fits wins, not the best fit`() {
        // Engine order is the channel model's. Taking the fittest candidate
        // instead would let one bigram promote a distant word over an
        // adjacent-key one, which is the line right context does not cross.
        assertEquals(
            "hate",
            decide(
                candidates = listOf("hat", "hate", "the"),
                continues = { it == "hate" || it == "the" }
            )
        )
    }

    @Test
    fun `a candidate identical to what was typed is not a correction`() {
        assertNull(decide(candidates = listOf("hte"), continues = { true }))
    }

    @Test
    fun `an empty word or follower is left alone`() {
        assertNull(decide(typed = "", committed = ""))
        assertNull(decide(follower = ""))
    }

    @Test
    fun `the distance bar is asked only about candidates the follower picked`() {
        // Ordering inside the implementation, and worth pinning: `confident`
        // is the expensive predicate (an edit distance over the key grid) and
        // `continues` is a map lookup. Asking the bar about candidates the
        // n-grams have already rejected would pay the cost of the whole
        // candidate list on every committed word.
        val asked = ArrayList<String>()
        decide(
            candidates = listOf("hat", "hate", "the"),
            continues = { it == "the" },
            confident = { asked.add(it); true }
        )
        assertEquals(listOf("the"), asked)
    }
}
