# h46 pre-registration: "VWAP reject" (social-media strategy), option BUYING only, 1 lot

Written 2026-10-08 BEFORE any P&L of this study was computed. Nothing below changes after results are seen.
Anything added later is labelled POST-HOC in the report.

## The rule as shared
EMA9 above VWAP = bullish, below = bearish. A candle that "rejects off VWAP" in the trend direction is the entry.
Buy CE (bullish) / PE (bearish). Exit when a candle closes beyond the EMA9 against the trade.

## Objective definitions (fixed now)
- Bars: tf-minute candles anchored at 09:15 (tf = 1, 2, 3, 5, 15). A candle's signal is known at its last minute's close.
- EMA9: standard EMA (alpha = 2/10, seeded with the SMA of the first 9) on candle closes, continuous across days
  (as a chart shows it).
- VWAP: anchored to the session (resets each day), source hlc3 of each chart candle (TradingView default),
  cumulative sum(hlc3 x w) / sum(w) over the day's candles up to and including the current one.
  - (a) TWAP: w = 1 (the index has no volume). Whole sample.
  - (b) STK: w = summed traded value (close x volume) of the index's constituent stocks in that candle (approximate
    current constituent lists from the 214-stock NSE_EQ minute set; survivorship noted). From 2024-10-07 only.
  - (c) FUT: signals computed entirely on the near-month FUTURES chart (futures candles, futures EMA9, futures
    volume VWAP). Futures minutes exist only 2026-07-29 .. 2026-10-06 = INSIDE the locked holdout. Run once,
    descriptive only, labelled IN-HOLDOUT; it can never be chosen or promoted.
- Trend at the signal candle: EMA9 > VWAP bullish; EMA9 < VWAP bearish (values at that candle's close).
- Rejection (tolerance tol = 0.02% of VWAP):
  - R1 (base) bullish: low <= VWAP x (1 + tol), open > VWAP and close > VWAP (wick tags VWAP, body stays above).
    Bearish mirror: high >= VWAP x (1 - tol), open < VWAP, close < VWAP.
  - R2: R1 plus a green candle (close > open) for bullish, red (close < open) for bearish.
- Entry: buy the nearest-expiry option (obuy `near`: weekly while Dhan has it, else nearest monthly) at the OPEN of the
  minute after the signal candle (first bar within 3 minutes, else skip). Strike from the index close at the signal:
  ATM or 1-ITM. CE bullish, PE bearish. Entry fill minute must be before 15:10.
- Exits (index-based, no premium stop):
  - CLOSE (as described): first candle AFTER the signal candle whose close is below EMA9 (bullish; above for bearish)
    -> sell at the next minute's open.
  - TOUCH (the implied variant): from the entry minute, the first 1-minute bar whose low goes below (bearish: high
    above) the EMA9 of the last CLOSED candle -> sell at the next minute's open.
  - Always: square-off at the 15:10 open (the app).
- Positions: one at a time per index (a new signal is taken only if its candle closes at or after the previous exit
  fill minute); max trades/day 3, 5 or unlimited (first k taken).
- Expiry days: skip (the app's arms' rule) or allow.

## Fills and costs
- App fills (obuy `Fills('app')`: +-5 bps market) and app charges `Costs('app')`.
- Real spread: per side cost = max(half-spread, 5 bps) (X3 lesson: not both) -> extra = (hs - 5 bps) on entry and exit.
  hs: BANKNIFTY 0.16%, NIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20% (h24).
- Gross (Boss's headline) = app-fill P&L before charges and extra spread. Net = gross - charges - extra spread.
- 1 lot = the day's lot from the data.

## Variant count (all counted in BH and SPA)
Per index: tf 5 x rejection 2 x exit 2 x money 2 x max/day 3 x expiry 2 = 240.
- TWAP: 5 indices x 240 = 1,200 variants (2020/2021 .. 2025-09-30).
- STK: 5 x 240 = 1,200 variants (2024-10-07 .. 2025-09-30).
- Total in-sample family: 2,400. (FUT 240 x 5 in-holdout, descriptive, separate.)

## Primary (fixed now)
5-minute, TWAP, R1, CLOSE exit, ATM, unlimited trades/day, expiry skip, NIFTY + BANKNIFTY combined.

## Tests
- Random-entry baseline matched by TIME OF DAY and SIDE (h25 lesson): for each real trade, B = 200 draws of a random
  other eligible day of the same index and calendar year, same signal minute, same side, same money, same holding
  length (exit minute offset, capped at 15:10). p = (1 + #{random mean >= real mean}) / (B + 1). Run for variants with
  net > 0 (others p = 1).
- BH (q 0.10) over the 2,400 p-values; Hansen SPA_c / White RC over the (days x variants) daily net matrix (vs 0).
- Anchored walk-forward by year (first 2 years train; pick best net over earlier years among each index's 240).
- Locked holdout 2025-10-01 .. latest: run ONCE for the primary and for any variant passing BH q<0.10 AND SPA p<0.10
  AND walk-forward net > 0.
- Report: win rate, gross and net Rs/trade and Rs/day at 1 lot, max drawdown, trades/day, per year; chart-day check
  (the best-looking days vs the average day).
