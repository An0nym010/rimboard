package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.FalseFriends
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
        ) + (assets().resolve("emoji/$lang.txt").takeIf { it.isFile }
            ?.let { mapOf("emoji/$lang.txt" to it.readText()) } ?: emptyMap())
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
}
