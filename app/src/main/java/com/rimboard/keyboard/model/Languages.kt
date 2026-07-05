package com.rimboard.keyboard.model

import java.util.Locale

/**
 * Registry of supported languages. Adding a language means: one entry here,
 * a layout function in [Layouts], a dictionary in assets/dictionaries/, a
 * subtype in res/xml/method.xml and an entry pair in res/values/arrays.xml.
 */
object Languages {

    class Lang(
        val code: String,
        val nativeName: String,
        val locale: Locale,
        val layout: (numberRow: Boolean, showGlobe: Boolean) -> KeyboardLayout
    )

    val all: List<Lang> = listOf(
        Lang("en", "English", Locale.ENGLISH, Layouts::qwertyEn),
        Lang("tr", "Türkçe", Locale.forLanguageTag("tr"), Layouts::qwertyTr),
        Lang("de", "Deutsch", Locale.GERMAN, Layouts::qwertzDe),
        Lang("es", "Español", Locale.forLanguageTag("es"), Layouts::qwertyEs),
        Lang("fr", "Français", Locale.FRENCH, Layouts::azertyFr),
        Lang("it", "Italiano", Locale.ITALIAN, Layouts::qwertyIt),
        Lang("pt", "Português", Locale.forLanguageTag("pt"), Layouts::qwertyPt),
        Lang("ru", "Русский", Locale.forLanguageTag("ru"), Layouts::cyrillicRu),
        Lang("nl", "Nederlands", Locale.forLanguageTag("nl"), Layouts::qwertyNl),
        Lang("pl", "Polski", Locale.forLanguageTag("pl"), Layouts::qwertyPl),
        Lang("sv", "Svenska", Locale.forLanguageTag("sv"), Layouts::qwertySv),
        Lang("id", "Bahasa Indonesia", Locale.forLanguageTag("id"), Layouts::qwertyId),
        Lang("ro", "Rom\u00e2n\u0103", Locale.forLanguageTag("ro"), Layouts::qwertyRo),
        Lang("cs", "\u010ce\u0161tina", Locale.forLanguageTag("cs"), Layouts::qwertyCs)
    )

    val codes: List<String> = all.map { it.code }

    private val map = all.associateBy { it.code }

    fun byCode(code: String): Lang = map[code] ?: all.first()
}
