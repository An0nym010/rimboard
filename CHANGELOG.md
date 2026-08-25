# Changelog

Release notes for every RimBoard version. The current release is summarised in the [README](README.md).

## Unreleased

**The keyboard stops rewriting names**
- **A capital in mid-sentence now means "this is a name", to autocorrect as well
  as to the underline.** Typing a name the dictionary has never seen, the space
  bar silently replaced it: "César" was committed as "Cesar", "Noël" as "Noel",
  "Parijs" as "Paris", "Sundays" as "Sunday". Measured over the real proper
  nouns in the test corpus that the shipped word list does not hold, 11.7% of
  English and 15.1% of Turkish were overwritten — and typing them with their
  capital changed nothing whatever, because autocorrect never looked at it. The
  spell checker had been declining to underline those same words all along, on
  exactly this reasoning, so the two halves of one keyboard held opposite
  opinions about whether a capital means anything. They share one rule now.
- **Refusing to commit is not refusing to offer.** The correction keeps its chip
  on the strip, so a capitalised word that really was mistyped is one tap from
  being fixed rather than silently changed.
- The first word of a sentence is unaffected, which is not a detail:
  auto-capitalisation puts a capital on it, so a rule blind to sentence position
  would have switched autocorrect off for a fifth of everything anyone writes.
  German is excluded, because it capitalises every noun — the same exclusion the
  spell checker already makes, and it means German names get no protection.
- **Found while adding it: user-defined shortcuts shared autocorrect's gate**,
  so the new rule would have stopped "Omw" expanding to "On my way" anywhere but
  the start of a sentence. A shortcut is configuration the user wrote down
  themselves and a heuristic about names has no standing to overrule it. The two
  questions are now asked separately.

**Ukrainian stopped being corrected into Russian**
- **`комп'ютер` was underlined and then rewritten as `компьютер`.** In Ukrainian
  the apostrophe is a letter, part of the spelling of ordinary words — computer,
  health, family, name, meat, five — but the corpus behind the word lists split
  at every apostrophe, so none of those words was in the list and both halves
  were counted as words instead. Underlining them was bad; silently replacing
  one with the Russian spelling was worse. Seventeen everyday words checked, all
  accepted now, and the corpus has none left unknown.
- The halves being present is what makes it recoverable: a fragment like `ютер`
  or `їзд` is not a word and is in the list only because something was cut in
  front of it. Ukrainian orthography allows the mark in exactly one place —
  after a consonant, before я ю є ї — which is what keeps the rule from
  accepting anything else. It names no language: Russian shares the alphabet,
  does not write the mark that way, and is untouched.

**Turkish stopped underlining every proper noun**
- **"Paris'e", "Türkiye'de", "ABD'de" were all marked misspelled.** Turkish
  attaches its case endings to names and acronyms across an apostrophe, so every
  sentence naming a person, a place or an organisation had one — and every
  single one was rejected. Twelve of the fifteen unrecognised words in the
  Turkish test corpus were this, 80% of the total; the same count is zero in
  English, French and Italian, which have had their apostrophes handled since
  the contraction work. Now one, and it is a place name the corpus has simply
  never seen.
- The rule deliberately does **not** check vowel harmony across the apostrophe,
  because that is exactly what the apostrophe is there to say: "ABD" is
  pronounced "a-be-de" and takes a front-vowel ending after a back-vowel
  spelling. Joining the halves and reusing the ordinary word-building check gets
  nine of the twelve and fails on precisely the ones the mark exists to write.
- Foreign names take Turkish endings the same way — "Google'a", "Twitter'da" —
  and those work now too.

**Two things that only a phone could show**
- **The setup screen's buttons were unreadable.** Dark purple lettering on the
  blue, 2.92:1 against the 4.5:1 needed to read it — on the first screen a new
  install shows. The theme took over the app's primary colour and left the
  colour *written on top of it* at Material's own default, which in the dark
  theme is purple.
- **Every switch in settings had a purple track around a blue thumb.** The same
  fault one attribute along: a switch tints its track from one colour and its
  thumb from another, so claiming half the family put two design systems in one
  control. Both halves are the app's own blues now, and a test holds the family
  together rather than the single line that was missing.

**The backspace swipe gives back exactly what it took**
- **Sliding right to undo left a character behind.** Backspace is a repeating
  key, so the finger going down deletes one character before the swipe can arm
  at 30dp of travel — and only whole words removed after that point were ever
  recorded. Swiping left to delete a few words and then changing your mind
  restored the words and silently kept the missing character; pausing on the key
  first lost more than one. An undo that does not round-trip is worse than none,
  because it invites you to rely on it. Everything removed since the finger went
  down is now restored, in the order it stood in the text.

**Three faults found by reading the changes rather than running them**
- **A swipe could offer a word it had nothing to do with.** If the keyboard had
  ever learned "wolfram", swiping "helo" put it on the strip. Learned words used
  to be filtered by shape before being scored; replacing the decoder replaced
  that filter with a *fit*, and a fit excludes almost nothing on its own —
  every word made of letters the layout draws sits some finite distance from
  some path. The dictionary kept its gate, the personal list silently lost it.
  Both use the same one now.
- **A word deleted from the personal dictionary kept its pin.** Words added by
  hand are never dropped when the learned list is trimmed; nothing cleared that
  mark when the word was deleted or blocked. So it sat in the file for good and
  would have re-applied itself if the word were typed again, on the strength of
  a decision the user had since undone.
- **A missing-space suggestion claimed the leftmost chip mid-word.** Typing
  "airport", the strip at "airpo" led with "air po" — a proposal to put a space
  in the middle of a word plainly still being written. French "aujo" gave
  "au jo" and Turkish "kita" gave "ki ta". A split fills a spare chip now rather
  than taking one; a word that really is two words run together has no
  continuations to lose to, so it still wins its place in the case it exists for.

**Things that were checked and left alone**
- Widening the window a fuzzy prefix search looks back over, from four
  characters to twelve, changes no figure at all. Neither does letting it
  substitute the first letter of a word, which it is otherwise forbidden to do:
  the ordinary corrector already reaches a first-letter slip.
- Weighting the ends of a swipe above its middle does not help either. The ends
  look like the reliable part — they are aimed at from and to rest — but they
  are also where overshoot lives, and the two cancel exactly.
- The five percent of mistyped words that cannot be recovered from the strip are
  mostly slips onto *another real word*: "if" typed as "of", "good" as "food",
  "be" as "he". The strip offers completions of what was actually typed, because
  what was actually typed is a word. They are overwhelmingly one- to
  three-letter words where there was almost nothing to save. That is the floor
  of typo correction without deeper context, not a defect to fix.

**Glide typing reads the shape of the swipe**
- A swipe used to reach the dictionary as a string: the keys the finger crossed,
  in order, with repeats dropped. Everything else about the gesture — where it
  actually went — was measured to draw the trail and then thrown away. Rounding
  a corner a third of a key short of `l` removed `l` from that string, and with
  it every word needing one, so a swipe that looks unmistakable to anyone
  watching the trail decoded to nothing.
- A word now names a curve — the line through its letters' keys — and the
  decoder compares that against the line the finger drew, point for point. On
  swipes with the ordinary distortions of a real hand (momentum rounding the
  corners, the odd cut corner, a shaking thumb), the right word went from being
  first 17% of the time to 88% in English and 8% to 92% in Turkish. On a hurried
  flick, from 3% to 75%. It is on the strip essentially always: 95–100%.
- Two models were built and measured before the right one. Charging every point
  of the path to the nearest letter of the candidate reads as a coverage test,
  and coverage is not what a swipe means — it billed the correct word for the
  ordinary business of travelling between its own letters and offered "stuff"
  for a swipe of "said". The tell was that a *flawless* swipe scored 1.2 key
  widths of error, when a perfect gesture should cost nothing.
- The trail is also thinned when it gets long rather than dropped from the
  front, which used to behead exactly the long swipes that most need decoding,
  and the n-grams that rank every tapped word now rank swiped ones too.

**A prefix is not a misspelling**
- Mid-word the strip has two free chips, and both were going to *repairs* of
  what had been typed rather than *continuations* of it. Typing "airport", at
  "airp" the strip offered "air" and "airs" — two ways of deleting what you had
  just typed — and left off "airport", the commonest completion in the
  dictionary by a distance. "abro" offered "a bro", proposing a space in the
  middle of a word still being written.
- One chip is now kept for finishing the word, and only when there is something
  to finish it with. The best repair keeps its place, so a typo already inside
  the prefix is still fixable; a finished word that is simply wrong has no
  continuation and is untouched; and whatever the space bar would commit is
  never what gets displaced.
- Generated Turkish inflections were also ranked above every attested
  completion but one, which is the opposite of what the code said they did.

**"don't" is a word**
- The keyboard treats an apostrophe as part of a word — it has to, or "don't"
  composes as two — and the bundled word lists come from a corpus whose
  tokeniser split at the apostrophe. So the joined form was in no list, and the
  system spell checker underlined "don't", "it's", "can't" and "we'll" **in
  every app on the phone**, offering *donut*, *its*, *cant* and *well* to
  replace them. French elides in nearly every sentence, so French was underlined
  nearly everywhere.
- The lists do hold the halves, stored the way each language elides — English
  puts the apostrophe on the suffix (`don` + `'t`), French and Italian on the
  article (`l'` + `homme`). A word is now well formed if it splits at an
  apostrophe into two halves the dictionary knows, which names no language: a
  list with no apostrophe entries, as German's and Turkish's have none, matches
  nothing.
- The strip can complete them too. The elided article is generative and needs no
  list; the English contraction cannot be, because `don` + `'t` and `don` + `'s`
  are both pairs of known entries and the corpus makes `'s` the commoner, so it
  comes from the curated contraction table instead. That table holds the common
  forms rather than every form.
- French typing went from twelfth of the twenty-two shipped languages to fourth.

**A keystroke costs a fraction of what it did, and two languages a third of the memory**
- Nothing had ever timed the keyboard's own per-keystroke work, which happens
  between the finger going down and the next frame. The middle keystroke cost
  almost nothing and the slowest one in a hundred cost 6 ms — a tail three
  hundred times the middle, concentrated at the end of long words. It is now
  0.8 ms, without a single suggestion changing.
- The distance function allocated three arrays for every candidate word across a
  scan of a hundred thousand of them, and computed every cell of a matrix when
  only a narrow band of it can matter. A letter-set bound now discards most
  candidates before any matrix is built at all.
- The word list was three hundred thousand separate objects, spending about
  fifty-six bytes of overhead per word to carry eight bytes of text. It is one
  array now. With the removal of a duplicate index that answered a question the
  sorted list already answers, English fell from 30.6 MB to 9.1 MB and two
  languages with the spell checker from about ninety to twenty-seven.
- The slowest languages to load are about twice as fast as well, because a word
  with no accent in it no longer goes through Unicode normalisation to find that
  out. German fell from 337 ms to 159 and Czech from 250 to 119; English, which
  was never slow, did not move. This is a background load, so what it shortens
  is the window in which the keyboard types fine and suggests nothing.

**What the keyboard learns now has a limit**
- The two-word and three-word tables have always had caps and dropped their
  least-used entries to meet them. The word table had none: its only rule
  dropped words seen *once*, and a word seen twice is not one. Forty thousand
  distinct words typed twice each were all still held. That file is read every
  time the keyboard warms up and kept for as long as it runs, so it is a
  keyboard that gets slower for as long as it is used.
- It holds twelve thousand now, dropping the least-used first, which is far more
  than an active typing vocabulary. Words added by hand in the personal
  dictionary are never dropped: they sit at three uses and stay there, so a
  use-ordered eviction would take them first, and losing one is not a trade-off
  — the point of that screen is that the keyboard stops arguing about the word.

**Numbers instead of opinions**
- Six things that had never been measured now are, each with a floor or ceiling
  set from what was measured rather than from a wish: how often glide typing is
  right, how much typing the suggestion strip saves across all twenty-two
  languages, what a keystroke costs, how long until suggestions arrive, how much
  memory a language holds, and what the system spell checker costs the app that
  asked it.
- The strip is measured against real prose rather than generated words, and in
  two arms — one blind to context, because the bundled n-grams were counted from
  the same source as the test sentences and a number scored partly on its own
  training data is a ceiling rather than a figure.
- Four ways of closing the gap for heavily inflected languages were tried and
  none worked; they are written down where the next person will think of them.
  Czech, Slovak, Ukrainian, Hungarian, Turkish, Russian and Polish still save
  markedly less typing than English or Dutch, and closing that needs the
  keyboard to know grammatical agreement rather than word counts.

**The keyboard stayed light after the system went dark**
- "Match the app's light or dark mode" reads the app's declared theme, and
  another package's resources resolve through *this* process's configuration
  — so an app with `-night` resources reads as light while the system is
  light, and as dark once it is not. That answer was cached under the package
  name alone, and nothing dropped it when the configuration changed: the
  reading taken before a flip went on being served after it. The app turned
  black, the keyboard stayed white, and it did so by overruling with a stale
  answer the very night setting it would have followed had it read nothing at
  all. The polarity is now part of the cache key, so a flip asks a new question
  instead of getting the old one's answer.
- What this still cannot see is an app's *own* dark-mode switch. That is
  resolved inside the app and is not visible from outside it, so an in-app
  toggle changes nothing here; following the system is the whole of what any
  keyboard can do.
- The guard against two lookups at once was a single field naming the last
  package asked about, set before the call but cleared only from the callback.
  Every lookup that returned early — the answers already known, or the read
  failing — left it set for good, and it then refused the next question
  about that same app: after the palette cache was dropped under memory
  pressure, an app's colours never came back while the user stayed in it. One
  field could not describe two apps either. It has moved into `AppPalette`,
  where the key is, as a set of the lookups actually running, cleared on both
  paths.
- **"App colours" now defaults to any app.** The setting promised more than it
  delivered. "Match the app's light or dark mode" and the per-app tint are both
  on by default, and both promise to match the app you are in — but both read
  it through this one list, which defaulted to about forty well-known apps. In
  anything else the switches were on, correct and inert, which is not
  distinguishable from broken. The list is still there as "Well-known apps" for
  anyone who wants it. What the wide default costs is unchanged and written
  down in the README and in the manifest beside the `<queries>` block that
  makes it possible: the keyboard reads the declared theme of the app being
  typed in, and on the offline build nothing it reads can leave the phone.

**Leaving the translate bar no longer sends a translation**
- Opening a picker was meant to stop whatever the previous one had in flight,
  and cancelled the GIF search only. So opening emoji or GIF while the
  translate bar held text left its debounce armed: it fired into a bar the user
  had already left and sent a translation — which against a metered source is a
  request paid for and never asked for. The reply then passed the staleness
  check too, because the counter that guards it was only moved on by closing
  the bar, not by replacing it.
- Every picker but the one opening is now stopped, stated that way round so a
  fourth added later is covered by default rather than silently missed — which
  is exactly how the translate bar was overlooked when the GIF picker was
  written.
- The panel list had three separate copies, so adding a panel meant editing
  three places and forgetting one would leave two on screen at once. One now.
  A variable shadowing another four lines above it, which the compiler had been
  warning about on every build, is renamed.

**Two clipboard faults, found by making it testable**
- **Pinning a clip and unpinning it lost the "do not preview" flag.** A
  password manager marks what it copies as not-for-preview, and unpinning
  rebuilt the entry from its text alone — so a password came back unmarked and
  the suggestion strip would then show it. The entry is carried across intact
  now, and the flag survives a restart too: the pinned file records it, reading
  both the old plain-string form and the new one.
- **The paste chip ignored the auto-clear timeout.** The clipboard panel pruned
  before drawing and the chip did not, so it kept previewing a clip past the
  time the user had set for it to be forgotten. Reading the history prunes it,
  so both obey the setting.
- The history and pinned list moved out of the service into `ClipboardStore`,
  which is what let either be seen. Behaviour otherwise unchanged, including
  the fencepost — a clip is dropped once it is *older* than the timeout rather
  than as it reaches it — now written down rather than incidental.

**The two builds are two apps**
- They shared an application id, so installing either replaced the other and
  only one could ever be listed anywhere. `online` now carries a `.online`
  suffix. `offline` keeps the plain name, because it is the build the README
  sends people to first — anyone already carrying an online build installs this
  one fresh, which is the price of doing this at all and is smallest now, while
  every release so far has been a debug-signed pre-release.
- Two things had been written against the shared id and quietly broke with it:
  the toolbar-picker row named its own package in an `<intent>`, so on the
  suffixed build it pointed at an app that is not installed and did nothing
  when tapped; and the network screen printed an `adb` command naming the
  *other* build, on the screen whose whole purpose is checking the one you are
  running. A test now rejects any resource that hardcodes the id, while still
  allowing the class names that must spell it out.

**Touch and hold works for a screen reader**
- Keys with alternatives advertised a long-press action and set a hint saying
  what it was for, and performing it did nothing: only `ACTION_CLICK` was
  implemented. It opens the popup now, and the alternatives are exposed as
  their own explorable items — without which the action would open something a
  screen-reader user could not reach into.

**The keyboard no longer stalls the first time it opens in an app**
- Working out an app's colours means parsing its entire resource table and
  rasterising its icon, and both were happening on the main thread inside the
  focus change — so the first open in any given app paid for both while the
  keyboard was appearing. That is the stall the dictionary warming thread
  exists to keep off this path, reintroduced by the back door when the tint and
  the light/dark matching were added.
- The keyboard now opens with whatever is already known and reapplies a moment
  later when the answers land. Being a frame late with a colour is not
  something anyone notices; a keyboard that hesitates when it appears is.
- **App colours** no longer greys out when the tint is switched off. It governs
  which apps the light/dark matching reads too, so a disabled row was quietly
  deciding the behaviour of an enabled feature.

**A dark app gets a dark keyboard**
- **Theme → Match the app's light or dark mode**, on by default, beside the
  tint settings. The app's own theme decides whether the keyboard is light or
  dark, so a dark messaging app no longer gets a white keyboard at midday.
- Read from the app's declared `isLightTheme`, falling back to the luminance of
  its window background where that is not set, and giving up rather than
  guessing where neither answers.
- Applies only to the System and Material You themes — the two that were
  already following something. A theme picked outright is left alone, which is
  the same line the accent tint draws.

**Search the settings**
- Results are inline now rather than in a dialog, so they appear where the
  settings list was instead of covering it.
- Following a result scrolls to the row and flashes it twice. Landing on the
  right screen was only most of the answer: these screens hold a dozen rows and
  the one you searched for looked like all the others.

**Search the settings (first cut)**
- Around fifty settings sit behind nine sub-screens, which is the point where
  "I know it exists, I don't know where you put it" becomes the normal way to
  look for one. The magnifier in the settings toolbar searches all of them.
- A result shows the setting's name *and the screen holding it*, so the answer
  to "where is this" arrives with the result rather than after tapping it.
- Titles come from the same strings the screens show, read in the language the
  interface is set to, so a setting is searchable the moment it exists and
  never under a stale name.
- Accents are ignored in both directions: "duzeltme" finds "düzeltme".

**Sentence starts work again**
- Reading the context back from the cursor decided whether a new sentence was
  starting, then took the preceding word by scanning straight across the
  boundary it had just found. After "Hello. " the openers appeared and were
  replaced a few milliseconds later by continuations of "hello" — and the next
  word typed was learned as following it, across the full stop. The
  sentence-opener model was very nearly unreachable as a result.
- Also fixed one word into a new sentence: the second word back no longer
  reaches into the sentence before it.

**Smaller fixes from the same review**
- The n-gram decay halved every count once per committed word, but only
  *removes* a context whose counts were all 1 — so on a mature model it could
  fail to get under the cap and run a full sweep again on the next keystroke,
  and the next. It now halves until it is actually under, once.
- The run-together split guard multiplied an Int frequency by 150, which
  overflows above 14.3 million and inverts the guard. Unreachable today —
  "that" at 10.2M is the most frequent word of four or more letters in any
  shipped dictionary — but an imported dictionary has no bound.
- Autocorrect was disabled for anything with a capital after the first letter,
  which caught caps-lock typing too, so `TEH` was never fixed. All-capitals is
  now corrected; a capital *inside* a word still is not.
- Each engine's warming thread is released on service destroy. Neither service
  did, and the spell checker's teardown reasoned about the other executor while
  missing this one.
- The learned-words files are written through a temporary file and a rename.
  Two `UserData` instances write them — the keyboard and the personal
  dictionary screen — and a flush landing mid-write could revert an edit.

**Live backgrounds**
- **Theme → Live background**: a drifting night sky, or a grid of particles
  that scatters from each key press and springs back. Both react to where you
  actually pressed, so nothing intercepts a touch and typing is untouched.
- Off by default. It is the only thing in the app that draws while nobody is
  doing anything, and a keyboard is on screen for hours a day.
- The particle grid is Justin Windle's "30,000 Particles" algorithm, MIT via
  CodePen and attributed in NOTICE. Adapted rather than copied: the count is
  derived from the view size instead of a fixed 30,000, the positions live in
  one reused array rather than allocating an image buffer every frame, the
  motion runs on a fixed timestep so a 120Hz phone matches a 60Hz one, and it
  stops drawing once it has come to rest.
- Not drawn when a background photo is set; two moving backgrounds behind the
  same keys is one too many.

**"Strong" vibration was the weakest**
- Support for the platform's predefined effects is per-effect, so a phone that
  implements `EFFECT_HEAVY_CLICK` but not `EFFECT_TICK` or `EFFECT_CLICK` got a
  predefined buzz for Strong and hand-built one-shots for Light and Medium.
  Those are two different scales — a one-shot at full amplitude is a shove,
  while an OEM's "heavy click" can be a short crisp tap — so the order
  inverted. Either all three come from the device's own vocabulary or none do.
- Duration now rises with amplitude across the three, because a good many
  motors have no amplitude control and every level felt identical on those.

**Eight more themes, six of them light**
- Sky, Sand, Lilac, Peach, Sage and Paper, plus Plum and Crimson for the dark
  set. The shipped set was nine dark to three light, which is backwards for a
  keyboard used outdoors.

**Glide delete has a setting**
- The swipe-left-from-backspace gesture has shipped since before there was a
  screen to turn it off on — the wrong way round for something that deletes.

**"hellooo" is not a word, whatever the dictionary says**
- It is in the shipped English list, along with "helloooo" and "hellooooo" —
  the frequency lists are built from web text, where people write that way
  often enough to clear the cutoff. So the keyboard called them correctly
  spelled, which is why marking an unrecognised word worked on some words and
  not others: whether a held-down letter was noticed depended on whether that
  particular elongation had reached the corpus. Nothing was random, but from
  the outside it looked it.
- A word now counts as an elongation only when collapsing the run lands on a
  word the dictionary already has, and that word leads the suggestions:
  `hellooo` → `hello`, `coool` → `cool`. "brrr" collapses to "brr", so it is
  offered that rather than being called a misspelling of "berry". A word you
  added by hand is never touched.
- Elongations are also no longer offered as completions, so typing "hello"
  stops suggesting "hellooo".

**The copied-text chip is a notice, not a word**
- Cut to twelve characters — `QWERTYUIOPAS…` — and drawn as an outlined pill
  in the accent colour, so it reads as something you copied elsewhere rather
  than as the keyboard's guess at what you are typing.

**Spell-check answers could be attributed to the wrong word**
- The spell checker returned one shared object for every word it declines to
  judge — a URL, a version number, an acronym. `SuggestionsInfo` reads like an
  immutable value and is not one: the framework calls `setCookieAndSequence` on
  whatever a session returns, tagging the answer with the word it belongs to,
  and matches answers back to words by that tag. A batch of words therefore
  came back as the same object carrying whichever tag was written last, and
  sessions run on binder threads, so it was being mutated from more than one at
  a time.

**Typing time could go backwards**
- The active-typing total measured the gap between keystrokes with wall-clock
  time, which is not monotonic. A clock correction moving backwards produced a
  negative gap, which is under the three-second threshold and so was added —
  quietly reducing the recorded total, and taking words-per-minute with it.

**The paste chip shows what you copied**
- It said "Paste", which tells you an action is available but not whether it is
  the thing you meant. It now shows the text itself, flattened to one line and
  cut short. Tapping it pastes the clipboard exactly as before — the label is
  only a label.
- Two cases keep the old wording, and they are the point of the change being
  careful: a clip the copier marked sensitive (which is how a password manager
  says "do not preview this", and which Android's own clipboard preview
  honours), and any password field, which covers the apps that set no flag.
  Incognito never captures clips in the first place, so nothing is shown there
  either.
- The text comes from the clipboard listener's own record rather than being
  read on each strip update. Reading the clipboard is what raises the system's
  "pasted from your clipboard" notice, and doing it per keystroke would fire
  that continuously.

**An unrecognised word is marked as one**
- The typed word always sat in the first slot, looking exactly like a
  suggestion the keyboard was making. A word the dictionary does not know now
  moves to the middle and appears in quotes — `“hellooo”` — with a suggestion
  either side of it, so the front slot carries the best correction instead of
  repeating what is already visible in the field. The word stays tappable: a
  keyboard that hides what you typed is arguing with you about your own name.
- **A word with nothing to suggest is alone on the strip**, quoted. That is the
  honest display for `mndsnfms` — there is no candidate to rank against it, and
  filling the other slots would mean inventing entries. It is decided by having
  no candidates rather than by judging a word random, which matters: a rare or
  foreign word is not gibberish.
- **An empty field carries no suggestions.** Openers used to appear there, so
  an untouched field already had three words in it before anything was typed.

**Separate APKs in CI**
- The build uploaded one artifact holding both flavors, so getting the one you
  wanted meant downloading the one you did not. They are separate artifacts
  now, named for the flavor.

**Changing the interface language reaches screens already open**
- Every activity was built for the right language and none of them was rebuilt
  when the choice changed. Only the screen holding the picker recreated itself;
  everything else was already in the back stack, built for the old locale, so
  pressing Back returned to the setup screen still in the previous language —
  until the app was killed. Each activity now remembers which language it was
  built for and rebuilds itself when it comes back to the front, so it holds
  for a stack of any depth and for screens not written yet.

**A publish button for releases**
- The build workflow could only attach APKs to a release when a tag was
  pushed, which needs a computer. "Run workflow" in the Actions tab now offers
  a checkbox and a tag field, so a release can be cut from a phone. The rule
  that a plain version number may not be debug-signed applies to it too — a
  button that could bypass that would be a hole rather than a convenience.

**The per-app tint is now something you can see**
- It had been running all along and was tuned to invisibility: each surface
  was blended six percent toward the accent, which on a dark theme is nothing
  at all. Reported, fairly, as the feature not working.
- Blending was the wrong instrument, because mixing toward a colour drags
  lightness with it — which is why it had to stay tiny, or a near-black
  background would have lifted and taken the theme's contrast with it. The hue
  is now replaced while each surface keeps its own lightness, so it can go far
  enough to notice at no cost to legibility. Key text, hints and the light or
  dark polarity are still untouched.
- **Theme → Tint strength**: subtle, medium (default) or strong.
- The colour is now taken from the app's declared `colorPrimary` where it can
  be read, falling back to the launcher icon and then to the package-name hue.
  A theme colour is what the app chose to paint itself, which is a better
  answer to "the app's main colour" than the dominant hue of a picture.

**A failed request says something you can act on**
- The provider's own error body was the message, and it went straight onto the
  translate bar's two-line strip — so a failing service produced a clipped
  fragment of someone else's JSON (`HTTP 500: {"error":"An error occurred
  while retrieving the`) and left you to work out whether you had done
  something wrong. There are only three things worth knowing: the phone is
  offline, the service is broken and it is worth retrying, or the request will
  never be accepted. It says which; the detail goes to the log.
- **"1 requests" is now "1 request".** The counter was a format string with no
  plural form. It is a plurals resource now, which also matters past English —
  Russian needs three forms and Turkish adds no suffix after a numeral at all.
- **"Detect" is translated.** It sat in English beside Turkish labels. The
  network strings are deliberately left in English because they are consent
  text about what leaves the device, and a softened machine translation is
  worse than visible English — but a language-detection label and a request
  counter are not consent text, and that reasoning never covered them.

**The per-app tint uses the app's actual colour**
- It never did. The hue came from a hash of the package name, so WhatsApp got
  a stable colour but not WhatsApp green — a documented trade-off, and still
  the wrong answer for a setting called "tint to match the app". The current
  app's launcher icon is now read and its dominant colour used, with the hash
  kept as the fallback for anything that cannot be read or has a monochrome
  icon.
- This needed package visibility, which the keyboard did not have. The manifest
  now declares a launcher-intent query, so **every app with an icon is visible
  to the keyboard**. That is not a permission — the offline APK still declares
  only `VIBRATE` — and on the offline build there is no `INTERNET` by any
  route, so nothing it sees can leave. The README says so in its own section
  rather than leaving it to be discovered.
- **Settings → Theme → App colours** chooses between a fixed list of about
  forty well-known apps (the default) and any app at all. It restrains what
  RimBoard reads, not what it could read; a setting cannot revoke a manifest
  declaration, and only a second build dimension would make it provable.

**GIF thumbnails stop costing what they were costing**
- **Decoded to the tile, not to whatever the provider sent.** Every preview was
  decoded at full source resolution into ARGB_8888 and then drawn into a
  two-column tile 92dp tall. What the grid can show is bounded by the screen;
  what was held in memory was bounded by nothing — two dozen results at
  480x270 is about twelve megabytes, in a process that also holds the
  dictionaries and gets killed before anything the user can see.
- **Released when the panel closes.** They were only dropped when it was next
  *opened*, so one GIF search in the morning was still resident at midnight.
  They are released on close and under memory pressure now; the tiles stay, so
  the grid keeps its shape and scroll position and the placeholders come back.
- The emoji grid was checked for the same fault and does not have it — its
  adapter has always recycled through `convertView`, as does the GIF grid's.

**Panels no longer act after they are gone**
- The GIF panel and the translate bar each schedule work on a delay — a search,
  a translation — and neither cancelled it when the view was taken away. The
  service cancels on every route the *user* takes out of a panel, but not on
  the ones where the view is removed underneath them: a rotation rebuilds the
  whole input view, and Android does not take a posted callback off the queue
  when its view detaches. It fires on time, into a panel that no longer exists.
  For the translate bar that meant sending a translation request — billed,
  against the Anthropic source — for a bar that had already gone, with the
  answer landing in the newly built one.
- The other four views that schedule delayed work already cancelled it on
  detach, one with a comment describing this exact hazard. A test now requires
  the handler to exist, so the next panel with a debounce cannot quietly skip
  it.

**Vibration, and an honest answer about it**
- The previous attempt at this was aimed the wrong way. It declared key
  vibrations as `USAGE_TOUCH` on the reasoning that an undeclared vibration
  gets dropped — but `USAGE_TOUCH` is precisely the category the system's own
  touch-feedback switch governs. With that switch off, the keyboard was asking
  the platform to play a touch vibration on a device told not to play them.
  The usage is now chosen from the switch, and when it is off the request goes
  out under a usage that switch does not govern.
- **The settings screen no longer claims something untrue.** "Drives the
  vibrator directly, so it keeps working when system touch feedback is off"
  was wrong in all eight languages, and wrong specifically for the people it
  was written to reassure. When the system switch is off, the screen now says
  so and says that Android suppresses keyboard vibration while it is.
- **Changing vibration strength plays a sample.** Whether a vibration survives
  the platform's filtering cannot be observed from inside the app — a
  suppressed one throws nothing and returns normally, which is how this fault
  was diagnosed from theory twice and fixed wrongly once. The only reliable
  instrument is the user's hand.

**Three faults found reading the service file**
- **A clock change no longer types a full stop.** Double-space-to-period and
  double-tap-shift measured the gap between taps with wall-clock time, which is
  not monotonic — an automatic time correction stepping backwards makes the gap
  negative, and a negative gap is "well inside the window". A single space
  after a letter then became ". " on its own, and a single shift became caps
  lock. Both now use the monotonic clock the rest of the file already used, and
  the rule is a tested function rather than a comparison written twice.
- **A GIF can no longer land in the wrong app.** Downloading one is the longest
  wait in the keyboard, and it was the only async path without a staleness
  check — the thumbnails have one and the AI reply re-checks what it was asked
  about. Switching apps while a GIF downloaded inserted it into whatever was
  focused when it arrived.
- **A failed translation can be retried.** The guard against paying twice for
  the same words recorded the request before sending it, so a translation lost
  to a dropped connection counted as already asked and the timer turned every
  retry away. The only escape was editing the message.

**Stability: three faults introduced by sharing the dictionary**
- **The keyboard can give memory back when the system asks.** Sharing the
  dictionary between the keyboard and the spell checker made the cache static,
  and that removed the only thing that ever released it — the maps used to die
  with their engine, and instead began outliving the service, holding every
  language ever typed at roughly fifteen megabytes each until the process was
  killed. A keyboard is a background process and near the front of the kill
  list, so this was the memory deciding whether it survived to the next
  sentence. It now drops everything but the languages in use when the platform
  signals pressure; a language returned to reloads.
- **A keystroke no longer waits for a background parse.** The three lazily
  loaded asset maps were each `@Synchronized`, which put them behind one
  monitor, held for the whole of a parse. Warming — whose only purpose is to
  keep the first keystroke off the slow path — took that monitor to parse the
  prediction model, so the first keystroke needing an emoji or the offensive
  list blocked behind exactly the work that was supposed to be getting out of
  its way. Each map now has its own lock.
- **Warming no longer makes a thread per focus change.** It runs on every app
  switch, rotation and settings change, and each call started a new `Thread`;
  before the first load finished they did not return quickly either, but piled
  up on the dictionary lock. One reusable daemon thread now.

**The keyboard takes on a colour from the app you are typing in**
- On by default, and switchable off under Theme → "Tint to match the app".
  Whichever theme you have chosen keeps its character; only the accent — enter,
  caps lock, the active suggestion — moves to a hue belonging to that app, with
  the surfaces following a few percent behind. Key text, hints and the light or
  dark polarity are untouched, so contrast stays exactly as the theme was
  designed for whatever hue comes out.
- The hue comes from the package name, not from the app's icon. Sampling the
  icon would mean reading another package's resources, which needs
  `QUERY_ALL_PACKAGES` — a permission that would cost this keyboard its
  headline claim for a nicety. So the colour is not the app's brand colour and
  does not claim to be; it is distinct, and it is the same every time.
- Themes chosen for their colours specifically are left alone: high contrast,
  the three custom slots, and Material You, each of which already answers to
  something the user picked.

**Vibration works on phones that had none**
- Key haptics asked the vibrator for a buzz without saying what kind it was.
  An unattributed vibration is filed under "unknown usage", and an
  unknown-usage vibration coming from a background service is exactly what the
  platform discards when the system's own touch-feedback switch is off — MIUI
  and HyperOS more readily than most. Nothing errored: the motor was fine, the
  permission was held, the call returned, and the phone stayed still. It now
  says the vibration is touch feedback for a key that was just pressed, which
  is the thing that decides whether it survives.

**Translate bar**
- **You can put the cursor where the mistake is.** Typing into the bar only
  ever appended, so a typo four words back could not be reached — the only way
  in was to delete everything after it. Tapping the text now places a caret and
  typing and backspace happen there.
- **A translation is no longer written out twice.** Inserting an emoji while
  the bar was open left it sitting after the translation, and the replacement
  looked for the previous translation only in the characters immediately before
  the cursor. It was no longer there, so nothing was removed and the whole
  translation was inserted a second time. It is now found wherever it sits, and
  anything typed after it is carried across rather than stranded between two
  copies.

**The suggestion strip carries words**
- Recent emoji no longer appear there, and the setting that offered them is
  gone with them.
- A word that matched an emoji used to spend the third suggestion slot on it,
  which quietly cost a real suggestion on exactly the words most likely to have
  one. All three slots are words now. The emoji key is unchanged — picker,
  search and recents all work as they did.

**Prediction models**
- **Turkish, Spanish, German and Russian roughly tripled**, and English grew by
  half. Turkish went from 115 contexts to 210, Russian 115 to 194, German 115
  to 193, Spanish 115 to 159, English 115 to 174 — and the lists behind each
  context roughly doubled in length. What was missing was mostly structural
  rather than vocabulary: Russian had no `не`, no prepositions and no
  conjunctions; German had no subordinating conjunctions and no participles for
  the perfect; Spanish had no preterite and none of the `tener` idioms. Same
  provenance as before: written by hand, no external corpus.
- Turkish gained the most contexts because it needs them most. The context is
  whatever word came before, and an agglutinative language spells every
  grammatical difference as a different word — "geldim", "geldin", "geldi" and
  "geliyorum" are four contexts where English has one "came".
- German nouns keep their capitals. A prediction is committed exactly as it is
  written, with no typed prefix to copy a capital from the way a completion
  has, so "vielen Dank" was being offered as "vielen dank". Predictions are now
  scored under a case-folded key and shown in the curated spelling, which also
  stops a learned lower-case copy and the curated one competing for the same
  slot as though they were two different words.
- Fixed in passing: `добрый утро` (neuter noun under the masculine article),
  `viele dank`, a duplicated Turkish continuation, `akşam yemek` for `akşam
  yemeği`, and three entries that could never fire at all — `что-то`,
  `наконец-то` and `вообще-то` are single words to the tokeniser, so no
  word-pair table can reach them.

**Completions were ranked, then thrown away**
- **The commonest words were never offered.** Prefix lookup collected the first
  80 matches and ranked *those* — but the word list is held alphabetically,
  because that is what the binary search needs, so the first 80 matches of a
  short prefix are the alphabetically earliest and those are overwhelmingly the
  rarest. "th" filled its slots with "tha", "thai", "thailand" and the long tail
  of "thank..." forms and never reached "the"; "s" never reached "so". Measured
  against the shipped lists that was the wrong top completion for 91% of
  one-letter and 64% of two-letter prefixes, in every language — the commonest
  prefixes there are, and the moment the strip is leaned on hardest. Every match
  is now considered, by a bounded selection that costs one comparison per word
  and allocates nothing.

**Case, where a language disagrees about what lower case means**
- **The offensive-word filter could be walked straight past.** It ran on
  suggestions already cased for display and folded them back without a locale.
  Turkish "İbne" became `i` + a combining dot rather than "ibne", and German
  "scheiße" capitalises to "SCHEISSE", which nothing turns back — so with the
  filter switched on, a capitalised slur was offered anyway. English, Spanish
  and Russian never showed it, because their case mapping happens not to depend
  on the language.
- **Words added to the personal dictionary were never recognised.** They were
  filed by one set of rules and looked up by another: "Işık" was stored as
  "işık" and searched for as "ışık", and "İstanbul" was stored under a key the
  typing path cannot produce at all. A name added expressly to stop autocorrect
  touching it went on being corrected. Text shortcuts and emoji search had the
  same split.

**Typing**
- **A tap that slid a little typed nothing.** Gliding arms after 14dp of travel,
  but a letter key is wider than that in both directions, so a thumb that moves
  while pressing could cross the threshold without ever leaving the key. One key
  is not a word, so nothing was sent anywhere and the keystroke simply vanished.
  A drag that never leaves its key is now a tap, which is what it was.
- Suggestions after moving the cursor no longer depend on where it had been:
  whether the cursor sits at the start of a sentence is now read from the text,
  like the two words beside it, instead of being left over from before the move.

**Answers arriving after the question changed**
- **Closing the translate bar no longer types into your message.** A request
  already on the wire cannot be recalled, and its answer was inserted whenever
  it arrived — after the bar had been closed. A slow reply could also overwrite
  a newer translation with an older one, because whichever landed last won.
- **GIF search could show the wrong results, permanently blank.** A slow search
  returning after a later one replaced the newer grid, and every thumbnail it
  then asked for was correctly discarded as stale — leaving the results it had
  just installed with no images at all, for good.
- Opening the translate bar on a selection no longer clears the bookkeeping for
  the request that opening it just sent, which had the counter showing none
  outstanding and could bill the same unchanged text twice.

**A self-hosted translator is used only for the service you picked**
- One address field cannot say which software answers at it, and the two keyless
  translators speak incompatible protocols. With the source left on Automatic —
  the default — a self-hosted LibreTranslate address was being sent requests in
  Lingva's shape, so every translation failed. The instance is now used for a
  service you chose, and the Network screen says so when it is set but idle.

**Next-word prediction**
- **The strip is no longer empty at the start of a message.** The first word had
  no preceding word, which the engine treated as no context rather than as the
  opening of a sentence — so nothing was suggested at the one moment there is
  the most to guess and the least typed to go on. Openings are now predicted,
  and learned: the keyboard picks up how *you* start a message.
- **The shipped model and your own habits are now weighed, not stacked.** Any
  word pair you had ever typed used to come first and the curated model only
  filled what was left, so a single accidental pairing held the top slot for
  that context until it aged out. Both are scored on one scale: a pair typed
  once sits below the curated first guess, three sightings beat it, and an
  exact two-word context beats it immediately.
- **Learned n-grams now decay.** Counts are halved once the model passes 20,000
  contexts, and anything seen exactly once is dropped. Without this the tables
  only ever grew and only ever remembered — a phrase used heavily during one
  month stayed top of the predictions a year later, because a new habit had to
  out-count a lifetime total.
- **Turkish prediction data went from 15 contexts to 69**, and English from 95
  to 115 — including sentence openers for both. Same provenance as before:
  common non-creative associations written by hand for RimBoard, no external
  corpus. Turkish at 15 contexts was barely a model at all.
- Fixed in passing: the English expansion initially *replaced* existing entries
  rather than extending them, shortening 25 contexts that had been curated
  earlier. The generator now merges.

**Suggestion engine**
- **Turkish is now generated, not looked up.** Inflection is regular enough to
  run forwards: vowel harmony, consonant assimilation and the softening of
  final p/ç/t/k are deterministic rules, so every ordinary form of a known stem
  can be built. Typing "kitapl" now completes to "kitaplar" even though no
  corpus contains it, and "kitaplarimizdan" typed on bare keys comes back as
  "kitaplarımızdan" — a word that cannot be looked up anywhere because no
  frequency list holds it. Previously both produced nothing at all.
- **A typo in a half-typed word no longer blanks the strip.** Exact prefix
  search returns nothing for "helk", so suggestions vanished for the rest of
  the word and only returned once it was finished. Near-miss prefixes are now
  searched too — adjacent key, transposition, doubled letter, dropped letter —
  and rank below exact matches. Costs ~30µs, against ~1.4ms for the correction
  scan that already ran.
- **Missing spaces are spotted.** "alot" offers "a lot", "infact" offers "in
  fact". Harder than it looks, because a web corpus records those spellings as
  words: the rule is how much rarer the run-together form is than its own
  halves, which separates "alot" (~495x) from "cannot" (~37x) and "himself"
  (below 1x). Offered on the strip, never committed automatically.
- **Fixed: "alot" auto-corrected to "lot"**, silently deleting a word on the
  space bar. Where a run-together reading and a spelling correction both fit,
  neither is now applied without a tap.
- The spell checker offers all of the above under the underline.

**Spell check in other apps**
- RimBoard now registers as a **system spell checker**, so the red underlines
  in Gmail, Chrome and everywhere else can come from its dictionaries instead
  of the platform's. Same APK, same engine, no separate install and no new
  permission — a second service component, inert until you pick it under
  Settings → Languages and input → Spell checker.
- The win is the accented and agglutinative languages, where the platform
  checker is weakest: suffix peeling means `kitaplarımızdan` is not flagged,
  bare-key spellings like `gunaydin` are offered their accented form, and words
  the keyboard has learned from you stop being underlined.
- It **never learns**. The keyboard learns as you type; this is handed text
  from every app on the phone — including text pasted or autofilled rather than
  written — and folding that into a personal dictionary would be a far broader
  claim than the keyboard makes. Read-only, by design.
- Tokens it declines to judge rather than guessing at: URLs and addresses,
  anything containing a digit, acronyms, and camelCase. The API distinguishes
  "spelled correctly" from "not my business", and these are the latter.

**Translation**
- Translation no longer needs an API key, and now supports three services
  chosen in Settings → Network: **Lingva** (keyless, the default),
  **LibreTranslate** (self-hostable, or the public instance with a key) and
  **Anthropic** (your own key, best quality and long text). "Automatic" uses
  the best one you have actually set up, and the screen names the one in force
  — a choice that cannot work falls back rather than failing every request.
- MyMemory is gone. It could not detect the source language, so the bar had to
  make you declare what you were typing, and its free tier capped a request at
  500 bytes. All three replacements detect the language, so the bar says
  "Detect" again whichever service is doing the work.
- **Self-hosting.** Set your own Lingva or LibreTranslate hostname and the text
  goes nowhere else. This is the one address the static allowlist cannot know
  in advance, so the gate reads it back from that setting — matched exactly,
  never as a suffix, and still HTTPS-only.
- The translate tool is marked ready in the tools panel whenever the network
  is on, rather than only when an Anthropic key is set, since it no longer
  needs one.

**Fixes**
- Shift and caps lock had no effect on the translate, GIF or emoji search box:
  the keyboard visibly changed case and then typed lower case anyway, so a
  capital letter could not be entered in any of them. They now go through the
  same case handling as the message field.
- Opening 🌍 appeared to replace the suggestion bar. The translate bar now sits
  *above* it, so suggestions and the tool row stay where they are, next to the
  keys.

**Typing**
- Type accented languages on the bare keys and get the accents back: "cafe"
  becomes "café", "gunaydin" becomes "günaydın". The dictionary is now indexed
  by the accent-stripped form of every accented word, so the real spelling is
  offered — and committed on space with a revert chip — whenever the bare form
  is not itself a word. A word that is valid as typed ("cam") is left alone.
- Turkish (and other agglutinative languages) no longer have valid words
  "corrected" away. Turkish builds words by stacking suffixes onto a root, so
  most valid forms — "kitaplarımızdan", "evlerimizden" — never appear in any
  frequency dictionary and the keyboard read that absence as a misspelling.
  A word that peels down to a known root through recognised suffixes is now
  accepted as real, so it is left alone; a genuine typo, which does not peel to
  a root, still corrects. Turkish for now — the framework is there for others.
- Contractions are restored on space: "dont" becomes "don't", "youre" becomes
  "you're", "im" becomes "I'm". The bundled dictionaries come from a corpus
  that stripped apostrophes, so the bare forms sat in the word list with large
  frequencies and the keyboard treated them as correctly spelled — never fixing
  them and even suggesting them over the real spelling. Ambiguous forms whose
  bare spelling is also a real word ("cant", "wont", "ill") are offered to tap
  but never committed automatically, and genuinely-common words like "its" and
  "were" are left alone entirely.
- Suggestions and corrections are ranked by the word before them, not by raw
  frequency alone — "am" is lifted over "and" after "I", and a correction tie
  the dictionary cannot settle ("the stroe") is broken by context.
- Spell-check reaches the same-sized vocabulary in every language. The
  correction-target cutoff was a flat frequency that kept far fewer words in
  smaller-corpus languages than in English; it scales to the corpus now.


**Network — RimBoard now ships as two builds**
- There are two APKs from now on. **`offline`** is what RimBoard has always
  been: `VIBRATE` and nothing else, no `INTERNET`, so Android refuses it a
  connection outright. **`online`** adds `INTERNET`, AI translation, and GIF
  search.
- **GIF and sticker search.** Tabs switch between the two and re-run the same
  query against the other index. The panel carries its own compact keypad, so
  you can type a search into it — an `EditText` inside an IME window fights the
  keyboard it belongs to for focus, so the panel draws keys instead, now as a
  shared `MiniKeypad`. Searches fire on a pause in typing rather than per
  letter. Anything already typed in the field seeds the query, and picking a
  result deletes it, since it was the query and not part of the message.
  Stickers come back as transparent **GIFs** rather than WebP: animated
  WebP only decodes from API 28, so WebP would have left the sticker grid blank
  on Android 8.0 and 8.1 while looking fine on a newer test device. It checks the field
  accepts images *before* opening rather than after a download, and the four
  reasons it can be unavailable produce four different messages instead of one
  shrug. Insertion goes through a `FileProvider` scoped to a single cache
  directory and declared only in the online build — the offline APK ships no
  such component, which `aapt dump xmltree` will show you.
- **🌍 translates in place** on the online build, into whichever language the
  keyboard is set to. It is the same tool as before, not a second icon: with no
  key, with network off, or on the offline build it still just hands the text
  to another app. It needs your own Anthropic API key — an open-source APK has
  nowhere to hide a shared one — and that key is the one piece of RimBoard data
  kept out of device-protected storage, so Android keeps it encrypted until
  first unlock rather than readable on the lock screen. A reply that was
  declined, errored, or cut off at the token limit is reported instead of
  committed; a truncated translation looks finished and would eat the end of
  your sentence.
- The split is a build dimension rather than a setting because it could not
  honestly be a setting. `INTERNET` is a normal install-time permission: an
  APK that declares it is granted it forever, and no in-app toggle can take
  it back. An "offline mode" inside a build holding the permission is the app
  vouching for itself, which is exactly what this keyboard has spent every
  release refusing to ask for. Leaving the permission out of one build keeps
  `aapt dump permissions` a real answer.
- **A first-run dialog** now asks which mode you want, with both sides of the
  trade-off, before the keyboard has been enabled for anything. The offline
  build shows the same dialog as a statement instead of a question, so
  installing the wrong APK is something you find out immediately rather than
  the first time a feature is missing. Your answer is deliberately excluded
  from Export/Import — restoring a backup is not the same act as consenting
  to network access.
- **Settings → Network proves it rather than asserting it.** The screen reads
  the permission list back from Android's own copy of the manifest, then
  actually attempts a TCP connection while you watch and prints whatever the
  system says — on the offline build, `EACCES (Permission denied)`, straight
  from the kernel. It is built so it cannot flatter the build it runs on: the
  same probe on the online build connects, and it says so.
- All network access goes through one function, and a unit test fails the
  build if any other file in the app so much as names a networking API. CI
  now builds both flavors and checks the packaged APKs with `aapt`, so a
  manifest-merger change cannot quietly hand the offline build a socket.
- **Proofread tool.** Fixes spelling, grammar and punctuation in the selection
  and changes nothing else. It shares its whole code path with 🌍, so the
  guards that matter — needs a selection, never on the main thread, re-checks
  the field before committing, refuses in incognito — exist once rather than in
  two copies where one of them quietly lacks the staleness check.
- The generic "rewrite this more nicely" task was removed rather than left
  sitting there: nothing could invoke it, so nothing had ever judged its output.
- **Hold to delete in panel search.** The GIF and sticker panel's keypad now
  repeats on a held backspace and draws proper key caps, matching the emoji
  panel's. Clearing a mistyped search was one tap per character.
- GIF and sticker search use **KLIPY**. It took three attempts to land there.
  Tenor could never have worked for anyone new — Google stopped accepting API
  clients in January 2026 and shut the service down that June. Giphy still
  issues keys but moved production access to paid, with developers reporting
  four-figure quotes. KLIPY has a lifetime free tier, still issues keys, and
  its advertising is opt-in for developers rather than injected into results,
  which is the only reason it fits a keyboard that promises no ads. RimBoard
  does not enable it. Attribution is shown in the panel because that is a
  condition of use, and no `customer_id` is sent — KLIPY accepts a stable
  per-user identifier and taking it would hand over a durable handle joining up
  every search this keyboard makes.
- There is still no keyless option, and there is unlikely to be one: every
  production GIF service needs a key. The ways around that are shipping a
  shared one in an open-source APK, where the first `strings` run finds it and
  one rate limit is shared by every install, or scraping, which breaks the
  provider's terms and this keyboard's own.
- **Failures say which thing went wrong.** `ACCESS_NETWORK_STATE` is now
  actually used — it was declared and unused, which on this app is exactly the
  sort of thing that should not ship. A request that fails while the phone has
  no signal says so, instead of showing a socket timeout and leaving you to
  guess between the keyboard, the API key and the café wifi. The check lives in
  the online flavor's backend rather than in shared code, so the build without
  the permission never compiles a call that needs it.
- **The GIF panel adapts to short keyboards.** Its height is whatever the
  keyboard's is, which in landscape, on small phones, or at the "compact"
  height setting is a fraction of a tall portrait tablet's. The keypad takes a
  share of the height rather than a fixed 132dp, and the category chips yield
  first — the keypad is the only way to type a query, so it is the last thing
  to give up room.
- The setup screen no longer claims "no internet permission" in its subtitle,
  since that now depends on which APK you installed. The claim moved to the
  places that can back it up.

**Suggestion bar and tools**
- The chevron is now a drawer: it slides your pinned tools onto the bar and
  closes when you run one. The settings and clipboard icons are no longer
  fixed on the bar — both are ordinary pinnable tools now, so every slot is
  yours.
- Opening the full tools panel became its own pinnable tool ("All tools"),
  and long-pressing the chevron always reaches it.
- Tool slots size themselves to the bar: a couple sit large, a full drawer
  packs tighter, and past a minimum the row scrolls. A fixed width had been
  overflowing the narrower strip of floating mode.

**Look**
- Background photos, done properly. Portrait photos are no longer sideways —
  the picker now honours the EXIF orientation tag that cameras store the
  rotation in. Keys over a photo become translucent scrims that let the
  picture read through, and whether they scrim dark with light lettering or
  the reverse follows the image itself (measured once, when picked) combined
  with how much dimming is applied. "Image dimming" is a 0–100 slider now
  instead of three presets; an upgrade keeps the strength you had chosen.
  The photo also stopped costing a stutter: it used to be decoded inside the
  draw pass on every open and resize, and now loads off the UI thread.
- Picking a photo now opens a crop screen: the picture sits behind a
  keyboard-shaped window, drag and pinch position it, the dimming slider
  previews live, and Apply saves exactly what you framed. Before, the pick
  saved immediately with a blind centre-crop — whether your subject survived
  was luck.
- The emoji, clipboard, editing and tools panels let the photo through too,
  so opening one no longer blanks the picture out with a flat slab. They
  keep a mostly-opaque surface on purpose — an emoji grid has to stay
  readable over whatever you picked.
- The photo covers the whole keyboard now, suggestion bar included. It used
  to stop at the top of the keys, leaving the bar a flat block of theme
  colour cutting the picture off; the bar now goes transparent over the
  image and its text follows the same light-or-dark adaptation as the keys.
- Flat key style is the default: bare letter glyphs, caps only on the keys
  that are not letters, no shadows. The raised style is one switch away in
  Settings, Theme, and returns automatically behind a background image, where
  bare letters on a photo are unreadable.
- Six new palettes: Ocean, Forest, Sunset, Graphite, Rose and Mint.
- Key labels cross-fade on a plane or language switch; the language name
  flashes on the spacebar when you switch. Motion respects the system
  "remove animations" setting.

**Languages** — every bundled language now has the same support
- Native offensive-word lists for all 22 (was English and Turkish; the rest
  relied on the English fallback).
- Starter next-word predictions for all 22 (was English and Turkish, with no
  fallback at all, so the other twenty had none until you had typed enough).
- Settings, the tools panel and every accessibility label translated into all
  seven interface languages.

**Accessibility**
- Five icon-only buttons that screen readers could not announce now have
  labels: "All tools" on the suggestion strip (which every fresh install
  starts with, at the far left), Translate, Undo and Redo in the editing
  panel, and Clear in the clipboard panel — the one destructive control
  there. Still to do: the six cursor arrows in the editing panel.
- Reopening the tools panel after rearranging it in the picker no longer
  leaves a screen reader describing the previous layout.
- Screen readers can use the keyboard. Every key is announced, with character
  keys naming the glyph they would produce so shift is reflected rather than
  described, and keys with long-press alternatives say so.
- The tools panel is navigable too, with pin and unpin as explicit actions —
  dragging is not a gesture a screen reader user can perform.

**Typing**
- Switching language mid-word keeps the word. It used to commit the
  half-typed word and blank the strip; now the suggestions immediately
  re-run in the language you switched to — so typing an English word on the
  Turkish layout and hitting 🌐 gets you English suggestions for it.
- Spell check reaches further: words the keyboard has learned from you can
  now fix typos of themselves (after the dictionary's own candidates, never
  displacing an obvious fix), and when the current language has no idea what
  you typed, the other enabled language gets to offer a correction chip —
  shown to tap, never auto-committed.
- The recent-emoji row on the idle suggestion bar is now off by default; the
  toggle stays in Settings → General ("Emoji fast-access row").
- The Theme and Keyboard height dropdowns are translated. Twelve of the
  fourteen settings dropdowns pulled their options from translatable strings;
  these two had them written inline, so they stayed English in every
  interface language.
- Inline calculator gained metric/imperial conversion (`5km=`).
- Email fields offer domain completions after `@`, where word suggestions are
  correctly switched off and the bar was otherwise idle.

**Fixes and stability**
- "Leave symbols after space" works. The setting has shipped switched on and
  doing nothing: its one implementation sat on the code path for ordinary
  character keys, which the spacebar never takes.
- Opening emoji, the clipboard or the editing panel from the toolbar drawer
  while the tools panel is open now actually shows them. The tools panel is
  the topmost panel and none of the three hid it, so the new panel was drawn
  underneath and the keyboard looked stuck.
- Fixed a crash when a held key rebuilt the keyboard (floating mode, or
  settings hiding it) while a second finger was landing.
- Fixed a crash when dragging a row in the toolbar arranger if the list was
  mid-relayout.
- "Auto-space after punctuation" no longer inserts a space that was not
  asked for. Its pending state survived pressing space, pressing enter, and
  even moving to another app's text field, so the next letter you typed got
  a space in front of it — a doubled space mid-sentence, or a stray one at
  the start of an empty field.
- Typing statistics format their numbers and the "since" date in the
  interface language you picked, rather than the phone's language.
- The personal dictionary screen shows your words. It read them before the
  load off disk had finished, so it opened saying you had none.
- Text shortcuts restored from a backup now take effect immediately instead
  of waiting for the keyboard process to be killed.
- Emoji that are drawn as plain glyphs rather than colour pictures (⏸ 🕳 🗣
  and friends) now follow the keyboard theme. They were taking their colour
  from the system light/dark setting instead, so they could come out black
  on a black keyboard.
- The skin-tone popup and the "remove word" popup close with the keyboard
  instead of outliving it.
- Picking an image the phone cannot decode now says so, instead of reporting
  "Background removed" — which was both wrong and the opposite of what had
  happened, since the old background was still in place.
- Exporting and restoring a backup no longer freeze the settings screen while
  they work.
- The Theme tool cycles through all thirteen themes. It held its own copy of
  the list, six palettes out of date, so Ocean through Mint were unreachable
  — and starting on one of them threw you back to System with no way in.
- Fixed a data race between the background dictionary warm-up and the UI
  thread that could corrupt the prediction cache.
- Held key repeat no longer survives the keyboard being replaced — a rotation
  mid-hold could keep deleting text from a keyboard that no longer existed.
- Learned words, bigrams and trigrams are now written and waited for at
  shutdown instead of being queued and possibly lost.
- Restoring a backup reports honestly: a failed write no longer says
  "restored", and settings are applied only once the data has landed, so a
  rejected restore leaves your setup alone.
- Dictionary import and background images run off the main thread and always
  report their result; both previously froze the UI on large files and failed
  silently.
- Fixed thread leaks from the settings screens, two force-unwraps on the
  per-keystroke path, and a shortcut save that could throw and vanish.
- Failures that used to be swallowed now log to `adb logcat -s RimBoard`,
  alongside dictionary load timings.

## What's new in 2.8.0

- **Customizable toolbar** — pick your favourite shortcuts in Settings →
  Preferences → Toolbar keys (all 20 actions available); they show in the
  always-visible strip, which now scrolls and shares space with your recent
  emoji instead of replacing them. Press-and-hold icons in the expanded toolbar
  to drag them into your own order.
- **Emoji, finished** — a ninth tab with ~215 flags, long-press any emoji for
  its skin-tone variants (probed from the device font, so only real variants
  are offered), and emoji search now understands German, Spanish, French,
  Italian, Portuguese and Russian keywords alongside English and Turkish.
- **New toggles** — Smart tap correction and the Inline calculator can each be
  switched off under Settings → Suggestions.
- **Accessibility** — every toolbar and strip icon now carries a TalkBack
  label; previously the icon buttons were silent to screen readers.
- **Fixes** — the toolbar stays reachable in incognito mode (so you can turn
  incognito off from it); the block-word popup follows the keyboard theme; the
  floating keyboard no longer publishes an empty touch region before its first
  layout (taps fell through to the app); tapping one-handed while floating now
  exits floating instead of doing nothing.
- **Under the hood** — first unit-test suite (calculator + tap-targeting
  geometry, runs in CI before every APK build); tap-targeting geometry is now
  derived from each language's real layout so it can never drift; release
  builds can be signed with a real key via gradle properties or environment
  variables (no keys in the repo).

## What's new in 2.7.2

- **Gboard-style toolbar** — a chevron on the left of the idle suggestion bar
  expands into a scrollable row of tools: one-handed, resize (height), floating,
  next language, text editing, clipboard, emoji, translate, share, theme, undo,
  redo, incognito, settings and hide. Tapping the chevron again collapses it.
  New actions this adds: quick **theme** cycling, keyboard **resize**, and
  **share** (via the system share sheet — nothing leaves the device unless you
  pick a target).
  - Not included, on purpose: GIF, stickers and scan-text. Those need the
    internet or the camera, which RimBoard deliberately never asks for.
- **Fixes from a code audit** — the character model no longer blocks the first
  taps while a dictionary is still loading; the inline calculator won't fire on
  a text-shortcut expansion or when its expression is truncated off-screen, and
  its chip can't be "blocked" like a word; Turkish taps fold the dotless ı
  correctly in tap targeting.

## What's new in 2.7.1

- **Vibration fixed on Xiaomi/HyperOS** — key-press haptics used a predefined
  vibration effect that many OEM devices silently ignore, so the keyboard felt
  dead even with haptics on. It now uses a predefined effect only when the
  device reports support and otherwise a reliable one-shot vibration, so every
  device with a motor buzzes (and still works when the system touch-feedback
  toggle is off).
- **Floating keyboard fixed** — toggling floating mode rebuilt the keyboard but
  skipped its setup, leaving it blank. It now re-applies layout, theme and
  settings on toggle.
- **Refreshed default theme** — the built-in light and dark themes get a
  cleaner, more modern skin (Gboard layout with a Telegram-style palette):
  quieter neutrals, one confident azure accent, smoother 11dp key corners and a
  touch more spacing. AMOLED, high-contrast, custom and dynamic themes are
  unchanged. All colours are original.

## What's new in 2.7.0

- **Adaptive tap targeting** — the technique behind Gboard's tap accuracy,
  implemented from scratch: when a touch lands near a key boundary, a spatial
  Gaussian around each key centre is combined with a per-language
  character-transition model (built from the bundled dictionary at load), and
  the most probable letter wins. Type "t" then tap between q and w — you get
  the w you meant. Touches comfortably inside a key are never diverted, and
  password fields are exempt. Works for all 22 languages.
- **Trigram predictions** — the keyboard now learns two-word contexts, not
  just word pairs: after "see you" it can predict "soon" even though "you"
  alone is usually followed by "are". Trigram evidence outranks bigram,
  everything stays on-device, and the data is included in backups.
- **Inline calculator** — type `12*34` and the suggestion bar offers `= 408`;
  tap to insert. Proper operator precedence, decimal commas, × and ÷.
  Dates (12/07/2026) and phone-style numbers are left alone unless you type
  an explicit trailing `=`.
- **Telegram-grade rendering** — the technique, not the code: the keyboard is
  one custom-drawn view with a zero-allocation draw loop (shifted key labels
  are now cached instead of re-created every frame), an eased spring-back on
  key release, a soft radial highlight that blooms from the exact touch point,
  and long-press popups and key previews that scale in with a subtle
  overshoot. All animations run on `postInvalidateOnAnimation` frame timing.
- Everything above is offline, permission-free and original code (MIT).

## What's new in 2.6.0

- **Proximity-aware autocorrect** — corrections now weigh where the keys sit on
  the layout, noisy-channel style: an adjacent-key slip (`helko` → `hello`,
  `noq` → `now`) is treated as far more likely than a distant one, so the word
  that gets auto-committed matches what you meant more often. Works for every
  layout (QWERTY/QWERTZ/AZERTY/Cyrillic/Greek/Turkish), and the strip can now
  offer up to two corrections instead of one.
- **Next-word predictions from the first word** — a small bundled starter model
  (English and Turkish) means the suggestion bar predicts your next word before
  it has learned anything from you; your own learned n-grams still take priority
  and take over as you type. Regenerate or extend it with
  `python3 tools/build_predictions.py`.
- **Emoji search** — the emoji panel has a 🔍 search with its own compact
  keypad and a fully offline keyword index (420 English + Turkish terms); type
  `cat`, `pizza`, or `heart` to find emoji. Accent-folding lets an ASCII query
  reach accented keywords (`kopek` → köpek). Extend it with
  `python3 tools/build_emoji_search.py`.
- **Polish** — the auto-commit suggestion now sits on a compact inset pill
  instead of a full-height bar, and the emoji-search keys have rounded,
  press-highlighted backgrounds.
- Also fixed: a type-mismatch compile error in `SuggestionEngine` that broke
  clean builds of 2.5.0.

## What's new in 2.5.0

- **Split keyboard** — off, landscape-only, or always; letter rows part around a centered gap while the bottom row stays full-width.
- **Custom font** — load any .ttf for key labels; plus a key-borders toggle (borderless mode), narrow key gaps, side and bottom padding sliders, and custom space-bar text.
- **Toolbar keys** — pick up to twelve actions for the idle suggestion bar: undo, redo, copy, paste, cut, select all, one-handed, incognito, edit panel, floating, numpad, hide keyboard.
- **Spacebar gestures** — horizontal swipe moves the cursor or switches language; vertical swipe can hide the keyboard; long-press cycles languages or opens the input-method picker.
- **Smarter behaviors** — .com/.net/.org popups on the period key in URL and email fields, per-app language memory, auto-return from symbols after space, optional return after emoji or clipboard picks, long-press symbols for the numpad, and customizable currency symbols.
- **Offensive-word filter** — profanity is never suggested or autocorrected to (English and Turkish lists, on by default, toggleable).
- **Import dictionary from file** — load any “word frequency” text file into the current language; useful for unsupported languages and personal corpora.
- **Precision controls** — touch-and-hold delay in milliseconds and key text size in percent, both as sliders.
- New settings are localized in English and Turkish; other interface languages temporarily fall back to English for the new items.

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

