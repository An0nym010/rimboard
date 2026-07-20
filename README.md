# RimBoard

A free, open-source Android keyboard with a GBoard-style layout, on-device
suggestions, and a real incognito mode. No ads, no accounts, no analytics,
and **zero Android permissions** — the app cannot touch the internet even if
it wanted to.

- Kotlin, no heavyweight dependencies, single small APK
- 22 languages built in — English, Turkish, German, Spanish, French, Italian,
  Portuguese, Russian, Dutch, Polish, Swedish, Indonesian, Romanian, Czech,
  Danish, Norwegian, Finnish, Hungarian, Ukrainian, Greek, Croatian, Slovak —
  with native layouts (QWERTY, QWERTZ, AZERTY, ЙЦУКЕН, Greek, Turkish Q)
- Gboard-class typing engine: adaptive tap targeting, proximity-aware
  autocorrect, glide typing, trigram predictions, emoji search, inline
  calculator — all fully offline
- MIT licensed code, CC BY-SA 4.0 dictionaries

## Device compatibility

Runs on any Android 8.0+ phone or tablet (API 26, ~97% of devices) — Samsung, Xiaomi, Pixel, OnePlus, Oppo, Huawei and everything else. No Google services required, pure Kotlin with no native code, works on every CPU architecture.

- **Direct boot aware** — the keyboard works on the lock screen right after a reboot, before your first unlock
- **No fullscreen extract mode** — landscape typing keeps your app visible, Gboard-style
- **Themed navigation bar** — no white system-bar strip under a dark keyboard on 3-button-nav devices
- **Emoji filtered per device** — emoji your Android version can't render are hidden instead of showing ▯ boxes

## What's new

The latest release is **2.8.0**. See **[CHANGELOG.md](CHANGELOG.md)** for the release notes of this and every earlier version.

## Features

**Typing**
- Glide typing: slide across letters to type a word, with a swipe trail and
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
- Long-press Enter inserts a newline in chat apps where Enter sends
- Multi-touch typing (rollover), repeating backspace
- Inline calculator — type `12*34` for a "= 408" chip. Handles parentheses,
  percentages the way a pocket calculator does (`150+18%` → 177, `200-10%` →
  180) and metric/imperial conversion (`5km=` → 3.1069 mi)

**Tools**
- **Toolbar** — the chevron on the idle suggestion strip expands into a
  scrollable row of tools: edit panel, one-handed, floating, incognito,
  clipboard, numpad and more. Long-press and drag any icon to reorder it, or
  choose which tools appear in Settings. Long-press the comma key for a quick
  😊 shortcut.
- ◨ **One-handed mode** — shrink the keyboard to either side (toolbar → ◨); a
  rail switches sides or expands back. Auto-off in landscape.
- 📋 **Clipboard history** — last 10 copied items (toolbar → 📋, or long-press
  the paste chip). Kept in RAM only, never written to disk, cleared when the
  keyboard process ends, disabled in incognito, 🗑 wipes it instantly.

**Languages**
- 22 languages out of the box (see the list at the top) — pick any set in
  Settings; 🌐 cycles them, long-press for the system picker; locale-correct
  casing (Turkish i → İ, Cyrillic, etc.)
- Add more languages by dropping a dictionary file and a layout (see below)

**Privacy**
- **Zero permissions.** No `INTERNET`, no contacts, no microphone — nothing.
  Feel free to verify the manifest.
- **Incognito mode** (🕶): open the toolbar → 🕶, or enable
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

The bundled lists hold up to 200,000 words for the eight core languages and
100,000 for the rest (from the OpenSubtitles frequency corpus). To regenerate
or enlarge them:

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
