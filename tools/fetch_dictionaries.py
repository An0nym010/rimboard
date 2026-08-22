#!/usr/bin/env python3
"""Regenerate the word-frequency dictionaries, bundled and downloadable.

    python tools/fetch_dictionaries.py            # the bundled assets
    python tools/fetch_dictionaries.py --extended # the downloadable set

Downloads frequency lists from Hermit Dave's FrequencyWords project
(OpenSubtitles corpus, CC BY-SA 4.0), filters to alphabetic words per
language, and writes "word count" lines ordered by frequency.

Two depths, and the difference is the whole design
--------------------------------------------------
**Bundled** is the top TOP words, which every language gets, and which the
APK carries. **Extended** is every word the corpus saw at least MIN_COUNT
times -- about 300,000 words in English and 490,000 in Finnish, because
morphology decides how many distinct forms a language has and a fixed count
cannot. Those are gzipped into dist/ for the app to download, with a manifest
of sizes and SHA-256 hashes that ships inside the APK.

Why not simply ship everything
------------------------------
Because "everything" makes the keyboard worse, and it was measured rather than
guessed. Against the shipped engine, English at each depth -- typos still
recognised as typos, typos actually repaired, and correctly-typed rare words
destroyed by autocorrect:

    top 200,000    94%   91%   30%
    count >= 10    94%   91%   25%
    count >= 5     91%   88%    1%     <- MIN_COUNT
    count >= 3     86%   84%    1%
    count >= 2     83%   80%    1%
    everything     71%   69%    1%

A hapax in a subtitle corpus is usually a misspelling, a name, or an OCR
artifact. Include them all and the spell checker starts accepting nearly a
third of real typos as words -- it stops underlining them and autocorrect
stops fixing them. MIN_COUNT is where the destroy-a-correct-word failure has
already collapsed and typo repair has barely moved.

What that table is really about is *size*, not the threshold
--------------------------------------------------------------
Six languages ship a TOP-capped list that reaches into count 1 or 2, because
their corpora are too thin to fill 200,000 entries any other way -- Ukrainian
bottoms out at count 1 with only 56,869 words seen five times. That looks like
the bottom row of the table above, and it is not. Measured per language, on
their own shipped lists, typos still recognised as typos / typos repaired:

    uk  count>=1  200,000   99% / 98%      cutting to >=5 loses 49% of
    id  count>=1  200,000   93% / 92%      correctly-typed rare words and
    no  count>=1  200,000   95% / 91%      buys at most four points
    da  count>=1  200,000   97% / 94%
    sv  count>=1  200,000   96% / 94%
    pt  count>=1  199,872   94% / 92%
    tr  count>=28 200,000   93% / 91%      <- the deepest-cut list is the worst
    de  count>=6  200,000   98% / 96%

English's collapse came from a *million*-word list, five times the size of
these: the junk is in the extra 800,000, not in "count 1" as a property. A
200,000-entry list built from a thin corpus is most of what that language
attests, not a tail of noise. **So TOP stays a rank cap and MIN_COUNT is not
applied to it.** Do not "fix" the six by cutting them; it was measured, and it
makes them worse.

English is bundled at the extended depth (see BUNDLE_EXTENDED): it is the
default keyboard language for most installs and the one people type without
choosing it.
"""

import gzip
import hashlib
import io
import json
import re
import sys
import urllib.request
from pathlib import Path

BASES = [
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_full.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{lang}/{lang}_50k.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2016/{lang}/{lang}_full.txt",
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2016/{lang}/{lang}_50k.txt",
]

# Words kept per language. One number, and every language gets it.
#
# This used to be 200,000 for eight languages and 100,000 for the other
# fourteen. Measured against the shipped engine, the smaller cap silently
# overwrites **20-35% of correctly-typed words** drawn from the band it omits
# -- autocorrect replacing something that was already right, which is the
# failure people actually complain about -- while repairing ordinary typos at
# exactly the same rate, 88% either way. The second hundred thousand words
# cost the corrector nothing because frequency ranks them near the bottom of
# the candidate list; they only ever act by being *known*.
#
# The capped fourteen were also the languages that could least afford it.
# English at rank 120,000 is mostly surnames and transliterations
# ("greenbriar", "kozlenko"); Dutch at 100,000 is still losing "hoofdrekenen"
# and "raakvlak", because compounding and agglutination produce far more
# distinct forms of ordinary words.
#
# The price is 6.8 MB of APK, and that is the whole of the trade.
TOP = 200_000

# The extended depth: every word the corpus saw at least this many times.
# Chosen by measurement -- see the table above. One number, and moving it
# moves both the downloadable set and English's bundled dictionary.
MIN_COUNT = 5

# Languages bundled at the extended depth instead of at TOP, so they need no
# download at all. English, because it is what most installs type by default.
BUNDLE_EXTENDED = {"en"}

# Where the app fetches the extended dictionaries from. A raw.githubusercontent
# URL rather than a release asset on purpose: release downloads answer with a
# redirect to objects.githubusercontent.com, and the online build's transport
# refuses redirects deliberately. Serving from a branch keeps that hardening
# untouched at the cost of the files living in git.
DIST_BASE = "https://raw.githubusercontent.com/An0nym010/rimboard/dictionaries/"

# Downloaded corpora, so the two modes cost one transfer rather than two.
CACHE = Path(__file__).resolve().parent.parent / "build/freqwords"
DIST = Path(__file__).resolve().parent.parent / "dist/dictionaries"
MANIFEST = Path(__file__).resolve().parent.parent / "app/src/main/assets/extended.json"

PATTERNS = {
    "en": r"^[a-z']+$",
    "tr": r"^[a-z\u00e7\u011f\u0131\u00f6\u015f\u00fc]+$",
    "de": r"^[a-z\u00e4\u00f6\u00fc\u00df]+$",
    "es": r"^[a-z\u00e1\u00e9\u00ed\u00f3\u00fa\u00fc\u00f1]+$",
    "fr": r"^[a-z\u00e0\u00e2\u00e7\u00e9\u00e8\u00ea\u00eb\u00ee\u00ef\u00f4\u00f9\u00fb\u00fc\u00ff\u0153\u00e6']+$",
    # No i-circumflex: it is not modern Italian orthography, and every corpus
    # entry carrying it was another language leaking in \u2014 "\u00een" is Romanian,
    # "ma\u00eetre" and "pla\u00eet" are French. Filtered out rather than folded, because
    # folding "\u00een" to "in" would credit an Italian word with Romanian counts.
    "it": r"^[a-z\u00e0\u00e8\u00e9\u00ec\u00ed\u00f2\u00f3\u00f9\u00fa']+$",
    # No trema: FOLD removes it before this pattern is applied.
    "pt": r"^[a-z\u00e1\u00e2\u00e3\u00e0\u00e7\u00e9\u00ea\u00ed\u00f3\u00f4\u00f5\u00fa]+$",
    "ru": r"^[\u0430-\u044f\u0451]+$",
    "nl": r"^[a-z\u00e9\u00eb\u00ef\u00f6\u00fc']+$",
    "pl": r"^[a-z\u0105\u0107\u0119\u0142\u0144\u00f3\u015b\u017a\u017c]+$",
    "sv": r"^[a-z\u00e5\u00e4\u00f6\u00e9]+$",
    "id": r"^[a-z]+$",
    # No cedilla forms here on purpose: FOLD rewrites them to the comma-below
    # letters before this pattern is applied, so they cannot reach it.
    "ro": r"^[a-z\u0103\u00e2\u00ee\u0219\u021b]+$",
    "cs": r"^[a-z\u00e1\u010d\u010f\u00e9\u011b\u00ed\u0148\u00f3\u0159\u0161\u0165\u00fa\u016f\u00fd\u017e]+$",
    "da": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "no": r"^[a-z\u00e6\u00f8\u00e5\u00e9]+$",
    "fi": r"^[a-z\u00e4\u00f6\u00e5]+$",
    "hu": r"^[a-z\u00e1\u00e9\u00ed\u00f3\u00f6\u0151\u00fa\u00fc\u0171]+$",
    "uk": r"^[\u0430-\u0449\u044c\u044e\u044f\u0454\u0456\u0457\u0491']+$",
    "el": r"^[\u03b1-\u03c9\u03ac\u03ad\u03ae\u03af\u03cc\u03cd\u03ce\u03ca\u03cb\u0390\u03b0\u03c2]+$",
    "hr": r"^[a-z\u010d\u0107\u017e\u0161\u0111]+$",
    "sk": r"^[a-z\u00e1\u00e4\u010d\u010f\u00e9\u00ed\u013a\u013e\u0148\u00f3\u00f4\u0155\u0161\u0165\u00fa\u00fd\u017e]+$",
}

# Spellings folded together before counting, per language.
#
# Romanian: the corpus mixes the correct comma-below letters (ș U+0219,
# ț U+021B) with the legacy cedilla ones (ş U+015F, ţ U+0163) inherited from
# pre-Unicode codepages. They are different characters. The Romanian layout
# offers only the comma-below pair — which is the standard — so every
# cedilla-spelled entry was a word the keyboard could suggest but could not
# type, and the corpus prefers those spellings about ten to one, so they
# outranked the ones a user can actually produce. Folding also reunites the
# frequency of a word that the corpus had split across both spellings.
# Portuguese: the trema was abolished by the 1990 Orthographic Agreement, so
# "freqüência" is now "frequência". The corpus predates the change in places
# and carries both; the modern form already dominates, so this mostly removes
# long-tail spellings the keyboard cannot produce and returns their counts to
# the spelling it can.
FOLD = {
    "ro": str.maketrans({"ş": "ș", "ţ": "ț"}),
    "pt": str.maketrans({"ü": "u"}),
}

OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/dictionaries"


def corpus(lang: str) -> Path:
    """The full frequency list for [lang], downloaded once and kept.

    Both modes read the same file, so a rerun of either costs nothing. The
    English list is 19 MB and the Finnish one 37; fetching them twice because
    the tool was invoked twice is the kind of waste that stops people
    regenerating data at all.
    """
    CACHE.mkdir(parents=True, exist_ok=True)
    p = CACHE / (lang + "_full.txt")
    if p.exists() and p.stat().st_size > 0:
        return p
    last = "no source answered"
    for base in BASES:
        url = base.format(lang=lang)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "rimboard-dict"})
            tmp = p.with_name(p.name + ".part")
            with urllib.request.urlopen(req, timeout=300) as resp:
                with io.open(tmp, "wb") as fh:
                    while True:
                        chunk = resp.read(1 << 20)
                        if not chunk:
                            break
                        fh.write(chunk)
            tmp.replace(p)
            return p
        except Exception as e:  # noqa: BLE001 - any one source may be missing
            last = "%s: %s" % (type(e).__name__, e)
    raise RuntimeError("%s: no usable source (%s)" % (lang, last))


def collect(lang: str):
    """Every qualifying word in the corpus, frequency descending.

    One filter pipeline for both depths, so the bundled dictionary and the
    downloadable one cannot come to disagree about what counts as a word.
    """
    pat = re.compile(PATTERNS[lang])
    fold = FOLD.get(lang)
    counts = {}
    with io.open(corpus(lang), encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            sp = line.find(" ")
            if sp <= 0:
                continue
            w = line[:sp]
            if fold:
                w = w.translate(fold)
            if len(w) > 24 or not pat.match(w):
                continue
            try:
                n = int(line[sp + 1:])
            except ValueError:
                continue
            # Folded spellings sum rather than one being dropped: the surviving
            # form must carry the frequency of both, or it ranks far below
            # where the language actually uses it.
            counts[w] = counts.get(w, 0) + n
    # Frequency descending, ties alphabetical, so a rerun over the same corpus
    # produces the same bytes. Folding can move an entry up, so the source
    # order can no longer be relied on.
    return sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))


def write_rows(path: Path, rows) -> int:
    # newline="" so this writes the same bytes on every platform. Without it
    # Python translates on Windows and the file lands with CRLF, which the
    # dictionary loader tolerates -- it trims before parsing the count -- and
    # which nothing else notices: a silent 200 KB per language.
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write("\n".join("%s %d" % (w, n) for w, n in rows) + "\n")
    return path.stat().st_size


def bundled(lang: str) -> str:
    """The dictionary that ships inside the APK."""
    rows = collect(lang)
    if lang in BUNDLE_EXTENDED:
        rows = [r for r in rows if r[1] >= MIN_COUNT]
        depth = "count >= %d" % MIN_COUNT
    else:
        rows = rows[:TOP]
        depth = "top %d" % TOP
    if len(rows) < 20000:
        return "%s: FAILED (only %d words -- corpus too small, or the filter is wrong)" % (
            lang, len(rows))
    size = write_rows(OUT_DIR / (lang + ".txt"), rows)
    return "%s: %7d words bundled (%s), %.1f MB" % (lang, len(rows), depth, size / 1048576)


def extended(lang: str):
    """The dictionary the app can download, and its manifest entry.

    Gzipped, because these are three to seven megabytes of sorted text and
    deflate takes about two thirds off. The hash is over the *compressed*
    bytes -- what a download actually transfers, and what an import off a
    memory stick actually is -- so verifying never means decompressing
    something unverified first.
    """
    rows = [r for r in collect(lang) if r[1] >= MIN_COUNT]
    if len(rows) < 20000:
        return None, "%s: skipped (only %d words at count >= %d)" % (
            lang, len(rows), MIN_COUNT)
    # A download has to be worth the transfer, and for a small corpus it is
    # not: TOP ranks by frequency and so adapts to whatever the corpus holds,
    # while MIN_COUNT is absolute. Where the corpus is thin the bundled list
    # already reaches *past* this depth -- Ukrainian has 56,869 words seen five
    # times and ships 200,000 -- so the extended file would be a downgrade
    # wearing the word "extended". Eight of the twenty-two are in that
    # position; they get no entry and the screen does not list them.
    bundled_path = OUT_DIR / (lang + ".txt")
    have = 0
    if bundled_path.exists():
        with io.open(bundled_path, encoding="utf-8") as fh:
            have = sum(1 for _ in fh)
    if len(rows) < have * 11 // 10:
        return None, "%s: skipped (%d at count >= %d vs %d bundled -- not worth a download)" % (
            lang, len(rows), MIN_COUNT, have)
    text = ("\n".join("%s %d" % (w, n) for w, n in rows) + "\n").encode("utf-8")
    # mtime=0 so identical input produces identical bytes, and therefore an
    # identical hash, on every run. Otherwise gzip stamps the current time into
    # the header and every rebuild looks like new data to anyone diffing.
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(text)
    blob = buf.getvalue()
    DIST.mkdir(parents=True, exist_ok=True)
    with io.open(DIST / (lang + ".txt.gz"), "wb") as fh:
        fh.write(blob)
    entry = {
        "lang": lang,
        "words": len(rows),
        "bytes": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
    }
    return entry, "%s: %7d words, %.1f MB gzipped" % (lang, len(rows), len(blob) / 1048576)


def write_manifest(entries) -> str:
    doc = {
        "version": 1,
        "minCount": MIN_COUNT,
        "base": DIST_BASE,
        "entries": sorted(entries, key=lambda e: e["lang"]),
    }
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    with io.open(MANIFEST, "w", encoding="utf-8", newline="") as fh:
        json.dump(doc, fh, indent=2, sort_keys=True)
        fh.write("\n")
    total = sum(e["bytes"] for e in entries)
    return "manifest: %d languages, %.1f MB to host" % (len(entries), total / 1048576)


if __name__ == "__main__":
    args = list(sys.argv[1:])
    want_extended = "--extended" in args
    langs = [a for a in args if not a.startswith("--")] or list(PATTERNS.keys())
    # A mistyped code (or a stray flag copied from stale docs) reached
    # PATTERNS[lang] and came out as a bare KeyError traceback. Name the
    # problem instead, and list what is valid.
    unknown = [lg for lg in langs if lg not in PATTERNS]
    if unknown:
        sys.exit(
            "unknown language code(s): %s\nknown: %s\n"
            "usage: fetch_dictionaries.py [--extended] [LANG ...]"
            % (" ".join(unknown), " ".join(sorted(PATTERNS)))
        )
    if want_extended:
        entries = []
        for lg in langs:
            # A language bundled at the extended depth has nothing to download.
            if lg in BUNDLE_EXTENDED:
                print("%s: bundled at this depth already, skipping" % lg, flush=True)
                continue
            try:
                entry, note = extended(lg)
            except Exception as e:  # noqa: BLE001 - one language must not sink the run
                entry, note = None, "%s: FAILED (%s: %s)" % (lg, type(e).__name__, e)
            print(note, flush=True)
            if entry:
                entries.append(entry)
        print(write_manifest(entries), flush=True)
    else:
        for lg in langs:
            try:
                print(bundled(lg), flush=True)
            except Exception as e:  # noqa: BLE001
                print("%s: FAILED (%s: %s)" % (lg, type(e).__name__, e), flush=True)
