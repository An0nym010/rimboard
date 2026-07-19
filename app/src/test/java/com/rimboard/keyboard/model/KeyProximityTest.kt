package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the geometry behind adaptive tap targeting. These would have caught a
 * layout change silently drifting away from the proximity model.
 */
class KeyProximityTest {

    @Test
    fun `same key costs nothing`() {
        assertEquals(0.0, KeyProximity.forLang("en").cost('a', 'a'), 0.0001)
    }

    @Test
    fun `neighbours are much cheaper than distant keys`() {
        val en = KeyProximity.forLang("en")
        assertTrue("q-w should beat q-p", en.cost('q', 'w') < en.cost('q', 'p'))
        assertTrue("a-s should beat a-l", en.cost('a', 's') < en.cost('a', 'l'))
        // A horizontal neighbour should stay in the cheap band.
        assertTrue(en.cost('a', 's') < 0.4)
        // Opposite ends of the keyboard should saturate at the maximum.
        assertEquals(1.0, en.cost('q', 'p'), 0.0001)
    }

    @Test
    fun `vertically adjacent keys are cheap too`() {
        val en = KeyProximity.forLang("en")
        assertTrue(en.cost('e', 'd') < en.cost('e', 'l'))
    }

    @Test
    fun `cost is symmetric and bounded`() {
        val en = KeyProximity.forLang("en")
        for (a in "qwertyuiopasdfghjklzxcvbnm") {
            for (b in "qwertyuiopasdfghjklzxcvbnm") {
                val c = en.cost(a, b)
                assertTrue("$a-$b out of range: $c", c in 0.0..1.0)
                assertEquals("$a-$b asymmetric", c, en.cost(b, a), 0.0001)
            }
        }
    }

    @Test
    fun `unknown characters fall back to the maximum cost`() {
        assertEquals(1.0, KeyProximity.forLang("en").cost('a', '€'), 0.0001)
    }

    @Test
    fun `rows are read from each language's real layout`() {
        // Turkish has dotless i on the top row; German is QWERTZ; French AZERTY.
        // If a layout changes, proximity follows it instead of going stale.
        val tr = KeyProximity.forLang("tr")
        assertTrue("Turkish should know dotless i", tr.cost('ı', 'o') < 1.0)
        val de = KeyProximity.forLang("de")
        assertTrue("German z sits on the top row", de.cost('z', 'u') < de.cost('z', 'm'))
        val fr = KeyProximity.forLang("fr")
        assertTrue("French a and z are neighbours", fr.cost('a', 'z') < 0.4)
    }

    @Test
    fun `every bundled language builds a usable grid`() {
        for (lang in Languages.codes) {
            val p = KeyProximity.forLang(lang)
            val letters = Languages.byCode(lang).layout(false, false).rows
                .flatMap { it.keys }
                .filter { it.type == KeyType.CHARACTER && it.label.length == 1 && it.label[0].isLetter() }
                .map { it.label[0] }
            assertTrue("$lang has no letters", letters.isNotEmpty())
            // Letters from the layout must be known to the model (cost < 1 to themselves' neighbours).
            val known = letters.count { a -> letters.any { b -> a != b && p.cost(a, b) < 1.0 } }
            assertTrue("$lang: too few letters mapped ($known/${letters.size})", known > letters.size / 2)
        }
    }
}
