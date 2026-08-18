package com.rimboard.keyboard.model

/**
 * How much of the text before the cursor one backspace should remove.
 *
 * A backspace is meant to delete one *visible* character, and the field stores
 * UTF-16 units. Those two only agree for the simple cases. The delete path used
 * to check `Character.isSurrogatePair` and take two units when it matched, one
 * otherwise, which is right for the 475 single-code-point emoji in
 * `assets/emoji/` and wrong for the other 71.
 *
 * The bad case is not the one that takes two presses, it is the one where the
 * first press deletes something invisible. `⌨️` is `U+2328 U+FE0F`: a keyboard
 * glyph followed by a variation selector asking for the emoji presentation
 * rather than the monochrome one. Deleting the last unit removes the request,
 * not the character, so the emoji turns into `⌨` and sits there looking like a
 * backspace that half worked. Sixty-nine of the shipped emoji are that shape.
 * The two family emoji are eight units and took five presses, shrinking a
 * person at a time.
 *
 * Written out rather than taken from `BreakIterator.getCharacterInstance()`:
 * that is backed by the device's ICU, whose emoji data is as old as the
 * platform image, so the cluster rules would vary by phone for exactly the
 * sequences this exists to handle. It is also unusable in these unit tests.
 * The rules below are the parts of UAX #29 that a keyboard actually meets.
 */
object GraphemeDelete {

    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F

    /** Skin tones, and the only modifiers that follow the emoji they change. */
    private fun isModifier(cp: Int) = cp in 0x1F3FB..0x1F3FF

    /** 🇦 to 🇿. A flag is exactly two of these, which is what makes the run length matter. */
    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /**
     * A code point that has no standalone existence: it modifies whatever
     * precedes it, so deleting it alone leaves the thing it was modifying
     * behind in an altered form rather than deleting anything the user can see.
     */
    private fun isExtender(cp: Int): Boolean =
        cp == VS15 || cp == VS16 || cp == KEYCAP || isModifier(cp) ||
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK,
                Character.ENCLOSING_MARK -> true
                else -> false
            }

    /** The index where the code point ending at [at] begins. */
    private fun startBefore(text: CharSequence, at: Int): Int {
        if (at <= 0) return 0
        return at - Character.charCount(Character.codePointBefore(text, at))
    }

    /**
     * The number of UTF-16 units of [text] that one backspace should remove,
     * where [text] is the text immediately before the cursor. 0 if there is
     * nothing to delete.
     *
     * [text] may be a window onto a longer field, so it can begin in the middle
     * of a character; a caller that reads a fixed number of units should pass
     * enough of them that the last cluster is whole. Sixteen is plenty for
     * anything short of a deliberately pathological sequence.
     */
    fun unitsToDeleteBefore(text: CharSequence): Int {
        val end = text.length
        if (end == 0) return 0

        // Flags first, because they are the one cluster whose extent depends on
        // what is behind it rather than on what it is. Two regional indicators
        // make a flag, so in a run of them the pairing starts at the *front*:
        // an even-length run ends with a whole flag, an odd one ends with a
        // single indicator that is not part of any pair.
        if (isRegionalIndicator(Character.codePointBefore(text, end))) {
            var run = 0
            var k = end
            while (k > 0 && isRegionalIndicator(Character.codePointBefore(text, k))) {
                run++
                k = startBefore(text, k)
            }
            return if (run % 2 == 0) 4 else 2
        }

        var i = startBefore(text, end)
        while (i > 0) {
            val cur = Character.codePointAt(text, i)
            val j = startBefore(text, i)
            val prev = Character.codePointAt(text, j)
            when {
                // The cluster so far begins with something that cannot stand
                // alone, so whatever precedes it is part of the same character.
                isExtender(cur) -> i = j
                // A joiner binds what is before it to what is after it, so it
                // and the code point before it both come too. Without that
                // second step the walk would stop on the joiner and leave it
                // dangling at the end of the field.
                prev == ZWJ -> i = startBefore(text, j)
                else -> return end - i
            }
        }
        return end - i
    }
}
