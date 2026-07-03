# RimBoard

A free, open-source Android keyboard with a GBoard-style layout, on-device
suggestions, and a real incognito mode. No ads, no accounts, no analytics,
and **zero Android permissions** — the app cannot touch the internet even if
it wanted to.

- Kotlin, no heavyweight dependencies, single small APK
- English (QWERTY) and Turkish (Q-klavye, 12-key rows with ğ ü ş i ö ç)
- MIT licensed code, CC BY-SA 4.0 dictionaries

## Features

**Typing**
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
- Multi-touch typing (rollover), repeating backspace

**Languages**
- English and Turkish out of the box; 🌐 key cycles languages, long-press it
  for the system keyboard picker; correct Turkish casing (i → İ, ı → I)
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

- Glide (swipe) typing
- Voice input
- One-handed / floating mode
- Clipboard history (currently a single "paste latest" chip)

## License

- **Code:** MIT (see `LICENSE`). If you prefer a public-domain-style grant,
  swapping in The Unlicense only requires replacing that file.
- **Dictionaries:** derived from
  [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit
  Dave (OpenSubtitles 2018 corpus), CC BY-SA 4.0 — see `NOTICE`. Derivative
  dictionary files must stay CC BY-SA 4.0; the app code is unaffected.
