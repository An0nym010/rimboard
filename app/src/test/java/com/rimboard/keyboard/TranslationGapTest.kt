package com.rimboard.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A string added to English must be added to the other seven locales, or named
 * here as a known gap.
 *
 * This does not close the backlog; it stops it growing. Every locale sat at
 * exactly the same 281 of 392 translatable strings, which is not a coincidence:
 * they were machine translated in one pass and have drifted from English by
 * exactly as much as has been added since. The failure that follows is quiet
 * — an untranslated string is not an error, it silently falls back to
 * English, and on a Turkish-first keyboard the report is "some of the settings
 * are in English" long after anyone remembers which commit did it.
 *
 * So the pin is the *set*, not the count. Adding a string to English and
 * nothing else fails, and the message names the string; translating one and
 * leaving it listed here also fails, because a list that is allowed to be
 * wrong in the harmless direction stops being read.
 *
 * Adding to [KNOWN_GAPS] is a legitimate move for a string that genuinely
 * cannot be translated yet. Doing it silently, in the same commit that added
 * the string, is how a ratchet becomes a rubber stamp.
 */
class TranslationGapTest {

    /** Every locale the app ships. `values/` is English and is the source. */
    private val locales = listOf("de", "es", "fr", "it", "pt", "ru", "tr")

    /**
     * The network-consent copy, deliberately English in every locale.
     *
     * These are the strings that explain what leaves the phone and ask whether
     * that is allowed. They were left in English on purpose rather than
     * shipped as machine translation, because a consent notice that is subtly
     * wrong in the reader's language is worse than one they have to read in a
     * second language — it still looks like it was understood. A prefix
     * rather than a list: a new `net_` string belongs to the same policy.
     *
     * **Except the error messages.** `net_error_*` are not consent copy --
     * "the server is busy" explains nothing about what left the phone and
     * carries none of the risk that policy is about. All three were translated
     * everywhere anyway, quietly, while the prefix rule was exempting them, so
     * the rule was wider than its own reason. Narrowed to match, which also
     * means a new `net_error_` string is now held to the same standard as any
     * other message the user reads.
     */
    private fun deliberatelyEnglish(name: String) =
        name.startsWith("net_") && !name.startsWith("net_error")

    /**
     * The backlog, and it is empty.
     *
     * It held 72 strings on 2026-08-18, the day this test was written: names
     * that existed in English and in no other locale. They were translated on
     * 2026-08-22 and the list came out with them, which is what this test was
     * built to make happen.
     *
     * It exists as an empty set rather than being deleted, because the second
     * test below is what gives it teeth: a name added here is allowed to be
     * untranslated, and is *required* to still be untranslated. That makes the
     * escape hatch cost something to use and impossible to leave behind, so it
     * is worth keeping open. Adding to it is a legitimate move for a string
     * that genuinely cannot be translated yet; doing it silently, in the same
     * commit that added the string, is how a ratchet becomes a rubber stamp.
     */
    private val KNOWN_GAPS = emptySet<String>()

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun res(): File {
        for (p in listOf("src/main/res", "app/src/main/res")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("res directory not found from ${File(".").absolutePath}")
    }

    private fun stringNames(valuesDir: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res(), "$valuesDir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        val out = HashSet<String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            // Only meaningful in values/, and only there does it mean "this one
            // is not copy" — a package name, a URL, a format string.
            if (e.getAttribute("translatable") == "false") continue
            out.add(e.getAttribute("name"))
        }
        return out
    }

    @Test
    fun `a string added to English is added to every locale or named as a gap`() {
        val english = stringNames("values")
        // Guards the guard: a parse that silently returns nothing compares
        // nothing and reports clean, which is the same output as a clean run.
        assertTrue(
            "only ${english.size} English strings were read — the scan has stopped matching",
            english.size >= 300
        )

        val newGaps = locales.flatMap { loc ->
            (english - stringNames("values-$loc"))
                .filterNot { deliberatelyEnglish(it) || it in KNOWN_GAPS }
                .map { "$loc: $it" }
        }.sorted()

        assertTrue(
            "these strings exist in English and nowhere else. Translate them, or " +
                "add the name to KNOWN_GAPS and say in the commit message why it " +
                "could not be done:\n" + newGaps.joinToString("\n"),
            newGaps.isEmpty()
        )
    }

    @Test
    fun `the backlog list holds nothing already translated or deleted`() {
        val english = stringNames("values")
        val stillMissing = locales.flatMap { loc -> english - stringNames("values-$loc") }.toSet()

        val deleted = KNOWN_GAPS.filterNot { it in english }
        val done = KNOWN_GAPS.filterNot { it in stillMissing || it in deleted }
        assertTrue(
            "remove these from KNOWN_GAPS — they no longer exist in English:\n" +
                deleted.sorted().joinToString("\n"),
            deleted.isEmpty()
        )
        assertTrue(
            "remove these from KNOWN_GAPS — they are translated everywhere now, " +
                "which is the whole point:\n" + done.sorted().joinToString("\n"),
            done.isEmpty()
        )
    }

    @Test
    fun `no locale carries a string English no longer has`() {
        val english = stringNames("values")
        val orphans = locales.flatMap { loc ->
            (stringNames("values-$loc") - english).map { "$loc: $it" }
        }.sorted()
        // How a rename leaves a corpse: the English key changes, the seven
        // translations keep the old name, and every locale silently falls back
        // to English for a string that is translated seven times over.
        // `UnusedResources` is a lint error here, but it does not see these.
        assertTrue(
            "these are translated but no longer exist in English:\n" +
                orphans.joinToString("\n"),
            orphans.isEmpty()
        )
    }
}
