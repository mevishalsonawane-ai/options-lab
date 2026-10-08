# STRAD-CRUDE pre-registration (written 8 Oct 2026, before any P&L was computed)

Question (Boss): "trade the SIZE of the move, not the direction". Buy an MCX CRUDEOIL near-month ATM straddle (or the
1-strike-out strangle) only when a forecast says the next 15/30/60/240 minutes will move more than usual AND the
straddle is cheap against that forecast. Does that pay after Zerodha costs and the real bid/ask spread?

Option BUYING only. 1 lot = 100 bbl. Capital Rs 1,00,000. One position at a time per rule.

## Honesty statement (read first)

- The brief says the NN-CRUDE study already opened the 1 Apr - 8 Oct 2026 holdout once, for DIRECTION, and saw the
  size-model AUC there. So that holdout is **partly seen**. Every holdout number in this study carries that label.
- On this machine I could NOT find the NN-CRUDE study: no `research/NN_CRUDE.md`, no `research/hunt/nn_crude/`, no
  `scratchpad/hunt/nn_crude/` (no `mlp_size_h*.pkl`, no `feature_spec.json`, no cached crude minutes). Searched the
  whole disk for `*crude*`, `*mcx*`, `mlp_size*`, `feature_spec.json`. So:
  - I fetched the crude option minutes myself (Dhan `rollingoption`, MCX_COMM, CRUDEOIL underlying id 294,
    near month = expiryCode 1, ATM-3..ATM+3 CE/PE, plus next month ATM; Jul 2025 - 8 Oct 2026; IV, OI, spot fields).
  - I build my own "size MLP" stand-in (sklearn MLPRegressor) next to a HAR model. Both are trained walk-forward on
    design months only. They are NOT the NN study's models.
  - WTI/Brent/USDINR proxies: not used. The crude future in rupees already carries them minute by minute. Said so in
    the report.
- Spread: Dhan minute bars have no bid/ask. I logged live option-chain snapshots (top bid/ask) for CRUDEOIL on
  8 Oct 2026 22:24-23:30 IST and use the measured relative spread per moneyness. That is one evening of a liquid
  near-expiry contract, so I also report 2x spread as a stress case and treat 2x as the realistic case for morning
  entries (09:00-17:00) — fixed here, not tuned.

## Data split

- Design: 1 Aug 2025 - 31 Mar 2026. All choices are made here only.
- Walk-forward inside design, by calendar month, anchored: train on all design months before month m (purge: drop the
  last trading day before m), test on m. Test months: Oct 2025 - Mar 2026 (Aug-Sep 2025 are training only).
- Holdout: 1 Apr - 8 Oct 2026. Run ONCE at the end, after this file and the design results are written.
  Label: "partly seen".

## Instruments and prices

- Underlying F = the `spot` field of the near-month series (checked against put-call parity K + C - P).
- ATM strike at entry = strike nearest F (step 50). Straddle = ATM CE + ATM PE. Strangle = (ATM+1) CE + (ATM-1) PE.
- After entry the strikes are fixed. Each leg is priced every minute from whichever rolling series carries that strike
  (ATM-3..ATM+3). If the strike leaves that window, the leg is priced by Black-76 at the leg's last observed IV
  (count of such minutes is reported).
- Prices: 1-minute closes; a missing minute is forward-filled for at most 5 minutes, otherwise no entry.
- No trading on an option's own expiry day (the rolling series switches contract; settlement is special).
- Fills: buy each leg at close + half spread, sell at close - half spread.

## Costs (Zerodha, MCX options, per lot of 100)

- Brokerage Rs 20 per executed order (4 orders per straddle round trip).
- CTT 0.05% of sell-side premium.
- Exchange transaction charge 0.0418% of premium (both sides). SEBI Rs 10 per crore.
- GST 18% on brokerage + exchange + SEBI. Stamp duty 0.003% of buy-side premium.
- Spread: measured relative half-spread per leg (1x), stress 2x.
- Gross = P&L at mid closes, no costs, no spread. Net = after costs and spread.

## Signals (all computed with data up to the decision minute only)

Decision grid: every 15 minutes from 09:15 up to (23:25 - h). Forced exit at 23:25 at the latest.

- Realised moves: |log F(t+h) - log F(t)|, h in {15, 30, 60, 240} minutes of trading clock.
- Forecast models (fit on training months only, refit each walk-forward month):
  - HAR: OLS of log realised abs move over the next h on: log RV of the last 15 m, 60 m, day so far, yesterday,
    last 5 days; log clock-slot seasonal mean (training months); flags for EIA / CPI release inside (t, t+h];
    FOMC next-morning; OPEC Monday; days to expiry; weekday.
  - MLP: sklearn MLPRegressor(16,8), same inputs, standardised, early stopping, fixed seed.
- Forecast move FM_h (rupees per bbl), and implied move IM_h from the straddle:
  implied variance left = IV_atm^2 x tau (tau = calendar years to expiry); spread evenly over remaining trading
  minutes N_rem; IM_h = F x sqrt(IV^2 tau h / N_rem) x sqrt(2/pi) (mean absolute move).
- Ratio R_h = FM_h / IM_h ("cheap" when high).
- Filters:
  - A Always: every grid time (first free slot after the previous exit).
  - B Size-only: FM_h at or above the training q-quantile of FM_h at the same clock slot, q in {0.7, 0.8, 0.9}.
  - C Cheap-only: R_h at or above its training q-quantile, q in {0.7, 0.8, 0.9}.
  - D Size AND cheap: B(q1) and C(q2), q1, q2 in {0.7, 0.8, 0.9}.
  - Random: same number of trades drawn uniformly from the grid times of the same period, same exits (1000 draws).
- Exits (fixed in advance; time exit always on): T (time h only), T+PT20, T+PT40, T+SL25, T+SL40, T+PT40+SL40.
  PT/SL are checked on the 1-minute close of the combined mid premium; the fill is that minute's close less half
  spread.
- EIA family: on EIA release days, enter 15 minutes before the release (19:45 or 20:45 IST), exit at release + N,
  N in {5, 15, 30, 60}; straddle or strangle; always on EIA days (no forecast needed). Compared with the same clock
  on non-EIA days.

Variant count: 4 h x 2 structures x 6 exits x (1 + 2x3 + 2x3 + 2x9) = 1,488, plus 8 EIA variants = 1,496.
All are counted for Benjamini-Hochberg (one-sided, H0: mean net per trade <= 0) and for the random-time p.

## Event calendar (built from memory, approximate; checked against the 1-minute move at the release minute)

- EIA weekly petroleum report: Wednesdays 10:30 ET = 20:00 IST (US daylight time) / 21:00 IST (US standard time);
  holiday weeks move to Thursday (1-day shift after a Monday US holiday).
- US CPI 08:30 ET (18:00 / 19:00 IST). FOMC 14:00 ET = 23:30 IST (summer) / 00:30 (winter): after MCX close, so the
  flag is the next morning. OPEC+ meetings mostly on Sundays: flag the next Monday.
- Event days are listed separately in the report.

## What is chosen, and how

- Walk-forward: in each test month, pick the variant with the best net Rs/day on the training months (minimum 30
  trades), trade it in the test month. The chained test-month result is the honest design number.
- In the holdout the rule (family, h, structure, exit, quantiles) is frozen. The forecast models and the quantile
  cut-offs keep being refit each month on all data before that month (anchored, purge 1 day), exactly as in the
  walk-forward. No holdout month ever sees its own outcomes.
- Final rule for the holdout: the variant with the best net Rs/day over all design months (min 30 trades), and also
  the best variant inside each family (A, B, C, D, EIA) for comparison. Nothing else is run on the holdout.

## Pass gates ("it pays")

All of: (1) walk-forward test months net > 0 at 1x spread; (2) holdout net per trade > 0 at 1x spread;
(3) holdout random-time p < 0.05; (4) design BH q < 0.10 for the chosen rule; (5) >= 60% green months in holdout.
If any fails, the verdict is NO.

## Descriptive (chooses nothing)

- Implied-vs-realised gap: per h and clock slot, mean realised |move| / IM_h. And "to expiry": every day at 15:00,
  ATM straddle mid vs |F at expiry - K| (is the crude straddle cheap or dear on average?).
- Event days separately: EIA, OPEC, CPI, FOMC.

## Amendment (written before any P&L was computed, same day)

- Walk-forward selection detail: for test month m the candidate variants are scored on the earlier TEST months
  (Oct 2025 .. m-1), where the forecasts and quantile cut-offs are out-of-sample. For the first test month
  (Oct 2025) no earlier out-of-sample signals exist, so only family A (always buy) can be chosen, scored on
  Aug-Sep 2025. This replaces "score on the training months in-sample".
- The implied-vs-realised gap is computed on design months in the design step; the holdout months are added only in
  the holdout step.
- Black-76 fallback for a leg whose strike leaves the ATM-3..ATM+3 window uses that leg's last observed Dhan IV
  (or the straddle IV if none).
- Capital: an entry is skipped when its cost (premium at ask + fees) is above Rs 1,00,000 (1 lot does not fit;
  this happens in the March-May 2026 crude shock when ATM straddles cost Rs 1-1.5 lakh). Skips are counted.
  Capital is kept at Rs 1 lakh per entry (no compounding) so that per-trade numbers stay comparable.
- Expiry detection: day-to-day jump of the rolling ATM straddle > 1.6x marks a new contract; a jump less than 20 days
  after the previous expiry is ignored (the 9 Mar 2026 vol shock faked one).
- Event calendar (replaces my memory list; still before any P&L): EIA times use the official 2025-26 exception list
  from eia.gov as recorded in `research/hunt/nn_crude/calendar_events.py` (Thursday 12:00 ET after holidays);
  CPI and FOMC dates come from `scratchpad/hunt/h28/events.csv`. OPEC Mondays stay approximate.
- The NN-CRUDE code appeared on disk after this file was first written; its size MLP (classifier, 71 inputs) was not
  rebuilt. The "size MLP" here is my own regressor on HAR-type inputs (same Dhan data, same dev/holdout split).
