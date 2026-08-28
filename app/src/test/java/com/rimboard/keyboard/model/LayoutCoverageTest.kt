package com.rimboard.keyboard.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every letter a language writes with can be typed on its keyboard.
 *
 * The layouts are written by hand, one function per language in [Layouts], and
 * a language whose alphabet gained a letter its layout did not is a language
 * you cannot spell in. Nothing checked it: the subtype tests say each language
 * has a layout, the accuracy tests say the suggestions are good, and neither
 * asks whether the keys are there.
 *
 * The alphabet is taken from the shipped dictionary rather than declared here,
 * so it is the language as this keyboard actually models it.
 *
 * ## What this can and cannot catch
 *
 * "Typed" means printed on a key **or** in its long-press popup, and for the
 * Latin layouts the popups are generous enough that almost nothing could fail:
 * the shared accent popups between them offer most of the Latin block, so a
 * letter dropped from a Latin row is still reachable and this stays quiet.
 * Demonstrated rather than assumed -- removing the dotless i from the Turkish
 * top row does not fail this test, because a long press on i still produces it.
 *
 * Where it has teeth is the scripts the popups do not cover. Removing Cyrillic
 * "ж" from the Russian layout fails with `ru: ж`, and the same would hold for
 * Greek and Ukrainian. Those are also the layouts where a missing letter is
 * most likely, because they are the ones a reviewer cannot check by eye.
 *
 * ## The survey it prints, which is not an assertion
 *
 * A letter reachable only by long press is typeable but not convenient, and
 * that is a different question with a real cost on the other side: every key
 * added to a row makes all of them narrower, and narrower keys are more taps
 * landing on the wrong one. So the keys-only gaps are printed rather than
 * failed, because closing them is a layout decision with a measurable price
 * and this test cannot pay it.
 *
 * As it stands only English, Turkish and Indonesian have every letter of their
 * alphabet on a key of its own. Turkish is the proof it can be done -- it fits
 * ğ ü ş ö ç by running twelve keys to the row -- and the nineteen others put
 * their own letters behind a long press, including ones as ordinary as Swedish
 * å, German ä and Danish ø. Somebody should decide that language by language
 * with the tap-accuracy harness in hand; the numbers below are so the question
 * is at least visible.
 */
class LayoutCoverageTest {

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    /** Every character the layout can produce, folded to lower case. */
    private fun reachable(lang: Languages.Lang): Set<Char> = walk(lang, popups = true)

    /** Only what is printed on a key, with no long press involved. */
    private fun onKeys(lang: Languages.Lang): Set<Char> = walk(lang, popups = false)

    private fun walk(lang: Languages.Lang, popups: Boolean): Set<Char> {
        val out = HashSet<Char>()
        fun take(k: Key) {
            k.label.lowercase(lang.locale).forEach { out.add(it) }
            if (popups) k.popup.forEach { take(it) }
        }
        // Both with and without the number row, and both globe states, since
        // each is a preference and none of them may be the one carrying a
        // letter.
        for (numberRow in listOf(false, true)) {
            for (globe in listOf(false, true)) {
                lang.layout(numberRow, globe).rows.forEach { r -> r.keys.forEach { take(it) } }
            }
        }
        return out
    }

    /**
     * The letters the language's own word list is written with.
     *
     * A letter has to appear in at least [MIN_WORDS] of the top [TOP_WORDS]
     * entries to count. A frequency list built from subtitles carries stray
     * foreign words, and a letter that turns up only in those is not part of
     * the alphabet.
     */
    private fun alphabet(lang: Languages.Lang): Set<Char> {
        val counts = HashMap<Char, Int>()
        var seen = 0
        assets().resolve("dictionaries/${lang.code}.txt").useLines { lines ->
            for (line in lines) {
                if (seen >= TOP_WORDS) break
                val w = line.substringBefore(' ')
                if (w.isEmpty()) continue
                seen++
                for (c in w.lowercase(lang.locale).toSet()) {
                    if (c.isLetter()) counts[c] = (counts[c] ?: 0) + 1
                }
            }
        }
        return counts.filterValues { it >= MIN_WORDS }.keys
    }

    @Test
    fun `every letter of every language is on its own keyboard`() {
        val missing = StringBuilder()
        for (lang in Languages.all) {
            val gaps = (alphabet(lang) - reachable(lang)).sorted()
            if (gaps.isNotEmpty()) missing.append("  ${lang.code}: ${gaps.joinToString(" ")}; ")
        }
        assertTrue(
            "letters these languages write with that their layout cannot " +
                "produce, on a key or in a popup: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `how many of its own letters each language keeps behind a long press`() {
        val out = StringBuilder("letters of the alphabet not on a key of their own:")
        for (lang in Languages.all) {
            val gaps = (alphabet(lang) - onKeys(lang)).sorted()
            out.append("%n    %-3s %s".format(lang.code, if (gaps.isEmpty()) "-" else
                gaps.joinToString("")))
        }
        println(out)
        // Not a threshold, a floor: three languages have none, and if that
        // became none at all the survey would be measuring nothing.
        val clean = Languages.all.count { (alphabet(it) - onKeys(it)).isEmpty() }
        assertTrue("no language has its whole alphabet on keys any more", clean >= 1)
    }

    @Test
    fun `the alphabets came out looking like alphabets`() {
        // Guards the guard. If the dictionary parsing broke, every alphabet
        // would be empty and the test above would pass by measuring nothing.
        for (lang in Languages.all) {
            val n = alphabet(lang).size
            assertTrue(
                "${lang.code} derived an alphabet of $n letters, which is not one",
                n in 20..70
            )
        }
    }

    private companion object {
        const val TOP_WORDS = 20000
        const val MIN_WORDS = 5
    }
}
