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

# ...except where the language's endings really are two characters long, which
# is a fact about the language rather than a knob. Slavic and Germanic
# inflection is short; Romance and Uralic endings are longer and admitting
# two-character ones there costs far more than it gains. Measured both ways for
# every language, at the same 1.5% ceiling:
#
#            two chars      three chars              two chars     three chars
#     cs    12.7 / 0.6      3.0 / 0.2        hu     24.8 / 3.9    13.5 / 0.4
#     nl     7.5 / 0.8      3.8 / 0.3        es     18.3 / 4.5    11.3 / 1.4
#     de     7.0 / 0.9      2.2 / 0.5        ro     18.3 / 3.6    10.3 / 0.7
#     id     6.0 / 0.8      2.8 / 0.7        fi     18.2 / 2.3    10.0 / 0.9
#     sv     5.8 / 1.2      2.7 / 0.0        pl     17.8 / 1.8     9.5 / 0.4
#
# The left column takes two; the right column would pay several times the cost
# for its extra gain and keeps three.
MIN_SUFFIX_BY_LANG = {"cs": 2, "de": 2, "id": 2, "nl": 2, "sv": 2, "ru": 2, "no": 2,
                      "sk": 2, "da": 2}

# How many two-letter endings a language may admit on top of its longer ones.
#
# The setting above is all-or-nothing: a language either takes every two-letter
# ending its own words support, or none. That is the wrong shape for Croatian,
# which had the worst destruction rate of any shipped language -- 38.3% of
# correct words outside the list were being rewritten -- and could not be fixed
# either way round. At three characters it prevents 2.2 points of that. At two
# it prevents 6.3, which is the largest gain measured for any language, and
# costs 3.2% of damaged words wrongly waved through: more than twice the 1.5%
# ceiling SuffixInventoryTest holds every inventory to.
#
# The cost is not spread evenly across those endings. Croatian's two-letter
# endings are its case and verb inflections and they fall off steeply -- -om
# appears in 2,244 words, the sixteenth in a few hundred, the seventy-fourth in
# barely more than the floor. Admitting the head of that list and not the tail:
#
#     added   prevents   wrongly accepted
#         0     2.2         0.4%      (what shipped)
#         8     3.0         1.1%
#        16     3.8         1.2%
#        20     4.0         1.4%
#        26     4.7         2.1%      over the ceiling
#
# Sixteen: 1.6 points better than shipping, at a cost equal to the highest any
# inventory already carries (Swedish, 1.2%), with room left under the ceiling
# for a dictionary rebuild to move things. Twenty buys 0.2 more points for 0.2
# more cost and leaves almost no margin.
#
# Hungarian and Finnish were rejected at two characters for the same reason and
# are fixed the same way. Swept identically: Hungarian goes 5.8 -> 7.5 points at
# sixteen (1.0% cost; twenty reaches 8.2 but sits exactly on the ceiling), and
# Finnish 3.7 -> 5.0 at four (1.2%; eight is already over at 1.9%). Their
# destruction rates go 30.5% -> 28.8% and 29.7% -> 28.3%.
#
# Romanian, Spanish and Polish were rejected on the old all-or-nothing basis and
# have not been re-swept with this cap. They are the next candidates and nobody
# has measured them.
SHORT_CAP = {"hr": 16, "hu": 16, "fi": 4}

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
# per language in SuffixInventoryTest, and judged on the outcome rather than on
# a proxy for it: a language ships if its inventory takes at least one point off
# the rate at which correct words outside the list are rewritten, for no more
# than 1.5% of damaged words wrongly waved through.
#
# Points of destruction prevented, measured with the inventory and without:
#
#     hu 5.8   ro 4.0   fi 3.7   de 3.2   sv 2.5   da 1.8   sk 0.2
#     es 5.5   cs 3.8   fr 3.5   nl 2.8   hr 2.2   no 1.5
#     it 4.2   pl 3.8   pt 3.3   id 2.2   ru 1.5
#
# Slovak is the only one that derives an inventory and gains nothing from it.
#
# That ceiling is the one already in the product rather than a number chosen
# here: the hand-written Turkish inventory, which has shipped from the start,
# runs at 3.8%. Anything well inside that is a trade this keyboard has already
# made once and measured.
#
#     hu 13.5/0.4   es 11.3/1.4   cs  3.0/0.2   sv  2.7/0.0
#     ro 10.3/0.7   it  8.3/0.8   hr  4.5/0.4   de  2.2/0.5
#     fi 10.0/0.9   fr  7.2/0.3   nl  3.8/0.3   da  2.0/0.5
#     pl  9.5/0.4   en  6.2/0.8   id  2.8/0.7   ru  1.3/0.0
#     tr 31.3/0.5   pt  6.2/0.7   no  1.8/0.2   sk  0.3/0.0
#
# Greek and Ukrainian derive nothing at all: their endings are one and two
# characters, which MIN_SUFFIX excludes for good reason.
#
# Turkish is absent because it already has a hand-written inventory checked
# against vowel harmony, and that one is better -- 46% for 3.8% against 31% for
# 0.5%. Harmony is doing real work there and nothing counted here knows it.
ENABLED = {
    "cs", "de", "es", "fi", "fr", "hu", "id", "it", "nl", "pl", "pt", "ro", "sv",
    "ru", "no", "da", "hr", "en",
}

# English was held out for a while on a judgement rather than a measurement: its
# list comes back with -man, -son, -ton, -ley and -ville beside -ing and -ness,
# because English builds names out of whole words -- Johnson, Hamilton,
# Nashville -- and counting cannot tell a name formative from a suffix. "that"
# plus "-ville" being vouched for looked like a wrong rule rather than a lenient
# one.
#
# The measurement overruled it, which is the right way round. Those endings do
# productive work -- -man alone rescues twenty held-out words, and they are
# policeman and spokesman rather than thatman. English prevents 1.5 points of
# destruction for 0.8% wrongly accepted, which is a lower false-accept rate than
# fi, de, da, sv or es, all of which ship. And it is the one language with a
# repair benchmark of its own: AutocorrectAccuracyTest reports 96% of typos
# fixed with the inventory and without it, to the word.
#
# The feared harm is exactly what the false-accept figure prices, and it is
# priced lower here than in half the languages already shipping.


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


def derive(words, min_suffix):
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
            if n - i < min_suffix:
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
        found = derive(words, MIN_SUFFIX_BY_LANG.get(lang, MIN_SUFFIX))
        keep = chosen(found)
        cap = SHORT_CAP.get(lang)
        if cap:
            # The best few two-letter endings, in front of the longer ones the
            # language already had. Order in the file is presentation only --
            # both the engine and the test sort by length before walking -- so
            # this puts the commonest first for anyone reading it.
            short = [s for s in chosen(derive(words, 2)) if len(s) == 2][:cap]
            keep = short + [s for s in keep if s not in short]
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
