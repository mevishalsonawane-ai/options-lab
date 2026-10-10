# h37 pre-registration: Fibonacci, Gann, harmonics, Elliott, Renko, Point & Figure, Market Profile and Wyckoff as option-BUYING triggers (and as filters on Liquidity 15+5)

Written 2026-10-08, BEFORE any P&L of this study was computed. Nothing below is changed after results are seen.
Anything added later is labelled POST-HOC in the report. Not repeated here (done elsewhere): candles, MAs, classic
indicators (h25); pivots, round numbers, OI levels (h35).

## Data, contract, fills, costs (same as h25/h35)

- Index minutes: obuy `Index` (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX). Options: obuy `Options`, Dhan nearest
  expiry minute bars. Constituent stock minutes (NSE_EQ, from 2024-10-07) only for the volume-profile check.
- Bars: 5, 15, 60 minutes anchored 09:15, continuous across days; daily bars (D) built from the index minutes.
  Renko and Point & Figure are built from 1-minute closes (they are price-based, not time-based).
- A signal is known at the close of its bar (`sig_min` = last minute of the bar). Entry = option OPEN at
  `sig_min + 1` (obuy, first bar within 2 minutes). Daily signals are known at the day's close and enter at the next
  trading day's 09:16 open (`sig_min` = 09:15 of that day; the outcome table starts at 09:15).
- Entry window: `sig_min` 09:15 .. 15:00. Expiry days skipped (obuy `expiry="skip"`, the arms' rule).
- Contract: 1-ITM (strike from the index close at `sig_min`), obuy `series="near"`. Bull = buy CE, bear = buy PE.
  1 lot (the day's lot from the data). Option BUYING only.
- Fills/charges: obuy app fills (+-5 bps, stops -10 bps) and `Costs('app')`, PLUS the real half-spread (h24) on BOTH
  legs: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20%. Stress = 1.5x.
- Gross = app-fill P&L before charges and spread.

## Exits (17, fixed now; obuy `Exits`, square-off 15:10, premium exits checked on the option's 1-minute HIGH/LOW,
stop first on a tie; ties are counted and reported)

| id | exit |
|---|---|
| P{T}_{S} (12) | premium target +T points (T = 15, 20, 25, 30) x premium stop -S points (S = 10, 15, 20) |
| ARM | the Liquidity arm: stop -15%, 20-minute time stop unless +5%, square-off 15:10 (`liquidity.ARM_EXITS`) |
| LAD | stop -40 pts, target +40 pts, profit-lock ladder (obuy LADDER, R = 40 pts) (same as h35) |
| T15 / T30 / T60 | out after 15 / 30 / 60 minutes, nothing else |

Extra structural exits for Fibonacci bounces only (extensions as targets): FX127 / FX162 = index target at the 127.2%
/ 161.8% extension of the swing (beyond the swing end by 0.272 / 0.618 of the swing), index stop at the swing origin
(100% retracement), square-off 15:10 (run in the obuy engine directly).

## Objective swings

ZigZag on bar highs/lows with reversal threshold theta = k x ATR14 (Wilder, same timeframe, value at the current bar),
k in {2, 4}. A pivot is CONFIRMED at the first bar whose low (high) is theta below (above) the running extreme; only
confirmed pivots are used, from the confirming bar on. No look-ahead.

## Signals (direction = textbook unless stated; each signal fires at most once per setup)

1. **Fibonacci** (tf 5, 15, 60, D; k 2, 4). Completed swing = last two confirmed pivots (L -> H up-swing, or H -> L).
   Levels lev_r = H - r (H - L) for r in {0.382, 0.5, 0.618, 0.786} (mirror for down-swings). After the confirming bar
   and until the next pivot is confirmed (or the swing origin is broken):
   - FIB_B{r} bounce: first bar with low <= lev_r and close > lev_r, previous close > lev_r -> trade the swing direction
     (CE after an up-swing).
   - FIB_K{r} break: first close below lev_r with previous close >= lev_r -> trade the retracement (PE after an up-swing).
   - Extension targets: FX127 / FX162 exits on the FIB_B entries (above).
2. **Gann**
   - SQ9_BRK / SQ9_BNC (tf 5, 15, 60; ref = today's 09:15 open or previous close): levels (sqrt(ref) + j/4)^2,
     j = +-1..+-4 (45 to 180 degrees). BRK: close crosses a level (prev close on the other side) -> follow the cross.
     BNC: bar touches a level from below (high >= L, close < L, prev close < L) -> PE; mirror -> CE. Each level once
     per day per direction.
   - G1X1 (tf 5, 15, 60; k 2, 4; unit u = s x ATR14 at the pivot bar per bar, s in {0.25, 0.5}): from the last
     confirmed pivot low, line(t) = P + u (t - t_pivot); first close crossing below it (prev close >= line) -> PE;
     from a pivot high the falling line, first close above -> CE.
   - GTC time cycles (tf 5, 15, 60; k 2, 4): bars since the last confirmed pivot in NINES {9, 18, ..., 90} or SQUARES
     {4, 9, 16, 25, 36, 49, 64, 81} (two signals) -> reversal: PE if the close is above the pivot price, CE if below.
     Daily: calendar days since the last confirmed daily pivot reaching {30, 45, 60, 90, 120, 144, 180, 270, 360}
     (first trading day at or after) -> same reversal rule, entry 09:16 that day (GTC_D, k 2, 4).
3. **Harmonics** (tf 5, 15, 60, D; k 2, 4). X, A, B, C = last four confirmed pivots; D = the PRZ level; signal on the
   first bar after C's confirmation whose low (bull) / high (bear) reaches D, if D was not already reached before C was
   confirmed and CD/BC at D is inside the range (5% relative tolerance on the range ends). Bull -> CE.
   | pattern | AB/XA | BC/AB | D | CD/BC |
   |---|---|---|---|---|
   | Gartley | 0.588-0.648 | 0.382-0.886 | 0.786 XA | 1.13-1.618 |
   | Bat | 0.382-0.50 | 0.382-0.886 | 0.886 XA | 1.618-2.618 |
   | Butterfly | 0.756-0.816 | 0.382-0.886 | 1.272 XA | 1.618-2.24 |
   | Crab | 0.382-0.618 | 0.382-0.886 | 1.618 XA | 2.24-3.618 |
   | ABCD | (A, B, C only) | 0.382-0.886 | CD = AB | 1.13-2.618 |
4. **Elliott** (tf 5, 15, 60, D; k 2, 4), from confirmed pivots p0, p1, ...
   - EW3: p0 low, p1 high, p2 low, p2 > p0, (p1-p2)/(p1-p0) in [0.382, 0.786]; first close above p1 after p2 is
     confirmed -> CE (wave 3). Mirror.
   - EW5: p0..p4 (L H L H L), p2 > p0, p3 > p1, p4 > p1, (p3-p2) > (p1-p0); wave-5 target T = p4 + (p1-p0) > p3;
     first bar after p4's confirmation with high >= T -> PE (exhaustion). Mirror.
   - EWC: a valid up impulse p0..p5 (above rules plus p5 > p3), then A = p5->p6, B = p6->p7 with
     (p7-p6)/(p5-p6) in [0.382, 0.886]; first close below p6 after p7 is confirmed -> PE (wave C). Mirror.
5. **Renko** (1-minute closes, reset each day at the 09:15 open; box b = 0.10%, 0.20%, 0.30% of the previous close,
   or 0.5 / 1.0 x ATR14 of 15-minute bars at the previous close; classic 2-box reversal):
   - RK_REV: first brick in the new direction after >= 3 bricks the other way -> follow it.
   - RK_BRK: a brick closes beyond the extreme of the previous 10 bricks of the day -> follow it.
6. **Point & Figure** (same 5 box sizes, 3-box reversal, 1-minute closes, reset daily):
   - PF_DT: double-top buy (X column exceeds the previous X column's top) -> CE; double-bottom sell -> PE.
   - PF_TT: triple-top / triple-bottom (the two previous X tops equal, in boxes) breakout.
7. **Market Profile (TPO)** (signals on tf 5 and 15 bar closes). Prior day's profile: 30-minute TPO periods, bins of
   0.02% of the prior close; POC = most TPOs (tie: nearest the day's mid); value area = 70% of TPOs, built out from the
   POC two bins at a time (standard). IB = 09:15-10:14 range.
   - MP_IB: first close above IB high (or below IB low) from 10:15 on -> follow.
   - MP_80: open outside the prior VA, then two consecutive closes inside -> trade toward the far side (80% rule).
   - MP_OOV: open outside the VA and the first bar closes outside on the same side -> follow (go with the open).
   - MP_VAB: open inside the VA, first close beyond VAH / VAL -> follow.
   - MP_VAF: open inside the VA, a bar touches VAH (VAL) and closes back inside -> fade the edge.
   - MP_POC: first close of the day crossing the prior POC -> follow the cross.
   - Volume profile (secondary check, NIFTY and BANKNIFTY, from 2024-10 only): the same MP_80 / MP_VAB / MP_VAF /
     MP_POC with VP levels, where each index minute's volume = the rupee turnover of the index's constituents present in
     the data (NIFTY 50 / BANKNIFTY 12 lists, fixed now), put in the bin of the minute's typical price. Also reported:
     how often the VP and TPO POC/VA agree.
8. **Wyckoff** (tf 5, 15, 60, D): trading range = previous 20 bars (exclusive) with width <= 4 x ATR14.
   - WY_SPRING: a bar's low breaks below the range low and the same bar or the next bar closes back above it -> CE.
   - WY_UPTHRUST: mirror -> PE.

A variant = signal x parameters (k / scale / box / ref) x underlying x timeframe x exit. Every variant run counts.

## Positions

One position at a time per variant, at most 5 trades a day, first signal first; signals with no option fill skipped.

## Periods

- LOCKED HOLDOUT: 2025-10-01 .. latest. Not looked at until the end; then run ONCE for the passers below.
- Pre-holdout (selection): everything before 2025-10-01.
- Walk-forward (anchored, yearly, pre-holdout): per family (= signal name), the variant (und x tf x params x exit)
  with the best net over all earlier years (>= 30 trades) is traded next year. Test years 2022, 2023, 2024, 2025 (Jan-Sep).

## Tests

- Random baseline MATCHED BY TIME OF DAY (h25's warning): each real trade is compared with buying at a random minute
  within +-15 minutes of its signal minute on the SAME day, coin-flip side (CE or PE equally), same contract rule and
  same exit. The expected random P&L is computed exactly as the mean of the outcome table over those minutes and both
  sides. Test statistic: day-clustered paired t of (real - matched random), one-sided, p from the normal tail; this uses
  the signal trades' own variance. Variants with net <= 0 get p = 1.
- Benjamini-Hochberg q over ALL variants. White's Reality Check and Hansen SPA_c over the (pre-holdout days x all
  variants) daily net P&L vs not trading (stationary bootstrap, mean block 5, B = 500, chunked).
- Walk-forward: Holm and BH across families on the walk-forward trades vs the matched random.

## Pass gates

Variant gate (all): pre-holdout net > 0 at 1.5x spread; BH q < 0.05 vs matched random; positive in more than half the
pre-holdout years with trades; >= 100 trades.
Family gate (all): walk-forward net > 0; Holm p < 0.05 vs matched random on the walk-forward trades; positive in more
than half the walk-forward years.
Passers of either gate go to the holdout ONCE. For information only, the top 10 variants by pre-holdout t of net per
trade are also shown in the holdout (nothing is chosen from that).

## Filter study (base = Liquidity 15+5, h25's trade file: 5 indices, 1-ITM near, arm exits, app costs + real spread)

- State of a signal at a Liquidity entry = direction of its latest event on bars closed at or before the Liquidity
  `sig_min`, within the last 3 bars (Renko / P&F: last 15 minutes; Market Profile and Gann square-of-9: same day).
- KEEP-AGREE and VETO-OPPOSE per signal x params x timeframe; bases: 5 indices pooled and BANKNIFTY alone.
- Test: kept net per trade vs random subsets of the same size (B = 2000), one-sided; BH over all filters. Pass: BH
  q < 0.05, kept net per trade above the base in more than half the pre-holdout years, kept total >= 0.8 x base total.
  Passers -> holdout once.

## Reporting

Gross and net (real spread) and stress side by side; Rs/day at 1 lot (net / trading days of that index in the
period); lots for Rs 5,000/day and whether Rs 1 lakh (fixed lots) can carry them; max drawdown; tie counts; honest
variant count.
