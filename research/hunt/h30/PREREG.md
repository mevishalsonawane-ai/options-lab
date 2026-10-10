# h30 pre-registration: signals on the OPTION PREMIUM's own chart (option buying only)

Written BEFORE any P&L was computed. Nothing below is changed after results are seen. Any later addition is labelled
post-hoc in HUNT_H30.md and counted in the variant total.

## Question
Do the chart signals Indian retail buyers read on the option's own premium chart beat costs, and beat random entries
on the same contract, same day, same exits? Buying only, 1 lot fixed, Rs 1 lakh capital.

## Data and universe
- Real Dhan 1-minute option bars with volume/OI (research/obuy/data.py). Nearest expiry ('near': weekly when listed,
  else monthly). NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.
- Contracts per day: strike fixed once at 09:15 from the index open (the chart a trader opens in the morning):
  ATM, 1-ITM, 1-OTM, CE and PE (6 charts per underlying-day). The strike does not move during the day.
- Expiry days skipped (engine default). Days where a contract has < 200 of 375 minute bars are skipped (no chart).
- Lot: the day's lot from the data (obuy Index.lot 'data').
- Pre-holdout (choice data): first available day .. 2025-09-30. LOCKED HOLDOUT 2025-10-01 .. latest, run once at
  the end only for survivors (or, if none, for the single best pre-holdout variant as a descriptive check).

## Candles
Premium candles of 1, 3, 5, 15 minutes built from 1-minute bars aligned at 09:15 (375 = 3x125 = 5x75 = 15x25).
A signal is known at the close of its candle; entry at the OPEN of the next minute (first bar within 2 minutes).
True VWAP = cumulative sum(typical price x volume) / cumulative volume from 09:15 (volume = contracts traded).
Entry window: entry minute 09:20 .. 14:30. ONE trade per chart per day: the first signal (simplest retail rule).

## Signal families (54 signal definitions)
Level breaks use "candle close above level while the previous candle close was at or below it".
1. ORB: break of the premium's own opening-range high. OR = 5 min (tf 1, 5), 15 min (tf 1,3,5,15), 30 min
   (tf 1,3,5,15). 10 defs.
2. PDH: break of the same contract's prior-day high (only when the prior day had the same series and was not an
   expiry day, i.e. the same contract). tf 1,3,5,15. 4 defs.
3. VWAP reclaim: prev close < its VWAP, close > VWAP. tf 1,3,5,15. 4 defs.
4. VWAP hold: 3 consecutive closes above VWAP and the third candle green (first occurrence). tf 1,3,5,15. 4 defs.
5. EMA 9/20 cross up on premium closes (intraday EMAs from the day's first candle; needs >= 9 candles). 4 defs.
6. EMA pullback: EMA9 > EMA20 for >= 3 candles, candle low <= EMA9, close > EMA9, green. 4 defs.
7. HH/HL structure: last two confirmed swing highs rising and last two swing lows rising (2-candle fractals),
   close breaks the latest swing high. 4 defs.
8. Volume spike: candle volume >= 3x the mean of the previous 10 candles (needs >= 5), green candle. 4 defs.
9. Straddle compression then a side wins: on the previous candle both this option and its opposite (same moneyness
   label, other right) closed below their close 30 minutes earlier; this candle closes above the highest high of the
   last 30 minutes. 4 defs.
10. Round-number break: crossing above a multiple of a step set by the day's first open (Rs 5 if < 50, 10 if < 150,
    25 if < 400, 50 if < 1000, else 100), only for levels above the day's first open. 4 defs.
11. Swing-high break: close crosses above the latest confirmed swing high (2-candle fractal). 4 defs.
12. Opposite side collapse: this option closes above its prior intraday high AND the opposite option (same moneyness
    label) is down >= 10% over the last 15 minutes. 4 defs.
CE charts give CE buys, PE charts give PE buys; identical rules on both.

## Exits (menu fixed now; all square off by 15:10; resting stops/targets on the option's bar, engine order)
X1 ARM: Liquidity arm exits = stop 15%, time stop 20 min unless premium >= +5%.
X2 PTS20: stop 20 points, target 20 points.
X3 P15_30: stop 15%, target 30%.
X4 LADDER: stop 15%, profit-lock ladder (LADDER rungs (0.25,0),(0.5,0.25),(0.75,0.5)) with R = 30% of entry, no target.
X5 VWAPX: exit at the next open after the first 1-minute close below the premium VWAP; hard stop 30%.
X6/X7/X8 T15/T30/T60: pure time exits after 15/30/60 minutes (no stop, no target).

## Costs and fills
- Entry fill = next-minute open x (1 + 5 bps app) x (1 + half-spread). Exit = engine exit price x (1 - 5 bps, 10 bps
  for stops) x (1 - half-spread). Charges = obuy Costs('app') (today's rates, STT 0.15%).
- Half-spreads (h24, real book): BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20%
  (not measured; assumption). Stress = 1.5x these.
- GROSS = raw premium move (exit price - entry open) x qty, no spread, no charges. NET = after all of the above.

## Variants
54 signal defs x 3 moneyness x 8 exits = **1,296 variants**. A variant pools all 5 underlyings and both rights.
Per-underlying numbers are descriptive only.

## Tests (pre-holdout data)
- Random baseline: for every contract-day, 20 random entry minutes uniform in 09:20..14:30 on the SAME contract,
  same day, same exit. Per variant: excess = real mean net/trade - mean of matched random nets; one-sided p
  (normal approximation of the random mean's distribution).
- Zero benchmark: t-test of daily net P&L (all pre-holdout trading days, zero on no-trade days) > 0.
- BH and Holm over all 1,296 variants for both p-value sets; Hansen SPA and White RC (stationary bootstrap, block 5,
  B=1000) on the (days x variants) daily net matrix vs not trading.
- Walk-forward anchored by year: for each test year from the 3rd calendar year on, trade the variant with the best
  net over all earlier years; report OOS net.
- SURVIVOR = BH q < 0.10 vs zero AND BH q < 0.10 vs random AND SPA p < 0.10 AND positive net in >= 3 of the
  pre-holdout years AND positive at 1.5x spread. Survivors go to the holdout once.

## Report
Rs/day at 1 lot (per pooled variant and per underlying), lots for Rs 5,000/day, max drawdown, worst day/month,
P(losing month) by bootstrap, capital check vs Rs 1 lakh, honest variant count.

## Amendment A1 (recorded 2026-10-08, before ANY P&L was computed; only the chart cutting had run)
Coordinator / Boss clarification: "points" = absolute PREMIUM points (rupees of option price). The exit menu adds a
point grid: targets +15/+20/+25/+30 premium points x stops -10/-15/-20 premium points (12 combos; the original
X2 20/20 is one of them, so it is not double-counted). Exit menu = X1, X3, X4, X5, X6, X7, X8 + 12 point combos =
**19 exits**. Variants = 54 x 3 x 19 = **3,078**, all in BH / Holm / SPA / RC and the walk-forward pool.
The report also gives Rs per premium point per lot for each index (= the lot size in force).
