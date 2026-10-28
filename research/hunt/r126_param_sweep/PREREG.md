# R126 pre-registration: every indicator, data input and rule at many settings (plus Bollinger Bands and MACD)

Written 28 Oct 2026, before any R126 input was computed or any model fitted. Research only: nothing in the app
changes, nothing trades.

Boss, 28 Oct: *"Add Bollinger Bands and MACD too, and for the existing indicators try to modify the parameters of the
indicator/data like volume, vertical volume etc, instead of keeping them as is, try multiple variables of each and every
indicator/data/strategy, try changing them and then test again."*

## 0. The question, and the danger

R125 found that wick S/R, liquidity, order flow, volume profile, wick candles, VWAP/EMAs, RSI and Supertrend at their
standard settings add nothing to the direction of the next 30-point move. R126 asks: **at any other setting, does any
of them (or Bollinger Bands or MACD) add something, and does any rule setting make money?**

Searching many settings guarantees that some look good by luck. The guards, fixed now:
1. Every variant tried is counted, and BH runs over **all** of them in each family.
2. All selection happens on the 2-year walk-forward only.
3. The holdout (1 Apr - 19 Oct 2026) is read once, for the frozen winners only.
4. **This is the holdout's fourth read today** (R123, R124, R125). So even a pass in R126 earns only a pre-registered
   *shadow* forward test on new sessions, never paper or live directly.

Everything is on candle **wicks**: indicators use the wick midpoint (high + low) / 2, ranges use highs and lows, and no
open or close is used, except a trade's own entry reference (R124 amendment 1).

## 1. Data, rows, labels (unchanged from R125)

- R124's rows, 20 Oct 2024 - 19 Oct 2026: every 5 minutes, all five indices.
- Labels: the wick-to-wick 30-point move (m) within 15 / 30 minutes, up first / down first / neither.
- Trades: index-exit ATM option trades.
- Folds: quarterly, F1 Apr-Jun 2025 .. F4 Jan-15 Mar 2026.

## 2. The variant grid (pre-registered; 134 input variants)

tf = candle timeframe in minutes (aligned to 09:15; always the last *completed* candle). m = the move size (30 NIFTY
points, scaled by price). Distances are signed wick-span distances in m.

| family | settings tried | variants | inputs per variant |
|---|---|---|---|
| **Bollinger Bands (new)** | n ∈ {10, 20, 50} × k ∈ {1.5, 2, 2.5} × tf ∈ {1, 5} | 18 | span vs upper / lower band (m), bandwidth % of price, squeeze (bandwidth in the lowest 20% of the last 120 candles) |
| **MACD (new)** | (fast, slow, signal) ∈ {(12,26,9), (8,21,5), (5,35,5)} × tf ∈ {1, 5} | 6 | MACD line, histogram, histogram change over 3 candles (all in m) |
| RSI | period ∈ {7, 14, 21} × tf ∈ {1, 5, 15} | 9 | RSI, its change over 3 candles |
| Supertrend | ATR period ∈ {7, 10, 14} × multiplier ∈ {2, 3, 4} × tf ∈ {1, 5} | 18 | direction, distance (m), signed candles since the flip |
| EMA | length ∈ {5, 9, 20, 50, 100, 200} × tf ∈ {1, 5} | 12 | distance (m), slope over 3 candles (m) |
| EMA cross | pairs (5,20), (9,21), (20,50) × tf ∈ {1, 5} | 6 | signed candles since the cross (capped at 120), gap between them (m) |
| VWAP | session; rolling 30 / 60 / 120 minutes | 4 | distance (m), position in SD bands |
| Volume | window ∈ {5, 15, 30, 60} min × baseline ∈ {5, 10, 20} sessions | 12 | log ratio to the same minutes' baseline |
| Volume profile | lookback ∈ {today only, 1, 5, 10 sessions} at a 70% value area; plus 60% and 80% at 5 sessions | 6 | the R125 profile inputs (POC, VAH, VAL, inside, node) |
| Support / resistance | pivot k ∈ {3, 5, 10} × lookback ∈ {1, 5 sessions} | 6 | the R125 S/R inputs |
| Liquidity sweeps | failure bars ∈ {1, 3, 5} × look-back ∈ {10, 30 bars} | 6 | sweep high/low, minutes since, poke size |
| Liquidity pools | gap ∈ {m/10, m/6, m/3} | 3 | pool distance above / below |
| Wick candles | tf ∈ {1, 3, 5, 15} | 4 | the R125 eight wick patterns |
| Option flow | call/put volume window ∈ {3, 5, 15, 30}; OI build-up window ∈ {5, 15, 30} | 7 | the log ratio / build-up score |
| R125 defaults | (the R125 inputs not covered above: participant OI, highs-minus-lows, S/R counts) | 1 | as in R125 |
| | | **134** | |

## 3. Tests

**A. Screen every variant, on the walk-forward only.**
- Model: a logistic regression (training-row medians and scaling; L2, C = 1) on a fixed **price core**: R124's index
  state inputs (returns 5/15/30/60, realised volatility, day range, distances to the day high/low, move since the
  open, gap, VIX, time of day). It is fitted with and without the variant's inputs, per fold.
- The measure: the change in out-of-sample log-loss for **direction given a move** (15 and 30 minutes).
- 268 tests (134 × 2), one BH family, with day-block bootstrap p-values.
- A variant "adds" if q ≤ 0.05, it is better in ≥ 3 of 4 folds, and it improves the log-loss by at least 0.001 (a
  tenth of a percent: smaller is not worth an input).

**B. The network with everything (N4).**
- R124's multi-level network plus 14 specialists, one per family above, holding **all 134 variants' inputs**.
- Compared against R124's network (R125's walk-forward "base" predictions on the same rows and seeds) on the four
  cells of R125 (direction and move-coming, 15 and 30 minutes); BH over 4.
- Permutation importance by family.

**C. Strategy settings (rules).** Signal on the underlying, buy the ATM option. Exits on the index's wicks at ±e·m or
the time limit; 1 lot at today's lots; dated charges; liquidity-aware slippage; at most 5 trades a day; one open per
index.
- Settings tried, using N4's forecasts:
  - direction share ∈ {0.55, 0.60, 0.65};
  - "move likely" = top {5%, 10%, 20%} of training P(move);
  - horizon ∈ {15, 30} minutes;
  - exit size e ∈ {0.67, 1.0, 1.5} (20 / 30 / 45 NIFTY points).
- That is 54 rule variants, one BH family against 1,000 random-entry twins each.
- Walk-forward gates (R123): ≥ 300 trades, positive at 1x and 2x slippage, positive in 3 of 4 folds, q ≤ 0.10.

**Holdout (read once, after everything above is committed).**
- Read: (a) the variants that passed A (their holdout log-loss change, same direction, CI); (b) N4 vs R124's network;
  (c) the rule variants that passed C, with R123's holdout gates (≥ 100 trades, positive at 1x and 2x, q ≤ 0.10 over
  the ones read, the account stop).
- If nothing passed the walk-forward, the holdout is still read for B, and reported.

## 4. Outputs

- Code and results: `research/hunt/r126_param_sweep/`.
- Write-up: `research/HUNT_R126_PARAM_SWEEP.md`.
- Experiment entries: one per family plus the rule sweep, `variants_tried` the real counts.
- Big files: on Drive (`derived/r126/`).
