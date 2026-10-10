# STRAD-INDEX pre-registration

Written 8 Oct 2026, **before any straddle P&L was computed**. Nothing has been priced or simulated yet. Only the
existing studies were read (h18, h24, h29, h42, OBUY_GC, the STRAD-CRUDE and STRAD-CRYPTO pre-registrations).

Question (Boss): "trade the SIZE of the move, not the direction". Buy a NIFTY / BANKNIFTY ATM straddle (or the
1-strike-out strangle) only when a forecast says the next 15/30/60/120 minutes (or the rest of the day) will move a lot
AND the straddle is cheap against that forecast. Does it pay after app charges and the real spread?

Option BUYING only. 1 lot per leg. Capital Rs 1,00,000. One open position per index per rule.

## Missing inputs (said up front)

- `research/NN_CRUDE.md`, `research/NN_CRYPTO.md`, `research/X3_AUDIT.md` and `research/STRAD_*.md` do not exist on
  this machine. I reuse the sister PREREGs' design (`research/hunt/strad_crude/PREREG.md`, `.../strad_crypto/PREREG.md`).
- torch is NOT installed. The neural net is sklearn `MLPRegressor`; the boosted model is sklearn
  `HistGradientBoostingRegressor`.
- India CPI release dates are not in the event calendar. Events used: RBI policy days, Union Budget days, election
  result days, the Indian morning after a US CPI release, the Indian morning after an FOMC decision
  (all from `scratchpad/hunt/h28/events.csv`), and expiry day.

## Data

- Index minutes: `scratchpad/jx/ix_<U>.pkl` via `research/obuy/data.py` (NIFTY from Aug 2020, BANKNIFTY from Aug 2021).
  Days whose index bars are synthetic (`real` = False) are skipped.
- Options: Dhan rolling ATM±10 minute bars with OI and IV, series `near` (NIFTY weekly; BANKNIFTY weekly until its
  weekly ended in Nov 2024, then monthly). On an expiry day `near` is that day's expiry.
- India VIX: daily closes (all years), minute closes from Oct 2021.
- Lot: today's lot for all years (NIFTY 65, BANKNIFTY 30), so rupees are comparable across years.

## Split

- Design (pre-holdout): start of data .. 30 Sep 2025.
- Walk-forward by calendar year, anchored: models for test year Y are fitted on all design data before 1 Jan Y.
  Test years: 2022, 2023, 2024, 2025 (Jan-Sep). 2020-2021 are training only (BANKNIFTY starts Aug 2021).
- **Locked holdout: 1 Oct 2025 .. latest data.** Run ONCE at the end, after the design results are written.
  For the holdout the models are refitted once on all design data and then frozen.

## Decision grid and trades

- Decision minutes: the close of 09:20, 09:35, 09:50, ..., every 15 minutes, to 14:50 (23 times a day).
- Horizons h: 15, 30, 60, 120 minutes, and EOD (exit at 15:10). A decision time is valid for h when entry + h <= 15:10
  (EOD: decision <= 14:55).
- Strikes: ATM = index close at the decision minute rounded to the strike step (NIFTY 50, BANKNIFTY 100).
  Straddle = ATM CE + ATM PE. Strangle = (ATM + 1 step) CE + (ATM - 1 step) PE. Strikes fixed after entry.
- Entry: each leg at the OPEN of the next minute (decision + 1). Missing bar: the last close up to 5 minutes old,
  otherwise no trade.
- Exits (combined premium = sum of the two legs' 1-minute mid closes; no bid/ask in the data):
  - T: time exit at the open of minute entry + h (EOD: the 15:10 open).
  - Overlays, checked on every 1-minute close of the combined premium from the entry minute on, filled at the next
    minute's open: PT20 (+20% vs entry premium), PT40, SL25 (-25%), SL40, PT20+SL25, PT40+SL40.
  - So 7 exits per horizon: T, T+PT20, T+PT40, T+SL25, T+SL40, T+PT20+SL25, T+PT40+SL40. The time exit always applies.
- Capital: no entry if combined premium x lot > Rs 1,00,000.

## Costs

- Charges: `obuy.costs.Costs('app')` per order (4 orders per round trip): brokerage Rs 20, STT 0.15% of sell premium,
  exchange 0.03553%, SEBI, stamp, GST.
- Spread: each leg bought at mid x (1 + hs) and sold at mid x (1 - hs), hs = max(real half-spread, 5 bps).
  Real half-spread from h24 (Dhan chain, 6 Oct 2026): ATM / 1-ITM NIFTY and BANKNIFTY 0.16%. So hs = 0.16% for ATM
  legs and 0.20% for the 1-OTM strangle legs (h24's OTM range runs to 0.24%). Stress case: 2x hs.
- **Gross** = mid-to-mid P&L, no charges, no spread. **Net** = after charges and 1x spread (headline). Net 2x also shown.

## Size forecast (target and features)

Target for horizon h at decision minute s: RV_h = sqrt(sum of squared 1-minute log returns of the index closes over
(s, s+h]) (EOD: (s, 15:10]). Model target = log RV_h. One model per horizon, pooled over the two indices.

Features (all computed from data at or before the close of minute s; the feature function receives arrays truncated
at s, and a perturbation audit scrambles everything after s and checks the features do not change):
- HAR terms: log per-minute RV over the last 15, 30, 60 minutes and since the open; yesterday's per-minute RV;
  mean of the last 5 days' per-minute RV.
- Range today so far; prior-day range (H - L) / close; gap |open / prev close - 1| and signed gap; prior-day return
  and a "prior day down >= 1%" flag (h42).
- Time of day (minute s); weekday; calendar days to expiry; expiry-day flag; monthly-series flag; index flag.
- India VIX: log previous close; log(VIX at s / previous close) (0 with a missing flag before Oct 2021).
- h18 gamma concentration: gtot = total |gamma x OI| near spot (h18's `day_feats`, OTM-side IV, ATM±10 OI) at the
  latest 5-minute column <= s. B_lvl = log(gtot / median gtot at the same column over the previous 20 days).
  A_share = GEX_A / gtot.
- Events: RBI day (+ "before 10:15" flag), Budget day, election-result day, morning after US CPI, morning after FOMC.
- Implied volatility is NOT a size-model input (the forecast must be independent of the price it is compared with).

Models, fitted per walk-forward year on training rows only:
- HAR-X: ridge regression (alpha 1.0, standardised) on all features plus the training mean of log RV_h at that slot.
- GBM: HistGradientBoostingRegressor(max_iter 300, learning_rate 0.05, max_leaf_nodes 15, min_samples_leaf 200,
  l2 1.0, random_state 0).
- MLP: MLPRegressor((32, 16), alpha 1e-3, early_stopping, random_state 0), standardised, NaN -> 0 + flags.
- **The forecast used by every rule is the ensemble: the mean of the three log forecasts**, bias-corrected so the mean
  of exp(forecast) equals the mean RV on the training rows. FRV_h. The three models' out-of-sample R^2 and rank
  correlation are reported, but no model is chosen by them.

## Cheapness (implied vs forecast)

- Straddle mid S_t = ATM CE close + ATM PE close at s. Implied total sd to expiry: sd_T = S_t / (0.7979 x spot).
- Minutes left to expiry N_rem = trading minutes from s to 15:30 on the expiry day (375 per full day; nights and
  weekends add nothing - the overnight risk is spread over trading minutes, which makes options look dearer).
- Implied RV over h: IRV_h = sd_T x sqrt(h / N_rem). Implied mean |move| IM_h = 0.7979 x IRV_h x spot.
- Ratio R_h = FRV_h / IRV_h (high = cheap). Also IV-based: Dhan ATM IV vs FRV_h annualised (descriptive only).

## Filters (signals)

Thresholds are trailing: for index u, horizon h and clock slot s, the q-quantile of that quantity over the previous
60 trading days at the same slot, using the forecasts as they were made then (the first test year's first 60 days are
seeded with the training model's in-sample fits; the holdout is seeded with the 2025 out-of-sample forecasts).

- A ALWAYS: every valid decision time (one position at a time).
- B SIZE: FRV_h >= trailing q-quantile, q in {0.7, 0.8, 0.9}.
- C CHEAP: R_h >= trailing q-quantile, q in {0.7, 0.8, 0.9}.
- D SIZE AND CHEAP: B(q1) and C(q2), q1, q2 in {0.7, 0.8, 0.9} (9).
- E CHEAP ABSOLUTE ("forecast >= priced"): R_h >= 1.0; and R_h >= 1.0 with B(0.8).

18 filters. Variants = 2 indices x 5 horizons x 2 structures x 7 exits x 18 filters = **2,520**, all run, all counted.

## Statistics (design period)

- For each variant, on its out-of-sample design trades (2022-01 .. 2025-09): trades, hit rate, gross and net Rs per
  trade and per day (per trading day of the period), max drawdown, % green months.
- One-sided t-test p of mean net per trade > 0; Benjamini-Hochberg over all 2,520.
- Hansen SPA and White RC (obuy.overfit.spa) on daily net P&L of all variants vs not trading.
- Random-time baseline: for each variant, 2,000 draws of the same number of trades from all valid decision times of
  the same index, horizon and period, with the same structure and exit; p = share of draws whose mean net per trade
  >= the variant's. BH over the variants too.

## Walk-forward selection (the honest design number)

Per index. For test year Y in 2023, 2024, 2025: choose the variant with the best net Rs/day on the earlier
out-of-sample test years (>= 30 trades), trade it in Y. For 2022 no earlier out-of-sample forecasts exist, so only
family A (always buy, 70 variants per index) can be chosen, scored on 2021. Primary: if the chosen variant's score is
<= 0, stand aside that year (Rs 0). Also shown: forced (always trade the pick).

## Holdout (run once)

- Frozen rule per index = the variant with the best net Rs/day over all out-of-sample design years 2022-01 .. 2025-09
  (>= 100 trades). Also the best variant in each family A-E, for comparison. Nothing else is run as a test on the
  holdout. (Holdout numbers for other variants, if shown, are marked descriptive.)
- Models refitted once on all design rows; thresholds trailing as above.

## Pass gates ("it pays") - all must hold, else the verdict is NO

1. Walk-forward test years net > 0 (1x spread).
2. Holdout net per trade > 0 (1x spread).
3. Holdout random-time p < 0.05.
4. Design BH q < 0.10 for the chosen rule (t-test), and SPA p < 0.10.
5. >= 60% green months in the holdout.

## Descriptive (chooses nothing)

Average implied-vs-realised gap: mean realised |move_h| / mean IM_h, and mean realised RV_h / IRV_h, by index, horizon,
time of day, event type (RBI, Budget, election, post-CPI, post-FOMC, expiry day, after a >= 1% down day, normal day),
and year. Design period in the design step; holdout added in the holdout step.
