# RimBoard extended dictionaries

Deeper word lists for [RimBoard](https://github.com/An0nym010/rimboard),
served from this branch and fetched by the app on request.

These are **data, not code**, and this branch holds nothing else. The app
lists them under Settings -> Corrections -> Extended dictionaries: the
`online` build downloads one, and the `offline` build - which holds no
INTERNET permission and so cannot download anything - imports a file you
fetched yourself, through the system file picker.

**Every file is checked before it is installed.** The SHA-256 of each one
is compiled into the APK (`app/src/main/assets/extended.json`), and
nothing that fails to match is accepted. A file imported off a memory
stick is trusted exactly as much as one downloaded from here.

## Contents

| language | words | download |
|---|---|---|
| `cs` | 469,068 | 2.1 MB |
| `de` | 232,384 | 1.1 MB |
| `el` | 330,722 | 1.8 MB |
| `es` | 330,480 | 1.4 MB |
| `fi` | 488,508 | 2.2 MB |
| `hr` | 399,891 | 1.7 MB |
| `hu` | 645,346 | 3.0 MB |
| `it` | 237,278 | 1.0 MB |
| `nl` | 237,002 | 1.1 MB |
| `pl` | 450,433 | 2.0 MB |
| `ro` | 295,430 | 1.3 MB |
| `ru` | 347,482 | 1.8 MB |
| `tr` | 548,863 | 2.4 MB |

Each holds every word the source corpus recorded at least 5 times.

The other nine languages have no file here, and that is deliberate:
RimBoard bundles 200,000 words by frequency rank, and where a corpus is
thin that already reaches past this depth. Ukrainian has 56,869 words
seen five times and ships 200,000, so an "extended" list would be a
downgrade wearing the word extended.

Inside each gzip is one `word count` pair per line, ordered by frequency
- the same format the bundled dictionaries use.

## Checksums

```
395d6df4be64abd81b63f8016c2c2b92e7937c159e3b023b50cb299ba3490556  cs.txt.gz
d978c1871cd5101f7ca88e3df356775f42d1649ae7813956c75c1656b7d6db7c  de.txt.gz
ddd49cd26bd3c641a52e381c42fa1dc854f2f0514014cc34ff21a29a93c825bc  el.txt.gz
9b8652406abc4275eef42d2804d672d515754550a763fd10cf8a9a2257378277  es.txt.gz
710b9f077203f59c132c048e22618562bb9028246811c08408ab7ba87715bc0e  fi.txt.gz
958dec8d895f1ff267279e63305bd8c3b8bd5cc1f2e89fcfa35de42cd88c6fd2  hr.txt.gz
55de42963ef18f9ad03f091478d60d684f97e8ce424b76cdc3f48b9251ad74a7  hu.txt.gz
b7cf66c07e0343985af3c6b0f21d895e92c36ff6b8957ea47edec15dbf2232bc  it.txt.gz
b4fdfd748d29ae0d2579a05359e50376a3fcc14a5feed6d63b5174f849f9ec1c  nl.txt.gz
03c968a599b0e8a7de7f513f477ac68c16c9c2975dca96928f871bf3fae06143  pl.txt.gz
05d9c1ebff4e3c0e5f1a65ac413bd26012c95776702426470950d2a0988cb248  ro.txt.gz
5768577ae9fec19521d2aebf0514d79c04f42e232f4e85e624b1029cafed45b7  ru.txt.gz
bbf5d57736f8be4b15a7bc2253fc4900ccf07ff47167fbbc0e6b859bdb00f4c7  tr.txt.gz
```

## Licence and attribution

Derived from **FrequencyWords** by Hermit Dave
(https://github.com/hermitdave/FrequencyWords), generated from the
OpenSubtitles 2018 corpus (https://opus.nlpl.eu/OpenSubtitles.php), and
licensed under **Creative Commons Attribution-ShareAlike 4.0**
(https://creativecommons.org/licenses/by-sa/4.0/).

Modifications: filtered to the alphabetic words of each language,
deduplicated, folded per language where the corpus mixes two spellings of
the same word (Romanian cedilla forms, the Portuguese trema), and cut to
the words seen at least 5 times. `tools/fetch_dictionaries.py --extended`
on the main branch is the authoritative description of what was changed.

Under CC BY-SA 4.0 these files, and any dictionary derived from them,
remain under CC BY-SA 4.0. That is a licence on the word lists, not on
RimBoard's own MIT-licensed code.

## Regenerating

From a checkout of the main branch:

```
python3 tools/fetch_dictionaries.py --extended
```

That rewrites `dist/dictionaries/` and the manifest inside the APK
together. Both have to be published in the same change, or every download
fails its hash check.

