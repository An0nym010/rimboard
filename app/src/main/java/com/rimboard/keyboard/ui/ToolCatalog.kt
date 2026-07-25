package com.rimboard.keyboard.ui

import com.rimboard.keyboard.R
import com.rimboard.keyboard.model.Codes

/**
 * Every action that can be pinned to the idle suggestion strip: a stable id for
 * preferences, the icon to draw, the code to fire, and a label for settings.
 *
 * Shared so the keyboard and the settings picker can never disagree about what
 * a tool id means — they each used to carry their own copy of this list.
 *
 * "GIF" and "Sticker" are here but are not universally usable: they need the
 * online build, the network switch on, and a KLIPY key. They stay in the
 * catalog on every build so the pinned-tool order does not shift underneath
 * someone who switches flavors — they report why they are unavailable instead
 * of vanishing. Scan-text remains absent.
 */
object ToolCatalog {

    class Tool(val id: String, val icon: Int, val code: Int, val labelRes: Int)

    val all: List<Tool> = listOf(
        Tool("toolbar", Icons.GRID, Codes.TOOLBAR_PANEL, R.string.tb_all_tools),
        Tool("undo", Icons.UNDO, Codes.UNDO, R.string.tb_undo),
        Tool("redo", Icons.REDO, Codes.REDO, R.string.tb_redo),
        Tool("copy", Icons.COPY, Codes.COPY, R.string.tb_copy),
        Tool("paste", Icons.PASTE, Codes.PASTE, R.string.tb_paste),
        Tool("cut", Icons.CUT, Codes.CUT, R.string.tb_cut),
        Tool("selectall", Icons.SELECT_ALL, Codes.SELECT_ALL, R.string.tb_selectall),
        Tool("onehanded", Icons.ONE_HANDED, Codes.ONE_HANDED, R.string.tb_onehanded),
        Tool("incognito", Icons.INCOGNITO, Codes.INCOGNITO, R.string.tb_incognito),
        Tool("edit", Icons.EDIT, Codes.EDIT_PANEL, R.string.tb_edit),
        Tool("floating", Icons.FLOATING, Codes.FLOATING, R.string.tb_floating),
        Tool("numpad", Icons.KEYBOARD, Codes.NUMPAD, R.string.tb_numpad),
        Tool("hide", Icons.HIDE, Codes.HIDE_KB, R.string.tb_hide),
        Tool("emoji", Icons.EMOJI, Codes.EMOJI, R.string.tb_emoji),
        Tool("clipboard", Icons.CLIPBOARD, Codes.CLIPBOARD, R.string.tb_clipboard),
        Tool("language", Icons.GLOBE, Codes.LANG, R.string.tb_language),
        Tool("translate", Icons.TRANSLATE, Codes.TRANSLATE, R.string.tb_translate),
        Tool("gif", Icons.SEARCH, Codes.GIF, R.string.tb_gif),
        Tool("sticker", Icons.EMOJI, Codes.STICKER, R.string.tb_sticker),
        Tool("proofread", Icons.SPELLCHECK, Codes.PROOFREAD, R.string.tb_proofread),
        Tool("share", Icons.SHARE, Codes.SHARE, R.string.tb_share),
        Tool("theme", Icons.THEME, Codes.THEME, R.string.tb_theme),
        Tool("resize", Icons.RESIZE, Codes.RESIZE, R.string.tb_resize),
        Tool("settings", Icons.SETTINGS, Codes.SETTINGS, R.string.tb_settings)
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Tool? = byId[id]

    private val byCode = all.associateBy { it.code }

    /**
     * The tool that fires [code], or null if no pinnable tool does.
     *
     * Well-defined because the codes are unique, which ToolCatalogTest asserts.
     * Exists so the suggestion strip can label an icon from the catalog rather
     * than from a second hand-written code-to-label table, which had drifted.
     */
    fun byCode(code: Int): Tool? = byCode[code]

    /** Catalog order, used when the user has never arranged the list. */
    val defaultOrder: List<String> = all.map { it.id }
}
