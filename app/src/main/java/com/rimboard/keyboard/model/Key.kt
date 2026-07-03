package com.rimboard.keyboard.model

import java.util.Locale

/** Special (non-character) key codes. Positive codes are Unicode code points. */
object Codes {
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val MODE_SYM = -3
    const val MODE_ABC = -4
    const val MODE_SYM2 = -5
    const val ENTER = -6
    const val LANG = -7
    const val EMOJI = -8
    const val SETTINGS = -9
    const val INCOGNITO = -10
    const val IME_PICKER = -11
    const val SPACE = 32
}

enum class KeyType { CHARACTER, FUNCTION, SPACE, ENTER }

data class Key(
    val code: Int,
    val label: String,
    val width: Float = 1f,
    val type: KeyType = KeyType.CHARACTER,
    val repeatable: Boolean = false,
    val hint: String? = null,
    val popup: List<Key> = emptyList()
)

data class Row(val keys: List<Key>)

enum class LayoutKind { MAIN, SYMBOLS, SYMBOLS2, NUMPAD }

data class KeyboardLayout(
    val rows: List<Row>,
    val unitsPerRow: Float,
    val locale: Locale,
    val kind: LayoutKind
)
