#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Words that a comma almost always comes before, counted from the corpus.

Yandex ships a punctuation half to its Neurocorrector
(`kb_preference_neurocorrect_punct`) and RimBoard has nothing. The note that
stood against it for a week said missing commas need clause structure that no
shipped asset carries -- true of the assets, and not of the inputs. The Tatoeba
corpus the prediction models are built from is cached in `build/corpus` with
its punctuation intact; `build_ngrams.py` strips it on the way in, and nothing
stops a second tool counting it.

What this counts is deliberately not "where do commas go". It is the one
question a keyboard can act on: **for each word, how often is the character
before it a comma?** A word that answers 95% is a word whose comma the writer
can be reminded of at the moment they type it, with no parse and no model.

## The population, which is the whole argument

Only **mid-sentence** occurrences are counted. A sentence cannot open with a
comma before its first word, so the rule can never fire there, and counting
those occurrences would understate every share by however often the word starts
a sentence -- which for a subordinating conjunction is a lot. Measuring over a
population the feature never meets is the mistake this project has made twice
and written down both times.

The share that comes out is therefore **precision on the occurrences the rule
would actually see**, and `--eval` checks that on held-out sentences rather
than trusting it.

## What it found (2026-09-08)

    lang  commas/sent   words >=50%   share of comma sites they cover
    cs        0.26          12                 56.6%
    pl        0.24          14                 44.4%
    ru        0.33          23                 44.0%
    de        0.42          16                 27.2%
    en        0.13           4                  9.5%
    tr        0.11           0                  0.0%

The heads are the grammar rather than the corpus: ru `которые` 95%, `чтобы`
95%, `но` 89%; de `dass` 97%, `sondern` 98%, `desto` 94%; pl `że` 97%; cs `že`
99%, `aby` 95%. **English has four such words and Turkish none.** This is a
feature for the languages whose comma placement is rule-shaped, and building it
for twenty-two would be building it for four and carrying eighteen.

The model is 12-23 words a language -- a few hundred bytes, so nothing here
goes near `ASSET_CEILING_MB`, which is the tightest budget in the project.

Usage:
    python tools/build_commas.py --report            # the table above
    python tools/build_commas.py --eval              # held-out precision/recall
    python tools/build_commas.py --write             # writes assets/commas/*.txt
"""

import argparse
import bz2
import collections
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "build", "corpus")

# Same map as build_ngrams.py; Tatoeba is ISO 639-3 and the app is 639-1.
ISO3 = {
    "en": "eng", "tr": "tur", "de": "deu", "es": "spa", "fr": "fra", "it": "ita",
    "pt": "por", "ru": "rus", "nl": "nld", "pl": "pol", "sv": "swe", "id": "ind",
    "ro": "ron", "cs": "ces", "da": "dan", "no": "nob", "fi": "fin", "hu": "hun",
    "uk": "ukr", "el": "ell", "hr": "hrv", "sk": "slk",
}

WORD = re.compile(r"[^\W\d_]+", re.UNICODE)

# What ends a sentence, so the token after one is an opener and is not counted.
ENDERS = ".!?…;"

# The share a word needs before its comma is worth inserting.
#
# **0.90, and it is the lowest value at which every shipping language is at or
# above 95% precision on held-out text.** Swept 2026-09-08 -- nine tenths of
# each corpus to build the list, every tenth sentence held out, precision and
# recall measured on sentences the list has never seen:
#
#     share   ru prec/rec    cs prec/rec    pl prec/rec    de prec/rec
#     0.80    85.7 / 42.7    92.9 / 61.2    94.6 / 36.3    91.9 / 30.0
#     0.85    96.3 / 16.2    96.2 / 49.9    94.9 / 35.2    92.2 / 28.8
#     0.90    96.3 / 16.2    97.0 / 47.3    95.4 / 32.3    96.7 / 17.5
#     0.95    97.8 / 11.8    98.2 / 37.1    95.6 / 29.0    97.3 / 15.8
#
# Two things in that table are worth more than the number chosen from it.
#
# **The curves are not the same shape.** Russian gains ten points of precision
# and loses twenty-six of recall in the single step from 0.80 to 0.85, and
# German does the same thing between 0.85 and 0.90: one high-volume word in
# each is carrying most of the recall and nearly all of the errors. Czech and
# Polish have no such cliff -- they are flat and high everywhere. A per-language
# threshold would therefore buy Czech real recall, and it is still not taken:
# 0.80 leaves Czech at 92.9%, under the bar, so the split would be four
# constants to move one language from good to slightly better. One threshold is
# right here.
#
# **0.95 is a bad trade.** One to two points of precision for five to ten of
# recall, in every language at once.
MIN_SHARE = 0.90

# How many times a word must appear mid-sentence before its share means
# anything. A word seen forty times at 100% is four hundred times weaker
# evidence than one seen four thousand times at 95%.
MIN_SEEN = 200

# How far a token may be over-represented in the corpus before it is treated as
# an artifact of that corpus rather than a fact about the language.
#
# The same detector and the same number as `build_ngrams.py`, imported here by
# hand rather than by import because it was needed for the same reason and the
# reason is worth restating: Tatoeba is a language-teaching corpus and its
# sentences are overwhelmingly about a character called Tom.
#
# **It caught this list before it shipped.** Ukrainian and Czech decline names,
# and direct address takes a comma, so `Томе` and `Tome` -- the vocative of Tom
# -- sat at 90%+ in both and were about to be shipped as comma words:
#
#     Привіт, Томе.        Ahoj, Tome.        Tome, neopouštěj mě!
#
# The rule was right about the corpus and absurd about the language. Every one
# of those sentences is distinct, so nothing about repetition or a flat count
# would have found it; what finds it is asking whether the word is that common
# in the language, which is what the shipped frequency list answers.
OUTLIER = 8.0

# How often the rule must fire before a language is worth shipping a list for.
#
# Once per hundred sentences, and the statistic matters more than the number.
# This was a *coverage* bar -- the share of that language's commas the list
# accounts for -- and coverage is the wrong question, because it says nothing
# about how often anybody meets the feature. Held out, English fires 74 times
# in 203,000 sentences and German 5,797 times in 78,000: **one firing per 2,749
# sentences against one per thirteen**, a factor of two hundred, where the
# coverage figures were 9.5% and 17.4% and looked like the same kind of number.
#
# Coverage also punished the wrong language. Dropping the corpus artifact `daß`
# took German under a 15% coverage bar, and German has the densest commas of
# anything measured (0.42 a sentence) and fires more often than any language
# but Russian. A bar that excludes the language the feature works best in is
# measuring something other than the feature.
MIN_RATE = 0.01

# The held-out precision a language must reach before it ships a list.
#
# **This is the gate, and the firing rate above is only a second opinion.**
# A share counted from the corpus is precision on the sentences it was counted
# from, which is the one number a model must never be trusted to give about
# itself -- so `--write` builds a model from nine tenths, runs it over the
# tenth it has never seen, and ships the language only if that comes back at
# 95% or better. The asset it then writes is built from everything, which is
# the ordinary arrangement: judge on held-out, train on all.
#
# Measured 2026-09-08, and it splits twelve candidate languages cleanly:
#
#     sk 100.0   cs 96.9   de 96.5   ru 96.3   uk 95.5   hu 95.4   pl 95.4
#     fi  91.5   pt 91.1   da 90.9   ro 84.4   no (nothing above the share bar)
#
# Nothing sits between 95.4 and 91.5, so the bar is not cutting a continuum in
# an arbitrary place; it is separating the languages whose commas are
# rule-shaped from the ones where this is a guess. The four rejected are also
# the four with the lowest recall (7-11%), so they were marginal twice over.
MIN_PRECISION = 0.95


def corpus(code3):
    path = os.path.join(CACHE, code3 + "_sentences.tsv.bz2")
    if not os.path.exists(path):
        return None
    return path


def sentences(path):
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[2]:
                yield parts[2]


def count(text, after, total, sites):
    """Adds one sentence to the tallies.

    A token is skipped when it opens the sentence or follows an ender, since
    the rule can never fire there. `sites` counts every mid-sentence comma
    that sits before a word, which is the denominator recall is measured
    against.
    """
    opener = True
    for m in WORD.finditer(text):
        w = m.group(0).lower()
        j = m.start() - 1
        while j >= 0 and text[j] in " \t":
            j -= 1
        prev = text[j] if j >= 0 else ""
        if opener:
            opener = False
            continue
        if prev in ENDERS:
            opener = False
            continue
        total[w] += 1
        if prev == ",":
            after[w] += 1
            sites[0] += 1


def tally(path, keep=None):
    after = collections.Counter()
    total = collections.Counter()
    sites = [0]
    n = 0
    for s in sentences(path):
        n += 1
        if keep is not None and not keep(n):
            continue
        count(s, after, total, sites)
    return after, total, sites[0], n


def frequencies(lang):
    """The shipped word list for [lang], as {word: count} plus its total."""
    path = os.path.join(ASSETS, "dictionaries", lang + ".txt")
    if not os.path.exists(path):
        return {}, 0
    freq = {}
    total = 0
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            i = line.find(" ")
            if i <= 0:
                continue
            try:
                n = int(line[i + 1:].strip())
            except ValueError:
                continue
            freq[line[:i]] = n
            total += n
    return freq, total


def model(after, total, min_share=MIN_SHARE, min_seen=MIN_SEEN, freq=None, freq_total=0):
    """The words worth acting on, best first.

    [freq] is the shipped frequency list, and a candidate absent from it or
    wildly over-represented against it is dropped whatever its share -- see
    [OUTLIER], and the vocative of Tom.
    """
    corpus_total = sum(total.values()) or 1
    rows = []
    for w, seen in total.items():
        if seen < min_seen:
            continue
        share = after[w] / seen
        if share < min_share:
            continue
        if freq is not None:
            d = freq.get(w, 0)
            if d == 0:
                continue
            if (seen / corpus_total) / (d / max(1, freq_total)) > OUTLIER:
                continue
        rows.append((w, after[w], seen, share))
    rows.sort(key=lambda r: -r[1])
    return rows


def report(langs, min_share):
    print("lang  sentences  comma sites  words   covered   head")
    for lang in langs:
        code3 = ISO3.get(lang)
        path = code3 and corpus(code3)
        if not path:
            print("%-4s  (no corpus)" % lang)
            continue
        after, total, sites, n = tally(path)
        freq, ft = frequencies(lang)
        rows = model(after, total, min_share, freq=freq, freq_total=ft)
        covered = sum(r[1] for r in rows)
        head = ", ".join("%s %.0f%%" % (r[0], 100 * r[3]) for r in rows[:6])
        print("%-4s %10d %12d %6d %8.1f%%   %s"
              % (lang, n, sites, len(rows),
                 100.0 * covered / max(1, sites), head))


def holdout(path, lang, min_share):
    """Precision and volume on sentences the list was not built from.

    Nine tenths in, every tenth sentence out, then the rule run over the part
    it has never seen: each occurrence of a listed word mid-sentence is a comma
    the keyboard would insert, and the original says whether it belonged.
    """
    after, total, _, _ = tally(path, keep=lambda i: i % 10 != 0)
    freq, ft = frequencies(lang)
    listed = {r[0] for r in model(after, total, min_share, freq=freq, freq_total=ft)}
    if not listed:
        return None
    right = wrong = sites = 0
    n = 0
    for s in sentences(path):
        n += 1
        if n % 10 != 0:
            continue
        a = collections.Counter()
        t = collections.Counter()
        si = [0]
        count(s, a, t, si)
        sites += si[0]
        for w, seen in t.items():
            if w in listed:
                right += a[w]
                wrong += seen - a[w]
    return right, wrong, sites


def evaluate(langs, min_share):
    """Nine tenths to build, one tenth to answer for it.

    The share a word carries in the model is precision *on the sentences it was
    counted from*, which is the one number a model must never be trusted to
    give about itself. Here the tenth sentence of every ten is held out, the
    model is built without it, and the rule is then run over it: every
    occurrence of a listed word mid-sentence is a comma the keyboard would
    insert, and the original says whether it belonged.
    """
    print("lang   inserted   right   wrong   precision   recall of comma sites")
    for lang in langs:
        code3 = ISO3.get(lang)
        path = code3 and corpus(code3)
        if not path:
            continue
        got = holdout(path, lang, min_share)
        if got is None:
            print("%-4s   (nothing above the bar)" % lang)
            continue
        right, wrong, sites = got
        ins = right + wrong
        print("%-4s %10d %7d %7d %10.1f%% %12.1f%%"
              % (lang, ins, right, wrong,
                 100.0 * right / max(1, ins), 100.0 * right / max(1, sites)))


def write(langs, min_share):
    out = os.path.join(ASSETS, "commas")
    os.makedirs(out, exist_ok=True)
    for lang in langs:
        code3 = ISO3.get(lang)
        path = code3 and corpus(code3)
        if not path:
            continue
        after, total, sites, n = tally(path)
        freq, ft = frequencies(lang)
        rows = model(after, total, min_share, freq=freq, freq_total=ft)
        covered = sum(r[1] for r in rows) / max(1, sites)
        rate = sum(r[2] for r in rows) / max(1, n)
        target = os.path.join(out, lang + ".txt")
        got = holdout(path, lang, min_share) if rows else None
        prec = (got[0] / max(1, got[0] + got[1])) if got else 0.0
        if not rows or rate < MIN_RATE or prec < MIN_PRECISION:
            if os.path.exists(target):
                os.remove(target)
            print("%-4s skipped (held-out %.1f%%, fires 1 per %s sentences)"
                  % (lang, 100 * prec, round(1 / rate) if rate else "never"))
            continue
        with io.open(target, "w", encoding="utf-8", newline="\n") as f:
            for w, _a, _seen, share in rows:
                f.write("%s\t%d\n" % (w, round(share * 1000)))
        print("%-4s %2d words, held-out %.1f%%, fires 1 per %3d sentences, "
              "%4.1f%% of its commas, %d bytes"
              % (lang, len(rows), 100 * prec, round(1 / rate), 100 * covered,
                 os.path.getsize(target)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--eval", action="store_true")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--share", type=float, default=MIN_SHARE)
    ap.add_argument("--langs", default="")
    args = ap.parse_args()
    langs = [l for l in args.langs.split(",") if l] or sorted(ISO3)
    if args.report:
        report(langs, args.share)
    if args.eval:
        evaluate(langs, args.share)
    if args.write:
        write(langs, args.share)
    if not (args.report or args.eval or args.write):
        ap.print_help()


if __name__ == "__main__":
    main()
