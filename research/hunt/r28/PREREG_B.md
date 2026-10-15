# R28b pre-registration: the last 30 minutes of candles before each decision, with GEX, as a pattern filter

Written 14 Oct 2026, after R28 (`PREREG.md`, holdouts read) and BEFORE any R28b number was computed. It is committed and
pushed on its own. Nothing below changes after that commit. Research only: nothing in the app changes.

Boss (14 Oct 2026): "Now use last 30 min candles before testing this, and find a pattern from there". Asked to choose,
Boss picked:
- the 30 minutes right BEFORE each arm's decision (not the day's last 30 minutes);
- confirmation on FORWARD paper / shadow data, because every arm's locked holdout on the lake is spent (R28 read
  them today).

## What is different from R28

- R28 tested 10 fixed GEX filters, and none passed.
- R28b is a SEARCH: the shape of the 30 one-minute index candles before the decision, alone and together with the GEX
  sign. Because it searches, it is judged by:
  - walk-forward inside the design years only (a pattern is chosen on earlier years and scored on the next year);
  - BH across every pattern tried;
  - a forward test on new data that the lake does not contain.
- The R28 holdout blocks (Aug 2021 - Dec 2022 for family A; Jan 2024 - Mar 2025 for family H) are EXCLUDED from R28b,
  as are the embargoes. They are spent and cannot confirm anything.

## Data and trades

- The same arm rebuilds as R28 (`r28arms.py`, `<cache>/r28/trades_*.parquet`): ORB, Range Fade, Liquidity 15+5
  BANKNIFTY, Hero H0, lottery L3 and Night R3, each with its own fills and charges.
- The same GEX tables (`gex_<UND>.parquet`, near expiry, S1).
- Index minutes from the lake (`index_1m`).
- **The window:** the 30 one-minute bars ending at the arm's GEX row (R28's decision row; that bar closes at the
  decision time, so nothing after it is read). That is:
  - ORB and Range Fade: 09:30-09:59 bars (to 10:00);
  - Liquidity: the 30 bars before the entry;
  - Hero H0: the 30 bars before min(signal, 13:45);
  - lottery L3: 13:15-13:44 (to 13:45);
  - Night: 14:45-15:14 (to 15:15).
- Windows with fewer than 25 finite bars, or starting before 09:15, are "missing". Those trades are kept by every
  pattern and counted.

## The 7 candle features (side = +1 for a call / long, -1 for a put)

| id | feature |
|---|---|
| c_ret | side × (last close - first open) / ATR14 (the 30-minute move in the trade's direction) |
| c_rng | (window high - window low) / ATR14 |
| c_body | abs(last close - first open) / (high - low) of the window (0 = doji, 1 = all body) |
| c_loc | where the last close sits in the window, aligned: side > 0: (close - low) / (high - low); side < 0: (high - close) / (high - low) |
| c_wick | the wick against the trade / the window range: side > 0: (high - max(open, close)) / range; side < 0: (min(open, close) - low) / range |
| c_bars | the six 5-minute bars of the window that closed in the trade's direction (0 .. 6) |
| c_late | side × (last close - close 10 minutes earlier) / ATR14 (the last 10 minutes' push) |

ATR14 is the mean true range of the 14 sessions before the day (as R22).

## The patterns (per arm)

- **Bins:** each feature is cut into terciles: low, mid and high. The cut points are fitted on the TRAINING trades of
  each fold only. For the full-design BH table they are fitted on all design trades of that arm.
- **Single patterns:** keep only trades in one tercile of one feature. 7 × 3 = 21.
- **GEX pairs:** keep only trades in one tercile of one feature AND with S1 < 0 (or S1 >= 0) at the decision.
  7 × 3 × 2 = 42.
- **63 patterns per arm × 6 arms = 378 patterns.** This is the BH family m (q 0.10). R28's 162-test family is reported
  beside it.

## Walk-forward (design years only)

- **Family A** (ORB, Range Fade, Liquidity, Night). Folds:
  - train 2023 → test 2024;
  - train 2023-24 → test 2025;
  - train 2023-25 → test 2026 (to 12 Oct).
- **Family H** (Hero H0, L3). Folds (design years only; the Jan 2024 - Mar 2025 holdout and its embargoes are
  excluded):
  - train 2021-22 → test 2023;
  - train 2021-23 → test Apr 2025 - Oct 2026.
- **In each fold:**
  - Choose the ONE pattern with the highest change in P&L per training session (kept minus all). It must keep at least
    30 training trades (family A) or 10 (family H).
  - Then score that pattern on the test year: the change in P&L per test session.
- **The out-of-sample (OOS) result of an arm:** the chosen patterns' test-year trades joined across folds, vs the
  arm's unfiltered trades on the same test sessions.
- **The OOS twin:** skip the same number of index-days at random in each test year (R28's corrected `twin_p`, 2,000
  twins). The p-value is on the joined OOS change.

## Gates for a FORWARD candidate (all must hold)

1. The arm's OOS change per session > 0, and OOS twin p < 0.05.
2. The change is positive in at least 2 of 3 folds (family A), or in 2 of 2 (family H).
3. The same pattern is chosen in at least 2 folds (a stable pattern, not a new one every year).
4. Its full-design BH q < 0.10 among the 378 patterns.
5. At least 100 kept design trades (family A) or 30 (family H).

A pattern that passes all five is a forward candidate, nothing more.
- It is written into `research/hunt/r28/results/forward_candidates.json` with its exact definition and cut points,
  frozen at that commit.
- Its forward test, fixed now:
  - from the first session after the commit, on the app's paper / shadow trades of that arm (or a lake refresh after
    12 Oct 2026);
  - after 100 forward trades: the pattern's change in P&L per session > 0 and twin p < 0.10.
- Until then it changes nothing.
- Putting it on a paper arm needs Boss's go and the CLAUDE.md paper rules. Putting it in a shadow log in the app is a
  separate change: a switch that is off by default, logged only.

A pattern that fails any gate is a note. All 378 are listed in the results.

## Reporting

For each arm:
- the chosen pattern per fold and its training and test change;
- the joined OOS: trades base → kept, win %, average win and loss, P&L per session, % green days, worst day, max DD,
  big winners cut, missing windows, twin p;
- the full-design table of all 63 patterns with q;
- sample sizes everywhere.

Experiment entries: `r28b-<arm>.yaml` (variants_tried 63 each) and `r28b-search.yaml`. The write-up is a new section
of `research/HUNT_R28_GEX.md`.
