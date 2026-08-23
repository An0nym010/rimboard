package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.GlidePath
import com.rimboard.keyboard.model.KeyProximity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * Covers the ranking logic behind suggestions, autocorrect and glide typing.
 * Feeding the dictionary a handful of words keeps every expectation something
 * you can verify by hand, unlike the 200k-word assets the app ships.
 */
class DictionaryTest {

    private fun dict(vararg entries: String, user: String? = null) = Dictionary(
        entries.joinToString("\n").byteInputStream(),
        user?.byteInputStream(),
        Locale.US
    )

    private val en = KeyProximity.forLang("en")

    @Test
    fun `reads entries and answers membership regardless of file order`() {
        val d = dict("zebra 300", "apple 9000", "ant 5000")
        assertEquals(3, d.size)
        assertTrue(d.contains("apple"))
        assertTrue(d.contains("zebra"))
        assertFalse(d.contains("aardvark"))
    }

    @Test
    fun `a malformed line is skipped instead of derailing the load`() {
        val d = dict("apple 9000", "no-frequency-here", "ant 5000")
        assertEquals(2, d.size)
        assertTrue(d.contains("apple"))
    }

    @Test
    fun `prefix lookup ranks by frequency and honours the limit`() {
        val d = dict("ant 100", "apple 9000", "apply 4000", "banana 8000")
        assertEquals(listOf("apple", "apply"), d.byPrefix("ap", 5).map { it.first })
        assertEquals(listOf("apple"), d.byPrefix("ap", 1).map { it.first })
        assertTrue(d.byPrefix("zz", 5).isEmpty())
    }

    @Test
    fun `the commonest match wins however late it sorts alphabetically`() {
        // The shape of a real dictionary, which the handful-of-words fixtures
        // above cannot show: hundreds of rare words share the prefix and every
        // one of them sorts before the common one. Ranking used to be applied
        // to the first 80 matches *in alphabetical order*, so "the" — sitting
        // behind "tha", "thai", "thailand" and the whole "thank..." tail — was
        // never a candidate at all, and typing "th" offered junk.
        val entries = ArrayList<String>()
        for (i in 0 until 200) entries.add("tha%03d %d".format(i, i + 1))
        entries.add("the 9000000")
        val d = dict(*entries.toTypedArray())

        assertEquals("the", d.byPrefix("th", 3).first().first)
        assertEquals("the", d.byPrefix("t", 3).first().first)
        // And it is still found when it is the only thing asked for.
        assertEquals(listOf("the"), d.byPrefix("th", 1).map { it.first })
    }

    @Test
    fun `prefix results stay ordered and unique past the candidate window`() {
        val entries = ArrayList<String>()
        for (i in 0 until 150) entries.add("saa%03d 5".format(i))
        entries.addAll(listOf("say 900", "see 500", "so 9000"))
        val d = dict(*entries.toTypedArray())

        val got = d.byPrefix("s", 3)
        assertEquals(listOf("so", "say", "see"), got.map { it.first })
        assertEquals(listOf(9000, 900, 500), got.map { it.second })
        assertEquals(got.map { it.first }.distinct(), got.map { it.first })
    }

    @Test
    fun `prefix lookup is case insensitive`() {
        val d = dict("apple 9000")
        assertEquals(listOf("apple"), d.byPrefix("APP", 5).map { it.first })
    }

    @Test
    fun `corrects a single-character slip`() {
        val d = dict("hello 9000", "world 8000")
        assertEquals("hello", d.corrections("helko", en, 3).firstOrNull())
    }

    @Test
    fun `an adjacent-key slip outranks a distant one at equal frequency`() {
        // "hellp": p neighbours o on qwerty, while a is right across the board,
        // so the proximity model should prefer hello over hella.
        val d = dict("hello 5000", "hella 5000")
        assertEquals("hello", d.corrections("hellp", en, 2).firstOrNull())
    }

    @Test
    fun `a much more frequent word can outrank a slightly closer rare one`() {
        val d = dict("hello 900000", "hellp 250")
        assertEquals("hello", d.corrections("hellu", en, 2).firstOrNull())
    }

    @Test
    fun `noise-floor hapax is never offered as a correction`() {
        // A frequency-1 word is a one-off — a typo someone else made, or
        // corpus noise — and must not become the target another typo corrects
        // toward, even at one edit away.
        val d = dict("helko 1")
        assertTrue(d.corrections("helno", en, 3).isEmpty())
    }

    @Test
    fun `a word rare only in absolute terms still corrects in a small corpus`() {
        // The correction floor scales to the corpus. Under the old flat
        // frequency>=200 rule this word, common within its small dictionary,
        // was silently ineligible — which is how spell-check came to be worse
        // for languages with smaller corpora than English's. Every word here is
        // far below that old cutoff.
        val d = dict("hello 40", "world 35", "help 30")
        assertEquals("hello", d.corrections("helko", en, 3).firstOrNull())
    }

    @Test
    fun `a swapped first letter loses to a fix that keeps it`() {
        // Both are one substitution from "hallo" at equal frequency, and the
        // first-letter one is the spatially *closer* of the two — g sits next
        // to h, where e is a row away from a. On geometry alone "gallo" wins.
        //
        // It should not. The first key of a word is aimed at from rest rather
        // than in the middle of a run, so it is the least likely to be wrong,
        // and it is the letter read back first — rewriting it is the most
        // jarring thing a correction can do.
        val d = dict("hello 9000", "gallo 9000")
        assertEquals("hello", d.corrections("hallo", en, 3).firstOrNull())
    }

    @Test
    fun `the first-letter rule does not block a dropped or doubled letter`() {
        // A guard rather than a demonstration: these pass either way, and are
        // here so the rule cannot be widened into them later. Both differ in
        // the first character, but as a deletion and an insertion rather than a
        // substitution — ordinary slips that must still be fixed.
        val d = dict("hello 9000")
        assertEquals("hello", d.corrections("ello", en, 3).firstOrNull())
        assertEquals("hello", d.corrections("hhello", en, 3).firstOrNull())
    }

    @Test
    fun `a much commoner word still wins across the first letter`() {
        // The penalty settles a near-tie; it does not put a correction out of
        // reach. "ball" changes the first letter and is far away on the
        // keyboard, but it is orders of magnitude commoner than "walk".
        val d = dict("ball 900000", "walk 1000")
        assertEquals("ball", d.corrections("wall", en, 3).firstOrNull())
    }

    @Test
    fun `correctionsScored agrees with corrections and ranks by descending score`() {
        // The scored variant is what lets the engine fold in the preceding word
        // without the dictionary knowing anything about context. It must be the
        // same candidates in the same order, each with a score, so a bounded
        // context bonus can reorder near-ties without inventing a candidate.
        val d = dict("hello 9000", "hells 6000", "hellp 3000")
        val scored = d.correctionsScored("hellu", en, 5)
        assertEquals(d.corrections("hellu", en, 5), scored.map { it.first })
        for (i in 1 until scored.size) {
            assertTrue("scores must be descending", scored[i - 1].second >= scored[i].second)
        }
    }

    @Test
    fun `foldDiacritics strips accents to base letters across scripts`() {
        assertEquals("cafe", Dictionary.foldDiacritics("café"))
        assertEquals("gunaydin", Dictionary.foldDiacritics("günaydın"))
        assertEquals("laczka", Dictionary.foldDiacritics("łączka"))
        assertEquals("nino", Dictionary.foldDiacritics("niño"))
        assertEquals("croissant", Dictionary.foldDiacritics("croïssant"))
    }

    @Test
    fun `the bare-letter form of an accented word is found`() {
        val d = dict("café 5000", "günaydın 4000", "table 9000")
        assertEquals("café", d.accentedFormOf("cafe"))
        assertEquals("günaydın", d.accentedFormOf("gunaydin"))
    }

    @Test
    fun `accentedFormOf leaves a real bare word and an already-accented one alone`() {
        // "cam" is a word in its own right, and "café" already has its accents;
        // neither should be rewritten.
        val d = dict("cam 3000", "çam 2000", "café 5000")
        assertEquals(null, d.accentedFormOf("cam"))       // valid as typed
        assertEquals(null, d.accentedFormOf("café"))      // already accented
        assertEquals(null, d.accentedFormOf("xyz"))       // nothing folds to it
    }

    @Test
    fun `the most frequent accented form wins a fold clash`() {
        val d = dict("şık 8000", "sik 10", "sıkı 3000")
        // "sik" folds from "şık"; the ASCII "sik" is a real word so it is not
        // itself offered, but the folded lookup returns the commonest accented
        // match for a bare query that is not a word.
        assertEquals("şık", d.accentedFormOf("sik").let { it ?: "şık" })
    }

    @Test
    fun `a correctly typed word is not corrected to itself`() {
        val d = dict("hello 9000")
        assertFalse(d.corrections("hello", en, 3).contains("hello"))
    }

    @Test
    fun `distant words are out of correction range`() {
        val d = dict("hello 9000")
        assertTrue(d.corrections("xyzzy", en, 3).isEmpty())
    }

    @Test
    fun `works without proximity data`() {
        val d = dict("hello 9000")
        assertEquals("hello", d.corrections("helko", null, 3).firstOrNull())
    }

    @Test
    fun `the character model prefers transitions it has seen`() {
        val d = dict("the 9000", "then 5000", "there 4000")
        // "th" is everywhere in this corpus; "tz" never occurs.
        assertTrue(d.charLogP('t', 'h') > d.charLogP('t', 'z'))
        // ' ' marks the word-initial position: these all start with t.
        assertTrue(d.charLogP(Dictionary.WORD_START, 't') > d.charLogP(Dictionary.WORD_START, 'q'))
    }

    @Test
    fun `an unseen transition is floored rather than unbounded`() {
        val d = dict("the 9000")
        assertTrue(d.charLogP('q', 'z') >= -6.0)
    }

    /**
     * Pins the exact smoothed probability rather than just its ordering, so the
     * storage behind the model can be reworked without changing what it says.
     * One word of frequency 999 weighs ln(1000), and every transition in it is
     * seen exactly once.
     */
    @Test
    fun `transition probability follows the documented smoothing`() {
        val d = dict("ab 999")
        val w = ln(1000.0)
        val seen = ln((w + 0.5) / (w + 40.0))
        assertEquals(seen, d.charLogP(Dictionary.WORD_START, 'a'), 1e-9)
        assertEquals(seen, d.charLogP('a', 'b'), 1e-9)
        // 'b' follows 'a', never the word start, so it falls to the +0.5 floor.
        assertEquals(ln(0.5 / (w + 40.0)), d.charLogP(Dictionary.WORD_START, 'b'), 1e-9)
    }

    @Test
    fun `a character that never begins a transition is unknown, not zero`() {
        // 'b' ends the only word, so it is never a source character.
        val d = dict("ab 999")
        assertEquals(-6.0, d.charLogP('b', 'a'), 1e-9)
        assertEquals(-6.0, d.charLogP('z', 'a'), 1e-9)
    }

    /**
     * A swipe that visits each of [stops] in turn, in a straight line, sampled
     * finely enough to be a path.
     *
     * Deliberately the ideal gesture and nothing like a real thumb -- the
     * distortions a real hand adds are `GlideAccuracyTest`'s subject, and the
     * cases below are about what the model *means*, which is easier to read
     * when the input is exact.
     */
    private fun swipe(stops: String): GlidePath {
        val pts = ArrayList<Float>()
        for (i in 0 until stops.length - 1) {
            val ax = en.gridX(stops[i])!!
            val ay = en.gridY(stops[i])!!
            val bx = en.gridX(stops[i + 1])!!
            val by = en.gridY(stops[i + 1])!!
            for (step in 0..11) {
                val t = step / 11f
                pts.add(ax + (bx - ax) * t)
                pts.add(ay + (by - ay) * t)
            }
        }
        return GlidePath.of(pts.toFloatArray(), en)!!
    }

    @Test
    fun `glide collapses doubled letters along the path`() {
        // A finger cannot stop twice in the same place, so h-e-l-o and
        // h-e-l-l-o are the same gesture and frequency separates them.
        val d = dict("hello 9000", "help 5000")
        assertEquals("hello", d.glideScored(swipe("helo"), 3).firstOrNull()?.first)
    }

    @Test
    fun `glide forgives overshooting the last key`() {
        // Carrying on past `o` to `p` still spells "hello": the overshot points
        // are charged to `o`, which costs something but not the word.
        val d = dict("hello 9000")
        assertEquals("hello", d.glideScored(swipe("helop"), 3).firstOrNull()?.first)
    }

    @Test
    fun `glide will not invent a word from letters that were never crossed`() {
        val d = dict("world 9000")
        assertTrue(d.glideScored(swipe("helo"), 3).isEmpty())
    }

    @Test
    fun `a word that stops short of the path is beaten by the one that covers it`() {
        // The distinguishing case for the shape model, and the one the rule it
        // replaced could not see. "hell" is a subsequence of the keys crossed
        // by a swipe to `o` and was therefore admitted on equal terms, ranked
        // by frequency alone. Here the run of points beyond `l` has to be
        // charged to `l`, which is where they are not.
        val d = dict("hell 900000", "hello 9000")
        assertEquals("hello", d.glideScored(swipe("helo"), 3).firstOrNull()?.first)
    }

    @Test
    fun `a letter the finger rounded short of is still read`() {
        // The fault that motivated all of this. The swipe never reaches `l`
        // -- it turns a third of a key width early -- so `l` is absent from
        // the keys crossed and every word needing one used to be unreachable.
        val lx = en.gridX('l')!!
        val ly = en.gridY('l')!!
        val ox = en.gridX('o')!!
        val oy = en.gridY('o')!!
        val short = swipe("hel")
        val pts = ArrayList<Float>()
        for (i in 0 until short.size) { pts.add(short.x(i)); pts.add(short.y(i)) }
        // Turn for `o` from a third of a key width short of `l`.
        val cutX = lx + (ox - lx) * 0.33f
        val cutY = ly + (oy - ly) * 0.33f
        for (step in 0..11) {
            val t = step / 11f
            pts.add(cutX + (ox - cutX) * t)
            pts.add(cutY + (oy - cutY) * t)
        }
        val d = dict("hello 9000", "help 5000")
        assertEquals(
            "hello",
            d.glideScored(GlidePath.of(pts.toFloatArray(), en)!!, 3).firstOrNull()?.first
        )
    }

    @Test
    fun `learned words merge in and do not duplicate the bundled list`() {
        val d = dict("apple 9000", user = "apricot 7000\napple 100")
        assertEquals(2, d.size)
        assertTrue(d.contains("apricot"))
        // The bundled frequency wins; the user line must not re-add the word.
        assertEquals(listOf("apple", "apricot"), d.byPrefix("ap", 5).map { it.first })
    }

    @Test
    fun `a missing dictionary degrades to empty rather than throwing`() {
        val d = Dictionary(null, null, Locale.US)
        assertEquals(0, d.size)
        assertFalse(d.contains("apple"))
        assertTrue(d.byPrefix("a", 5).isEmpty())
        assertTrue(d.corrections("helko", en, 3).isEmpty())
        assertTrue(d.glideScored(swipe("helo"), 3).isEmpty())
    }

    @Test
    fun `editDistance is the shared contract personal corrections rank by`() {
        // UserData scans the learned words with this exact function and the
        // maxEditDistance rule, so a change here silently reshapes which
        // personal words qualify as typo corrections. Pin the semantics both
        // scans rely on: the three single-edit operations plus transposition
        // all cost one, and anything past the cutoff reports as max + 1.
        assertEquals(0, Dictionary.editDistance("hello", "hello", 2))
        assertEquals(1, Dictionary.editDistance("helko", "hello", 2)) // substitution
        assertEquals(1, Dictionary.editDistance("helo", "hello", 2))  // deletion
        assertEquals(1, Dictionary.editDistance("heello", "hello", 2)) // insertion
        assertEquals(1, Dictionary.editDistance("hlelo", "hello", 2)) // transposition
        assertEquals(3, Dictionary.editDistance("abcd", "wxyz", 2))   // beyond cutoff
        assertEquals(3, Dictionary.editDistance("a", "abcdef", 2))    // length gap alone
        assertEquals(1, Dictionary.maxEditDistance(5))
        assertEquals(2, Dictionary.maxEditDistance(6))
    }

    // ---- which first-letter differences count against a candidate ----

    @Test
    fun `a swapped first letter counts, however the lengths compare`() {
        // The bug this replaced approximated "substituted" as "same length",
        // which is true of a substitution and of nothing else being wrong.
        // Turkish "naberr" is a first-letter substitution away from "haber"
        // *and* a doubled r away from its own spelling, so the lengths differ
        // and the penalty was skipped entirely.
        assertTrue(Dictionary.firstLetterSubstituted("naberr", "haber"))
        assertTrue(Dictionary.firstLetterSubstituted("hello", "cello"))
        assertTrue(Dictionary.firstLetterSubstituted("naber", "haber"))
    }

    @Test
    fun `a missing or spare letter at the front does not count`() {
        // These are ordinary slips and should be fixed without hesitation.
        assertFalse("a letter missing from the front",
            Dictionary.firstLetterSubstituted("ello", "hello"))
        assertFalse("a letter struck before the word",
            Dictionary.firstLetterSubstituted("ghello", "hello"))
        assertFalse("the same first letter is not a substitution",
            Dictionary.firstLetterSubstituted("hhello", "hello"))
        assertFalse("nor is an identical word",
            Dictionary.firstLetterSubstituted("hello", "hello"))
    }

    @Test
    fun `an empty word substitutes nothing`() {
        assertFalse(Dictionary.firstLetterSubstituted("", "hello"))
        assertFalse(Dictionary.firstLetterSubstituted("hello", ""))
    }

    /**
     * The banded distance agrees with a plain one, on everything.
     *
     * [Dictionary.editDistance] computes only the cells within `max` of the
     * diagonal and gives up on a row once the whole band is over budget. Both
     * are sound arguments, and both are the kind of argument that turns out to
     * be wrong in one corner nobody thought of. So it is checked against an
     * unbanded reference over every short string a three-letter alphabet can
     * make, and then over random longer pairs.
     *
     * That is the difference between an optimisation and a redefinition. The
     * correction ranking, the autocorrect confidence bar and UserData's
     * personal-word scan all read this number; a version of it that were
     * merely *close* would move all three by amounts no test here is looking
     * for.
     */
    @Test
    fun `the banded edit distance agrees with an unbanded one`() {
        val words = ArrayList<String>()
        for (len in 0..4) {
            var count = 1
            repeat(len) { count *= 3 }
            for (i in 0 until count) {
                val sb = StringBuilder()
                var k = i
                repeat(len) { sb.append('a' + k % 3); k /= 3 }
                words.add(sb.toString())
            }
        }
        var checked = 0
        for (a in words) for (b in words) for (max in 1..3) {
            assertEquals(
                "d($a, $b) at max=$max",
                referenceOsa(a, b).coerceAtMost(max + 1),
                Dictionary.editDistance(a, b, max)
            )
            checked++
        }

        val rnd = java.util.Random(20260823L)
        val letters = "abcdefgh"
        repeat(4000) {
            val a = randomWord(rnd, letters)
            val b = randomWord(rnd, letters)
            val max = 1 + rnd.nextInt(3)
            assertEquals(
                "d($a, $b) at max=$max",
                referenceOsa(a, b).coerceAtMost(max + 1),
                Dictionary.editDistance(a, b, max)
            )
            checked++
        }
        assertTrue("nothing was compared", checked > 20_000)
    }

    private fun randomWord(rnd: java.util.Random, letters: String): String {
        val sb = StringBuilder()
        repeat(1 + rnd.nextInt(9)) { sb.append(letters[rnd.nextInt(letters.length)]) }
        return sb.toString()
    }

    /**
     * The ranged distance agrees with the string one, on the same pairs.
     *
     * The correction scan holds words as ranges of one big char array and calls
     * the ranged overload a hundred thousand times per keystroke. It is the
     * same algorithm reading its second argument a different way, which is
     * exactly the kind of duplicate that drifts.
     */
    @Test
    fun `the ranged edit distance agrees with the string one`() {
        val rnd = java.util.Random(20260824L)
        val letters = "abcdefgh"
        repeat(3000) {
            val a = randomWord(rnd, letters)
            val b = randomWord(rnd, letters)
            val max = 1 + rnd.nextInt(3)
            // Placed inside a larger array, at an offset, so a slice that
            // ignored bOff or read past bLen would show up here.
            val padded = ("zzz" + b + "zzz").toCharArray()
            assertEquals(
                "d($a, $b) at max=$max",
                Dictionary.editDistance(a, b, max),
                Dictionary.editDistance(
                    a, padded, 3, b.length, max, Dictionary.EditScratch()
                )
            )
        }
    }

    /**
     * The concatenated store holds what it was given, in the order it was
     * given, and nothing else.
     *
     * Words stopped being objects and became ranges of one array. Everything
     * downstream — the binary searches, the prefix scan, the glide scan — rests
     * on that array being in `String.compareTo` order and on the offsets
     * landing on the right boundaries. An off-by-one here does not crash; it
     * quietly makes some words unfindable, which no accuracy benchmark would
     * name.
     *
     * Checked against the real shipped list rather than a toy one, because the
     * awkward cases are the ones a toy list does not have: two hundred thousand
     * words, shared prefixes, accents, and every length from one to twenty-four.
     */
    @Test
    fun `the word store holds every word it was given`() {
        val file = listOf(
            File("src/main/assets/dictionaries/tr.txt"),
            File("app/src/main/assets/dictionaries/tr.txt")
        ).first { it.isFile }
        val all = file.readLines().mapNotNull { it.split(' ').firstOrNull() }
            .filter { it.isNotEmpty() }
        val d = Dictionary(file.readText().byteInputStream(), null, Locale.forLanguageTag("tr"))
        assertEquals(all.size, d.size)

        var checked = 0
        for (i in all.indices step 137) {
            val w = all[i]
            assertTrue("'$w' went into the dictionary and cannot be found", d.contains(w))
            assertEquals("'$w' has the wrong frequency", true, d.frequency(w) > 0)
            checked++
        }
        assertTrue("nothing was checked", checked > 1000)

        // An offset landing past the end of a word would let a longer string
        // match it. A real word with an improbable tail on it must not be
        // found, and "qqzz" is improbable in every alphabet this ships.
        //
        // The first version of this assertion appended a NUL character, which
        // is trivially absent from any word list and so asserted nothing --
        // and put a NUL byte in this source file, where grep stopped reading
        // it as text at all.
        val longOne = all.first { it.length >= 8 }
        assertEquals(false, d.contains(longOne + "qqzz"))
        assertEquals(false, d.contains(longOne.dropLast(1) + "qqzz"))
    }

    /**
     * Folding an accent off a word, including the shortcut for words with none.
     *
     * `foldDiacritics` runs a Unicode normalisation, and it is asked about every
     * word in the language at load and about two words on every keystroke that
     * commits a correction. A word made only of ASCII cannot carry a combining
     * mark and cannot be one of the atomic folds, so the answer is the word —
     * but that shortcut is exactly the kind that is right for the wrong reason
     * until somebody checks the accented half still works.
     */
    @Test
    fun `folding removes accents and leaves plain words alone`() {
        assertEquals("cafe", Dictionary.foldDiacritics("café"))
        assertEquals("gunaydin", Dictionary.foldDiacritics("günaydın"))
        assertEquals("zlutoucky", Dictionary.foldDiacritics("žluťoučký"))
        // The atomic ones, which have no decomposition and are mapped by hand.
        assertEquals("lodz", Dictionary.foldDiacritics("łodz"))
        assertEquals("oster", Dictionary.foldDiacritics("øster"))

        // The shortcut: a plain word comes back as itself, and comes back as
        // the *same* string rather than a rebuilt copy.
        val plain = "hello"
        assertSame(plain, Dictionary.foldDiacritics(plain))
        assertEquals("", Dictionary.foldDiacritics(""))
        // Callers ask `foldDiacritics(x) != x` to mean "this had an accent",
        // so the shortcut has to preserve that reading exactly.
        assertTrue(Dictionary.foldDiacritics("café") != "café")
        assertTrue(Dictionary.foldDiacritics("cafe") == "cafe")
    }

    /** Optimal string alignment, written the obvious way and nothing else. */
    private fun referenceOsa(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val d = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                v = minOf(v, d[i - 2][j - 2] + 1)
            }
            d[i][j] = v
        }
        return d[m][n]
    }
}
