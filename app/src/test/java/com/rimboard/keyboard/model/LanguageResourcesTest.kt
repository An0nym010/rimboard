package com.rimboard.keyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the language registry against the resource files that have to agree
 * with it.
 *
 * [Languages]' own doc comment spells the invariant out — "adding a language
 * means: one entry here, a layout function, a dictionary, a subtype in
 * method.xml and an entry pair in arrays.xml" — and nothing checked any of it.
 * The list lives in four files maintained by hand, and this project has already
 * shipped two copies of a list that silently fell behind the original: the
 * theme cycle, six palettes out of date, and the strip's tool labels, missing
 * the one tool every fresh install pins first.
 *
 * The failure modes here are all quiet. A code in arrays.xml that [Languages]
 * does not know is filtered straight back out by Prefs.languages, so the
 * checkbox appears to do nothing. A language missing from arrays.xml cannot be
 * switched on at all. Worst is entries and values drifting out of step: the row
 * labelled "Deutsch" then enables Spanish, and nothing anywhere complains.
 */
class LanguageResourcesTest {

    /** Unit tests run from the module directory; tolerate the project root too. */
    private fun res(): File {
        for (p in listOf("src/main/res", "app/src/main/res")) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        throw AssertionError("res directory not found from ${File(".").absolutePath}")
    }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res(), path))

    /** The <item> texts of the named string-array, in document order. */
    private fun arrayItems(name: String): List<String> {
        val doc = parse("values/arrays.xml")
        val arrays = doc.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val el = arrays.item(i) as org.w3c.dom.Element
            if (el.getAttribute("name") != name) continue
            val items = el.getElementsByTagName("item")
            return (0 until items.length).map { items.item(it).textContent.trim() }
        }
        throw AssertionError("no string-array named $name")
    }

    @Test
    fun `the language preference offers exactly the languages that exist`() {
        assertEquals(
            "arrays.xml lang_values must match Languages.codes, in order",
            Languages.codes,
            arrayItems("lang_values")
        )
    }

    @Test
    fun `each language checkbox is labelled with its own language`() {
        // entries and values pair up by position. Misalign them and every
        // checkbox below the mistake turns on the wrong language, with the
        // right name beside it.
        val entries = arrayItems("lang_entries")
        val values = arrayItems("lang_values")
        assertEquals("lang_entries and lang_values differ in length", values.size, entries.size)
        assertEquals(
            "labels must be each language's own name, in registry order",
            Languages.all.map { it.nativeName },
            entries
        )
    }

    @Test
    fun `every language has a keyboard subtype`() {
        // Without a subtype the language is invisible to the system's own
        // keyboard switcher, however well the app itself knows about it.
        val doc = parse("xml/method.xml")
        val subtypes = doc.getElementsByTagName("subtype")
        val tagged = (0 until subtypes.length).map {
            (subtypes.item(it) as org.w3c.dom.Element)
                .getAttribute("android:languageTag").take(2).lowercase()
        }
        val missing = Languages.codes.filter { it !in tagged }
        assertTrue("no subtype declared for: $missing", missing.isEmpty())
        val extra = tagged.filter { it !in Languages.codes }
        assertTrue("subtype for unknown language: $extra", extra.isEmpty())
    }

    @Test
    fun `every interface language actually has translations`() {
        // ui_lang_values drives the in-app language picker. A value with no
        // values-xx folder behind it silently renders in English, which reads
        // as the picker being broken.
        val values = arrayItems("ui_lang_values").filter { it != "system" }
        val entries = arrayItems("ui_lang_entries")
        assertEquals(
            "ui_lang_entries and ui_lang_values differ in length",
            values.size + 1, entries.size
        )
        val missing = values.filter {
            // "en" is the base resource folder, not a qualified one.
            it != "en" && !File(res(), "values-$it/strings.xml").isFile
        }
        assertTrue("interface language with no translations: $missing", missing.isEmpty())
    }

    @Test
    fun `the declared per-app locales match the interface languages`() {
        // locales_config is what Android's own per-app language screen reads.
        // Offering a locale there that the app has no strings for gives the
        // user a setting that changes nothing.
        val doc = parse("xml/locales_config.xml")
        val nodes = doc.getElementsByTagName("locale")
        val declared = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name")
        }.sorted()
        val offered = arrayItems("ui_lang_values").filter { it != "system" }.sorted()
        assertEquals("locales_config must match the interface languages", offered, declared)
    }
}
