package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The do-not-preview flag, asked of the one path that never got it.
 *
 * `EXTRA_IS_SENSITIVE` is how a password manager tells everything downstream
 * not to show what it just put on the clipboard, and this app takes it
 * seriously nearly everywhere. [ClipChip.label] refuses to preview such a clip
 * on the suggestion strip and says why: "Android's own clipboard preview
 * honours it and so does this". `ClipboardStore.Entry` carries the flag.
 * `captureClip` records it from the description at the moment of the copy
 * rather than reading it later. The pin round trip keeps it, and `loadPinned`
 * reads it back from the file with a note explaining that "without the flag a
 * pinned password would come back previewable after a restart".
 *
 * Then the clipboard panel drew the text on a card anyway. `updateClipView`
 * called `pinnedTexts()` and mapped the history to `it.text`, so the flag was
 * discarded on the last line before the one thing it exists to stop: the panel
 * showed a password manager's clip in full, in a two-column grid, one row
 * below a strip that was refusing to show the same string. The card's
 * `contentDescription` was the clip text too, so a screen reader read it out.
 *
 * It is the shape this codebase keeps finding: a rule honoured by every path
 * that was written with it in mind, and missing from the sibling path that
 * builds its own list.
 *
 * The panel hides the content rather than dropping the entry, which is what
 * the strip already does -- offer the action, withhold the text. A masked card
 * still pastes.
 */
class SensitiveClipTest {

    @Test
    fun `a sensitive clip is not drawn on the card`() {
        assertFalse(
            "the clipboard panel draws a password manager's clip in full, on a " +
                "card, while the strip one row above refuses to preview it",
            ClipChip.cardLabel(PASSWORD, sensitive = true).contains(PASSWORD)
        )
    }

    @Test
    fun `an ordinary clip is drawn as itself`() {
        // The other half: this must hide the flagged clip and nothing else.
        assertEquals("see you at six", ClipChip.cardLabel("see you at six", false))
    }

    @Test
    fun `the mask is the same width whatever it hides`() {
        // A mask as long as the secret is a length disclosure, and a password
        // length is worth having if you are guessing one.
        assertEquals(
            ClipChip.cardLabel("a", sensitive = true),
            ClipChip.cardLabel(PASSWORD + PASSWORD, sensitive = true)
        )
    }

    @Test
    fun `the strip and the panel agree`() {
        // The defect was these two disagreeing, so it is the agreement that is
        // worth pinning rather than either rule on its own.
        val onStrip = ClipChip.label(PASSWORD, sensitive = true, inPasswordField = false, fallback = "Paste")
        assertTrue("the strip stopped hiding it", !onStrip.contains(PASSWORD))
        assertTrue(
            "the panel shows what the strip hides",
            !ClipChip.cardLabel(PASSWORD, sensitive = true).contains(PASSWORD)
        )
    }

    private companion object {
        /** Long enough that a partial leak would still be a leak. */
        const val PASSWORD = "correct-horse-battery-staple"
    }
}
