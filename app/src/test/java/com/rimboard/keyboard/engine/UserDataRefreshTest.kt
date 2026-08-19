package com.rimboard.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The second reader of the learned data.
 *
 * The keyboard writes these files and its own memory is always ahead of them.
 * The spell checker is a separate component with its own [UserData], and it
 * loaded once and held that snapshot for as long as it ran — so a word added
 * in the personal dictionary stopped being underlined in the keyboard's strip
 * and went on being underlined by the spell checker in every other app, which
 * is the reverse of what its class comment promises.
 */
class UserDataRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The store queues its work, as a binder-thread caller needs it to. */
    private fun UserData.settle() =
        assertTrue("the queued reload never ran", awaitIdle())

    private fun writeLearned(dir: File, vararg words: String) {
        File(dir, "learned.txt")
            .writeText(words.joinToString("\n") { w -> "$w\t5" })
    }

    @Test
    fun `a word added by the other reader is picked up`() {
        val dir = tmp.newFolder()
        writeLearned(dir, "ankara")
        val data = UserData.inDir(dir)
        data.reload()
        data.settle()
        assertTrue("the word it started with", data.isKnown("ankara"))
        assertFalse("not written yet", data.isKnown("izmir"))

        // What the personal dictionary screen does, from another component.
        writeLearned(dir, "ankara", "izmir")
        File(dir, "learned.txt").setLastModified(System.currentTimeMillis() + 2000)

        data.reloadIfChanged()
        data.settle()
        assertTrue("the word added since the load", data.isKnown("izmir"))
        assertTrue("and the one from before it", data.isKnown("ankara"))
    }

    @Test
    fun `an unchanged file is not re-read`() {
        val dir = tmp.newFolder()
        writeLearned(dir, "ankara")
        val data = UserData.inDir(dir)
        data.reload()
        data.settle()

        // Rewritten with different content but the same length and the same
        // timestamp, so the stamp cannot tell it apart. If the check were
        // dropped and every field re-read the files, "samsun" would appear
        // here -- which is the cost this exists to avoid, made visible.
        //
        // Deleting the file would have been the obvious way to write this and
        // proves nothing: a deletion changes both length and timestamp, so it
        // is a change, and the reload it triggers is correct.
        val f = File(dir, "learned.txt")
        val stamp = f.lastModified()
        writeLearned(dir, "samsun")
        f.setLastModified(stamp)

        data.reloadIfChanged()
        data.settle()
        assertTrue("an unchanged stamp must not re-read", data.isKnown("ankara"))
        assertFalse("nothing should have been re-read", data.isKnown("samsun"))
    }
}
