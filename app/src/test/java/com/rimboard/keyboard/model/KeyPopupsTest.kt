package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a long-press offers, and in what order.
 *
 * A hundred and twenty lines of ordering rules that nothing had ever run. They
 * are the kind that go wrong quietly: a popup with a duplicate in it wastes a
 * cell, one that runs past its cap gets scaled until the cells are too small to
 * aim at, and one that comes back empty is a key that does nothing when held —
 * which the file promises never happens and which no test was checking.
 */
class KeyPopupsTest {

    private fun popup(letter: Char, native: String? = null, digit: String? = null) =
        KeyPopups.forLetter(letter, native, digit)

    @Test
    fun `the digit leads, then the language, then everyone else`() {
        // Turkish 'u': the digit is the commonest reason to hold a top-row key
        // with the number row off; 'ü' is a letter of the language; 'ú' is
        // decoration that some other language wanted.
        val p = popup('u', native = "ü", digit = "7")
        assertEquals('7', p[0])
        assertEquals('ü', p[1])
        assertTrue("the general accents should follow: $p", p.indexOf('ú') > 1)
    }

    @Test
    fun `a letter the language declares is not repeated further down`() {
        // 'ü' is both Turkish's own and in the general list for 'u'. It must
        // appear once, in its earlier and higher-priority place.
        val p = popup('u', native = "ü")
        assertEquals("'ü' appears twice in $p", 1, p.count { it == 'ü' })
        assertEquals('ü', p[0])
    }

    @Test
    fun `the letter under the finger is never offered as an alternative`() {
        // It is already under the thumb, so a cell for it is a wasted cell.
        // Reachable through the language list, which is free-form text.
        val p = popup('a', native = "aá")
        assertTrue("the base letter was offered: $p", p.none { it == 'a' })
    }

    @Test
    fun `a popup never grows past what can be aimed at`() {
        // Wider than this and the cells are scaled down until they cannot be
        // hit, which is worse than offering fewer.
        val p = popup('o', native = "öø", digit = "9")
        assertTrue("popup of ${p.length} cells: $p", p.length <= 9)
        // And every letter, with the most crowded inputs available.
        for (c in 'a'..'z') {
            val q = popup(c, native = "áéíóú", digit = "1")
            assertTrue("popup of ${q.length} for '$c': $q", q.length <= 9)
        }
    }

    @Test
    fun `no Latin letter is a dead press`() {
        // The promise the file makes. Letters that take no accent anywhere --
        // b, f, k, m, p, q, v, x -- are exactly why the symbol table exists, so
        // this is the test that stops it being trimmed away as decoration.
        for (c in 'a'..'z') {
            assertTrue("holding '$c' offers nothing", popup(c).isNotEmpty())
        }
    }

    @Test
    fun `a letter with no accent still offers its symbol`() {
        assertTrue(popup('v').contains('✓'))
        assertTrue(popup('x').contains('×'))
        assertTrue(popup('k').contains('¿'))
    }

    @Test
    fun `a letter outside the tables offers only what its language gives it`() {
        // Cyrillic and Greek carry no entry in either table -- Greek supplies
        // its accents through the layout instead. So the general answer for
        // such a letter is the language's own list and nothing else, and an
        // empty one is an honest empty rather than a crash.
        assertEquals("ά", popup('α', native = "ά"))
        assertEquals("", popup('в'))
    }

    @Test
    fun `case is not this function's business`() {
        // Every caller in Layouts passes the lowercase letter, because that is
        // what the rows are built from; the view applies shift afterwards.
        // Written down because the function would otherwise look as though it
        // handled case, and it does not: the accents it returns are lowercase.
        val p = popup('A')
        assertTrue("an uppercase key returned nothing: $p", p.isNotEmpty())
        assertTrue("accents came back uppercased: $p", p.any { it == 'á' })
    }
}
