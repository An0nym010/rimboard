#!/usr/bin/env python3
"""Which languages the shipped dictionary serves worst, and what would reach them.

    python tools/dictionary_gap.py           # every shipped language
    python tools/dictionary_gap.py fi hu     # just these

Needs build/corpus, which build_ngrams.py fetches. Prints, does not write.

Why this exists
---------------
Two features generate words the list does not hold, and each is gated to one
language: Compounds.writesClosed is German, Morphology.isAgglutinative is
Turkish. Both gates were set from tables denominated in *share of a language's
missing words*, which says nothing about how many words that is. A language
missing 0.5% of its tokens and one missing 4.5% do not deserve the same reading
of "39% of them are compounds".

This is the denominator. For each language: the share of corpus tokens the
dictionary cannot offer at all, how long those words are, and how each one
could be reached -- by stripping one counted ending, by splitting into two
dictionary words, or by neither.

Measured 2026-09-05
-------------------
::

           missing   len vs known   ending  compound  neither
    fi      4.52%     11.3 vs 5.8     16%      26%      58%
    hu      3.92%     10.3 vs 5.1     28%      16%      56%
    sk      2.50%      8.3 vs 4.6     14%       6%      81%
    tr      2.44%      9.8 vs 5.8      0% *    32%      68%
    uk      2.29%      8.7 vs 4.5     14%       5%      81%
    hr      1.20%      8.3 vs 4.5     16%       9%      75%
    pl      1.20%      9.4 vs 5.2     19%       7%      74%
    ru      1.18%      9.3 vs 4.7     12%       8%      80%
    de      1.11%     11.7 vs 4.9     14%      47%      40%   ships Compounds
    da      0.96%     10.5 vs 4.2     12%      38%      50%
    cs      0.92%      8.6 vs 4.5     16%       5%      79%
    no      0.89%     10.7 vs 4.1     15%      37%      48%
    el      0.79%      8.3 vs 4.7      0% *    11%      89%
    sv      0.76%     10.1 vs 4.2     15%      33%      52%
    es      0.72%      8.6 vs 4.5      9%      11%      80%
    fr      0.59%      7.8 vs 4.3      5%      10%      85%
    nl      0.57%     10.1 vs 4.4      8%      39%      54%
    en      0.55%      5.4 vs 4.1      2%       6%      93%
    ro      0.53%      7.7 vs 4.4      9%       7%      85%
    pt      0.52%      8.5 vs 4.4     11%      12%      77%
    it      0.44%      8.1 vs 4.6     12%      11%      77%
    id      0.43%      7.6 vs 5.4     14%      11%      75%

`*` Turkish and Greek ship no `assets/suffixes/*.txt`; Turkish's inventory is
hand-written in `Morphology.TR_SUFFIXES` and Greek has none. Their 0% is this
script's blind spot, not a fact about the languages -- see the note on
Morphology, which records why Greek was refused one.

What it says
------------
**Finnish and Hungarian are in a class of their own**, missing four to eight
times what most of the list misses, in words twice the length of the ones it
holds. Every other language is under 2.5%.

Multiplying the columns out gives what each feature could actually reach, as a
share of all tokens rather than of the missing ones:

- **Endings.** hu 1.10%, fi 0.74%, pl 0.23%, everything else below 0.2%.
  Hungarian is the largest, ahead of Finnish, and ships the biggest counted
  inventory of any language here at 91 endings. It is not in
  Morphology.isAgglutinative.
- **Compounds.** fi 1.18%, tr 0.78%, hu 0.65%, de 0.52%, da 0.36%, no 0.33%,
  sv 0.25%, nl 0.22%. Finnish is more than twice German, which ships it.

Those are printed by the script rather than only recorded here, because a
figure that exists in one place goes stale the day the assets are rebuilt.

And it vindicates the refusals in Compounds' own table, which sent Dutch and
Danish away on the reading that "their corpora are small enough that their
dictionaries already cover them". That is exactly right and now has a number:
nl misses 0.57% of its tokens and da 0.96%, so their 39% and 38% are large
shares of very little. The Germanic languages all cluster high on the compound
column -- de 47%, nl 39%, da 38%, no 37%, sv 33% -- because that is what
writing compounds closed does, and only German has enough missing words for it
to matter.

The `neither` column is the honest ceiling on both features together. It is
never below 40%, and for the Slavic languages it is around 80%: those need the
grammatical form rather than the word, which no lookup table of endings
describes.
"""
import collections
import io
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
import build_prose_fixture as B

MIN_PART = 4      # Compounds.MIN_PART
MIN_STEM_TAIL = 2  # an ending has to leave more than a fragment behind


def endings(lang):
    p = os.path.join(B.ASSETS, "suffixes", lang + ".txt")
    if not os.path.exists(p):
        return []
    out = [l.strip() for l in io.open(p, encoding="utf-8") if l.strip()]
    out.sort(key=len, reverse=True)
    return out


def survey(lang):
    src = os.path.join(B.CORPUS, "%s_sentences.tsv.bz2" % B.ISO3.get(lang, ""))
    if not os.path.exists(src):
        return None
    freq = B.dictionary(lang)
    sfx = endings(lang)
    unk = collections.Counter()
    tot = 0
    known_len = 0
    for text in B.sentences(src):
        for w in B.tokens(text):
            tot += 1
            if freq.get(w, 0):
                known_len += len(w)
            else:
                unk[w] += 1
    miss = sum(unk.values())
    if not tot or not miss:
        return None

    def by_ending(w):
        return any(len(w) > len(e) + MIN_STEM_TAIL and w.endswith(e)
                   and freq.get(w[:len(w) - len(e)], 0) for e in sfx)

    def by_compound(w):
        return any(freq.get(w[:i], 0) and freq.get(w[i:], 0)
                   for i in range(MIN_PART, len(w) - MIN_PART + 1))

    end_t = comp_t = neither_t = miss_len = 0
    for w, n in unk.items():
        miss_len += len(w) * n
        if by_ending(w):
            end_t += n
        elif by_compound(w):
            comp_t += n
        else:
            neither_t += n
    return dict(
        lang=lang, tokens=tot, missing=100.0 * miss / tot,
        miss_len=miss_len / miss, known_len=known_len / max(tot - miss, 1),
        ending=100.0 * end_t / miss, compound=100.0 * comp_t / miss,
        neither=100.0 * neither_t / miss,
    )


def main():
    langs = sys.argv[1:] or sorted(B.ISO3)
    rows = []
    for lang in langs:
        r = survey(lang)
        if not r:
            print("  %s: no corpus -- run tools/build_ngrams.py to fetch" % lang)
            continue
        rows.append(r)
        print("  %-3s %9d tokens  missing %5.2f%%  len %4.1f vs %4.1f   "
              "ending %2.0f%%  compound %2.0f%%  neither %2.0f%%"
              % (r["lang"], r["tokens"], r["missing"], r["miss_len"],
                 r["known_len"], r["ending"], r["compound"], r["neither"]))
    if not rows:
        return
    print("\n  share of *all* tokens each feature could reach:")
    for key in ("ending", "compound"):
        best = sorted(rows, key=lambda r: -(r["missing"] * r[key]))[:5]
        print("    %-9s %s" % (key, "  ".join(
            "%s %.2f%%" % (r["lang"], r["missing"] * r[key] / 100.0) for r in best)))


if __name__ == "__main__":
    main()
