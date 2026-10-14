# B2 pre-registration: every feature at once (one model), why candles turn green or red, and can it trade

Written 14 Oct 2026, before any B2 model was fitted or any B2 target was read. Committed and pushed on its own. After this
commit nothing below changes; a change needs a new entry, a new pre-registration and a fresh holdout.

Boss (14 Oct 2026): "Add all the parameters / strategies / indicators at once, and find the pattern between them" ...
"deep research all the patterns, check why we get green candles / red candles". Earlier work tested indicators ONE AT A
TIME (F1: 11,672 single-feature tests, 201 features; R16 / the direction model: about 40 features; B1: 154 features but
only for move SIZE and day type, never direction). B2 puts **all features into one model at once**, so the model can use
any combination and interaction, and asks: which way, what colour, and would a bought option pay.

Research only. Nothing in the app changes.

## 1. Data

- **Feature rows**: F1's table (`gdrive:IraAlgo-data-lake/derived/f1_factory/featrows`, built by
  `research/hunt/f1/screen.py featrows` from the brain's feature store on the Dhan lake). One row per index per 5-minute
  decision (09:30 .. 14:30, rows 15..315), **201 point-in-time features** (every brain feature incl. F1's 47: EMAs, crosses
  and slopes, Supertrend, RSI, MACD, ADX / DI, Bollinger, Keltner, squeeze, ATR, realised vol, VWAP / anchored VWAP and
  bands, pivots, CPR, previous-day levels, opening ranges, candles, OI and OI build-up per strike, PCR, max pain, OI walls,
  ATM IV, IV rank / percentile, skew, straddle, GEX proxy, VIX, basis, gap, calendar) plus F1's 4 breadth features (58
  NIFTY / bank stocks). Indices: NIFTY, BANKNIFTY, FINNIFTY, SENSEX (MIDCPNIFTY has no usable chain, F1 amendment 2).
- **Outcomes**: F1's outcome table at the same rows (index returns 15 / 30 / 60 min; net Rs a lot of buying the ATM call /
  put with F1's exits `x1`: -15% stop, +Rs 1,000 -> keep Rs 500 lock and Rs 500 trail, 30-minute time stop, 15:10 / 15:05
  out, app charges, liq fills, today's lots), plus the index close at 15:29 and 09:15 open from the lake's `index_1m`.
- **Every period F1 used (design and its spent holdout) is B2 DESIGN**: 2021-08-01 .. 2022-12-14, 2023-01-01 .. 2024-06-30,
  2024-07-15 .. 2026-04-13.

## 2. Holdout (locked; read ONCE) and the forward test

- **Holdout: 27 Apr 2026 .. 12 Oct 2026, all four indices** (the brain's locked holdout; 14-26 Apr is its embargo).
  - No model was ever fitted or scored on it. B1 (the brain) left it unread; F1 never built rows for it.
  - Honest caveat: it is not unseen market data. R24 read NIFTY / BANKNIFTY option outcomes there (what-if patterns),
    and R3 read ORB-family option outcomes on FINNIFTY / SENSEX there. Neither modelled index direction or candle colour.
  - Its feature rows are built with F1's own code (the F1 branch, unchanged) only for the holdout read; B2's
    `load(part="holdout")` refuses without the read flag.
- **Forward test**: the models are fitted on design + holdout after the read, frozen (file hash recorded), and scored on
  sessions from 15 Oct 2026 on, once at least 40 new sessions exist. Nothing acts on them before that.

## 3. Targets (all decided AFTER the decision row; never features)

At every decision row t (09:30 .. 14:30, every 5 minutes), per index:
- **G15 / G30 / G60**: is the next 15 / 30 / 60-minute candle green (index close at t + h > close at t)? (the "green
  candle" questions; ret = 0 rows dropped)
- **GEOD**: does the index close the day (15:29) above its close at t?
- **CEW / PEW**: does buying the ATM call / put now make money after costs (net_x1 > 0)?
- **SIZE (positive control)**: is |ret30| above the training folds' median for that index? (Known to be predictable, R16 /
  B1 / F1. If B2 cannot find it, B2 is broken.)

Once per day per index:
- **DAY0930 / DAY1015**: is the day green (close 15:29 > open 09:15), decided at 09:30 / 10:15 with the features of that row?

## 4. Models (all features at once)

Inputs: the 201 + 4 features, the decision row (time of day), days to expiry, expiry flag and the index (one-hot). Fitted
pooled across the four indices.
- **LR**: logistic regression (L2, C 0.1), features median-imputed (training medians) and standardised.
- **GBM**: scikit-learn HistGradientBoostingClassifier (max_iter 300, learning_rate 0.05, max_leaf_nodes 31,
  min_samples_leaf 200, l2 0.1, early stopping on the last 10% of the training data by time) on raw features (NaN native).
  It captures combinations and interactions.
- **NN**: a neural network, scikit-learn MLPClassifier (two hidden layers 128 and 64, ReLU, Adam, alpha 1e-3, early
  stopping 10% by time, max 200 epochs) on the LR's imputed, standardised inputs.
- Baselines: the training base rate (AUC 0.5) and the sign of the last 15 minutes (for G15 / G30 / G60).

## 5. Walk-forward (design only)

Expanding window by test block; train on everything strictly before the block, with a 5-session gap:
- test 2023 (train 2021-08 .. 2022-12-14);
- test 2024 (2023-01 .. 2024-06-30 and 2024-07-15 .. 2024-12-31; train: everything before 2024);
- test 2025 (train: before 2025);
- test 2026 (1 Jan .. 13 Apr 2026; train: before 2026).

Metric: AUC (and log-loss vs the base rate). p-value: one-sided, AUC > 0.5, by day-block bootstrap (2,000 resamples of
days). Walk-forward AUC = all four test blocks pooled; also per index and per block.

## 6. "Why green / red" (descriptive, design only)

- **A. What comes before** (predictive): for GBM on G15, G30, G60, GEOD and DAY0930, permutation importance (AUC drop on the
  test blocks, 5 repeats) of every feature; the top 25. A depth-3 tree fitted to the GBM's walk-forward predictions turns
  the model into readable "if A and B then" rules; each leaf is reported with its test-block count and green rate.
- **B. What moves together with the candle** (concurrent, cannot be traded): for each feature, its change from t to t+15
  against the 15-minute candle's colour (AUC); and a GBM on all the changes at once (how much of a candle's colour the data
  "explains" while it forms).
- **C. Green vs red days**: share of green days by gap, previous day's colour and size, VIX level and change, weekday,
  expiry, month, the first 15 minutes' colour, and the opening range position; with counts.

## 7. Can it trade? (walk-forward and holdout)

One rule per target G15, G30, G60 and per option pair (CEW / PEW), using the GBM's probability:
- at each decision row, buy the ATM CE when p(up) is at or above the 90th percentile of that model's predictions on its
  own training data; buy the ATM PE when at or below the 10th; for CEW / PEW buy the side whose p(win) is at or above its
  90th percentile (the higher one if both). At most one entry per side per index per day (the first qualifying row).
- Money from F1's outcome table (`net_ce_atm_x1` / `net_pe_atm_x1`).
- Random twin: the same number of trades per index-day and side at random decision rows of that day, 1,000 twins, seed 2.

## 8. Gates

A **pattern** (target x model) is real only if:
- walk-forward AUC > 0.5 with BH q < 0.10 over the design family (8 targets x 3 models = 24), AND
- holdout AUC > 0.5 with day-bootstrap p < 0.05 and BH q < 0.10 over the holdout family (the same 24), AND
- the holdout AUC is at least 0.52 (anything smaller cannot pay an option's costs; F1 / R16).

A **trading rule** passes only if:
- walk-forward net > 0 after costs and > 0 in at least 3 of the 4 test blocks, AND
- holdout net > 0, beats its random twins (p < 0.05, BH over the 4 rules), and > 0 on at least 3 of the 4 indices.

A rule that passes may go on paper only (CLAUDE.md: logged, labelled, undoable in one tap, never live by itself).

## 9. Reports

`research/HUNT_B2_ALL_AT_ONCE.md` with "Verdict for Boss" first: AUC per target / model / block / index, the top
features and the readable rules, the green / red anatomy, the trading tables (trades, win rate, average win / loss,
net, per day, green days, worst day, max drawdown), sample sizes. Experiment entries `research/experiments/entries/b2-*.yaml`.
Tests `research/hunt/b2/test_b2.py` with the look-ahead guard (no feature is a future value; targets never enter X; the
walk-forward never trains on its test block).

---

## Amendment 1 (14 Oct 2026, before any B2 model is fitted or any target is looked at)

Boss (14 Oct): "all parameters include OI, VWAP, volume, EMA and all the variants, order flow and all the other things.
Try changing all the params from them too." So the model's inputs get a **parameter-sweep bank** on top of the 205
brain / F1 features (`b2sweep.py`), computed from the lake's index minutes, point in time (a 5- or 15-minute bar is used
only once it has closed by the decision minute), on three charts: **1-minute, 5-minute and 15-minute**:

- EMA distance (close vs EMA) for lengths 5, 8, 9, 13, 20, 21, 34, 50, 100, 200;
- EMA crosses (fast vs slow spread) for 5/13, 8/21, 9/20, 9/21, 12/26, 13/34, 20/50, 50/200, and the bars since each cross;
- RSI 7, 9, 14, 21; stochastic %K 5 and 14; Williams %R 14; CCI 20;
- MACD histogram (12, 26, 9), (8, 17, 9), (5, 35, 5);
- Bollinger %B and width (20, 2), (20, 2.5), (10, 1.5), (50, 2);
- Supertrend side and distance (7, 2), (10, 3), (14, 2), (20, 4);
- ATR % 7, 14, 28; ADX 7, 14, 28 (with DI spread);
- momentum (return over 1, 3, 5, 10, 20, 40 bars); Donchian position 20 and 55; distance from the session TWAP.

The index has no traded volume (Dhan stores 0). Volume, VWAP and order-flow inputs therefore come from the option chain
and the futures, as already in the brain / F1 features: option-volume VWAP and bands, anchored VWAPs, PCR (OI and
volume), OI build-up per strike (long / short build-up, short covering, long unwinding), writing bias, OI walls, max
pain, GEX proxy, futures basis / OI / volume (futures only from Jul 2026), and breadth. True order flow (bid / ask
queues, aggressor side) does not exist in the history; it exists only in the phone's recent recordings (OF1 follow-up),
so it cannot be in this model.

Every model in section 4 is fitted twice: on the 205 base features ("base") and on base + the sweep bank ("all"). Both
count in the BH families (section 8: 8 targets x 3 models x 2 input sets = 48 per family). The trading rules (section 7)
use the "all" GBM only (still 4 rules). Nothing else changes.

## Amendment 2 (14 Oct 2026, before any B2 model is fitted or any target is looked at)

Boss (14 Oct): "include all the other things ... like gamma, vertical volume etc. Add all you can think of." The "all"
input set (amendment 1) also gets, in `b2sweep.py`:
- **Vertical volume (volume profile) from the option chain** (the index itself has no volume): the day's option volume by
  strike so far -> point of control, volume-weighted strike, 70% value-area high / low (distances from spot), call share
  of volume (day and last 15 minutes), share of volume at strikes above spot; OI-weighted call / put strike; today's call
  vs put OI change.
- **Order-flow proxy (tick rule on options)**: option volume signed by its own price change (calls rising / puts falling
  = buying pressure), over the day, the last 15 and the last 5 minutes.
- **Gamma exposure**: R28's per-minute GEX (`derived/r28_gex`): near-expiry gamma ratio, gamma flip distance and side,
  positive / negative / absolute gamma walls, edge share, net share (near, month, all).
- **Other indices** at the same minute: NIFTY, BANKNIFTY, FINNIFTY, SENSEX, MIDCPNIFTY, BANKEX: last 15 minutes and day
  so far.
- **Levels**: distance to round numbers (100 / 500 / 1,000), position in yesterday's range and distance to its
  Fibonacci levels (0.236 .. 0.786); returns over the last 1, 2, 5, 10, 20 days; gap; session TWAP distance; day of month,
  month.
Nothing else changes (same targets, models, families of 48, rules and gates).
