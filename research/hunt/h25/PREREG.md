# h25 pre-registration: every candlestick pattern, chart pattern, moving average and classic indicator as an option-BUYING trigger (and as a filter on Liquidity 15+5)

Written 2026-10-08, BEFORE any P&L of this study was computed. Nothing below is changed after results are seen.
Anything added later is labelled POST-HOC in the report.

## Data, contract, fills, costs

- Index minutes: obuy `Index` (NIFTY, BANKNIFTY, FINNIFTY from jx; MIDCPNIFTY, SENSEX from h4's caches, same files
  h4/h17/h23 used). Options: obuy `Options`, Dhan nearest-expiry minute bars.
- Underlyings: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.
- Timeframes: 1, 3, 5, 15, 30, 60 minutes, bars anchored at 09:15 each session. Indicators run on the continuous bar
  series across days (as a chart would show them). A bar's signal is known at its last minute's close (`sig_min`).
- Entry window: `sig_min` 09:15 .. 15:00. Expiry days skipped (the arms' rule, obuy `expiry="skip"`).
- Contract: 1-ITM (strike from the index close at `sig_min`), obuy `series="near"` (weekly while Dhan has it, else the
  nearest monthly). CE for a bull signal, PE for a bear signal. 1 lot (the day's lot from the data).
- Fill: option OPEN at `sig_min + 1` (first bar within 2 minutes), obuy app fills (+-5 bps, stops -10 bps), app
  charges `Costs('app')`. PLUS the measured half-spread (h24 snapshot) on BOTH the entry and the exit:
  BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.16% (not measured; assumed equal to
  NIFTY/BANKNIFTY). Stress = 1.5x these spreads.
- Gross (Boss's headline) = app-fill P&L before charges and before the extra spread.

## Exit menu (fixed now, 7 exits; obuy `Exits`, square-off 15:10 always)

| id | exit |
|---|---|
| E1 ARM | the Liquidity arm's exits: premium stop -15%, 20-min time stop unless +5%, index stop (BN 30, FIN 15, NIFTY 15, SENSEX 50, MIDCP 8 points) |
| E2 P20 | premium stop -20 pts, target +20 pts |
| E3 PCT | premium stop -15%, target +30% |
| E4 LAD | premium stop -15%, profit-lock ladder (obuy LADDER, R = 15% of entry), target +30% |
| E5 T15 | time stop 15 minutes (out regardless), nothing else |
| E6 T30 | time stop 30 minutes |
| E7 T60 | time stop 60 minutes |

## Signals (each in two orientations: FOLLOW = trade the conventional direction; FADE = the opposite option)

1. All 61 TA-Lib CDL functions (TA-Lib 0.8.1). Sign of the output = direction. Direction-less dojis
   (CDLDOJI, CDLLONGLEGGEDDOJI, CDLRICKSHAWMAN, CDLHIGHWAVE*, CDLSPINNINGTOP*) — for any CDL whose output is always
   +100 on the data, the direction is set as a reversal of the previous 5 bars' move (close vs close 5 bars back).
   CDLDRAGONFLYDOJI = bull, CDLGRAVESTONEDOJI = bear. (*only if always positive.)
2. Chart patterns (objective versions; swing pivots = 2-bar fractals, known 2 bars after the pivot):
   double bottom / top break, bull / bear flag break, triangle break (two falling pivot highs + two rising pivot lows,
   close beyond the last pivot), break of structure (higher-low then close above the last pivot high; mirror),
   inside-bar break, NR7 break, NR4 break, three-bar reversal, outside-bar reversal.
3. Moving averages, for each of SMA, EMA, WMA, HMA, DEMA, TEMA, KAMA:
   close crosses MA(n), n in {9, 20, 50, 200}; MA(n) slope turns, n in {9, 20, 50}; fast/slow cross for
   (5,13), (9,21), (13,34), (20,50), (50,200); pullback to MA(n) in its trend (MA rising, prior close above, low
   touches MA, closes above; mirror), n in {20, 50}. Plus EMA ribbon (5,8,13,21,34,55) and SMA ribbon becoming fully
   ordered.
4. Other indicators: MACD(12,26,9) signal cross, zero cross; Stochastic(14,3,3) %K/%D cross inside <20 / >80, and
   leaving the zones; CCI(20) breaking +-100 and returning from beyond +-100; Williams %R(14) leaving -80/-20;
   RSI(14) 30/70 return, RSI 50 cross; Bollinger(20,2) close outside (breakout) and re-entry (reversion), squeeze
   break (bandwidth at its 120-bar low within the last 5 bars, then close outside); Keltner(20, 2xATR) close outside;
   TTM squeeze release; Donchian 20 and 55 breakout; Ichimoku Tenkan/Kijun cross and close crossing the cloud;
   Parabolic SAR flip; Aroon(25) up/down cross; ADX(14)>25 with a DI cross; Supertrend(10,3) flip; Heikin-Ashi
   colour change; session TWAP cross; ROC(10) zero cross.

A variant = signal x orientation x underlying x timeframe x exit. Every variant run counts in the corrections.

## Positions

One position at a time per variant (a new entry only after the previous exit minute), at most 5 trades a day per
variant, signals with no option fill are skipped.

## Periods

- LOCKED HOLDOUT: 2025-10-01 .. latest. Not looked at until the end, then run once.
- Pre-holdout (selection): everything before 2025-10-01.
- Walk-forward (anchored, yearly, inside pre-holdout): per family (= signal x orientation), the variant
  (underlying x timeframe x exit) with the best net over all earlier years (>= 30 trades) is traded in the next year.
  Train-only years: up to 2021. Test years: 2022, 2023, 2024, 2025 (Jan-Sep).

## Tests

- Random baseline (THE key test): each real trade is matched with a random entry on the same day, same underlying,
  same option side, a uniform random signal minute in 09:15..15:00, the same contract rule and the same exit.
  p = (1 + #{mean of B random draws >= real mean}) / (B + 1), B = 1000, one-sided. Variants with net <= 0 get p = 1.
  Also reported: coin-flip side baseline for the top 20.
- Benjamini-Hochberg q over ALL variants (net at the real spread). White's Reality Check and Hansen SPA_c over the
  (pre-holdout days x all variants) daily net P&L against not trading (stationary bootstrap, mean block 5, B = 500,
  computed in chunks over every variant).
- Walk-forward: per family; Holm and BH across families on the walk-forward trades vs the random baseline.

## Pass gates (all must hold)

Variant gate: pre-holdout net > 0 at 1.5x spread; BH q < 0.05 vs random; positive in more than half the pre-holdout
years with trades; >= 100 trades.
Family gate: walk-forward net > 0 at the real spread; Holm p < 0.05 vs random on the walk-forward trades; positive in
more than half the walk-forward years.
Anything passing either gate goes to the holdout once. For information only, the top 20 variants by pre-holdout
t-stat of net per trade are also shown in the holdout (nothing is chosen from that).

## Filter study (Liquidity 15+5 as the base)

- Base: Liquidity 15+5 signals from h4's caches (BANKNIFTY/FINNIFTY validated port; NIFTY/SENSEX/MIDCP h4
  re-implementation), 1-ITM near, arm exits, app fills/charges + the same real spread, one at a time per book.
- State of a signal at a Liquidity entry = direction of its most recent event on bars closed at or before the
  Liquidity sig_min: within the last 3 bars for candlestick and chart patterns; unlimited look-back for indicator
  and MA signals (a cross's last direction is the current regime).
- Filters per signal x timeframe: KEEP-AGREE (keep only trades whose state equals the trade side) and VETO-OPPOSE
  (drop trades whose state is opposite). Two bases: all 5 indices pooled, and BANKNIFTY alone.
- Test: kept-trades net per trade vs random subsets of the same size from the same base (B = 2000), one-sided;
  BH over all filter variants. Pass: BH q < 0.05, kept net per trade higher than the base in more than half the
  pre-holdout years, and kept TOTAL net >= base total net x 0.8. Passers -> holdout once.

## Reporting

Rs/day at 1 lot (net per trading day of that index), lots needed for Rs 5,000/day, premium per lot vs Rs 1 lakh,
honest count of variants.

## AMENDMENT 1 (2026-10-08, coordinator relaying Boss) — recorded BEFORE any P&L of these exits

Context, stated honestly: by this time the original 7-exit run, its analysis, its one holdout look (28 variant-gate
passers + top 20) and the filter screen had been done. This amendment adds exits; it changes nothing above.

- Boss: "points" = absolute PREMIUM points (rupees of the option price), not %. Added exits (obuy `Exits(stop_pts=S,
  tgt_pts=T)`, square-off 15:10): targets T in {15, 20, 25, 30} x stops S in {10, 15, 20} = 12 combos; T20/S20 is the
  existing E2 P20, so 11 NEW exits (P15_10, P15_15, P15_20, P20_10, P20_15, P25_10, P25_15, P25_20, P30_10, P30_15,
  P30_20).
- Same signals, orientations, underlyings, timeframes, fills, spreads, positions and gates as above.
- Variant count becomes 195 x 2 x 5 x 6 x 18 = 210,600. BH, White RC / SPA and the walk-forward (families now pick
  among 540 variants) are recomputed over ALL 210,600 variants.
- Holdout: run once for any NEW gate passer (variant or family gate) under the full 210,600-variant correction; for
  information only, the top 20 by pre-holdout t-stat among the 11 new exits. The 7-exit holdout already shown is
  not re-selected from.
- Report Rs per premium point per lot for each index.
