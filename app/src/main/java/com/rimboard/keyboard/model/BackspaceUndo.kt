package com.rimboard.keyboard.model

/**
 * What sliding back to the right owes the user.
 *
 * The backspace swipe deletes a word per step of travel and restores one per
 * step back, which is an undo affordance — and an undo that does not round-trip
 * is worse than none, because it invites the user to rely on it.
 *
 * **It did not round-trip.** Backspace is a repeating key, so the finger going
 * down deletes a character *before* the swipe can arm at 30dp of travel — and
 * only whole words removed after that point were ever recorded. Swiping left to
 * delete a few words and sliding back to change your mind left the last
 * character of the text missing, silently. Holding the key a moment before
 * swiping lost more than one.
 *
 * The rule that fixes it is the invariant this class exists to state: **a
 * restore returns everything removed since the finger went down, in the order
 * it stood in the text.** Deletions travel leftwards, so each new one is
 * *prepended* to what is already pending, and the pending run is folded into
 * the next whole word removed — so one slide right returns "world" rather than
 * "worl", and the character deleted on the way in is not orphaned.
 *
 * Extracted from `RimBoardService` because it is arithmetic about strings with
 * an invariant worth pinning, and the service cannot be run on a plain JVM.
 *
 * **Deliberately not covered: the long hold.** Holding backspace switches to
 * word-by-word deletion after twelve repeats, and those words are not recorded
 * here. That is not an oversight — the swipe cancels the repeat timer the
 * moment it arms, and sliding right restores only as many words as the swipe
 * itself removed, so a long hold's words are never something a slide can reach.
 * Recording them would put text in this list that no gesture can ask for.
 */
class BackspaceUndo(private val cap: Int = CAP) {

    companion object {
        /**
         * How many restorable chunks are kept.
         *
         * A bound rather than a filter: the swipe is one gesture across one
         * screen width and cannot produce anything near this many steps. It is
         * here so a stuck pointer cannot grow the list without limit.
         */
        const val CAP = 50
    }

    /** Whole words removed by the swipe, oldest first. */
    private val chunks = ArrayDeque<String>()

    /**
     * Text removed since the last whole word, still unattached to a chunk.
     *
     * This is the part that was missing. It holds the character the key-down
     * deleted, plus anything auto-repeat removed before the swipe armed.
     */
    private val pending = StringBuilder()

    /** A new gesture, or a new text field: nothing here is restorable now. */
    fun reset() {
        chunks.clear()
        pending.setLength(0)
    }

    /**
     * Record text deleted before any word boundary was crossed.
     *
     * Prepended, because successive backspaces walk leftwards through the text
     * while this accumulates forwards: deleting "d" then "l" from "world" has
     * to reassemble as "ld", not "dl".
     */
    fun noteDeleted(text: String) {
        if (text.isEmpty()) return
        pending.insert(0, text)
    }

    /**
     * Record a whole word removed by the swipe, taking any pending text with it.
     *
     * [chunk] sits to the *left* of whatever is pending — the word-delete scans
     * backwards from the cursor, and the pending run was deleted from in front
     * of it — so the restorable text is the chunk followed by the pending run.
     */
    fun noteWordDeleted(chunk: String) {
        val whole = chunk + pending
        pending.setLength(0)
        if (whole.isEmpty()) return
        chunks.addLast(whole)
        while (chunks.size > cap) chunks.removeFirst()
    }

    /**
     * The text one step back to the right should put back, or null.
     *
     * Null rather than empty so the caller can tell "nothing left to restore"
     * from "a chunk that happened to be empty" — the latter cannot occur, and
     * saying so here is what keeps it that way.
     */
    fun restore(): String? = chunks.removeLastOrNull()

    /** Whether anything is waiting to be put back. */
    fun isEmpty(): Boolean = chunks.isEmpty()
}
