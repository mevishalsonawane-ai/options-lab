# NN-CRUDE pre-registration (written 2026-10-08 ~21:00 IST, before any model was fit)

## Data
- MCX near-month CRUDEOIL 1-minute price = `spot` field of Dhan `/charts/rollingoption` (underlying 294, MONTH,
  expiryCode 1), Aug 2025 - 8 Oct 2026. Volume/OI from the ATM option rows; futures volume/OI only for live contracts.
- Long proxy: CL=F 1h x INR=X 1h (Yahoo, May 2024 -), CL=F/BZ=F/INR=X daily since 2000.

## Locked holdout
- **2026-04-01 .. 2026-10-08** (last ~6 months). Nothing in it is used for features scaling, model choice,
  thresholds or hyper-parameters. `evaluate.py --holdout` is run ONCE at the end; its output is reported as is.
- Development period = start of MCX data .. 2026-03-31 (and proxy data before 2026-04-01).

## Targets
- Decision points: every 5 minutes, 09:30 - 23:00 IST (last decision leaves room for the horizon inside the session;
  a sample whose horizon crosses the session close is dropped).
- y_dir(h) = 1 if log(close[t+h]/close[t]) > 0, h in {15, 30, 60} minutes. Ties (0 return) dropped.
- y_big(h) = 1 if |ret(h)| > its dev-period median (the "size" target).

## Validation
- Anchored walk-forward by calendar month on the dev period: train on all months before month k, test month k.
  Purge: drop training samples whose label window overlaps the test month; embargo 1 trading day.
- Hyper-parameters (MLP alpha in {1e-3, 1e-2, 1e-1}; hidden (64,32) vs (32,)) chosen by mean walk-forward AUC on dev.

## Models (all variants counted)
- MLP (sklearn MLPClassifier, early stopping, 5-seed average) on: last 30 one-minute returns (normalised by recent
  vol) + engineered features.  Variant "proxy-pretrained": first trained on the hourly WTI x USDINR proxy (60-min
  target), then continued (warm start) on MCX.
- Baselines: logistic regression (C=0.1), HistGradientBoosting (depth 3, 200 iters, lr 0.05), always-flat,
  random score.
- Metrics: AUC, accuracy, Brier score + reliability table, per month and per period.

## Trade translation (fixed before results)
- Signal: score in the top 5% (long) / bottom 5% (short) of the dev walk-forward OOS score distribution
  (thresholds frozen from dev). One position at a time; max 6 trades/day.
- Futures: 1 lot CRUDEOILM (10 bbl), enter next minute open, exit at open h minutes later; costs = Zerodha
  charges + measured half-spread (futures depth snapshot) each side.
- Options: buy ATM CE (long) or ATM PE (short) of CRUDEOIL (100 bbl) nearest expiry, next-minute open, exit h later
  at open; costs = Zerodha option charges + half the measured ATM bid/ask (chain snapshots) each side.
- Random-entry baseline: same number of trades on the same days, random decision times from the same grid, random
  side, identical exits and costs; 1,000 draws -> p-value.
- Primary horizon for the verdict: h = 30 minutes, MLP. Others are reported as secondary (multiple-testing note).
