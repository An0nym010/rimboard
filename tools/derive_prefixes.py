"""Derive a prefix inventory for each language from the dictionaries it ships.

`derive_suffixes.py` counts the endings a language builds words with, and
eighteen languages ship one. It only ever looks at the end of a word, because
Turkish -- the language morphology was built for -- is purely suffixing. Most
of the languages that inherited the walk are not.

What that costs is visible in `OutOfVocabularyTest`'s own output. `nl
verschuldigde` is rewritten to `verschuldigd`, and `de angeschlichen` to
`geschlichen`: correct words, destroyed by having a prefix taken off them,
where the same word with an ending stripped would have been vouched for and
left alone. Three of the five real English words destroyed on a phone lost a
derivational prefix.

The method is the same one, at the other end of the word:

  * take every word in the list;
  * split it at each position where the *back* half is itself a frequent word;
  * count what the front half was.

What comes out is ordinary grammar -- Czech `ne- po- vy- za-`, German
`ver- be- an- ge- aus-`, Russian `по- за- про- вы-`, Dutch `ver- be- op- uit-`,
Hungarian `meg- fel- vissza-`, Indonesian `di- ber- ter- ke-`.

    python tools/derive_prefixes.py            # write assets/prefixes/*.txt
    python tools/derive_prefixes.py --report   # print what it found, write nothing
"""
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
DICTS = os.path.join(ASSETS, "dictionaries")
OUT = os.path.join(ASSETS, "prefixes")

# The same floor the suffix derivation uses and for the same reason: the
# runtime asks this of the stem, and two different numbers would be two
# different opinions about what counts as a word.
STEM_MIN_FREQ = 500

# ...scaled to the corpus for the two languages whose lists are far too small
# for a flat count to mean the same thing. Identical to the rule in
# derive_suffixes.py and Dictionary.stemFloorFor; all three must agree about
# what a stem is, and Ukrainian has 910 stems at the flat floor.
TURKISH_TOKENS = 215064959.0
SCALED_STEM_LANGS = {"sk", "uk"}


def stem_floor(lang, words):
    if lang not in SCALED_STEM_LANGS:
        return STEM_MIN_FREQ
    return max(2, round(STEM_MIN_FREQ * sum(words.values()) / TURKISH_TOKENS))

# What is left after the prefix comes off has to be a word, not a syllable.
MIN_STEM = 3

# Shorter than this and a "prefix" is a letter.
#
# Prefixes are shorter than endings -- `ne-`, `po-`, `be-`, `по-` are the whole
# of the grammar in several of these languages -- but two characters is where
# the counting stops being able to tell a prefix from the first syllable of a
# longer word. Swept per language in PrefixInventoryTest against the same
# ceiling the suffix inventories are held to.
MIN_PREFIX = 3

# ...except where the language's prefixes really are two characters long, which
# is a fact about the language rather than a knob. See the sweep in
# PrefixInventoryTest.
MIN_PREFIX_BY_LANG = {"fr": 2, "pl": 2, "ro": 2, "ru": 2, "sk": 2, "uk": 2}

# Longer than this and a "prefix" is really a first word; that is a compound,
# and Compounds is the right tool for it.
MAX_PREFIX = 6

# How many prefixes to keep. Past this the tail is mostly first syllables.
KEEP = 120

# A prefix has to be found in front of this many distinct stems before it is
# believed. The same floor the endings are held to.
MIN_STEMS = 150


def load(lang):
    words = {}
    path = os.path.join(DICTS, lang + ".txt")
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            parts = line.split(" ")
            if len(parts) < 2:
                continue
            try:
                words[parts[0]] = int(parts[1])
            except ValueError:
                pass
    return words


def derive(lang, words, min_prefix):
    """Prefixes, and how many distinct stems each was found in front of."""
    floor = stem_floor(lang, words)
    stems = {w for w, f in words.items() if f >= floor and len(w) >= MIN_STEM}
    found = Counter()
    for w in words:
        n = len(w)
        for i in range(min_prefix, min(MAX_PREFIX, n - MIN_STEM) + 1):
            if w[i:] in stems:
                found[w[:i]] += 1
    return found


def chosen(found):
    return [p for p, c in found.most_common(KEEP) if c >= MIN_STEMS]


# Languages whose derived prefix inventory earns its place, and only those.
#
# Held to what the endings are held to, and measured the same way in
# PrefixInventoryTest: at least one point off the rate at which correct words
# outside the list are rewritten, for no more than 1.5% of damaged words wrongly
# waved through -- and the cost is counted for the *whole* walk, endings and
# prefixes together, because that is what the user is exposed to.
#
# The table this was chosen from is in PrefixInventoryTest.
# English is absent, and not because the counting produced junk -- though it
# did. Its counted list holds `the-`, `mar-`, `man-`, `car-` and `your-`, for
# the reason the suffix tool's own note gives: English builds names out of whole
# words. The obvious answer is a curated list of the prefixes that are really
# productive, and that was measured rather than assumed:
#
#     prefixes                                        prevents   cost
#     none                                                0.0    0.8%
#     un                                                  0.2    0.8%
#     un mis non                                          0.2    0.8%
#     un mis non over under pre anti semi inter counter   0.3    1.2%
#     ...plus dis out sub                                 0.5    1.2%
#     the counted list                                    0.5    1.7%
#
# The bar is one point. The best curated list reaches half of it, so the fault
# was never the counting: English destruction is not prefix-shaped. The word
# that prompted the attempt -- "unhelpfully", which a phone silently rewrote to
# "unhelpful" -- is not rescued by any of these anyway, because it needs
# "helpfully" to be a frequent stem and it is not.
#
# Slovak, Ukrainian and Greek were the three that had never been measured here
# at all. The first two were starved by the stem floor -- Ukrainian had 833
# stems to count prefixes in front of -- and derive ordinary Slavic verbal
# prefixes once it is scaled to their corpus. Both take two characters like
# their siblings Polish and Russian, and the sweep is why rather than the
# family resemblance:
#
#              min 3        min 2
#     sk    0.5 / 0.4%   1.7 / 0.8%
#     uk    1.0 / 0.8%   2.7 / 1.2%
#     el    0.5 / 0.0%   0.8 / 0.0%   <- refused
#
# Slovak does not reach the bar at three and clears it comfortably at two.
# Ukrainian's 2.7 ties Russian for the best measured.
#
# **Greek is refused and is the one to revisit first.** It was starved by
# nothing -- 22,238 stems at the flat floor -- and its counted list is real
# Greek morphology (xana-, ana-, apo-, para-, dia-, pro-, kata-, epi-, ypo-,
# syn-). It costs *nothing*: 0.0% wrongly accepted, the only inventory measured
# at zero, and a better ratio than sv (1.2/1.2), de (1.3/1.2) or hu (1.8/1.2).
# It simply does not reach a bar that is written as a minimum gain rather than
# a ratio. Do not move the bar to admit it; that is the same move this project
# has refused elsewhere. Greek has no ending inventory either -- its stems are
# never words on their own, so the counting method cannot find one -- so this
# is the only morphology it could have, and it is the first thing to re-measure
# if the dictionaries are rebuilt.
ENABLED = {"de", "fr", "hu", "id", "nl", "pl", "pt", "ro", "ru", "sv", "sk", "uk"}


def main():
    report = "--report" in sys.argv
    langs = sorted(f[:-4] for f in os.listdir(DICTS) if f.endswith(".txt"))
    if not report:
        langs = [l for l in langs if l in ENABLED]
        os.makedirs(OUT, exist_ok=True)
    for lang in langs:
        words = load(lang)
        found = derive(lang, words, MIN_PREFIX_BY_LANG.get(lang, MIN_PREFIX))
        keep = chosen(found)
        if report:
            top = ", ".join("%s-(%d)" % (p, found[p]) for p in keep[:14])
            print("%-4s %3d prefixes   %s" % (lang, len(keep), top))
            continue
        with open(os.path.join(OUT, lang + ".txt"), "w", encoding="utf-8",
                  newline="\n") as fh:
            for p in keep:
                fh.write(p + "\n")
    if not report:
        print("wrote %d files to %s" % (len(langs), OUT))


if __name__ == "__main__":
    main()
