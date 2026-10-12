# Jarvis's knowledge pack (L5 Level 4)

`build_pack.py` turns the repo's research into one sourced pack that ships in the app
(`android/app/src/main/assets/knowledge/pack.bin`, about 0.6 MB). Jarvis answers research questions ("what did the
research say about ORB?", "is Liquidity profitable?", "how does BankNifty behave on expiry after a gap down?") from it,
on the phone, with no network: ira-core's `Knowledge` retrieves the best facts (BM25 + instruments + topic tags + a small
English/Hinglish synonym map) and the answer is made of their own sentences, so every number said is a fact's.

```
python research/knowledge/build_pack.py          # rebuild after research changes; commit the pack with them
python research/knowledge/build_pack.py --check  # is the committed pack up to date?
```

## What goes in

| kind | from | facts |
|---|---|---|
| `finding` | research/FINDINGS.md, every bullet | highest weight |
| `strategy` | strategies.yaml: what each strategy does, its verdict, key numbers with period and costs | every number is checked against the cited source; a mismatch fails the build |
| `study` | the app's study pages (assets/study/pages.txt) | |
| `doc` | every research/*.md (HUNT, R, X and the earlier studies): prose and list items; verdict-like sections weigh more and their tables are read row by row | |
| `answer` | research/hunt/h42/answers.csv, the 1,296 plain-language answers | confidence from the BH q-value |
| `model` | research/models/*.md model cards | none yet |

Each fact: `id`, `kind`, `title`, `text`, `tags`, `inst` (instruments), `src` + `sec` (doc and section), `date`, `verdict`
(positive / weak / negative / context: a keyword heuristic except for strategy cards and h42), `conf` (high / medium /
low), `w` (weight) and `personal` (the text quotes Boss's own fills, paper days or capital: never said on a locked phone).
The pack's `version` is a hash of its facts; `built_from` is the newest source date (not the build time), so the same
sources always give the same bytes.

## Adding a strategy card

Add it to `strategies.yaml` with `src` and `sec` for each number, run the build, and fix any "numbers not in source".
