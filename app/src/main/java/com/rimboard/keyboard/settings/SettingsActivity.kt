package com.rimboard.keyboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import com.rimboard.keyboard.ui.IconView
import com.rimboard.keyboard.ui.Icons
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.rimboard.keyboard.R
import com.rimboard.keyboard.engine.UserData
import java.io.File

class SettingsActivity : LocalisedActivity() {

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 0, R.string.search_settings).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setIcon(android.R.drawable.ic_menu_search)
        }
        menu.add(0, 1, 1, R.string.ui_lang_title)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 2) {
            showSearch()
            return true
        }
        if (item.itemId == 1) {
            val entries = resources.getStringArray(R.array.ui_lang_entries)
            val values = resources.getStringArray(R.array.ui_lang_values)
            val current = values.indexOf(Prefs.uiLanguage(this)).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.ui_lang_title)
                .setSingleChoiceItems(entries, current) { d, which ->
                    Prefs.setUiLanguage(this, values[which])
                    d.dismiss()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }


    /**
     * Every setting on every screen, with the screen it lives on.
     *
     * Built by inflating each preference XML rather than from a hand-written
     * list, so a setting is searchable the moment it exists and its title here
     * is the same string the screen shows, in the same language. Built once and
     * kept: nine inflations is not something to do on every keystroke of a
     * query.
     */
    private val searchIndex: List<SettingsSearch.Entry> by lazy { buildSearchIndex() }

    /**
     * Read straight from the preference XML with the resource parser.
     *
     * Not by inflating the hierarchy: `PreferenceManager`'s constructor and
     * `inflateFromResource` are restricted to the library group, and lint says
     * so. Suppressing that would be borrowing an internal API for a
     * convenience, when the parser is public, cheaper, and gives exactly the
     * two attributes wanted. Titles resolve through this activity's resources,
     * which [LocalisedActivity] has already put in the chosen language, so an
     * index built here is in the language on screen.
     */
    private fun buildSearchIndex(): List<SettingsSearch.Entry> {
        val screenTitles = readAttributes(R.xml.preferences)
            .filter { it.key.startsWith("screen_") }
            .associate { it.key to it.title }
        val out = ArrayList<SettingsSearch.Entry>()
        for ((key, xml) in SCREEN_XML) {
            val screenTitle = screenTitles[key] ?: continue
            for (a in readAttributes(xml)) {
                // A category header has a title and no key; it is a label, not
                // a setting, and tapping a result for one would go nowhere.
                if (a.title.isBlank() || a.key.isBlank()) continue
                out.add(
                    SettingsSearch.Entry(
                        key = a.key,
                        title = a.title,
                        summary = a.summary,
                        screenTitle = screenTitle,
                        screenXml = xml
                    )
                )
            }
        }
        return out
    }

    private class Attrs(val key: String, val title: String, val summary: String)

    private fun readAttributes(xmlRes: Int): List<Attrs> {
        val ns = "http://schemas.android.com/apk/res/android"
        val out = ArrayList<Attrs>()
        try {
            resources.getXml(xmlRes).use { p ->
                while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                    val key = p.getAttributeValue(ns, "key").orEmpty()
                    // Titles are string resources; a literal would return 0 and
                    // is read back with getAttributeValue instead.
                    val titleRes = p.getAttributeResourceValue(ns, "title", 0)
                    val sumRes = p.getAttributeResourceValue(ns, "summary", 0)
                    val title = if (titleRes != 0) getString(titleRes)
                    else p.getAttributeValue(ns, "title").orEmpty()
                    val summary = if (sumRes != 0) getString(sumRes)
                    else p.getAttributeValue(ns, "summary").orEmpty()
                    if (key.isNotBlank() || title.isNotBlank()) out.add(Attrs(key, title, summary))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RimBoard", "settings index: could not read xml $xmlRes", e)
        }
        return out
    }

    private var searchInput: android.widget.EditText? = null
    private var searchResults: LinearLayout? = null
    private var searchScroll: View? = null

    /**
     * Opens the search field in the screen rather than over it.
     *
     * A dialog was the first version and it was the wrong shape: it covered the
     * settings, so the results were shown in place of the thing they were
     * meant to help you find, and dismissing it to look at a result put you
     * back where you started. Inline, the results sit where the settings list
     * was and leave everything else — the toolbar, the header, the back stack
     * — exactly as it was.
     */
    private fun showSearch() {
        val input = searchInput ?: return
        input.visibility = View.VISIBLE
        input.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(input, 0)
    }

    private fun hideSearch() {
        searchInput?.let {
            it.setText("")
            it.visibility = View.GONE
            (getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
        searchScroll?.visibility = View.GONE
        findViewById<View>(CONTAINER_ID)?.visibility = View.VISIBLE
    }

    private fun renderResults(query: String) {
        val results = searchResults ?: return
        val scroll = searchScroll ?: return
        val container = findViewById<View>(CONTAINER_ID)
        results.removeAllViews()
        val hits = SettingsSearch.search(searchIndex, query)
        // An empty query is not "no results", it is "not searching yet" — so
        // the settings come back rather than being replaced by a blank panel.
        if (query.isBlank()) {
            scroll.visibility = View.GONE
            container?.visibility = View.VISIBLE
            return
        }
        scroll.visibility = View.VISIBLE
        container?.visibility = View.GONE
        if (hits.isEmpty()) {
            results.addView(TextView(this).apply {
                val d = resources.displayMetrics.density
                setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
                setText(R.string.search_no_results)
                alpha = 0.7f
            })
            return
        }
        for (hit in hits) {
            results.addView(
                resultRow(hit),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun resultRow(hit: SettingsSearch.Entry): View {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            isClickable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true
            )
            setBackgroundResource(outValue.resourceId)
            addView(TextView(context).apply {
                text = hit.title
                textSize = 16f
            })
            // The path, which is the whole reason a result is worth showing:
            // "where did you put this" is the question being asked, and the
            // answer belongs in the result rather than one tap further on.
            addView(TextView(context).apply {
                text = hit.screenTitle
                textSize = 12f
                alpha = 0.7f
            })
            setOnClickListener {
                hideSearch()
                supportFragmentManager.beginTransaction()
                    .replace(
                        CONTAINER_ID,
                        SettingsFragment.newInstance(hit.screenXml, hit.key)
                    )
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    override fun onBackPressed() {
        // Back closes the search before it leaves the screen, which is what a
        // search field open over a list is expected to do.
        if (searchInput?.visibility == View.VISIBLE) {
            hideSearch()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.settings_title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = resources.displayMetrics.density
        val header = buildHeader()
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        hlp.setMargins((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        root.addView(header, hlp)

        // Hidden until the magnifier is tapped, so the screen is unchanged for
        // anyone not searching.
        val input = android.widget.EditText(this).apply {
            setHint(R.string.search_settings)
            setSingleLine()
            visibility = View.GONE
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderResults(s?.toString().orEmpty())
                }
                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, x: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, x: Int) {}
            })
        }
        searchInput = input
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchResults = results
        val scroll = android.widget.ScrollView(this).apply {
            visibility = View.GONE
            addView(results)
        }
        searchScroll = scroll

        val container = FrameLayout(this).apply { id = CONTAINER_ID }
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Same slot as the settings list and the same weight, so results take
        // exactly the space the list had rather than pushing it around.
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, SettingsFragment())
                .commit()
        }
    }

    private fun buildHeader(): LinearLayout {
        val d = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A73E8.toInt(), 0xFF0B47A1.toInt())
            ).apply { cornerRadius = 18 * d }
        }
        card.addView(
            IconView(this, Icons.KEYBOARD).apply { color = 0xFFFFFFFF.toInt() },
            LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt())
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
        }
        col.addView(TextView(this).apply {
            text = "RimBoard"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            ""
        }
        col.addView(TextView(this).apply {
            text = "v$ver \u2022 " +
                "${com.rimboard.keyboard.model.Languages.all.size} ${getString(R.string.header_languages)} \u2022 " +
                // Only true of the build that has no INTERNET permission.
                // Claiming it on the online build is exactly the kind of
                // reassurance this app is not supposed to hand out.
                getString(
                    if (com.rimboard.keyboard.net.Net.capable) R.string.header_byok
                    else R.string.header_offline
                )
            setTextColor(0xDDFFFFFF.toInt())
            textSize = 12f
        })
        card.addView(col)
        return card
    }

    companion object {
        const val CONTAINER_ID = 0x0A11CE

        /**
         * Which XML each sub-screen shows.
         *
         * The *titles* are deliberately not here: they are read from the root
         * screen's own preferences at search time. Nine screens are titled from
         * seven differently-named strings — `screen_general`, `cat_look`,
         * `pref_glide_title` — and a second copy of that mapping would drift
         * the first time one was renamed, leaving a search result labelled with
         * the wrong screen and no way to notice.
         */
        val SCREEN_XML: Map<String, Int> = mapOf(
            "screen_general" to R.xml.prefs_general,
            "screen_theme" to R.xml.prefs_theme,
            "screen_corrections" to R.xml.prefs_corrections,
            "screen_glide" to R.xml.prefs_glide,
            "screen_clipboard" to R.xml.prefs_clipboard,
            "screen_privacy" to R.xml.prefs_privacy,
            "screen_backup" to R.xml.prefs_backup,
            "screen_advanced" to R.xml.prefs_advanced,
            "screen_about" to R.xml.prefs_about
        )
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) runExport(uri) }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) confirmRestore(uri) }

        private val bgLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            // Picking no longer saves blindly: the crop screen shows the photo
            // behind a keyboard-shaped window and saves what the user framed.
            if (uri != null) {
                startActivity(
                    Intent(requireContext(), BackgroundCropActivity::class.java)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

        private val fontLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) saveFont(uri) }

        private val dictLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importDict(uri) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.setStorageDeviceProtected()
            val xmlRes = arguments?.getInt(ARG_XML, 0)?.takeIf { it != 0 } ?: R.xml.preferences
            setPreferencesFromResource(xmlRes, rootKey)
            val screens = mapOf(
                "screen_general" to R.xml.prefs_general,
                "screen_theme" to R.xml.prefs_theme,
                "screen_corrections" to R.xml.prefs_corrections,
                "screen_glide" to R.xml.prefs_glide,
                "screen_clipboard" to R.xml.prefs_clipboard,
                "screen_privacy" to R.xml.prefs_privacy,
                "screen_backup" to R.xml.prefs_backup,
                "screen_advanced" to R.xml.prefs_advanced,
                "screen_about" to R.xml.prefs_about
            )
            for ((key, res) in screens) {
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(SettingsActivity.CONTAINER_ID, newInstance(res))
                        .addToBackStack(null)
                        .commit()
                    true
                }
            }
            findPreference<Preference>("bg_pick")?.setOnPreferenceClickListener {
                bgLauncher.launch(arrayOf("image/*"))
                true
            }
            findPreference<Preference>("font_pick")?.setOnPreferenceClickListener {
                fontLauncher.launch(arrayOf("*/*"))
                true
            }
            findPreference<Preference>("font_clear")?.setOnPreferenceClickListener {
                java.io.File(com.rimboard.keyboard.engine.UserData.dataDir(requireContext()),
                    "custom_font.ttf").delete()
                android.widget.Toast.makeText(requireContext(),
                    R.string.font_removed, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("dict_import")?.setOnPreferenceClickListener {
                dictLauncher.launch(arrayOf("*/*"))
                true
            }
            findPreference<Preference>("bg_clear")?.setOnPreferenceClickListener {
                java.io.File(com.rimboard.keyboard.engine.UserData.dataDir(requireContext()),
                    "bg_image.jpg").delete()
                com.rimboard.keyboard.ui.BgImageState.version++
                android.widget.Toast.makeText(requireContext(),
                    R.string.bg_removed, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<androidx.preference.SeekBarPreference>("clip_timeout_min")?.let { sb ->
                // Seeded from the legacy list preference so an upgrade shows
                // the value that is actually in force.
                sb.value = Prefs.clipTimeoutMin(requireContext())
                fun label(v: Int) =
                    if (v <= 0) getString(R.string.clip_timeout_never)
                    else getString(R.string.clip_timeout_mins, v)
                sb.summary = label(sb.value)
                sb.setOnPreferenceChangeListener { _, newValue ->
                    sb.summary = label(newValue as? Int ?: 0)
                    true
                }
            }
            findPreference<Preference>("screen_network")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NetworkActivity::class.java))
                true
            }
            findPreference<Preference>("personal_dict")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PersonalDictActivity::class.java))
                true
            }
            findPreference<Preference>("text_shortcuts")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ShortcutsActivity::class.java))
                true
            }
            findPreference<Preference>("typing_stats")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), StatsActivity::class.java))
                true
            }
            findPreference<Preference>("custom_colors")?.setOnPreferenceClickListener {
                showCustomColors()
                true
            }
            // Haptics are the one setting whose result the app cannot observe:
            // a vibration the platform suppresses throws nothing and returns
            // normally. So the screen says what the system switch is set to
            // rather than describing what the keyboard intends to do, and the
            // strength row plays a sample so the answer comes from the user's
            // hand instead of from a claim in a summary.
            findPreference<Preference>("haptic")?.let { pref ->
                if (!com.rimboard.keyboard.Haptics.systemTouchFeedbackOn(requireContext())) {
                    pref.summary = getString(R.string.pref_haptic_summary_system_off)
                }
            }
            findPreference<Preference>("haptic_strength")?.setOnPreferenceChangeListener { _, _ ->
                view?.post { view?.let { com.rimboard.keyboard.Haptics.test(it) } }
                true
            }
            findPreference<Preference>("version")?.summary = try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            val learnedFile = File(UserData.dataDir(requireContext()), "learned.txt")
            val count = try {
                if (learnedFile.exists()) learnedFile.readLines().count { it.isNotBlank() } else 0
            } catch (_: Exception) {
                0
            }
            if (count > 0) {
                findPreference<Preference>("clear_learned")?.summary =
                    getString(R.string.learned_count, count)
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "backup_export") {
                exportLauncher.launch("rimboard-backup.json")
                return true
            }
            if (preference.key == "backup_import") {
                importLauncher.launch(arrayOf("*/*"))
                return true
            }
            if (preference.key == "clear_learned") {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_confirm_title)
                    .setMessage(R.string.clear_confirm_msg)
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        // Throwaway instance: shut its writer thread down, or
                        // every tap of this leaves one behind.
                        UserData(requireContext()).apply {
                            clearAll()
                            shutdown()
                        }
                        Prefs.setPendingClear(requireContext(), true)
                        Toast.makeText(
                            requireContext(), R.string.clear_done, Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                return true
            }
            return super.onPreferenceTreeClick(preference)
        }

        private val palette = intArrayOf(
            0xFF000000.toInt(), 0xFF1B1E23.toInt(), 0xFF37474F.toInt(), 0xFF546E7A.toInt(),
            0xFF795548.toInt(), 0xFF8D6E63.toInt(), 0xFFB71C1C.toInt(), 0xFFE53935.toInt(),
            0xFFFF7043.toInt(), 0xFFFFA726.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFF9C4.toInt(),
            0xFF33691E.toInt(), 0xFF43A047.toInt(), 0xFF26A69A.toInt(), 0xFF00BCD4.toInt(),
            0xFF1A73E8.toInt(), 0xFF3949AB.toInt(), 0xFF8AB4F8.toInt(), 0xFF7E57C2.toInt(),
            0xFFAB47BC.toInt(), 0xFFEC407A.toInt(), 0xFFE8EAED.toInt(), 0xFFFFFFFF.toInt()
        )

        /**
         * Lists the three custom themes.
         *
         * Each is a slot rather than a single "custom" theme because one set of
         * four colours meant experimenting cost you whatever you already had.
         * Long-press deletes; deleting only clears the stored colours, so the
         * slot itself stays in the theme list ready to be used again.
         */
        private fun showCustomColors() {
            val ctx = requireContext()
            val labels = (1..Prefs.CUSTOM_SLOTS).map { slot ->
                val name = getString(R.string.cc_slot, slot)
                val state = getString(
                    if (Prefs.customSlotUsed(ctx, slot)) R.string.cc_slot_used
                    else R.string.cc_slot_empty
                )
                "$name\n$state"
            }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle(R.string.pref_custom_colors_title)
                .setItems(labels) { _, which -> showSlotEditor(which + 1) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** The four colours of one slot, each opening the wheel. */
        private fun showSlotEditor(slot: Int) {
            val ctx = requireContext()
            val labels = arrayOf(
                getString(R.string.cc_background), getString(R.string.cc_keys),
                getString(R.string.cc_text), getString(R.string.cc_accent)
            )
            val defs = intArrayOf(
                0xFF1B1E23.toInt(), 0xFF3A3E46.toInt(), 0xFFE8EAED.toInt(), 0xFF8AB4F8.toInt()
            )
            val b = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.cc_slot, slot))
                .setItems(labels) { _, which ->
                    showColorWheel(
                        labels[which], Prefs.slotKey(Prefs.CC_KEYS[which], slot),
                        defs[which], slot
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
            if (Prefs.customSlotUsed(ctx, slot)) {
                b.setNeutralButton(R.string.cc_delete) { _, _ ->
                    Prefs.clearCustomSlot(ctx, slot)
                    // Leaving the deleted theme selected would show the
                    // defaults while claiming to be the user's own theme.
                    if (Prefs.theme(ctx) == Prefs.customThemeId(slot)) {
                        Prefs.get(ctx).edit().putString(Prefs.KEY_THEME, "system").apply()
                    }
                    Toast.makeText(ctx, R.string.cc_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            b.show()
        }

        /**
         * Picks one colour on the wheel, with a live preview.
         *
         * Saving also selects the slot as the active theme: editing a theme you
         * are not looking at is a strange thing to have asked for, and the old
         * flow left people adjusting colours with no visible effect because the
         * theme dropdown was still on something else.
         */
        private fun showColorWheel(title: String, prefKey: String, def: Int, slot: Int) {
            val ctx = requireContext()
            val d = resources.displayMetrics.density
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
            }
            val preview = View(ctx).apply {
                setBackgroundColor(Prefs.customColor(ctx, prefKey, def))
            }
            col.addView(preview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt()))
            // Hex, and the wheel writes into it. This is the path that does
            // not need a drag on an unlabelled canvas: typing six characters
            // works with a screen reader, with a keyboard, and for anyone who
            // already knows the colour they want.
            val hex = android.widget.EditText(ctx).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                contentDescription = getString(R.string.cc_hex_label)
                hint = getString(R.string.cc_hex_label)
            }
            var syncing = false
            fun setHex(c: Int) {
                syncing = true
                hex.setText(String.format("#%06X", c and 0xFFFFFF))
                syncing = false
            }
            val wheel = com.rimboard.keyboard.ui.ColorWheelView(ctx).apply {
                color = Prefs.customColor(ctx, prefKey, def)
                onColorChanged = {
                    preview.setBackgroundColor(it)
                    setHex(it)
                }
            }
            setHex(wheel.color)
            hex.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    // Guarded so the wheel writing into the field does not read
                    // straight back out and fight the finger that caused it.
                    if (syncing) return
                    val parsed = parseHex(e?.toString()) ?: return
                    wheel.color = parsed
                    preview.setBackgroundColor(parsed)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            col.addView(hex, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(wheel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(col)
                .setPositiveButton(R.string.cc_apply) { _, _ ->
                    Prefs.setCustomColor(ctx, prefKey, wheel.color)
                    Prefs.get(ctx).edit()
                        .putString(Prefs.KEY_THEME, Prefs.customThemeId(slot)).apply()
                }
                .setNeutralButton(R.string.cc_reset) { _, _ ->
                    Prefs.setCustomColor(ctx, prefKey, def)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * Parses `#RRGGBB` or `RRGGBB`, opaque. Null for anything else, so a
         * half-typed value leaves the wheel alone instead of jumping to black
         * on every keystroke.
         */
        private fun parseHex(text: String?): Int? {
            val t = text?.trim()?.removePrefix("#") ?: return null
            if (t.length != 6) return null
            val v = t.toLongOrNull(16) ?: return null
            return (0xFF000000L or v).toInt()
        }

        private fun saveFont(uri: android.net.Uri) {
            try {
                val ctx = requireContext()
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val f = java.io.File(
                    com.rimboard.keyboard.engine.UserData.dataDir(ctx), "custom_font.ttf")
                f.writeBytes(bytes)
                val ok = try {
                    android.graphics.Typeface.createFromFile(f)
                    true
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    f.delete()
                    android.widget.Toast.makeText(ctx, R.string.font_invalid,
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(ctx, R.string.font_saved,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
            }
        }

        /**
         * Imports on a background thread and always reports back.
         *
         * This read, parsed and wrote the whole file on the main thread —
         * dictionaries run to megabytes — and swallowed every failure without a
         * toast, so a failed import was indistinguishable from the button
         * simply not working.
         */
        private fun importDict(uri: android.net.Uri) {
            val ctx = requireContext().applicationContext
            val lang = Prefs.currentLang(ctx) ?: "en"
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            fun toast(msg: String) = ui.post {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
            Thread {
                try {
                    val lines = ctx.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readLines() }
                    if (lines == null) {
                        toast(ctx.getString(R.string.dict_invalid))
                        return@Thread
                    }
                    var valid = 0
                    val out = StringBuilder()
                    for (line in lines) {
                        val sp = line.indexOf(' ')
                        if (sp > 0 && line.substring(sp + 1).trim().toIntOrNull() != null) {
                            out.append(line.trim()).append('\n')
                            valid++
                        }
                    }
                    if (valid < 30) {
                        toast(ctx.getString(R.string.dict_invalid))
                        return@Thread
                    }
                    java.io.File(com.rimboard.keyboard.engine.UserData.dataDir(ctx),
                        "userdict_" + lang + ".txt").writeText(out.toString())
                    com.rimboard.keyboard.engine.DictVersion.v++
                    toast(ctx.getString(R.string.dict_saved, valid, lang))
                } catch (e: Exception) {
                    android.util.Log.w("RimBoard", "dictionary import failed", e)
                    toast(ctx.getString(R.string.dict_invalid))
                }
            }.start()
        }

        companion object {
            private const val ARG_XML = "xml"
            private const val ARG_HIGHLIGHT = "highlight"

            fun newInstance(res: Int, highlightKey: String? = null) = SettingsFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_XML, res)
                    highlightKey?.let { putString(ARG_HIGHLIGHT, it) }
                }
            }
        }

        /**
         * Scrolls to the row a search result pointed at and flashes it.
         *
         * Landing on the right screen is only most of the answer: these screens
         * hold a dozen rows and the one that was searched for looks like all
         * the others. The flash is what finishes the sentence the search
         * started.
         *
         * Two posts deep, and both are needed: the adapter does not exist until
         * the list has been laid out once, and the view holder for a row does
         * not exist until after the scroll that brings it on screen.
         */
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val key = arguments?.getString(ARG_HIGHLIGHT) ?: return
            // Consumed, so returning to this screen with Back does not flash
            // the row again as though it had just been found.
            arguments?.remove(ARG_HIGHLIGHT)
            listView.post {
                val adapter = listView.adapter
                val pos = (adapter as? androidx.preference.PreferenceGroup.PreferencePositionCallback)
                    ?.getPreferenceAdapterPosition(key) ?: return@post
                if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return@post
                listView.scrollToPosition(pos)
                listView.post { flashRow(pos) }
            }
        }

        private fun flashRow(pos: Int) {
            val holder = listView.findViewHolderForAdapterPosition(pos) ?: return
            val row = holder.itemView
            // The return value decides whether `data` means anything: an
            // unresolved attribute leaves it holding whatever the TypedValue
            // was last used for, and the flash would then be an arbitrary
            // colour rather than the theme's.
            val accent = android.util.TypedValue().let { tv ->
                val ok = requireContext().theme.resolveAttribute(
                    androidx.appcompat.R.attr.colorAccent, tv, true
                )
                if (ok) tv.data else 0xFF3E7BFA.toInt()
            }
            val original = row.background
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                addUpdateListener { a ->
                    // Two pulses, fading out: one is easy to miss while the
                    // scroll is still settling.
                    val t = a.animatedValue as Float
                    val pulse = kotlin.math.abs(kotlin.math.sin(t * Math.PI * 2)).toFloat()
                    val alpha = ((1f - t) * pulse * 60f).toInt().coerceIn(0, 255)
                    row.setBackgroundColor((alpha shl 24) or (accent and 0x00FFFFFF))
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Restored rather than left transparent: these rows are
                        // recycled, and a row left with a colour would show it
                        // under whatever setting scrolled into its place.
                        row.background = original
                    }
                })
            }
            anim.start()
        }


        /**
         * Export and restore both run off the main thread, for the same reason
         * the dictionary import and the background image above them do: a
         * backup carries the entire learned word list plus the bigram and
         * trigram models, so serialising it to JSON, or parsing it and writing
         * five files back, is not work to do on the UI thread. These two were
         * the pair that got missed.
         */
        private fun runExport(uri: Uri) {
            val ctx = requireContext().applicationContext
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                val ok = Backup.export(ctx, uri)
                ui.post {
                    Toast.makeText(
                        ctx,
                        if (ok) R.string.backup_export_ok else R.string.backup_export_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.start()
        }

        private fun confirmRestore(uri: Uri) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_confirm_title)
                .setMessage(R.string.import_confirm_msg)
                .setPositiveButton(R.string.action_restore) { _, _ -> runRestore(uri) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun runRestore(uri: Uri) {
            val ctx = requireContext().applicationContext
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                val ok = Backup.restore(ctx, uri)
                ui.post {
                    Toast.makeText(
                        ctx,
                        if (ok) R.string.backup_import_ok else R.string.backup_import_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                    // Recreating is how the restored values reach the screen:
                    // every preference re-reads its stored value. Skipped if the
                    // fragment went away while the restore was running.
                    if (ok && isAdded) activity?.recreate()
                }
            }.start()
        }
    }
}
