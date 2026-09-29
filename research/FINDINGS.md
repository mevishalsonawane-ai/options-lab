# Research findings (BANKNIFTY intraday option buying)

Written 29 Sep 2026. Read this before proposing a new arm.

## Data
- `research-data` release, `banknifty_year.parquet` (16 MB): 249 sessions, 17 Feb 2025 .. 23 Feb 2026.
  Index 1-minute bars from Upstox v3 (unauthenticated); options from the Kaggle archive
  `samardubey/niftybanknifty-options-data` (1-minute OHLC, volume, OI; broker-scraped, not an official record):
  each day's nearest expiry after the day, strikes within 300 points of the 09:20 index.
  Regenerate with `python research/dump_year.py 250 <path>`; every script reads it as `file:<path>`.
- The repo's own 21 recorded sessions (10 Aug .. 10 Sep 2026) reproduce the app's Lab > Arms numbers to the rupee.

## Method (all scripts)
App rules exactly; decide on a completed 5-minute bar, fill on the option's first minute after it +0.5, exits on the
option's minute lows/highs (stop counted first when both hit in a minute), 15:10 out, Rs 40 a trip, 1 lot of 30.
Optimistic vs live: Rs 40 is below Zerodha's real ~Rs 60-70 a trip, stops fill at the level with no slippage.

## Every arm, one year (net Rs, 1 lot)
| arm | trades | win | net | t | green months |
|---|---|---|---|---|---|
| ORB | 1109 | 45% | -193,592 | -4.94 | 0/13 |
| ORB Fresh | 410 | 47% | -56,777 | -2.39 | 4/13 |
| ORB Sweep (-40/+80) | 291 | 30% | -73,480 | -2.95 | 1/13 |
| Range Fade | 322 | 40% | -84,458 | -4.14 | 4/13 |
| RSI 30/70 reversal (-40/+80) | 235 | 34% | -31,613 | -1.44 | 5/13 |
| Supertrend(10,3)+EMA20/50, 15-minute | 209 | 47% | -22,709 | -1.35 | 5/13 |

Range Fade: +19,916 on days that closed inside the opening range, -104,375 on trend days; -40/+30 and -40/+60 also lose.
The Aug-Sep 2026 month on which Sweep and Range Fade were chosen was an outlier range month.

## Learned model (ml_long.py), walk-forward by month, out of sample Jun 2025 .. Feb 2026
26 features at each 5-minute point 09:45-14:30 (index returns, realised vol, range/opening-range position, TWAP distance,
ATM straddle level and change, CE/PE skew, OI change and PCR over ATM +-200, CE/PE volume, day of week, days to expiry)
-> rupees of buying the ATM CE / PE with -40/+40. 14,442 points.
- Buying blind at every point: CE -Rs 143, PE -Rs 157 a trade on average.
- Rank correlation of predicted vs actual: about 0 (trees +0.016/-0.019, neural net -0.004/+0.004). No signal.
- Trading on it: every bar from Rs 0 to 400 loses; the Rs 600 bar shows +6k..+15k (t 0.8-1.2) but is the best of 12
  variants with near-zero correlation underneath - treat as noise. Coin flip baseline: -70,046.

## Conclusions
1. Buying ATM BANKNIFTY options for fixed +-40 points intraday lost money over this year whatever the entry rule:
   costs (~Rs 70 a trip incl. slippage) plus time decay exceed what direction calls recover.
2. One month of data is not evidence; test every idea on the year file first, walk-forward, out of sample.
3. Not yet tested: defined-risk premium selling (credit spreads / iron fly), which has time decay on its side.

## Green candles (green_candles.py, full stats in GREEN_CANDLES.md)
- During green candles put OI rises and call OI falls (big green 15-min: calls -0.7%, puts +1.9%); big red is the
  mirror (calls +2.4%, puts -0.7%) and the ATM straddle rises. This is concurrent - option sellers moving with price.
  None of the OI / PCR / straddle conditions predicts the NEXT candle (all noise in both halves).
- Next-candle colour: only a small reversal effect survives both halves (after a red / 3+ reds / big red / far below
  TWAP / low RSI: 51-56% green vs 50%), worth +1 to +4 index points on average - below the ~Rs 70 round-trip cost.
- The 20-25 candles before a green candle look the same as before a red one (greens 10.0 vs 10.1 of 20; no steady
  difference in net move, slope, range, OI or straddle). Only the last 1-2 candles carry anything.
- Daily: first 30 minutes green -> 72% green days (red -> 28%); above the opening range at 10:30 -> 74% (below -> 23%);
  gap down > 0.3% -> 69% green days (42 days). Largely mechanical (the early move is part of the day's candle).
- Red candles (RED_CANDLES.md) mirror the green ones: the only steady next-candle effects are small reversals
  (after a green / 3+ greens on 15-min: 51.7% / 54.9% red; after 3+ reds or a big red on 5-min: 46-48% red).
  The 20-25 candles before a red candle look the same as before a green one. Red days: first 30 min red -> 72%,
  below the opening range at 10:30 -> 77% (mechanical); gap down > 0.3% -> only 31% red.
- Runs (RUNS.md): same-colour runs are as long as coin flips on 1-min (2.01 vs 2.00 candles) and slightly SHORTER on
  5-min (1.93) and 15-min (1.86): after 2-4 in a row the next candle keeps the colour only 43-48%. Long runs (5+) are
  rarer than chance. After a candle the price is still beyond its close h candles later 46-52% of the time - a coin
  flip, even after big candles. Days: green after green 45%, red after red 43%; streaks average 1.8 days.
- Candle levels (HOLD_LEVELS.md): an ordinary candle's low (green) / high (red) holds no longer than any level at the
  same distance (5-min: ~49% still unbroken after 15 min, ~23% after 2 h; the open, being closer, breaks sooner).
  BIG 15-min candles (top 20% body, ~150 pts) are different: the green's low is still unbroken after 2 h in ~70% of
  cases vs ~47% for an equally distant level (1st/2nd half 68%/73%), the red's high ~60% vs ~38% (62%/59%).
  Big 15-min candles are rarely fully retraced - but the follow-through test shows no reliable continuation either.
- Volatility (VOLATILITY.md; IV from the ATM straddle, median 12%, 10-90% range 10-17%): big candles come with higher
  IV (~12.8-13 vs ~11.9) and higher recent realised vol, and are ~1.2x the recent average candle range; IV barely moves
  during or after them (+-0.1 pt). The big-15-min "level holds" effect is strongest when IV / recent RV are LOW
  (green low held 2 h: 81% vs 22% baseline in low IV; weaker in high IV: 68% vs 49%). By the candle's own volatility
  (range vs the last 20 candles), WILD 15-min candles hold their level 2 h ~55-57% vs 32-37% baseline; calm ones don't
  (27% vs 27%). Next-candle colour does not change with any volatility measure (reversal after red stays ~51-53%).
  High-IV days are wider (631 vs 464 pts range) and greener (58% vs 41%).
- 9 EMA + VWAP + HH/HL direction filter (TREND_FILTER.md, VWAP = time-weighted, the index has no volume): the next
  15 min go AGAINST the signal slightly (bullish: 47% up); 30-60 min lean its way by only ~2-8 pts over the drift; by
  the close it is gone. Buying ATM options on it: best version (3 HH/HL, -40/+40, sideways filter on) breaks even
  (-Rs 126 on 462 trades, +11k / -11k by half); every other version loses; the 9-EMA trend exit wins only 32%.
  The sideways (VWAP-chop) filter is the useful part: it cuts losses by Rs 16-23k in every pairing.
