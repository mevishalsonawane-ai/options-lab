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
- 1-minute "institutional" scalp (SCALP_1MIN.md; bank_stocks_year.parquet on the research-data release holds the 12
  constituents' 1-minute bars): HMA(21) turns + VWAP + candle colour + swing-low/high stop with a 1:2 target lose in
  every combination (-Rs 20k to -128k, 27-34% winners). Advance-decline 8:4 makes it worse (27% winners); with HDFC &
  ICICI at their day high/low as well it fires only 10 times a year. Breadth does not call the next 5-30 minutes
  (every advance count: 50% up after 5 min). Narrow-CPR days are NOT more trending: day range 513 vs 583 pts on wide
  -CPR days. Skipping wide-CPR days does cut losses (-28k vs -53k).
- Selling beyond big 15-min candles (SELL_LEVELS.md, on banknifty_year_wide.parquet = strikes within 1,000 pts;
  the 300-pt file biases multi-day holds because far strikes drop out when the market moves away): put spread below a
  big green candle's low / call spread above a big red's high, 100 or 200 wide, exits 15:10 / level break / next day
  / 2 days, with the EMA/VWAP and CPR filters: every version loses (-Rs 110 to -260 a trade, 17-38% winners net).
  Gross is near zero; the ~Rs 140 round trip (4 fills + charges) is the loss. The same spread after ORDINARY candles
  does about the same, so the big candle adds nothing tradable. Expiry week (<= 7 days) comes closest to break-even
  (200 wide: -Rs 28 to -43 a trade) but is still negative.
- The owner's manual method (MANUAL_METHOD.md): guess the direction from the last candles, buy the CE/PE of a
  non-expiry-week contract that fits the capital, sell at +10%, no stop (held up to 5 days). It wins 70-81% of trades,
  but the average loss is ~4x the average win, so the year nets about zero: best mechanical direction rule
  +Rs 18.8k on Rs 20k capital, others -Rs 8k to -41k; 20 coin-flip runs of the same method range -Rs 74k .. +92k
  (median +4.8k) - the direction rule adds nothing beyond luck. Stops (-10/-20/-30%) lower the win rate and do not fix
  it; later entries (11:00, 13:00) are worse. Break-even needs a win rate of ~avg loss / (avg win + avg loss) = 80%.
  With the owner's -33% stop (+10% target): 72-77% winners, average loss ~3x the average win, net -Rs 29k .. +13k on
  Rs 20k depending on the rule; the coin-flip version of the same method ranges -Rs 45k .. +91k (median +23k), so
  the rule adds nothing measurable. Reversing it to profit 3x loss (+30/-10 .. +90/-30): 19-36% winners, mostly worse.
  As the owner meant it - profit : loss = 3 : 1 (target +10%, stop -3.3% of premium): 19-31% winners (break-even is
  ~25% before costs, ~28% after); net -Rs 29k .. +9k on Rs 20k, -Rs 17k .. +2k on Rs 10k; coin-flip direction
  -Rs 30k .. +15k (median -10k). 73% of trades hit the 3.3% stop (~19 premium pts, ~40 index pts - ordinary noise).
- Expiry-day options (expiry_days.parquet: BANKNIFTY 11 monthly expiries, NIFTY 53 weekly, 2025-26). The owner's
  reversal setup (big fall stalls -> CE) is too rare there to judge (1 BANKNIFTY, 3-4 NIFTY days). The owner's general
  style (EXPIRY_SCALP.md: read direction, buy near the money, +10%, repeat): with the 3:1 stop (-3.3%) 13-24% winners
  vs ~28% needed - loses like a coin flip; the stop is inside an expiry option's normal wiggle. With no stop: 83-100%
  winners but a loser costs ~10-13 winners (the option dies by 15:10); several trades a day lose heavily (-Rs 8k ..
  -82k NIFTY); one trade a day at 09:30 was positive for some rules (+Rs 4k..12k) but "follow" and "fade" both were,
  and coin flips reach +Rs 6-11k, so it is the timing / luck, not the direction read.
- COMBINED (COMBINED.md): big 15-min candle (top-20% body) -> wait for a 40% pullback toward its low/high that does
  not break it -> trade its direction, stop at the level, target 2R. The INDEX moves our way: +0.20 R a trade,
  t = 2.35, 285 trades, +0.17 / +0.23 R by half, 9/13 months positive; the same entry after ordinary candles: +0.01 R.
  First direction edge in the study. Bought as ATM options it nets only +Rs 7.5k a year (t 0.27): ~11 index pts a
  trade become ~5.5 option pts, less ~2.3 pts costs and decay. Width (>= 1.2x ATR), low IV and VWAP-band stretch do
  not improve the R edge; RSI(2) hurts; pullback depth matters (25% and 60% lose). Next: carry it with a higher-delta
  instrument (ITM option / futures) and re-check out of sample.
- ADVANCED (ADVANCED.md) on the big-candle pullback (base +0.21 R, t 2.47, 292 trades): FVG entry, Fibonacci zones,
  anchored VWAP, break-of-structure confirmation and TTM squeeze all REMOVE the edge (R -0.42 .. +0.14) - they move
  the entry away from the 40% pullback. GAMMA EXPOSURE (nearest-expiry OI at 09:30, ATM IV, calls +, puts -) splits it:
  short-gamma days (GEX below its trailing median) +0.38..+0.44 R, t 2.9-3.4 for 10/20/40-day medians, +0.40 / +0.40
  by half, 10/12 months, every quarter positive, ATM-option net +Rs 38k on 131 trades; long-gamma days +0.03..+0.08 R.
  Consistent with dealer hedging: short gamma amplifies moves. A TTM squeeze barely predicts big candles (21.6% vs 19.3%).
- The owner's Pine "Supertrend(10,3) + 50 EMA" (ST_EMA_PINE.md), exactly as written (flip + EMA side, reverse, 15:15
  out, no stop): 5-min +8.5 index pts a trade, 357 trades, +Rs 91.5k a year on 1 futures lot BEFORE costs (~Rs 45k
  after), t = 0.85 - not significant; both halves positive; ATM option +Rs 14k, 2 strikes ITM +Rs 29k. 3-min loses
  (-Rs 69k pts / -Rs 53k options); 15-min small (+Rs 23k pts, options lose).
- OUT OF SAMPLE (OUT_OF_SAMPLE.md; previous BANKNIFTY year Feb 2024 - Feb 2025, weekly expiries until Nov 2024):
  big-candle pullback still positive but weaker: +0.09 R (t 1.11, 299 trades, +0.14 / +0.05 by half), ATM options
  +Rs 20.6k, 2 ITM +Rs 29.1k. Both years together ~+0.15 R on ~590 trades. The GEX split did NOT replicate
  (short gamma +0.11, long gamma +0.07) - treat GEX as unproven (that year's nearest expiry was weekly, a different
  OI picture). Supertrend + 50 EMA flips between years: 5-min +8.5 pts -> +0.7 pts; 15-min +6.3 -> +26.8 pts;
  3-min negative both years - no stable edge.
- Trades a day (COMBINED.md): the big-candle pullback gives ~1.2 a day (a quarter of days none, never 3+ with the
  cap of 2). Loosening "big" to the top 30% gives ~1.4-1.5 a day and is the best version in BOTH years
  (+0.23 R t 2.99 / +0.10 R t 1.40; ATM options +Rs 26k / +23k). Allowing up to 4 a day (~2 a day, 3-4 on only
  ~35% of days) halves the edge; forcing 3-4 every day (top 40%) leaves ~+0.05-0.11 R and options lose.
- 5-min candles over 50 pts (BIG_BARS.md, both years): range > 50 on ~36-44 candles a day, body > 50 on ~11-15
  (first hour 36-43% of candles, midday 10-16%). SIZE clusters: after a >50-pt body the next has a 25-32% chance
  (base 15-20%) and a >50-pt range 73-83%; after a quiet half hour (avg range < 30) only ~2%. DIRECTION does not:
  50-55% green whatever came before. Inside a candle, a 40+ pt first 2 minutes finishes > 50 that way 58-59% of the
  time but adds ~0 pts after minute 2 - catching it once it has started earns nothing.
- Options during >50-pt 5-min candles (BIG_BAR_OPTIONS.md): the ATM call/put move ~+-5% (current year, monthly
  options, premium ~Rs 720; ~33 pts on a 68-pt index move) / ~+-8.5% (previous year, weekly options, ~Rs 455). Buying
  the right side at the candle's open would reach +10% by its close only 18% / 41% of the time; the wrong side is the
  mirror. Call + put together barely move (+-0.5%): a 50-pt candle is not a volatility event for the straddle, so a
  straddle bought after a big candle loses in every exit (-Rs 90 .. -170 a trade, both years).
- SWING (SWING.md, both years): daily-chart direction for 1/3/5-day option holds - EMA 20/50, Donchian 20-day
  breakout, daily Supertrend, RSI(2), 5-day momentum/reversal, inside-day, gap recovery. No rule is right on the
  index in both years: every-day rules sit at 42-52% (coin flip 40-60% over 50-250 trades); the Donchian breakout
  won the second year (+Rs 83k, 33 trades) and lost the first; option buying loses for almost everything held 3-5
  days (decay). Using the daily trend as an intraday filter for the big-candle pullback: with-trend better in one
  year, against-trend better in the other - no consistent value.
- DIRECTION MODEL (DIRECTION_MODEL.md; 498 days Feb 2024 - Feb 2026, 30,378 decision points, walk-forward by month,
  35 inputs incl. OI walls, PCR, GEX-like straddle/skew, breadth, per-bank leads, NIFTY lead-lag, India VIX, futures
  basis): 50.9-51.7% right on all moments, 51-55% on the most confident 5-10%; adding NIFTY/VIX/basis changed nothing
  (best 55.4% -> 53.7%). Every classic rule 48-52% (NIFTY led 49.7-50.0%, VIX falling 48.7-49.5%, basis rising
  50.4-51.6%, HDFC+ICICI lead 50.2-50.7%). No input gets near 80%; do not look for it again without new information.
- NON-DIRECTIONAL SELLING (NONDIRECTIONAL.md, both years): naked ATM short straddle is the only structure positive in
  both years - intraday 09:20-15:10 with a 50%-of-credit stop +Rs 60k / +29k per lot; overnight 15:15 -> 15:10 next
  day with a 30-50% stop +Rs 26-28k / +91-104k (t 1.5, worst day ~-Rs 17-28k, margin ~Rs 2 lakh+). The hedged
  versions (iron fly / condor) show large losses but the fill model is too pessimistic for the far strikes (every
  leg at its worst price in the same minute) - not trustworthy yet.
- BUY BOTH SIDES, stop the loser, ladder the winner (BOTH_SIDES.md): loses in all 54 versions, both years
  (t -2.8 .. -5.7). The losing side's stop is usually hit before a move big enough to pay for it, then the
  survivor decays or reverses; about 1 day in 4-7 both sides stop out.
  Owner's settings (-3% stop, +5/8/10% targets, 3 lots a side): loses every way - once a day -Rs 15k .. -210k a
  year, repeated all day -Rs 22-75 lakh (costs on 18-60 round trips a day). A 3% stop is ~15-25 option points,
  inside the normal minute-to-minute wobble.
- MATRIX v2.2 (MATRIX_V22.md): structure-break entry (BOS/CHoCH, 3-bar pivots) with the described risk engine (pivot
  + 1.5-2.5x ATR stop, TP1 half + breakeven, TP2 lock / TP3 4R / ATR ribbon). All 38 in-spec versions lose in BOTH
  years: 5-min about -Rs 2.0-2.8 lakh a year on 2 lots (2.3-2.4 trades a day), 15-min about -Rs 1.3-1.9 lakh (1.1 a
  day). The pivot + ATR stop sits ~265-330 index pts away, so TP1 (1R) is reached on only 1-17% of trades; 67-70%
  end on the opposite CHoCH and ~28% at 15:10, and the index result is ~0 R (direction a coin flip). The one
  positive row (15-min CHoCH only, +22k, t 0.15, previous year) is -56k in the other year: noise.
- MATRIX PINE (MATRIX_PINE.md), the owner's script exactly: close crossing EMA(20) on 5-min, SL 2.7x ATR(14), exit
  at 2.5R or 15:15, no signals 11:00-13:15. Loses in both years: -Rs 1.09 lakh / -1.09 lakh a year on 1 ATM lot
  (1.4 trades a day, 35-38% winners, t -2.1 / -1.4); ITM no better; the TP1 half-exit + breakeven doubles the loss
  on 2 lots; without the dead zone -83k / -48k. TP2 (~390-480 pts away) is reached on 5-7% of trades; 55% end at
  15:15 and ~38% at the stop. The 09:xx entries lose the most (-62k / -77k).
- NON-DIRECTIONAL BUYING (LONG_VOL.md: long_vol.py, long_vol2.py, expiry_straddle.py). Buying the ATM straddle /
  strangle every day loses in BOTH years at every entry time (09:20 .. 14:00), held to 15:10 or with take-profit /
  stop on the pair: -Rs 0.5 to -1.5 lakh a year per lot. Reason: the 09:20 straddle cost a median 835 / 1169 pts
  while BANKNIFTY moved a median ~200 pts by 15:10 (more than the cost on 8% / 1% of days) - the volatility risk
  premium (published: Nifty implied > realised ~74% of the time). No pre-entry filter is positive in both years
  (previous range, NR4/NR7/inside day, CPR, gap, first-5-minute range, straddle vs recent moves, VIX level / rank,
  days to expiry, weekday). Short holds (15/30/60 min at every 15-minute mark): 0 of 66 positive in both years.
  Overnight straddles: +19k / -117k. Events bought the day before: +60k only because of the 2024 election result
  (+86k); the 11 RBI days net -3.6k. BANKNIFTY expiry-day ATM straddle 14:00-14:45 -> 15:20: +14k..25k over 11
  monthly expiries, but the same trade on 53 NIFTY weekly expiries loses at every entry (-22k..-39k): not a
  reliable edge. Buying-only non-directional has no edge in this data.
- LIQUIDITY (LIQUIDITY.md, liquidity_break.py; levels in indicator/liquidity.py after LuxAlgo's Liquidity Swings
  pivot 20 full range + Liquidity Pools 2 contacts / 5 bars / 10 confirm): buy the option on a close through a
  liquidity level, sell at the next liquidity. 1- and 3-minute: many trades, lose in both years (-Rs 0.1 to -3.7
  lakh). 5-minute "either" / "swing" / "pool": lose. 1-hour: 0.1-0.3 trades a day, nothing positive in both years.
  Only the CONFLUENCE version ("both": a pool broken where a swing zone sits) is positive in both years: 15-min with
  the failed-break stop +Rs 28k / +25k (134 / 112 trades, 0.5 a day, t 1.47 / 0.73, index +19.5 / +16.8 pts a trade),
  15-min no stop +18k / +37k, 5-min with stop +3k / +33k. Pooled t about 1.3 and it is the best of 48 variants a
  year, so it is a candidate for paper testing, not a proven edge.
- LIQUIDITY + WAIT 5 CANDLES (LIQUIDITY_WAIT5.md): after a liquidity level is found or broken, wait 5 candles, read
  the direction (net move / candle colours / higher highs-lows / held beyond the level), buy the call or put, sell at
  the next liquidity. 3, 5 and 15-minute, swings / pools / either / both: 0 of 88 versions make money in both years
  (most lose -Rs 0.2 to -3 lakh a year); win rates 36-60%, index move per trade around 0. The same 5-candle read
  after an ordinary candle (control) does about as well or badly: the liquidity event adds nothing to the read.
- LIQUIDITY, MORE TRADES (LIQUIDITY_MORE.md): shorter swing lookback (5/10), faster pool confirmation (5), 3-minute
  charts and "either" levels raise trades to 3-10 a day but every version with 5+ trades a day loses in BOTH years
  (-Rs 0.5 to -2.8 lakh a year). The most that stayed positive in both years: the 15-min and 5-min "both, lookback
  20, confirm 10, stop" rules run side by side, ~2 trades a day, +Rs 58k / +32k (t 1.05 / 1.08) - still not proven.
- LIQUIDITY 15+5 ON OTHER INDICES (LIQUIDITY_INDICES.md): NIFTY with real options loses both years (-Rs 44k / -22k;
  index only +0.3 / +1.7 pts a trade). FINNIFTY index +5.8 / +3.6 pts a trade (t ~1.9), estimated options +40k / +6k
  (no option history to confirm; monthly-only, thinner). SENSEX +4.5 / -0.4 pts, estimated negative. Adding indices
  does not add a reliable 6-8 trades a day: only BANKNIFTY (real options) and maybe FINNIFTY (estimate) hold up.
- LIQUIDITY 15+5 + PREMIUM STOP (LIQUIDITY_STOP.md): the owner's 15% stop (now in the app) keeps both years positive:
  +Rs 55.6k / +21.6k per lot (vs +58.4k / +31.7k with no premium stop); it is hit on 18% / 6% of trades. 10% helps
  one year (+83.5k) and turns the other negative (-5.2k); 20-25% change little.
- LIQUIDITY 15+5 ON FINNIFTY, REAL OPTIONS (LIQUIDITY_FINNIFTY.md): the owner's upload covers 4 expiries (57 days, not
  two years). With the 15% stop: 104 trades, +Rs 24k per lot of 65, but all of it from March 2026, where the chart is
  rebuilt from option prices (unreliable). On the real index (83 trades, Oct 2024 / Mar 2025 / Oct 2025) -Rs 6.0k;
  BANKNIFTY on the same days -Rs 2.2k. Not confirmed: keep FINNIFTY on paper until more data says otherwise.
- LIQUIDITY 15+5 "PLUS", PER INDEX (LIQUIDITY_NIFTY_PLUS.md, LIQUIDITY_FINNIFTY_PLUS.md, LIQUIDITY_SENSEX_PLUS.md; ~30-50
  variants each, chosen on one year and tested on the other):
  NIFTY (real options): baseline -66k / -28k; nothing holds both ways (the Year-A pick, 10-min + filters, +37k / +6k
  on 33 held-out trades, t 0.8; the reverse fails). Profit lock and 1 trade a day per book help but it still loses.
  FINNIFTY (index, options estimated): 5-min + 30-min books (30 replacing 15) +8.2 / +4.8 pts a trade (t 2.6 / 2.4),
  the only change that holds both years; on the 4 real-option months it breaks even. Skipping expiry days hurts.
  SENSEX (index only, all Rs estimated): the Year-B pick (15 + 3-min books, entries 10:15-14:30, first-hour range
  >= 0.8x its 20-day median) est. +36k / +25k; the Year-A pick fails; under a strict option model all negative.
- LIQUIDITY 15+5 ON XAUUSD (LIQUIDITY_GOLD.md, Dukascopy 1-minute, Oct 2023 - Sep 2026, spot gold, spread + $7/lot):
  London + New York session loses (buys only 15+5 -16k / +8k / -25k USD per lot a year). India hours, buys only:
  15+5 -1.7k / +12.1k / +22.9k (t 1.7 over three years) - one losing year and a strong gold uptrend; not proven.
- XAUUSD 1-HOUR (LIQUIDITY_GOLD_1H.md): buys only on 1h charts is positive every year in three versions - 1h held
  overnight (London+NY +7k/+21k/+65k; all day +1.5k/+15k/+61k per lot) and India-hours 1h+15m (+1.6k/+8k/+31k, t 2.8).
  Beats random buys of the same holding time (+2.3 to +10.6 vs +0.1 to +2.0 USD/oz a trade). Few trades (88-155 in 3
  years) and the best of 12 versions: a candidate for paper trading, not proven.
- LIQUIDITY AS A DIRECTION FILTER FOR THE NORMAL ARMS (LIQUIDITY_DIRECTION.md): last level taken (5m / 15m), last
  liquidity sweep, nearer liquidity ("draw"), two BANKNIFTY years with real options. No reading predicts the index in
  both years (year A +4-6 pts for taken15/draw, year B gone or reversed) and none makes ORB, ORB Fresh, ORB Sweep or
  Range Fade profitable in either both years. Sweeps were not reversals (price tended to continue). Not worth adding.
- CHANDELIER EXIT TREND NAVIGATOR, ATR 7 x 2 (CHANDELIER_EXIT.md): BUY -> CE, SELL -> PE, 5/15/30/60-min, exits on the
  opposite signal or SL 1 ATR / TP 1-3R, two years real options. 5-min loses every way (-30k to -121k a year); only
  60-min SL1/TP1 is positive both years (+38k / +6k, t 1.1 / 0.2, best of 16). Not a reliable strategy.
- PROFIT-LOCK LADDERS (PROFIT_LOCK.md): 25% of target -> breakeven, 50% -> lock 25%, 75% -> lock 50% cuts ORB's loss
  from -218k / -194k to -77k / -70k and ORB Fresh's from -69k / -57k to -17k / -6k (both years), but neither turns
  profitable; no ladder helps ORB Sweep or Range Fade.
- PROFIT LOCK ON LIQUIDITY 15+5 (LIQUIDITY_LOCK.md): the 25/50/75 ladder against a reference target of 15-100% of the
  premium never beats the arm in both years (arm +55.6k / +21.6k; best ladder 60%: +40.5k / +29.6k). The arm's own
  exits (next liquidity, failed break) already do this job; the ladder cuts its big winners. Not added.
- LIQUIDITY 15+5 WITH A +40 TARGET (LIQUIDITY_TARGET40.md): +40 alone -36.9k / +35.9k, +40 with the profit lock
  -61.9k / +21.5k, against the arm's +55.6k / +21.6k. Capping the target cuts the winners that pay for the arm. Not added.
- LIQUIDITY 15+5, EXITING SOONER ON A TURN (LIQUIDITY_REVERSAL.md): a 30-point index stop beyond the broken level plus a
  time stop (not +5% after 20 min) lifts the arm from +55.6k / +21.6k to +103.0k / +30.3k and cuts the worst trade from
  -15.7k to -9.6k; better in both years (best of 11 variants). A 10% premium stop fails year B.
- LIQUIDITY 15+5 WITH A TRAILING PROFIT STOP (LIQUIDITY_TRAIL.md): keeping 50% of the best profit (the owner's idea)
  turns year A negative when it starts early and is mixed when it starts late; it halves the average winner. Not added.
- EXPIRY DAY, SAME-DAY OPTION (EXPIRY_SAME_DAY.md): on 11 BANKNIFTY monthly expiry days, buying the expiring option
  instead of next month's gives +11.7k vs +0.8k (ORB family better, Liquidity 15+5 slightly worse). Small sample.

## Majority Rule 14, NIFTY weekly (R13, 11 Oct 2026) - for every agent, human or app
Full study: `HUNT_R13_MAJORITY_RULE.md`. MR(14) = % of the last 14 weekly closes above the previous close; 28.57 = 4 of 14 up.
- Rare: 13 touches in 20 years (about 8 sell-off episodes). Too few to prove anything sharp.
- Boss's rule (touch, then a green week, then "+1,000 points"): NOT special. "+1,000 before -500" 33-50% vs 36% for random
  green weeks; with a +1,000 target / -500 stop on 1 futures lot: -Rs 69k to +Rs 39k over 20 years.
- What is real: after a deep weekly sell-off NIFTY drifts up (4 weeks +6.2% vs +0.9%; 26 weeks +18.4% vs +6.2%). It is
  the generic "buy after a deep sell-off" effect, not the number 28.57 (neighbouring thresholds, length 20, daily: nothing).
- Option buying on it: break-even to losing (high VIX after sell-offs makes calls dear; the bounce is slow).
- As an intraday direction filter: no usable tilt (57.5% vs 46.6% up-days the next week, p=0.17; nothing over 4 weeks).
- Decision (Boss, 11 Oct): no strategy, no alert. Agents may use it only as slow context ("deep weekly sell-off zone:
  slow upward drift likely over weeks, intraday direction unaffected, option premiums expensive") - never as an entry
  trigger, never to raise size or risk. Live state on 11 Oct: touched in the week ending 1 Oct 2026 (NIFTY 22,422).

## ORB re-entry churn (R14, 12 Oct 2026) - cap the re-entries, do not "let it run"
Full study: `HUNT_R14_REENTRY_CHURN.md` (code `hunt/r14/`, pre-registered, holdout 1 Oct 2025 - 5 Oct 2026 read once).
- ORB re-entered on the next bar while BANKNIFTY stayed beyond the range: 9 entries a day (max 23 on one side). The
  re-entries made about Rs 0 before charges (Rs 75-110 a trade); charges = 36% of the winners' gross.
- At most 1 entry a side a day: ORB -Rs 6.50 lakh -> -0.97 lakh (design), -1.89 -> -0.18 lakh (holdout); orders 15,640 ->
  1,924. ORB Fresh -2.00 -> -0.97 lakh, -38k -> -18k. Confirmed in the holdout (p < 0.001 / 0.054).
- "Let it run" (no +40 target, trail 75% of the best gain from +40): no effect (+Rs 14k in 4 years, p 0.16; holdout -0.4k).
- A damage limiter, not an edge: both arms still lose, and random thinning does about as well (the gain is the charges).
- App: paper default 1 a side a day (strategy lab, undoable); live only with Boss's PIN. No other strategy churns (Sweep and
  Range Fade already stop at 2 a day; the rest are 1 a day or signal-change only).

## Daily filters on Liquidity BANKNIFTY (R15, 12 Oct 2026) - none helps; add-ons go to a shadow test
Full study: `HUNT_R15_DAILY_FILTERS.md` (pre-registered `hunt/r15/PREREG.md`); shadow specs: `PREREG_LIQUIDITY_ADDONS.md`.
- Data: the Dhan token expired on 10 Oct and its cache is not in the repo, so only 2 years could be used (release
  parquet Feb 2024 - Feb 2026 + free NSE bhavcopy + Yahoo daily index/VIX). TODO for a full test: a new broker token.
- Base (app exits, ATM, real spread and charges, 1 lot): design Feb 2024 - Sep 2025 +Rs 176/day (743 trades, t 1.27);
  holdout part Oct 2025 - Feb 2026 -Rs 16/day (223 trades). Puts +Rs 228 a trade, calls -Rs 60 (design).
- 0 of 5 pre-open filters passed (quiet yesterday, low VIX, extreme PCR, futures OI build-up, after a -1% day; best
  BH q 0.50). Low-VIX days were GOOD for the arm (skipping them -Rs 73/day). Do not add a daily filter.
- Pre-registered for SHADOW only (engine `LiquidityAddons`, default OFF): A1 OI veto (x2 R1), A2 calls need OI (x2 R5),
  A3 Night R3 BANKNIFTY leg. Judged at 300 Liquidity trades (A1/A2) / 100 nights (A3) with Holm; the shadow alone is
  too small to prove A1 (expected +Rs 30-40 a trade vs a Rs 55-60 standard error) - it mainly guards against harm.

## Dealer gamma exposure, intraday (R28, 14 Oct 2026) - size of the day only; no arm filter
Full study: `HUNT_R28_GEX.md` (pre-registered `hunt/r28/PREREG.md`; lake Aug 2021 - Oct 2026; holdout read once).
- GEX = Rs of index delta the option market-makers must trade per 1% move (gamma x OI x spot^2 x 1%), built every
  minute from the ATM+-10 option prices and OI. 12 GEX features are in the Data Brain (`gex_*`).
- Negative GEX at 10:00 (dealers long calls / short puts) came before a bigger rest-of-day range on all 5 indices
  (e.g. BANKNIFTY 0.87% vs 0.75%, 199 / 397 design days). It does not tell trend from chop, ORB runs, expiry pins,
  hero blasts or the next gap.
- 0 of 10 GEX filters on the arms passed (ORB x4, Range Fade x2, Liquidity, Hero, lottery, Night). On a losing arm
  any skip "loses less", and GEX skipped no better than random days. On Night R3 it cut the holdout from +Rs 249 to
  -Rs 18 a day (224 trades). Do not filter any arm by GEX; Jarvis may mention it as information about the day's size.
- R28b (Boss's follow-up): 378 patterns of the 30 one-minute candles before each arm's entry (alone or with the GEX
  sign), walk-forward on design years: 0 passed BH, no forward candidate. On Night R3 every picked pattern cut profit.

## What is predictable? (R16, 12 Oct 2026) - only the size of the next 30-60 min move, not its direction
Full study: `HUNT_R16_PREDICTABILITY.md` (pre-registered `hunt/r16/PREREG.md`; 24 holdout tests, one BH family).
- Data: release 1-min BANKNIFTY (Feb 2024 - Feb 2026) and NIFTY (Apr 2024 - Apr 2026), Yahoo daily index/VIX
  2010 - Oct 2026, NSE bhavcopy OI 2022 - Oct 2026. Locked holdout = last ~6 months of each, read once.
- PREDICTABLE: range of the next 30/60 min (both indices, q 0.003). Holdout error 15-20% below the time-of-day
  average, 10-15% below VIX-implied. A 4-input formula (usual range at this time, last 30/60-min range, VIX) keeps most
  of it (13-14%). Forecast fifths separate quiet from busy half-hours ~2x (BANKNIFTY 80 vs 154 pts).
- Not beyond VIX: next-day range and rest-of-day range from 09:30 (VIX alone is as good; use it for "expected range").
- NOT predictable: direction 15/30/60 min (holdout AUC 0.509-0.536; every option-buying version loses Rs 12-142 a
  trade after costs; an ATM round trip costs ~Rs 133 BANKNIFTY / ~Rs 98 NIFTY); trend vs range day from the first
  30/45 min (AUC 0.46-0.52); expiry close vs max pain (max pain is WORSE than "no change": 0.74% vs 0.43% BANKNIFTY,
  0.58% vs 0.52% NIFTY); "days like today" analogs (worse than all past days pooled, all 8 cases).
- App: build an expected-range nowcast (stop/target sizing, quiet-market warning, Jarvis); show analogs only as
  context, never as a forecast; no direction / regime / max-pain models.

## Better exits (R18, 13 Oct 2026) - none of five early-exit overlays helps; the cause of the low capture is open
Full study: `HUNT_R18_EXITS.md` (pre-registered `hunt/r18/PREREG.md`, amendment 1; 60 tests in one BH family).
- Data: release 1-min BANKNIFTY (Feb 2024 - Feb 2026) and NIFTY (Apr 2024 - Apr 2026) with chains and VIX. The holdout
  (122 / 120 sessions) is R16's, which R16 had already read: a REUSED holdout, so its numbers are descriptive only. One
  switch (`R18_SOURCE=lake`) re-runs it on the 6-year Dhan lake as a new experiment with a fresh holdout (from 27 Apr
  2026; design up to 13 Apr 2026, the last date already examined; embargo between).
- Five overlays on every arm's own exits (each only exits earlier or raises the stop): VWAP band trail, next
  support/resistance, 45-minute time stop, R16 expected range used up, breakeven after +15%.
- 0 of 60 passed (best BH q 0.48). Today's exits stay on every arm; no shadow line was added.
- The losing arms keep about 0% of the best price their option reached (ORB -0.4% on 320 trades, Pine -0.1% to -3.0% on
  632-1,173, Sweep / Range Fade -5% on 460-493); the best overlay moved their loss by 1-9%. None of these five early-exit
  overlays helps; the cause of the low capture is not established (entries or exits).
- Liquidity BANKNIFTY (frozen, study only): every overlay made it worse (+Rs 96.6k own exits on 336 design trades; the
  overlays -Rs 0.1k to -76k). Proposal for after 26 Oct: keep its exits.
- Post hoc, design only: "exit at the next support/resistance" helped 9 of 10 non-frozen arms (+Rs 64.8k on 4,964
  trades) - worth one pre-registered test on the lake, judged on its fresh holdout; the reused holdout is no evidence for it.
- Corrected after a review (expiry-day square-off at 15:05; no fill after the square-off) and re-run with the same 60
  rows: only VIX divergence BANKNIFTY (3 trades) and one Pine EMA NIFTY holdout trade changed; no verdict changed.
- Order-flow exits not tested (recording began 12 Oct). Hero: 10 trades in two years, too few to judge.
## Boss's Rs 500 give-back trail on Liquidity (R19b, 13 Oct 2026) - fails; keep the plain rules
Full study: `HUNT_R19B_LIQUIDITY_TRAIL.md` (pre-registered `hunt/r19b/PREREG.md`; design Feb 2024 - Feb 2025, holdout
3 Mar - 22 Aug 2025 read once; 14 hypotheses, one BH family; release data, Liquidity BANKNIFTY 15m and 5m books).
- Boss's rule ("down Rs 500 a lot from its best, sell", from +Rs 500) made both books earn LESS. Holdout: 15m +Rs 4,584
  vs +Rs 14,633 on plain rules (27 trades), 5m +Rs 2,968 vs +Rs 22,124 (83 trades).
- It cut 12 of the 13 big winners (Rs 62,175 given up). It did worse than random early exits (beat 3% / 0.2% of twins).
  BH q >= 0.92 for every variant (Rs 300 / 750 / 1,000 give-backs, from the entry, 3% of premium).
- Why: a BANKNIFTY option moves Rs 500 a lot (about 17 points) in a minute or two, so the trail fires on half the trades
  and cuts the runners that pay for the arm. The same lesson as LIQUIDITY_TRAIL.md and R12.
- Today's MIDCPNIFTY trade (-Rs 864) would have been about +Rs 2,000 under it. One trade; the history says the opposite.
  MIDCPNIFTY itself was not tested (no option minutes without the Dhan API).
- App: not added to the arm. Boss's opt-in paper switch only ("Try the ₹500 trail on paper", off by default, "failed
  research, your choice"); never live; its trades are labelled and kept out of the plain-rules paper record.

## Consistency: Boss's +Rs 1,000 / Rs 500 floor on every arm, and account day rules (R19c, 13 Oct 2026) - fails; the leak is the ORB family
Full study: `HUNT_R19C_CONSISTENCY.md` (pre-registered `hunt/r19c/PREREG.md`; objective = more green days, a worst day and
drawdown no worse, net > 0 and >= 70% of plain; design before 1 Apr 2025, holdout from 1 Apr 2025 read once; 61
hypotheses, one BH family; release data, 1 lot each arm).
- Boss's floor ("from +Rs 1,000 a lot, sell Rs 500 below the best") failed on all 10 arms, as did +Rs 750 / +Rs 1,500 and
  Rs 300 / Rs 700 give-backs. It raises green days (holdout: Solo 52% -> 80%, n 75; VIX divergence BANKNIFTY 32% -> 68%,
  n 37; Liquidity 5m 40% -> 50%, n 170) and removes the "in profit, ended in loss" trades. But it gives up far more than 30% of
  net (Liquidity 15m +Rs 23,373 -> -Rs 6,911; 5m +Rs 27,342 -> +Rs 6,675; Solo +Rs 42,457 -> +Rs 18,451). It leaves the
  worst day unchanged, and random early exits do as well.
- Why: red days come from trades that never reached +Rs 1,000 (they hit their stop), which the floor never touches; Rs 500
  a lot is 14-17 BANKNIFTY / 7-8 NIFTY option points, so the floor cuts the few long runners that pay for each arm.
- ORB / Fresh / Fade: the ladder locks before +Rs 1,000 (the whole +40 target is Rs 1,200 a lot at lot 30), so the floor on
  top changes <= Rs 3k; the floor INSTEAD of the ladder is worse (ORB -Rs 18,026 vs -Rs 10,958, n 207).
- ORB, ORB Fresh, ORB Sweep and Range Fade lost in BOTH years. Switched off: holdout net -Rs 1.29L -> +Rs 68.9k, max drawdown
  -Rs 1.71L -> -Rs 35.2k, green months 4 -> 8 of 12 (design +Rs 7.5k -> +Rs 116.8k). It passed the daily and net gates in both
  years but failed the pre-registered colour sign test (days with only ORB-family winners become no-trade days).
- Day loss cap (-Rs 2,000 / -3,000, no new entries) lowers green days (42% -> 36% / 39%): it blocks the afternoon recoveries.
  Day profit lock (+Rs 2,000 -> keep +Rs 1,000) raises green days (42% -> 49%; on top of ORB-off 44% -> 53%, post hoc), but
  failed net / drawdown or its random twin. It is the next pre-registered test (R19d), on fresh data.
- Not tested: MIDCPNIFTY (no option minutes in the release or the IraAlgo data repo), MCX (no data), Night (inputs
  missing; ~11 open minutes to act), Pine (already locked).
- App: nothing acts by itself. Liquidity's opt-in "your choice" paper switch now uses Boss's exact rule (off by default,
  labelled failed, never live). Recommendation to Boss: switch off the ORB family; no strategy guarantees no losses.
## Profit lock for Liquidity 15+5 (R19, 13 Oct 2026) - none helps; Liquidity keeps no lock
Full study: `HUNT_R19_LIQUIDITY_LOCK.md` (pre-registered `hunt/r19/PREREG.md`; 12 tests, one BH family).
- Asked after a Liquidity 5m MIDCPNIFTY trade went about +Rs 2.5k and then left at -Rs 864 on the index stop.
- Tested 6 locks that only add an earlier exit (a resting stop that only rises) on BANKNIFTY 15m and 5m, the app's
  rules and real option prices: an index-distance ladder (breakeven at 50% of the way to the next level, keep 25% / 50%
  of the premium gained at 75%), breakeven once +10% / +20%, give back 50% of the best gain after +10% / +20%.
- 0 of 12 passed. Walk-forward lost on both books (15m -Rs 15,125, 5m -Rs 6,083). On the holdout (1 Aug 2024 - 14 Feb
  2025, 136 sessions, 36 + 103 trades) 11 of 12 lost against the plain rules; the only gain was +Rs 452 (p 0.40); every
  BH q 0.97.
- Why: Liquidity's profit comes from a few trades that run to the next level, and a lock sells them early. The give-back
  that would have saved Boss's trade (keep half after +10%) was the worst lock: -Rs 22,262 (15m) and -Rs 24,637 (5m) on
  the holdout.
- MIDCPNIFTY could not be tested: the release has no MIDCPNIFTY data and the Dhan lake is not published. It stays on the
  plain rules until it is tested on its own data. App: no change, paper or live.
## Adaptive opening range for the ORB (R20, 13 Oct 2026) - no variant passes; on a wide-range day the ORB simply sits out
Full study: `HUNT_R20_RANGE.md` (pre-registered `hunt/r20/PREREG.md`; 7 variants, 14 holdout tests in one BH family).
- Facts (BANKNIFTY, plain 09:15-10:00 range): no ORB trade on 15.2% of design days (55 of 361) and 12.3% of holdout days
  (15 of 122); a late break after 14:00 on such a day (like 13 Oct) came 13 times in 361 design days and 4 in 122
  holdout days, and the ORB trade on it made -Rs 567 in all (13 trades, design) and +Rs 925 (4, holdout).
- 15- and 30-minute ranges trade more and lose more (A15 -Rs 34,682 over 416 design trades vs plain -Rs 14,772 over 337);
  the adaptive range (45 minutes when W45 <= k x ATR14, else 15), the 14:30 last entry and the wide-day fade all failed
  the design gates; best holdout (B0.5, +Rs 3,078 over 122 sessions) had BH q 0.91 and lost Rs 19,849 in design.
- App: keep the plain range; the ORB family stays paused on paper. Wide-range days are rare and not why the ORB loses.
## Support and resistance levels as the ORB range (R20b, 13 Oct 2026) - no variant passes; levels are no better than random lines
Full study: `HUNT_R20B_SR_RANGE.md` (pre-registered `hunt/r20b/PREREG.md`; 7 variants, 14 holdout tests, one BH family of
28 with R20).
- Levels tried: yesterday's high / low / close and floor pivots, the Liquidity arm's swing and pool zones (15-minute),
  the biggest put / call OI strikes (previous-day NSE bhavcopy, and the minute OI at 10:04); used as the range, to clip a
  wide range, to confirm a break, or for bounce trades inside a wide range.
- BANKNIFTY: every variant lost in design (361 sessions) except the OI walls as the range (+Rs 1,745 over 98 trades,
  only 2 of 4 blocks positive), which lost Rs 3,972 over 57 trades in the 122-session holdout. Best holdout p-values: 0.31
  (twin), 0.50 (t-test); nothing survives BH. Levels mirrored at random to the other side of the price made more on
  average than the real ones in 6 of 7 variants (random-level twins, 1,000 each), so the level positions add nothing.
- App: no change; the ORB family stays paused on paper with the plain 09:15-10:00 range. Levels stay as context only.
## Support / resistance breaks confirmed by 2 candles (R20c, 13 Oct 2026) - not an edge; no app change
Full study: `HUNT_R20C_SR_BREAK.md` (pre-registered `hunt/r20c/PREREG.md`; 12 holdout tests, BH with R20 / R20b's 28).
- Event study, design (BANKNIFTY / NIFTY / FINNIFTY, 1,121 sessions, 7,548 two-close breaks of PDH/PDL, R1/S1,
  liquidity zones, bhavcopy OI walls, opening range): to the close +0.02 ATR14, 51% further on, 32-49% back through the
  level within 30 min; random levels at the same distance about the same. Holdout: -0.004 to -0.020 ATR.
- The 2nd candle sorts real from false breaks, but its wait costs 14 (5-min) / 18 (15-min) points (BANKNIFTY 23 / 30)
  and leaves about +4 points; a 3rd candle costs more and gains nothing.
- 0 of 4 option-buying rules passed (best BH q 0.16). 2-close R1/S1 on 5-min: design Rs -21,292, holdout +25,603, but
  random levels made +19,734 on average (momentum, not the level). Low-VIX 15-min rule: design +66,057, holdout -33,153.
## Catching expiry zero-to-hero moves (R21, 13 Oct 2026) - nothing passes; the Hero arm keeps its rules
Full study: `HUNT_R21_HERO.md` (pre-registered `hunt/r21/PREREG.md`, commit 78e9761; 9 variants, one BH family).
- Why Hero missed 13 Oct: straddle peaked +13.7% (needs 15%) and the 0.25% momentum minutes (14:40, 14:43-44) never
  met it. In the data that is rare: the straddle condition held on 32 of 33 design expiries; momentum (9 of 33) binds.
- Hindsight anatomy (33 design expiries, NIFTY + BANKNIFTY, Apr-Oct 2025): some Rs 1-10 option at 13:00-14:30 ran 5x
  before 15:05 on 24 days, 10x on 11, 20x on 7; median give-back from the peak by 15:05 68%; 11 of 24 back under 2x.
  Nothing at 13:00 (trend, gap, max pain, OI walls, VIX, call-vs-put change) separated those days; the winning side
  went with the trend since 12:00 on 12 of 24.
- Holdout (31 expiries, read once): one-sided trigger (option 2.5x its low since 12:00 + momentum) 11 trades, 0 wins,
  -Rs 35,188; with window to 15:00 and Rs 1-15 band 15 trades, 0 wins, -Rs 46,268 (design had been +Rs 44,527 on 10);
  straddle 10% in the last 15 min 13 trades, 1 win, -Rs 18,837; plain Hero 2 trades, -Rs 6,259; blind 13:30 buy
  30 trades, -Rs 66,764. Random-time twins did better than every trigger (best BH q 0.054, the blind control).
- Exits did not help: no holdout trade reached the 3-4x that arms a lock or trail; the plain 15:05 exit lost more
  (-Rs 61,863) than the app's -60% stop.
- Deciding it needs the 6-year Dhan lake (~300 NIFTY weeklies): `R21_SOURCE=lake python3 research/hunt/r21/run.py all`.
## The morning's three 15-minute candles as support and resistance (R22, 13 Oct 2026) - not real levels; no rule passes
Full study: `HUNT_R22_ORB_15M_LEVELS.md` (pre-registered `hunt/r22/PREREG.md`, commits 19a8660 / 7dee09e; 6 rules, BH
family of 58 with R20 / R20b / R20c).
- Levels from 09:15-09:59 only (high, low, mid, each candle's high / low, 0.5x / 1x extensions); events 10:00-15:00 on
  BANKNIFTY / NIFTY / FINNIFTY (design 1,121 sessions). A touch of the high / low was turned back (3 closes inside) 29%
  of the time (347 of 1,211); lines at the same distance in the wrong place (mirror twin) 29% too; holdout 22% vs 30%.
- A 5-min break held 63% (688 of 1,099; twin 57%), but a held break was further on at 15:29 only 47% (-0.008 ATR14).
  Break-and-retest is not better: retested 82%, the retest closed beyond 78%, then 45% further on (-0.020 ATR, 442).
  The 1x extension was reached after 31% of breaks (17% before a close back inside). Mid and inner candle levels: same.
- The one design split (wide-range days carried +0.06 ATR, narrow faded -0.02) reversed in the holdout (-0.05 / +0.045).
- 0 of 6 option-buying rules passed; all lost in the holdout. 15-min close break Rs -8,573 design / -8,882 holdout vs
  today's ORB -15,603 / -29,117 (loses less, still loses; mirrored levels made +16,040). Retest -20,547 / -6,095;
  1x-extension target -1,17,565 / -53,564 (stop hit on 486 of 665); mid stop +21,472 / -27,859; skip wide -7,785 / -7,529;
  skip narrow -2,751 / -9,915. Best R22 BH q 1.00. App: no change; the ORB stays paused.
- Re-run on the 6-year lake: `R22_SOURCE=lake python3 research/hunt/r22/r22trades.py design` (and `r22study.py events`).

## Which morning range helps the ORB (R23, 13 Oct 2026) - none on the release; the verdict waits for the fresh lake
Full study: `HUNT_R23_ORB_RANGE.md` (pre-registered `hunt/r23/PREREG.md`; parts A / B / C in ddc9e8a / 5c342af / a9a21ed;
22 range configurations, BH family of 80 now, 89 at the lake read).
- The release (2 years) was already read by R16-R22, so R23 used it all as design data. The fresh test is the Dhan
  lake from 27 Apr 2026 (R18 amendment 1). The lake is NOT published (13 Oct), so there is no verdict yet. One command
  reads it: `R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake`.
- Anatomy (1,242 ORB-range breaks, BANKNIFTY / NIFTY / FINNIFTY): 23% ran >= 0.5 ATR14, 36% faked out, under 1% ran
  1.5 ATR. Good breaks:
  - are already further from the morning TWAP (AUC 0.65) and close clearly beyond the edge (0.63);
  - come early (after 11:05: 16% run / 44% fake) and after few edge tests (3+: 17% / 45%);
  - happen on wider ranges (0.58).
  No signal from option volume / OI, VIX level, gap, first-candle shape, inside day or expiry (AUC 0.48-0.53); VIX
  change leaned good (0.55) but failed BH (q 0.13).
- Release trading (515 days, BANKNIFTY + NIFTY, net of the app's fills and fees). All 18 plain ranges lose:
  - 15-75 minute windows, own-timeframe candles, volatility-sized, first-hour value area, plus yesterday's high / low,
    plus the gap. Rs -18k to -86k, against today's ORB -44,720 and R22's 15-min range -17,455;
  - a 15-minute close beats a 5-minute one on every window;
  - only R22's range plus the TWAP / break-strength filter was positive (+15,593 / +16,158). That was in-sample, in
    2 of 4 blocks, and mirrored ranges with the same filter made more (+20,408 / +26,318).
- The nested walk-forward of the selection lost Rs -17,791 out of sample, against R22's plain range at -8,535. BH:
  nothing (best q 0.32). App: no change; the ORB stays paused. Finalists for the lake: `r23-r22-f2`, `r23-r22-f1`,
  `r23-orb-f2`, all "carried, not promising".

## What every option buy would have done, and patterns before big winners / losers (R24, 13 Oct 2026) - no rule passes
Full study: `HUNT_R24_WHATIF_PATTERNS.md` (pre-registered `hunt/r24/PREREG.md`, commit 11cfa984; 8 rules, BH with R20-R22).
- What-if table: 11,662,056 option-minutes (NIFTY + BANKNIFTY, CE/PE ATM-3..+3, entries 09:20-14:45, 2 x 1,282 sessions
  Aug 2021 - 12 Oct 2026, Dhan lake), each bought at the ask, net of app charges and a spread by moneyness, with fixed
  exits, MFE/MAE and the ORB / Sweep / Liquidity exits. On Drive (`IraAlgo-data-lake/derived/r24_whatif`); in the brain
  as y_wi_* labels only.
- Blind buying loses at every exit: design mean per lot NIFTY Rs -97 (+15 min) to Rs -170 (15:05), BANKNIFTY Rs -131
  to Rs -231 (10.5 million option-minutes, 1,161 sessions), though the median option rises +17% to +26% after entry.
- Before big winners (net >= +50% at 15:05; 8.8% of 2.13 million grid rows): expiry day with the ATM straddle grown since
  09:20 and the option on the move's side, 32-34% big wins (lift 3.6-3.8x) - but 45-47% big losses (lift 2.7-2.8x)
  and about Rs 0 under ORB exits. The most common states before winners (levels near, NIFTY, high IV) are as common
  before losers.
- Before big losers: OTM2-3 bought on expiry day lose half or more 90-95% of the time (18,000-38,000 rows each); 7+ days
  to expiry almost never wins big (0.8%).
- 8 pattern rules (design only, Liquidity exits chosen): walk-forward Rs -332k over 3 test blocks; holdout (27 Apr - 12 Oct
  2026, 116 sessions per index, read once) 0 of 8 pass. Best p3 (ATM IV high, gap against the option) +Rs 14,066 on 47
  trades, but random entry times did as well (p 0.073, BH q 0.86). Patterns beat random patterns, not random timing.
- Re-run on the full lake (incl. monthly series): `bash research/hunt/r24/refresh_lake.sh` (Steps 1-2; Step 3 needs a new
  pre-registration).

## The ORB family on the full lake (R3, 14 Oct 2026) - every version loses; ORB, ORB Fresh, ORB Sweep and Range Fade stay paused
Full study: `HUNT_R3_ORB_FAMILY.md` (pre-registered `hunt/r3orb/PREREG.md`; Dhan lake Aug 2021 - 12 Oct 2026; design
BANKNIFTY + NIFTY, holdout out-of-instrument FINNIFTY + MIDCPNIFTY + SENSEX read once; app charges, liq fills, 1 lot).
- Rebuilt exactly as the app has them (matches R14 / R12 within a few percent), BANKNIFTY + NIFTY over 5 years: ORB
  -Rs 2,23,040 (2,489 trades), ORB Fresh -Rs 2,23,812, ORB Sweep -Rs 5,05,954 (3,156), Range Fade -Rs 5,10,978 (3,512);
  0 of 6 years in profit for every arm. Unseen indices: ORB -Rs 1,13,993, Fresh -Rs 1,49,434, Sweep -Rs 5,85,798, Fade
  -Rs 5,77,344.
- Anatomy: no point-in-time feature (VIX, VIX change, gap, range width, time, expiry, distance from the morning TWAP)
  has a third in which any arm makes money. Even on days that trended its way the ORB lost Rs 52 a trade (1,094 trades):
  it buys after the move. Sweep and Fade win only on trend days traded with the trend (known only after the close).
- 16 pre-registered versions (a day-type filter, Boss's R19c exits without a target, 1-ITM with 1 entry a side, all
  three together): all lose in every design year and on the unseen indices; 0 of 16 pass. The least bad, ORB with all
  three, lost Rs 1,69,567 on design and Rs 1,46,770 unseen. BH over 183 ORB-family tests.
- 13 Oct's wide range: on BANKNIFTY days with a range of at least 0.8 x ATR14, price stayed inside until 13:55 on 28%
  of days (33 of 119); not trading them saves money.
- App: nothing changes; the four arms stay paused (code and settings kept); nothing goes on paper.

## Every indicator at once, and why candles turn green or red (B2, 14 Oct 2026) - direction stays a coin flip; nothing trades
Full study: `HUNT_B2_ALL_AT_ONCE.md` (pre-registered `hunt/b2/PREREG.md`; NIFTY, BANKNIFTY, FINNIFTY, SENSEX; design
2021-08 .. 2026-04 walk-forward, holdout 27 Apr - 12 Oct 2026 read once).
- 469 inputs in one model (every brain feature + every indicator setting on 1/5/15-min charts, option volume profile,
  order-flow proxy, R28 gamma, other indices, Fibonacci / round levels), in a linear model, boosted trees and a neural net.
- Next 15 / 30 / 60-minute candle colour: holdout AUC 0.516 / 0.511 / 0.518 (0.5 = coin flip); no direction target passes;
  all 469 inputs add at most 0.005 over the 208 base ones; the neural net is worst.
- Trading the most confident 10%: all 4 rules lose (holdout 15-min Rs -21,397 on 527 trades); none beats random entries.
- Predictable: move size (holdout AUC 0.64), whether a bought option pays (0.59-0.63, calls and puts alike: timing, not
  direction), "green day" at 10:15 (0.73, but it is mostly the move since the open; the rest of the day is a coin flip).
- A green 15-minute candle forms with call buying / put writing / call short covering, breadth rising, gamma up and
  VIX / IV falling (concurrent, not known before). Before it: a small reversal (after red 15 minutes, green 52.6% vs 49.6%).
- App: nothing changes.

## Crypto: what is predictable? (C1, 13 Oct 2026) - only the size of the next hour / day, not its direction
Full study: `CRYPTO_C1_PREDICTABILITY.md` (pre-registered `brain/crypto/PREREG.md`, commit 5adf2a6; 80 tests in one BH
family). Built on the new crypto Data Brain (`research/brain/crypto`, 24x7 UTC cube of Binance spot, perp, funding, OI).
- Data: Binance archive BTCUSDT and ETHUSDT 1-minute spot and perp, funding and OI, 1 Oct 2023 - 31 Mar 2026 (913 days
  each, no gap minutes); Deribit DVOL for BTC. DESIGN data only: the locked crypto holdout (1 Apr 2026 on) was not read,
  so every pass below is a candidate for one holdout read, not a proven finding.
- PREDICTABLE: crypto range of the next hour (error 24.7% smaller than the hour-of-day average for BTC, 19.9% for ETH,
  16,823 hours) and of the next day (19.9% / 15.1% smaller than the 20-day average, 670 days). Recent realised ranges
  carry it; DVOL adds little. Median BTC next-hour range 0.546% (10-90%: 0.244-1.307%), next-day 3.34%.
- Crypto weekends are quieter: weekend hours move 0.595x (BTC) / 0.672x (ETH) of weekday hours (126 weeks). The busy
  hour is 14:00-15:00 UTC (the US open), 2.29x the quietest (05:00 UTC) for BTC.
- NOT predictable after costs: crypto direction. Next hour has a real short-term reversal (AUC 0.535 BTC / 0.543 ETH,
  4,340 / 5,168 trades) but it is worth about 0 bps gross against a 12 bps perp round trip (net -11.74 / -13.16 bps a
  trade). Next day AUC 0.495 / 0.512. Funding, basis and OI change do not predict the next day (best q 0.28); no hour or
  weekday return beats costs (best ETH 21:00 UTC +7.5 bps, q 0.02, below 12 bps).
- App and Jarvis: an expected-range nowcast for BTC/ETH and weekend/hour-of-day context; say plainly that crypto
  direction, funding and basis are not forecasts. Nothing trades on it.

## Crypto in Boss's window, 00:00-09:00 IST (C2, 13 Oct 2026) - size predictable; no direction edge
Full study: `CRYPTO_C2_WINDOW.md` (pre-registered `brain/crypto/PREREG_C2_WINDOW.md`, commit 9bdbc27; 26 tests, one BH
family). Boss: crypto only runs 00:00-09:00 IST (18:30-03:30 UTC), flat by 09:00. Design data only (BTC, ETH, Nov 2023 -
Mar 2026: 7,928 window hours and 880 windows per coin); the holdout from 1 Apr 2026 was not read.
- PREDICTABLE: the crypto range of the next window hour (error 27.8% BTC / 23.4% ETH below the window-hour average,
  6,308 hours) and of the whole window (17.2% / 15.2% below the last 20 windows, 670 windows). Median BTC window range
  1.83% (10-90%: 0.9-3.87%); a window hour is about as busy as the average crypto hour (the US open is outside it).
- Crypto direction in the window: window hours reverse slightly (AUC 0.547 / 0.550) but lose -10.45 / -10.73 bps a
  trade after costs. Whole-window direction: BTC -3.82 bps (fails); ETH passed its bar (AUC 0.558, +0.37 bps a trade,
  351 trades; a reversal of the last day's move) and is a CANDIDATE for one holdout read, not an edge: -1.63 bps at 2x
  slippage, and it lost in 2024 (-27.3 bps, 119 trades) while winning in 2025 (+10.56, 179). No IST hour's mean
  return beats the 12 bps round trip (best ETH 03:00 IST +7.88 bps).

## Crypto for a small Indian account: hold or DCA, trade rarely (RC1, 13 Oct 2026) - no active strategy passes
Full study: `HUNT_RC1_CRYPTO_SMALL_ACCOUNT.md` (pre-registered `hunt/rc1/PREREG.md`, commit 3ceda7b; 10 crypto ideas, BH
across 8 active tests; holdout 13 Oct 2025 - 12 Oct 2026 read once). Research only, not financial advice; the tax figures
are modelling assumptions (confirm with a CA).
- Best after tax: buy and hold or DCA into BTC + ETH. Walk-forward 13 Oct 2019 - 12 Oct 2025 (73 months, Rs 1L, 50/50):
  hold +58.8% a year after 31.2% tax and 1% TDS (max drawdown -76%, 632 days to recover), 12 monthly DCA buys +56.4%.
  Holdout year: hold -30.4%, DCA +4.7%.
- India's crypto tax (30% + cess on each profitable sale, no loss set-off) punishes frequent trading: spot trend made
  +24.4% a year after tax vs +43.8% before (77 entries); the 4h breakout made +48.0% gross and -12.2% after tax (397
  entries, 133 orders a year). 0 of 8 active ideas beat random-entry twins after BH (best q 0.356).
- Leverage hurts a small account: trend on INR perps at 3x had a -95% drawdown, ETH alone at 3x was liquidated in
  March 2020, and 3x lost -70.4% in the holdout year.
- Funding carry (spot long + perp short) lost -10.9% a year after tax with 6 short liquidations at 1x; it needs two
  exchanges and each leg is taxed separately. Not practical for a small Indian account.
- INR futures' tax treatment is disputed: on the 4h breakout, the VDA reading gives -3.0% a year and the business-income
  reading +21.3%. Ask a CA before trading crypto futures.
- Day-of-week effects in BTC / ETH are not real after BH (16 tests, lowest q 0.355).

## Crypto options on Delta Exchange India (RC2, 13 Oct 2026) [crypto] - nothing passes; no crypto arm
Tag: crypto. Full study: `HUNT_RC2_CRYPTO_OPTIONS.md` (pre-registered `hunt/rc2/PREREG.md`, commit f32ef32; 8 option-buying
rules x 4 stop/lock exits = 32 variants, one BH family). Every trade had a stop-loss and a profit lock (Boss's crypto
rule). Research, not financial advice.
- What exists: Delta India BTC/ETH calls and puts, a new daily expiry every day (weekends too) settling 17:30 IST,
  plus weeklies and monthlies; cash-settled; rupee wallet; 24x7.
  - Lots: 0.001 BTC (about Rs 8,050 notional) and 0.01 ETH (about Rs 2,430).
  - An at-the-money daily lot costs about Rs 69 (BTC) / Rs 31 (ETH) with 24 h left.
  - Fee: 0.01% of notional per side, capped at 3.5% of premium, + 18% GST.
- Costs decide it: a round trip in an at-the-money daily option costs 7-14% of the premium (spread 3-6.5 points, fees
  2.7-6.8, slippage about 1); a cheap far strike about 36%.
- Design (Mar 2024 - Apr 2026, 772 expiries per coin): no rule cleared all gates.
  - R4 (15-min move >= 2.5x implied: +0.9%/trade, 2,218 trades), R8 (previous-day high/low break: +0.4%, 1,184) and the
    expiry hero (cheap OTM, 14:30-16:30 IST: +7.9%, 211) were positive at base costs.
  - All three were negative under the cost stress and in the walk-forward.
- Holdout (Apr-Oct 2026, read once): 7 of 8 picks lost (-3.1% to -18.7% a trade).
  - R8 made +1.5% a trade on 290 trades (t 0.3): +Rs 8,910 on Rs 1 lakh at 2% a trade, -Rs 34,173 after the strict VDA
    tax reading.
  - The hero lost -18.7% a trade (56 trades).
- The rules beat random same-clock times (they buy after moves, when vol persists). Mostly they do NOT pick calls vs
  puts better than a coin flip.
- Options are dear in 08:30-17:30 IST (moves 0.67-0.90x implied) and cheap hour-for-hour at the US open, 18:30-21:30 IST
  (1.12-1.46x). A daily option prices the whole day, so the US hours cannot be bought alone.
- Zero to hero (hindsight, design): a cheap OTM daily option bought 3 h before settlement closed at >= 3x on 17% of tries
  (BTC, 3,554 options), but was worth 0.68x on average at 17:20 IST. On Deribit's traded prices, 3x happened 7.7% of
  the time (897 options).
- Data trap: Delta's first mark candle of a new strike is often stale (5.7% of 81,452 listings). One such trade (+4,889%)
  made a rule look like a winner until fixed. Never back-test an entry in a strike's first 15 minutes.

## Who placed the orders and who made money: NSE participant data (OF1, 14 Oct 2026) - nothing to follow
Full study: `HUNT_OF1_ORDERFLOW_INDIA.md` (pre-registered `hunt/of1/PREREG.md`, commit f969bc2; holdout read once).
NSE's data is anonymous: only types (Client, DII, FII, Pro), never a person, firm or reason.
- Data: participant OI 3,632 usable days (2 Jan 2012 - 13 Oct 2026), index F&O bhavcopy 3,644 days, NSDL FPI 3,560 days;
  on Drive `IraAlgo-data-lake/participants/`. DII cash flows not reachable (NSE API 403).
- Who profited, 2012-2026 (gross end-of-day MTM, Rs crore): Pro +8,921 on index options (+5,946 to +12,925 by strike
  mix), -2,797 on futures; profit in 13 of 15 years. FII +3,235 to +7,062 on futures, made on trend days (+7,591 on
  1,363 days vs -4,356 on 2,279 range days). DII +1,147 to +1,571 on futures. Client -2,009 to -4,973 futures and
  -7,617 to -11,647 options (worst at low VIX: -9,661 on 1,949 days). End-of-day data misses intraday losses and costs,
  so retail losses are far larger than this (SEBI's study counts both).
- Behaviour (3,385 days): FII trend-follow (same-day corr +0.44); Client takes the other side (-0.35); DII buys the
  day after falls (-0.22); Client and Pro are the net option writers (97% / 92% of days), FII holds long puts and calls.
- Follow-the-group signals (36 tests): 15 passed BH on 2012-2025 (FII adds longs -> next day up, Client adds longs ->
  down; 0.03-0.08% per sigma, n 3,322) but 0 of 15 held on the locked last 12 months (246 days; 13 flipped sign). FII
  signal hit rate 33-39% in 2024-2025. FPI cash flow predicts nothing (p 0.66 / 0.97).
- Rules (app option-buying arm, real charges): R0-R3 none pass the design gates (best twin p 0.076); holdout all lose
  (Rs -32,212 on 39 trades to Rs -1,04,848 on 57).
- Option chain: OI added on falling-premium days paid the writers ~23% of premium by expiry (NIFTY Rs 47,133 crore,
  BANKNIFTY Rs 34,907 crore); far-OTM options added that way won for the buyer 1-2% of the time. Max-OI strikes are
  neither walls nor magnets (0 of 12 tests; max-OI strike closer to settlement than a same-distance random strike
  42-43% of 873 expiries, which is the no-magnet level of ~44%).
- The app's own order-flow recordings: none uploaded yet (IraAlgo has 0 releases); `hunt/of1/footprints.py` is ready.

## Night (R3) smart exit: hold past 09:16 for +20/+30 points? (R27, 14 Oct 2026) - no; keep the 09:16 sale
Full study: `HUNT_R27_NIGHT_EXIT.md` (pre-registered `hunt/r27/PREREG.md`, commit b2f0b55; holdout read once).
- R3 rebuilt on the Dhan lake (Aug 2021 - 12 Oct 2026, 869 trades); its 15:19 OI build-up equals the app's seed exactly.
  The 09:16 sale (B0) made +Rs 168/day in the locked year (X2: +150).
- 8 pre-registered holds, each with a stop and a 10:30 cap: index +20/+30 (BANKNIFTY scaled and raw), option +Rs 20/+30,
  H2 "hold when the gap and the first minute agree", Boss's +Rs 1,000 / Rs 500 floor. None passed the walk-forward
  (2023-25, 472 trades; best +Rs 24,778, BH q 0.48, every drawdown worse); in the locked year (173 trades) all 8 lost
  to the 09:16 sale, Rs -4,576 to Rs -25,893.
- After 09:16 the morning is a coin flip with big swings both ways (helped about as many nights as it hurt). A first
  minute in the position's favour is given back (H2 lost to random holding, twin p 0.71 / 0.62). Boss's floor gives more
  green days but cuts the big ones (Rs -25,893 in the locked year).
- 2021-22 alone made the index-point holds look good (p 0.006-0.04): they faded and then reversed. The app's agents
  (order flow, consensus, trap guard) cannot be tested on minute data: a SHADOW log at 09:16 is proposed.

## Every indicator on the Indian indices: the F1 Strategy Factory screen (F1, 14 Oct 2026) - size yes, direction no; 0 of 20 rules pass
Full study: `HUNT_F1_FACTORY.md` (pre-registered `hunt/f1/PREREG.md`, commit 33fc686 + amendments 0169252, 2d93ccd;
design results committed 57b704c before the holdout; holdout 1 Jan 2023 - 30 Jun 2024 read once).

**Scope.** 201 brain features (47 new: EMA crosses/slopes, 5-min EMA/Supertrend/RSI/MACD, squeeze, CPR, NR7,
option-volume VWAP, OI build-up types per strike, PCR change, OI walls, GEX proxy, VIX percentile) plus 4 breadth
features, on NIFTY, BANKNIFTY, FINNIFTY and SENSEX (771 / 768 / 760 / 432 design sessions with a chain).

**Screen: 11,672 tests, 2,045 BH survivors.**
- Move size (825 survivors): ATR, recent range, realised vol, VIX, IV, straddle. IC 0.2-0.37.
- Direction: |IC| about 0.03-0.05. The 1-minute chart indicators (RSI, MACD, Supertrend distance, EMA distance, %B, the
  last 15-min return) lean the WRONG way over the next 15 minutes (a small reversal, about -0.05 on all four indices),
  and it is gone by 60 minutes.
- IV skew (puts dearer -> index up over 30-60 min): the same sign everywhere, but only +0.01 to +0.08 on the later
  split.
- Breadth, VWAP, CPR, pivots, opening range, NR7, OI walls, max pain, PCR and the GEX proxy carry no usable direction.

**Rules.** C1 (EMA 9/21 + Supertrend + VWAP), C2 (put writing + VWAP) and 18 screen survivors; 1-ITM with Boss's lock
(+Rs 1,000 then keep Rs 500, trail Rs 500), -15% stop, 30-min time stop or none.
- Design: 0 of 20 pass (15,508 walk-forward trades).
- Holdout: 0 of 20 pass (8,782 trades):
  - C1: -Rs 2.99 lakh on 2,256 trades; C2: -Rs 3.33 lakh on 2,224 trades. Both are worse than random entry minutes
    (p 1.000).
  - The best are S2 (BANKNIFTY call when realised vol is high), +Rs 15,354 on 228 trades, and S18 (BANKNIFTY call when
    IV percentile is high), +Rs 12,319 on 158 trades. Both have BH q 0.279 and both lost in design.
- Volatility features rank option-buying outcomes ("busy minutes lose less") but cannot make them positive: the average
  loss is about 2x the average win under these exits.

**Exits.** The lock closed 51% of 24,290 simulated trades and the stop 30%. Only 8 reached the 15:10 square-off.

**Brain fix.** FINNIFTY and SENSEX had no option chain in the brain (no expiry calendar). Their expiries are now
inferred from the lake (`brain/expiry_infer.py`). MIDCPNIFTY still has none (unreliable dates).

## Many EMAs at once: EMA ribbons on 1/3/5/15-minute bars (R29, 14 Oct 2026) - no pattern passes
Full study: `HUNT_R29_EMA_RIBBON.md` (pre-registered `hunt/r29/PREREG.md`, Part A commit 917651e, Part B c52eab0; 195
tests, one BH family with F1).
- Ribbon EMA 5/8/13/21/34/55/89/200 and EMA 9/21/50/200 on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX: 12 pattern
  types (stack onset, twist, pullback to 21/34, squeeze then fan-out, ribbon breakout, fan-out, 1/5/15-minute alignment
  and combinations), traded with and against the trend, ATM, Boss's +Rs 1,000-keep-Rs 500 lock, app charges and spread.
- Mining: 1 of 140 candidates made money in both 2021-22 (93,650 trades) and 2024 (134,455), on 2 and 9 trades; the index moves a median
  2 bps (either way; signed median -0.1) beyond random in 30 minutes after an event. Boss's three ideas (15-min stack + 5-min pullback, squeeze then
  fan-out, MTF alignment) lost in 2024 in every cell but one (15-min squeeze down +Rs 123 on 146 trades, Rs -297 in
  2021-22).
- The 10 best twin-beating candidates (all fades, all losing on their own) on unseen years: 1 of 10 positive in 2025,
  1 of 10 in 2026, none in both; holdout 2023 (read once) 1 of 10 positive pooled (R6 +Rs 21 a trade on 264, sign
  flipping by period on every index). 0 of 55 rule x index tests passed. EMA 9 x 21 cross: Rs -110 a trade in the
  holdout (1,785 trades), negative on all five indices except NIFTY 2026.
- Kept: 77 point-in-time ribbon features in the Data Brain (`rib*`, `cls*`, `rib_mtf_align`) as context. Nothing to
  paper or live.

## Every parameter at once for CE / PE buying (R30, 14 Oct 2026) - 0 of 30 pass; one near miss
Full study: `HUNT_R30_ALL_PARAMETERS.md` (pre-registered `hunt/r30/PREREG.md`, commit a570202; walk-forward Jul 2024 -
Oct 2026, F1's holdout untouched).
- 164 Data Brain indicators (incl. the EMA ribbon) + option chain (IV, skew, PCR, OI build-up, straddle, volume, max-OI)
  + VIX + calendar + other indices, at 249,414 decision moments on 5 indices; 4 models and 2 combination miners, trained
  on earlier years, tested on later ones, real charges and Boss's lock.
- 0 of 30 strategy x index tests passed. All trades together lost (Rs -35 to -196 a trade by strategy). EMA x everything
  combinations (C2) were +Rs 300-520 a trade on training years and Rs -111 out of sample: over-fitting.
- Near miss: M4 (win-chance model) on NIFTY, 77 trades, +Rs 277 a trade, positive in all three test periods
  (+48 / +536 / +162), p 0.025 vs random entries, still positive at 2x spread; fails only BH q (0.109). About one trade
  a week. Proposed: a pre-registered 40-session forward paper test, logged only.
- What the models lean on (varies by period): IV skew and the ATM straddle price most often, then PCR; but also the
  weekday (2026 M1), VIX (M3) and EMA-ribbon slopes (3rd-4th in two models). A lead, not a finding.

## Option-market signals alone (IV1, 14 Oct 2026) - none pass; not forward-tested
Full study: `HUNT_IV1_OPTION_MARKET.md` (pre-registered `hunt/iv1/PREREG.md`, commit ff5f951; descriptive walk-forward,
inputs chosen after R30).
- IV skew, straddle, PCR, OI build-up on their own, 5 indices, Jul 2024 - Oct 2026 out of sample, Boss's lock: an
  option-only model lost on all 5 indices (Rs -16 to -256 a trade); the best option-only pairs made +Rs 35 a trade on
  SENSEX (915 trades) but lost in 2025; a fixed put-writing + PCR + straddle-expansion rule lost on all 5 (Rs -24 to
  -245, 7,339 trades). The option chain helps only as part of R30's full model (M4-NIFTY, now in its forward test).

## Neural networks on every running study (R41, 14 Oct 2026) - they forecast move SIZE better than trees; no trade passes
Renumbered 14 Oct from R30 (the number R30 was already taken by R30 all parameters).
Full study: `HUNT_R41_NEURAL_NETS.md` (pre-registered `hunt/r41/PREREG.md`, commit 36af9e9; India holdout 1 Jan 2023 -
30 Jun 2024 and crypto holdout 1 Apr - 30 Sep 2025, each read once). Models: MLP, causal TCN and GRU (3 seeds each,
signal on the underlying, last 60 one-minute bars + the brain's features) against base rate, logistic and trees.
- Index direction over 15/30/60 minutes (5 indices) is a coin for the networks too: holdout AUC 0.510-0.517, log-loss no
  better than the base rate (36,462-41,967 forecasts, 368 sessions). Every ATM option rule built on it loses after
  costs (R1-R3: -Rs 106 to -424 a day) or trades too rarely to judge (R4-R6: 21-172 holdout trades).
- Move size is where networks help: holdout R2 of the next 60-minute range 0.432 vs trees 0.382 and B1's formula 0.353
  (36,320 forecasts); 30 minutes 0.465 vs 0.440; better on every index; q 0.006. GRU / TCN (raw minutes) edge the MLP.
- Filters on the arms' trades (ORB, Range Fade, Liquidity, Night, lottery L3; 195-1,083 design trades each): no net
  beats the base rate. Night's filter lifted the holdout from Rs 174 to Rs 295 a day but fails BH (q 0.14) and the
  100-skip gate, and the trees' filter did as well (Rs 314). The Liquidity filter made the arm worse (Rs 234 -> -70).
- Expiry blast from 13:45 (R26's 4x) is not predictable (holdout log-loss 0.439 vs base rate 0.381, 685 option-days).
- Next-morning gap from 15:20: the net only learned the gap-up base rate. Its overnight call rule made +Rs 24 a day
  (208 trades, q 0.11) and plain "always the call" on the same nights +Rs 22.
- Crypto: a tiny real 60-minute direction signal (holdout AUC 0.533, log-loss 0.6915 vs 0.6928, q 0.006, 35,112
  forecasts), far below Delta India costs: every stop-and-lock perp rule loses (-Rs 4 to -301 a day before tax).
- If ever used, the models are tiny: under 0.4 MB for all three, about 6 ms per forecast on a CPU.

## SILVERM morning call (M4) vs bad option prices (R42, 14 Oct 2026) - not bad prices, but no edge: switch the paper arm off
Full study: `HUNT_R42_SILVERM_SPIKES.md` (pre-registered `hunt/r42/PREREG.md`, commit b91dc30f). Signal source: the clock
(15-minute morning grid), strike from the SILVERM future; option premium only for P&L.
- The ">50% one-minute jumps" in the lake's metal options are 98-99% strike rolls of the rolling ATM+k series (the +4,000x
  ones are expiry-day handovers). Real one-off bad prints (F1: >1.5x both neighbour medians, per contract) are 0.02-0.08%
  of traded minutes in GOLD/GOLDM/SILVER/SILVERM, mostly in the evening; SILVERM 09:00-10:44 has 5 of 120,739.
- 0 of M4's 51 holdout trades touched a flagged price, and cleaning changed no trade (95 design, 53 holdout).
- Rebuilt from the lake, M4 reproduces (to 7 Oct: 51 trades, +Rs 466/trade, p 0.050). On the pre-registered holdout
  (8 Jul - 8 Oct): 53 trades, +Rs 336/trade, +Rs 17.8k, but random-twin p 0.079 (same day, random morning minute);
  2x spread +Rs 62/trade. Design on fuller data: 95 trades +Rs 871/trade (M4: 78, +1,439), twin p 0.33. Walk-forward
  -Rs 44.6k (129 trades). Extra 9-12 Oct: 4 trades, -Rs 3.8k.
- Pre-registered verdict: switch the M4 SILVERM paper arm off (risk-lowering paper change; Boss decides; R42 does not
  change the app).

## Boss's 7 sweep signals as zero-to-hero expiry trades (R43, 14 Oct 2026) - no better than a random minute; 0 of 88 pass
Full study: `HUNT_R43_SWEEP_SIGNALS_HERO.md` (pre-registered `hunt/r43/PREREG.md`, commit f4fc6f9; design = expiry days
5 Aug 2021 - 31 Dec 2024, 670 index-days; locked holdout 1 Jan 2025 - 12 Oct 2026, 245 index-days, read once). Signal
source: the 5-minute INDEX spot (EMA8/9 x 21/26 with ADX, RSI fade, RSI momentum, Donchian 20), buying a Rs 5-30 OTM or
the OTM2 option on the index's own expiry day, Rs 5,000 a trade, -50% stop, no target, real charges and slippage.
- 0 of 88 tests pass (7 families x strike x window x exit + 12 no-signal baselines); smallest holdout BH q 0.132 (a
  baseline), 0.201 among signal rules.
- Against 500 random-entry twins each, 39 of 76 signal variants beat the twin mean in design and 38 in the holdout: a coin.
- Every profitable case (22 in design, 18 in the holdout, only 3 in both) turns into a loss without its 5 best trades.
  Best both-period rule: EMA8x21 ADX>15, cheap, 12:30-14:45, hold: design +Rs 1,23,022 (324 trades, p 0.17), holdout
  +Rs 87,534 (119 trades, p 0.34, q 0.78), -Rs 1,94,680 without its top 5 (one 29x SENSEX call +Rs 1,40,013).
- Donchian 20 (OTM2, all day) was the design's best (+Rs 5.83 lakh, 662 trades) and lost in the holdout (-Rs 67,696, 245).
- A stepped profit lock (+25/+50/+75/+100% ratchet) wins more often (31% vs 10%) but sells the heroes early: its 4 variants
  banked one 10x in 667 holdout trades and all 4 lost (-Rs 19,993 to -Rs 47,088; 119-214 trades each).
- No app change, nothing on paper or live. Across R21-R43, 0 of 170 hero rules pass.

## Boss's 5-year sweep re-tested independently (R44, 14 Oct 2026) - we agree: none of the 7 strategies pays
Full study: `HUNT_R44_SWEEP_RETEST.md` (pre-registered `hunt/r44/PREREG.md`, commit 65ce8f46; holdout 27 Apr - 12 Oct
2026 read once). Signal on the 5-minute index (or CRUDEOIL future) candle; ATM option bought; Boss's exits (stop -25%,
target +50%, 60-minute exit, 15:15 square-off, max 2 a day a side); expiry days skipped; Boss's Rs 95 and our real costs.
- EMA8/21 + ADX15 (with and without a stepped lock), EMA9/21 + ADX20, EMA9/26 + ADX20, RSI fade 30/70, RSI momentum
  55/45 and Donchian 20 lose on the 5 indices: 22,443 trades -Rs 26.6 lakh in 2021-23 and 41,766 trades -Rs 36.4 lakh
  in 2024-26 (real costs). Out-of-sample 0 of 14 strategy-side combinations are positive, as Boss found. In-sample 2
  were slightly positive (EMA9/21 and EMA9/26 calls, +Rs 24,878 on 916 and +Rs 17,134 on 751 trades) and both lost after.
- 0 of 112 cells (strategy x index x side x session) pass the gates; CRUDEOIL lost Rs 16.9 lakh on 10,203 trades
  (Aug 2025 - Oct 2026). The best BH q, 0.019, is RSI fade timing beating random bars while still losing money.
- Boss's trade counts are about 2x ours per cell (4x in total): their per-session rates match ours only when the WEEK
  and MONTH option series are both traded (byte-identical files after Nov 2024), so their sweep most likely counted
  trades twice. Their loss per trade is also bigger (Rs 203 vs Rs 102 for the EMA8/21 family). The verdict is the same.
- The "October champion" EMA8/21 + ADX15 (most likely BANKNIFTY both sides: our 46 and 306 trades match their 46 and 288)
  made +Rs 6,410 in the last 25 sessions (Boss: +12,192) after -Rs 1,612 in Jan-Aug 2026 (Boss: -40,109): a short
  lucky patch. The plain rule lost Rs 8.3 lakh on 9,455 NSE trades over 2021 - Oct 2026. Nothing to paper.

## The other AI's "NIFTY premium mean-reversion scalp" (R45, 14 Oct 2026) - an artifact of the rolling ATM series; loses on one contract
Full study: `HUNT_R45_PREMIUM_SCALP_AUDIT.md` (pre-registered `hunt/r45/PREREG.md`, commit b1b24f3; holdout 27 Apr -
13 Oct 2026 read once). Signal source: the OPTION'S OWN PRICE CHART (5-minute ATM call premium crossing below its
Bollinger(20, 2) lower band). That is not the default; it is reported separately.
- The claim (+Rs 174 a trade, 3,726 trades, 53.4% win, PF 1.45) reproduces in kind on the lake: +Rs 264 a trade on 2,843
  design trades (WEEK + MONTH pooled, which also breaks its own max-2-a-day rule: 404 of 983 days had 4 trades).
- The lake's `offset == 0` rows switch contract as spot moves. The ATM strike changed during 68.6% of the trades, and
  those trades made +Rs 341 against +Rs 98 for the rest. The "bounce" is the series jumping to the lower strike's
  dearer call, which no trader holds.
- Priced on the strike bought at entry, the same signals lose Rs 65 a trade (2,826 trades, 17/57 green months). With
  every fix (gap fills, 15-minute hold, real expiry days, real charges + 0.5% slippage, WEEK only) they lose Rs 170 (fixed
  contract, 1,250 trades) or Rs 149 (09:15 contract, 1,125 trades). Every year 2021-2026 is negative, and the holdout
  shows -106 and -127 (122 and 130 trades). On the fixed contract the signal is no better than random entries (q 0.62).
- BANKNIFTY: +Rs 237 on the rolling series, -Rs 124 a trade on one contract at real lots (1,405 trades).
- Rule for every lake study: never trade the rolling ATM series as one instrument; hold the strike that was bought.

## Rejected MCX, gold and crypto rules on the recent market only (R53, 15 Oct 2026) - 0 of 44 pass; nothing comes back on paper
Full study: `HUNT_R53_MCX_CRYPTO_RECENT.md` (pre-registered `hunt/r53/PREREG.md`, commit 3d233ea4; list of every rejected
rule, tested and left: `hunt/r53/CATALOGUE.md`). Data from 20 Nov 2024 only. Design 20 Nov 2024 - 31 Mar 2026 (MCX
minutes from 5 Aug 2025). Final test 1 Apr - 12 Oct 2026, read once; earlier studies had seen parts of it. Signal source
of every rule: the underlying (MCX near future, US future + USD/INR, XAUUSD, BTC/ETH perp; DVOL/OI as context).
- 31 rules (10 MCX families incl. the app's NG evening, US-night silver and 12-month trend arms; 13 XAUUSD; 8 crypto),
  44 final-test versions, one BH step: 0 pass, 10 too few trades to judge.
- Closest: the app's US-night silver arm (SILVERMIC, 09:05 sign of SI=F + INR=X): final 109 trades, +Rs 62,850 (57% won,
  max drawdown Rs 18.5k), design walk-forward +Rs 1.42 lakh, twin p 0.036, but q 0.198. Shorts made +Rs 56.5k of it.
  It stays the "not proven" paper arm it already is.
- The app's NG evening breakout lost on the final test (115 trades, -Rs 3.5k; design +Rs 19k) and the 12-month trend lost
  -Rs 66.1k on 28 leg-months. Turning them off would be a risk-lowering paper change; Boss decides.
- R2's gap-residual fade was positive in all three final books (GOLDTEN +Rs 17.0k p 0.004; NG option +Rs 20.2k p 0.008;
  CRUDEOILM +Rs 22.6k p 0.032; 59-77 trades) but lost in design: watch-list, not paper.
- M3's "close" GOLDM rules were data holes: on the fuller lake the GOLDM evening break goes from +Rs 313 to -Rs 386 a
  trade, the GOLDM morning break from +Rs 110 to -Rs 315.
- Gold (XAUUSD, per standard lot): every study re-run trade for trade with its own code. Most made +$80k to +$245k in
  design, then lost or shrank in 2026's final test. Best: Liquidity 1h confirmation 20, +$25.5k but only 23 trades.
  Boss's two session strategies, TAS 15m and AI's Opinion all fail.
- Crypto (stop + profit lock on every trade; Delta and CoinSwitch costs; 31.2% tax per winner): all 8 rules lose after
  tax in design and final. Nearest: RC2 R8 day-high/low break on Delta options, +Rs 16.9k before tax, -Rs 28.9k after
  (313 trades). Buy-and-hold made +Rs 13-15k per Rs 1 lakh after tax over the same final window.
