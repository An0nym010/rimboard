package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.PersonalCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a run of commits does to the capitals [UserData] holds, and what
 * happens to them when the word they belong to goes away.
 *
 * [PersonalCase] owns the rule and is tested as a rule; this is the store
 * keeping the two fields that rule reads, across a save, a reload, and every
 * path that can drop a learned word. The invariant worth naming is that the
 * case table is a strict subset of the word table: a capitalisation that
 * outlived its word would come back the moment the word was typed again,
 * carrying a spelling from before the user deleted it.
 */
class PersonalCaseStoreTest {

    private lateinit var dir: File
    private lateinit var data: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-case", "").let { it.delete(); it.mkdirs(); it }
        data = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        data.shutdown()
        dir.deleteRecursively()
    }

    /** One commit of [word] exactly as the service makes it: learn, then vote. */
    private fun type(word: String, times: Int = 1, on: UserData = data) {
        repeat(times) {
            on.learnWord(word.lowercase(Locale.ENGLISH))
            on.noteCase(word, Locale.ENGLISH)
        }
    }

    private fun reopen(): UserData {
        data.saveIfDirty()
        assertTrue("the queued save never ran", data.awaitIdle())
        val fresh = UserData.inDir(dir)
        fresh.reload()
        assertTrue("the queued load never ran", fresh.awaitIdle())
        return fresh
    }

    @Test
    fun `a name written twice is offered with its capital`() {
        type("Anthropic", 2)
        assertEquals("Anthropic", data.casedForm("anthropic"))
    }

    @Test
    fun `once is not a habit`() {
        type("Anthropic")
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `a word the store has never held has no opinion`() {
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `case is kept only for a word the store holds`() {
        // The subset invariant, at the one door that could break it. Voting
        // for a word that was never learned would leave a row nothing can
        // ever evict, because every eviction path works from the word table.
        data.noteCase("Anthropic", Locale.ENGLISH)
        data.noteCase("Anthropic", Locale.ENGLISH)
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `the capitals survive a save and a reload`() {
        type("Anthropic", 2)
        type("hello", 3)
        val fresh = reopen()
        try {
            assertEquals("Anthropic", fresh.casedForm("anthropic"))
            assertNull("a word nobody capitalises stays uncapitalised", fresh.casedForm("hello"))
            assertTrue(fresh.isKnown("anthropic"))
        } finally {
            fresh.shutdown()
        }
    }

    @Test
    fun `the file every earlier install wrote still loads`() {
        // Two columns is the whole format before this feature. An install
        // upgrading into it must keep its vocabulary and simply have no
        // opinion about capitals yet.
        File(dir, "learned.txt").writeText("anthropic\t5\nhello\t9\n")
        data.reload()
        assertTrue("the queued load never ran", data.awaitIdle())
        assertTrue("the vocabulary must survive the format change", data.isKnown("anthropic"))
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `a word nobody capitalises costs the file nothing`() {
        type("hello", 3)
        data.saveIfDirty()
        assertTrue(data.awaitIdle())
        val line = File(dir, "learned.txt").readLines().first { it.startsWith("hello\t") }
        assertEquals(
            "the extra columns are written only where there is a vote to " +
                "write, so the file does not grow for the people this never " +
                "fires for: $line",
            2, line.split('\t').size
        )
    }

    @Test
    fun `a lower-case lead is deliberately not carried across a reload`() {
        // The other half of the rule above, and the price it charges. Nothing
        // is written for a word whose own lower-case spelling is winning, so
        // the counterweight does not survive a restart -- and an IME process
        // is killed constantly. Asserted rather than left implicit, because
        // this is the one behaviour of the feature that is a trade rather
        // than a design, and it should fail loudly if the trade is revisited.
        type("hello", 5)
        val fresh = reopen()
        try {
            type("Hello", 2, on = fresh)
            assertEquals(
                "two deliberate mid-sentence capitals reach the bar, because " +
                    "the hundred lower-case sightings behind them were not " +
                    "written to the file",
                "Hello", fresh.casedForm("hello")
            )
            type("hello", 2, on = fresh)
            assertNull(
                "and two of the ordinary spelling take the slot straight " +
                    "back, which is what keeps the trade cheap",
                fresh.casedForm("hello")
            )
        } finally {
            fresh.shutdown()
        }
    }

    @Test
    fun `blocking a word takes its capitals with it`() {
        type("Anthropic", 2)
        data.blockWord("anthropic")
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `deleting a word from the personal dictionary takes its capitals`() {
        type("Anthropic", 2)
        data.removeLearned("anthropic")
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `taking back the last commit takes back the vote`() {
        // Post-correction unlearns a word it has just decided was a typo. The
        // capitals were filed by that same commit and go back with it.
        type("Anthropic")
        data.unlearnWord("anthropic")
        type("Anthropic")
        assertNull(
            "the vote from the retracted commit is still counted, so two " +
                "occurrences of a typo -- one of them taken back -- reach the bar",
            data.casedForm("anthropic")
        )
    }

    @Test
    fun `an evicted word does not leave its capitals behind`() {
        // The subset invariant at the one door that does not remove a word by
        // name. The prune drops the least-used words in bulk, and the first
        // draft of the line that follows it was guarded on a condition the
        // invariant makes impossible -- so it never ran, and a word evicted
        // after years of use would have come back, the next time it was
        // typed, wearing a capital from before it was dropped.
        type("Anthropic", 2)
        for (i in 0 until 13_000) repeat(3) { data.learnWord("w%06d".format(i)) }
        data.saveIfDirty()
        assertTrue("the queued prune never ran", data.awaitIdle())

        assertTrue(
            "nothing was evicted, so this test measured nothing",
            data.learnedSize <= UserData.learnedCap()
        )
        assertTrue("the least-used word should have gone", !data.isKnown("anthropic"))
        assertNull(
            "the word was evicted and its capitalisation outlived it",
            data.casedForm("anthropic")
        )
    }

    @Test
    fun `clearing the learned words clears the capitals`() {
        type("Anthropic", 2)
        data.clearAll()
        assertNull(data.casedForm("anthropic"))
    }

    @Test
    fun `a word written both ways is never offered either of them`() {
        for (w in listOf("Rose", "rose", "Rose", "rose")) type(w)
        assertNull(data.casedForm("rose"))
    }

    @Test
    fun `a habit that changes is followed`() {
        // Somebody who wrote a word in lower case and now writes it as a name.
        type("rose", 3)
        assertNull(data.casedForm("rose"))
        type("Rose", 6)
        assertEquals("Rose", data.casedForm("rose"))
    }
}
