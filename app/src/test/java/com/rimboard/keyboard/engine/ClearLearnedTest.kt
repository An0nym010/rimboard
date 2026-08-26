package com.rimboard.keyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * What "Delete learned words" deletes.
 *
 * The setting says "Remove all learned words and predictions from this
 * device", and [UserData.clearAll] empties four things in memory to match:
 * the learned words, the bigrams, the trigrams, and the pins that mark which
 * words were added by hand rather than picked up.
 *
 * It then deleted three files. `pinned.txt` was left on disk, and an IME's
 * process is killed constantly, so the next load read every pin back in. The
 * words themselves were gone, so nothing looked wrong -- until one of them was
 * typed again, at which point it was relearned *and* unevictable, pinned by an
 * act the user had since undone.
 *
 * That exact hazard is written down two methods above, in `removeLearned`,
 * about deleting a single word: "Leaving one behind would mean the word came
 * back unevictable if it were ever typed again, having been pinned by an act
 * the user had since undone." The one-word path was careful about it. The
 * delete-everything path was not.
 *
 * `blocked.txt` is deliberately left alone. A block is not a learned word --
 * it is the opposite, a word the user asked never to see -- and clearing the
 * vocabulary must not quietly unban anything.
 */
class ClearLearnedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun UserData.settle() = assertTrue("queued work never ran", awaitIdle())

    @Test
    fun `clearing the learned words takes the pins with it`() {
        val dir = tmp.newFolder()
        val data = UserData.inDir(dir)
        data.reload(); data.settle()

        // What the personal dictionary screen does when somebody types a word
        // into it by hand.
        data.addUserWord("kadikoy", Locale.ENGLISH)
        data.settle()
        val pins = File(dir, "pinned.txt")
        assertTrue(
            "the premise: a hand-added word is pinned on disk. It is not, so " +
                "this test proves nothing",
            pins.exists() && pins.readText().contains("kadikoy")
        )

        data.clearAll()
        data.settle()

        // Read once and safely: the message must not itself throw when the
        // file is absent, which is the passing case.
        val left = if (pins.exists()) pins.readText() else ""
        assertTrue(
            "pinned.txt survived \"Delete learned words\", so the next load " +
                "reads the pins back and the word returns unevictable if it " +
                "is ever typed again: " + left,
            left.isBlank()
        )
    }

    @Test
    fun `a restart after clearing brings nothing back`() {
        // The consequence, stated the way it actually happens: an IME process
        // is killed and rebuilt constantly, and a second store over the same
        // directory is exactly what that looks like.
        val dir = tmp.newFolder()
        val data = UserData.inDir(dir)
        data.reload(); data.settle()
        data.addUserWord("kadikoy", Locale.ENGLISH)
        data.settle()
        data.clearAll()
        data.settle()

        val after = UserData.inDir(dir)
        after.reload(); after.settle()
        assertTrue(
            "the word came back after a restart",
            !after.isKnown("kadikoy")
        )
        val pins = File(dir, "pinned.txt")
        val left = if (pins.exists()) pins.readText() else ""
        assertTrue("the pin came back after a restart: " + left, left.isBlank())
    }

    @Test
    fun `clearing the learned words does not unblock anything`() {
        // The other half. "Delete learned words" is not "forget what I banned",
        // and a block that quietly lifted would put the word the user got rid
        // of back on the strip.
        val dir = tmp.newFolder()
        val data = UserData.inDir(dir)
        data.reload(); data.settle()
        data.blockWord("duck")
        data.settle()

        data.clearAll()
        data.settle()
        assertTrue("clearing learned words unblocked a word", data.isBlocked("duck"))

        val after = UserData.inDir(dir)
        after.reload(); after.settle()
        assertTrue(
            "the block did not survive a restart after clearing",
            after.isBlocked("duck")
        )
    }
}
