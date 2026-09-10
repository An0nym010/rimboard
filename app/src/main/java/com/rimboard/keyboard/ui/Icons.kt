package com.rimboard.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import com.rimboard.keyboard.R
import com.rimboard.keyboard.model.Codes

/**
 * The tool icons for the keyboard chrome: one VectorDrawable per tool, tinted
 * with the theme so the interface looks identical on every device.
 *
 * **There used to be two sets behind this object** — these drawables, and a
 * hand-drawn `Canvas` fallback that [draw] used whenever it had never been
 * handed a `Context`. While the two were renderings of one design that cost
 * nothing. After the redesign they were two *different* designs, and a view
 * that drew without attaching produced wrong artwork rather than none —
 * silently, with nothing logged.
 *
 * [draw] now takes the `Context` it needs, so there is no path that reaches a
 * fallback and no fallback to reach. The compiler enforces what `IconSetTest`
 * used to scan the source for.
 */
object Icons {

    const val GLOBE = 1
    const val CLIPBOARD = 2
    const val EDIT = 3
    const val ONE_HANDED = 4
    const val FLOATING = 5
    const val INCOGNITO = 6
    const val SETTINGS = 7
    const val EMOJI = 8
    const val KEYBOARD = 9
    const val PIN = 10
    const val TRASH = 11
    const val TRANSLATE = 12
    const val UNDO = 13
    const val REDO = 14
    const val SEARCH = 15
    const val COPY = 16
    const val PASTE = 17
    const val CUT = 18
    const val SELECT_ALL = 19
    const val HIDE = 20
    const val SHARE = 21
    const val THEME = 22
    const val RESIZE = 23
    const val CHEVRON = 24      // expand ">"
    const val CHEVRON_L = 25    // collapse "<"
    const val GRID = 26         // all tools
    const val SPELLCHECK = 27   // proofread: a tick over a text baseline
    const val GIF = 28          // the GIF search, a magnifier holding a "G"

    fun forCode(code: Int): Int? = when (code) {
        Codes.LANG -> GLOBE
        Codes.TOOLBAR_PANEL -> GRID
        Codes.CLIPBOARD -> CLIPBOARD
        Codes.EDIT_PANEL -> EDIT
        Codes.ONE_HANDED -> ONE_HANDED
        Codes.FLOATING -> FLOATING
        Codes.INCOGNITO -> INCOGNITO
        Codes.SETTINGS -> SETTINGS
        Codes.EMOJI -> EMOJI
        Codes.IME_PICKER -> KEYBOARD
        Codes.UNDO -> UNDO
        Codes.REDO -> REDO
        Codes.COPY -> COPY
        Codes.PASTE -> PASTE
        Codes.CUT -> CUT
        Codes.SELECT_ALL -> SELECT_ALL
        Codes.HIDE_KB -> HIDE
        Codes.NUMPAD -> KEYBOARD
        Codes.TRANSLATE -> TRANSLATE
        Codes.SHARE -> SHARE
        Codes.THEME -> THEME
        Codes.RESIZE -> RESIZE
        else -> null
    }

    fun forLabel(label: String): Int? = when (label) {
        "🔍" -> SEARCH
        "🕶" -> INCOGNITO
        else -> null
    }

    // ---- vector set ------------------------------------------------------

    /**
     * The twenty-eight drawables, and why they are one set.
     *
     * **All twenty-eight are RimBoard's own**. Twenty-three came
     * from the light-theme design canvas when the redesign was ported; the
     * last five — the two chevrons, the pin, the trash and the search
     * magnifier — were drawn afterwards, because the canvas did not cover
     * them and a row mixing two stroke weights is the inconsistency the
     * redesign existed to remove. Nothing here is Lucide any more, and
     * `NOTICE` no longer carries their attribution.
     *
     * The search magnifier is deliberately [GIF] with its "G" taken out, so
     * the two match by construction rather than by eye — they are one icon
     * and a variant of it, and drawing them separately would have let them
     * drift.
     *
     * VectorDrawable takes path data only, so the conversion had to turn every
     * `<rect>` and `<circle>` into arcs and expand `stroke-dasharray` — which
     * it does not support at all — into separate dashes. Every one was
     * rendered back from its committed `pathData` and compared against the
     * source symbol before landing; the ones worth doubting were the dashed
     * select-all, the half-filled theme disc and the filled dots on emoji,
     * keyboard and settings.
     */
    private var appContext: android.content.Context? = null
    private val vectorRes = HashMap<Int, Int>()
    private val vectorCache = HashMap<Int, android.graphics.drawable.Drawable?>()

    /**
     * Keeps the application context and wires the table. Idempotent, and
     * called by [draw] itself, so no caller has to remember it.
     */
    private fun attach(context: android.content.Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Direct R references rather than getIdentifier: these are checked at
        // compile time, and a name-only lookup would be stripped by resource
        // shrinking in any build that turns it on.
        vectorRes[CHEVRON] = R.drawable.ic_tool_chevron_right
        vectorRes[CHEVRON_L] = R.drawable.ic_tool_chevron_left
        vectorRes[SETTINGS] = R.drawable.ic_tool_settings
        vectorRes[CLIPBOARD] = R.drawable.ic_tool_clipboard
        vectorRes[GRID] = R.drawable.ic_tool_grid
        vectorRes[GLOBE] = R.drawable.ic_tool_globe
        vectorRes[EDIT] = R.drawable.ic_tool_edit
        vectorRes[TRASH] = R.drawable.ic_tool_trash
        vectorRes[UNDO] = R.drawable.ic_tool_undo
        vectorRes[REDO] = R.drawable.ic_tool_redo
        vectorRes[CUT] = R.drawable.ic_tool_cut
        vectorRes[COPY] = R.drawable.ic_tool_copy
        vectorRes[TRANSLATE] = R.drawable.ic_tool_translate
        vectorRes[THEME] = R.drawable.ic_tool_theme
        vectorRes[SHARE] = R.drawable.ic_tool_share
        vectorRes[INCOGNITO] = R.drawable.ic_tool_incognito
        vectorRes[HIDE] = R.drawable.ic_tool_hide
        vectorRes[PIN] = R.drawable.ic_tool_pin
        vectorRes[SEARCH] = R.drawable.ic_tool_search
        vectorRes[PASTE] = R.drawable.ic_tool_paste
        vectorRes[SELECT_ALL] = R.drawable.ic_tool_selectall
        vectorRes[RESIZE] = R.drawable.ic_tool_resize
        vectorRes[FLOATING] = R.drawable.ic_tool_floating
        vectorRes[ONE_HANDED] = R.drawable.ic_tool_onehanded
        vectorRes[EMOJI] = R.drawable.ic_tool_emoji
        vectorRes[KEYBOARD] = R.drawable.ic_tool_keyboard
        // Both new with the redesign: proofread had only the hand-drawn
        // glyph, and the GIF tool used to borrow SEARCH.
        vectorRes[SPELLCHECK] = R.drawable.ic_tool_spellcheck
        vectorRes[GIF] = R.drawable.ic_tool_gif
    }

    private fun vector(icon: Int): android.graphics.drawable.Drawable? {
        // Never cache before attach(): a miss recorded then would be permanent.
        // [draw] attaches first, so this is null only if some future caller
        // reaches the table another way.
        val ctx = appContext ?: return null
        if (!vectorCache.containsKey(icon)) {
            val id = vectorRes[icon] ?: 0
            vectorCache[icon] = if (id == 0) null else try {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, id)
                    ?.mutate()
            } catch (_: Exception) {
                null
            }
        }
        return vectorCache[icon]
    }

    /**
     * Draws [icon] centred at [cx],[cy] at size [s], tinted [color].
     *
     * Takes the `Context` rather than relying on an earlier [attach] because
     * that is the difference between a compile error and wrong artwork on
     * screen: every caller is a `View` and already has one.
     */
    fun draw(c: Canvas, context: Context, icon: Int, cx: Float, cy: Float, s: Float, color: Int) {
        attach(context)
        val d = vector(icon) ?: return
        val h = s / 2f
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.setTint(color)
        d.draw(c)
    }
}
