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

class SettingsActivity : AppCompatActivity() {

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.ui_lang_title)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(L10n.wrap(newBase))
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
        val container = FrameLayout(this).apply { id = CONTAINER_ID }
        root.addView(container, LinearLayout.LayoutParams(
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
                getString(R.string.header_offline)
            setTextColor(0xDDFFFFFF.toInt())
            textSize = 12f
        })
        card.addView(col)
        return card
    }

    companion object {
        const val CONTAINER_ID = 0x0A11CE
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
        ) { uri -> if (uri != null) saveBackgroundImage(uri) }

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

        private fun showCustomColors() {
            val labels = arrayOf(
                getString(R.string.cc_background), getString(R.string.cc_keys),
                getString(R.string.cc_text), getString(R.string.cc_accent)
            )
            val keys = arrayOf(Prefs.KEY_CC_BG, Prefs.KEY_CC_KEY, Prefs.KEY_CC_TEXT, Prefs.KEY_CC_ACCENT)
            val defs = intArrayOf(
                0xFF1B1E23.toInt(), 0xFF3A3E46.toInt(), 0xFFE8EAED.toInt(), 0xFF8AB4F8.toInt()
            )
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_custom_colors_title)
                .setItems(labels) { _, which -> showColorGrid(labels[which], keys[which], defs[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /**
         * [def] is the colour this slot falls back to when unset. It was passed
         * in and never used, which left no way back once a custom colour had
         * been chosen — the grid could only set one.
         */
        private fun showColorGrid(title: String, prefKey: String, def: Int) {
            val ctx = requireContext()
            val d = resources.displayMetrics.density
            val grid = android.widget.GridLayout(ctx).apply {
                columnCount = 6
                setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            }
            var dlg: androidx.appcompat.app.AlertDialog? = null
            for (color in palette) {
                val v = View(ctx)
                val lp = android.widget.GridLayout.LayoutParams()
                lp.width = (44 * d).toInt()
                lp.height = (44 * d).toInt()
                lp.setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                v.layoutParams = lp
                v.setBackgroundColor(color)
                v.setOnClickListener {
                    Prefs.setCustomColor(ctx, prefKey, color)
                    dlg?.dismiss()
                }
                grid.addView(v)
            }
            dlg = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(grid)
                .setNeutralButton(R.string.cc_reset) { _, _ ->
                    Prefs.setCustomColor(ctx, prefKey, def)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

        /**
         * Decodes and re-encodes on a background thread, then reports.
         *
         * This ran on the main thread while holding the whole source image in
         * memory — a phone photo is easily 5-15MB — decoding it twice and then
         * JPEG-encoding it. It also swallowed failures silently, so choosing an
         * unreadable image looked identical to the setting doing nothing.
         */
        private fun saveBackgroundImage(uri: android.net.Uri) {
            val ctx = requireContext().applicationContext
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            fun toast(res: Int) = ui.post {
                android.widget.Toast.makeText(ctx, res, android.widget.Toast.LENGTH_SHORT).show()
            }
            Thread {
                var bm: android.graphics.Bitmap? = null
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        toast(R.string.bg_invalid)
                        return@Thread
                    }
                    val bounds = android.graphics.BitmapFactory.Options()
                        .apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options()
                        .apply { inSampleSize = sample }
                    val decoded =
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (decoded == null) {
                        toast(R.string.bg_invalid)
                        return@Thread
                    }
                    bm = decoded
                    val f = java.io.File(
                        com.rimboard.keyboard.engine.UserData.dataDir(ctx), "bg_image.jpg")
                    java.io.FileOutputStream(f).use {
                        decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
                    }
                    com.rimboard.keyboard.ui.BgImageState.version++
                    toast(R.string.bg_saved)
                } catch (e: Exception) {
                    android.util.Log.w("RimBoard", "background image save failed", e)
                    toast(R.string.bg_invalid)
                } finally {
                    // Recycled even when compress throws, which previously left
                    // a full-size bitmap alive until the collector noticed.
                    bm?.recycle()
                }
            }.start()
        }
        companion object {
            private const val ARG_XML = "xml"
            fun newInstance(res: Int) = SettingsFragment().apply {
                arguments = Bundle().apply { putInt(ARG_XML, res) }
            }
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
