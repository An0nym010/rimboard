package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.FalseFriends
import com.rimboard.keyboard.model.Languages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The English offensive list, applied to languages where the word means
 * something else.
 *
 * Every shipped language has its own list, and English is read behind it so
 * the slurs those lists miss are still caught. That fallback never asked
 * whether the word was ordinary vocabulary where it was being typed -- so
 * Swedish lost "slut" (end), French lost "retard" (delay) and German lost
 * "dick" (thick), out of completions, corrections, predictions and glide, with
 * the setting on by default.
 *
 * These tests read the shipped data rather than fixtures, because the claim
 * being made is about the shipped data.
 */
class FalseFriendsTest {

    private fun assets(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    private fun listOf_(kind: String, lang: String): Set<String> {
        val f = assets().resolve("$kind/$lang.txt")
        if (!f.isFile) return emptySet()
        return f.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun freq(lang: String, word: String): Pair<Long, Long> {
        var f = 0L
        var total = 0L
        assets().resolve("dictionaries/$lang.txt").forEachLine { line ->
            val p = line.split(' ')
            if (p.size >= 2) {
                val n = p[1].toLongOrNull()
                if (n != null) {
                    total += n
                    if (p[0] == word) f = n
                }
            }
        }
        return f to total
    }

    @Test
    fun `every exemption is a word the English list actually blocks`() {
        // An entry for a word that is not on the English list would be dead,
        // and dead entries are how a list stops describing anything.
        val en = listOf_("offensive", "en")
        for ((lang, words) in FalseFriends.entries()) {
            for (w in words) {
                assertTrue(
                    "$lang exempts \"$w\", which is not on the English list at all",
                    w in en
                )
            }
        }
    }

    @Test
    fun `no exemption contradicts the language's own list`() {
        // The ordering in `listed` puts the language's own list first, so this
        // could never take effect -- but an entry that cannot take effect is a
        // statement that disagrees with a native-reviewed one, and it should
        // not be sitting there unnoticed.
        for ((lang, words) in FalseFriends.entries()) {
            val own = listOf_("offensive", lang)
            for (w in words) {
                assertFalse(
                    "$lang exempts \"$w\" while its own offensive list holds it",
                    w in own
                )
            }
        }
    }

    @Test
    fun `every exemption is a word that language actually uses`() {
        // The evidence half. Three per million is where the KDoc drew the line
        // for "somebody would notice it missing"; anything rarer was left out
        // rather than guessed at.
        for ((lang, words) in FalseFriends.entries()) {
            for (w in words) {
                val (f, total) = freq(lang, w)
                val ppm = f.toDouble() * 1e6 / total
                assertTrue(
                    "$lang exempts \"$w\" at only %.2f per million, which is not ".format(ppm) +
                        "evidence that the word is used there",
                    ppm >= 3.0
                )
            }
        }
    }

    @Test
    fun `the slur the fallback exists for is still caught everywhere`() {
        // The load-bearing half. These languages carry the English slur in
        // their corpora and do not list it themselves, so the fallback is the
        // only thing filtering it -- and the exemption must not have widened
        // into it.
        val en = listOf_("offensive", "en")
        val slur = en.firstOrNull { it == "nigger" }
        assertTrue("the fixture word left the English list; pick another", slur != null)
        for (lang in listOf("da", "de", "hu", "no", "sv")) {
            assertFalse(
                "$lang now exempts the slur",
                FalseFriends.ordinaryHere(lang, slur!!)
            )
            assertFalse(
                "$lang lists it itself, so this no longer tests the fallback",
                slur in listOf_("offensive", lang)
            )
        }
    }

    /**
     * The population, walked, which the emoji half of this file already does
     * and this half did not.
     *
     * Every test above starts from the seven exemptions and checks each one.
     * That can only ever confirm what is there; it cannot say whether an
     * eighth is missing, and a list assembled from sightings is shaped like
     * what somebody happened to notice. So: every word of the English list,
     * against every language that falls back to it, weighted by how often that
     * language's own corpus uses it.
     *
     * Above 50 per million and not already accounted for, there are three, and
     * all three are the English expletive borrowed whole rather than a false
     * friend -- Danish "fuck" (86/M), Dutch "shit" (82/M), Slovak "bastard"
     * (59/M). Blocking them is right. They are named here rather than exempted
     * because the assertion has to distinguish "we looked and it is fine" from
     * "nobody looked".
     *
     * **The interesting result is that frequency cannot find these at all**,
     * and that is worth knowing before anyone tries to automate the list. The
     * seven real exemptions run from Swedish "slut" at 362 per million down to
     * Norwegian "fag" below 5, and the band in between is full of pure noise:
     * "dick" appears at 12-23 per million in sixteen languages, which is the
     * English word leaking through subtitle corpora and the given name, not
     * vocabulary. German "dick" -- a real word meaning thick -- sits at 33,
     * barely above that noise, and Swedish "prick" at 10 sits below most of
     * it. No threshold separates them. This list is a speaker's judgement and
     * has to stay one; what this test can do is prove that nothing large is
     * being missed.
     */
    @Test
    fun `no common word in any language is blocked by the English fallback`() {
        val en = listOf_("offensive", "en")
        // The English expletive borrowed wholesale: offensive where it lands,
        // and absent from that language's own short list only because the
        // lists are short.
        val borrowed = setOf("da fuck", "nl shit", "sk bastard")
        val surprises = StringBuilder()
        val report = StringBuilder()
        for (lang in Languages.codes) {
            if (lang == "en") continue
            val own = listOf_("offensive", lang)
            for (w in en) {
                if (w in own) continue
                val (f, total) = freq(lang, w)
                if (f == 0L) continue
                val perMillion = f * 1_000_000.0 / total
                if (perMillion < COMMON_PER_MILLION) continue
                report.append("    %-3s %-12s %6.1f/M%n".format(lang, w, perMillion))
                if (FalseFriends.ordinaryHere(lang, w)) continue
                if ("$lang $w" in borrowed) continue
                surprises.append(" $lang \"$w\" at %.0f/M".format(perMillion))
            }
        }
        println(report)
        assertEquals(
            "the English offensive list is withholding a common word somewhere " +
                "and nothing accounts for it. Either it is a false friend and " +
                "wants an exemption with a speaker's evidence, or it is that " +
                "language's own expletive and wants naming here.$surprises",
            "", surprises.toString()
        )
        assertTrue(
            "the sweep found nothing at all, so it is measuring an empty " +
                "population rather than a clean one",
            report.isNotEmpty()
        )
    }

    @Test
    fun `the exemptions are the seven measured, and only those`() {
        val flat = FalseFriends.entries()
            .flatMap { (l, ws) -> ws.map { "$l $it" } }
            .toSortedSet()
        assertEquals(
            "the exemption list moved; each entry is a claim about a language " +
                "that wants evidence in the KDoc beside it",
            sortedSetOf("da fag", "da slut", "de dick", "fr retard", "no fag", "sv prick", "sv slut"),
            flat
        )
    }

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-ff", "").let { it.delete(); it.mkdirs(); it }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun engineFor(lang: String): SuggestionEngine {
        val files = mapOf(
            "dictionaries/$lang.txt" to assets().resolve("dictionaries/$lang.txt").readText(),
            "offensive/$lang.txt" to assets().resolve("offensive/$lang.txt").readText(),
            "offensive/en.txt" to assets().resolve("offensive/en.txt").readText(),
            "emoji/en.txt" to assets().resolve("emoji/en.txt").readText()
        ) + listOf("emoji/$lang.txt", "emoji/search_$lang.txt")
            // Both, because the strip reads the keyword file and falls back to
            // the picker's search index. Naming only the first is how this
            // helper reported a language having no emoji at all while the file
            // sat beside it -- the same fault OutOfVocabularyTest's hand-built
            // map has had twice.
            .filter { assets().resolve(it).isFile }
            .associateWith { assets().resolve(it).readText() }
        return SuggestionEngine.forTesting(userData) { p -> files[p]?.byteInputStream() }
            .also { it.blockOffensive = true }
    }

    private fun completions(lang: String, prefix: String): List<String> {
        val loc = Locale.forLanguageTag(lang)
        return engineFor(lang).suggestionsFor(
            prefix, lang, loc, allowAutocorrect = false, personalized = false
        ).items.map { it.lowercase(loc) }
    }

    @Test
    fun `a Swede gets the word for end back`() {
        // The whole point, end to end and through the real filter: with
        // blocking on, "slut" was removed from every candidate list for the
        // 362-per-million word Swedish uses for "end".
        assertTrue(
            "slut is still filtered out of Swedish completions",
            "slut" in completions("sv", "slu")
        )
    }

    @Test
    fun `and a German gets the word for thick`() {
        assertTrue(
            "dick is still filtered out of German completions",
            "dick" in completions("de", "dic")
        )
    }

    @Test
    fun `while the slur stays out of the same strip`() {
        // Same language, same setting, same code path: the fallback still does
        // the job it is there for.
        val out = completions("sv", "nigg")
        assertTrue("the slur came back with the exemption: " + out, "nigger" !in out)
    }

    // ---- the same fallback in the emoji map ----

    private fun emojiKeywords(lang: String): Set<String> {
        val f = assets().resolve("emoji/$lang.txt")
        if (!f.isFile) return emptySet()
        return f.readLines().mapNotNull {
            it.split('	').firstOrNull()?.trim()?.lowercase()?.takeIf { w -> w.isNotEmpty() }
        }.toSet()
    }

    @Test
    fun `every emoji exemption is a keyword English would have answered for`() {
        val en = emojiKeywords("en")
        for ((lang, words) in FalseFriends.emojiEntries()) {
            for (w in words) {
                assertTrue(
                    "$lang declines \"$w\", which English has no emoji for anyway",
                    w in en
                )
            }
        }
    }

    @Test
    fun `no emoji exemption overrides a keyword written for that language`() {
        // The language's own map is read first, so an entry here for a word
        // that language has its own emoji for would be dead -- and a dead
        // entry is a claim nobody is checking.
        for ((lang, words) in FalseFriends.emojiEntries()) {
            val own = emojiKeywords(lang)
            for (w in words) {
                assertFalse(
                    "$lang declines \"$w\" but has its own emoji keyword for it",
                    w in own
                )
            }
        }
    }

    @Test
    fun `every emoji exemption is a word that language actually uses`() {
        for ((lang, words) in FalseFriends.emojiEntries()) {
            for (w in words) {
                val (f, total) = freq(lang, w)
                val ppm = f.toDouble() * 1e6 / total
                assertTrue(
                    "$lang declines \"$w\" at only %.2f per million".format(ppm),
                    ppm >= 50.0
                )
            }
        }
    }

    @Test
    fun `three languages had their own emoji vocabulary and the strip ignored it`() {
        // emoji/<lang>.txt is what the strip reads and five languages have one.
        // emoji/search_<lang>.txt is what the picker's search box reads and
        // eight do. French, Italian and Portuguese were in the second set and
        // not the first, so the strip answered them out of the English list --
        // words a French user does not type -- while their own vocabulary sat
        // in the same directory, in the same format, unread.
        assertEquals("❤️", engineFor("fr").emojiFor("coeur", "fr"))
        assertEquals("🐶", engineFor("fr").emojiFor("chien", "fr"))
        assertEquals("🎁", engineFor("fr").emojiFor("cadeau", "fr"))
        assertEquals("🍕", engineFor("it").emojiFor("pizza", "it"))
        assertEquals("🐶", engineFor("pt").emojiFor("cachorro", "pt"))
        // The five that already had a keyword file read it, not the search
        // index, so nothing about them moves.
        assertEquals("❤️", engineFor("de").emojiFor("liebe", "de"))
        assertEquals("🔥", engineFor("de").emojiFor("feuer", "de"))
    }

    @Test
    fun `French because is not a car and a Romanian room is not a camera`() {
        // Both found by sweeping all 138 English keywords against every
        // language the fallback answers for, rather than by noticing them.
        //
        // "car" is the commonest French conjunction in the whole keyword list
        // at 304.67 per million, and the same fault as "lit" sitting beside it
        // in the map. "camera" is worse than missed: it was already listed for
        // Italian, for the same word and the same wrong meaning, and Romanian
        // was not -- which is what a list assembled from sightings does.
        val fr = engineFor("fr")
        assertEquals("French car means because", null, fr.emojiFor("car", "fr"))
        val ro = engineFor("ro")
        assertEquals("Romanian camera means the room", null, ro.emojiFor("camera", "ro"))
        // Still answered for the language that has neither meaning, which is
        // what says this declines rather than deletes.
        assertEquals("🚗", fr.emojiFor("car", "de"))
        assertEquals("📷", ro.emojiFor("camera", "de"))
    }

    @Test
    fun `Danish four is not a flame and Danish food is not an angry face`() {
        val e = engineFor("da")
        assertEquals(null, e.emojiFor("fire", "da"))
        assertEquals(null, e.emojiFor("mad", "da"))
        assertEquals(null, e.emojiFor("gift", "da"))
    }

    @Test
    fun `a keyword that means the same thing still travels`() {
        // The fallback is not being switched off. "ok" is commoner in Italian
        // than in English and means exactly what it means in English, which is
        // also why frequency could not have decided this.
        val it = engineFor("it")
        assertTrue("the English fallback stopped working entirely",
            it.emojiFor("ok", "it") != null)
        assertEquals("Italian camera is a room", null, it.emojiFor("camera", "it"))
    }

    @Test
    fun `English itself is unaffected`() {
        // The exemption is only consulted on the fallback, which does not run
        // for English. Nothing here should be able to reach an English field.
        assertFalse(FalseFriends.ordinaryHere("en", "slut"))
        assertFalse(FalseFriends.ordinaryHere("en", "dick"))
    }

    private companion object {
        /**
         * How common a word has to be in a language before withholding it
         * from that language's suggestions is a thing worth accounting for.
         *
         * The same fifty per million the emoji half of this file holds its
         * additions to, and for the same reason: below it a word is corpus
         * traffic rather than vocabulary. It is emphatically *not* the bar for
         * being a false friend -- four of the seven exemptions sit under it.
         */
        const val COMMON_PER_MILLION = 50.0
    }

}
