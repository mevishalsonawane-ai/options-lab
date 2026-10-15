# Data Brain v1, L5 first models: pre-registration (B1)

Written 14 Oct 2026, before any brain model was fitted or any brain metric was seen. Committed on its own, before the
code that fits the models is run. Boss (14 Oct): "Finish all the work fast, we need to train our brain."

The models **only inform and rank** (expected-move size for stop/target sizing, a quiet-or-busy warning, context for
Jarvis). Nothing here trades, skips or resizes a trade; any later use on live follows CLAUDE.md (shadow first).

## Data

The L3 cube and L4 feature store of `research/brain` (cube v2), per index, from the best source per day
(`--source auto`: the Dhan lake on Google Drive where it has the day with options, else the research-data release,
else the lake's index-only days). Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX. Exact coverage is
written by the build to `research/brain/COVERAGE.md`. Indices without option data get NaN option features (the
gradient-boosted model handles missing values; nothing is filled).

## Splits (holdout locked, NOT read here)

- Design = sessions up to **2026-04-13**. Embargo 2026-04-14 .. 2026-04-26 (used by neither). Locked holdout =
  **2026-04-27 onwards**, every index (R18 amendment 1; `research/brain/holdout.py`). No label is built for the
  embargo or the holdout, and every training frame passes `holdout.assert_design`.
- Within design: `split.walk_forward` with 8 test blocks after 2 training blocks (10 equal blocks of sessions);
  training strictly before each test block; purge = the label window (60 minutes for T1/T2, the whole session for
  T3/T4), embargo 1 day. Every model and baseline is refitted per fold on that fold's training rows only.
- The holdout is read once, later, by a separate step, only after the lake is complete and has >= 100 holdout
  sessions per index; that step gets its own experiment entry.

## Targets (per index; decision rows are minute closes, features at the decision row only)

- **T1 range30**: log of the high-low range of the next 30 minutes (t+1 .. t+30) in % of the close at t
  (`labels.y_range_30m`). Decision every 15 minutes, 09:45 .. 14:45 (the rows of the 09:44, 09:59, ... bars).
- **T2 range60**: the same for 60 minutes (`y_range_60m`), 09:45 .. 14:15.
- **T3 daytype** (trend day vs range day), decided at 09:45: trend = efficiency of the rest of the day
  `y_er_rest` >= 0.5 (R16 test c).
- **T4 regime** (busy vs quiet rest of day), decided at 09:45: busy = log `y_range_rest` above the median of the same
  quantity over the previous 60 sessions (needs 30; the threshold reads only earlier sessions' finished labels).

## Baselines

- T1/T2: **B-tod** = `tod_logrange_{30,60}m` (mean log range of the same clock window over the previous 20 sessions);
  **B-last** = log of the last 30/60-minute range; **B-vix** = a + b * log(VIX * sqrt(h / 375 / 252)), fitted in
  fold (indices without VIX at that row: B-vix = B-tod). The model must beat the **best** of the three, chosen as the
  one with the lowest pooled walk-forward MAE.
- T3: the training base rate; **yesterday's day type** (as a 0/1 forecast, clipped to 0.05/0.95).
- T4: the training base rate; yesterday's regime (clipped as above); **VIX-only** logistic (log VIX at 09:44).

## Models (fixed now; no tuning)

- T1/T2: M-lin = ridge (alpha 1, standardised) on the 4 R16 inputs (B-tod, log last-30 range, log last-60 range,
  log VIX); **M-gbm (primary)** = scikit-learn HistGradientBoostingRegressor on every L4 feature that is not all
  missing in training (loss absolute_error, max_iter 200, learning_rate 0.05, max_leaf_nodes 15,
  min_samples_leaf 50, l2 1.0, random_state 0, no early stopping).
- T3/T4: M-lin = logistic (C 1, standardised) on opening efficiency `er_day`, day range so far, gap, log VIX, log
  last-30 range, DTE, previous-session ER/state; **M-gbm (primary)** = HistGradientBoostingClassifier, same settings
  (log_loss).
- Missing inputs for the linear models: the training median (computed on the fold's training rows only).

## Metrics, tests and bars (walk-forward, design only)

- T1/T2: MAE of the log range (primary), RMSE, skill = 1 - MAE_model / MAE_best_baseline. p = one-sided paired
  session-block bootstrap (B = 2000, seed 0) of the per-session mean loss difference. **Bar: skill >= 5% and BH
  q <= 0.05.** Weak: skill 1-5% and q <= 0.05.
- T3/T4: AUC (primary), log-loss, Brier, accuracy. p = one-sided session bootstrap of (log-loss of the best baseline
  - log-loss of the model). **Bar: AUC >= 0.58, log-loss below every baseline, BH q <= 0.05.** Weak: AUC 0.53-0.58
  with q <= 0.05.
- Calibration "plots" as tables: T1/T2 forecast deciles -> mean forecast and mean actual range (in %), and the
  10th/50th/90th percentile of actual/forecast; T3/T4 probability deciles -> mean forecast vs observed rate.
- Multiple testing: one Benjamini-Hochberg family over the 24 primary tests (6 indices x 4 targets, M-gbm only),
  q = 0.05. M-lin and every baseline are reported for every test too. Nothing is dropped from the report.
- Sample sizes: rows and sessions of every test are written with every number.

## What is NOT allowed

- Reading any label after 2026-04-13. Changing a target, bar, model setting or the feature list after seeing a
  result (a change needs a new entry and a new pre-registration). Using direction: R16 showed it is not predictable;
  no direction model is fitted here.

## Outputs

- `research/brain/models/metrics_v1.json` (committed): every test, every model and baseline, n rows / sessions,
  folds, calibration tables, BH q.
- Model files (one per index x target, fitted on all design rows) go to the cache and to Google Drive
  `gdrive:IraAlgo-data-lake/brain_models/v1/`, never to git.
- `research/experiments/entries/b1.yaml`, `research/models/brain_v1.md` (model card for the knowledge pack).
