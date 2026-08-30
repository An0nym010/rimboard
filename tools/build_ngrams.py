#!/usr/bin/env python3
"""Add corpus-derived next-word predictions to the bundled models.

    python tools/build_predictions.py       # hand-written model first
    python tools/build_ngrams.py tr en      # then this, which merges into it
    python tools/build_ngrams.py --check    # do the shipped models still agree
                                            # with the shipped dictionaries?

Why this exists
---------------
The bundled prediction models were written by hand: 2,235 lines of authored
word associations producing 1,746 rows across all 22 languages, of which
English had 224 and Hungarian 36. Every context feature in the engine -- the
preceding word, the following word, sentence openers -- reads that model, so a
well-built ranking layer was running on almost no fuel.

Source and licence
------------------
Tatoeba sentence exports (https://tatoeba.org), CC BY 2.0 FR. Chosen over the
alternatives deliberately: OPUS OpenSubtitles, which is where the dictionaries
come from, is not reachable over TLS from every machine, and the Leipzig
corpora are CC BY-NC, which an app that anyone may redistribute cannot use.
Tatoeba is also the right register -- short everyday sentences rather than news
or encyclopedia prose.

Attribution belongs in NOTICE. CC BY does not impose share-alike, so unlike the
dictionaries these files do not carry a licence onward.

The Tom problem
---------------
Tatoeba is a language-teaching corpus and its sentences are overwhelmingly
about Tom and Mary. "tom" is the single most frequent token in the Turkish
export -- more common than "bir" -- and taken raw it would become the top
prediction after almost every word.

Rather than a hand-written blocklist of names, which would need one per
language and would miss whatever the next corpus over-represents, this compares
each token's share of the corpus against its share of the shipped frequency
dictionary. That dictionary comes from subtitles and has no such bias, so the
ratio between the two is an artifact detector that needs no list:

    tom 262x    mary 39x    john 1.0x    bir 1.1x    merhaba 0.1x

Ordinary words sit between 0.1 and about 2. Anything above OUTLIER is corpus
noise, whatever it happens to be called.
"""
import bz2
import collections
import os
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "build", "corpus")
START = "\u0001"

# Tatoeba uses ISO 639-3; the app uses 639-1.
ISO3 = {
    "en": "eng", "tr": "tur", "de": "deu", "es": "spa", "fr": "fra", "it": "ita",
    "pt": "por", "ru": "rus", "nl": "nld", "pl": "pol", "sv": "swe", "id": "ind",
    # Norwegian is "nob" (Bokmal), not "nor": Tatoeba has no generic Norwegian
    # and 404s on it. Bokmal is the written form the app's "no" list is.
    "ro": "ron", "cs": "ces", "da": "dan", "no": "nob", "fi": "fin", "hu": "hun",
    "uk": "ukr", "el": "ell", "hr": "hrv", "sk": "slk",
}

# How far a token may be over-represented in the corpus before it is treated as
# an artifact of that corpus rather than a fact about the language.
OUTLIER = 8.0
# Continuations kept per context word.
#
# Six, and measured rather than guessed. Raising it to 12 is nearly free in
# bytes and lifts how often the model holds the word actually typed next, on
# held-out sentences, by a lot: en 33% -> 41%, tr 22% -> 27%. It was still not
# taken, because nothing consumes ranks 7-12 usefully. The prediction row shows
# three words, so the first six already cover it; the only other consumer is
# correction re-ranking, and giving that the deeper list made it worse in the
# accuracy benchmark (Turkish gained four points from context at six
# continuations and one at twelve). More data that only reaches a consumer it
# harms is not an improvement.
PER_CONTEXT = 6

# A runaway guard, not a tuning knob.
#
# This was 6000 and it bound: nine of the twenty-two languages were truncated
# by it, discarding roughly two thirds of what their corpora supported. On
# held-out text those rows are worth 2.4 points of context coverage in English
# and 4.8 in Turkish -- the share of running text where the preceding word is
# one the model has an opinion about at all. The thirteen smaller languages
# were never near it and did not change by a byte when it was lifted.
#
# What actually limits a language now is MIN_PAIR: a continuation seen twice is
# not kept, whatever the cap says. 30000 is above every language the corpus
# supports today (Russian, the largest, reaches 25,536) and exists so that a
# future corpus cannot silently produce a five-megabyte asset.
#
# **It does not do that, and Russian is already past it.** This bounds `rows`,
# which is the freshly counted model, and [merge] then unions that with whatever
# the asset already held -- it "only ever adds", by design, so that hand-written
# entries survive. Two rebuilds against a growing corpus can therefore approach
# twice this number without either of them exceeding it. Counted on the shipped
# assets, `ru` holds 39,226 rows against a cap of 30000 + TRI_ROWS = 36,000.
#
# Silence was the part worth fixing rather than the size: 39,226 rows cost 6.6
# MB of heap against a 12 MB budget, so Russian is not too big, it merely got
# there without anybody being told. [merge] now says so, and
# `PredictionFootprintTest` holds every asset to a ceiling so the next one is a
# failing test rather than a discovery.
MAX_ROWS = 30000
# Two-word contexts kept per language, on top of the one-word ones.
#
# The engine has always taken two preceding words and, until 2026-08-21, only
# the user's own learned n-grams were ever asked about both: the shipped model
# was looked up by the last word alone. These rows are what the second word
# now reaches, keyed "first second" and merged in front of the one-word row
# rather than replacing it.
#
# Six thousand is the knee of the curve, not a ceiling. Measured on held-out
# sentences, first suggestion correct / one of three / one of six, English:
#
#     bigram only        16.3%   27.1%   35.1%
#     +  6,000 trigram   19.6%   31.9%   40.5%     217 KB, fires on 48% of words
#     + 12,000 trigram   20.3%   32.8%   41.5%     425 KB, fires on 55%
#     + 25,000 trigram   21.1%   33.8%   42.5%     768 KB, fires on 62%
#
# The first six thousand buy five points for 217 KB; the next nineteen
# thousand buy two more for 551 KB. Turkish gains about a third as much at
# every cap -- agglutination puts the informative context inside the word
# rather than in the two before it -- and gains nothing after the first six
# thousand. Raise this if a language's strip feels thin; it is one number and
# the asset is regenerated from it.
TRI_ROWS = 6000

# How many times a continuation must be seen to be kept.
#
# This said "a pair seen fewer times than this is not evidence of anything",
# which was never measured. It has been now, and the honest answer is that it
# is wrong about predictions and right about everything else.
#
# `tools/eval_ngrams.py` holds out a tenth of each corpus by sentence, builds
# the model from the other nine, and asks the held-out text how often the model
# has an opinion about the next word and how often it is right. At 2 rather
# than 3, all eighteen languages measured improved and none regressed --
# in coverage, which more rows must raise, and in precision *given* coverage,
# which they need not. Coverage gained, in points:
#
#     hr +8.3  id +5.2  fi +5.1  sk +4.5  ro +4.4  pl +4.1  no +3.9  el +3.8
#     cs +3.7  hu +3.4  tr +3.0  sv +2.9  uk +2.8  da +2.5  nl +1.8  de +1.3
#     ru +1.3  en +0.4
#
# The gain runs inversely with corpus size, which is the whole story: three
# occurrences is a reasonable bar when the corpus is large enough to clear it,
# and English is the only language whose corpus comfortably is. Croatian, whose
# Tatoeba export is 6,408 sentences, keeps 419 rows at 3 and 1,174 at 2.
#
# **It was 3 until the sweep it was waiting for.** These rows are not only read
# by the prediction strip. `SuggestionEngine.correctionCandidates(contextRank=)`
# ranks *corrections* by what the preceding word predicts, so a denser model is
# a louder context on the typing path -- and context is allowed to settle a
# near-tie, not to overrule the geometry. At the weight that shipped alongside
# MIN_PAIR=3, regenerating at 2 broke the ceiling:
#
#     tr  a deliberately wrong context overturned 65 of 258 answers (ceiling 64)
#     tr  true context: rescued 6, broke 5   -- was comfortably net positive
#
# That is a statement about the pair of constants, not about this one. Swept
# jointly on 2026-08-28, worst damage across all four arms of that test:
#
#     MIN_PAIR  weight   worst damage   rescued  broken
#     3         1.50     19.9%          22       4       <- what shipped
#     2         1.50     25.2%          28       7          over the ceiling
#     2         1.25     20.2%          24       7       <- here
#     2         1.00     17.1%          19       5
#
# The 1.25 row is the shipped correction behaviour to within noise -- 20.2%
# against 19.9%, net rescues 17 against 18 -- while the strip gets the coverage
# in the table above. The baseline row was re-measured in the same run rather
# than quoted, because a comparison against numbers taken on another day and
# another corpus is not a comparison.
#
#
# Confirmed on a phone, which is where a coverage figure becomes a keyboard.
# `millet` is one of 11,781 Turkish one-word contexts the old model had no
# opinion about at all. Typed into the same field, on the same build of
# everything else, with the Turkish layout selected:
#
#     MIN_PAIR 3   "millet " -> the strip is empty
#     MIN_PAIR 2   "millet " -> gidelim | bu | devam
#
# Which is the whole argument in one line: 3.0 points of coverage is not an
# abstraction, it is the difference between three suggestions and none, on
# words people use.
# What it costs, end to end: Turkish keystroke savings 38.1% -> 39.9%, English
# 43.8% -> 43.9% (it was already saturated, as the +0.4 coverage said it would
# be), and 1.45 MB on the release APK -- 30.91 to 32.43. The gain is real for
# the agglutinative and small-corpus languages and negligible for English.
#
# That last sentence was half-earned when it was written. English and Turkish
# were measured; the other twenty languages the megabyte was spent on had only
# a coverage figure, and coverage is an input to keystroke savings, not a
# synonym for it. The repository could not measure them, and said so: their
# prose fixtures come from this same corpus, so the model would have been
# scored on the sentences it counted.
#
# `--fixtures` closes that. It builds a model from nine tenths of a corpus and
# writes the tenth beside it, which is a split the shipped assets cannot offer,
# and `StripAccuracyTest.held-out context savings` reads the pair. Four corpora
# of differing size, keystrokes saved with context:
#
#     cs  28.3% -> 29.1%    da  40.9% -> 41.4%
#     hr  28.4% -> 29.3%    sk  29.7% -> 30.0%
#
# All four improve, the smallest corpus by the most. 0.6 points on average --
# smaller than Turkish, larger than English, and now a measurement rather than
# an inference. The 1.45 MB stands.
# **Swept again at 1, and not taken.** The sentence above -- that the gain runs
# inversely with corpus size -- keeps being true below two. Held out the same
# way, coverage and top-3 at MIN_PAIR 2 against 1:
#
#     hr +13.4 / +3.7    ro  +6.8 / +1.6    uk +3.6 / +0.9    fr +1.0 / +0.3
#     sk  +7.4 / +3.2    id  +6.8 / +1.5    da +3.4 / +1.6    de +0.8 / +0.1
#     no  +6.9 / +2.1    cs  +6.5 / +1.3    nl +2.4 / +0.5    it +0.6 / +0.3
#     pl  +6.7 / +1.3    fi  +6.3 / +1.5    hu +2.1 / +0.4    en +0.2 / +0.1
#     el  +5.7 / +2.0    sv  +3.9 / +1.3    pt +1.5 / +0.4    ru +0.1 / -0.0
#                        es  +1.4 / +0.4    tr +1.1 / +0.1
#
# Every language gains, in coverage and in precision *given* coverage, and
# Croatian -- the smallest corpus in the set at 6,280 sentences and the weakest
# model in the app at 1,354 rows -- gains four times what anything else does.
# Several of the largest gains cost nothing at all in size, because those
# languages are already at MAX_ROWS and a lower bar merely picks better rows:
# Polish gains 6.7 points and Finnish 6.3 at an identical row count.
#
# Two things stop it, both about the tooling rather than the number:
#
#  1. [merge] only ever adds, so lowering this and re-running does not *build*
#     the model below -- it unions it onto the old one, past MAX_ROWS. One run
#     took Turkish from 35,246 rows to 42,775. A clean MIN_PAIR=1 asset needs a
#     regenerating builder, which is a different contract from the one the
#     hand-written entries rely on.
#  2. These rows also rank corrections, and the last time this constant moved it
#     broke the ceiling in the table above and had to be paid for by dropping
#     the context weight. That sweep was run on English and Turkish, which gain
#     0.2 and 1.1 points here -- so the languages that would gain are the ones
#     the ceiling was never measured on.
#
# Neither is a reason it is wrong. Both are reasons it is not a one-line change.
MIN_PAIR = 2

STRIP = ".,!?;:\"'()[]{}\u00ab\u00bb\u2018\u2019\u201c\u201d\u2026-\u2014"


def fetch(code3):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, code3 + "_sentences.tsv.bz2")
    if os.path.exists(path):
        return path
    url = ("https://downloads.tatoeba.org/exports/per_language/%s/%s_sentences.tsv.bz2"
           % (code3, code3))
    print("  downloading %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    # Retried, because this host is flaky in a way that is not about us: two
    # HEAD requests and one TLS handshake failed during a single afternoon and
    # every one of them succeeded on a retry. Without this a twenty-language
    # run throws away the languages it had already finished.
    last = None
    for attempt in range(4):
        try:
            tmp = path + ".part"
            with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(tmp, path)
            return path
        except Exception as e:  # noqa: BLE001 - any network failure is retryable
            last = e
            if attempt < 3:
                wait = 3 * (attempt + 1)
                print("    %s; retrying in %ds" % (type(e).__name__, wait))
                time.sleep(wait)
    raise last


def dictionary(lang):
    """word -> frequency, from the list the app already ships."""
    out = {}
    p = os.path.join(ASSETS, "dictionaries", lang + ".txt")
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                out[parts[0]] = int(parts[1])
    return out


def sentences(path):
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                yield parts[2]


def build(lang):
    code3 = ISO3.get(lang)
    if not code3:
        print("  no Tatoeba code for %s" % lang)
        return
    path = fetch(code3)
    freq = dictionary(lang)
    total_dict = sum(freq.values())

    unigram = collections.Counter()
    bigram = collections.defaultdict(collections.Counter)
    trigram = collections.defaultdict(collections.Counter)
    tri_seen = collections.Counter()
    opener = collections.Counter()
    total_tokens = 0
    for s in sentences(path):
        words = [w.strip(STRIP).lower() for w in s.split()]
        words = [w for w in words if w and w.isalpha()]
        if not words:
            continue
        opener[words[0]] += 1
        for w in words:
            unigram[w] += 1
            total_tokens += 1
        for a, b in zip(words, words[1:]):
            bigram[a][b] += 1
        for a, b, c in zip(words, words[1:], words[2:]):
            trigram[(a, b)][c] += 1
            tri_seen[(a, b)] += 1

    def ordinary(w):
        """Not a corpus artifact, and a word the app already knows."""
        d = freq.get(w, 0)
        if d == 0:
            return False
        share_c = unigram[w] / total_tokens
        share_d = d / total_dict
        return share_c / share_d <= OUTLIER

    rows = {}
    ranked = sorted(bigram.items(), key=lambda kv: -unigram[kv[0]])
    for prev, nexts in ranked:
        if len(rows) >= MAX_ROWS:
            break
        if not ordinary(prev):
            continue
        keep = [w for w, n in nexts.most_common(PER_CONTEXT * 4)
                if n >= MIN_PAIR and ordinary(w)][:PER_CONTEXT]
        if keep:
            rows[prev] = keep
    # Two-word contexts, commonest first, appended behind the one-word rows.
    # Both halves of the context have to be ordinary words, for the same
    # reason the one-word rows do: a corpus artifact in either position makes
    # the row a fact about Tatoeba rather than about the language.
    tri_kept = 0
    for (a, b), nexts in sorted(trigram.items(), key=lambda kv: -tri_seen[kv[0]]):
        if tri_kept >= TRI_ROWS:
            break
        if not (ordinary(a) and ordinary(b)):
            continue
        keep = [w for w, n in nexts.most_common(PER_CONTEXT * 4)
                if n >= MIN_PAIR and ordinary(w)][:PER_CONTEXT]
        if keep:
            rows[a + " " + b] = keep
            tri_kept += 1

    starts = [w for w, n in opener.most_common(PER_CONTEXT * 8)
              if n >= MIN_PAIR and ordinary(w)][:20]

    merge(lang, rows, starts)


def check(lang):
    """Does the shipped model still agree with the dictionary beside it?

    Asked because the dictionaries grew after the models were built, so
    [ordinary] had been rejecting words the app now knows. Rebuilding on the
    bigger dictionaries added rows to ten of the twenty-two: Hungarian 328,
    Ukrainian 32, Finnish 25, Danish 11, Polish 7, and a continuation or two
    elsewhere. The other twelve gained nothing at all, because MIN_PAIR does
    most of what the dictionary gate does -- a word rare enough to be missing
    from a 100,000-entry list rarely has the same follower three times -- and
    the languages that did gain are the agglutinative ones, where the words
    the dictionary was missing were ordinary inflections rather than names.

    The direction a rebuild *cannot* report is the other one. [merge] only
    ever adds, so nothing tells you when a row should now be dropped -- and
    that can happen without the corpus changing at all, because [ordinary]
    divides a word's share of the corpus by its share of the dictionary and
    the dictionary's total moves under it. Measured after this week's growth:
    two rows in Dutch, two continuations in English -- and forty contexts in
    Hungarian, whose dictionary doubled, so its denominator moved most. Some
    of those are the detector working ("magyar" really is over-represented in
    a language-learning corpus); the Dutch pair are ordinary words that merely
    drifted over the line.

    Nothing here removes them. [merge] is additive on purpose, because the
    hand-written rows share the file with the corpus ones and a from-scratch
    rebuild would have to tell them apart. Forty rows in twenty thousand is
    not worth building that for -- but it is worth being able to see, which is
    why this prints the count rather than the file staying quiet about it.

    So: run this after changing a dictionary. Rebuild if the counts are large,
    and know that a rebuild only adds.
    """
    code3 = ISO3.get(lang)
    path = os.path.join(CACHE, code3 + "_sentences.tsv.bz2") if code3 else None
    if not path or not os.path.exists(path):
        return "%s: no cached corpus; run a build first" % lang
    freq = dictionary(lang)
    total_dict = sum(freq.values())
    unigram = collections.Counter()
    total_tokens = 0
    for s in sentences(path):
        for w in s.split():
            w = w.strip(STRIP).lower()
            if w and w.isalpha():
                unigram[w] += 1
                total_tokens += 1

    def ordinary(w):
        d = freq.get(w, 0)
        if d == 0:
            return False
        return (unigram[w] / total_tokens) / (d / total_dict) <= OUTLIER

    rows = 0
    conts = 0
    bad_ctx = []
    bad_next = 0
    p = os.path.join(ASSETS, "predictions", lang + ".txt")
    with open(p, encoding="utf-8") as f:
        for line in f:
            if "\t" not in line:
                continue
            k, v = line.rstrip("\n").split("\t", 1)
            nexts = v.split()
            rows += 1
            conts += len(nexts)
            parts = k.split(" ")
            # The opener row is keyed U+0001 and is not a corpus context.
            if all(w.isalpha() for w in parts) and not all(ordinary(w) for w in parts):
                bad_ctx.append(k)
            bad_next += sum(1 for w in nexts if w.isalpha() and not ordinary(w))
    note = "%s: %d rows, %d continuations" % (lang, rows, conts)
    if bad_ctx or bad_next:
        return note + " -- %d contexts and %d continuations would no longer qualify%s" % (
            len(bad_ctx), bad_next,
            (" (e.g. " + " ".join(bad_ctx[:4]) + ")") if bad_ctx else "")
    return note + " -- agrees with the dictionary"


def merge(lang, rows, starts):
    """Hand-written entries keep their place; corpus entries fill in behind."""
    p = os.path.join(ASSETS, "predictions", lang + ".txt")
    existing = {}
    order = []
    with open(p, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if "\t" not in line:
                continue
            k, v = line.split("\t", 1)
            existing[k] = v.split()
            order.append(k)

    added_rows = 0
    added_words = 0
    if starts:
        cur = existing.get(START, [])
        merged = cur + [w for w in starts if w not in cur]
        added_words += len(merged) - len(cur)
        existing[START] = merged
        if START not in order:
            order.append(START)
    for k, v in rows.items():
        cur = existing.get(k)
        if cur is None:
            existing[k] = v
            order.append(k)
            added_rows += 1
            added_words += len(v)
        else:
            merged = cur + [w for w in v if w not in cur]
            added_words += len(merged) - len(cur)
            existing[k] = merged

    with open(p, "w", encoding="utf-8", newline="\n") as f:
        for k in order:
            f.write(k + "\t" + " ".join(existing[k]) + "\n")
    print("  %s: +%d rows, +%d continuations, %d rows total"
          % (lang, added_rows, added_words, len(order)))
    # The cap above bounds the freshly counted rows and nothing else, so a
    # merge can carry the asset past it without any single run doing so. Said
    # out loud, because the constant's own note promised it could not happen.
    if len(order) > MAX_ROWS + TRI_ROWS:
        print("  WARNING: %s now holds %d rows, %d past the %d this tool caps a "
              "fresh build at. merge() only adds, so this cannot come back down "
              "without regenerating the asset from scratch."
              % (lang, len(order), len(order) - (MAX_ROWS + TRI_ROWS),
                 MAX_ROWS + TRI_ROWS))


if __name__ == "__main__":
    args = list(sys.argv[1:])
    checking = "--check" in args
    langs = [a for a in args if not a.startswith("--")]
    if not langs:
        langs = list(ISO3.keys()) if checking else ["tr", "en"]
    if checking:
        for lang in langs:
            try:
                print(check(lang), flush=True)
            except Exception as e:  # noqa: BLE001 - one language must not sink the run
                print("%s: FAILED (%s: %s)" % (lang, type(e).__name__, e), flush=True)
        sys.exit(0)
    failed = []
    for lang in langs:
        print(lang)
        try:
            build(lang)
        except Exception as e:  # noqa: BLE001 - one language must not sink the run
            print("  %s FAILED: %s" % (lang, e))
            failed.append(lang)
    if failed:
        print()
        print("failed, safe to re-run (downloads are cached): %s" % " ".join(failed))
