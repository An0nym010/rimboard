package com.rimboard.keyboard.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.rimboard.keyboard.R
import com.rimboard.keyboard.engine.UserData

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.settings_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<Preference>("version")?.summary = try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
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
    }
}
