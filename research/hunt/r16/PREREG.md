# R16 pre-registration: "What is predictable?"

Written 12 Oct 2026, committed BEFORE any model was fitted or any target was looked at against a feature.
Boss: "See something with which we could predict the future, by checking the old data."

## Data (all legal / free, nothing invented)

| id | source | span | used for |
|---|---|---|---|
| D1 | repo release `research-data`: `banknifty_prev_year_wide.parquet` + `banknifty_year.parquet` (BANKNIFTY 1-min index + near-ATM 1-min options with OI) | 13 Feb 2024 - 23 Feb 2026 | intraday targets a1, a2, a3b, b, c, e on BANKNIFTY; option prices for b's cost test |
| D2 | same release: `nifty_prev.parquet` + `nifty_year.parquet` (NIFTY, same layout) | 1 Apr 2024 - 13 Apr 2026 | same targets on NIFTY |
| D3 | Yahoo daily `^NSEI`, `^NSEBANK`, `^INDIAVIX` (public chart endpoint) | 1 Jan 2012 - 9 Oct 2026 | a3 (next-day range), VIX feature everywhere (previous close only) |
| D4 | NSE F&O bhavcopy (old format to Jul 2024, UDiFF after), the session BEFORE each expiry | Jan 2022 - Oct 2026 | d (max pain from the previous day's end-of-day OI) |

No `data-*` release exists on the repo today and no Dhan token is used. Raw data and intermediate tables live in
the scratchpad only, never in git.

## Splits

- Walk-forward by calendar month, expanding window. For each test month m the model is fitted on everything that
  ended before the first session of m minus an **embargo of 5 sessions** (intraday targets never cross a session, so
  purging = dropping the 5 sessions before the test month). First test month: the 7th month of each data set (6
  months warm-up). Hyper-parameters are fixed below; no tuning beyond the listed choice "linear vs LightGBM".
- **Locked holdout = the last 6 months of each data set**, read ONCE (a flag file in the scratchpad stops a second
  run). Inside the holdout the same monthly walk-forward continues (refit each month on all earlier data, as the app
  would), with the model choice frozen from DESIGN.
  - D1 BANKNIFTY intraday: holdout from **2025-08-25** (design = 13 Feb 2024 - 22 Aug 2025).
  - D2 NIFTY intraday: holdout from **2025-10-13** (design = 1 Apr 2024 - 10 Oct 2025).
  - D3 daily (a3): holdout from **2026-04-09** (design = 2012 - 8 Apr 2026; walk-forward test months from 2014).
  - D4 expiry (d): holdout from **2026-04-09** (design = Jan 2022 - 8 Apr 2026).

## Targets, baselines, features

Decision times are minute closes; features use only minutes up to and including the decision minute, the previous
session, and the previous day's VIX close. 09:15 = first minute.

**a) Volatility (magnitude).** Target = log of realised range (high - low of the 1-min index bars) / price.
- a1: next 30 min, decided every 15 min from 09:45 to 14:45. a2: next 60 min, 09:45 to 14:15.
- a3: next-day full range (daily D3, decided after the close), both indices.
- a3b: rest-of-day range from 09:30 (decided at 09:30), intraday D1/D2.
- Baselines (the model must beat the BEST of them, chosen on DESIGN): B-tod = mean log range of the same clock
  window over the previous 20 sessions; B-last = range of the last equal-length window today (a1/a2 only);
  B-vix = VIX-implied (a + b * log(VIX * sqrt(window/375/252)), fitted in-fold); for a3: 20-day mean range and VIX.
- Features: last 5/15/30/60-min ranges and returns, range so far today, gap, time of day, B-tod, VIX prev close,
  previous day range, 5/20-day mean range, weekday, days to expiry, expiry-day flag, near-ATM CE/PE OI change since
  open (D1/D2 only). a3 features: HAR (1/5/22-day log range), VIX, |return| today, weekday, monthly-expiry week.
- Models: linear (ridge, alpha 1) and LightGBM (200 trees, lr 0.03, 15 leaves, min 50 rows, subsample 0.8).
- Metric: MAE of log range (primary), RMSE; skill = 1 - MAE_model / MAE_best_baseline. p = one-sided paired
  block bootstrap by session (B = 2000) of the per-session mean loss difference.
- **Success bar: skill >= 5% in holdout and BH q <= 0.05.** Weak: skill 1-5% and q <= 0.05. Else not predictable.

**b) Direction.** Target = sign of the next 15 / 30 / 60-min index return (ties dropped), decided every 15 min from
09:30 until 15:15 - horizon.
- Baseline: train base rate (majority class for accuracy, base rate as the probability for log-loss/Brier).
- Features: returns over 1/5/15/30/60 min and since open, gap, distance to VWAP (in range units), position in
  today's range, time of day, VIX prev close, previous day return, near-ATM CE/PE OI change over 15/30 min and
  since open.
- Models: logistic (L2, C = 1, standardised) and LightGBM (as above).
- Metrics: AUC (primary), accuracy, log-loss, Brier, calibration table (deciles). p = one-sided session-block
  bootstrap of AUC - 0.5 (B = 2000).
- **Success bar (statistical): holdout AUC >= 0.53 AND log-loss below the base rate AND BH q <= 0.05.** Weak: AUC
  0.51-0.53 with q <= 0.05.
- **Tradability (only reported; can only pass if the statistical bar passes):** when p >= 0.55 buy the nearest-expiry
  ATM CE, when p <= 0.45 the ATM PE, at the decision-minute close; sell at the horizon-minute close. Costs: half
  spread 0.16% of premium each side (R15's figure; stressed at 2x) + Zerodha-style charges (`obuy.costs`, 'app'
  rates). 1 lot: BANKNIFTY 30, NIFTY 65. Random twin: same entries with a random side (B = 2000); pass needs net
  Rs/trade > 0, t > 2 and beating >= 95% of random twins.

**c) Regime from the opening.** Decision at 09:45 (first 30 min) and 10:00 (first 45 min). Target = rest-of-day
trend day: efficiency ER = |close(15:29) - close(t)| / (rest-of-day high - low) >= 0.5 -> trend, else range.
- Baselines: yesterday's regime computed the same way from yesterday's same time (accuracy, and as a 0/1 prob with
  0.05/0.95 clipping for log-loss) and the train base rate.
- Features: opening ER and range (vs 20-day mean), gap, return so far, VIX, previous day's regime and range, days to
  expiry, OI changes since open.
- Models: logistic and LightGBM. Metrics: AUC (primary), accuracy, log-loss, Brier.
- **Success bar: holdout AUC >= 0.58, log-loss below base rate, accuracy above yesterday's-regime accuracy, BH q <=
  0.05.** Weak: AUC 0.53-0.58 with q <= 0.05.

**d) Expiry day: close vs max pain.** Every weekly/monthly expiry of NIFTY and BANKNIFTY (D4). Max pain = strike
minimising total option-writer payout using the previous session's end-of-day OI of that expiry. Forecast of the
expiry close made at 09:15 open (Yahoo daily open, D3).
- Baseline: no change (close = open).
- Predictors: P1 close = max pain; P2 shrink: open + w (max pain - open), w fitted by least squares in-fold.
- Metric: MAE of close in % of open (primary); pin rate = |close - max pain| <= 0.25% vs |close - open| <= 0.25%.
- **Success bar: holdout MAE of P2 (or P1, chosen on DESIGN) at least 5% below no-change, BH q <= 0.05** (paired
  bootstrap by expiry). Weak: 1-5%.
- Also descriptive (no test): expiry-day range vs other days (comes from a3).

**e) Analogs ("days like today").** At 10:15. Day vector: first-hour path (12 five-min closes relative to the open,
divided by the trailing 20-day mean daily range), gap (same units), VIX percentile (trailing 250 days). Standardised
with in-fold mean/sd. k = 25 nearest earlier sessions (Euclidean), only sessions before the embargo.
- Targets: e1 rest-of-day return 10:15 -> 15:29 and e2 rest-of-day range, both in trailing-range units.
- Baseline: the unconditional distribution of all earlier in-fold sessions (same units).
- Metrics: CRPS of the empirical ensemble (primary); log-loss of the 3-bin (down/flat/up terciles of train) forecast
  with add-one smoothing. Skill = 1 - CRPS_analog / CRPS_uncond.
- **Success bar: holdout CRPS skill >= 3% and BH q <= 0.05** (paired session bootstrap). Weak: 1-3%.

## Model ladder

Linear and LightGBM only, in DESIGN. A small sequence model (1D-CNN/GRU on the last 60 one-minute returns) is run
ONLY for a target where a tree model beat the baselines in DESIGN with skill above the success bar; it then has to
beat the tree model in DESIGN to be the frozen choice. CPU only, short runs.

## Multiple testing

One BH family over every holdout primary p-value: a1, a2, a3, a3b (2 indices each = 8), b (3 horizons x 2 = 6), c (2
times x 2 = 4), d (2), e (2 targets x 2 = 4) = **24 tests**, q = 0.05. Tradability tests for b are a second family
(6). DESIGN results are reported for every test too, including failures. Nothing is dropped from the report.

## What is NOT allowed

Changing a target, bar, baseline, feature set or holdout date after seeing results; re-reading the holdout; any
data that is not in the table above. Deviations, if unavoidable (e.g. a download fails), are listed in the report.
