# RimBoard

A free, open-source Android keyboard with a GBoard-style layout, on-device
suggestions, and a real incognito mode. No ads, no accounts, no analytics,
and **zero Android permissions** — the app cannot touch the internet even if
it wanted to.

- Kotlin, no heavyweight dependencies, single small APK
- 8 languages built in: English (QWERTY), Turkish (Q-klavye), German (QWERTZ),
  Spanish, French (AZERTY), Italian, Portuguese, Russian (ЙЦУКЕН)
- MIT licensed code, CC BY-SA 4.0 dictionaries

## Device compatibility

Runs on any Android 8.0+ phone or tablet (API 26, ~97% of devices) — Samsung, Xiaomi, Pixel, OnePlus, Oppo, Huawei and everything else. No Google services required, pure Kotlin with no native code, works on every CPU architecture.

- **Direct boot aware** — the keyboard works on the lock screen right after a reboot, before your first unlock
- **No fullscreen extract mode** — landscape typing keeps your app visible, Gboard-style
- **Themed navigation bar** — no white system-bar strip under a dark keyboard on 3-button-nav devices
- **Emoji filtered per device** — emoji your Android version can't render are hidden instead of showing ▯ boxes

## What's new in 2.4.0

- **Settings, reorganized Gboard-style** — the flat list is now proper sub-screens with icons and back navigation: Preferences, Look and feel, Suggestions, Glide typing, Clipboard, Privacy, Statistics, Backup, About.
- **Background image themes** — pick any photo as the keyboard background (permission-free document picker), with adjustable dimming so keys stay readable. Works over every theme.
- **New controls** — number row for passwords, auto-space after punctuation, touch-and-hold delay, key text size, key sound volume, vibration strength (light/medium/strong), and a glide-trail toggle.
- **Emoji fast-access row** — the idle suggestion bar now shows your recent emoji between the gear and clipboard; one tap inserts.
- **System (auto) theme** — follows the device light/dark setting.
- Fixed in this release's review: a strip-update regression that would have overwritten the incognito label with the idle bar.

## What's new in 2.3.0

- **Dictionaries at full scale** — 200,000 words for English, Turkish, German, Spanish, French, Italian, Portuguese and Russian; 100,000 for the other 14 languages. Dictionaries now preload on a background thread, so the first keystroke never stalls.
- **Rich settings** — a gradient hero header with version and language count, hand-drawn stroke icons on every key setting (theme, languages, shortcuts, statistics, backup and more), and Material accent coloring on all switches and dialogs.
- **Statistics dashboard** — WPM and total words now headline the stats screen as large accent tiles.

## What's new in 2.2.0

- **Key press animation** — keys sink in slightly on touch and fade smoothly back over 150 ms after release, giving every tap a tactile, fluid feel.
- **Depth** — soft shadows under every key, the preview bubble and long-press popups; the keyboard background now carries a subtle per-theme gradient (true black stays true black on AMOLED).
- **Suggestion highlight pill** — the boosted suggestion sits on a rounded accent-tinted pill instead of plain bold text.
- **Panel transitions** — clipboard, edit and emoji panels slide-fade in instead of snapping.
- **Detail work** — slightly rounder keys, rounded glide-trail stroke caps.

## What's new in 2.1.0

- **Gboard-class dictionaries** — word lists quadrupled: 120,000 words for English, Turkish, German, Spanish, French, Italian, Portuguese and Russian; 60,000 for every other language (previously 30,000 across the board). Sourced from the OpenSubtitles frequency corpus.
- **8 new languages** — Dansk, Norsk, Suomi, Magyar, Українська (full Ukrainian Cyrillic layout with ґ/ї long-press), Ελληνικά (native Greek layout), Hrvatski and Slovenčina — 22 languages total. (Azerbaijani is not available in the source corpus and will be added when a quality word list is found.)
- **Suggestion bar redesign** — when idle: settings gear on the left, Paste chip in the middle, clipboard on the right.
- **Decluttered comma menu** — now holds only edit panel, one-handed, floating, incognito and emoji; language, clipboard and settings live where they belong.
- **Correction engine speedup** — autocorrect candidates are indexed by word length, keeping corrections instant even with the 4x larger dictionaries.
- **Bugs found in this release's audit and fixed**: incognito icon could linger over suggestions while typing; the spacebar showed a raw emoji instead of the vector icon in incognito mode; a dead code path referencing the removed quick-action buttons.

## What's new in 2.0.0

- **Icon redesign** — every emoji in the keyboard chrome is replaced with hand-drawn vector icons that tint with your theme: globe, clipboard, edit, one-handed, floating, incognito, settings, emoji panel, keyboard picker, pin, trash, translate, undo/redo and search. The interface now looks identical on every device instead of depending on the system emoji font. (The emoji *panel* still shows real emoji, of course — that is content, not chrome.)

## What's new in 1.9.0

- **Text shortcuts** — Settings → Suggestions → Text shortcuts: define codes like "brb" → "be right back". While typing, the expansion appears as the first suggestion; hitting space expands it automatically. Included in backups.
- **Emoji suggestions** — type "fire" and 🔥 appears in the suggestion bar (English + Turkish keyword maps, offline).
- **Typing statistics** — Settings → Typing → Typing statistics: words, keys, backspace rate, autocorrections, active time and average WPM. 100% local, resettable.
- **Custom theme** — new Custom theme with a color picker (background, keys, text, accent — the rest of the palette is derived automatically), plus a High-contrast theme for accessibility.
- **Key repeat speed** — slow / normal / fast setting for backspace and arrows.
- **Stable APK signing** — builds are now signed with a fixed debug key committed to the repo, so every new version installs directly over the previous one. (One-time step when upgrading from an older build: export a backup in Settings → Backup, uninstall, install the new APK, import the backup.)
- **Haptics dedup fix** — key vibration now fires exactly once per press via a single path.

## What's new in 1.8.0

- **6 new languages** — Dutch, Polish, Swedish, Indonesian, Romanian, Czech — and every dictionary grown to 30,000 words (3× larger).
- **Floating keyboard** — long-press comma → ▣: a compact keyboard with a ☰ drag handle you can move anywhere; taps outside pass through to the app. Position is remembered.
- **Personal dictionary** — Settings → Suggestions: view every learned word with its use count, add words, remove words.
- **Remove any suggestion** — long-press a word in the suggestion bar → 🗑 to block it forever (works for built-in dictionary words too).
- **Learned words earn their place** — a new word must be typed 3+ times before it appears in suggestions.
- **Language auto-detection** — type 3 words in your other enabled language and suggestions quietly swap priority; it swaps back on its own.
- **Translate** — 🌍 in the edit panel hands text to any installed translator via the system process-text action; RimBoard itself still has no network access.
- **Interface language** moved to the ⋮ menu in Settings; settings reorganized with a Suggestions section.
- **Haptics actually work now** — key presses vibrate via the vibrator service (fixes silent keys on MIUI/HyperOS). This adds the harmless install-time VIBRATE permission; still no network, storage or contacts access.
- First run now enables your device language automatically.

## What's new in 1.7.0

- **Quick actions bar** — when the suggestion bar is idle, it shows shortcuts: 📋 clipboard, ✂ edit panel, 😊 emoji, 🕶 incognito, ⚙ settings. Toggle it in Settings.
- **Undo / redo** — ↶ ↷ buttons in the edit panel (sends Ctrl+Z / Ctrl+Shift+Z to the app).
- **Word delete, forgiven** — after swiping left on backspace, slide back right to restore deleted words one by one, with a haptic tick per word.
- **Auto-clear clipboard** — optional setting to drop unpinned clips after 15 or 60 minutes.
- Backups now include pinned clips; pins reload after a restore.
- Bilingual suggestions are now capitalized with their own language rules (no more Turkish dotted İ on English words).

## What's new in 1.6.0

- **Text editing panel** — long-press comma, tap ✂: arrow keys, Home/End, Select, Select all, Copy, Cut, Paste.
- **Word delete gesture** — touch backspace and swipe left to delete whole words, one per step.
- **Clipboard pinning** — tap 📌 on any clip to pin it. Pinned clips survive restarts (stored in the app's device-encrypted storage because you explicitly chose to keep them); everything else stays RAM-only and vanishes when the keyboard process ends.
- **Bilingual typing** — with two or more languages enabled, suggestions draw from your top two dictionaries, and autocorrect never "fixes" a word that is valid in the other language.
- **Interface language** — Settings → Interface language: the keyboard panels and settings UI in any of the 8 supported languages, independent of your system language.

Feature ideas in this release were informed by studying Gboard, SwiftKey, HeliBoard and FlorisBoard. No code was copied from any of them — HeliBoard/OpenBoard are GPL-3.0 licensed and RimBoard is MIT, so every implementation here is original and written for RimBoard's own architecture.

## Features

**Typing**
- Glide typing: slide across letters to type a word, with a swipe trail and
- ◨ **One-handed mode** — shrink the keyboard to either side (long-press comma → ◨); arrows switch sides, ⇔ restores full width. Auto-off in landscape.
- 📋 **Clipboard history** — last 10 copied items (long-press comma → 📋, or long-press the paste chip). Kept in RAM only, never written to disk, cleared when the keyboard process ends, disabled in incognito, 🗑 wipes it instantly.
  tap-to-replace alternatives in the strip (works in all bundled languages)
- GBoard-like key layout, sizes and spacing; adjustable key height; optional
  number row (digit hints on the top row when it's off)
- Auto-capitalization (sentence-aware), double-tap Shift for caps lock
- Autocorrect (edit distance ≤ 2 against a frequency dictionary) with a
  one-tap "↩ original" revert chip that also teaches the keyboard your word
- Suggestion strip: verbatim | best match | alternative, with the word that
  will be auto-committed shown in bold/accent
- Next-word predictions learned from your own typing (bigrams)
- Double-space inserts ". ", hold-and-slide the spacebar to move the cursor
- Long-press popups for accents, digits and symbols; key preview bubbles
- Two symbol pages, a phone/number pad for numeric fields, ~700 emoji with
  categories and recents
  a rail to switch sides or expand back; long-press Enter inserts a newline
  in chat apps where Enter sends
- Multi-touch typing (rollover), repeating backspace

**Languages**
- English, Turkish, German, Spanish, French, Italian, Portuguese and Russian
  out of the box — pick any set in Settings; 🌐 cycles them, long-press for
  the system picker; locale-correct casing (Turkish i → İ, Cyrillic, etc.)
- Add more languages by dropping a dictionary file and a layout (see below)

**Privacy**
- **Zero permissions.** No `INTERNET`, no contacts, no microphone — nothing.
  Feel free to verify the manifest.
- **Incognito mode** (🕶): long-press the comma key → 🕶, or enable
  "Always incognito" in settings. While active the keyboard learns nothing,
  suggests nothing personal, and records no emoji history.
- Incognito also turns itself on automatically in password fields and in any
  field that requests no personalized learning (e.g. browsers' private tabs).
- Learned words live in two plain-text files in the app's private storage.
  You can wipe them anytime from Settings → "Delete learned data".
- `allowBackup=false`, so learned words are never uploaded to device backups.
- Built-in **Export / Import backup** (Settings → Backup): everything —
  settings, learned words, predictions — goes into one JSON file you control,
  written through the system file picker. Move it between devices yourself;
  nothing ever leaves the phone otherwise.

## Install

### Option A — GitHub Actions (no Android Studio needed)
1. Push this repository to GitHub (or fork it).
2. The **Build APK** workflow runs automatically; open the run and download
   the `RimBoard-debug` artifact.
3. Copy `app-debug.apk` to your phone and install it (allow "install unknown
   apps" for your file manager if asked).
4. Tagging a commit `v1.0` (or any `v*`) also attaches the APK to a GitHub
   Release automatically.

### Option B — Android Studio
Open the project, let Gradle sync, then **Build → Build APK(s)**, or:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enable it (Xiaomi/HyperOS example — Poco phones)
1. Open the RimBoard app and follow the three steps, or go to
   **Settings → Additional settings → Languages & input → Manage keyboards**
   and enable RimBoard.
2. Tap any text field, then use the keyboard switcher (or the app's
   "Switch keyboard" button) to select RimBoard.
3. Android will show a standard warning that third-party keyboards may
   collect what you type — that applies to any IME; RimBoard has no network
   permission, so it has no way to send anything anywhere.

## Extending the dictionaries

The bundled lists are the top 10,000 words per language. To regenerate or
enlarge them:

```
python3 tools/fetch_dictionaries.py --top 20000
python3 tools/fetch_dictionaries.py --langs en tr de --top 15000
```

Dictionary format is one `word count` pair per line, ordered by frequency.
Adding a whole new language also needs a layout in
`app/src/main/java/com/rimboard/keyboard/model/Layouts.kt` and a subtype in
`app/src/main/res/xml/method.xml`.

## Project notes

- After publishing your fork, update the GitHub URL in
  `app/src/main/res/xml/preferences.xml` (the "Source code" preference) so
  the settings link points at your repository.
- Debug builds are signed with the debug key. For Play/F-Droid distribution
  you'd add a release signing config; for personal use the debug APK is fine.

## Roadmap / not implemented yet

- Voice input

## License

- **Code:** MIT (see `LICENSE`). If you prefer a public-domain-style grant,
  swapping in The Unlicense only requires replacing that file.
- **Dictionaries:** derived from
  [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit
  Dave (OpenSubtitles 2018 corpus), CC BY-SA 4.0 — see `NOTICE`. Derivative
  dictionary files must stay CC BY-SA 4.0; the app code is unaffected.
