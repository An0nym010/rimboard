package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.ExtendedDicts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * The catalogue of downloadable dictionaries, and what it takes to install one.
 *
 * A downloaded dictionary decides what the keyboard offers and what it accepts
 * as a word, so the interesting cases here are all refusals: the wrong file,
 * a truncated one, one that unpacks into something that is not a dictionary.
 * The happy path is one test and the ways of saying no are six, which is the
 * right proportion for a feature whose failure mode is a keyboard quietly
 * ranking words out of somebody else's file.
 */
class ExtendedDictTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("extdict", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    /** A dictionary the store will accept: "word count" lines, enough of them. */
    private fun dictText(words: Int = 25_000): String =
        (1..words).joinToString("\n") { "word$it ${words - it + 1}" } + "\n"

    private fun entryFor(lang: String, blob: ByteArray, words: Int = 25_000) =
        ExtendedDicts.Entry(lang, words, blob.size.toLong(), ExtendedDicts.sha256(blob))

    // ---- the manifest -----------------------------------------------------

    private val goodJson = """
        {"version":1,"minCount":5,
         "base":"https://raw.githubusercontent.com/x/y/dictionaries/",
         "entries":[
           {"lang":"tr","words":412000,"bytes":1800000,
            "sha256":"${"ab".repeat(32)}"},
           {"lang":"fi","words":488508,"bytes":2342428,
            "sha256":"${"cd".repeat(32)}"}]}
    """.trimIndent()

    @Test
    fun `a manifest names languages, sizes and hashes`() {
        val c = ExtendedDicts.parse(goodJson)
        assertEquals(2, c.entries.size)
        assertEquals(5, c.minCount)
        assertEquals(412000, c.forLang("tr")?.words)
        assertNull("a language not listed cannot be installed", c.forLang("de"))
    }

    @Test
    fun `the download url is built from the catalogue, not from the entry`() {
        // So a manifest cannot name one host for itself and another for a file
        // in it: there is one base, and every URL is that base plus a name.
        val c = ExtendedDicts.parse(goodJson)
        assertEquals(
            "https://raw.githubusercontent.com/x/y/dictionaries/tr.txt.gz",
            c.urlFor(c.forLang("tr")!!)
        )
    }

    @Test
    fun `a base that is not https is not a catalogue`() {
        val c = ExtendedDicts.parse(goodJson.replace("https://", "http://"))
        assertTrue("plain http must not survive parsing", c.entries.isEmpty())
    }

    @Test
    fun `one malformed entry does not take the good ones down with it`() {
        val json = goodJson.replace(""""sha256":"${"ab".repeat(32)}"""", """"sha256":"nope"""")
        val c = ExtendedDicts.parse(json)
        assertNull("the entry with the bad hash is gone", c.forLang("tr"))
        assertEquals("the other one survives", 1, c.entries.size)
    }

    @Test
    fun `nonsense parses to an empty catalogue rather than throwing`() {
        // This runs when a settings screen opens. An exception here would be a
        // crash on a screen the user came to for something else.
        for (s in listOf(null, "", "   ", "not json", "[]", "{}")) {
            assertTrue(s.toString(), ExtendedDicts.parse(s).entries.isEmpty())
        }
    }

    @Test
    fun `the hash is a real SHA-256, checked against a known answer`() {
        // Everything else here asserts that a file matching its entry is
        // accepted and one that does not is refused -- both of which hold if
        // the hash function is consistently wrong. A byte sign-extended, or a
        // nibble printed without its leading zero, would reject every
        // legitimate download and the failure would read as a bad file.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ExtendedDicts.sha256(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ExtendedDicts.sha256("abc".toByteArray())
        )
        // A byte over 0x7F is where sign extension would show, and 0x00 is
        // where a dropped leading zero would.
        assertEquals(64, ExtendedDicts.sha256(byteArrayOf(0, -1, -128, 127)).length)
    }

    // ---- installing -------------------------------------------------------

    @Test
    fun `a file that matches the manifest is installed and then read back`() {
        val blob = gzip(dictText())
        val entry = entryFor("tr", blob)
        assertNull(DictionaryStore.install(dir, entry, blob))
        assertEquals(setOf("tr"), DictionaryStore.installed(dir))
        val text = DictionaryStore.open(dir, "dictionaries/tr.txt")!!
            .bufferedReader().use { it.readLine() }
        assertEquals("word1 25000", text)
    }

    @Test
    fun `a file the manifest does not describe is refused`() {
        val blob = gzip(dictText())
        // Same size, different content: the length check passes and the hash
        // is what has to catch it.
        val other = gzip(dictText().replaceFirst("word1 ", "wordX "))
        val entry = entryFor("tr", blob)
        assertEquals(
            DictionaryStore.Refusal.WRONG_FILE,
            DictionaryStore.install(dir, entry.copy(bytes = other.size.toLong()), other)
        )
        assertTrue("nothing was written", DictionaryStore.installed(dir).isEmpty())
    }

    @Test
    fun `a truncated download is refused before it is unpacked`() {
        val blob = gzip(dictText())
        val entry = entryFor("tr", blob)
        assertEquals(
            DictionaryStore.Refusal.WRONG_FILE,
            DictionaryStore.install(dir, entry, blob.copyOf(blob.size - 20))
        )
    }

    @Test
    fun `something that is not a dictionary is refused after it is unpacked`() {
        // Hash and length agree -- this is a file somebody built on purpose --
        // and it still must not become the keyboard's word list.
        val blob = gzip("<html><body>not a dictionary</body></html>\n")
        val entry = entryFor("tr", blob, words = 1)
        assertEquals(DictionaryStore.Refusal.CORRUPT, DictionaryStore.install(dir, entry, blob))
        assertTrue(DictionaryStore.installed(dir).isEmpty())
    }

    @Test
    fun `a dictionary too small to be one is refused`() {
        val blob = gzip(dictText(words = 100))
        val entry = entryFor("tr", blob, words = 100)
        assertEquals(DictionaryStore.Refusal.CORRUPT, DictionaryStore.install(dir, entry, blob))
    }

    @Test
    fun `a refused install leaves the previous dictionary in place`() {
        val good = gzip(dictText())
        assertNull(DictionaryStore.install(dir, entryFor("tr", good), good))
        val junk = gzip("<html>\n")
        DictionaryStore.install(dir, entryFor("tr", junk, words = 1), junk)
        val first = DictionaryStore.open(dir, "dictionaries/tr.txt")!!
            .bufferedReader().use { it.readLine() }
        assertEquals("the good dictionary is still the installed one", "word1 25000", first)
        assertTrue(
            "the working file was cleaned up",
            dir.listFiles().orEmpty().none { it.name.endsWith(".part") }
        )
    }

    @Test
    fun `a file longer than the manifest promises is refused before it is held`() {
        // The import path takes whatever the user picks out of a file manager.
        // Reading it whole first and checking afterwards means a mis-tap on a
        // video is an OutOfMemoryError -- an Error, not an Exception, so the
        // catch around the install would not have caught it either.
        val data = ByteArray(1000) { 7 }
        assertNull(
            "a file over the manifest size must not be read into memory",
            DictionaryStore.readAtMost(data.inputStream(), 999)
        )
        assertEquals(1000, DictionaryStore.readAtMost(data.inputStream(), 1000)!!.size)
    }

    @Test
    fun `a language code that is not a language code cannot name a file`() {
        // The catalogue already refuses these, and this is the function that
        // turns a code into a path, so it refuses them too. Two checks,
        // because only one of them is next to the file write.
        val blob = gzip(dictText())
        for (lang in listOf("../../etc", "tr/../..", "TR", "", "t r")) {
            assertEquals(
                lang,
                DictionaryStore.Refusal.NOT_OFFERED,
                DictionaryStore.install(dir, entryFor(lang, blob), blob)
            )
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    // ---- the override seam ------------------------------------------------

    @Test
    fun `the store answers for dictionaries and for nothing else`() {
        val blob = gzip(dictText())
        DictionaryStore.install(dir, entryFor("tr", blob), blob)
        // A store that could shadow any asset would be a much larger thing to
        // have to trust than one that can only replace a word list.
        for (path in listOf(
            "predictions/tr.txt", "offensive/tr.txt", "emoji/tr.txt",
            "dictionaries/../predictions/tr.txt", "dictionaries/tr.json", "tr.txt"
        )) {
            assertNull(path, DictionaryStore.open(dir, path))
        }
    }

    @Test
    fun `a language with nothing installed falls through to the bundled asset`() {
        assertNull(DictionaryStore.open(dir, "dictionaries/de.txt"))
    }
}
