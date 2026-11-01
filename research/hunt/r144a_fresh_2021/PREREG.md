# R144a fresh test: the best skip and exit rules on Nov 2021 - Nov 2022, a year no study has used

Written 1 Nov 2026, before any number from this period. Research only: **no app or arm code changes**.

Boss, 1 Nov, after R144a: *"Can we find any way to skip this trades"*, then chose option 2: one pre-registered test on
the only period no study has used.

## Why this period

- R139-R144a used 1 Dec 2022 - 13 Oct 2026 (the unseen two years, design and final). Every one of those months has now
  been read, so any new rule found there cannot be proven there.
- The lake also holds **1 Nov 2021 - 30 Nov 2022**: index minutes from Aug 2021, weekly option minutes for NIFTY and
  BANKNIFTY from Aug 2021, FINNIFTY from late 2021, MIDCPNIFTY from Jan 2022. **SENSEX has no options before May 2023,
  so it has no trades here.** No R139-R144a study built a store for it or read a trade in it.
  - Aug - Oct 2021 is warm-up only (indicators, no trade).
  - The arms were built after older studies that may have seen some of these months. What is new: **no rule below has
    ever been run on them.**

## The rules, frozen exactly as found (no cut is re-fitted)

| id | arm | rule | frozen cut | record so far |
|---|---|---|---|---|
| **FB-R72-A10** (R140) | R72 | skip entries when yesterday's range / the 20-session average daily range >= the cut | 1.3206 | passed R140; cut losses in all 5 periods (R143) |
| **NP5-52** (R142) | R52 | sell at fill + 5 minutes if the option's minute highs have not reached the entry open + 5% | 5 min, +5% | passed R142; cut losses in all 5 periods (R143) |
| **BBX-72** (R142) | R72 | skip after an earlier fake poke today, or push A1 < cut, or signal-bar range A7 < cut | A1 < 1.8094, A7 < 1.0673 | failed vs random (R142), cut losses on the unseen years (R143, reported only) |
| **BBX-51** (R142) | R51 | as BBX-72 with R51's cuts | A1 < 1.6274, A7 < 0.9947 | failed vs random (R142, R143) |
| **R144A-72-V4** | R72 | skip when yesterday's index-futures volume / its 20-day average is in the design top fifth | >= 1.3162 | p < 0.001 on the unseen years, failed the final (R144a) |
| **R144A-51-F4** | R51 | skip when yesterday's index futures showed short covering against the trade | category | p < 0.001 on the unseen years, failed the final (R144a) |

**What a result can mean (fixed now):**
- **FB-R72-A10 and NP5-52** are already proven loss-cutters. Passing here is a sixth period of confirmation; failing
  here is reported as a warning against switching them on.
- **The other four already failed somewhere.** Passing here can NOT make them adoptable: it only moves them to "mixed
  record, worth a forward shadow log". Failing here closes them.

## Engine and data

- R115's engine and R101's arm rules, unchanged; R51's clean store rebuilt in its own folder
  (`$R51_OUT/r144a_fresh`) for 2 Aug 2021 - 31 Dec 2022 from the same Drive lake; each date's own lot size; dated
  charges (STT 0.05% before Apr 2023) and R17 fills; 1 lot.
- **Engine check first:** the fresh store must give exactly R143's old-store trades on 1 - 30 Dec 2022 for all three
  arms (both stores cover it). If it does not, nothing is reported as a result.
- Expiry days come from the data, as in R143 (weekly expiries were on Thursdays then; the arms' own rules apply).
- Features for V4 / F4: R144a's `r144a_feat.daily_context` (previous session's daily futures only). BBX features:
  R140 Amendment A's, from the fresh store.

## Gates (R143's lose-less gates, on 1 Nov 2021 - 30 Nov 2022)

1. the sum of losing days is smaller than today's rules;
2. the max drawdown, worst day and worst month are no worse;
3. if today's arm made money in this period, the rule still does;
4. it beats 2,000 random twins on the sum of losing days, p <= 0.05 (filters: drop as many candidates at random; the
   exit: sell at a random minute between the fill and today's exit);
5. BH q <= 0.10 over these 6 tests;
6. >= 40 trades and >= 30 that differ.

**Reported, not gated:** the two halves (Nov 2021 - May 2022, Jun - Nov 2022), the win rate against Boss's 65% rule,
trades, net. Raw p-values go to `results/pvalues.json`.

## Outputs

`r144a_fresh.py`, `test_r144a_fresh.py`, `results/` (small JSON), a section in
`research/HUNT_R144A_LOSS_BREAKOUT.md`, entries `research/experiments/entries/r144a-fresh-*.yaml`.
