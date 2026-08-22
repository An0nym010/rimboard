package com.rimboard.keyboard.settings

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.rimboard.keyboard.R
import com.rimboard.keyboard.engine.DictionaryStore
import com.rimboard.keyboard.model.ExtendedDicts
import com.rimboard.keyboard.model.Languages
import com.rimboard.keyboard.net.Net

/**
 * Extended dictionaries: the deeper word list for a language, fetched or
 * imported after the install.
 *
 * # Two flavours, two doors, one check
 *
 * The online build downloads. The offline build cannot — it holds no INTERNET
 * permission, which is the entire point of it — so it opens the system
 * document picker and takes a file the user fetched themselves, which needs no
 * permission at all because the picker grants access to that one file. Both
 * paths end in [DictionaryStore.install], which verifies a SHA-256 from the
 * manifest inside the APK before anything is unpacked. A file off a memory
 * stick is trusted exactly as much as one off the network: not at all until it
 * matches.
 *
 * # Why this is a screen and not a switch
 *
 * The files are one to three megabytes each and only matter for the languages
 * a person actually types, so bundling all of them would put 25 MB in every
 * APK to serve the two languages each user has. What this offers is per
 * language, with the size in front of the button.
 */
class DictionariesActivity : LocalisedActivity() {

    private lateinit var catalogue: ExtendedDicts.Catalogue
    private lateinit var adapterImpl: Adapter
    private var rows: List<ExtendedDicts.Entry> = emptyList()

    /** Which language an import is running for; the picker cannot carry it. */
    private var importing: String? = null

    /** Languages with a fetch in flight, so a row cannot be started twice. */
    private val busy = HashSet<String>()

    /**
     * What is installed, read once per redraw rather than once per row.
     *
     * The adapter asked the disk directly, so drawing the list was one
     * `File.length()` per language on the UI thread, repeated every time
     * anything changed. Nothing here is slow enough to drop a frame on its
     * own, and file I/O on the main thread is still the wrong shape.
     */
    private var installedNow: Set<String> = emptySet()

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val lang = importing
            importing = null
            val entry = lang?.let { catalogue.forLang(it) }
            if (uri != null && lang != null && entry != null) {
                install(lang) { readPicked(uri, entry) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.pref_dicts_title)
        catalogue = ExtendedDicts.parse(
            try {
                assets.open(ExtendedDicts.ASSET).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        )
        // The languages this user types come first: the screen is about the two
        // or three they use, and the other nineteen are noise until they are not.
        val mine = Prefs.languages(this).toSet()
        rows = catalogue.entries.sortedWith(
            compareBy({ it.lang !in mine }, { Languages.byCode(it.lang).nativeName })
        )

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            text = if (Net.capable) {
                getString(R.string.dicts_intro, catalogue.minCount)
            } else {
                getString(R.string.dicts_intro_offline, catalogue.minCount, catalogue.base)
            }
            textSize = 13f
            alpha = 0.75f
        })

        if (rows.isEmpty()) {
            root.addView(TextView(this).apply {
                setPadding(dp(16), dp(24), dp(16), dp(16))
                gravity = Gravity.CENTER
                setText(R.string.dicts_none)
            })
        } else {
            installedNow = DictionaryStore.installed(DictionaryStore.dir(this))
            adapterImpl = Adapter()
            root.addView(
                ListView(this).apply { adapter = adapterImpl },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)
    }

    /**
     * Reads a picked document, refusing anything longer than the manifest
     * promises. Runs off the main thread.
     *
     * The picker hands over whatever the user taps, and a file manager full of
     * videos is one mis-tap away from a file that does not fit in memory --
     * an OutOfMemoryError, which is an Error rather than an Exception and so
     * would not have been caught by the install below either.
     * [DictionaryStore.readAtMost] stops before that rather than after; an
     * empty result then fails the hash check as the wrong file.
     */
    private fun readPicked(uri: Uri, entry: ExtendedDicts.Entry): ByteArray =
        contentResolver.openInputStream(uri).use { input ->
            DictionaryStore.readAtMost(input!!, entry.bytes) ?: ByteArray(0)
        }

    /**
     * Runs [source] off the main thread and installs whatever it returns.
     *
     * One path for both doors, so the verification, the refusal messages and
     * the row state cannot come to differ between downloading and importing.
     */
    private fun install(lang: String, source: () -> ByteArray) {
        val entry = catalogue.forLang(lang) ?: return
        if (!busy.add(lang)) return
        refresh()
        Thread {
            val result = try {
                DictionaryStore.install(this, entry, source())
            } catch (e: Exception) {
                android.util.Log.w("RimBoard", "extended dictionary fetch failed", e)
                DictionaryStore.Refusal.NOT_OFFERED
            }
            runOnUiThread {
                busy.remove(lang)
                val name = Languages.byCode(lang).nativeName
                when (result) {
                    null -> {
                        // DictVersion has already moved, which is what makes a
                        // running keyboard drop the dictionary it is holding.
                        Prefs.setPendingReload(this, true)
                        toast(getString(R.string.dicts_ready, name, fmt(entry.words)))
                    }
                    DictionaryStore.Refusal.WRONG_FILE ->
                        toast(getString(R.string.dicts_wrong_file, name))
                    DictionaryStore.Refusal.CORRUPT -> toast(getString(R.string.dicts_corrupt))
                    DictionaryStore.Refusal.NO_SPACE -> toast(getString(R.string.dicts_no_space))
                    // Named for the door it came through. The offline build
                    // has no download to fail, and telling somebody who just
                    // picked a file that a download failed sends them looking
                    // for a network problem they do not have.
                    DictionaryStore.Refusal.NOT_OFFERED -> toast(
                        getString(if (Net.capable) R.string.dicts_failed else R.string.dicts_corrupt)
                    )
                }
                refresh()
            }
        }.start()
    }

    private fun download(entry: ExtendedDicts.Entry) = install(entry.lang) {
        Net.fetchBytes(
            this, catalogue.urlFor(entry),
            reason = getString(R.string.dicts_net_reason, entry.lang),
            // Nothing typed is in this request: it is a GET of a static file
            // whose name was decided when the APK was built.
            sendsTypedText = false
        ).getOrThrow()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Grouped digits in the screen's own language, not the machine's. */
    private fun fmt(n: Int): String =
        String.format(resources.configuration.locales[0], "%,d", n)

    private fun refresh() {
        installedNow = DictionaryStore.installed(DictionaryStore.dir(this))
        if (::adapterImpl.isInitialized) adapterImpl.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()

        /**
         * Rebuilt rather than recycled, like the personal-dictionary list this
         * follows: there are twenty-one rows at most, and a recycled row that
         * kept a stale click listener is a real bug where reuse here would
         * save nothing measurable.
         */
        override fun getView(i: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@DictionariesActivity
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val entry = rows[i]
            val lang = entry.lang
            val installed = lang in installedNow
            val working = lang in busy

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(12), dp(10))
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply {
                text = Languages.byCode(lang).nativeName
                textSize = 16f
            })
            texts.addView(TextView(ctx).apply {
                textSize = 12f
                alpha = 0.7f
                text = when {
                    working -> getString(R.string.dicts_working)
                    installed -> getString(R.string.dicts_installed, fmt(entry.words))
                    else -> getString(
                        R.string.dicts_available,
                        fmt(entry.words),
                        android.text.format.Formatter.formatShortFileSize(ctx, entry.bytes)
                    )
                }
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Long-press copies the exact link. It matters most on the offline
            // build, where the file has to be fetched on something else and the
            // alternative is retyping a URL by hand off a phone screen; the
            // toast shows what was copied rather than announcing that a copy
            // happened, which also spares a string nobody would translate.
            row.setOnLongClickListener {
                val url = catalogue.urlFor(entry)
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                toast(url)
                true
            }
            row.addView(Button(ctx).apply {
                isEnabled = !working
                setText(
                    when {
                        installed -> R.string.dicts_remove
                        Net.capable -> R.string.dicts_download
                        else -> R.string.dicts_import
                    }
                )
                setOnClickListener {
                    when {
                        installed -> {
                            DictionaryStore.remove(ctx, lang)
                            Prefs.setPendingReload(ctx, true)
                            refresh()
                        }
                        Net.capable -> download(entry)
                        else -> {
                            importing = lang
                            // Any type: a gzip arrives as application/gzip,
                            // application/x-gzip or octet-stream depending on
                            // which app put it there, and a filter that guesses
                            // wrong makes the file invisible in the picker.
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    }
                }
            })
            return row
        }
    }
}
