package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * One backspace, one visible character.
 *
 * The cases are written as code points rather than pasted glyphs, because the
 * whole subject is how many UTF-16 units a character occupies and a literal in
 * a source file hides exactly that.
 */
class GraphemeDeleteTest {

    private fun s(vararg cps: Int) = buildString { cps.forEach { appendCodePoint(it) } }

    private fun assertUnits(expected: Int, text: String, what: String) =
        assertEquals(what, expected, GraphemeDelete.unitsToDeleteBefore(text))

    @Test
    fun `the ordinary cases are unchanged`() {
        assertUnits(0, "", "nothing before the cursor")
        assertUnits(1, "a", "one letter")
        assertUnits(1, "word", "the last letter of a word")
        assertUnits(2, s(0x1F921), "a plain astral emoji is one surrogate pair")
    }

    @Test
    fun `a variation selector goes with the character it is modifying`() {
        // The regression this was written for. U+2328 U+FE0F is the keyboard
        // emoji; deleting only the selector left U+2328, which still renders,
        // in its monochrome text form. The backspace looked like it had
        // changed the character rather than removed it. 69 of the 572 shipped
        // emoji are this shape.
        assertUnits(2, s(0x2328, 0xFE0F), "BMP base plus VS16")
        assertUnits(3, s(0x1F3F3, 0xFE0F), "astral base plus VS16")
        assertUnits(2, s(0x0031, 0xFE0F), "a digit asking for emoji presentation")
    }

    @Test
    fun `a skin tone goes with the hand it is colouring`() {
        assertUnits(4, s(0x1F44D, 0x1F3FD), "thumbs up with a modifier")
    }

    @Test
    fun `a joined sequence is one character however many people are in it`() {
        // Eight units, and it used to take five presses: the family lost a
        // child, then a parent, then the joiners one at a time.
        assertUnits(8, s(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467), "family of three")
        assertUnits(11, s(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466), "of four")
    }

    @Test
    fun `a flag is two indicators and pairing starts at the front of the run`() {
        // Not "join anything adjacent": in a run of indicators the pairs are
        // formed from the start, so what the last character is depends on
        // whether the run has an even length. Deleting greedily would eat two
        // flags at once.
        assertUnits(4, s(0x1F1F9, 0x1F1F7), "one flag")
        assertUnits(4, s(0x1F1F9, 0x1F1F7, 0x1F1E9, 0x1F1EA), "the second of two flags")
        assertUnits(2, s(0x1F1F9), "a lone indicator is its own character")
        assertUnits(2, s(0x1F1F9, 0x1F1F7, 0x1F1E9), "the odd one out of three")
    }

    @Test
    fun `a combining mark goes with its base letter`() {
        assertUnits(2, s(0x0065, 0x0301), "e plus combining acute")
        assertUnits(1, s(0x00E9), "the precomposed form is already one unit")
    }

    @Test
    fun `a keycap is one character`() {
        assertUnits(3, s(0x0031, 0xFE0F, 0x20E3), "keycap 1")
    }

    @Test
    fun `preceding text is never included`() {
        assertUnits(2, "ab" + s(0x2328, 0xFE0F), "the emoji only, not the letters")
        assertUnits(1, s(0x2328, 0xFE0F) + "a", "the letter only, not the emoji")
    }

    @Test
    fun `every shipped emoji is removed by exactly one backspace`() {
        // The point of the fix, asserted against the actual content rather
        // than against a handful of examples. Any emoji added to the assets is
        // covered from the moment it lands.
        val dir = listOf(File("src/main/assets/emoji"), File("app/src/main/assets/emoji"))
            .first { it.isDirectory }
        val tokens = HashSet<String>()
        dir.listFiles().orEmpty().filter { it.extension == "txt" }.forEach { f ->
            f.readLines().forEach { line ->
                line.split('\t').drop(1).forEach { col ->
                    col.split(' ').forEach { if (it.isNotBlank()) tokens.add(it) }
                }
            }
        }
        // Guards the guard: an asset layout change that yields nothing would
        // otherwise report a clean sweep of zero emoji.
        assertEquals("the emoji assets stopped parsing", true, tokens.size > 400)
        val stragglers = tokens.filter {
            GraphemeDelete.unitsToDeleteBefore(it) != it.length
        }
        assertEquals(
            "these need more than one backspace: " +
                stragglers.joinToString { e -> e.codePoints().toArray().joinToString("+") {
                    cp -> "U+%04X".format(cp)
                } },
            emptyList<String>(), stragglers
        )
    }
}
