package com.rimboard.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Two thirds of the emoji panel could not be found by searching it.
 *
 * The search index is a hand-written asset: 420 keywords, reaching 527 of the
 * 1,564 emoji the palette shows. `flagKeywords` closed the flags by reading
 * the ISO code the flag itself spells. This closes most of the rest the same
 * way, from the same principle — **the platform already knows the names, so
 * none are shipped**.
 *
 * `Character.getName` returns the Unicode Character Database's own name for a
 * code point: "SALUTING FACE", "JACK-O-LANTERN", "FACE WITH ROLLING EYES".
 * Split on spaces and hyphens, because the panel matches a keyword by prefix,
 * that is 1,346 keywords over the palette — and of the 939 the previously
 * unreachable emoji contribute, 693 name exactly one emoji.
 *
 * Verified on the device before it was written down: Android's implementation
 * has the table. Searching "lantern" on a Redmi Note 8 running Android 16
 * returns 🎃 and 🏮, neither of which was reachable by any query before.
 *
 * ## What it does not cover, and why
 *
 * Only emoji that are a single code point once variation selectors and joiners
 * are set aside. A Unicode name belongs to a code point, and no part of a
 * joined sequence is a fair name for the whole: calling 👩‍🚀 "WOMAN" because
 * that is what it starts with would be worse than leaving it unnamed. That
 * leaves the joined sequences, the keycaps and the skin tones out.
 */
class EmojiNameSearchTest {

    private fun palette(): List<String> {
        val src = listOf(
            File("src/main/java/com/rimboard/keyboard/ui/EmojiData.kt"),
            File("app/src/main/java/com/rimboard/keyboard/ui/EmojiData.kt")
        ).first { it.exists() }.readText()
        return Regex("""c\("[^"]+",\s*\n\s*"([^"]*)"\)""")
            .findAll(src)
            .flatMap { it.groupValues[1].split(" ").asSequence() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    /** What the shipped asset reaches on its own. */
    private fun fromAsset(): Set<String> {
        val f = listOf(
            File("src/main/assets/emoji/search_en.txt"),
            File("app/src/main/assets/emoji/search_en.txt")
        ).first { it.exists() }
        val out = HashSet<String>()
        for (line in f.readLines()) {
            val t = line.indexOf('\t')
            if (t > 0) out.addAll(line.substring(t + 1).trim().split(" ").filter { it.isNotBlank() })
        }
        return out
    }

    private fun reachable(): Set<String> {
        val out = HashSet(fromAsset())
        out.addAll(EmojiData.flagKeywords(Locale.ENGLISH).values.flatten())
        out.addAll(EmojiData.unicodeNameKeywords().values.flatten())
        return out
    }

    /**
     * Measured 2026-09-01: 527 of 1,564 from the asset alone (33.7%), 1,541
     * with the two derivations (98.5%). The 23 left are the joined sequences,
     * the keycaps and the skin tones, which have no name of their own.
     */
    @Test
    fun `most of the palette can be found by searching for it`() {
        val pal = palette()
        val asset = pal.count { it in fromAsset() }
        val all = pal.count { it in reachable() }
        println(
            "palette ${pal.size}: asset reaches $asset (%.1f%%), with the derivations $all (%.1f%%)"
                .format(100.0 * asset / pal.size, 100.0 * all / pal.size)
        )
        assertTrue("the palette shrank; re-measure before trusting this", pal.size >= 1500)
        // The asset is where it was; this test is about what the derivations
        // add, and it fails if they stop adding it.
        assertTrue(
            "the derived keywords reach only $all of ${pal.size}, which is no " +
                "better than the shipped asset's $asset",
            all >= asset + 700
        )
    }

    @Test
    fun `emoji nobody had written a keyword for are findable by their own name`() {
        val kw = EmojiData.unicodeNameKeywords()
        val missing = listOf(
            "saluting" to "🫡", "lantern" to "🎃", "handshake" to "🤝",
            "cowboy" to "🤠", "relieved" to "😌", "goblin" to "👺",
            "melting" to "🫠", "perch" to null, "weary" to "😩"
        ).filter { (word, emoji) ->
            emoji != null && kw[word]?.contains(emoji) != true
        }
        assertEquals(
            "an emoji the hand-written index never named cannot be found by the " +
                "name Unicode gives it.",
            emptyList<Pair<String, String?>>(), missing
        )
    }

    /**
     * A one-letter query must not fill with whatever a grammar word collected.
     *
     * Unicode names are phrases, so splitting them yields "with" in 77 of them
     * and "and" in 28. The panel sorts matches by keyword length, so both would
     * outrank real words for the queries "w" and "a". They are the only two
     * excluded, and the exclusion is about English rather than about emoji.
     */
    @Test
    fun `grammar words do not become keywords`() {
        val kw = EmojiData.unicodeNameKeywords()
        assertEquals("\"with\" is not something anybody searches for", null, kw["with"])
        assertEquals("\"and\" is not either", null, kw["and"])
        // ...while the descriptive words that share the sentence stay.
        for (w in listOf("face", "arrow", "hand", "circle", "square", "symbol")) {
            assertTrue("\"$w\" is a fair thing to search for and should be a keyword", kw.containsKey(w))
        }
    }

    /** Nothing may name an emoji the panel does not show. */
    @Test
    fun `it only names emoji the palette actually has`() {
        val shown = palette().toSet()
        val stray = EmojiData.unicodeNameKeywords().values.flatten().distinct()
            .filterNot { it in shown }
        assertEquals("a keyword points at an emoji the panel never shows", emptyList<String>(), stray)
    }

    /**
     * The derivation is decoration unless the panel merges it.
     *
     * `EmojiView` is a View and cannot be instantiated here, so the wiring is
     * checked by reading it — the same way `AutoSpaceContextTest` checks that
     * the service asks before it inserts a space. Both derivations have to be
     * in the index builder; either one missing is the feature silently not
     * shipping.
     */
    @Test
    fun `the panel merges both derivations into its index`() {
        val src = listOf(
            File("src/main/java/com/rimboard/keyboard/ui/EmojiView.kt"),
            File("app/src/main/java/com/rimboard/keyboard/ui/EmojiView.kt")
        ).first { it.exists() }.readText()
        val at = src.indexOf("private fun searchIndex(")
        assertTrue("searchIndex() is gone; find what replaced it", at >= 0)
        val body = src.substring(at, minOf(src.length, at + 2500))
        assertTrue(
            "the emoji search index no longer merges EmojiData.flagKeywords, so " +
                "the flags are unsearchable again",
            // The call, not the name: the comment above it says
            // "[EmojiData.flagKeywords]" and would satisfy a looser test.
            body.contains("EmojiData.flagKeywords(")
        )
        assertTrue(
            "the emoji search index no longer merges EmojiData.unicodeNameKeywords, " +
                "so two thirds of the palette is unsearchable again",
            body.contains("EmojiData.unicodeNameKeywords()")
        )
    }

    /**
     * It runs when the panel's search index is built, once per language, so it
     * has to be cheap enough not to be felt. Measured at about 15 ms on the
     * JVM for the whole palette; the bar here is loose because a test machine
     * under load is not a phone.
     */
    @Test
    fun `building the keywords is not slow`() {
        EmojiData.unicodeNameKeywords()
        val t0 = System.nanoTime()
        val kw = EmojiData.unicodeNameKeywords()
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("unicodeNameKeywords: ${kw.size} keywords in %.1f ms".format(ms))
        assertTrue("naming the palette took %.0f ms, which would be felt".format(ms), ms < 400.0)
    }
}
