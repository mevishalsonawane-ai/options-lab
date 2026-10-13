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
