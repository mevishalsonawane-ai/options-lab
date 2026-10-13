# India Data Brain v1 (B1): every India index, first models, one refresh command

Written 14 Oct 2026. Boss (14 Oct, night): "Finish all the work fast, we need to train our brain."
Pre-registration: `research/brain/models/PREREG.md` (committed before any model was fitted). Code: `research/brain/`
(adapters, cube, features, labels, `holdout.py`, `models/train.py`, `refresh.py`). Every number below:
`research/brain/models/metrics_v1.json`. Coverage: `research/brain/COVERAGE.md`. Experiment entry:
`research/experiments/entries/b1.yaml`. **Nothing here trades: the models only inform and rank.**

## Verdict (read this first)

**The brain can tell how BIG the next 30-60 minutes will be, on every India index. It cannot tell what KIND of day it
will be.** This repeats R16 on more data and four more indices; the locked holdout (from 27 Apr 2026) was NOT read.

| question | verdict (design walk-forward, 24 tests, one BH family) |
|---|---|
| size of the next 30 min (all 6 indices) | **predictable**: error 16.5-22.6% smaller than the best plain baseline, q 0.001 each |
| size of the next 60 min (all 6 indices) | **predictable**: 10.6-21.1% smaller, q 0.001 each |
| trend day vs range day, decided at 09:45 | **not predictable**: AUC 0.483-0.510 (0.5 = coin) on 718-1,773 sessions |
| busy vs quiet rest of day, decided at 09:45 | **not predictable as pre-registered**: the boosted model ranks days (AUC 0.549-0.689) but is over-confident: its log-loss is worse than the base rate on 5 of 6, and on MIDCPNIFTY better but not significantly (q 0.55) |

Direction was not modelled: R16 showed it is not predictable.

## Data and coverage

| source | what the brain used | span |
|---|---|---|
| Dhan lake on Google Drive `gdrive:IraAlgo-data-lake` (download of 12 Oct 2026, partly uploaded) | 1-minute index bars of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX and India VIX; NIFTY weekly options ATM-10..+10 (rolling offset, OI, Dhan IV); 1-minute futures of the contracts live on 12 Oct | NIFTY 3 Apr 2017 - 12 Oct 2026 (options and VIX from 4 Aug 2021); the others 4 Aug 2021 (MIDCPNIFTY 31 Jan 2022) - 12 Oct 2026; futures minutes from 29 Jul 2026 only |
| research-data release (GitHub) | BANKNIFTY 1-minute index + nearest-expiry chain + VIX + continuous future | 13 Feb 2024 - 23 Feb 2026 (498 sessions, the only BANKNIFTY chain) |

Cube days per index (design / embargo / holdout): NIFTY 2,354 (2,230 / 8 / 116), BANKNIFTY 1,283 (1,159 / 8 / 116),
FINNIFTY 1,283, SENSEX 1,283, BANKEX 1,282 (1,158 / 8 / 116), MIDCPNIFTY 1,162 (1,038 / 8 / 116). Days with an option
chain: NIFTY 1,285, BANKNIFTY 498, the other four 0.

**Missing (not on Drive yet, or not usable):** BANKNIFTY options outside the release span (the lake's
`opt_BANKNIFTY_WEEK` files are not uploaded); FINNIFTY, MIDCPNIFTY, SENSEX and BANKEX options; constituent minutes
(`equity_1m`, so breadth stays "pending data"); NSE participant OI (the order-flow session has not put it on Drive);
futures minutes before mid-2026 (Dhan keeps none for expired contracts), so volume, VWAP and profile features exist only
on about 50 recent days (BANKNIFTY also has the release's continuous future, which has no volume). MCX futures minutes
are on Drive (live contracts, about May-Oct 2026) but the cube is the NSE 09:15-15:29 grid, so MCX is not in this brain.

## Splits

Design = sessions up to 13 Apr 2026. Embargo 14-26 Apr (8 sessions). **Locked holdout from 27 Apr 2026 (116 sessions
per index by 12 Oct), never read here**: no label is written for it and every training frame is refused if it reaches
past 13 Apr (`holdout.assert_design`). Within design: 10 equal blocks of sessions, train on all earlier blocks, test
on each of the last 8 (purged by the label window, 1-day embargo), every model and baseline refitted per fold.

## a) Size of the next 30 / 60 minutes: PREDICTABLE on every index

Target: log of the high-low range of the next 30 or 60 minutes (% of the close), decided every 15 minutes 09:45-14:45
(60 min: to 14:15). Model: gradient-boosted trees on all 154 point-in-time features. Baselines: the usual range at
this clock time (previous 20 sessions), the last 30/60-minute range, and VIX-implied (fitted per fold; NIFTY before
Aug 2021 has no VIX, where it falls back to time of day). Error = mean absolute error of the log range (0.30 means
typically off by a factor of about 1.35). Skill = 1 - model error / best baseline error.

| index | target | rows / sessions | design span tested | model error (MAE, log) | time of day | last window | VIX | best baseline | skill vs best | linear skill | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | next 30 min | 37,150 / 1775 | 2019-02-04 .. 2026-04-13 | **0.289** | 0.371 | 0.383 | 0.366 | VIX | **21.0%** | 13.6% | 0.001 | predictable |
| NIFTY | next 60 min | 30,044 / 1772 | 2019-02-04 .. 2026-04-13 | **0.297** | 0.374 | 0.400 | 0.365 | VIX | **18.6%** | 10.8% | 0.001 | predictable |
| BANKNIFTY | next 30 min | 18,763 / 897 | 2022-08-25 .. 2026-04-13 | **0.301** | 0.367 | 0.385 | 0.369 | time of day | **18.1%** | 14.5% | 0.001 | predictable |
| BANKNIFTY | next 60 min | 15,176 / 894 | 2022-08-25 .. 2026-04-13 | **0.321** | 0.375 | 0.401 | 0.370 | VIX | **13.2%** | 12.3% | 0.001 | predictable |
| FINNIFTY | next 30 min | 18,967 / 907 | 2022-08-10 .. 2026-04-13 | **0.303** | 0.367 | 0.387 | 0.370 | time of day | **17.2%** | 13.9% | 0.001 | predictable |
| FINNIFTY | next 60 min | 15,199 / 896 | 2022-08-23 .. 2026-04-13 | **0.318** | 0.373 | 0.402 | 0.367 | VIX | **13.5%** | 12.1% | 0.001 | predictable |
| MIDCPNIFTY | next 30 min | 17,148 / 822 | 2022-12-15 .. 2026-04-13 | **0.300** | 0.388 | 0.397 | 0.407 | time of day | **22.6%** | 16.5% | 0.001 | predictable |
| MIDCPNIFTY | next 60 min | 13,830 / 819 | 2022-12-15 .. 2026-04-13 | **0.311** | 0.395 | 0.425 | 0.404 | time of day | **21.1%** | 14.7% | 0.001 | predictable |
| SENSEX | next 30 min | 19,041 / 918 | 2022-07-25 .. 2026-04-13 | **0.305** | 0.369 | 0.394 | 0.370 | time of day | **17.2%** | 13.4% | 0.001 | predictable |
| SENSEX | next 60 min | 15,276 / 913 | 2022-07-26 .. 2026-04-13 | **0.316** | 0.373 | 0.406 | 0.367 | VIX | **14.0%** | 11.5% | 0.001 | predictable |
| BANKEX | next 30 min | 18,537 / 893 | 2022-09-01 .. 2026-04-13 | **0.304** | 0.364 | 0.394 | 0.367 | time of day | **16.5%** | 13.1% | 0.001 | predictable |
| BANKEX | next 60 min | 14,920 / 890 | 2022-09-01 .. 2026-04-13 | **0.329** | 0.372 | 0.408 | 0.368 | VIX | **10.6%** | 11.2% | 0.001 | predictable |

- The model forecasts the **median** move: in its forecast deciles (12 tests x 10 deciles), the median of actual /
  forecast is 0.87-1.05, the 10th percentile 0.52-0.68 and the 90th 1.56-1.87. So the error band is still about
  -40% / +70%: useful for sizing stops and targets, not exact.
- It separates quiet from busy half-hours: NIFTY's lowest forecast decile averages a 0.13% move in the next 30 minutes
  (3,715 rows), the highest 0.60% (3,715 rows), about 4.6x.
- The plain 4-input linear formula keeps most of it (10.8-16.5% skill); the trees add -0.6 to +7.8 points (on
  BANKEX 60 min the linear formula is slightly better).
- Design skill here (10.6-22.6%) is in line with R16 (BANKNIFTY / NIFTY design 15-19%, holdout 15-20%).

## b) Day type and regime at 09:45: not predictable

Day type: trend day = efficiency of the rest of the day |close(15:29) - close(09:45)| / rest-of-day range >= 0.5 (R16
test c). Regime: busy = the rest-of-day range above the median of the previous 60 sessions. Bar: AUC >= 0.58,
log-loss below every baseline, BH q <= 0.05.

| index | target | sessions | base rate | AUC model | AUC linear | AUC VIX | AUC yesterday | log-loss model | linear | base rate | VIX | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | day type (trend day) | 1773 | 0.49 | 0.507 | 0.500 | 0.500 | 0.487 | 0.766 | 0.704 | 0.693 | 0.694 | 1.0 | not predictable |
| NIFTY | regime (busy rest of day) | 1749 | 0.50 | 0.649 | 0.627 | 0.516 | 0.586 | 0.710 | 0.703 | 0.694 | 0.703 | 1.0 | not predictable |
| BANKNIFTY | day type (trend day) | 895 | 0.46 | 0.503 | 0.491 | 0.483 | 0.497 | 0.785 | 0.705 | 0.690 | 0.692 | 1.0 | not predictable |
| BANKNIFTY | regime (busy rest of day) | 871 | 0.50 | 0.599 | 0.613 | 0.579 | 0.594 | 0.731 | 0.675 | 0.695 | 0.691 | 1.0 | not predictable |
| FINNIFTY | day type (trend day) | 888 | 0.47 | 0.510 | 0.479 | 0.478 | 0.480 | 0.771 | 0.703 | 0.692 | 0.693 | 1.0 | not predictable |
| FINNIFTY | regime (busy rest of day) | 864 | 0.50 | 0.639 | 0.619 | 0.579 | 0.576 | 0.697 | 0.678 | 0.694 | 0.697 | 0.9938 | not predictable |
| MIDCPNIFTY | day type (trend day) | 798 | 0.52 | 0.483 | 0.477 | 0.468 | 0.533 | 0.785 | 0.705 | 0.695 | 0.696 | 1.0 | not predictable |
| MIDCPNIFTY | regime (busy rest of day) | 774 | 0.52 | 0.689 | 0.666 | 0.557 | 0.585 | 0.686 | 0.644 | 0.698 | 0.705 | 0.5545 | not predictable |
| SENSEX | day type (trend day) | 734 | 0.48 | 0.484 | 0.500 | 0.483 | 0.479 | 0.802 | 0.705 | 0.693 | 0.693 | 1.0 | not predictable |
| SENSEX | regime (busy rest of day) | 676 | 0.52 | 0.589 | 0.615 | 0.563 | 0.563 | 0.737 | 0.671 | 0.696 | 0.687 | 1.0 | not predictable |
| BANKEX | day type (trend day) | 718 | 0.45 | 0.510 | 0.447 | 0.466 | 0.505 | 0.766 | 0.696 | 0.689 | 0.691 | 1.0 | not predictable |
| BANKEX | regime (busy rest of day) | 669 | 0.51 | 0.549 | 0.575 | 0.571 | 0.572 | 0.759 | 0.684 | 0.696 | 0.686 | 1.0 | not predictable |

- **Day type is a coin** on every index (AUC 0.483-0.510); yesterday's day type is no better.
- **Regime ranks but is not calibrated.** The boosted model's AUC is 0.549-0.689 and the observed rate rises across
  its deciles (NIFTY: 30% busy in the lowest tenth, 76% in the highest, 175 sessions each), but it forecasts 11% and
  93% there, so its log-loss is worse than just saying "50%" (5 of 6 indices; MIDCPNIFTY 0.686 vs 0.698, p 0.30,
  q 0.55). The pre-registered test fails. The linear model's
  log-loss is below the base rate on 5 of 6 indices (not NIFTY) and its AUC is 0.575-0.666: a candidate for a NEW
  pre-registered test, not a finding. This is an open question in the knowledge pack.

## Honest notes

1. **A units bug was fixed before reporting.** The first full run compared the target (log of a %) with the
   time-of-day baseline in log of a fraction (off by log 100 = 4.6), which made that baseline look useless and changed
   the "best baseline" for some tests. It was fixed in `models/train.py` (+ log 100) and the whole run was redone once
   with no setting, target, model or bar changed. A test now checks every baseline is on the target's scale. The
   numbers above are from the corrected run.
2. Design data of NIFTY and BANKNIFTY overlaps the sessions R16 and R18 examined (including R16's holdout). These are
   design numbers; only the fresh holdout from 27 Apr 2026 can confirm them, read once, later.
3. The four index-only indices have no option features; SENSEX and BANKEX have fewer day-type rows (734, 718) because
   their 15:29 bar is often missing (SENSEX: 72 of 300 sessions checked, 2023-2025); rest-of-day labels then stay
   NaN, never filled.

## Using it and refreshing it

- Model files (one per index x target, fitted on all design rows, with their feature lists) are on Google Drive
  `gdrive:IraAlgo-data-lake/brain_models/v1/` (24 files) and in `<cache>/models/brain_v1/`, never in git.
- **Refresh when the lake is complete:** `python -m research.brain.refresh --source lake --upload --pack`. It syncs
  only what the brain reads from Drive, rebuilds the days whose source changed (BANKNIFTY moves from the release to the
  lake once its options land), recomputes features and labels, rewrites COVERAGE.md, refits the models and the metrics,
  uploads the models and rebuilds the knowledge pack. `--no-sync` builds from the cache only.
- Next step (not done here, by design): read the locked holdout once (>= 100 sessions per index are already there),
  in its own step with its own entry, for the 12 range tests and nothing else.
