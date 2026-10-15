# R61 "Solo 3.0": teaching Solo what OI, IV and gamma are, and testing whether that knowledge helps (15 Oct 2026)

Boss, 15 Oct: "Make Solo more advanced, intelligent and smart; it should understand each and every thing - what OI is,
what gamma is, etc., their features, why and how they are used - so it can think on its own and trade." Solo is
Jarvis's own trader and stays **paper only, forever**. Research only: nothing in the app changed (no `android/**`, no
`.github/**`, no knowledge-pack rebuild). Code: `research/hunt/r61/`.

## Status: Part 1 done; Part 2 pre-registered and built, NOT run yet

- **Part 1 (the encyclopedia) is finished:** `hunt/r61/CONCEPTS.md` (plain English) and `hunt/r61/concepts.json`
  (for the app to turn into Jarvis's knowledge). It covers 25 concepts. Each entry gives:
  - what the concept is;
  - its exact formula and whether the phone can compute it;
  - how traders use it, and its pitfalls;
  - what OUR studies found, with the study, the sample and the verdict.
- **Part 2 (the Solo 3.0 model) is pre-registered, coded and unit-tested, but has not been run.**
  - Pre-registration: `hunt/r61/PREREG.md`, commit 701b8c18, written before any data was read.
  - Downloading the data lake (`rclone` from `gdrive:IraAlgo-data-lake`) was blocked by this session's permission
    check, so there is **no R61 model score, P&L or holdout number yet**.
  - Nothing below should be read as a result. The 7 experiment entries (`r61-v1` .. `r61-v7`) are logged as
    "inconclusive, not run, holdout not read".
- **So: Solo 2.0 (A2) stays.** Nothing has shown that Solo 3.0 beats it.

## Part 1 in one table: what each concept is worth, by our own research

Full details, formulas and sources are in `hunt/r61/CONCEPTS.md`. "Size" means the concept predicts how much the index
may move, not which way.

| concept | best evidence (study, sample) | verdict |
|---|---|---|
| OI level / change | OI_FILTER (145 arm x check results), H26 (2,268 variants; BU15 filter +Rs 81/day on 876 trades, p 0.035, not adopted) | failed (one near-miss filter) |
| OI build-up types | IV1 O3 (7,339 trades, lost on all 5 indices), H38 (1,080 + 1,800 variants) | failed |
| PCR (OI, volume) | R47 C4 (BANKNIFTY, 91 holdout trades, p 0.0499, q 0.50; faded to +9 a trade in 2025), R15 F3 | near-miss, non-default signal only |
| Max pain | R16 (forecast error 0.74% vs 0.43% for "no change"), EXPIRY_PLAYBOOK (64 expiries) | failed (folklore) |
| OI walls | OF1 (873 expiries, 0 of 12), R20b (57 holdout trades), R26 (770 expiries) | failed |
| ATM IV | VOLATILITY (249 days: range rises with IV), H29 (16 variants, best -Rs 30/day) | size only |
| IV rank / percentile | F1 S18 (158 trades, q 0.28, lost in design), OBUY_GC | failed (size only) |
| IV skew / smile | F1 (consistent but tiny direction clue), H29 (24 variants lose), R30 M4 (77 trades, q 0.109) | weak lead |
| IV term structure | H29 ("did not sort the move at all") | failed |
| India VIX | R15 F2 (228 trades, skipping low VIX cost -Rs 73/day), SIDEWAYS_GUARD, R16 | size only |
| Delta, gamma, theta, vega | F1 feature screen (gamma / theta size IC +0.15 / +0.10; delta / vega ~0 for direction) | context only; never a rule |
| GEX and gamma flip | R28 (0 of 10 filters), R36 (264 expiries, -Rs 1.04 lakh), R37 (42 trades, worse than 99.4% of random) | size only; failed as a filter |
| Dealer positioning | OF1 (3,632 days: 15 of 36 passed 2012-2025, 0 of 15 held in the last year) | failed |
| VWAP | TREND_FILTER, H46 (2,400 variants), F1 C1/C2 (Rs 2.5-3.3 lakh losses) | failed |
| ATR | F1 (strongest size predictor, IC +0.335) | size only |
| Opening range | R3 (2,489 trades, -Rs 2.23 lakh, 0 of 16), R49, R57 S4 (459 trades, -Rs 1.14 lakh) | failed |
| Value area / profile | R57 S3 (232 final trades, +Rs 49,360 but not fresh), R9 (0 of 56), H37 | one candidate setup; otherwise failed |
| Trend / range regime | SIDEWAYS_GUARD (35 tests), R16 (AUC 0.46-0.52) | failed |
| FII/DII, participant OI | OF1, H40 (9,600 variants, 0 pass) | failed |
| Futures basis | H38 (design pass, holdout -Rs 35/day) | failed |
| Rollover | H40 S10 | failed / barely tested; not on the phone |
| Event days | EVENT_SHIELD (BANKNIFTY RBI + Budget range 1.44% vs 0.98%, 15 sessions, holdout confirmed), H28 | size warning passed; no trade edge |

What this means for Solo: every one of these is used only as **context**. It can make Solo skip a dear, crowded or
risky trade, or prefer one index's candidate over another's. It never creates a trade and never picks the side.
Solo can now say *why* in plain English, for example "skipped: IV is in the top fifth of its 60-day range and our IV
studies say dear options lose". That is the "understanding" Boss asked for. Whether it also earns money is what Part 2
tests.

## Part 2: what will be tested (pre-registered; nothing run)

**Question.** Solo 2.0 (A2) looks at each day's candidate trades from four index setups and decides take or skip,
using 29 inputs. Does adding 30 more inputs (one or more from each concept above) pick the trades better on unseen
data?

**Setups (unchanged from R57).** Every signal comes from the index:

| id | setup | signal source |
|---|---|---|
| S1 | SoloMidday | index 1-min |
| S2 | Donchian-20 | index 5-min |
| S3 | open outside value and go | index 5-min + the previous day's profile |
| S4 | 15-min opening-range break | index 5-min |

C4 (the PCR signal) is not used.

**Labels and costs.** As R57:
- the held contract's own net P&L;
- real charges (STT 0.15% from 1 Apr 2026) and R17 fills;
- foreign rows and bad ticks removed;
- WEEK files only, so the same contract is never counted twice;
- Solo never trades an index on its own expiry day;
- only 20 Nov 2024 onwards.

**New inputs (all known at the signal minute).** All are context; how the phone gets each one is listed:

| block | inputs | how the phone gets them |
|---|---|---|
| CH, option chain (19) | near-ATM OI and its change, writer tilt, build-up code, PCR change, volume PCR, PCR 5- and 20-session ranks, max-pain distance, OI wall ahead / behind, IV rank (60 sessions), IV change today, smile curvature, term slope (NIFTY only), GEX net/gross and sign, gamma-flip distance, synthetic futures basis | live chain (`ChainAnalytics`); the ranks need 20-60 sessions of the phone's own stored history |
| GK, Greeks of the contract Solo would buy (4) | delta, gamma per 1%, theta per session as % of premium, vega as % of premium | from the contract's own IV |
| CX, context (7) | VIX 60-session rank, position in yesterday's value area, RBI / Budget day, the session after a US FOMC / CPI release, another index's expiry today, FII index-futures long share and its change (previous evening's NSE file) | the FOMC / CPI dates would need a small static list on the phone; the participant file is recorded daily |

**Not used as inputs, and why:**
- real futures basis and futures build-up: futures minutes exist only from Jul 2026;
- rollover: not built, and not on the phone;
- DII cash flows: not reachable;
- 25-delta skew: the ±3-strike skew is used instead;
- India CPI: not in a pre-listed calendar.

**Seven variants, one BH family.**

| id | model | inputs |
|---|---|---|
| V1 | A2 refitted (the comparator) | A2's 29 |
| V2 | logistic | everything (59) |
| V3 | logistic | A2 + Greeks + context, no chain |
| V4 | logistic | A2 + chain |
| V5 | depth-3 boosted trees | everything |
| V6 | depth-3 boosted trees | A2 + Greeks + context, no chain |
| V7 | sparse L1 logistic | everything; it picks its own concepts |

All use A2's take rule: the first candidate of the day scoring at least the training 70th percentile, then nothing
more that day.

**Splits and reports.**
- Design: 21 Nov 2024 - 31 Mar 2026, purged walk-forward with monthly refits and a 1-session embargo.
- Final: 1 Apr 2026 - latest, read once.
- Reports:
  - AUC: random split (leaky) vs walk-forward;
  - a drop-one-concept ablation and an add-one-feature test, so each concept's out-of-sample worth is measured;
  - trades, net, p, q, max drawdown, top-5 share, 2x costs, per index and per setup.

**Gates.** As R47-R60: final net > 0, twin p < 0.05, BH q ≤ 0.10, 40+ trades, top-5 < net, design walk-forward > 0.

**Replacing Solo 2.0.** Passing the gates is not enough.
- The candidate is the passing V2-V7 with the best DESIGN walk-forward net.
- It replaces A2 only if, on the same final days against V1, all four hold:
  - it makes more money;
  - it has a higher AUC;
  - a paired day bootstrap gives p < 0.10;
  - it makes more without MIDCPNIFTY (so it does not just lean harder on one index).

**Honest warning before any result.** The final window (Apr-Oct 2026) is not fresh. R57 already read it for A2 with
these same setups, and R47-R55 read parts of it. R61's final test can only say whether the extra inputs add anything
on a window where A2's result is known. Live paper from 15 Oct 2026 is the only truly unseen data.

## Running it on the phone (only if a variant ever replaces Solo 2.0)

- The model is exported as R57's small JSON, with a one-line meaning for every input. It comes with 25 golden vectors.
- `hunt/r61/r61eval.py` is a pure-Python reference with no numpy, file or network. It holds:
  - the scorer;
  - every chain formula (Black-Scholes Greeks, max pain, OI walls, PCR, build-up code, GEX, gamma flip, synthetic
    basis).
- Tests prove it matches the research code feature by feature, so a Kotlin port can be checked line by line.
- The phone's own `ChainAnalytics.gex` prices from the forward and takes its own rate. A port must use R61's exact
  definition or re-test.
- Calendar note: the app's EventCalendar has the Aug 2026 RBI policy on 6 Aug, but the research list says 7 Aug.
  R61 uses the research list. One of the two is wrong and should be checked on rbi.org.in.

## How to run it (once the lake download is approved)

```
rclone copy gdrive:IraAlgo-data-lake ~/.cache/iraalgo/lake --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,INDIAVIX}_*.parquet" \
    --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY}_WEEK_*.parquet" --include "opt_NIFTY_MONTH_*.parquet" --include "participants/oi/**"
python research/hunt/r61/r61data.py build && python research/hunt/r61/r61data.py context
python research/hunt/r61/r61study.py design      # writes results/design.json (design months only)
python research/hunt/r61/r61study.py dryrun      # the final pipeline on the last 3 design months (crash check)
python research/hunt/r61/r61study.py final       # the ONE final read (guard file; refuses a second)
python research/hunt/r61/r61concepts.py results.json   # writes each concept's R61 result into CONCEPTS.md / concepts.json
```

Then update this file and the `r61-v*` experiment entries with the numbers, and delete the lake copy and cache.

## Tests

`python -m pytest research/hunt/r61/test_r61.py -q`: 30 tests on synthetic data, run under pandas 3.0.5 / sklearn 1.9
and under the pinned pandas 2.2.3 / sklearn 1.5.2. They cover:
- the look-ahead guard for every chain feature (values at a minute are identical when later minutes are deleted);
- same-minute ranks that only look back;
- context built from earlier sessions only;
- snapshot carry-forward that never mixes two minutes;
- hand-checked max pain, OI walls, build-up codes and gamma flip;
- Greeks against finite differences;
- numpy vs pure-Python parity;
- signing by side;
- the event calendar against its research sources, including the next-session rule across holidays;
- purge and embargo;
- held-contract labels left untouched;
- the period guard and the one-read guard;
- the replace rule;
- scorer parity for all three model kinds;
- cleaning on a tiny parquet lake;
- edge cases: holidays, expiry day, missing chain minutes, no chain at all.

Signal source of every rule: S1-S4 read the underlying index. Every R61 input is context or a filter. No rule here
reads its signal from an option's own chart.
