package com.rimboard.keyboard.spell

import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The spell checker must not hand the framework the same result object twice.
 *
 * `SuggestionsInfo` reads like an immutable value and is not one: the framework
 * calls `setCookieAndSequence` on whatever a session returns, tagging the
 * answer with the word it belongs to, and it matches answers back to words by
 * that tag. `onGetSuggestionsMultiple` walks a batch calling the session once
 * per word and tagging each result in turn.
 *
 * So a shared instance for every unjudged word — a URL, a version number, an
 * acronym — put the same object at several positions in one batch, all of them
 * carrying whichever cookie was written last. Sessions also run on binder
 * threads, so it was being mutated from more than one at a time.
 */
class SpellResultTest {

    @Test
    fun `each not-judged answer is its own object`() {
        assertNotSame(
            "the framework tags this object; sharing one lets a later word's " +
                "tag overwrite an earlier word's",
            RimSpellService.notJudged(),
            RimSpellService.notJudged()
        )
    }
}
