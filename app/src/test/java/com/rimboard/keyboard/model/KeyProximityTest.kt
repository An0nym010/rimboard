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
    /**
     * The grid is where the keys are drawn, for every shipped layout.
     *
     * This class's header promises that every letter sits "on the same
     * staggered three-row grid that [Layouts] draws", and that reading the rows
     * from the real layout means "a layout change can never leave tap
     * targeting pointing at the wrong keys". The *letters* were read from the
     * layout. The **positions** were the constant [0.5, 1.0, 2.0], which is the
     * QWERTY shape, and five of the twenty-two layouts are not that shape:
     *
     *     lang  rows        drawn               assumed
     *     es    10, 10, 7   0.50, 0.50, 2.00    0.5, 1.0, 2.0
     *     fr    10, 10, 6   0.50, 0.50, 2.00
     *     ru    11, 11, 9   0.50, 0.50, 1.50
     *     uk    11, 11, 9   0.50, 0.50, 1.50
     *     el     9,  9, 7   1.00, 1.00, 2.00
     *
     * Half a key is not a rounding error at this scale: it inverts adjacency.
     * With the assumed offsets Spanish "a" is nearest to "w", when it is drawn
     * directly under "q".
     *
     * **`AutocorrectAccuracyTest` could not have caught it**, and that is worth
     * knowing before trusting it about geometry again: it builds its typos with
     * `KeyProximity.neighbours()` and then asks the engine to repair them, so
     * the corpus and the subject were wrong in the same way and cancelled. Only
     * measuring the grid against the layout finds this, which is what this
     * test does.
     */
    @Test
    fun `every letter sits where its layout draws it`() {
        val out = StringBuilder()
        val wrong = ArrayList<String>()
        for (code in Languages.codes) {
            val layout = Languages.byCode(code).layout(false, false)
            val units = layout.unitsPerRow
            // The letter rows, as KeyProximity picks them: character keys with
            // a single-letter label, rows of four or more, first three.
            val letterRows = layout.rows.filter { row ->
                row.keys.count {
                    it.type == KeyType.CHARACTER && it.label.length == 1 && it.label[0].isLetter()
                } >= 4
            }.take(3)
            val trueOffsets = letterRows.map { row ->
                // Where the first letter's centre sits, in key units, with the
                // row centred in the layout as the view draws it.
                val rowUnits = row.keys.sumOf { it.width.toDouble() }
                var x = (units - rowUnits) / 2.0
                var first = -1.0
                for (k in row.keys) {
                    val isLetter = k.type == KeyType.CHARACTER &&
                        k.label.length == 1 && k.label[0].isLetter()
                    if (isLetter && first < 0) first = x + k.width / 2.0
                    x += k.width
                }
                first
            }
            // What the grid actually says, which is the point.
            val prox = KeyProximity.forLang(code)
            val model = letterRows.map { row ->
                val first = row.keys.first {
                    it.type == KeyType.CHARACTER && it.label.length == 1 &&
                        it.label[0].isLetter()
                }.label[0]
                (prox.gridX(first) ?: -1f).toDouble()
            }
            val off = trueOffsets.indices.map {
                Math.abs(trueOffsets[it] - model[it])
            }
            val worst = off.maxOrNull() ?: 0.0
            if (worst > 0.01) {
                wrong.add(code + " drawn " + trueOffsets.map { "%.2f".format(it) } +
                    " but the grid says " + model.map { "%.2f".format(it) })
            }
            out.append("%-3s units=%.1f  rows=%s  drawn=%s  grid=%s  off %.2f%s%n"
                .format(code, units,
                    letterRows.map { r -> r.keys.count { it.type == KeyType.CHARACTER && it.label.length == 1 && it.label[0].isLetter() } },
                    trueOffsets.map { "%.2f".format(it) },
                    model.map { "%.2f".format(it) }, worst,
                    if (worst > 0.05) "   <-- MISMATCH" else ""))
        }
        println(out)
        assertEquals(
            "the proximity grid does not match the keys the layout draws:" +
                System.lineSeparator() + out,
            emptyList<String>(), wrong
        )
    }

}
