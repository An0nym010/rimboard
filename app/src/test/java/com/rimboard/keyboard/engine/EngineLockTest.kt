package com.rimboard.keyboard.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The engine's lazily-loaded asset maps must not share one lock.
 *
 * `warm()` exists so the first keystroke never waits for an asset parse. It
 * runs on a background thread and loads the prediction model there — but while
 * all three loaders were `@Synchronized`, they shared the engine's monitor, and
 * `warm()` held it for the whole parse. So the first keystroke that needed the
 * emoji map or the offensive list blocked behind the very parse `warm()` was
 * started to get out of the way of. The larger the model, the longer the stall,
 * on exactly the path that was supposed to be fast.
 *
 * The test makes the prediction parse block, then asks for an emoji from
 * another thread and requires an answer while the parse is still stuck. With
 * one shared monitor it cannot answer, and the test times out.
 */
class EngineLockTest {

    private lateinit var dir: File
    private lateinit var userData: UserData

    @Before
    fun setUp() {
        dir = File.createTempFile("rimboard-lock-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        userData = UserData.inDir(dir)
    }

    @After
    fun tearDown() {
        userData.shutdown()
        dir.deleteRecursively()
    }

    @Test(timeout = 10_000)
    fun `a stuck prediction load does not block an emoji lookup`() {
        val parseStarted = CountDownLatch(1)
        val releaseParse = CountDownLatch(1)

        val engine = SuggestionEngine.forTesting(userData) { path ->
            when (path) {
                // Stands in for a large model still being parsed: the loader is
                // inside the lock for as long as this stream takes to produce.
                "predictions/en.txt" -> {
                    parseStarted.countDown()
                    releaseParse.await(5, TimeUnit.SECONDS)
                    "see\tyou".byteInputStream() as InputStream
                }
                "emoji/en.txt" -> "dog\t🐶".byteInputStream() as InputStream
                else -> null
            }
        }

        val warm = Thread { engine.predictions("", "see", "en", java.util.Locale.ENGLISH, 3) }
        warm.start()

        assertTrue(
            "the prediction load never started",
            parseStarted.await(5, TimeUnit.SECONDS)
        )

        // The typing path, on another thread so a regression is a timeout here
        // rather than a hung test run.
        val answered = CountDownLatch(1)
        val emoji = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val typing = Thread {
            emoji.set(engine.emojiFor("dog", "en"))
            answered.countDown()
        }
        typing.start()

        val gotAnswer = answered.await(3, TimeUnit.SECONDS)
        // Release first, so the assertion failing still leaves both threads
        // free to finish and the suite does not hang on the blocked one.
        releaseParse.countDown()
        warm.join(5_000)
        typing.join(5_000)

        assertTrue(
            "an emoji lookup waited for an unrelated asset parse to finish",
            gotAnswer
        )
        assertTrue("expected the emoji, got ${emoji.get()}", emoji.get() == "🐶")
    }

    /**
     * Sharing the dictionary cache made it static, which removed the only thing
     * that ever released it: the maps used to die with their engine, and now
     * they outlive the service and hold every language ever typed — about
     * fifteen megabytes each — until the process is killed. A keyboard is a
     * background process and near the front of the kill list, so this is the
     * memory that decides whether it survives to the next sentence.
     */
    /**
     * The prediction models are the other half of what a trim is for, and they
     * were neither shared nor released.
     *
     * Two engines exist in this process by design — the keyboard has one and
     * the spell checker has another — and they are usually on the same
     * language. Each was parsing and holding its own copy of that language's
     * model: two loads of one asset, and two live maps of it. Cheap when a
     * model was 200 KB of bigrams, and 2.6 to 4.9 MB each since they started
     * carrying two-word contexts.
     *
     * The release half was worse: at TRIM_MEMORY_COMPLETE the process gave up
     * every dictionary and kept every model.
     */
    @Test
    fun `two engines share one prediction model, and a trim releases it`() {
        fun engine() = SuggestionEngine.forTesting(userData, shared = true) { path ->
            when (path) {
                "predictions/en.txt" -> "see\tyou the\n".byteInputStream() as InputStream
                "dictionaries/en.txt" -> "the 900".byteInputStream() as InputStream
                else -> null
            }
        }
        SuggestionEngine.trimLanguageCaches(emptySet())
        val keyboard = engine()
        val speller = engine()
        keyboard.predictions("", "see", "en", java.util.Locale.ENGLISH, 1, mayLoad = true)
        speller.predictions("", "see", "en", java.util.Locale.ENGLISH, 1, mayLoad = true)
        assertTrue(
            "one model for one language, not one per engine: found " +
                "${SuggestionEngine.cachedModelCount()}",
            SuggestionEngine.cachedModelCount() == 1
        )

        SuggestionEngine.trimLanguageCaches(emptySet())
        assertTrue(
            "a trim that gives up every dictionary must give up the models too, " +
                "found ${SuggestionEngine.cachedModelCount()}",
            SuggestionEngine.cachedModelCount() == 0
        )

        // Same bargain as the dictionaries: releasing is a memory decision and
        // never a functional one.
        assertTrue(
            "a released model must come back",
            keyboard.predictions("", "see", "en", java.util.Locale.ENGLISH, 1, mayLoad = true)
                .isNotEmpty()
        )
        SuggestionEngine.trimLanguageCaches(emptySet())
    }

    @Test
    fun `trimming keeps the languages in use and releases the rest`() {
        var loadsOfGerman = 0
        // `shared = true`: the cache being trimmed is process-wide by
        // construction, so an unshared fixture would leave the assertions
        // passing against a map the trim never touches.
        val engine = SuggestionEngine.forTesting(userData, shared = true) { path ->
            when (path) {
                "dictionaries/en.txt" -> "the 900".byteInputStream() as InputStream
                "dictionaries/tr.txt" -> "ve 900".byteInputStream() as InputStream
                "dictionaries/de.txt" -> {
                    loadsOfGerman++
                    "und 900".byteInputStream() as InputStream
                }
                else -> null
            }
        }
        SuggestionEngine.trimLanguageCaches(emptySet())
        engine.dictionary("en", java.util.Locale.ENGLISH)
        engine.dictionary("tr", java.util.Locale("tr"))
        engine.dictionary("de", java.util.Locale.GERMAN)
        assertTrue("expected three cached", SuggestionEngine.cachedCount() == 3)
        assertTrue("German loaded once", loadsOfGerman == 1)

        SuggestionEngine.trimLanguageCaches(setOf("en"))
        assertTrue(
            "only the language in use should survive, found ${SuggestionEngine.cachedCount()}",
            SuggestionEngine.cachedCount() == 1
        )

        // Trimming is a memory decision, never a functional one: a language the
        // user returns to has to come back, at the cost of one reload.
        assertTrue(
            "a trimmed language must still answer",
            engine.knownIn("und", "de", java.util.Locale.GERMAN)
        )
        assertTrue("returning to German should have reloaded it", loadsOfGerman == 2)

        SuggestionEngine.trimLanguageCaches(emptySet())
    }
}
