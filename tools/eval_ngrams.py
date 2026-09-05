"""Held-out evaluation of the bundled next-word models.

Splits each language's Tatoeba corpus by sentence, builds the model from one
side as tools/build_ngrams.py does, and asks the other how often the model has
an opinion and how often it is right.

--coverage sweeps thresholds and holds out every tenth sentence, which is the
right split for a coverage percentage: it wants a large sample and does not
care that the model is a tenth smaller than what ships.

--fixtures writes models for the test suite to score a keyboard against, and
wants the opposite. There it holds out only the 140 sentences it scores, so the
model stays the size of the one users have -- see write_fixtures.
"""
import collections, io, os, sys
sys.path.insert(0, os.path.join(os.getcwd(), "tools"))
import build_ngrams as B

def corpus(lang):
    path = B.fetch(B.ISO3[lang])
    out = []
    for s in B.sentences(path):
        w = [x.strip(B.STRIP).lower() for x in s.split()]
        w = [x for x in w if x and x.isalpha()]
        if len(w) >= 2:
            out.append(w)
    return out

def build(train, freq, min_pair):
    total_dict = sum(freq.values())
    uni = collections.Counter(); bi = collections.defaultdict(collections.Counter)
    tri = collections.defaultdict(collections.Counter); tri_seen = collections.Counter()
    tok = 0
    for w in train:
        for x in w:
            uni[x] += 1; tok += 1
        for a, b in zip(w, w[1:]):
            bi[a][b] += 1
        for a, b, c in zip(w, w[1:], w[2:]):
            tri[(a, b)][c] += 1; tri_seen[(a, b)] += 1

    def ordinary(x):
        d = freq.get(x, 0)
        if d == 0:
            return False
        return (uni[x] / tok) / (d / total_dict) <= B.OUTLIER

    rows = {}
    for prev, nexts in sorted(bi.items(), key=lambda kv: -uni[kv[0]]):
        if len(rows) >= B.MAX_ROWS:
            break
        if not ordinary(prev):
            continue
        keep = [w for w, n in nexts.most_common(B.PER_CONTEXT * 4)
                if n >= min_pair and ordinary(w)][:B.PER_CONTEXT]
        if keep:
            rows[prev] = keep
    kept = 0
    for (a, b), nexts in sorted(tri.items(), key=lambda kv: -tri_seen[kv[0]]):
        if kept >= B.TRI_ROWS:
            break
        if not (ordinary(a) and ordinary(b)):
            continue
        keep = [w for w, n in nexts.most_common(B.PER_CONTEXT * 4)
                if n >= min_pair and ordinary(w)][:B.PER_CONTEXT]
        if keep:
            rows[a + " " + b] = keep; kept += 1
    return rows

def evaluate(rows, held):
    """The engine's own lookup: trigram row first, bigram row behind it."""
    seen = top1 = top3 = total = 0
    for w in held:
        for i in range(1, len(w)):
            total += 1
            prev, prev2 = w[i-1], (w[i-2] if i >= 2 else "")
            cand = list(rows.get(prev2 + " " + prev, [])) if prev2 else []
            for x in rows.get(prev, []):
                if x not in cand:
                    cand.append(x)
            if not cand:
                continue
            seen += 1
            if w[i] == cand[0]:
                top1 += 1
            if w[i] in cand[:3]:
                top3 += 1
    pc = lambda n: 100.0 * n / total if total else 0.0
    return pc(seen), pc(top1), pc(top3), total


def raw_sentences(lang):
    """The corpus as text, in the order `corpus()` walks it."""
    path = B.fetch(B.ISO3[lang])
    out = []
    for s in B.sentences(path):
        w = [x.strip(B.STRIP).lower() for x in s.split()]
        w = [x for x in w if x and x.isalpha()]
        if len(w) >= 2:
            out.append(s.strip())
    return out


def write_fixtures(langs, held_sentences=140):
    """Emit a held-out model and the text it was held out from.

    The repository measures context savings for English and Turkish only, and
    says why: for every other language the prose fixture and the n-grams come
    from the same corpus, so the model would be scored on the sentences it was
    counted from. That is not a measurement, it is a mirror.

    A split fixes it. The model here is built from the corpus minus the
    sentences it is about to be scored on, so a keystroke figure taken against
    it means the same thing for Croatian as the shipped one does for English.

    That last clause was written on 2026-08-28 and was false for every
    small-corpus language until 2026-09-05, because this used to hold out every
    tenth sentence while scoring 140 of them. MIN_PAIR is a count threshold, so
    in a small corpus most surviving pairs sit exactly at it and a tenth fewer
    sentences knocks them out:
    dropping a tenth cost English 4% of its rows and Croatian 87%. The figures
    were then read side by side as though they meant the same thing. Holding out
    only what is scored costs nothing and puts every language within 89-100% of
    the model it ships.

    Three models are written per language, at MIN_PAIR 3, 2 and 1, so the pair
    can be compared honestly rather than by quoting coverage and hoping it
    translates -- and so that whichever threshold a language ships, a fixture
    exists at it. build_ngrams.MIN_PAIR_BY_LANG decides that per language, and
    the suite reads it from there.
    """
    # Keep this in step with StripAccuracyTest's held-out arm, which prints the
    # current figures rather than carrying a copy of them. Three copies of the
    # 2026-08-28 table existed -- here, in build_ngrams.py and in the test --
    # and all three went stale together two days later.
    root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "test", "fixtures", "heldout")
    os.makedirs(root, exist_ok=True)
    for lang in langs:
        toks = corpus(lang)
        raws = raw_sentences(lang)
        freq = B.dictionary(lang)
        # Hold out the sentences that are scored, and only those.
        #
        # This used to drop every tenth sentence from training while scoring
        # 140 of them, which throws away corpus for nothing -- and not evenly:
        # MIN_PAIR is a count threshold, so in a small corpus almost every
        # surviving pair sits exactly at it and a tenth fewer sentences knocks
        # it out. Measured over the eight split languages, dropping a tenth
        # cost English 4% of its rows and Croatian 87%. The resulting figures
        # were then read side by side as if they meant the same thing.
        cut = set(list(range(0, len(toks), 10))[:held_sentences])
        train = [s for i, s in enumerate(toks) if i not in cut]
        held = [s for i, s in enumerate(raws) if i in cut]
        with io.open(os.path.join(root, "prose_%s.txt" % lang), "w",
                     encoding="utf-8", newline="\n") as f:
            f.write("\n".join(held) + "\n")
        for mp in (3, 2, 1):
            rows = build(train, freq, mp)
            path = os.path.join(root, "pred%d_%s.txt" % (mp, lang))
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                for k in sorted(rows):
                    f.write(k + "\t" + " ".join(rows[k]) + "\n")
            print("  %s min_pair=%d  %d rows" % (lang, mp, len(rows)))
        print("%s: %d held-out sentences" % (lang, len(held)))

if "--fixtures" in sys.argv:
    write_fixtures([a for a in sys.argv[1:] if not a.startswith("--")])
    sys.exit(0)

print("%-4s %-9s %7s %7s %7s %8s %8s" %
      ("lang", "min_pair", "rows", "covers", "top1", "top3", "positions"))
for lang in sys.argv[1:]:
    sents = corpus(lang)
    freq = B.dictionary(lang)
    train = [s for i, s in enumerate(sents) if i % 10 != 0]
    held = [s for i, s in enumerate(sents) if i % 10 == 0]
    for mp in (3, 2):
        rows = build(train, freq, mp)
        cov, t1, t3, n = evaluate(rows, held)
        print("%-4s %-9d %7d %6.1f%% %6.1f%% %7.1f%% %8d" % (lang, mp, len(rows), cov, t1, t3, n))
