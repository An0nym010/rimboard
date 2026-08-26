"""Derive a suffix inventory for each language from the dictionaries it ships.

Turkish has had morphology since the beginning: strip a recognised suffix, and
if what remains is a known stem the word is a word. That is worth a great deal
-- 46% of the Turkish words beyond the shipped list are accepted that way, and
an accepted word is never autocorrected, so it cannot be silently rewritten.
Every other inflecting language accepts 0%, because `Morphology.isAgglutinative`
is `lang == "tr"` and nothing else has an inventory.

Writing one by hand needs a speaker of the language. Counting one does not:

  * take every word in the list;
  * split it at each position where the front half is itself a frequent word;
  * count what the back half was.

The endings a language really uses rise to the top on their own. Run against
Turkish, whose inventory was written by hand, this reproduces it: -lar, -ler,
-dan, -den, -sin all appear near the front. That is the check that says the
method works, because there the answer is already known.

Everything here reads only files already in the repository. There is no
network, and the output is a few kilobytes per language.

    python tools/derive_suffixes.py            # write assets/suffixes/*.txt
    python tools/derive_suffixes.py --report   # print what it found, write nothing
"""
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
DICTS = os.path.join(ASSETS, "dictionaries")
OUT = os.path.join(ASSETS, "suffixes")

# A stem has to be a real word rather than a fragment that happens to be listed,
# so it must clear a frequency floor. Dictionary.STEM_MIN_FREQ is 500 and this
# is deliberately the same number: the runtime asks the same question of the
# stem, and two different floors would be two different opinions about what
# counts as a word.
STEM_MIN_FREQ = 500

# Shorter than this and a "stem" is a syllable, not a word.
MIN_STEM = 3

# Shorter than this and an "ending" is a letter. One- and two-letter endings are
# real morphology in most of these languages, and they are also what makes a
# suffix stripper accept anything at all: measured, admitting them roughly
# quadruples the rate at which a mistyped word is waved through as correct
# (Turkish 0.3% -> 2.8% -> 10.2% at three, two and one). Turkish can afford them
# because its hand-written inventory is checked against vowel harmony, which
# nothing derived here knows.
MIN_SUFFIX = 3

# Longer than this and a "suffix" is really a second word; the compound
# splitter is the right tool for those and German already has one.
MAX_SUFFIX = 6

# How many endings to keep. Past this the tail is mostly truncated words.
KEEP = 120

# An ending has to be pulled off this many distinct stems before it is believed.
MIN_STEMS = 150

# Languages whose derived inventory earns its place, and only those.
#
# An inventory buys coverage -- words outside the shipped list that are
# recognised anyway, and so are never silently rewritten -- and pays for it by
# occasionally waving a mistyped word through as correct. Both were measured
# per language in SuffixInventoryTest; a language ships only if it gains at
# least 5% of the held-out words for no more than 1% false accepts:
#
#     hu 13.5/0.4   es 11.3/1.4   cs  3.0/0.2   sv  2.7/0.0
#     ro 10.3/0.7   it  8.3/0.8   hr  4.5/0.4   de  2.2/0.5
#     fi 10.0/0.9   fr  7.2/0.3   nl  3.8/0.3   da  2.0/0.5
#     pl  9.5/0.4   en  6.2/0.8   id  2.8/0.7   ru  1.3/0.0
#     tr 31.3/0.5   pt  6.2/0.7   no  1.8/0.2   sk  0.3/0.0
#
# Spanish is the near miss and is left out on the false-accept side rather than
# the gain. Greek and Ukrainian derive nothing at all: their endings are one and
# two characters, which MIN_SUFFIX excludes for good reason.
#
# Turkish is absent because it already has a hand-written inventory checked
# against vowel harmony, and that one is better -- 46% for 3.8% against 31% for
# 0.5%. Harmony is doing real work there and nothing counted here knows it.
ENABLED = {"fi", "fr", "hu", "it", "pl", "pt", "ro"}

# English clears the numbers and is still left out, which is the one judgement
# here that is not arithmetic. Its list comes back with -man, -son, -ton, -ley
# and -ville alongside -ing and -ness, because English builds names out of whole
# words -- Johnson, Hamilton, Nashville -- and the counting cannot tell a name
# formative from a suffix. So "that" + "-ville" is vouched for, which is not a
# permissive rule but a wrong one. Its gain was also the weakest of the eight
# (6.2% for 0.8%, against a Turkish inventory that ships at 46% for 3.8%), so
# there is little being given up. The other seven are inflectional endings and
# have no equivalent problem.


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


def derive(words):
    """Endings, and how many distinct stems each was found on."""
    stems = {w for w, f in words.items() if f >= STEM_MIN_FREQ and len(w) >= MIN_STEM}
    found = Counter()
    for w in words:
        n = len(w)
        if n < MIN_STEM + 1:
            continue
        for i in range(MIN_STEM, n):
            if n - i > MAX_SUFFIX:
                continue
            if n - i < MIN_SUFFIX:
                continue
            if w[:i] in stems:
                found[w[i:]] += 1
    return found


def chosen(found):
    return [s for s, c in found.most_common(KEEP) if c >= MIN_STEMS]


def main():
    report = "--report" in sys.argv
    langs = sorted(f[:-4] for f in os.listdir(DICTS) if f.endswith(".txt"))
    if not report:
        langs = [l for l in langs if l in ENABLED]
    if not report:
        os.makedirs(OUT, exist_ok=True)
    for lang in langs:
        words = load(lang)
        found = derive(words)
        keep = chosen(found)
        if report:
            top = ", ".join("-%s(%d)" % (s, found[s]) for s in keep[:14])
            print("%-4s %3d endings   %s" % (lang, len(keep), top))
            continue
        with open(os.path.join(OUT, lang + ".txt"), "w", encoding="utf-8",
                  newline="\n") as fh:
            for s in keep:
                fh.write(s + "\n")
    if not report:
        print("wrote %d files to %s" % (len(langs), OUT))


if __name__ == "__main__":
    main()
