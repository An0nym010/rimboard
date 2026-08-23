package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The context-aware ranking, tested end to end through the engine.
 *
 * This is the logic the previous change shipped without a test, because the
 * engine took a Context to read its assets and could not run on a plain JVM.
 * With the asset seam it can: a handful of in-memory words stands in for the
 * shipping dictionary, and every ranking claim here is verifiable by hand.
 */
class SuggestionEngineTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    /** Builds an engine over the given in-memory assets. */
    private fun engine(assets: Map<String, String>): SuggestionEngine =
        SuggestionEngine.forTesting(userData) { path ->
            assets[path]?.byteInputStream()
        }

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private val en = Locale.ENGLISH

    // ---- completion ranking ----

    @Test
    fun `with no preceding word completions fall back to raw frequency`() {
        // "and" is commoner than "am", so with nothing to go on it leads.
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000\nan 2000"
        )
        val out = engine(assets).suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertEquals("a", out.first())              // slot 0 is always verbatim
        assertEquals("and", out.drop(1).first())    // then by frequency
    }

    @Test
    fun `a correction survives the filters eating the top of the list`() {
        // The dictionary is asked for candidates and the answer is then
        // filtered — blocked words, offensive words, the corpus's
        // apostrophe-less contractions. Asking for exactly as many as are
        // wanted meant a dropped candidate left a hole with nothing behind it,
        // and the strip offered no correction at all even though the
        // dictionary had a perfectly good one further down.
        //
        // Five high-frequency neighbours of "hallo", all blocked, and one real
        // answer ranked beneath them.
        val blocked = listOf("hallx", "hally", "hallz", "hallw", "hallv")
        val assets = mapOf(
            "dictionaries/en.txt" to
                (blocked.joinToString("\n") { "$it 900000" } + "\nhello 5000")
        )
        val e = engine(assets)
        blocked.forEach { userData.blockWord(it) }

        assertEquals("hello", e.correctionCandidates("hallo", "en", en).firstOrNull())
    }

    /**
     * Loads the prediction model, which in the app is [SuggestionEngine.warm]'s
     * job and used to happen by accident here.
     *
     * `suggestionsFor` runs on the UI thread once per keystroke, so it no
     * longer loads the model itself — it ranks without context until the
     * warm thread has one. A test that wants to assert what context does must
     * therefore say that there is some, and this is the synchronous door to
     * it. Without the call these tests do not fail loudly: two of them assert
     * an ordering and go red, but the third asserts an *absence* and would
     * quietly pass for the wrong reason, proving nothing.
     */
    private fun SuggestionEngine.primeModel(lang: String, locale: Locale) {
        predictions("", "i", lang, locale, 1)
    }

    @Test
    fun `the preceding word lifts a contextual completion over a commoner one`() {
        // Same words, but now "I" precedes them and the bundled model says "I"
        // is followed by "am". "am" should overtake the commoner "and".
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000\nan 2000",
            "predictions/en.txt" to "i\tam are was"
        )
        val eng = engine(assets).apply { primeModel("en", en) }
        val out = eng.suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false,
            prevWord = "i"
        ).items
        assertEquals("a", out.first())
        assertEquals(
            "context should lift 'am' above the commoner 'and'",
            "am", out.drop(1).first()
        )
    }

    @Test
    fun `context only reorders real completions, never injects an unrelated word`() {
        // "am" is predicted after "I", but the user is typing "th" — "am" is
        // not a completion of that and must not appear.
        val assets = mapOf(
            "dictionaries/en.txt" to "the 9000\nthis 4000\nam 3000",
            "predictions/en.txt" to "i\tam are"
        )
        // Primed, or this passes for the wrong reason: with no model loaded
        // there is no context at all, so "am" would be absent because nothing
        // was consulted rather than because the rule held.
        val out = engine(assets).apply { primeModel("en", en) }.suggestionsFor(
            "th", "en", en, allowAutocorrect = false, personalized = false,
            prevWord = "i"
        ).items
        assertTrue("'am' must not be injected", out.none { it.equals("am", true) })
        assertTrue(out.any { it.equals("the", true) })
    }

    // ---- correction ranking ----

    @Test
    fun `context breaks a correction tie the dictionary cannot`() {
        // "wan" is one edit from both "was" and "war". Frequencies are close
        // enough that context decides: after "the", the model predicts "war".
        val assets = mapOf(
            "dictionaries/en.txt" to "was 5000\nwar 4000",
            "predictions/en.txt" to "the\twar world way"
        )
        val eng = engine(assets)
        val withContext = eng.correctionCandidates(
            "wan", "en", en, limit = 1,
            contextRank = mapOf("war" to 0, "world" to 1, "way" to 2)
        )
        assertEquals(listOf("war"), withContext)
        // Without the context the frequency ordering stands.
        val without = eng.correctionCandidates("wan", "en", en, limit = 1)
        assertEquals(listOf("was"), without)
    }

    @Test
    fun `the word committed on a separator is the word the strip put in bold`() {
        // Two functions answering one question from different evidence.
        // suggestionsFor ranked with the preceding word; correctionFor had no
        // way to be told about it at all, so the strip bolded what context
        // preferred and the space bar committed what raw frequency preferred.
        // The bold is a promise about what the separator is going to do.
        //
        // The fixture has to be one the channel model genuinely cannot
        // separate, or the spatial term settles it before context is
        // consulted: "g" sits between "f" and "h", so "gate" is exactly one
        // adjacent-key slip from both "fate" and "hate" and they cost the
        // same. Frequency then favours "fate"; after "the", the model predicts
        // "hate".
        val assets = mapOf(
            "dictionaries/en.txt" to "fate 5000\nhate 4000",
            "predictions/en.txt" to "the\thate other things"
        )
        val eng = engine(assets).apply { primeModel("en", en) }

        val res = eng.suggestionsFor(
            "gate", "en", en, allowAutocorrect = true, personalized = false,
            prevWord = "the"
        )
        val bolded = res.items.getOrNull(res.autocorrectIndex)
        val committed = eng.correctionFor("gate", "en", en, prevWord = "the")

        assertEquals("context should have chosen this", "hate", committed)
        assertEquals("and the strip should be showing the same word", bolded, committed)

        // Without a preceding word both fall back to frequency, and still
        // agree with each other.
        val noCtxRes = eng.suggestionsFor(
            "gate", "en", en, allowAutocorrect = true, personalized = false
        )
        assertEquals("fate", eng.correctionFor("gate", "en", en))
        assertEquals(
            noCtxRes.items.getOrNull(noCtxRes.autocorrectIndex),
            eng.correctionFor("gate", "en", en)
        )
    }

    @Test
    fun `a strong adjacent-key fix is not overturned by weak context`() {
        // "helko" is an adjacent-key slip for "hello" (l/k neighbours). Even
        // if context nudges "hells", the spatial evidence must win — the bonus
        // is a tie-break, not an override.
        val assets = mapOf("dictionaries/en.txt" to "hello 9000\nhells 200")
        val out = engine(assets).correctionCandidates(
            "helko", "en", en, limit = 1,
            contextRank = mapOf("hells" to 0)
        )
        assertEquals(listOf("hello"), out)
    }

    // ---- the learned-data path, now reachable without a device ----

    @Test
    fun `a learned word is suggested once past the use threshold`() {
        val assets = mapOf("dictionaries/en.txt" to "apple 9000")
        val eng = engine(assets)
        repeat(3) { userData.learnWord("appleseed") }  // three uses
        val out = eng.suggestionsFor(
            "app", "en", en, allowAutocorrect = false, personalized = true
        ).items
        assertTrue("learned word should appear", out.any { it.equals("appleseed", true) })
    }

    @Test
    fun `a word learned only once stays below the suggestion threshold`() {
        val assets = mapOf("dictionaries/en.txt" to "apple 9000")
        val eng = engine(assets)
        userData.learnWord("appleseed")  // one use only
        val out = eng.suggestionsFor(
            "app", "en", en, allowAutocorrect = false, personalized = true
        ).items
        assertTrue("one use is not enough", out.none { it.equals("appleseed", true) })
    }

    @Test
    fun `learned next-word context predicts before any bundled model exists`() {
        // No predictions asset at all; the engine must still predict from what
        // the user has been observed typing.
        val eng = engine(mapOf("dictionaries/en.txt" to "you 9000"))
        repeat(4) { userData.recordNgram("", "see", "you") }
        val preds = eng.predictions("", "see", "en", en, 3)
        assertEquals("you", preds.first())
    }

    @Test
    fun `predictions with no evidence anywhere are empty rather than wrong`() {
        val eng = engine(mapOf("dictionaries/en.txt" to "you 9000"))
        assertTrue(eng.predictions("", "nonesuch", "en", en, 3).isEmpty())
    }

    // ---- contractions: overriding the apostrophe-stripped corpus ----

    /** The corpus reality: the bare form is in the dictionary, the real one is not. */
    private val enWithBareForms =
        "dont 9523\ncant 3936\nwont 1200\nwere 8000\nits 15000\ndone 5000"

    @Test
    fun `an unambiguous contraction auto-commits over the corpus bare form`() {
        // "dont" is in the dictionary with a huge frequency, so the ordinary
        // path treats it as a correctly-spelled word and never touches it.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals("don't", eng.correctionFor("dont", "en", en))
    }

    @Test
    fun `contraction casing follows what was typed`() {
        val eng = engine(mapOf("dictionaries/en.txt" to "im 500"))
        assertEquals("I'm", eng.correctionFor("im", "en", en))
        assertEquals("I'm", eng.correctionFor("Im", "en", en))
    }

    @Test
    fun `an ambiguous contraction is offered but never auto-committed`() {
        // "cant" and "wont" are real words too, so the apostrophe form is a
        // tap-only suggestion — committing it on space would fight anyone who
        // meant the noun or "accustomed".
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals("cant", eng.correctionFor("cant", "en", en) ?: "cant")
        val strip = eng.suggestionsFor(
            "cant", "en", en, allowAutocorrect = true, personalized = false
        )
        assertTrue("can't should be offered", strip.items.any { it == "can't" })
        assertEquals("but never as the autocorrect", -1, strip.autocorrectIndex)
    }

    @Test
    fun `a common word that is also a bare contraction is left alone`() {
        // "its", "were" and "well" are usually correct as typed; the keyboard
        // must not turn "its" into "it's". They are in neither contraction list.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        assertEquals(null, eng.correctionFor("its", "en", en))
        assertEquals(null, eng.correctionFor("were", "en", en))
    }

    @Test
    fun `the apostrophe-less form is not offered as a completion`() {
        // Typing "don" must not suggest "dont" from the corpus; the contraction
        // path owns that word now.
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        val out = eng.suggestionsFor(
            "don", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("'dont' must not be a completion", out.none { it == "dont" })
        assertTrue("'done' still may be", out.any { it == "done" })
    }

    @Test
    fun `the contraction rides the front chip when the word is fully typed`() {
        val eng = engine(mapOf("dictionaries/en.txt" to enWithBareForms))
        val out = eng.suggestionsFor(
            "youre", "en", en, allowAutocorrect = true, personalized = false
        )
        assertEquals("youre", out.items.first())          // verbatim
        assertTrue(out.items.any { it == "you're" })
        assertEquals("you're", out.items[out.autocorrectIndex])
    }

    // ---- diacritic restoration ----

    @Test
    fun `typing bare letters offers the accented word`() {
        val tr = Locale.forLanguageTag("tr")
        val eng = engine(mapOf("dictionaries/tr.txt" to "günaydın 5000\naraba 4000"))
        assertEquals("günaydın", eng.correctionFor("gunaydin", "tr", tr))
    }

    @Test
    fun `diacritic restoration keeps the typed capitalization`() {
        val fr = Locale.FRENCH
        val eng = engine(mapOf("dictionaries/fr.txt" to "café 5000"))
        assertEquals("café", eng.correctionFor("cafe", "fr", fr))
        assertEquals("Café", eng.correctionFor("Cafe", "fr", fr))
    }

    @Test
    fun `a bare word that is valid in its own right is not re-accented`() {
        // "cam" (glass) is a real Turkish word; it must not be turned into
        // "çam" (pine) just because that also exists.
        val tr = Locale.forLanguageTag("tr")
        val eng = engine(mapOf("dictionaries/tr.txt" to "cam 3000\nçam 2000"))
        assertEquals(null, eng.correctionFor("cam", "tr", tr))
    }

    // ---- agglutinative languages: not correcting valid inflected forms ----

    @Test
    fun `a valid Turkish inflection absent from the dictionary is left alone`() {
        // "evlerden" (from the houses) is not in this small list, but it peels
        // to "ev". The engine must not offer a correction for a real word.
        val tr = Locale.forLanguageTag("tr")
        val eng = engine(mapOf("dictionaries/tr.txt" to "ev 9000\nel 8000\naraba 5000"))
        assertEquals(null, eng.correctionFor("evlerden", "tr", tr))
    }

    @Test
    fun `a Turkish typo with no valid stem is still corrected`() {
        // "arabz" does not peel to any root, so it stays a correctable typo of
        // "araba" (one adjacent-key edit away).
        val tr = Locale.forLanguageTag("tr")
        val eng = engine(mapOf("dictionaries/tr.txt" to "araba 9000\nev 5000"))
        assertEquals("araba", eng.correctionFor("araba", "tr", tr) ?: "araba")
        val corr = eng.correctionCandidates("arabz", "tr", tr, limit = 1)
        assertEquals(listOf("araba"), corr)
    }

    @Test
    fun `sanity - the two completion orderings genuinely differ`() {
        // Guards the first two tests against both silently returning the same
        // thing through some shared bug.
        val assets = mapOf(
            "dictionaries/en.txt" to "and 9000\nam 3000",
            "predictions/en.txt" to "i\tam"
        )
        val eng = engine(assets).apply { primeModel("en", en) }
        val neutral = eng.suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false
        ).items.drop(1).first()
        val contextual = eng.suggestionsFor(
            "a", "en", en, allowAutocorrect = false, personalized = false, prevWord = "i"
        ).items.drop(1).first()
        assertNotEquals(neutral, contextual)
    }

    @Test
    fun `a doubled letter is not corrected into a commoner word that starts differently`() {
        // Reported from a phone: typing "naberr" offered "haber" (news) rather
        // than "naber" (what's up). Both are real Turkish words and these are
        // their real frequencies from the shipped list — "haber" is fourteen
        // times commoner, which is enough to outweigh being a whole extra edit
        // away when nothing counts against starting with a different letter.
        //
        // The doubled r is not an elongation: that needs a run of three, and
        // deliberately so, or "brrr" and "shhh" stop being words. So this falls
        // to ordinary edit distance, and ordinary edit distance has to get it
        // right on its own.
        val tr = Locale.forLanguageTag("tr")
        val eng = engine(mapOf("dictionaries/tr.txt" to "haber 76328\nnaber 5533"))
        assertEquals(
            listOf("naber", "haber"),
            eng.correctionCandidates("naberr", "tr", tr, limit = 5)
        )
    }

    // ---- a prefix is not a misspelling ----

    @Test
    fun `the strip always offers to finish the word it can finish`() {
        // Typing "airport": at "airp" the strip used to offer "air" and "airs"
        // -- two ways of deleting what had just been typed -- and left the
        // commonest continuation in the dictionary off entirely. Both repairs
        // are edit-distance 1 and both outranked a completion four letters
        // longer, which is the right answer to the wrong question: mid-word,
        // the user is not finished being wrong yet.
        val assets = mapOf(
            "dictionaries/en.txt" to "air 90000\nairs 40000\nairport 30000\nairplane 9000"
        )
        val out = engine(assets).suggestionsFor(
            "airp", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertEquals("airp", out.first())
        assertTrue("no continuation on the strip: $out", out.contains("airport"))
    }

    @Test
    fun `the best repair keeps its slot when a continuation takes one`() {
        // The rule gives a continuation *one* slot, not both. A prefix with a
        // typo already in it still has to be repairable -- that is what
        // byPrefixFuzzy is for, and undoing it here would trade one fault for
        // another.
        val assets = mapOf(
            "dictionaries/en.txt" to "air 90000\nairs 40000\nairport 30000"
        )
        val out = engine(assets).suggestionsFor(
            "airp", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("the repair was evicted too: $out", out.contains("air"))
        assertTrue(out.contains("airport"))
    }

    @Test
    fun `a finished word that is simply wrong is unaffected`() {
        // Nothing in the dictionary continues "helko", so there is no
        // continuation to reserve a slot for and the repairs keep both.
        val assets = mapOf(
            "dictionaries/en.txt" to "hello 90000\nhelp 40000\nheld 20000"
        )
        val out = engine(assets).suggestionsFor(
            "helko", "en", en, allowAutocorrect = true, personalized = false
        ).items
        assertEquals("helko", out.first())
        assertTrue("the repair was lost: $out", out.contains("hello"))
    }

    @Test
    fun `what the space bar commits is never evicted from the strip`() {
        // The autocorrect target is committed on a separator, and a chip that
        // is not on the strip cannot be the one silently applied. Whatever the
        // reservation rule displaces, it must not be that.
        val assets = mapOf(
            "dictionaries/en.txt" to "hello 90000\nhell 50000\nhelloworld 100"
        )
        val res = engine(assets).suggestionsFor(
            "helo", "en", en, allowAutocorrect = true, personalized = false
        )
        if (res.autocorrectIndex >= 0) {
            assertTrue(
                "autocorrect index ${res.autocorrectIndex} is outside ${res.items}",
                res.autocorrectIndex < res.items.size
            )
        }
    }

    // ---- swiping a word the user taught the keyboard ----

    /** A straight-line swipe through each of [stops], sampled finely. */
    private fun swipe(stops: String): com.rimboard.keyboard.model.GlidePath {
        val prox = com.rimboard.keyboard.model.KeyProximity.forLang("en")
        val pts = ArrayList<Float>()
        for (i in 0 until stops.length - 1) {
            val ax = prox.gridX(stops[i])!!
            val ay = prox.gridY(stops[i])!!
            val bx = prox.gridX(stops[i + 1])!!
            val by = prox.gridY(stops[i + 1])!!
            for (step in 0..11) {
                val t = step / 11f
                pts.add(ax + (bx - ax) * t)
                pts.add(ay + (by - ay) * t)
            }
        }
        return com.rimboard.keyboard.model.GlidePath.of(pts.toFloatArray(), prox)!!
    }

    @Test
    fun `a learned word can be swiped though no dictionary has heard of it`() {
        // Nothing was testing personalised gliding at all, and the scoring for
        // it was rewritten: learned words used to be handed a score of 1.5e9,
        // which put every one of them above every dictionary word whatever the
        // finger drew. They are now placed on the dictionary's own frequency
        // scale instead, and the risk of that is the opposite failure — a word
        // somebody taught the keyboard becoming unglidable.
        val e = engine(mapOf("dictionaries/en.txt" to "hello 9000"))
        repeat(5) { userData.learnWord("wolfram") }
        val out = e.glideFor(swipe("wolfram"), "en", en, personalized = true)
        assertTrue("a learned word could not be swiped: $out", out.contains("wolfram"))
    }

    @Test
    fun `a learned word that does not fit the path is not offered`() {
        // The point of putting them on a scale rather than above it. Under the
        // old rule a learned word matching by a much weaker test outranked the
        // word the finger had actually drawn.
        val e = engine(mapOf("dictionaries/en.txt" to "hello 9000"))
        repeat(5) { userData.learnWord("wolfram") }
        val out = e.glideFor(swipe("helo"), "en", en, personalized = true)
        assertFalse("a learned word was offered for an unrelated swipe: $out",
            out.contains("wolfram"))
    }

    @Test
    fun `a common word still beats a learned one of the same shape`() {
        // "helo" and "hello" are the same gesture — a finger cannot stop twice
        // in one place — so only frequency separates them. The learned word
        // gets a better starting position on that axis, not an exemption from
        // it.
        val e = engine(mapOf("dictionaries/en.txt" to "hello 900000"))
        repeat(5) { userData.learnWord("helo") }
        val out = e.glideFor(swipe("helo"), "en", en, personalized = true)
        assertEquals("the commoner spelling should lead: $out", "hello", out.first())
    }

    // ---- completing a word with an apostrophe in it ----

    @Test
    fun `an elided article completes into an ordinary word`() {
        // French and Italian shape. The list holds the article with its
        // apostrophe attached and the noun on its own, so the join needs no
        // curated list and names no language -- it is two prefix lookups.
        val fr = Locale.forLanguageTag("fr")
        val assets = mapOf(
            "dictionaries/fr.txt" to listOf(
                "l' 3675406", "homme 90000", "hotel 40000", "qu' 2520219"
            ).joinToString("\n")
        )
        val out = engine(assets).suggestionsFor(
            "l'h", "fr", fr, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("no elision was offered: " + out, out.contains("l'homme"))
    }

    @Test
    fun `an English contraction completes from the curated list`() {
        // The English shape cannot be generated. Both "don" + "'t" and
        // "don" + "'s" are pairs of known entries, and a corpus that counted
        // the suffixes apart makes 's the commoner -- so generating would
        // offer "don's" first. Which suffix belongs to which stem is not in
        // the lists, so it comes from Contractions.
        val assets = mapOf(
            "dictionaries/en.txt" to listOf(
                "don 4158644", "'s 14291013", "'t 9628970"
            ).joinToString(System.lineSeparator())
        )
        val out = engine(assets).suggestionsFor(
            "don'", "en", en, allowAutocorrect = false, personalized = false
        ).items
        assertTrue("no contraction was offered: " + out, out.contains("don't"))
        assertFalse("a generated non-word was offered: " + out, out.contains("don's"))
    }

    @Test
    fun `a bare article on its own offers nothing`() {
        // Completing "l'" would mean ranking the whole dictionary behind an
        // apostrophe. The two commonest nouns in French are not a guess about
        // what this sentence wants, and the strip is three chips wide.
        val fr = Locale.forLanguageTag("fr")
        val assets = mapOf(
            "dictionaries/fr.txt" to listOf(
                "l' 3675406", "homme 90000", "hotel 40000"
            ).joinToString(System.lineSeparator())
        )
        val out = engine(assets).suggestionsFor(
            "l'", "fr", fr, allowAutocorrect = false, personalized = false
        ).items
        assertFalse("a bare article was completed: $out",
            out.any { it.startsWith("l'") && it.length > 2 })
    }

    @Test
    fun `an unknown article does not invent an elision`() {
        val fr = Locale.forLanguageTag("fr")
        val assets = mapOf("dictionaries/fr.txt" to "homme 90000")
        val out = engine(assets).suggestionsFor(
            "z'h", "fr", fr, allowAutocorrect = false, personalized = false
        ).items
        assertFalse("an elision was invented from an unknown head: " + out,
            out.any { it.contains("'") && it != "z'h" })
    }
}
