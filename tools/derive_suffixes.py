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

# ...except for the two languages whose corpora are far too small for a flat
# count to mean the same thing. 500 occurrences is 2.3 per million of Turkish,
# where the number was measured, and 106.8 per million of Ukrainian -- which
# leaves 910 stems in a 200,000-word list and nothing for this to count endings
# on. Dictionary.stemFloorFor holds the same rule and the same set; the two
# must not disagree about what a stem is. The note there records why it is
# these two and not every language.
TURKISH_TOKENS = 215064959.0
SCALED_STEM_LANGS = {"sk", "uk"}


def stem_floor(lang, words):
    if lang not in SCALED_STEM_LANGS:
        return STEM_MIN_FREQ
    return max(2, round(STEM_MIN_FREQ * sum(words.values()) / TURKISH_TOKENS))

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
                      "sk": 2, "da": 2, "uk": 2}

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
# Romanian, Spanish and Polish were the three the old all-or-nothing setting had
# rejected, and re-swept with the cap only one of them takes it. Polish takes
# twelve:
#
#     added   prevents   wrongly accepted   held-out words accepted
#         0     3.8         0.4%                57
#         4     4.7         0.5%                73
#        12     5.3         0.7%                84      <- here
#        18     5.3         0.7%                84
#        20     5.3         1.4%                87
#
# Twelve is where the value runs out rather than where the ceiling arrives, and
# that is a better reason to stop than the one Croatian and Hungarian had:
# fourteen through eighteen accept not one further word in the sample, and
# twenty doubles the cost for no gain whatever -- its two endings are
# -ki and -na, generic enough to take a typo apart as readily as a word. So
# Polish stops at half the ceiling with the whole gain in hand, rather than
# trading margin for the last tenth. Destruction 27.8% -> 26.3%.
#
# Romanian gains nothing that can be bought at any cap. Acceptance climbs
# steadily -- 62 held-out words at zero, 72 at six -- while destruction does not
# move by a tenth of a point (33.2% at every cap from zero to six) and the false
# accepts run 0.7% -> 1.2%. That is the Slovak result again, this time in a
# language that already ships an inventory: what the two-letter endings rescue
# is not what was being rewritten, and acceptance is a proxy rather than the
# thing. Its first real gain is at eight, by which point it is over the ceiling.
#
# Spanish has no room to buy anything with. It already runs at 1.4%, the highest
# false-accept rate of any shipped inventory, so a tenth of a point is all the
# headroom there is: two endings buy 0.2 points at no measured cost, four buy
# 0.5 for 1.7% and are over. 0.2 points is one word in six hundred, which is not
# a measurement.
# English was swept too and takes none, which is worth writing down because the
# shape of its list argues loudly for one. Its two-letter endings begin -ed
# (2,543 stems), -er (1,933), -ly (1,619), -es (1,249) -- the four productive
# endings of the language, and then a cliff into name fragments: -ie, -ka, -ya,
# -na. A cap of exactly four looks made for it.
#
#     added   prevents   wrongly accepted   held-out words accepted
#         0     0.0         0.8%                37
#         2     0.7         1.4%                47
#         4     1.5         1.5%                53
#         6     1.7         1.7%                58
#
# Four is the first row to clear the one-point bar, and it lands on 1.5% --
# which is the ceiling itself, not a place under it. Across five seeds it reads
# 1.5 1.5 0.8 1.3 1.7, so one draw is already over. Croatian stopped at sixteen
# rather than twenty and Hungarian at sixteen rather than twenty for exactly
# this reason; English has no better claim to the edge of the limit than they
# had. Two and three are affordable and buy 0.7 and 0.8, under the bar.
#
# So the answer is no, and it is no because of where the cost lands rather than
# because English lacks the endings.
SHORT_CAP = {"hr": 16, "hu": 16, "fi": 4, "pl": 12}

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
#     hu 8.0   ro 5.3   fr 4.0   nl 3.5   sk 3.0   da 1.8
#     pl 6.2   fi 5.0   pt 4.0   de 3.3   uk 2.3   ru 1.8
#     es 5.5   it 4.2   cs 3.8   sv 3.2   no 1.5   en 1.5
#     hr 3.8   id 3.0
#
# Measured with the prefix inventories present in both arms, which is what
# ships. They raised these rather than lowering them, because the two walks
# compose: a prefix is stripped and what is left goes to the ending walk, so
# the endings are worth more once there are prefixes. See derive_prefixes.py.
#
# Slovak read 2.0/0.0 here and gained nothing, and Ukrainian derived nothing at
# all. Both were being measured against a stem floor their corpora cannot
# reach -- see STEM_MIN_FREQ. Scaled to their own corpora they read sk 3.0/0.0
# and uk 2.3/0.2, and Slovak's out-of-vocabulary destruction goes 28.7% to
# 25.7%.
#
# That ceiling is the one already in the product rather than a number chosen
# here: the hand-written Turkish inventory, which has shipped from the start,
# runs at 3.8%. Anything well inside that is a trade this keyboard has already
# made once and measured.
#
#     tr 31.3/0.5   fi 12.0/1.2   it  8.3/0.8   pt  6.2/0.7   sv  5.8/1.2
#     hu 17.5/1.0   es 11.3/1.4   nl  7.5/0.8   en  6.2/0.8   ru  4.5/0.2
#     pl 14.0/0.7   hr 10.3/1.2   fr  7.2/0.3   id  6.0/0.8   da  3.8/0.9
#     cs 12.7/0.6   ro 10.3/0.7   de  7.0/0.9   no  3.3/0.3   sk  3.0/0.0
#                                                                  uk  2.3/0.2
#
# Both tables above are re-measured whenever one of them changes, because they
# went stale once: they held the three-character figures for cs, de, nl, sv, da,
# no, ru and id long after those languages moved to a two-character floor, which
# is a table describing a configuration that no longer shipped. They are what
# `SuffixInventoryTest` prints, so a disagreement is a run away from settling.
#
# Greek derives nothing at all: its endings are one and two characters, which
# MIN_SUFFIX excludes for good reason, and it is not on the Slavic-and-Germanic
# list below that would admit two.
#
# Ukrainian read the same way and for a different reason. It is Slavic, so it
# takes two characters like Czech, Russian and Slovak -- and it derives nothing
# even at two until its stem floor is scaled, because 910 stems cannot support
# an ending on 150 of them. Swept both ways once it could derive at all:
# three characters gives 10 endings and prevents 0.2 points, under the bar this
# file holds every inventory to; two gives 42 and prevents 2.3 for 0.2% wrongly
# accepted. The two-character floor is not a concession here, it is the
# language.
#
# Turkish is absent because it already has a hand-written inventory checked
# against vowel harmony, and that one is better -- 46% for 3.8% against 31% for
# 0.5%. Harmony is doing real work there and nothing counted here knows it.
ENABLED = {
    "cs", "de", "es", "fi", "fr", "hu", "id", "it", "nl", "pl", "pt", "ro", "sv",
    "ru", "no", "da", "hr", "en", "sk", "uk",
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


def derive(lang, words, min_suffix):
    """Endings, and how many distinct stems each was found on."""
    floor = stem_floor(lang, words)
    stems = {w for w, f in words.items() if f >= floor and len(w) >= MIN_STEM}
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
        found = derive(lang, words, MIN_SUFFIX_BY_LANG.get(lang, MIN_SUFFIX))
        keep = chosen(found)
        cap = SHORT_CAP.get(lang)
        if cap:
            # The best few two-letter endings, in front of the longer ones the
            # language already had. Order in the file is presentation only --
            # both the engine and the test sort by length before walking -- so
            # this puts the commonest first for anyone reading it.
            short = [s for s in chosen(derive(lang, words, 2)) if len(s) == 2][:cap]
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
