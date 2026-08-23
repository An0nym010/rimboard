package com.rimboard.keyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the keyboard has learned, after a very long time.
 *
 * Everything else about this engine is measured on a fresh install. This asks
 * the other question: a keyboard is used for years, it learns from every word
 * typed, and the file it learns into is read at startup and held in memory for
 * as long as the process lives. A store that grows without bound does not fail
 * on the day it is written — it fails as "the keyboard got slow after a year",
 * on someone else's phone, with nothing to point at.
 *
 * The n-gram tables have hard caps and evict the least-used context to meet
 * them. The word table did not, and this is the file that noticed.
 */
class LearnedGrowthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The store queues its writes; the prune runs inside one. */
    private fun UserData.settle() = assertTrue("the queued work never ran", awaitIdle())

    /**
     * Types [words] distinct words [times] each, then saves.
     *
     * Twice is the interesting number: once-only words are already dropped, so
     * a word typed twice is the cheapest thing that survives the existing
     * prune, and it is also entirely ordinary — everybody has thousands of
     * words they have typed more than once.
     */
    private fun grow(data: UserData, words: Int, times: Int) {
        for (i in 0 until words) {
            val w = "w%06d".format(i)
            repeat(times) { data.learnWord(w) }
        }
        data.saveIfDirty()
        data.settle()
    }

    @Test
    fun `the learned word table is bounded`() {
        val data = UserData.inDir(tmp.newFolder())
        try {
            grow(data, words = 40_000, times = 2)
            val size = data.learnedSize
            println("after 40,000 distinct words typed twice each: $size held")
            assertTrue(
                "the learned table grew to $size and has no bound. It is read at " +
                    "startup and held for the life of the process, so this is a " +
                    "keyboard that gets slower for as long as it is used.",
                size <= UserData.learnedCap()
            )
        } finally {
            data.shutdown()
        }
    }

    @Test
    fun `a word typed once still goes when the table is under pressure`() {
        // The rule that was already there, kept: a word seen once is a typo or
        // a passing proper noun, and is the first thing to lose.
        val data = UserData.inDir(tmp.newFolder())
        try {
            for (i in 0 until 12_000) data.learnWord("once%05d".format(i))
            data.learnWord("kept")
            data.learnWord("kept")
            data.learnWord("kept")
            data.saveIfDirty()
            data.settle()
            assertTrue("a word typed three times was dropped", data.isKnown("kept"))
            assertTrue(
                "the once-only words were kept: ${data.learnedSize}",
                data.learnedSize < 12_000
            )
        } finally {
            data.shutdown()
        }
    }

    @Test
    fun `deleting a word from the personal dictionary takes its pin with it`() {
        // Found reviewing the change that added pinning rather than by it
        // failing: a pin is set when somebody adds a word by hand and was
        // cleared by nothing at all. So the pin outlived the word, sat in the
        // file for good, and would have re-applied itself if the word were
        // ever typed again — leaving it unevictable on the strength of an act
        // the user had since undone.
        //
        // Ironic in a way worth recording: this was introduced while fixing
        // another store that only ever grew.
        val data = UserData.inDir(tmp.newFolder())
        try {
            data.addUserWord("Rimboard", java.util.Locale.US)
            data.removeLearned("rimboard")
            // Typed again afterwards, so it is back in the table as an
            // ordinary word with an ordinary count.
            repeat(2) { data.learnWord("rimboard") }
            grow(data, words = 40_000, times = 5)
            assertTrue(
                "a deleted word came back pinned and survived the cap",
                !data.isKnown("rimboard")
            )
        } finally {
            data.shutdown()
        }
    }

    @Test
    fun `blocking a word takes its pin with it too`() {
        val data = UserData.inDir(tmp.newFolder())
        try {
            data.addUserWord("Rimboard", java.util.Locale.US)
            data.blockWord("rimboard")
            repeat(2) { data.learnWord("rimboard") }
            grow(data, words = 40_000, times = 5)
            assertTrue(
                "a blocked word came back pinned and survived the cap",
                !data.isKnown("rimboard")
            )
        } finally {
            data.shutdown()
        }
    }

    @Test
    fun `a word the user added by hand outlives words that were merely typed`() {
        // addUserWord is somebody opening the personal dictionary and typing a
        // word in. Losing that to a cap is not a trade-off, it is a bug: the
        // whole point of the screen is that the keyboard stops arguing about
        // that word.
        //
        // The typed words are given *five* uses each against the hand-added
        // word's three, so the pinned word really is the weakest thing in the
        // table and eviction really would take it. Written first with two uses
        // each, where the pinned word outranked everything by accident and the
        // test passed with the protection deleted — which is worth saying out
        // loud, because that version of it proved nothing at all.
        val data = UserData.inDir(tmp.newFolder())
        try {
            data.addUserWord("Rimboard", java.util.Locale.US)
            grow(data, words = 40_000, times = 5)
            assertTrue(
                "the hand-added word was evicted by the cap",
                data.isKnown("rimboard")
            )
        } finally {
            data.shutdown()
        }
    }
}
