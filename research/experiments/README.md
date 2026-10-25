# Experiment log

Every model or rule idea tried, failures included, one YAML file per experiment in `entries/`. It is the count behind
the multiple-testing gate (research/data/ARCHITECTURE.md, L6 gate 4, and "Additions before building" item 3): an idea's
p-value means little without knowing how many ideas of its family were tried.

```
python research/experiments/validate.py          # check every entry; print tries per family
python research/experiments/validate.py --json   # the same counts as JSON (input for a Benjamini-Hochberg step)
```

## Fields

| field | type | meaning |
|---|---|---|
| `id` | string | the file name without `.yaml` (`h42`, `r15`, `x2`, later `l1-regime-v1` ...) |
| `title` | string | one line |
| `family` | string | the multiple-testing family (one of the list in `validate.py`: `liquidity`, `orb`, `ml`, `patterns-indicators`, `flow-oi`, `overnight`, `calendar-events`, `sizing-execution`, `option-structure`, `outside-strategies`, `trade-management`, `daily-filters`, `market-facts`, `mcx-crypto`, `stocks`, `diagnosis`) |
| `date` | YYYY-MM-DD | when it was written up |
| `doc` | path | the write-up in the repo |
| `hypothesis` | string | what it set out to show, said before the result |
| `data_version` | string | the data it ran on (a release tag, cache date or source list) |
| `period` | map | `design:` and, when there was one, `holdout:` |
| `instruments` | list | |
| `features` | list | inputs the rule or model read |
| `model_or_rule` | string | |
| `settings` | string | the settings, or where they are recorded |
| `prereg` | path or null | the pre-registration written before any result |
| `variants_tried` | int or null | how many versions were run (null: the doc does not say) |
| `results` | map | `summary:` (with the numbers) and `costs:` (which costs were counted) |
| `holdout_read` | true / false / null | the locked holdout was read for this idea (null: none existed) |
| `verdict` | string | `adopted`, `paper-only`, `shadow`, `rejected`, `inconclusive`, `superseded`, `not-a-test` (a replay, audit or measurement) or `proposed` (written down, never run: `variants_tried` null, `holdout_read` null; not counted as a test, and kept out of the phone's shared section) |
| `notes` | string, optional | |
| `backfilled` | bool, optional | written after the fact from the doc (summary level only) |

## Rules

- Log the idea **before** reading its holdout, and log it even when it fails. A failed idea still counts in its family.
- One holdout read per idea (`holdout_read: true` is final: a second look needs a new entry and a new holdout).
- `variants_tried` counts every version run, including the ones not reported.
- Backfilled entries (h1-h47, R1-R15, X1-X3) are summaries of the docs; where a doc did not say how many versions it ran
  the count is null and the family total is a lower bound.
- Entries named `app-lab-*` are written by `research/knowledge/import_app_lessons.py` from the phone's own strategy lab (13 Oct
  2026): one per idea family it tested, `variants_tried` = its lab tests, refreshed on each import. They count in their family.
