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
        private const val CONTAINER_ID = 0x0A11CE
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> if (uri != null) runExport(uri) }

        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) confirmRestore(uri) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.setStorageDeviceProtected()
            setPreferencesFromResource(R.xml.preferences, rootKey)
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
                        UserData(requireContext()).clearAll()
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
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun runExport(uri: Uri) {
            val ok = Backup.export(requireContext(), uri)
            Toast.makeText(
                requireContext(),
                if (ok) R.string.backup_export_ok else R.string.backup_export_fail,
                Toast.LENGTH_SHORT
            ).show()
        }

        private fun confirmRestore(uri: Uri) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_confirm_title)
                .setMessage(R.string.import_confirm_msg)
                .setPositiveButton(R.string.action_restore) { _, _ ->
                    val ok = Backup.restore(requireContext(), uri)
                    Toast.makeText(
                        requireContext(),
                        if (ok) R.string.backup_import_ok else R.string.backup_import_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) requireActivity().recreate()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }
}
