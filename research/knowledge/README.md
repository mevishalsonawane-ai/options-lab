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

## The shared section (13 Oct 2026)

Besides the facts, the pack carries a `shared` section that ira-core's `SharedKnowledge` reads for EVERY agent on the phone
(the trade manager's specialists, the order review, order flow, the Liquidity paper test, the slippage record, the study, the
forge and variants, and Jarvis): compact items, each tagged with its topics, strategy (`a`: the trade manager's family key),
instrument, time of day, regime, execution flag, source kind (`research`, `experiment`, `open`, `app`), date, sample size `n`,
effect and gate (`pass` / `fail` / `open`), plus the execution baselines.

| from | items |
|---|---|
| FINDINGS.md bullets and strategy cards with a verdict | `research` |
| `research/experiments/entries/*.yaml`, every idea tried (also `experiment` facts) | `experiment` |
| `open_questions.yaml` (and log entries still `inconclusive` / `shadow`) | `open` |
| `research/app_lessons/*.json`, the phone's lessons imported by `import_app_lessons.py` (also `app_lesson` facts) | `app` |
| `baselines.yaml`: the real option half-spread per index (h24), the signal-time multiplier, the ATM round trip (R16) | baselines |

Every number in `open_questions.yaml` and `baselines.yaml` is checked against its cited source, like the strategy cards. On the
phone the research is merged with Jarvis's lessons and the recent findings; an app lesson that disagrees with a research verdict
is kept beside it, both marked "conflict" with both sample sizes. Reading it only explains, ranks or (in shadow, off by default)
would lower risk.

```
python research/knowledge/test_import_app_lessons.py   # the importer's no-money rule and the shared section
```

## Adding a strategy card

Add it to `strategies.yaml` with `src` and `sec` for each number, run the build, and fix any "numbers not in source".
