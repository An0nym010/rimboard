package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The address book is read, not held — so giving it up gives it up.
 *
 * Two stores feed the engine words the user has vouched for outside the
 * dictionary: [ContactStore], the people they write to, and
 * [UserDictionaryStore], the words saved under Android's own
 * Settings -> Personal dictionary. Both are read once per process, and both have
 * a `forget()` called from `onTrimMemory` in **both** services, for a reason
 * written down beside it: holding somebody's address book in a process the
 * system is deciding whether to kill is the wrong side of that bargain.
 *
 * **It freed nothing.** The engine held the set itself, assigned on every focus
 * change and every spell-checker session, so `forget()` dropped the store's
 * reference and the two engines in the process went on holding the same object.
 * At `TRIM_MEMORY_RUNNING_LOW` the next focus put an identical set straight
 * back, having re-queried every contact to build it; at `TRIM_MEMORY_COMPLETE`
 * there is no next focus at all, and the process the system was deciding
 * whether to kill kept the whole address book.
 *
 * Measured with `Runtime` around [com.rimboard.keyboard.model.PersonalWords.of]
 * over minted name parts, so that only the set retains them: **96 bytes a
 * part**. That is 27 KB at the 285 distinct capitalised words the twenty-one
 * non-German prose fixtures hold between them, 94 KB at a thousand parts, and
 * 376 KB at [com.rimboard.keyboard.model.PersonalWords.MAX_NAMES]. One set
 * object per source however many engines reference it, so the ceiling on what a
 * trim was failing to release is about three quarters of a megabyte, against the
 * 40.8 MB a configured process was measured at in `DictionaryFootprintTest`.
 *
 * The size is not the argument. The argument is that the code said it gave up
 * somebody's contacts and did not, and that a second place to clear is the fault
 * this repository has found four times elsewhere — a rule enforced on one of
 * several paths. So the engine reads the store rather than copying it, and there
 * is no second place.
 *
 * ## What is proved where
 *
 * The engine half is behavioural and is below: acceptance follows the source
 * between two calls, with nothing re-assigned in between. The wiring half cannot
 * be — an `InputMethodService` and a `SpellCheckerService` cannot be
 * instantiated in a JVM test — so it is read out of the source in the style of
 * [com.rimboard.keyboard.FocusContextTest] and `InputRoutingTest`. What that
 * half guards is exactly the shape that was wrong: a snapshot taken at focus
 * instead of the store itself.
 */
class PersonalWordsForgetTest {

    private lateinit var dir: File
    private lateinit var userData: UserData
    private val en: Locale = Locale.ENGLISH

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-forget-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    private fun engine(): SuggestionEngine {
        val assets = mapOf("dictionaries/en.txt" to "hello 900\nworld 800")
        return SuggestionEngine.forTesting(userData) { p -> assets[p]?.byteInputStream() }
    }

    @Test
    fun `the address book is re-read on every ask, not copied`() {
        val e = engine()
        // What a store does: hands out whatever it is holding at the moment of
        // the call. `forget()` sets that to empty.
        var held: Set<String> = emptySet()
        e.contactNames = { held }

        assertFalse(
            "a name nothing has vouched for is not a word",
            e.acceptedWord("yilmaz", "en", en)
        )
        held = setOf("yilmaz")
        assertTrue(
            "the contact read landed and the engine did not see it, so it is " +
                "holding a copy taken before the query finished",
            e.acceptedWord("yilmaz", "en", en)
        )
        held = emptySet()
        assertFalse(
            "ContactStore.forget() emptied the store and the engine went on " +
                "accepting the name, so onTrimMemory released nothing",
            e.acceptedWord("yilmaz", "en", en)
        )
    }

    @Test
    fun `the system personal dictionary is re-read the same way`() {
        val e = engine()
        // A map now, keyed by the folded word and holding the spelling it was
        // declared with -- the shield reads the keys, and the strip, which
        // offers these words since they stopped being shields only, reads the
        // spelling.
        var held: Map<String, String> = emptyMap()
        e.userDictionary = { held }

        assertFalse(e.acceptedWord("anthropic", "en", en))
        held = mapOf("anthropic" to "Anthropic")
        assertTrue(e.acceptedWord("anthropic", "en", en))
        held = emptyMap()
        assertFalse(
            "UserDictionaryStore.forget() left the engine still accepting",
            e.acceptedWord("anthropic", "en", en)
        )
    }

    @Test
    fun `the two sources stay separate`() {
        // They answer to separate settings and separate permissions, so one
        // being emptied must not empty the other.
        val e = engine()
        var contacts: Set<String> = setOf("yilmaz")
        var words: Map<String, String> = mapOf("anthropic" to "anthropic")
        e.contactNames = { contacts }
        e.userDictionary = { words }
        contacts = emptySet()
        assertFalse(e.acceptedWord("yilmaz", "en", en))
        assertTrue(
            "emptying the address book took the personal dictionary with it",
            e.acceptedWord("anthropic", "en", en)
        )
        words = emptyMap()
        contacts = setOf("yilmaz")
        assertTrue(e.acceptedWord("yilmaz", "en", en))
        assertFalse(e.acceptedWord("anthropic", "en", en))
    }

    // ---------------------------------------------------------------- wiring

    private fun source(rel: String): String {
        for (p in listOf("src/main/java/$rel", "app/src/main/java/$rel")) {
            val f = File(p)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("$rel not found")
    }

    private fun services(): List<Pair<String, String>> = listOf(
        "RimBoardService" to source("com/rimboard/keyboard/RimBoardService.kt"),
        "RimSpellService" to source("com/rimboard/keyboard/spell/RimSpellService.kt")
    )

    @Test
    fun `both services hand the engine the store and not a copy of it`() {
        for ((name, src) in services()) {
            for ((prop, store, getter) in listOf(
                Triple("contactNames", "ContactStore", "names"),
                Triple("userDictionary", "UserDictionaryStore", "index")
            )) {
                assertTrue(
                    "$name no longer wires $prop to $store, so the words the " +
                        "user vouched for reach neither the underline nor the " +
                        "space bar",
                    src.contains("$prop =") && src.contains("$store::$getter")
                )
                assertFalse(
                    "$name assigns $prop a snapshot of $store. A copy outlives " +
                        "the forget() in onTrimMemory, which is the whole fault " +
                        "this test exists for",
                    src.contains("$prop = com.rimboard.keyboard.engine.$store.$getter()")
                )
            }
        }
    }

    @Test
    fun `both services still forget both stores under memory pressure`() {
        for ((name, src) in services()) {
            val start = src.indexOf("override fun onTrimMemory(")
            assertTrue("$name has no onTrimMemory", start >= 0)
            val body = src.substring(start, minOf(src.length, start + 2000))
            for (store in listOf("ContactStore", "UserDictionaryStore")) {
                assertTrue(
                    "$name.onTrimMemory no longer calls $store.forget(), so the " +
                        "process keeps the address book while the system decides " +
                        "whether to kill it",
                    body.contains("$store.forget()")
                )
            }
        }
    }
}
