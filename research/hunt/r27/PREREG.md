# R27 pre-registration: the NIFTY zero-to-hero hunt (new signal families, an ML ranker, and Boss's late range breakout)

Written 14 Oct 2026, BEFORE any R27 outcome was computed. Only the feature builder was timed on one day without labels
(feature summaries, no outcomes). Committed and pushed on its own; nothing here changes afterwards.

Boss (14 Oct): "take nifty data, use the brain framework, use internet, and all the resources you can, and find how can
we catch the zero to hero", and, mid-way, a simple "expiry day special" rule (the 14:30-14:45 range breakout).

## What is already known (and why R27 tries different things)

- R21 / R25 / R26: 0 of 28 hero rules pass. Cheap options lose 50-90% of stake on average; breakouts, momentum, straddle
  expansion and OI-wall unwinding don't beat random timing; profits sit on a handful of days.
- Literature (web review, 14 Oct; sources in the write-up): 0DTE options carry the largest variance risk premium, so
  buyers lose on average (SEBI FY22-24 study; Agarwal et al. 2025 on NSE retail; Beckmeyer et al.; Vilkov; Dim, Eraker,
  Vilkov). The one conditional lead: buy only when the chain prices the remaining move BELOW a fair forecast of realized
  volatility (expiry-day last-30-minute variance is about +53% higher than other days in an Indian replication). Other
  leads: intraday "rest-of-day" momentum (Baltussen et al. 2021; Gao et al. 2018) or its reversal on Indian expiry
  afternoons; dealer gamma (Barbon & Buraschi).

## Data

- NIFTY weekly expiries in the Dhan lake (R26's day files, R25's loader): **271 expiries, 5 Aug 2021 – 6 Oct 2026**.
  13 Oct 2026 is not in the lake.
- **No virgin holdout exists**: every NIFTY expiry was read by R25 / R26 (and the brain's holdout block from 27 Apr 2026
  was read by R24 / R25 for this family). So R27 uses:
  - an **anchored walk-forward** for every rule with a choice in it: test years 2023, 2024, 2025, 2026 (to Oct), each
    chosen and fitted on NIFTY expiries before that year only (training starts Aug 2021);
  - **all 271 expiries** for Boss's K rules (nothing fitted);
  - a **forward shadow log** from 14 Oct 2026 as the only fully clean test (plan in the write-up).
  - Honest caveat: R25's anatomy saw the old features (Rs 1-10, design years) against outcomes. The new families (fair
    value, gamma, momentum since the previous close) were never looked at against outcomes.

## Decision rows and features (`r27feat.py`)

- Every 5 minutes, bar closes 12:00 .. 14:55; every eligible OTM option priced **Rs 1-30** (R25's eligibility: traded in
  the last 5 bars, the stale-slack rule).
- Features read rows ≤ t of the day; the volatility seasonality reads EARLIER expiry days only. Look-ahead guard tests
  (future blanked / future absurd) must give identical features.
- R25's 28 anatomy features, plus:
  - **VOL** (via `research/brain/bs.py`): realized vol so far (day, last 60, last 15 minutes); the forecast vol to 15:30 =
    last-60 per-minute variance × the seasonal ratio (mean remaining / mean last-60 variance at that minute on earlier
    expiries; 1.0 with < 10 earlier days); our own BS implied vol of the option; **iv_rv = implied / forecast**; the price
    over its BS value at the forecast and at India VIX.
  - **GAMMA**: OI × BS gamma per stored strike (both sides counted short for writers): the pin strike, spot's distance
    from it toward the option in expected moves, the call-minus-put gamma share, gamma per contract.
  - **MOMENTUM**: first-30-minute return, return since 14:00, **return since the previous close (rod)**, all signed toward
    the option.
- Labels (hindsight, never features): the trade at Rs 2,000 with R26's engine (R25 costs and fills) held to 15:05 (E1)
  and with Boss's half at 2× + step lock (E3); its confirmed peak multiple.

## Rules (one trade per expiry; size Rs 2,000, whole lots, at least 1; R25 costs and fills)

| id | rule | choice made on the training years only |
|---|---|---|
| M1 | **ML ranker**: sklearn HistGradientBoostingRegressor (max_iter 300, lr 0.05, 15 leaves, min leaf 200, l2 1.0, seed 27) on net per Rs 1,000 with E1 (target capped at its training 99.9th percentile). At each decision time, the row with the highest prediction; the first time it exceeds τ | τ ∈ {0, 100, 250, 500}: inner split, fit on the training years before the last, pick τ on the last training year (≥ 10 trades, best per Rs 1,000; else 500) |
| M2 | as M1 with exit E3 (target = E3's net per Rs 1,000) | as M1 |
| F1 | **fair value**: the first decision time with an option whose iv_rv ≤ θ; buy the one with the lowest iv_rv | θ ∈ {0.75, 0.85, 1.0} × exit {E1, E3} |
| G1 | **gamma + momentum**: a Rs 15-30 option with spot moving away from the pin toward it (pin_dist ≥ a expected moves) and 15-minute move toward it ≥ b %; nearest the money | a ∈ {0.25, 0.5, 1.0}, b ∈ {0.05, 0.10, 0.20} × exit |
| Q1 | **rest-of-day momentum**: at the 14:30 close, if the return since the previous close ≥ k × the day's realized σ so far (rv_day / √252), buy the nearest Rs 15-30 option in that direction | k ∈ {0.5, 1.0, 1.5} × exit |
| Q2 | **rest-of-day reversal**: as Q1, the opposite side | as Q1 |
| K1 | **Boss's late range breakout**, window 1: high / low of 14:30-14:45; the first 1-minute close beyond it by 15:00 buys ATM+1 CE (up) / ATM-1 PE (down); hold to 15:25 | nothing |
| K2 | window 2: range 14:45-15:00, break by 15:15, hold to 15:25 | nothing |
| K3 | window 3: range 15:00-15:15, break by 15:20, hold to 15:25 | nothing |
| K1e | K1 with the app's 15:05 square-off (the only window the app can trade today) | nothing |
| KS | K1-K3 together (up to 3 trades a day) with a stop: an index close back beyond the other side of the range sells at the next open | nothing |

- Selection for F1 / G1 / Q1 / Q2: the best net per Rs 1,000 among (threshold, exit) with ≥ max(10, 10% of the training
  expiries) trades; ties to fewer trades; none → the strictest threshold with E1.
- The K rules' 15:25 exit is research only: the app squares off expiry positions at 15:05 (CLAUDE.md market times).
  Note the closing-auction settlement from 3 Aug 2026 (about 10 expiries in the data); years are reported separately.

## Controls, family, gates

- **Random twins (1,000 sets):** M / F / G / Q: each trade replaced by a random eligible row (same band: Rs 1-30, or Rs
  15-30 for G / Q) at a random decision time of the same day, same exit. K: the same direction at a random minute of the
  same break window, same strike rule, size and exit (KS keeps its stop). p = share of twin sets with net ≥ the rule's.
- **BH family: R21 9 + R25 9 + R26 10 + R27 11 = 39.**
- **Gates (all must hold):**
  1. ≥ 30 trades (walk-forward test years for M / F / G / Q; all expiries for K).
  2. Net > 0 after costs.
  3. M / F / G / Q: ≥ 3 of the 4 test years positive. K: both R25 blocks (2021-22, 2023-26) positive.
  4. BH q < 0.05.
  5. Net > 0 at 2× the half-spread.
  6. Net > 0 without the best day.
  7. Max drawdown smaller than Rs 40,000.
- Also reported: hit rate, ≥ 2× / 4× / 10× shares, per Rs 1,000, per year, by price band, and a descriptive table of mean
  net per Rs 1,000 by price band × iv_rv bucket over every row.

## If a rule passes

Paper only, after Boss's go-ahead: a labelled, logged, one-tap-undoable paper arm (CLAUDE.md). Nothing live. If nothing
passes: no app change; the forward shadow plan in the write-up.
