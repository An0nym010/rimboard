package com.rimboard.keyboard.model

import java.util.Locale

/**
 * Layout definitions. Widths are in "units"; each layout declares how many
 * units make up a full row (GBoard proportions): Latin layouts are 10 units
 * wide, Turkish 12, Russian 11. Shorter rows are centered automatically.
 */
object Layouts {

    // ---------- shared building blocks ----------

    private fun chars(s: String): List<Key> = s.map { Key(it.code, it.toString()) }

    private fun k(c: Char, popup: String = ""): Key =
        Key(c.code, c.toString(), popup = chars(popup))

    private fun shift(w: Float) = Key(Codes.SHIFT, "\u21E7", width = w, type = KeyType.FUNCTION)

    private fun backspace(w: Float) =
        Key(Codes.BACKSPACE, "\u232B", width = w, type = KeyType.FUNCTION, repeatable = true)

    private fun enter(w: Float) = Key(
        Codes.ENTER, "\u21B5", width = w, type = KeyType.ENTER,
        popup = listOf(Key('\n'.code, "\u21B5")) // long-press: plain newline
    )

    private fun space(w: Float) = Key(Codes.SPACE, " ", width = w, type = KeyType.SPACE)

    private fun modeSym(w: Float) = Key(Codes.MODE_SYM, "?123", width = w, type = KeyType.FUNCTION)
    private fun modeAbc(w: Float) = Key(Codes.MODE_ABC, "ABC", width = w, type = KeyType.FUNCTION)
    private fun modeSym2(w: Float) = Key(Codes.MODE_SYM2, "=\\<", width = w, type = KeyType.FUNCTION)

    private fun globe() = Key(
        Codes.LANG, "\uD83C\uDF10", type = KeyType.FUNCTION,
        popup = listOf(Key(Codes.IME_PICKER, "\u2328", type = KeyType.FUNCTION))
    )

    /** Long-press menu on the comma key. Language switching lives on the globe
     *  key and clipboard/settings live on the suggestion strip, so this holds
     *  only: edit panel, one-handed, floating, incognito, emoji. */
    private val commaMenu = listOf(
        Key(Codes.EDIT_PANEL, "\u2702", type = KeyType.FUNCTION),
        Key(Codes.ONE_HANDED, "\uD83E\uDD1A", type = KeyType.FUNCTION),
        Key(Codes.FLOATING, "\u25A3", type = KeyType.FUNCTION),
        Key(Codes.INCOGNITO, "\uD83D\uDD76", type = KeyType.FUNCTION),
        Key(Codes.EMOJI, "\uD83D\uDE0A", type = KeyType.FUNCTION)
    )

    private fun comma() = Key(','.code, ",", popup = commaMenu)
    private fun period() = Key('.'.code, ".", popup = chars("\u2026!?,-:;'\"@"))

    /** Top letter row: digit hints/popups mapped onto the first ten keys. */
    private fun topRow(
        letters: String, pops: Map<Char, String>, hintsOn: Boolean, w: Float = 1f
    ): Row {
        val digits = "1234567890"
        return Row(letters.mapIndexed { i, ch ->
            val d = if (i < digits.length) digits[i].toString() else null
            Key(
                ch.code, ch.toString(), width = w,
                hint = if (hintsOn && d != null) d else null,
                popup = chars((d ?: "") + (pops[ch] ?: ""))
            )
        })
    }

    private fun midRow(letters: String, pops: Map<Char, String>): Row =
        Row(letters.map { ch -> k(ch, pops[ch] ?: "") })

    private fun thirdRow(letters: String, pops: Map<Char, String>, sideW: Float = 1.5f): Row =
        Row(
            listOf(shift(sideW)) +
                letters.map { ch -> k(ch, pops[ch] ?: "") } +
                listOf(backspace(sideW))
        )

    private fun bottomRow(total: Float, showGlobe: Boolean, sideW: Float): Row =
        if (showGlobe) Row(
            listOf(
                modeSym(sideW), globe(), comma(),
                space(total - 2 * sideW - 3f), period(), enter(sideW)
            )
        ) else Row(
            listOf(
                modeSym(sideW), comma(),
                space(total - 2 * sideW - 2f), period(), enter(sideW)
            )
        )

    private fun assemble(
        letterRows: List<Row>, units: Float, locale: Locale,
        numberRowKeys: Row?, showGlobe: Boolean, sideW: Float
    ): KeyboardLayout {
        val all = mutableListOf<Row>()
        if (numberRowKeys != null) all += numberRowKeys
        all += letterRows
        all += bottomRow(units, showGlobe, sideW)
        return KeyboardLayout(all, units, locale, LayoutKind.MAIN)
    }

    private fun plainNumberRow(w: Float = 1f): Row {
        val syms = "!@#\$%^&*()"
        return Row("1234567890".mapIndexed { i, ch ->
            Key(ch.code, ch.toString(), width = w, popup = chars(syms[i].toString()))
        })
    }

    // ---------- main layouts ----------

    fun qwertyEn(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'e' to "\u00E8\u00E9\u00EA\u00EB\u0113",
            'y' to "\u00FD\u00FF",
            'u' to "\u00FB\u00FC\u00F9\u00FA\u016B",
            'i' to "\u00EE\u00EF\u00ED\u012B\u00EC",
            'o' to "\u00F4\u00F6\u00F2\u00F3\u00F5\u014D",
            'a' to "\u00E0\u00E1\u00E2\u00E4\u00E3\u00E5\u0101",
            's' to "\u00DF\u015B\u0161",
            'l' to "\u0142",
            'z' to "\u017E\u017A\u017C",
            'c' to "\u00E7\u0107\u010D",
            'n' to "\u00F1\u0144"
        )
        return assemble(
            listOf(
                topRow("qwertyuiop", pops, !numberRow),
                midRow("asdfghjkl", pops),
                thirdRow("zxcvbnm", pops)
            ),
            10f, Locale.ENGLISH,
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun qwertyTr(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'e' to "\u00E9", 'u' to "\u00FB", 'o' to "\u00F4",
            'a' to "\u00E2", 'i' to "\u00EE"
        )
        return assemble(
            listOf(
                topRow("qwertyu\u0131op\u011F\u00FC", pops, !numberRow),
                midRow("asdfghjkl\u015Fi", pops),
                thirdRow("zxcvbnm\u00F6\u00E7", pops)
            ),
            12f, Locale.forLanguageTag("tr"),
            if (numberRow) plainNumberRow(1.2f) else null,
            showGlobe, 2f
        )
    }

    fun qwertzDe(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'a' to "\u00E4", 'o' to "\u00F6", 'u' to "\u00FC",
            's' to "\u00DF", 'e' to "\u00E9"
        )
        return assemble(
            listOf(
                topRow("qwertzuiop", pops, !numberRow),
                midRow("asdfghjkl", pops),
                thirdRow("yxcvbnm", pops)
            ),
            10f, Locale.GERMAN,
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun qwertyEs(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'a' to "\u00E1", 'e' to "\u00E9", 'i' to "\u00ED",
            'o' to "\u00F3", 'u' to "\u00FA\u00FC"
        )
        return assemble(
            listOf(
                topRow("qwertyuiop", pops, !numberRow),
                midRow("asdfghjkl\u00F1", pops),
                thirdRow("zxcvbnm", pops)
            ),
            10f, Locale.forLanguageTag("es"),
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun azertyFr(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'a' to "\u00E0\u00E2\u00E6",
            'e' to "\u00E9\u00E8\u00EA\u00EB",
            'u' to "\u00F9\u00FB\u00FC",
            'i' to "\u00EE\u00EF",
            'o' to "\u00F4\u0153",
            'y' to "\u00FF",
            'c' to "\u00E7"
        )
        return assemble(
            listOf(
                topRow("azertyuiop", pops, !numberRow),
                midRow("qsdfghjklm", pops),
                thirdRow("wxcvbn'", pops)
            ),
            10f, Locale.FRENCH,
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun qwertyIt(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'a' to "\u00E0", 'e' to "\u00E8\u00E9", 'i' to "\u00EC\u00ED",
            'o' to "\u00F2\u00F3", 'u' to "\u00F9\u00FA"
        )
        return assemble(
            listOf(
                topRow("qwertyuiop", pops, !numberRow),
                midRow("asdfghjkl", pops),
                thirdRow("zxcvbnm", pops)
            ),
            10f, Locale.ITALIAN,
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun qwertyPt(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            'a' to "\u00E1\u00E2\u00E3\u00E0",
            'e' to "\u00E9\u00EA",
            'i' to "\u00ED",
            'o' to "\u00F3\u00F4\u00F5",
            'u' to "\u00FA",
            'c' to "\u00E7"
        )
        return assemble(
            listOf(
                topRow("qwertyuiop", pops, !numberRow),
                midRow("asdfghjkl", pops),
                thirdRow("zxcvbnm", pops)
            ),
            10f, Locale.forLanguageTag("pt"),
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun cyrillicRu(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            '\u0435' to "\u0451", // е -> ё
            '\u044C' to "\u044A"  // ь -> ъ
        )
        return assemble(
            listOf(
                topRow("\u0439\u0446\u0443\u043A\u0435\u043D\u0433\u0448\u0449\u0437\u0445", pops, !numberRow),
                midRow("\u0444\u044B\u0432\u0430\u043F\u0440\u043E\u043B\u0434\u0436\u044D", pops),
                thirdRow("\u044F\u0447\u0441\u043C\u0438\u0442\u044C\u0431\u044E", pops, sideW = 1f)
            ),
            11f, Locale.forLanguageTag("ru"),
            if (numberRow) plainNumberRow(1.1f) else null,
            showGlobe, 2f
        )
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

    private fun latin(
        pops: Map<Char, String>, tag: String, numberRow: Boolean, showGlobe: Boolean
    ): KeyboardLayout = assemble(
        listOf(
            topRow("qwertyuiop", pops, !numberRow),
            midRow("asdfghjkl", pops),
            thirdRow("zxcvbnm", pops)
        ),
        10f, Locale.forLanguageTag(tag),
        if (numberRow) plainNumberRow() else null,
        showGlobe, 1.5f
    )

    fun qwertyNl(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf(
            'e' to "\u00E9\u00E8\u00EA\u00EB", 'o' to "\u00F3\u00F6\u00F4",
            'i' to "\u00ED\u00EF\u00EE", 'a' to "\u00E1\u00E4", 'u' to "\u00FA\u00FC"
        ), "nl", numberRow, showGlobe
    )

    fun qwertyPl(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf(
            'a' to "\u0105", 'c' to "\u0107", 'e' to "\u0119", 'l' to "\u0142",
            'n' to "\u0144", 'o' to "\u00F3", 's' to "\u015B", 'z' to "\u017C\u017A"
        ), "pl", numberRow, showGlobe
    )

    fun qwertySv(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf('a' to "\u00E5\u00E4", 'o' to "\u00F6", 'e' to "\u00E9"),
        "sv", numberRow, showGlobe
    )

    fun qwertyId(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf('e' to "\u00E9"), "id", numberRow, showGlobe
    )

    private fun qwertz(
        pops: Map<Char, String>, tag: String, numberRow: Boolean, showGlobe: Boolean
    ): KeyboardLayout = assemble(
        listOf(
            topRow("qwertzuiop", pops, !numberRow),
            midRow("asdfghjkl", pops),
            thirdRow("yxcvbnm", pops)
        ),
        10f, Locale.forLanguageTag(tag),
        if (numberRow) plainNumberRow() else null,
        showGlobe, 1.5f
    )

    fun qwertyDa(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf('a' to "\u00E5\u00E6", 'o' to "\u00F8", 'e' to "\u00E9"),
        "da", numberRow, showGlobe
    )

    fun qwertyNo(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf('a' to "\u00E5\u00E6", 'o' to "\u00F8", 'e' to "\u00E9"),
        "no", numberRow, showGlobe
    )

    fun qwertyFi(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf('a' to "\u00E4\u00E5", 'o' to "\u00F6"),
        "fi", numberRow, showGlobe
    )

    fun qwertzHu(numberRow: Boolean, showGlobe: Boolean) = qwertz(
        mapOf(
            'a' to "\u00E1", 'e' to "\u00E9", 'i' to "\u00ED",
            'o' to "\u00F3\u00F6\u0151", 'u' to "\u00FA\u00FC\u0171"
        ),
        "hu", numberRow, showGlobe
    )

    fun qwertzHr(numberRow: Boolean, showGlobe: Boolean) = qwertz(
        mapOf(
            'c' to "\u010D\u0107", 's' to "\u0161",
            'z' to "\u017E", 'd' to "\u0111"
        ),
        "hr", numberRow, showGlobe
    )

    fun qwertzSk(numberRow: Boolean, showGlobe: Boolean) = qwertz(
        mapOf(
            'a' to "\u00E1\u00E4", 'e' to "\u00E9", 'i' to "\u00ED",
            'o' to "\u00F3\u00F4", 'u' to "\u00FA", 'y' to "\u00FD",
            'c' to "\u010D", 's' to "\u0161", 'z' to "\u017E",
            't' to "\u0165", 'd' to "\u010F", 'n' to "\u0148",
            'l' to "\u013A\u013E", 'r' to "\u0155"
        ),
        "sk", numberRow, showGlobe
    )

    fun cyrillicUk(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            '\u0433' to "\u0491", // г -> ґ
            '\u0445' to "\u0457"  // х -> ї
        )
        return assemble(
            listOf(
                topRow("\u0439\u0446\u0443\u043A\u0435\u043D\u0433\u0448\u0449\u0437\u0445", pops, !numberRow),
                midRow("\u0444\u0456\u0432\u0430\u043F\u0440\u043E\u043B\u0434\u0436\u0454", pops),
                thirdRow("\u044F\u0447\u0441\u043C\u0438\u0442\u044C\u0431\u044E", pops, sideW = 1f)
            ),
            11f, Locale.forLanguageTag("uk"),
            if (numberRow) plainNumberRow(1.1f) else null,
            showGlobe, 2f
        )
    }

    fun greekEl(numberRow: Boolean, showGlobe: Boolean): KeyboardLayout {
        val pops = mapOf(
            '\u03B1' to "\u03AC", '\u03B5' to "\u03AD", '\u03B7' to "\u03AE",
            '\u03B9' to "\u03AF\u03CA\u0390", '\u03BF' to "\u03CC",
            '\u03C5' to "\u03CD\u03CB\u03B0", '\u03C9' to "\u03CE",
            '\u03C3' to "\u03C2"
        )
        return assemble(
            listOf(
                topRow("\u03C2\u03B5\u03C1\u03C4\u03C5\u03B8\u03B9\u03BF\u03C0", pops, !numberRow),
                midRow("\u03B1\u03C3\u03B4\u03C6\u03B3\u03B7\u03BE\u03BA\u03BB", pops),
                thirdRow("\u03B6\u03C7\u03C8\u03C9\u03B2\u03BD\u03BC", pops)
            ),
            10f, Locale.forLanguageTag("el"),
            if (numberRow) plainNumberRow() else null,
            showGlobe, 1.5f
        )
    }

    fun qwertyRo(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf(
            'a' to "\u0103\u00E2", 'i' to "\u00EE",
            's' to "\u0219", 't' to "\u021B"
        ), "ro", numberRow, showGlobe
    )

    fun qwertyCs(numberRow: Boolean, showGlobe: Boolean) = latin(
        mapOf(
            'e' to "\u00E9\u011B", 's' to "\u0161", 'c' to "\u010D", 'r' to "\u0159",
            'z' to "\u017E", 'y' to "\u00FD", 'a' to "\u00E1", 'i' to "\u00ED",
            'u' to "\u00FA\u016F", 'o' to "\u00F3", 'd' to "\u010F",
            't' to "\u0165", 'n' to "\u0148"
        ), "cs", numberRow, showGlobe
    )
}
