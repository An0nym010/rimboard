package com.rimboard.keyboard.model

import java.util.Locale

/**
 * Layout definitions. Widths are in "units"; each layout declares how many
 * units make up a full row, mirroring GBoard's proportions:
 *   EN: 10 units per row, TR: 12 units per row (extra ğüşiöç keys).
 */
object Layouts {

    private val EN: Locale = Locale.ENGLISH
    private val TR: Locale = Locale.forLanguageTag("tr")

    // ---------- helpers ----------

    private fun chars(s: String): List<Key> = s.map { Key(it.code, it.toString()) }

    private fun k(c: Char, popup: String = "", hint: String? = null, w: Float = 1f): Key =
        Key(c.code, c.toString(), width = w, hint = hint, popup = chars(popup))

    private fun shift(w: Float) = Key(Codes.SHIFT, "\u21E7", width = w, type = KeyType.FUNCTION)

    private fun backspace(w: Float) =
        Key(Codes.BACKSPACE, "\u232B", width = w, type = KeyType.FUNCTION, repeatable = true)

    private fun enter(w: Float) = Key(Codes.ENTER, "\u21B5", width = w, type = KeyType.ENTER)

    private fun space(w: Float) = Key(Codes.SPACE, " ", width = w, type = KeyType.SPACE)

    private fun modeSym(w: Float) = Key(Codes.MODE_SYM, "?123", width = w, type = KeyType.FUNCTION)

    private fun modeAbc(w: Float) = Key(Codes.MODE_ABC, "ABC", width = w, type = KeyType.FUNCTION)

    private fun modeSym2(w: Float) = Key(Codes.MODE_SYM2, "=\\<", width = w, type = KeyType.FUNCTION)

    private fun globe() = Key(
        Codes.LANG, "\uD83C\uDF10", type = KeyType.FUNCTION,
        popup = listOf(Key(Codes.IME_PICKER, "\u2328", type = KeyType.FUNCTION))
    )

    /** Long-press menu on the comma key: language, settings, incognito, emoji. */
    private val commaMenu = listOf(
        Key(Codes.LANG, "\uD83C\uDF10", type = KeyType.FUNCTION),
        Key(Codes.SETTINGS, "\u2699", type = KeyType.FUNCTION),
        Key(Codes.INCOGNITO, "\uD83D\uDD76", type = KeyType.FUNCTION),
        Key(Codes.EMOJI, "\uD83D\uDE0A", type = KeyType.FUNCTION)
    )

    private fun comma() = Key(','.code, ",", popup = commaMenu)

    private fun period() = Key('.'.code, ".", popup = chars("\u2026!?,-:;'\"@"))

    // ---------- main layouts ----------

    fun qwertyEn(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val rows = mutableListOf<Row>()
        if (numberRow) rows += Row(chars("1234567890"))
        val h = !numberRow  // digit hints only when the number row is hidden
        rows += Row(listOf(
            k('q', "1", if (h) "1" else null),
            k('w', "2", if (h) "2" else null),
            k('e', "3\u00E8\u00E9\u00EA\u00EB\u0113", if (h) "3" else null),
            k('r', "4", if (h) "4" else null),
            k('t', "5", if (h) "5" else null),
            k('y', "6\u00FD\u00FF", if (h) "6" else null),
            k('u', "7\u00FB\u00FC\u00F9\u00FA\u016B", if (h) "7" else null),
            k('i', "8\u00EE\u00EF\u00ED\u012B\u00EC", if (h) "8" else null),
            k('o', "9\u00F4\u00F6\u00F2\u00F3\u00F5\u014D", if (h) "9" else null),
            k('p', "0", if (h) "0" else null)
        ))
        rows += Row(listOf(
            k('a', "\u00E0\u00E1\u00E2\u00E4\u00E3\u00E5\u0101"),
            k('s', "\u00DF\u015B\u0161"),
            k('d'), k('f'), k('g'), k('h'), k('j'), k('k'),
            k('l', "\u0142")
        ))
        rows += Row(listOf(
            shift(1.5f),
            k('z', "\u017E\u017A\u017C"), k('x'),
            k('c', "\u00E7\u0107\u010D"),
            k('v'), k('b'),
            k('n', "\u00F1\u0144"),
            k('m'),
            backspace(1.5f)
        ))
        rows += Row(
            if (showGlobe) listOf(modeSym(1.5f), globe(), comma(), space(4f), period(), enter(1.5f))
            else listOf(modeSym(1.5f), comma(), space(5f), period(), enter(1.5f))
        )
        return KeyboardLayout(rows, 10f, EN, LayoutKind.MAIN)
    }

    fun qwertyTr(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val rows = mutableListOf<Row>()
        if (numberRow) rows += Row("1234567890".map { Key(it.code, it.toString(), width = 1.2f) })
        val h = !numberRow
        rows += Row(listOf(
            k('q', "1", if (h) "1" else null),
            k('w', "2", if (h) "2" else null),
            k('e', "3\u00E9", if (h) "3" else null),
            k('r', "4", if (h) "4" else null),
            k('t', "5", if (h) "5" else null),
            k('y', "6", if (h) "6" else null),
            k('u', "7\u00FB", if (h) "7" else null),
            k('\u0131', "8", if (h) "8" else null),
            k('o', "9\u00F4", if (h) "9" else null),
            k('p', "0", if (h) "0" else null),
            k('\u011F'),
            k('\u00FC')
        ))
        rows += Row(listOf(
            k('a', "\u00E2"),
            k('s'), k('d'), k('f'), k('g'), k('h'), k('j'), k('k'), k('l'),
            k('\u015F'),
            k('i', "\u00EE")
        ))
        rows += Row(listOf(
            shift(1.5f),
            k('z'), k('x'), k('c'), k('v'), k('b'), k('n'), k('m'),
            k('\u00F6'),
            k('\u00E7'),
            backspace(1.5f)
        ))
        rows += Row(
            if (showGlobe) listOf(modeSym(2f), globe(), comma(), space(5f), period(), enter(2f))
            else listOf(modeSym(2f), comma(), space(6f), period(), enter(2f))
        )
        return KeyboardLayout(rows, 12f, TR, LayoutKind.MAIN)
    }

    // ---------- symbols ----------

    fun symbols(locale: Locale): KeyboardLayout {
        val rows = mutableListOf<Row>()
        rows += Row(chars("1234567890"))
        rows += Row(listOf(
            k('@'), k('#'),
            k('$', "\u20BA\u20AC\u00A3\u00A5\u00A2"),
            k('_'), k('&'),
            k('-', "\u2013\u2014\u00B7"),
            k('+', "\u00B1"),
            k('('), k(')'), k('/')
        ))
        rows += Row(listOf(
            modeSym2(1.5f),
            k('*', "\u2020\u2021\u2605"),
            k('"', "\u201E\u201C\u201D\u00AB\u00BB"),
            k('\'', "\u201A\u2018\u2019\u2039\u203A"),
            k(':'), k(';'),
            k('!', "\u00A1"),
            k('?', "\u00BF"),
            backspace(1.5f)
        ))
        rows += Row(listOf(modeAbc(1.5f), comma(), space(5f), period(), enter(1.5f)))
        return KeyboardLayout(rows, 10f, locale, LayoutKind.SYMBOLS)
    }

    fun symbols2(locale: Locale): KeyboardLayout {
        val rows = mutableListOf<Row>()
        rows += Row(chars("~`|\u2022\u221A\u03C0\u00F7\u00D7\u00B6\u2206"))
        rows += Row(chars("\u00A3\u20AC\u20BA\u00A5\u00A2^\u00B0={}"))
        rows += Row(listOf(
            modeSym(1.5f),
            k('%', "\u2030"),
            k('\u00A9'), k('\u00AE'), k('\u2122'), k('\u2713'),
            k('['), k(']'),
            backspace(1.5f)
        ))
        rows += Row(listOf(
            modeAbc(1.5f),
            k('<', "\u2264\u00AB"),
            space(5f),
            k('>', "\u2265\u00BB"),
            enter(1.5f)
        ))
        return KeyboardLayout(rows, 10f, locale, LayoutKind.SYMBOLS2)
    }

    // ---------- numeric pad ----------

    fun numpad(locale: Locale): KeyboardLayout {
        val rows = listOf(
            Row(listOf(k('1'), k('2'), k('3'), backspace(1f))),
            Row(listOf(k('4'), k('5'), k('6'), k('.'))),
            Row(listOf(k('7'), k('8'), k('9'), k('-', "+*/#()"))),
            Row(listOf(modeAbc(1f), k('0', "+"), k(','), enter(1f)))
        )
        return KeyboardLayout(rows, 4f, locale, LayoutKind.NUMPAD)
    }
}
