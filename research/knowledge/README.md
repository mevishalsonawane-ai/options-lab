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
| `answer` | research/hunt/h42/answers.csv, the 1,296 plain-language answers, titled "How BANKNIFTY behaves: gaps (h42)" | confidence from the BH q-value |
| `model` | research/models/*.md model cards | none yet |
| `experiment` | research/experiments/entries/*.yaml, every idea tried; topics from the idea's title and hypothesis (not the method's "after costs", "holdout", "BH q"); cited by its study doc; instruments as the pack's names (GOLDM -> GOLD) | failures included |
| `open` | open_questions.yaml | |
| `index_fact` | index_facts.yaml, R102's field guide (22 Oct 2026): how NIFTY and BANKNIFTY behave, the sign rules tested, the contract facts | every number checked; a contract fact is high confidence whatever its n |
| `app_lesson` | research/app_lessons/*.json, the phone's lessons sent back | rates, counts and effects only |

Each fact: `id`, `kind`, `title`, `text`, `tags`, `inst` (instruments), `src` + `sec` (doc and section), `date`, `verdict`
(positive / weak / negative / context: a keyword heuristic except for strategy cards and h42), `conf` (high / medium /
low), `w` (weight) and `personal` (the text quotes Boss's own fills, paper days or capital: never said on a locked phone).
The pack's `version` is a hash of its facts; `built_from` is the newest source date (not the build time), so the same
sources always give the same bytes. Weights (the retrieval's prior, 25 Oct 2026): FINDINGS and strategy cards 3.0; index
facts 2.5; a doc's verdict / answer / honest sections 2.2 and its other priority sections (caveats, limits, lessons) 2.0;
study pages, h42 answers and experiment entries 2.0; the phone's lessons 1.5; plain prose 1.0. A fact's `title` is read by
the retrieval only (a question naming what the title names gets it first); Jarvis says the `text`.

## The shared section (13 Oct 2026)

Besides the facts, the pack carries a `shared` section that ira-core's `SharedKnowledge` reads for EVERY agent on the phone
(the trade manager's specialists, the order review, order flow, the Liquidity paper test, the slippage record, the study, the
forge and variants, and Jarvis): compact items, each tagged with its topics, strategy (`a`: the trade manager's family key),
instrument, time of day, regime, execution flag, source kind (`research`, `experiment`, `open`, `app`), date, sample size `n`,
effect and gate (`pass` / `fail` / `open`), plus the execution baselines.

| from | items |
|---|---|
| FINDINGS.md bullets and strategy cards with a verdict | `research` |
| `research/experiments/entries/*.yaml`, ranked into the room left under `SHARED_CAP` (1,800): every passed or open entry always, then one item per idea family of the rejected ones (a sweep's cells share a hypothesis; the same rule on two markets is two families), newest and arm-tied first; the build fails if the section still passes the cap. An entry's arm is the strategy its words name, else `mcx` for an entry run only on MCX markets (also `experiment` facts, all of them) | `experiment` |
| `open_questions.yaml` (and log entries still `inconclusive` / `shadow`); the curated questions come before the experiments, so the first open item on a topic is Boss's question (S7 on order flow) | `open` |
| `research/app_lessons/*.json`, the phone's lessons imported by `import_app_lessons.py` (also `app_lesson` facts) | `app` |
| `baselines.yaml`: the real option half-spread per index (h24), the signal-time multiplier, the ATM round trip (R16) | baselines |

Every number in `open_questions.yaml` and `baselines.yaml` is checked against its cited source, like the strategy cards. On the
phone the research is merged with Jarvis's lessons and the recent findings; an app lesson that disagrees with a research verdict
is kept beside it, both marked "conflict" with both sample sizes. Reading it only explains, ranks or (in shadow, off by default)
would lower risk.

```
python research/knowledge/test_import_app_lessons.py   # the importer's no-money rule and the shared section
python -m pytest research/knowledge -q                 # plus the builder's ranking, tagging and cap rules (test_build_pack.py)
```

## Adding a strategy card

Add it to `strategies.yaml` with `src` and `sec` for each number, run the build, and fix any "numbers not in source".
