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

## Entry filters for Liquidity 15+5 (R80, 18 Oct 2026) - 0 of 10 pass; keep the plain entries
Full study: `HUNT_R80_LIQUIDITY_ENTRIES.md` (pre-registered `hunt/r80/PREREG.md`; R51 clean stores; design 20 Nov 2024 -
31 Mar 2026, final 1 Apr - 13 Oct 2026 read once (weak: already read by R51/R63/R70/R76); BH over 10; random-skip twins).
- Signal: the index (Liquidity level breaks); every filter reads index bars only. BANKNIFTY B0: design +Rs 1,10,094 (336
  trades), final +Rs 10,978 (131).
- Older pools (V1), a 2-a-day cap (V9) and stop-after-first-loss (V10) led in design (+Rs 4.8k to +6.9k) and lost in the
  final (-Rs 1.3k to -24.0k). Clean breaks (V4) won the final (+Rs 16.7k, twin p 0.03) after losing Rs 75k in design.
  Displacement breaks (V8) were ahead in walk-forward and final but no better than random skipping (twin p 0.30). All q >= 0.31.
- No entries before 10:15 (V6, V7) cost Rs 90-93k in design: the morning breaks carry the arm. Round numbers (V3), stacked
  zones (V2) and beyond-yesterday's-range (V5) also fail. Volume surge: not tested (no futures minutes before 29 Jul 2026).

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

## The other AI's BTC "Option 1" (EMA 8/21 + ADX > 15 + neural net, Delta India ATM calls) (R46, 15 Oct 2026) - the 85.7% claim does not hold; 0 of 14 pass
Full study: `HUNT_R46_BTC_EMA_ADX_NN.md` (pre-registered `hunt/r46/PREREG.md`, commit 3388242; design results d934a9e
before the holdout; holdout 1 Apr - 12 Oct 2026 read once). Signal source: BTC spot (the underlying), 5-minute bars;
the trade buys a Delta Exchange India BTC daily ATM call (puts and the perp as extras). Every trade had a 50% (or 30%)
premium stop AND a profit lock (the other AI's no-lock exit was not run), 60-minute exit, max 2 a day.
- Their claim: 21 trades in 44 days, 85.7% win, +$122 a trade. Same entry, our data, realistic fill (the option one
  minute later + half spread), 1,000 contracts = 1 BTC: design 2024 - Mar 2026 1,486 trades, 25.8% win, -$71 a trade,
  -$127,828 after tax; holdout 391 trades, 41.4% win, -$15 a trade, -$11,003 after tax; their own window (1 Sep - 12 Oct
  2026) 83 trades, 37.4% win, -$2,457 before tax. Best lock variant (L2 trail) shown; L1/L3 are a little worse.
- No edge before costs in design (gross -$5,616 even at the printed-trade fill). Fees + GST were $45 a round trip per
  BTC in 2024 and ~$16 now on a $280-410 premium. The printed-trade fill flatters results by ~$33k (design) and turns
  the holdout calls to +$288 before tax, -$5,919 after the per-winning-trade tax.
- Our walk-forward MLP in their 12-64-32-1 shape sits at coin-flip validation loss (0.692) and cleared 0.65 on 88 of
  7,536 design signals: 24 trades, -$1,200; 2 holdout trades; 0 in their window. The 60-minute BTC direction is not
  predictable from price features here.
- The calls beat random entry times in the holdout only (p 0.001; design p 0.58), while BTC rose ~20%: not a stable
  edge, and still a loss after costs. The BTC perp version loses $249k (design, 1,639 trades) and its 1% TDS would lock
  $1.36M. Nothing goes to paper; nothing in the app trades crypto.
- Their "1,000 contracts ~ $300" is right in kind (0.001 BTC a contract; median ATM premium $278 holdout, $410 design),
  but 1,000 contracts is far beyond what Delta's daily-option book fills at the modelled spread.
- **Amendments (15 Oct, labelled; primary view 20 Nov 2024 - 31 Mar 2026 design, 1 Apr - 12 Oct 2026 final = R46's
  already-read holdout span): still 0 of 22 pass.**
  - CoinSwitch PRO costs (0.015% of notional, unverified; GST with and without): A call L2 Nov24+ design 991 trades,
    -$72,640 to -$89,295 after tax; final 391 trades, -$11,003 to -$14,895. No sign changes.
  - The other AI's v2 "Ensemble" (MLP+GB+RF > 0.65 on the call's chart): its AUC 0.78 is real but comes from the
    ROLLING ATM series. The strike or expiry changes inside 98% of the 60-minute label windows. We get 0.786 on a random
    split and still 0.782 walk-forward, but the same model scores 0.50-0.51 against the held contract's own move. On
    held contracts the AUC is 0.54 and the filter almost never fires (1 design trade, 0 final). The rolling-filter
    version lost: 281 design trades, -$16,561 after tax; 140 final trades, -$4,027. In their Sep-Oct window: 23 trades,
    43.5% win.
  - The "buy after a 25% drop" claim (+$2.3 M per side) is a data artefact:
    - their code's `pct_change` compares a call row with the put row before it, and `idx + 4` is 2 minutes later;
    - on 15-minute rolling bars, 99.5% of the profit is strike switches and the daily expiry roll (the roll alone made
      $1.03 M).
    - On held contracts with real fills, a stop and a lock, the calls lost $181k before tax on 3,548 Nov24+ design
      trades ($300 each; p 0.97) and $44k on 1,380 final trades. The puts lost the same way.
    - Option-chart signal: non-default.
  - **Lesson for every study:** never label or build features on a rolling ATM series. Price and label on the held
    contract.

## Index-option buying with relaxed design rules (R47, 15 Oct 2026) - 387,392 variants, 10 to the holdout, none passes
Full study: `HUNT_R47_RELAXED_OPTION_BUYING.md` (pre-registered `hunt/r47/PREREG.md`, commit 2f27c32d; candidates
frozen in 4f012404; holdout 10 Nov 2025 - 13 Oct 2026 read once). Boss (15 Oct) allowed relaxing the DESIGN rules for
option buying. Relaxed: the signal source (index, option chain, the option's own chart - labelled non-default),
overnight and 1-5 session holds, any strike, month expiry, debit spreads, straddles, no trade cap, budget sizing, a wide
search with no BH during exploration, and VIX / weekday / expiry / month-end / event filters. Measurement was NOT
relaxed: one fixed contract per leg, R17 fills, dated charges, gap fills, no look-ahead.
- Design (Aug 2021 - Oct 2025, NIFTY, BANKNIFTY, FINNIFTY, SENSEX): 16 of the 17 signal families with enough trades lose
  in the median variant (Rs 34-2,446 a trade on a Rs 20,000 budget). Only multi-day breakouts (U4) are slightly
  positive. 18 of 132 families pass the walk-forward. The strongest were overnight late-day momentum (BANKNIFTY,
  FINNIFTY) and NIFTY 10-day breakout spreads.
- Holdout, 10 candidates, 500 random twins each: 6 make money, 4 lose, and none beats random entries after BH (best q
  0.50). Closest: BANKNIFTY "PCR extreme at 10:15 -> buy ITM3 in its direction" (+Rs 865 a trade, 91 trades, p 0.0499,
  q 0.50; its design profit had faded to +Rs 9 a trade in 2025). The best design idea, overnight late-day momentum,
  lost Rs 1,031 a trade on the BANKNIFTY holdout (90 trades). Nothing goes to paper.
- **Lake data defect (affects other studies):** some rolling option files contain rows of ANOTHER underlying, i.e.
  rows whose `spot` and `strike` sit at another index's level. Affected: FINNIFTY WEEK/MONTH (118k rows in 2025 at
  MIDCPNIFTY's level), BANKNIFTY MONTH 2022-24 (77k rows at NIFTY's level), SENSEX MONTH/WEEK (73k rows at BANKEX's
  level), MIDCPNIFTY (93k rows), and a few NIFTY MONTH / BANKNIFTY WEEK rows. NIFTY WEEK is clean. Drop any option row
  whose `spot` is more than 3% off the index at that minute (`hunt/r47/r47data.foreign_rows`). Thin contracts also
  carry stray prints (a 09:15 open of 500 on a Rs 60 option): never let a fill profit from a price more than 20% off
  the contract's surrounding closes (`hunt/r47/r47price.open_for_sell`). R47's selection was unchanged by both fixes.
- **Nov 2024+ view (amendment 4, Boss's primary view, 15 Oct): still nothing passes.**
  - Spans: design 20 Nov 2024 - 31 Mar 2026; final test 1 Apr - 13 Oct 2026, which overlaps the already-read holdout.
  - C9, C10 and C1 look good in design (p 0.012-0.018, q 0.098-0.10), and all three lose in the final test.
  - C4 (BANKNIFTY PCR follow) is the only candidate positive in both: 146 trades at +Rs 322, then 55 at +Rs 1,125
    (p 0.07, q 0.76).
  - Re-selecting inside Nov 2024+ only (101,136 variants in 27 groups) gives no pick with 40+ final-test trades and
    p < 0.05.

## High-win-rate option BUYING (R48, 15 Oct 2026) - 0 of 10 pass; a high win rate on design became losses on unseen data
Full study: `HUNT_R48_HIGH_WINRATE.md`. Pre-registered in `hunt/r48/PREREG.md` (amendments 1-4; the last, commit
50a189ed, came before the holdout read). Boss: "backtest only from Nov 2024", "only buying options".
- **Periods:** design 1 Nov 2024 - 17 Oct 2025; holdout 3 Nov 2025 - 13 Oct 2026, read once.
- **Signal source:** the index (gap, EMA20, TWAP, opening range). The option is bought; target and stop are on the
  premium. Every leg is one held contract, filled next minute, with dated charges and volume-based slippage.
- **Search:** 6,651,600 buying configs (ATM to 6 ITM, all 5 indices). 32,879 passed every design gate (43+ trades,
  65%+ winners, net > 0 in both halves, worst day, drawdown, top-5).
- **Holdout:** 0 of the 10 candidates pass.
  - The top three won 100% on design (49, 49 and 43 trades): +3-5% target, no stop.
  - On the holdout they won 81-88% but lost Rs 6-16k: average loss 7-12x the average win.
  - FINNIFTY ATM +3% / -50%: 70.6% winners over 218 trades, net -Rs 1,05,369.
  - The profitable three were no better than random entry times: NIFTY 6-ITM on expiry day +Rs 20,915 (49 trades,
    p_twin 0.10); BANKNIFTY 2-ITM +Rs 10,679 (70, p 0.07); BANKNIFTY ATM +Rs 3,771 (70, p 0.10).
- **Walk-forward of the picking rule:** picked on Nov 2024 - Apr 2025, the top 10 lost -Rs 30,179 on May - Oct 2025
  (344 trades, 70.1% winners).
- **Earlier full-period scan** (with hedged selling, Aug 2021 - Oct 2025, never read on the holdout): its top picks
  lost in each following year (2023 -Rs 60k, 2024 -Rs 50k, 2025 -Rs 47k) at 66-79% winners.
- **Lesson:** a 65%+ win rate is easy to fit (small target, no or wide stop) and says nothing about profit. Judge a
  rule by net, average win vs average loss, worst day and drawdown together. No paper change.

## The ORB family on the Nov-2024+ market only (R49, 15 Oct 2026) - still loses on every index; nothing comes back
Full study: `HUNT_R49_ORB_RECENT.md` (pre-registered `hunt/r49/PREREG.md`, commit 5448420; Dhan lake 21 Nov 2024 - 13 Oct
2026 only; design to 31 Mar 2026, final test 1 Apr - 13 Oct 2026 read once; one held contract, lots read from the data,
charges in force on each date incl. STT 0.15% from 1 Apr 2026, liq fills). Signal source: the index (default).
- The old rules exactly as paused, all five indices pooled: ORB design -Rs 88,644 (1,595 trades), final -Rs 9,813 (636);
  ORB Fresh the same; ORB Sweep -Rs 4,12,532 (2,093) and -Rs 92,211 (870); Range Fade -Rs 4,06,570 (2,363) and -Rs
  1,14,187 (1,020). Every arm lost on every index in design. ORB's small final loss is one month: April 2026 +Rs 23,944.
- R3's 16 versions (unchanged) all lose pooled in design; R23's two R22-range finalists, the only rules positive in
  design (+Rs 28,121 / +Rs 14,517), lost -Rs 57,452 / -Rs 61,789 in the final test. A 32-rule re-tune (range 15 / 30 / 50
  minutes x stop / target 0.5 / 1 / 1.5) is negative pooled in design and final for every point.
- 0 of 336 tests pass (56 rules x pooled + 5 indices; BH over 336; lowest q 0.061). Every rule that clears net, p and q
  in the final test lost on the same index in design: closest, ORB Sweep with a 30-minute range and half stops on
  FINNIFTY (final +Rs 20,997, 193 trades, p 0.002, q 0.061; design -Rs 39,857); several Sweep / Fade versions on
  MIDCPNIFTY likewise. FINNIFTY ORB 1-ITM was positive in both periods (+Rs 24,846 / 310 trades, +Rs 26,002 / 116) but
  fails p 0.12, q 0.55 and the top-5 rule (126%); R3 saw the same and called it noise.
- App: nothing changes; ORB, ORB Fresh, ORB Sweep and Range Fade stay paused on every index; nothing goes on paper.

## Every cancelled hero rule on the Nov 2024+ market (R50, 15 Oct 2026) - 0 of 28 pass; the final-test profits are one SENSEX call
Full study: `HUNT_R50_HERO_RECENT.md` (pre-registered `hunt/r50/PREREG.md`, commit 97d5c922). Window: expiry days 20 Nov 2024
- 13 Oct 2026 only (NIFTY / SENSEX weekly, BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly; 266 index-days). Design 20 Nov 2024 -
31 Mar 2026 (193, walk-forward by quarter); final test 1 Apr - 13 Oct 2026 (73), read once. The final test is not pristine
(R43-R45 saw 2025-26). Every trade is on one held contract, with dated charges, next-minute fills, a volume-based spread
(2x stress) and dated lots.
- Tested 20 originals in 8 families, plus one small re-tune per family (34 cells):
  - the app's Hero arm and V4;
  - Rs 1-5 lottery baskets and the 12:30 Rs 5-30 basket;
  - R26 OI-wall short covering and Boss's 13:45-14:30 window;
  - R36 GEX;
  - R43's 6 sweep signals;
  - R35 Supertrend;
  - R34 K1 and R25 T1.
- Design: 17 of 20 lost; together they lost Rs 8.85 lakh on 2,209 trades. The 3 positives are the OI-wall rules, with 4
  trades each.
- Final test: the EMA8/9 x 21/26 + ADX and RSI-momentum rules made +Rs 95k to +Rs 1.39 lakh, and the no-signal 12:30
  basket +Rs 1.27 lakh (146 trades, q 0.056). All of them ride one trade: the SENSEX 72,500 CE on 2 Apr 2026 (Rs 22-25 ->
  26-37x, +Rs 1.2-1.75 lakh, the same trade R43 found). Each is negative without its top 5 (-Rs 46k to -Rs 1.59 lakh), and
  each lost in design. The EMA rules also have too few trades (23-36).
- The Rs 1-5 baskets still lose about half of what they stake (13:30: -Rs 525 per Rs 1,000 in the final test, 121 tickets).
  Boss's 25% trail cuts most hero losses by half or more, but nothing turns positive beyond its top trades.
- The app's Hero arm: 12 design trades -Rs 26,417, 2 final -Rs 3,776; no trade on 13 Oct 2026. The OI-wall rules fire
  about once a quarter now (too few to judge).
- Nothing comes back on paper; every hero rule stays off. Across R21-R50, 0 of 198 hero rules pass.

## The Rs 1,000 hero ticket at 14:30 vs 12:30, exits 15:05 / 15:10 / 15:15 (R54, 15 Oct 2026) - 14:30 is not better; later exits add nothing reliable; 0 of 24 pass
Full study: `HUNT_R54_HERO_1430.md` (pre-registered `hunt/r54/PREREG.md`, commit 04437bbf). Boss: "What if we enter at 2:30
pm and exit at 3:10/3:15?" The ticket: on NIFTY / SENSEX own expiry days, 1 lot of a CE and 1 lot of a PE, nearest eligible
OTM priced Rs 5 - min(30, 1000 / lot), exited by Boss's 25% trail or held to the end time. Signal source: none (time only).
Window: 20 Nov 2024 - 13 Oct 2026 only. Design to 31 Mar 2026 (142 expiry days); final test 1 Apr - 13 Oct 2026 (55 days),
read once and not pristine (R43-R50 saw it). One held contract per ticket, next-minute fills, dated charges, R17 spread
widened x2.25 from 15:00 and x2.75 from 15:10 (measured on design days), R47 filters.
- With the trail, 14:30 is worse than 12:30. Design: -Rs 37,353 on 208 tickets (5% profitable) vs -Rs 14,507 on 248. Final:
  -Rs 6,029 (98) vs +Rs 1,559 (107). Paired: -Rs 161 a day in design (CI -307 to -56), -Rs 138 in final. After 14:00 a
  cheap expiry option swings about as much in a minute as the 25% trail allows: half the 14:30 tickets were out within
  4 minutes.
- Held without a trail, 14:30 and 12:30 lose about the same (design -Rs 45k vs -Rs 49k; final -Rs 12.3k each), about
  Rs 190-410 of every Rs 1,000 staked.
- 15:10 / 15:15 instead of 15:05:
  - With the trail it makes no difference: 0 of 940 design trail tickets were still held at 15:05 (5 of 417 in final).
  - Held, the later exits change the result by -Rs 31 to +Rs 76 a day, and every CI includes 0.
  - Changing the 15:05 rule is Boss's decision; the data gives no reason to.
- The best final cell (12:30 + trail, p 0.024, q 0.21) is 582% its top ticket and -Rs 11,411 without its top 5, and it
  lost in design.
- 2 Apr 2026 SENSEX: the 12:30 CE went 29x if held (+Rs 13,958 at 15:05), but the trail sold it at 2.7x (+Rs 280). By
  14:30 the move was over (-Rs 584 held). 13 Oct 2026 NIFTY: 14:30 + trail caught the CE (+Rs 2,633); 12:30 + trail was
  stopped out before it (-Rs 140).

## When the hero ticket's 25% trail should start, and holding to expiry settlement (R55, 15 Oct 2026) - keep the trail from the fill; settlement does not beat 15:05; 0 of 30 pass
Full study: `HUNT_R55_HERO_TRAIL.md` (pre-registered `hunt/r55/PREREG.md`, commit 1fbcc46). Boss had understood the 25%
trail to start "once the option has run up", and asked about removing the 15:05 square-off for hero tickets. The ticket is
R54's (NIFTY / SENSEX own expiry days, 1 lot CE + 1 lot PE in the cheap band, 12:30 entry; 14:30 for reference). Signal
source: none (time only); the trail works on the option premium. Design 142 expiry days (20 Nov 2024 - 31 Mar 2026), final
test 55 days (1 Apr - 13 Oct 2026), read once and not pristine (R43-R54 saw it). R54's pricing unchanged.
- Trail from the fill (T0, also a 25% stop) is the best start in design at every end time: -Rs 14,507 on 248 tickets
  (-91 per Rs 1,000). Armed after 2x / 3x / 5x (no stop before arming): -Rs 59,286 / -53,943 / -57,356. Hold:
  -Rs 48,733.
- Day by day, the armed trails lose Rs 278-315 more per expiry day than T0 in design (CIs below 0) and Rs 152-204 more in
  final. The reading is "worse" in all 9 comparisons at 12:30. Final at 15:05: T0 +Rs 1,559 (107 tickets), T2 -Rs 9,680,
  T3 -Rs 6,823, T5 -Rs 9,264, hold -Rs 12,331.
- Why: about 7 in 10 tickets never double. With T0 they are out in about 12 minutes, losing about Rs 175 each; without a
  stop they lose about Rs 624 each. T2's 175 never-armed design tickets lost Rs 1.10 lakh, and its 73 armed ones made
  +Rs 50k (sold at a median 1.7x).
- Settlement (the trail stays on until 15:29; ITM pays intrinsic at the mean of the index's 15:00-15:29 closes, less STT on
  exercise 0.125% / 0.15% from 1 Apr 2026; OTM is worthless): with T0 there is no difference (no ticket open at 15:05).
  Held, settlement minus 15:05 is +Rs 36 a day in design (CI -59 to +138) and -Rs 188 in final (-Rs 22,644 vs -Rs 12,331).
  Selling at 15:05 still collects time value that an OTM option loses at expiry. No settlement or 15:15 comparison reads
  "better".
- 2 Apr 2026 SENSEX 72,500 CE: T0 +Rs 280, T5 +Rs 2,205, held to settlement +Rs 15,770. 13 Oct 2026 NIFTY 22,650 CE: T0
  -Rs 140, T2 / T3 / T5 +Rs 2,884. These days motivated the question; they are not evidence for it.
- 0 of 30 cells pass; every cell lost in design. Keep T0 for the hero paper arm. 15:05 stays for everything else and
  anything live; changing it for the hero paper arm is Boss's call, and the data does not support it.

## Every other cancelled strategy on the Nov 2024+ market only (R51, 15 Oct 2026) - 1 of 125 passes, and it is thin
Full study: `HUNT_R51_CANCELLED_RECENT.md` (pre-registered `hunt/r51/PREREG.md`, commit 11fbca1d; trades only 21 Nov 2024 -
13 Oct 2026; design to 31 Mar 2026 walk-forward; final test 1 Apr - 13 Oct 2026 read once, not pristine because earlier
studies saw parts of 2025-26). Every trade is priced on ONE held contract, with dated charges, liq fills, next-minute fills and gap fills
at the worse price. The ORB family (R49) and zero-to-hero rules (R50) are not in it.
- Tested: Liquidity FINNIFTY (parked arm), R27's 8 Night holds, R28's GEX filters (G7 Liquidity BANKNIFTY, G10 Night),
  M4 SILVERM, Boss's 7 R44 sweep rules, R45's premium scalp (option-chart signal, non-default), F1's 20 rules, R29's
  EMA 9 x 21 cross + Boss's 3 ribbon ideas + R1-R10, R41's neural-net rules (R1-R6, R13, always-the-call), and B2's 4
  rules. Each was tested as originally specified, then with one small pre-registered re-tune: 68 originals and 57
  re-tunes gated, with BH over all 125.
- PASS (1): Boss's Donchian 20 on 5-minute index bars with re-tuned exits (1-ITM, stop -15%, target +30%, 30
  minutes, square-off 15:15, max 2 a day a side, no expiry days, 5 indices).
  - Final test: +Rs 48,689 on 1,775 trades (+Rs 27 a trade), p 0.002, q 0.025.
  - Design walk-forward: +Rs 1.82 lakh out of fold.
  - It is thin. At 2x spread and slippage it is about zero (-Rs 670 final, -Rs 85,770 design). It lost on NIFTY and
    MIDCPNIFTY in both periods, and its puts lost in both periods.
  - The whole final profit came from FINNIFTY (+Rs 49,040); without FINNIFTY it is -Rs 351.
  - A separate review after the read found two small issues (13 Oct expiry not flagged; time-stop spread volume).
    Together they change the final net to +Rs 47,563, and it still passes.
  - A paper forward test at most, if Boss wants it. R51 changes nothing in the app.
  - 15 Oct, cleaned re-pricing (Amendment A, foreign rows and stray prints removed): it still passes. Final 1,787 trades,
    +Rs 55,439, p 0.002, q 0.025; thinner at 2x costs (+Rs 5,757); the top 5 trades are 76% of the net; NIFTY loses
    (-Rs 18,955). R52's pass, H37-10:g17, is unchanged after cleaning (Amendment 2: 294 trades, +Rs 48,772, p 0.001,
    q 0.027).
- Near misses (stay off):
  - M4 SILVERM: final +Rs 1.0 lakh on 93 trades, p 0.026, q 0.14; design no better than random, p 0.66.
  - EMA 9/21 and 9/26 + ADX20: final +Rs 76-78k, but they lost in design.
  - F1-C1 and S7, R29 fades R3 / R6 / R8: positive in the final test, but they lost in design or depend on their top 5
    trades.
- Night: all 8 holds and the re-tune made less than the 09:16 sale on the same 86 final-test nights (Rs -5k to -19k).
  Keep the sale. G10's GEX filter fails (design p 0.75).
- Liquidity FINNIFTY: design +Rs 99,350 (252 trades, p 0.094); final +Rs 4,861 (94 trades, p 0.49), all from its top 5
  trades. It stays parked. G7 does not help (+Rs 4,653 vs +Rs 10,978 unfiltered).
- Everything else loses in the final test or is no better than random entries: R45, 19 of 20 F1 rules, R29's cross
  and Boss's ribbon ideas, every R41 rule (always buying the call overnight -Rs 5.36 lakh on 520 trades) and all 4 B2
  rules.

## Older rejected index rules re-tested on Nov 2024 onwards (R52, 15 Oct 2026) - 1 of 188 final reads passes; paper candidate only
Full study: `HUNT_R52_OLD_CATALOGUE_RECENT.md`. Pre-registered in `hunt/r52/PREREG.md`, with each rule's setting in
`results/rules_prereg.json`. Signal source of every tested rule: the index; VIX, OI or IV appear only as filters.
- **Scope.** The catalogue (`hunt/r52/CATALOGUE.md`) lists 268 distinct older rejected rules from the hunt series and
  the topic studies; R49-R51's rules are excluded. 114 of them, almost every "close" rule and Boss's own rules, were
  re-run with their own code on 20 Nov 2024 - 31 Mar 2026 (design, 1,468 settings), then read once on 1 Apr - 13 Oct
  2026 (188 reads). The measurement: one held contract, dated charges, R17 spread fills, 1 lot in force.
- **Final test.** 1 PASS, 123 FAIL, 64 too few trades (< 40).
- **The pass: H37-10 "open outside value and go".**
  - Rule: the previous day's 70% value area; open and the first 5-minute bar above VAH -> buy 1-ITM CE, below VAL ->
    PE, at 09:21; sell 60 minutes later; 5 indices.
  - Final: 294 trades, +Rs 48,772, +Rs 367 a day, win 50%, max DD -Rs 46,363, p 0.001, q 0.03, top 5 = 82% of net.
  - Caveats:
    - the 60-minute exit was a design re-tune; the original +15 / -10 point exit lost (-29k design, -1.9k final);
    - MIDCPNIFTY made +46k of the final, while FINNIFTY led on design;
    - with coin-flip twins its q would be 0.29.
  - Verdict: a paper candidate for Boss to decide on, never live without unseen paper first.
- **Fails only the same-side twin test.**
  - R4-8, Gann 2-bar swing, 30-minute exit: 242 trades, +38.9k, p 0.13.
  - H45-1, Liquidity entries with a ±10% bracket and a 30-minute stop: 341 trades, +31.3k, p 0.10.
  - Both have coin-flip p 0.001 and are re-tuned picks whose originals lost. Off.
- **The other old "close" rules stay off.**
  - Boss's Pine scripts lose or fail: Supertrend+EMA50 (ST-1), Matrix (MP-1, MV-1), and the Gann/Fib guide (R5-1),
    which fires about 0 times.
  - Also off: Chandelier, BTST, Camarilla, the H28/H42 calendar rules, swing and long-vol, the R24 patterns, and the
    NIFTY/SENSEX Liquidity versions.
- **Method lesson.** Same-side random twins leak the day's direction to breakout rules, and are degenerate (p = 1) for
  fixed-minute rules. Report a coin-flip-side p next to them (R52 Amendment 1).

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
  Boss's two session strategies, TAS 15m and AI's Opinion all fail or are too few to judge (GB1 34 trades, -$18.3k).
- Crypto (stop + profit lock on every trade; Delta and CoinSwitch costs; 31.2% tax per winner): all 8 rules lose after
  tax in design and final. Nearest: RC2 R8 day-high/low break on Delta options, +Rs 16.9k before tax, -Rs 28.9k after
  (313 trades). Buy-and-hold made +Rs 13-15k per Rs 1 lakh after tax over the same final window.

## MCX precious metals: 16 enhancement ideas on GOLDM / SILVERM proxies (R82, 18 Oct 2026) - 0 of 16 pass; the arms stay as they are

Full study: `HUNT_R82_MCX_PRECIOUS.md`. Pre-registered in `hunt/r82/PREREG.md` (commit d37b9b5); the final was read once.

**Data.** Proxies only, because the Drive lake (real MCX) was not reachable:
- Daily: spot gold / silver × USDINR × duty, 2005-2022 design, 2023 - Sep 2026 final.
- Hourly: COMEX gold / silver, Oct 2024 - Mar 2026 design, Apr - 16 Oct 2026 final.

The app's MCX costs and paper slippage apply. BH is across all 16 tests. Every rule has a stop, and every signal is on
the underlying.

**12-month trend with a stop** (T1-T4).
- Final +Rs 10.1-11.4 lakh a GOLDM + SILVERM lot-pair on 34-45 trades.
- The app's stop-less rule made +Rs 11.0 lakh on 6 trades (two longs held 2023-2026), so nothing beat it clearly.
- Design q 0.33; not significant.
- The lowest-risk version (T4: 3-ATR trail, no entry in the top 20% of volatility) kept 92% of the profit with a
  -Rs 1.9 lakh drawdown, against T0's -Rs 13.4 lakh open drawdown. It is a note for a future real-MCX re-test, not a
  pass.

**Other ideas.**
- Gold+silver agreement ("breadth") and Supertrend agreement (R72 / R73's lessons) did not carry over:
  - Donchian D0-D2 lost in design (-Rs 1.4 to -1.9 lakh on 134-139 trades).
  - The ratio regime and the DXY filter failed.
- The first US-cash hour with both metals agreeing (U2) was the only design pass: q 0.032 on 564 trades. In the final
  it lost **-Rs 5.84 lakh on 234 trades**; its design profit was silver's Jan-Mar 2026 run.
- Also lost: the overnight COMEX move (O1: final -Rs 1.29 lakh on 180), the US hour on CPI / NFP / FOMC days, and
  Liquidity 1h on MCX hours (buys, both sides, agreement, event skip).

**Not tested:**
- MCX options versus futures (no option history here);
- roll timing;
- real yields;
- USDINR alone;
- silver 15m.

**App:** nothing changes; nothing goes on paper.

## The other AI's stock-option "S2 Mean Reversion Dip Buy" (R59, 15 Oct 2026) - the +Rs 1,695 a trade is strike switches and expiry rolls; 0 of 84 pass
Full study: `HUNT_R59_STOCK_DIP_AUDIT.md` (pre-registered `hunt/r59/PREREG.md`, commit 8db2c18; design frozen 68e9ef6).
Claim: on the lake's rolling ATM stock options (10 stocks, 15-minute bars), buy CE / PE after the premium fell 20%+ in a bar,
sell 60 minutes later: 6,945 trades, 73.4% win, +Rs 1,695 a trade. Signal source: the option's own chart (NON-DEFAULT,
labelled); family B puts the signal on the stock (default).
- Reproduced on the rolling series: 6,689 trades, 73.5% win, +Rs 1,836 a trade, +Rs 1.23 crore (TCS CE +Rs 3,940).
- 89.5% of those trades and 100.1% of the profit involve a strike switch or an expiry roll between the bars. The 701
  one-contract trades lost Rs 18 a trade; 42 exits priced on the next month's contract made Rs 33 lakh. Their trades
  valued on the contract actually held: 6,509 trades, Rs -3 a trade before any spread.
- Honest version (held contract, 1 lot from the bhavcopy, next-minute fills, per-stock Roll spread 0.26-0.50% a side,
  dated charges, no trade in the last 2 sessions before expiry; design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 12 Oct
  2026 read once): pooled A no stop 78 final trades, +Rs 2 a trade (design 381, -Rs 32); A with a 25% stop 80, -Rs 57;
  B (stock moved 1% against the option) 75, -Rs 252, and -Rs 380 with the stop. No per-stock cell has 40 trades; AXISBANK
  CE's +Rs 16.6k is 5 trades. One lot costs a median Rs 8,730 (A) / Rs 12,820 (B).
- Lesson (R45, R46, R59): on the lake's rolling option files, always check that entry and exit are the same contract.

## Data lake from Dhan API v2 (DATA-LAKE, 12-13 Oct 2026) - read research/data/README.md first
- `research/data/`: fetch/build/validate scripts, `CATALOG.json` (file list, rows, sha256), `VALIDATION.md`.
  The data is Parquet (zstd), meant for release `data-20261012`. This session was not allowed to create GitHub
  releases (HTTP 403), so run `research/data/publish_release.py` from a machine that can; `CATALOG.json` has
  `release_published: false` until then.
- Coverage: index daily 2006+ and 1-min 2017+ (NIFTY) / 2021+ (others) for NIFTY, BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX, BANKEX, INDIA VIX. Expired index options at 1 minute (rolling ATM-10..+10, CE+PE, OHLC, volume,
  OI, IV, spot), weekly and monthly nearest expiry, from Aug 2021 (SENSEX from 2023). MCX options at ATM-5..+5 from
  mid-2025. Continuous near-month futures daily from 2011. NIFTY 50 + NIFTY BANK stocks: daily (2002+), 1-minute (2017+),
  stock futures daily, stock options (ATM-5..+5, monthly).
- Validated against NSE bhavcopy, NSE index closes and Yahoo: index closes 99.7-100% within 0.05%. Option contract-day
  high/low match 87-98%. Dhan's option OI runs about 1-5% above NSE end-of-day OI, so treat it as intraday OI.
- Dhan does not give historical bid/ask, expired futures minutes, or next-month continuous futures
  (expiryCode 1/2 = same as 0). Data APIs are capped at 100,000 requests/day, so stock options are still partial
  (15 Oct note, per data/README.md): fetched share of the planned 30-day chunks is ATM 99.4%, ATM±1 100%, ATM±2 23%,
  ATM±3..±5 0%; every stock-option file runs to 14 Oct 2026. The fill is paused because the Dhan token expired. Stock
  options match NSE only ~68% at contract-day high/low (index options 86-98%). Use stock-option minutes with care.
- `STOCK_OPTIONS_COVERAGE.json` counts fetched chunks, not rows, so it overstates what is usable (R58, 15 Oct). Share
  of design sessions (20 Nov 2024 - 31 Mar 2026) with ATM CE and PE for >= 300 minutes differs by name: HDFCBANK,
  TCS and AXISBANK about 91% (HDFCBANK has no ATM puts Dec 2025 - Jan 2026, TCS no ATM calls Feb - Mar 2026);
  BSE 63% and BAJFINANCE 58%, with no option rows before Jun 2025 although the coverage file shows >= 99% for both.
  Check rows per stock before using its options.

## Solo 2.0: a trained Solo that picks its own paper trades (R57, 15 Oct 2026) - 1 default pass (A2), paper candidate only
Full study: `HUNT_R57_SOLO_2.md` (pre-registered `hunt/r57/PREREG.md`, commit a2278995; NIFTY, BANKNIFTY, FINNIFTY,
MIDCPNIFTY; design 21 Nov 2024 - 31 Mar 2026 purged walk-forward with monthly refits; final 1 Apr - 14 Oct 2026 read
once; one held contract, R17 fills, dated charges; 10 variants, one BH family).
- **A2 passes.** It is a logistic "meta-labeller": take or skip the day's candidates from four index rules (S1 SoloMidday,
  S2 R51 Donchian-20-RT, S3 R52 H37-10, S4 15-min ORB), at most one trade a day.
  - Final: 128 trades, 59% won, +Rs 99,172 (+775 a trade), max DD Rs 18,056, 6/7 months green, top-5 40%, 2x costs
    +Rs 80,849.
  - Twin p 0.002, q 0.01. Design walk-forward +Rs 55,910 on 166 trades.
  - Plain SoloMidday on the same final period: 36 trades, -Rs 21,589.
  - Caveats: 72% of the final profit is MIDCPNIFTY (design walk-forward there: -Rs 153 on 90 trades), and 92 of the 128
    trades are S3, whose exit R52 chose and read on this same window. Without MIDCPNIFTY: +Rs 27,586 on 46 trades.
  - A3 (boosted Huber) also passes: +Rs 39,756, p 0.004.
  - A4 (with R47 C4, an option-chain signal, so NON-DEFAULT) also passes: +Rs 59,706, p 0.002.
  - A1 (boosted trees) fails: its top 5 trades exceed its net.
- **The direction model fails in all 6 versions** (next 30 / 60 minutes at 09:45, 10:30, 12:00, 13:30): final -Rs 19k to
  -51k. Out-of-sample AUC is 0.47-0.53; a leaky random split shows 0.52-0.61.
- Model skill is small everywhere: AUC 0.52-0.54 on held-contract labels. The gain comes from skipping the worst
  setups and days, not from reading direction.
- On-device: `results/models/A2.json` (3 KB), `r57eval.py` (pure-Python reference scorer), golden vectors (parity
  1e-16). `r57retrain.py` is a monthly retrain that replaces the model only if the latest 6 months pass the gates.
- Verdict: A2 is a paper candidate for the coordinator's app build, to run next to plain SoloMidday with a forward bar.
  Paper only, forever: Jarvis's own trades never go live.

## Buying stock options on the top 10 stocks (R58, 15 Oct 2026) - 0 of 38 pass; the same signals in the stock lose too
Full study: `HUNT_R58_STOCK_OPTIONS_TOP10.md`. Pre-registered in `hunt/r58/PREREG.md` (commit ce45ea9, before any P&L;
amendment 1 before the final read).

**Universe and window.**
- The 10 NIFTY 50 names with the most stock-option traded value (design bhavcopies) whose lake has ATM CE + PE on
  >= 95% of design sessions: RELIANCE, SBIN, MARUTI, ICICIBANK, INFY, LT, KOTAKBANK, M&M, TRENT, TATASTEEL.
- HDFCBANK, BSE, BAJFINANCE, TCS, BHARTIARTL, AXISBANK, BEL and ADANIENT failed coverage: lake holes.
- Design 20 Nov 2024 - 31 Mar 2026; final test 1 Apr - 12 Oct 2026, read once.

**How it was traded.** Signal source: the stock (NIFTY too for relative strength).
- 1 lot of the nearest-monthly ATM option, priced on one held contract.
- The real lot by date (bhavcopy / traded volumes).
- Out by 15:15 three trading days before expiry (physical settlement).
- Dated charges plus a per-stock fitted spread (0.38-0.80% of premium a side).

**Result.**
- 38 rules in 6 families (opening range, relative strength, gaps, volume / VWAP, daily Donchian / momentum, R51's
  Donchian-20 and R52's H37-10 on stocks).
- **0 pass.** The four design passers all lost in the final test, e.g. `gap-go-1` design +Rs 15,166 (140 trades) ->
  final -Rs 46,398 (103).
- The only two rules positive in the final test (`rs-d-l3-h3` +Rs 9,014 on 75 trades, `rs-d-l3-h1` +Rs 5,421 on 82)
  had lost in design, have p 0.13-0.20, and come from KOTAKBANK alone. BH q = 1.00 for all 38.

**Why.**
- Before costs the signals are about break-even on the option's own prints: -Rs 15 a trade in design, -Rs 78 in the
  final test.
- A round trip costs about Rs 280 a lot (about Rs 200 spread and slippage + Rs 80 charges), about 2% of the median
  Rs 14,184 premium. All 38 rules together lost Rs 76.4 lakh on 21,519 final trades.

**The stock in cash** (the same minutes, lot-many shares) also lost: -Rs 1.01 crore on 21,061 final trades.
- So the signals, not the instrument, are the problem.
- Per trade the option loses fewer rupees (it moves about half as much as the stock).
- But it needs about Rs 14k of capital against about Rs 1.3 lakh MIS margin, so it loses about 2.5% of its capital a
  trade against 0.4% for the stock.

**Context.**
- Holding swing options beyond the data's ±1-strike band (modelled) made them worse.
- Trading into the last 2 days before expiry lost more.

**Not tested:** post-results drift. No results calendar exists in the lake or the repo; `brain/calendar_data/events.csv`
has budget and RBI dates only.

**App:** nothing changes; nothing goes on paper.

## Liquidity direction filters: Supertrend, a 10-candle wait, the other indices (R79, 18 Oct 2026) - 0 of 6 pass; keep the plain rules
- BANKNIFTY Liquidity 15+5, 20 Nov 2024 - 23 Feb 2026 (research-data release; the Drive lake was not reachable). Signal
  on the index, option bought. Every filter made less than the plain rules in walk-forward (B0 Rs 32,567 on 173 trades).
  5-min Supertrend skips almost nothing, 15-min skips winners, a 10-candle wait gives up the first move (design Rs 15,268
  vs Rs 79,808), 2-of-3 other indices agreeing was -Rs 10,021 in walk-forward and no better than random skips (p 0.20).
  Short final (48 trades) is weak evidence. `HUNT_R79_LIQUIDITY_DIRECTION.md`.

## Liquidity 15+5 exits and trade management, and which indices run it (R81, 18 Oct 2026) - 0 of 66 pass; keep the own exits
Full study: `HUNT_R81_LIQUIDITY_EXITS.md` (pre-registered `hunt/r81/PREREG.md`; final 1 Apr - 13 Oct 2026 read once,
already read by earlier studies, so weak). Signal on the index in every rule.
- What was tested, on the pooled arm (881 design / 357 final trades) and on each of five indices:
  - index rules: a stop beyond the breaking bar, an ATR chandelier trail, a no-progress time stop;
  - scaling out half at +15%, one re-entry after a stop, adding a lot at 50% to the target;
  - 14:30 or 15:20 square-off, holding runners overnight with a GTT, breakeven after a structure confirmation;
  - no premium stop.
- 0 of 66 passed. Nearest miss: no -15% premium stop. It was never worse (+Rs 2.6k design, +Rs 23.2k final) but
  q 0.165, and one MIDCPNIFTY trade carries the final gain. Earlier exits (trail, no-progress stop, scale-out)
  lost Rs 62k-85k in design. Overnight holds lost in both periods. The pyramid made +Rs 58k in design and lost
  Rs 20k in the final. The runners are the edge, again.
- Per index on the newest lake (plain rules): BANKNIFTY (+110k / +64k / +11k) and FINNIFTY are positive in every period.
  MIDCPNIFTY lost in the final and trailed random entries in design. NIFTY loses. SENSEX's gains are all its top 5 trades.
  In the final period, no index's profit survives removing its top 5 trades or beats random entries. Do not add NIFTY or SENSEX.

## MCX energy and base metals: event days, US-open ORB, Liquidity, Donchian/breadth, seasonality, EVE regime, trend exits (R83, 18 Oct 2026) - 0 of 13 pass
Full study: `HUNT_R83_MCX_ENERGY.md` (pre-registered `hunt/r83/PREREG.md`, commit 97f2e00). Intraday signal source: the US
benchmark future as a labelled PROXY (WTI, Henry Hub; Dukascopy minutes 2016-2026), traded as 1 CRUDEOILM / NATGASMINI
lot inside MCX hours. Daily: the MCX continuous future (copper: COMEX x INR). Design 2016/2012-2022, walk-forward
2019-22, final 1 Jan 2023 - 13 Oct 2026, read once, plus a real-MCX futures and 1-ITM option replay (Aug 2025 - Oct
2026). Strict costs: McxCosts + half-spread 0.03% crude / 0.06% natgas futures, 0.30-0.60% crude / 0.45-0.90% natgas options.
- 33 variants, BH across all: 0 pass. EIA fade/follow (E1, E2), storage fade/follow (E3, E4), OPEC+ (E5), the US-open ORB
  with SMA/breadth agreement (E6), the Liquidity book on crude 1h/15m (E7), NG seasonality (E9) and the rollover week (E12)
  all lose in design, walk-forward and/or final. Gross is about zero; the costs decide.
- The app's NG evening breakout (EVE) loses after costs on 2016-2026 Henry Hub data: design -Rs 1.43 lakh (1,634 trades),
  final -Rs 51k (881), though its timing beats random (twin p 0.015). A calm-ATR filter halves the loss but stays negative
  (final -Rs 37.6k, 533 trades). Switching EVE off is a risk-lowering paper call for Boss.
- The 12-month trend arm on CRUDEOILM + NATGASMINI made +Rs 53.6k in the final (113 leg-months) after -Rs 14.6k in
  design; neither a chandelier stop nor a weekly check beat it. Still "not proven".
- Crude daily Donchian-20 + Supertrend: final +Rs 21.8k on 27 trades, but walk-forward below unfiltered, twin p 0.23 and
  carried by its top 5. Agreement filters do not help on commodities out of sample.
- Same signal, option vs mini future (real MCX): the option was worse in 4 of 7 replays, every crude one.
- Not tested: API Tuesday (after MCX hours), zinc and aluminium (no data; Dhan token expired), trend-arm roll timing (no
  next-month prices).

## Breadth Donchian, how many positions per signal (R84, 19 Oct 2026) - 0 of 8 pass
Full study: `HUNT_R84_BREADTH_RISK.md` (pre-registered `hunt/r84/PREREG.md`, commit 5693400; amendment A1 b5053cb).
Signal source: the indices' own 5-minute bars (R72 C1, 4 of 5); R75's C harness, design 20 Nov 2024 - 31 Mar 2026
(936 signals, 3,071 trades, K0 Rs 2,16,929 reproduced to the rupee); final window not read (nothing passed).
- Holding fewer of one signal's correlated positions (2 first, 2 or 1 strongest, BANKNIFTY only, a Rs 5k risk budget,
  skipping NIFTY shock bars > 5x, or the app's cap of 2 open) cuts the worst day 32-77% but the max DD only 0-36%, and
  keeps 28% of the net at best (X-skip). None beats a random subset of the same size (best p 0.13, BH q 0.91+).
- The edge is in the wide signals: 4-5 positions made Rs 3.45 lakh, 1-3 lost Rs 1.28 lakh. The biggest break in ATR is
  the worst pick (Rs 15k on 936 trades). NIFTY shock bars 5-10x usual are Breadth's best (+Rs 1.44 lakh on 142); calm
  bars (<= 2x) lose (-Rs 1.97 lakh on 250), a post-hoc note only.
- A per-signal cap does not cap exposure: C1 keeps firing, and the indices left out enter a bar later and lose.
- 19 Oct (index side rebuilt from Yahoo): 5 of 5 broke at 09:30 on a 14.5x NIFTY move. As now -Rs 9,722; the app's cap
  -Rs 5,049; X-skip Rs 0. About 2 signals a month lose Rs 9.7k or more (36 in design): normal for this arm.
- The app's same-direction cap on Breadth alone turns design +Rs 2.17 lakh into -Rs 19k. Recommendation: Breadth
  record-only on paper (or uncapped, Boss's call); not a live candidate at 1 lot x 5 indices (account stop breached).

## MCX candle anatomy and option-buying rules from it (R85, 19 Oct 2026) - 0 of 4 pass; nothing goes to paper
Full study: `HUNT_R85_MCX_CANDLES.md` (pre-registered `hunt/r85/PREREG.md`, commit 4ec89fc; amendment A1 6c5e7f3 froze the
rules before any P&L; deviation D1 in `hunt/r85/NOTES.md`). Signal source: the MCX future itself (lake `spot` = the
nearest option's underlying, close-only candles), trade = BUY the 1-ITM call / put (Boss, 19 Oct: options buying only).
Real MCX minutes, GOLD/GOLDM/SILVER/SILVERM/CRUDEOIL/NATURALGAS; design 5 Aug 2025 - 31 Mar 2026, final 1 Apr - 13 Oct 2026
read once.
- Part 1: 7,564 candle cells (11 shapes, 22 contexts, 7 sessions, 1m-1h) at h = 3; 59 BH q < 0.10, but most are the
  2025-26 metals rally (red candles "reverse" in any up-trend). 18 survive a same-colour (drift-neutral) baseline; on day
  candles (2010-2022) none does. After 1 Apr the 18 kept their sign 15 times but shrank to about a quarter (median 0.28 ->
  0.08 ATR); the survivors are mostly 1-minute effects, far below an option round trip (1.5-4.5 one-minute ATRs).
- Part 2 (the 4 cells larger than option costs): final R1 SILVER big red 5m on 2x option activity -> SILVERM call
  -Rs 17,053 (80 trades); R2 GOLDM red 5m in a high-volatility lull -> GOLDM call -Rs 29,131 (74); R3 SILVERM red 15m
  outside bar pre-US -> put -Rs 18,125 (28); R4 SILVERM red 5m hammer at the US open -> call -Rs 30,122 (56). Twin p
  0.19-0.87, walk-forward negative for all. No month below -Rs 13k (worst -Rs 7.6k).
- Hammer, shooting star, engulfing, inside/outside bars, marubozu and three-in-a-row carry no tradable meaning on MCX.
  OI classes (long build-up etc.) do not separate the next 15 minutes (Sep-Oct 2026 true futures, descriptive).
- Candles are largest in the US-open hour (GOLDM 15m 1.7x the 14:00-17:00 lull; natural gas from 17:00 2-3x its morning).
## The big players' footprints: FII positioning, CFTC managed money, stop runs, VWAP algos, dealer gamma, rolls (R86, 19 Oct 2026) - 0 of 23 pass
Full study: `HUNT_R86_BIG_PLAYERS.md` (pre-registered `hunt/r86/PREREG.md`, commit 65267ec); the cited guide to who the
big players are and how they work: `BIG_PLAYERS_GUIDE.md`. Signal source: the underlying or positioning data about it;
trades on the underlying's future with real costs and a stop. BH over 23 tests; final windows not read (nothing passed).
- FII positioning levels, 5-day shifts and option stance (NSE participant OI, 2015-2022 walk-forward) did not call NIFTY's
  next 5 days: 120-184 trades per rule, Rs -0.65 to -5.31 lakh, none significantly better than random days (twin p 0.37-0.99). Client option writing and
  FII crowding add nothing to a VIX-based 5-day range forecast (1,972 forecasts).
- CFTC managed-money extremes (COT index, 4 weeks) leaned contrarian on MCX silver: +Rs 2.14 lakh on 47 SILVERM trades,
  twin p 0.006, but -Rs 27,528 without the top 5 and BH q 0.115. Not proven. Weekly COT changes carry nothing.
- Stop runs: a poke beyond yesterday's high/low, a round number or the opening range that closes back within 10 minutes
  is not followed by a reversal (-2.9 to +1.4 bps in 60 minutes). Fading it lost on all 6 level x index tests (295-804
  trades, Rs -2.3 to -5.5 lakh, 2022-24). Keep trading breaks, not fades.
- VWAP: a midday stretch from NIFTY's turnover-weighted VWAP tends to continue, least on busy days (1,465 days
  2019-2024); the fade to VWAP on busy days lost Rs 64,786 on 71 trades. Early turnover predicts a busy day (corr 0.67).
- Dealer gamma adds nothing to the rest-of-day range forecast once VIX and the morning range are known (492 days per
  index, 0.95-2.8% worse error). Size stops from VIX and the range, not GEX.
- Gold moves about 14% more in the 5 sessions before a GOLDM contract roll (620 sessions 2012-2022, p 0.010, q 0.115):
  a size fact, not proven. NIFTY rollover % does not call next month (32 trades, carried by its top 5).
- The phone's tick recordings (large prints, absorption) were not reachable: idea 7 not run.

## How the big players trade, read backwards from index and constituent minutes (R86b, 19 Oct 2026) - footprints yes; 0 of 8 follow rules pass
Full study: `HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md` (pre-registered `hunt/r86/PREREG_B.md`, commit 155f3f2). Data: NIFTY /
BANKNIFTY 1-minute bars and the 59 current index stocks' minute turnover, 2019-2024 (design); final 2025-26 not read.
- Big selling is fast, big buying is slow: on FIIs' heaviest index-futures selling days NIFTY made about 61% of its move
  by 10:15 (gap 37%, first hour 24%) and 6% in the last hour (169 days); on their heaviest buying days 40% by 10:15 and
  16% in the last hour, with a busier close (18.3% vs 16.1% of turnover in the last 30 minutes; 179 days). Clients'
  heavy-buying days look like FII-selling days (they take the other side).
- Funds trade at the close on benchmark days: the last 30 minutes carry 26.7% of NIFTY's turnover at month-end (72 days)
  and 23.2% on monthly expiry days, vs 16.1% on all days (1,480).
- Basket bursts (>= 80% of the stocks one way on 3x turnover) happen ~0.7 a day on NIFTY, 2.4x as often on FII-selling
  days, and agree with the day's direction 65% of the time, but the 15-60 minutes after one move no more than after a
  random minute (997 bursts). Absorption at the day's extreme (heavy volume, no move) was almost never seen (2 + 4 cases).
- Following any of these early (steady morning drift, bursts, absorption fades, a heavy last hour held overnight): 0 of 8
  pass (best q 0.319). The steady-drift follow on NIFTY broke even (+Rs 3,403 on 82 trades, all in its top 5).

## Catching the big players, six ways, and every early seller pattern (R86c, 19 Oct 2026) - 0 of 12 pass; sellers recognisable by 09:45
Full study: `HUNT_R86C_CATCH_THEM.md` (pre-registered `hunt/r86/PREREG_C.md`, commits 6f7c61f and e603e38). Design to
2024 (folds 2022-2024); final 2025-26 not read. Signal on the underlying except C4 (option-chain trigger, non-default).
- Seller patterns by 09:45 (2019-2024): a gap down >= 0.5% marks a heavy FII selling day 3.25x more often than chance
  on NIFTY (132 days) and 2.73x on BANKNIFTY (107); a gap down with no recovery in 30 minutes 3.5x / 3.2x (55 / 45 days);
  India VIX up >= 5% 2.3x / 2.2x (87 / 87). Closing at the lows, failed bounces, breadth and volume signs are 1.0-1.7x.
- After a gap down NIFTY tends to bounce from 09:45 (+7 to +21 bps to 11:15); BANKNIFTY keeps falling when the gap is
  not recovered (-17 bps to 11:15, -23 bps to 15:15, 45 days).
- Seller detector at 09:45 (C6): BANKNIFTY +Rs 36,188 on 13 shorts, twin p 0.002, q 0.024, but -Rs 12,654 without its
  top 5: fails. FII-buyer detector at 12:00 (C5): AUC 0.75-0.90 on NIFTY, trade +Rs 28,842 on 40 (q 0.23, carried by
  the top 5). Both not proven; C6 is worth a fresh pre-registered test on new data.
- Buying the VWAP dip on slow-buying days (C1), fading the month-end close (C2), straddles on predicted busy afternoons
  (C3; buying the 11:30 straddle every day lost Rs 124-135 a day) and following unusual OTM option volume (C4: -Rs 1.71
  lakh / -Rs 1.38 lakh on 215 / 256 trades, worse than random minutes) all fail.

## The BANKNIFTY seller detector, improved, on unseen data (R86d, 19 Oct 2026) - 0 of 4 pass; it recognises sellers but does not pay
Full study: `HUNT_R86D_SELLER_DETECTOR.md` (pre-registered `hunt/r86/PREREG_D.md`, commit 544ddd5; post-hoc design
committed a9624bc before the one read of 1 Jan 2025 - 14 Oct 2026). Signal on the underlying; short at 09:45.
- On the unseen window the detector still ranks heavy FII-selling mornings well (AUC 0.72-0.74), but shorting them lost:
  R86c's C6 as it was -Rs 10,477 on 23 trades (it made +Rs 36,188 on 13 in 2023-24); pooled training -Rs 50,470 (31);
  hold to 15:15 -Rs 5,084 (23); the plain gap-down rule -Rs 16,336 (25); combined -Rs 41,361 (62). Win rate 32-39%.
- Bought as an ATM put, three versions were +Rs 7.5k to +Rs 17.7k, all negative without their top 5. Rejected: a seller
  at work at 09:45 has usually finished its push; the bounce hits the stop.
## MCX Data Brain, 5 levels (MB1, 20 Oct 2026) - size of the next hour predictable on 5 of 6 (holdout 8-13%), direction not; 0 of 16 brain rules pass
Full study: `BRAIN_MCX.md`, rules `HUNT_MB1_MCX_BRAIN.md` (pre-registered `brain/mcx/PREREG.md`, commit 8f1d9f5b). Signal
source: the MCX future's minute closes (the near future from the 6 option books, Aug 2025 - Oct 2026); ATM IV as
context. Data stored as Parquet zstd cube / features and float32 tensors (memmap .npy, Arrow, joint [11 x minute x 56],
120-minute windows) on Drive `derived/mcx_brain/v1/`. Design to 23 Jun 2026 (226 sessions, walk-forward ~150), locked
holdout 1 Jul - 16 Oct (76 sessions) read once for the predictions only.
- L1 size of the next 60 minutes beats the best plain guess (often the option market's own IV) by 14-19% in design and
  8-13% on the holdout on GOLD, GOLDM, SILVER, SILVERM (and crude 13% on the holdout after 4% in design); natural gas
  17.5% then 3.5% (fails). 3,917 holdout forecasts a symbol.
- Direction (AUC 0.48-0.54), persistence of the last hour (47-51%), the US open (0 of 12) and follow/fade around
  releases (0 of 8): not predictable. US CPI/NFP hours are 1.15-1.74x the usual hour on gold/silver (9-17 releases),
  EIA gas storage 1.23x (33): a risk fact. The L2 autoencoder state adds nothing (0 of 12); the L3 committee's one
  design pass (SILVERM 53.2% of 3,586) fell to 47.1% of 1,096 on the holdout.
- L4, 32 variants, real MCX costs: 0 of 16 pass. Only lead: B2 "big-size follow" on SILVERM (+Rs 2.53 lakh, 570 trades)
  and NATGASMINI (+Rs 20k, 421; q 0.08), both carried by their top 5. Buying the straddle when the brain expects more
  movement than the IV loses on all four (17-32% winners). Nothing goes to paper; the app is unchanged.
- Not modelled: CRUDEOILM, NATGASMINI, COPPER, ZINC, ALUMINIUM (20-37 sessions, all after the design end; cards only).
## Candle anatomy: every candle shape on the indices (R87, 19 Oct 2026) - no shape predicts direction; 0 of 3 rules pass
Full study: `HUNT_R87_CANDLE_ANATOMY.md` (pre-registered `hunt/r87_candle_anatomy/PREREG.md`, commit faa91cd; amendments
A0-A3). Signal source: the index's own 1m / 5m / 15m candle (NIFTY, BANKNIFTY, SENSEX spot), design Aug 2021 - 14 Oct
2025, holdout 15 Oct 2025 - 16 Oct 2026 read once. Knowledge pack for Jarvis: `hunt/r87_candle_anatomy/knowledge/`.
- After any of 43 shapes / patterns / footprints, the index went up over the next 5 candles 43-62% of the time (92% of
  234 tested 5m/15m cells within 45-55%) vs ~51% for matched candles (same time, trend, volatility, colour). Big tail,
  big head, both, small both: no edge on any index (NIFTY 15m q 0.79-0.95; holdout q >= 0.98).
- A long wick is not a defended level: a big tail's low is broken within 10 candles as often as any low at the same
  distance (NIFTY 5m 59% vs 57%, n 3,314). Only the green marubozu's low holds a little better (NIFTY 15m 38% vs 42%).
- "Reversal" shapes at trend extremes mostly mark continuation (NIFTY 15m hammer: -0.47 ATR after 20 candles vs +0.19
  matched); stop runs above the day's high are followed by more upside, not a reversal (agrees with R86).
- 4,049 shape x context cells (trend, day/prior-day levels, VWAP, opening range, gap, time, ATM OI, gamma proxy, value
  area, expiry): 0 pass. 2 of 17 design findings held out of sample, both 1-minute and worth 1.6-1.8 points (< an
  option round trip of 1.9 NIFTY / 6.4 SENSEX points).
- Rules on modelled premiums (A1): NIFTY 5m three white soldiers -> call -Rs 3,910 (160 trades); NIFTY 15m stop run
  above the day high -> call -Rs 25,064 (270); SENSEX 15m gravestone doji -> put +Rs 14,373 (71; twin p 0.026) but
  walk-forward -Rs 1,923 and negative without its top 5. Holdout not read for rules. Volume / order-flow results rest on
  an ATM-option activity proxy that correlates only 0.07-0.15 with true futures volume. MCX: see R85 (0 of 4).
- R87B (20 Oct; `PREREG_B.md`): 30m / 60m / daily shapes and named 3-candle sequences: 0 of 225 pass. One data-driven
  cluster held out of sample: SENSEX 15m green, green, then a medium red (a pullback after a rise) -> more upside (+0.18
  ATR design n 840; +0.26 ATR / +22 points holdout n 182). As a 1-ITM call with a stop under the red candle it lost
  (-Rs 21,127, 162 trades). Exiting an ORB breakout on a big wick against it helped in both windows but not
  significantly out of sample (+Rs 16,107, p 0.40); exits on a strong opposite candle made arms worse. Real option
  prices could not be fetched for the design window (Dhan degraded): still model-priced.

## What moves the candle: 22 parameters vs the next candle's size and direction, 5 indices + 10 MCX (R88, 20 Oct 2026) - size yes (225 of 305 hold), direction no (0 of 8)
Full study: `HUNT_R88_WHAT_MOVES_THE_CANDLE.md` (pre-registered `hunt/r88/PREREG.md`, commit 8eb9206; holdout 1 Jun -
19 Oct 2026 read once). Signal source: the underlying; the option chain is context only.
- Size of the next 5m/15m candle is predictable out of sample: previous candle big 1.5x (indices) / 1.65x (MCX), recent
  volatility 1.4x / 1.7x, yesterday's range 1.3x / 1.4x, high VIX/IV 1.3x, high PCR 1.4x (indices), expiry day 1.27x;
  rich implied vs realised 0.85-0.9x, call-heavy volume 0.87x. A model of all of them beats the time-of-day baseline by
  19-22% (index 5m) but the simple "last size + recent volatility" rule does most of it (model +2-7%).
- Direction: 8 of 841 design tests passed, 0 held; model AUC 0.485-0.535; best top-decile moves (SENSEX 5m +3.3 pts)
  are below an option round trip (6.4).
- With the candle (not a forecast): NIFTY's same candle explains 51-90% of the other indices' candles; VIX falls on up
  candles; on MCX the PCR falls on up candles (crude 20-28%).
- Use: size stops, targets and position size by expected candle size; never PCR/OI/skew/volume as a direction signal.

## A strategy from R88: buy a straddle when a big candle is expected (R89, 20 Oct 2026) - 0 of 18 profitable
Full study: `HUNT_R89_SIZE_STRADDLE.md` (pre-registered `hunt/r89/PREREG.md`, commit 5f4f988). Since R88 can forecast
size but not direction, the only option-buying use is a straddle (ATM call + put, 1 lot each, 15 or 60 minutes) on 5
indices and GOLDM/SILVERM/CRUDEOIL/NATURALGAS. Real option minutes and charges. Design: 4,302 straddles, -Rs 12.8 lakh,
win rate 2-40%; out of sample (Jun-Oct 2026): 2,214 straddles, -Rs 4.4 lakh, win rate 3-35%, 0 of 18 versions positive.
Gross before costs is about zero (options already price the expected move); costs (4 orders) decide; random timing
loses about the same. Use R88's size forecast for risk sizing only.
## Previous-day high / low break on the 1-minute chart (R89, 20 Oct 2026) - 0 of 180 pass; nothing goes to paper
Full study: `HUNT_R89_PDHL_BREAK.md` (pre-registered `hunt/r89_pdhl_break/PREREG.md`, commit 6cd81b9; notes N1). Signal
source: the underlying (index spot 1m OHLC; MCX near future, close-only); the 1-ITM option bought on real prints (R51
engine / R53 rules, real charges and slippage). Design 20 Nov 2024 - 31 Mar 2026 (MCX from 5 Aug 2025), holdout 1 Apr -
19 Oct 2026 read once. 20 variants (break and go / break and reclaim x levels, wick, 1/3/5m, HTF filter, fractal, RR,
window, first trade) x 9 instruments, BH over 180, random-entry twins.
- Boss's rule (break, a same-colour candle beyond, entry on its break, stop at its other end, swing target) is as good as
  a random entry minute: five indices base -Rs 3.07 lakh on 2,925 design trades (twins -Rs 3.13 lakh), holdout -Rs 1.28
  lakh on 1,210 (twins -Rs 1.65 lakh). At the exact levels the index made +0.4 to +3.5 points a trade; the option round
  trip costs 1.1-3.7 points.
- About a third of trades are false breaks (stopped within 3 candles). The daily + 15m trend filter does not cut them
  (NIFTY 32% -> 39%) and lowers the win rate; it only halves the trade count. The reclaim reading loses the same.
- Near-miss: FINNIFTY break and go on 3m, design +Rs 56,447 (474, p 0.002, q 0.12) -> holdout -Rs 6,921 (190). MCX:
  GOLDM / SILVERM 1-ITM options print too rarely to fill half the signals; every MCX cell lost. On all five indices the
  base rule breaks the -Rs 13k month in 12 of 17 design and 6 of 7 holdout months.
- Not the arms' signal (half the time on the other side of Breadth Donchian; daily corr -0.09 to +0.26) and it loses on
  their days and on the other days. PDH / PDL stay context levels only.
- R89B (Boss's "enhance it", 20 Oct; `PREREG_B.md`, amendment A1): 15m / 5m candles, ATR stop, let-winners-run exit
  (breakeven at +1R, 1R trail), breadth (4 of 5 indices), opening range, gap filter; 9 variants x 5 indices, holdout
  Aug 2021 - Nov 2024 (Boss's exception). 0 of 45 pass. Bigger candles made it worse; the run exit and ATR stop helped
  a little (ATR halves false breaks, 33% -> 14%); breadth cut losses 62% / 76% but never turned positive. The one lead,
  FINNIFTY 5m ATR + run + breadth, +Rs 78,202 (314 trades) in design, lost Rs 40,647 (401) in 2021-24. Method lesson:
  twins that keep the day's side carry hindsight on long holds; use after-trigger twins.

## The "Almazov Trade: MTF Structure & BOS Entries" indicator (R90, 20 Oct 2026) - 0 of 99 pass; a chart overlay only
Full study: `HUNT_R90_ALMAZOV_BOS.md` (pre-registered `hunt/r90_almazov_bos/PREREG.md`, commit 439876c; notes N1). Signal
source: the underlying (index spot 1m; MCX near future, close-only): swings, CHoCH / BOS structure on the chart and a
higher timeframe, buy-stop at the chart swing, stop at the opposite chart swing, target the HTF swing. The 1-ITM option
bought on real prints with real costs, flat 15:15 (15:05 expiry). 11 variants (1/3/5/15m charts with 4x HTF, 5m+60m,
15m+75m, 15m+day, swing 3, RR 2, no fill before 09:30, daily trend) x 9 instruments, BH over 99, after-trigger twins.
- As written (bar fills, no costs, held for days) the indices look positive on 9 of 11 settings (5m/60m +Rs 3.30 lakh,
  223 trades); flat by 15:15 the target (about 2x risk away) is rarely reached (5m: 3 of 76 index trades) and the option
  bleeds time value: design option -Rs 1.19 lakh to +Rs 41,833 pooled per setting, no better than random entry minutes.
- Signals are rare (5m: 0.03-0.06 a day per index); 79 of 99 cells under 40 trades. Few false breaks (0-9% of index
  trades stopped within 3 bars): the loss is slow, not whipsaw. MCX lost on every setting in both windows.
- Design leader MIDCPNIFTY 15m / day HTF +Rs 48,830 (34, p 0.068) -> holdout -Rs 25,462 (9). Holdout pooled indices:
  1m -Rs 36,278 (214), 5m -Rs 17,573 (42). Trades on the arms' days, near-zero correlation, loses on both kinds of day.
  Keep only as a structure / levels overlay; no signal, no paper arm.
- R90B (Boss, 20 Oct: 5 one-minute confirmation candles, profit lock, stop trail, profit trail; `PREREG_B.md`): 7 rules x
  3 charts x 9 instruments, 0 of 189 pass. Confirmation lifts the win rate (1m indices 37% -> 47%) and halves trades and
  losses, but after-trigger random entries do as well; lock + trails cut the 5m / 60m index loss from -Rs 1.19 lakh to
  -Rs 16,857 (design), never to a profit. Apr - Oct 2026 (already seen by R90): 5m / 60m indices positive with or without
  the additions (plain +Rs 19,679 on 92); commodities lose with every combination.

## Boss's ICT / Smart Money "liquidity sweep" of a 1h swing (R91, 20 Oct 2026) - 0 of 63 pass; it stays a note
Full study: `HUNT_R91_SMC_SWEEP.md` (pre-registered `hunt/r91_smc_sweep/PREREG.md`, commit d3020a2; notes N1). Signal
source: the underlying in every rule (1h fractal swing from an earlier session, a 1h high beyond it with the 1h close
back inside, a strong 5m candle within 30 minutes, sell-stop at its low / buy-stop at its high, stop at the sweep's
extreme, target the nearest 1h swing / previous day's level / 2R); the 1-ITM put / call bought on real prints, real
costs, flat 15:15 (15:05 expiry). 7 variants x 9 instruments, BH over 63, after-trigger twins. Design 20 Nov 2024 -
31 Mar 2026 (MCX from 5 Aug 2025), holdout 1 Apr - 19 Oct 2026 read once.
- Rare: 120-190 sweeps per index in 16 months, half find no strong 5m candle; 18-36 trades per index on the base rule
  (about 0.1 a day). Design pooled indices -Rs 40,340 on 135 (random after-trigger entries -Rs 42,134; underlying before
  costs -Rs 37,150), worst month -Rs 40,095; MCX lost on all 7 variants. Lowest q 0.38.
- The sweep fails (stop at its extreme hit) on 41% of index trades (51% MCX), only ~2% within 15 minutes; 18% of sweeps
  are retaken before any entry. Losses are slow, not whipsaws.
- Holdout (descriptive; no design passer): indices pooled +Rs 15,243 on 44 (base), BANKNIFTY / FINNIFTY / MIDCPNIFTY
  cells of 7-15 trades with twin p 0.002-0.03 but negative without their top 5; NIFTY and MCX lost. Not evidence; a
  fresh pre-registration on post-19 Oct data if Boss wants it watched.
- Mirror image of the Liquidity arm (which buys the break): on 74% of R91's BANKNIFTY days the Liquidity arm also trades,
  mostly on the other side; daily corr -0.00 (design) / -0.23 (holdout). Agrees with R86 (fading stop runs loses).

## Breadth Donchian on MCX commodities (R93, 21 Oct 2026) - 0 of 5 pass; no MCX arm
Full study: `HUNT_R93_BREADTH_DONCHIAN_MCX.md` (pre-registered `hunt/r93_breadth_donchian_mcx/PREREG.md`, commit 4e5ba0f;
notes N1-N4). Signal source: the MCX future itself (I-C also the international driver); 1-ITM GOLDM / SILVERM / CRUDEOILM /
NATGASMINI options on real prints, copper / zinc / aluminium as futures (options thin or none); daily rules as futures.
Breadth = 3 of 5 metals (GOLD, SILVER, COPPER, ZINC, ALUMINIUM; minis once) / 2 of 2 energy on the same bar; R72's exits.
- 5-minute (holdout 1 Apr - 19 Oct 2026, 142 sessions, read once): plain -Rs 30.06 lakh (3,365), breadth -Rs 17.93 lakh
  (1,957), cross-market (XAUUSD, XAGUSD, WTI, Henry Hub, COMEX copper) -Rs 22.57 lakh (2,485); every month below -Rs 13k;
  no better than random entries (q 0.39-0.58). Options alone: gold + silver -Rs 2.33 lakh (653, breadth), crude + gas
  -Rs 57,064 (594). Gross before costs is about zero (options -Rs 82 to +Rs 86 a trade vs Rs 100-520 of costs).
- Breadth sorts MCX breaks only a little: confirmed -Rs 805 a trade (640) vs unconfirmed -Rs 914 (2,725) in the holdout
  (indices, R72: +Rs 183 vs -Rs 54); confirmed gross higher in 7 of 7 commodities, by tens of rupees. Costs decide on MCX.
- Daily (2012-2022 design, 2023 - Oct 2026 holdout, futures): plain +Rs 7.26 lakh (367) but random entries made as much
  (twin p 0.51; -Rs 5.76 lakh without the top 5: the 2023-26 silver / copper rally); daily breadth -Rs 3.68 lakh (133).
- Sample: long enough for the intraday answer (post-hoc 95% upper bound of gross a trade below cost in every option book; closest
  gold with breadth Rs 265 vs Rs 364). Keep Breadth Donchian on the indices only.
## Boss's five "advanced commodity" ideas on MCX (R94, 21 Oct 2026) - 0 of 152 pass; nothing goes to paper
Full study: `HUNT_R94_MCX_ADVANCED.md` (pre-registered `hunt/r94_mcx_advanced/PREREG.md`, commit 8b6b326; notes N1-N3).
Real MCX costs on every leg, a hard stop on every trade, 1 lot, Boss's 2%-of-Rs-1-lakh check, one BH family over 152 cells,
twins, the holdout read once (64 in-scope cells). Signal sources: the futures legs / ratio / calendar / US CFD proxy.
- Calendar spreads (16 pairs of contracts live on 19 Oct, May - Oct 2026): the spread earns about Rs 0 a trade BEFORE
  costs (GOLDM +Rs 20 vs Rs 1,242 of costs on 505 trades); design -Rs 5.31 lakh (546), holdout -Rs 9.55 lakh (937).
- Sector index vs constituents: BULLDEX / METLDEX have no point-in-time history and ~0 lots a day (no ENRGDEX future).
  Proxies: GOLD/SILVER ratio reversion lost in both windows (W60 design -Rs 6.3 lakh, 68); base-metal pairs positive in
  design (q 1.00, fail the liquidity rule) and negative in the holdout.
- Option selling: out of scope (Boss, 21 Oct); design only, holdout not read, 0 of 88 at p <= 0.05; condors lose to
  costs, naked strangles carry -Rs 1.5 lakh single-gap tails.
- Bullion seasonals (2012-2026): no claim beats same-year random windows after BH (best: gold short 10 days after
  Diwali, +Rs 44,734 on 10, p 0.037, q 0.70). The 2022-26 holdout gains (wedding-month gold +Rs 5.7 lakh on 28) are the
  bull market: random windows of the same years made most of it (twin p 0.09-0.36).
- US overnight -> MCX open (Dukascopy proxies; a re-test of R2): the 09:00 gap prices the night (corr 0.71-0.87 gold,
  silver, crude); holdout pooled +Rs 7,467 on 320 trades. Gold / silver lots cannot fit a 2% stop at Rs 1 lakh.
## When does the ORB family make money? By entry time, range length, day type and exit time (R92, 21 Oct 2026) - never; 0 of 257 pass

HUNT_R92_ORB_TIME.md. Signal source: the index in every cell (R3's rebuild of ORB / ORB Fresh / ORB Sweep / Range
Fade, 1 entry a side a day); ATM option of the 09:20 bar on real prints, dated charges, R17 fills, 1 lot. 257
pre-registered cells, BH over all; design 1 Aug 2021 - 31 Mar 2026 (5 indices, 4,884 sessions), holdout 1 Apr - 19 Oct
2026 read once.
- Every entry hour loses for every arm (design Rs a trade): ORB -73 / -53 / -52 / -103 / -59 for 10-14 h (2,587-3,270
  trades each); Fresh -47 to -97; Sweep -115 to -200; Fade -117 to -154. All 125 slot cells (hour, 30 and 15 minutes)
  lose; range length 15-75 min, exit 13:30 / 14:30 / 15:10, weekday, days to expiry and month position all lose too.
  Smallest money p 0.13, every q 1.00; nested walk-forward -Rs 27,595 on 186 trades.
- R3's 12:00-12:59 ORB lead (+28 a trade) fails on the indices it never used (FINNIFTY + MIDCPNIFTY + SENSEX: -59 a
  trade on 260, p 0.83) and on its own indices' older years (BANKNIFTY + NIFTY to Mar 2026: -26 on 276); it was 35
  trades of Apr - Oct 2026 (+Rs 12,391).
- Least bad: the day's FIRST ORB signal at 13:30-13:59, +Rs 10,176 on 114 (holdout +Rs 5,475 on 19), but q 1.00,
  twin p 0.09, mostly FINNIFTY, negative without its top 5. No time filter goes to paper; the four arms stay paused.
- Why: 70% of ORB trades leave on the ladder near breakeven; the index is on the right side on 64-72% of trades
  (+3 to +16 points) yet the option loses Rs 33-110 a trade at every hour. The clock is not the problem.
## MCX commodity option BUYING hunt, round 1 (R96, 21 Oct 2026) - 0 of 9 pass; leads: NR7 breakout; holdout not read
Full study: `HUNT_R96_ROUND1.md` (pre-registered `hunt/r96_mcx_buy/PREREG_ROUND1.md`, commit c1d5f8e8; cumulative ledger
`hunt/r96_mcx_buy/LEDGER.md`, 9 ideas). Signal source: the MCX future; trade: buy 1-ITM / ATM straddle on CRUDEOILM,
NATGASMINI, GOLDM, SILVERM, hard stop + profit lock. Design 5 Aug 2025 - 14 Jul 2026; locked holdout 15 Jul - 19 Oct 2026
(not read: nothing passed).
- Event straddles (103), event follow (103) and US-session momentum (236) lose even before costs; IV-below-forecast
  straddles (15) lose: MCX options price events and vol like other markets (variance premium).
- Lead: NR7 breakout -> 1-ITM for 3 sessions, design +Rs 1.84 lakh on 58 (twin p 0.006, q 0.054), positive vs twins on
  2012-25 daily futures (+0.58% vs +0.32% a trade, 1,436); but it fails folds (2 of 4), the -13k month and the drawdown,
  and 7 model-priced deep-ITM silver exits carry the profit (real-print exits lose on every book). Round 2 refines it.
- Round 2 (R96, 21 Oct; `HUNT_R96_ROUND2.md`, prereg 38c1019a): 0 of 8 pass, holdout still unread, 17 ideas in the
  ledger. Sold only at real prints (no model marks), the NR7 breakout makes -Rs 4,067 on 65 (round 1's profit was the
  model-priced silver exits). Lead: NR7 on CRUDEOIL options +Rs 45,170 on 23 (with NATURALGAS +Rs 30,767 on 43, 3 of 4
  folds, twin p 0.076). Wider strikes (a fresh Dhan token) would settle NR7 on every book.
- Rounds 3-6 (R96, 21 Oct; `HUNT_R96_ROUND3..6.md`, summary `HUNT_R96_SUMMARY.md`): 46 ideas in all, 0 pass, holdout
  never read. Crude NR7 is the only lead: R3A +Rs 48,875 on 46 (twin p 0.014, q 0.161) and R4G on cheap-IV days
  +Rs 49,381 on 28 (14 signal days, p 0.002, q 0.046). Both fail the family gate (q 0.177). The fresh-book test failed:
  NR7 on COPPER lost -Rs 84,978 on 13, and ZINC options never filled (33 signals). Natural-gas storage days, the
  pre-event straddle, gold / silver ratio, first-half-hour momentum and the 20-day channel on copper all lose after
  costs. Next: the pre-registered forward read-out of crude NR7 (from 20 Oct 2026), wider MCX strikes, real bid / ask.

## The "Mo3ty CCI Signals" indicator (R95, 21 Oct 2026) - 0 of 288 for the indicator as given; 2 of 328 survive (FINNIFTY MO, thin)
Full study: `HUNT_R95_CCI_SIGNALS.md` (pre-registered `hunt/r95_cci_signals/PREREG.md` + amendment A1; notes N0-N5).
Signal source: the underlying in every rule (CCI 25 of hlc3 on index spot / MCX near future, 3m / 5m / 15m / 60m); the
1-ITM call / put bought on real prints, real costs, 1 lot; after-trigger twins, BH over 328 cells; design 20 Nov 2024 -
31 Mar 2026 (MCX from 5 Aug 2025), holdout 1 Apr - 19 Oct 2026 read once.
- What it measures: +200 = 3 mean deviations (about 2.4 SD, about 3 ATR14) above the 25-candle mean; MD / SD 0.77-0.79 in
  every cell. A steady trend holds +128, so the cross back inside marks an acceleration stopping, not a turn. Forward
  win rates after BUY / SELL signals (15 candles) are 38-54% against 48-52% for any candle: no direction edge.
- The indicator as given (B), pooled: underlying win 33-44% before costs (payoff about 1.8-2.7); option after costs
  28-47% win on indices, 11-39% on MCX (single cells). Indices 3m -Rs 7.01 lakh (4,072 trades) / holdout -Rs 2.01 lakh
  (1,618); 5m -Rs 4.73 lakh (2,766) / -Rs 1.40 lakh (1,122); 15m and 60m flip sign between periods; MCX loses on every
  timeframe. None of the 8 fade variants (2R, 10 candles, pulse, divergence-only, CCI 20 / 50, +-100) passes.
- Divergence labels repaint: entered at the label candle the index trades show +Rs 12.0 lakh (1,687, 60% win); entered
  when known, 5 candles later, -Rs 3.4 lakh (1,778, 43%). Holdout +Rs 4.75 lakh (674) vs -Rs 37,156 (691). Never judge it
  by the chart.
- MO (buy WITH the acceleration, exit back inside the level): FINNIFTY 5m (design 407 trades +Rs 59,212; holdout 158
  +Rs 23,387, p 0.002) and 15m (216 +Rs 1,13,428; holdout 76 +Rs 14,980, p 0.018) pass every pre-registered gate. Thin:
  both negative without their 5 best holdout trades, one instrument, q 0.12 / 0.40 over all 328 holdout cells. Paper
  candidates for Boss's decision; nothing switched on. BANKNIFTY 60m B / XT passed the design (48 trades) and failed the
  holdout (17 trades, -Rs 1,014 / +Rs 4,916, p 0.41 / 0.15).
- EX (the cross as an early exit): lowered the Liquidity arm (5m -Rs 70,223 on 171 changed trades) and Breadth Donchian
  (5m -Rs 1.39 lakh on 1,368) in the design; 0 of 4 pass. The arms keep their own exits.

## Liquidity 15+5 give-back protection (R100, 22 Oct 2026) - 0 of 15 pass; keep the own exits
Full study: `HUNT_R100_LIQUIDITY_GIVEBACK.md` (pre-registered `hunt/r100_liquidity_giveback/PREREG.md`; final 1 Apr - 13 Oct
2026 read once, already read by earlier studies, so weak). Boss, 22 Oct: a Liquidity 5m MIDCPNIFTY put went to about
+Rs 4,500 and was sold at -Rs 1,168 by the 20-minute time stop. Signal on the index in every rule; locks on the premium.
- Trades like it are rare: 9 of 881 design trades reached +20% and closed at a loss (-Rs 15,030); 5 of 357 in the final.
- Tested: breakeven once +20% / +15%, a step lock, keep half the gain from +20%, an index breakeven, time-tightening,
  keep half the profit from +Rs 3,000 a lot. All save the give-back trades and all but one lose more on cut winners.
- Nearest: breakeven once +20% (G1), about neutral (+Rs 3.3k design on 16 changed trades, -Rs 2.6k walk-forward,
  +Rs 1.3k final; twin p 0.41 / 0.51). Not passed; Boss's choice to switch on (not built).
- Boss's Rs 500 trail switch on the same trades: -Rs 1.79 lakh design, -Rs 45k final against the own exits. Leave it off.
- Amendments A1 / A2 (Boss, same day): keep half the best gain once +10% (G8) wins more often (48% vs 44%) but made
  Rs 1,05,055 against Rs 2,57,249 in design (881 trades; 145 changed); final +Rs 475 (p 0.76). Adding a 15% trailing
  stop (G9) or the trailing stop alone (G10) lost in both periods. 0 of 3 pass.
- Amendment A3 (Boss): Breadth Donchian's lock ladder on Liquidity (+5% once +25%, breakeven once +30%, +30% once +60%,
  +60% once +100%): Rs 2,43,190 against Rs 2,57,249 in design (23 changed), -Rs 16.8k walk-forward, +Rs 3.2k final
  (5 changed). Fail. It would not have armed on the 22 Oct trade (peak about +22%).
- Amendment A4 (Boss): breakeven after charges once +10% (G12): Rs 2,45,083 at a 38% win rate against Rs 2,68,404 at 42%
  on all 1,238 trades (design -Rs 17.6k on 70 changed; final -Rs 5.8k on 29; twin p 0.93 / 0.91). Fail. In the app only
  as Boss's own opt-in paper switch (off by default, labelled, kept out of the plain-rules record; never live).
- Amendment A5 (Boss: "keep the profit and make it not lose money"): two lots with one sold at +15% (G13), a spread sold
  2 strikes further out once +20% (G14), an early +20% take-profit in the first 15 minutes (G15). All 1,238 trades:
  Rs 1,64,131 / Rs 1,28,510 / Rs 1,76,449 against Rs 2,68,404; each fails every gate but the sample, in design already.
  They rescue the 9 design give-backs (-Rs 15,030 to between +Rs 1.8k and +Rs 20.6k) but give up Rs 0.9-1.7 lakh of
  winners. Why: the 36 design winners of Rs 5,000+ made Rs 3,38,862 and the other 845 trades lost Rs 81,613 together;
  any early profit-taking cuts those runners. Signal on the index in every rule.

## Hero-zero candles on all five indices (R98, 21 Oct 2026) - why they happen, and why 75% accuracy is not reachable
Full study: `HUNT_R98_HERO_ALL_INDEX.md` (code `hunt/r98/`, pre-registered `hunt/r98/PREREG.md`, commit `8f82415`).
Builds on R25 (branch `claude/r25-hero-deep`); adds FINNIFTY, MIDCPNIFTY, post-Nov-2024 monthlies and 13 Oct 2026.
- 911 expiries (NIFTY 273, BANKNIFTY 195, FINNIFTY 167, MIDCPNIFTY 100, SENSEX 176). On 38% some Rs 1-10 option
  closed 10x by 15:20, among a median 21 cheap options a day; 3 of them per 10x day, usually neighbouring strikes.
- Why: a hero needs the index to run a median 2.9 ATM straddles one way (the straddle is the market's own forecast);
  the index ends a median 0.70 straddles away. Moves >= 2.5 straddles: about 10% of cases; then a 10x is 47-88% likely.
- When: peaks are late (48% at 15:00-15:20); nothing in the morning flags the afternoon (base 7.7%; VIX, gap, range,
  weekday, index give 0-19% in small cells).
- 75%: no. Walk-forward models (AUC 0.64-0.69): best precision 29% (doubles), 9% (5x), 6% (10x); at 13:00 "will this
  afternoon give a 10x" AUC 0.40-0.44. Best win rate of 60 exits 56% (72% on the model's top decile with a +10% target,
  -Rs 1,119 a Rs 5,000 ticket); every exit loses. Held to 15:20 a cheap ticket loses -Rs 3,213 of Rs 5,000 on average.
- High accuracy lives on the zero side: 97% end below their price (85% near zero), but 1% end >= 10x and the worst 178x
  (13 Oct: selling the 22700 CE at 13:30 for Rs 5,000 = about -Rs 95,000). Selling was not tested.
- Exploratory lead only (not pre-registered): one top-scored ticket per index-day, held, +Rs 714 a ticket, but the 5
  best days are 213% of it and the 95% range spans 0. Paper-test forward if wanted; nothing goes in the app.
- Follow-ups (21 Oct, post hoc): removing the stop nearly doubles the loss (-Rs 3,213 vs -Rs 1,745 with -50%, -Rs 889
  with -20%; the -50% stop kills only 1-3.5% of later winners). A +30%-step profit lock (app ProfitLock fills) lifts
  the win rate 3% -> 19% and the loss to -Rs 2,139 (-Rs 594 with a -20% stop, the least bad tested, 0 of 6 years > 0),
  but cuts the 10x tickets from +Rs 43,742 to +Rs 4,977 (13 Oct 22650 CE: +Rs 84k held, +Rs 1.4k locked).
- R98b (pre-registered PREREG_B, design 2021-23 / holdout 2024-26): 26 new exits (free roll, late give-back trail,
  index trail, time stop) x every ticket / daily basket / confirmation entries: 0 of 4 pass on the holdout. Several
  exits keep all of 13 Oct's Rs 84k, but every exit loses overall: break-even needs 6-8% of tickets to be 10x
  heroes, the base is 2.2% and the best model ~5%. Real pattern: after a 1.5-straddle move from 13:00 the index
  reaches 2.5 straddles 30% of days (vs 6.9%), but R98c shows that is distance, not momentum (next line).
- R98c (PREREG_C, semi-clean holdout): buying ATM / ITM / OTM / Rs 5 options after a 1.5 / 2-straddle move with
  index exits: the design pick lost on the holdout (-Rs 613 a ticket); 4 of 48 rules positive in both halves (chance).
  After a 1.5-straddle move the index hits +1 before -0.5 straddle 34.5% of the time vs 33.3% for a random walk, and
  the index-only trade has no edge. Hero-zero buying is closed; only new information (event calendar, order flow) or a
  forward paper test could reopen it.
- R98d event days (PREREG_D; calendar events.csv with sources: RBI, Budget, election, Fed, US CPI): domestic
  events add nothing (Budget / election straddles are priced 3-5x; RBI basket lost on all 27 tickets). The session
  after a Fed decision or US CPI print: afternoon hero 14% vs 7%, 09:20 CE+PE basket +Rs 1,666 held / +Rs 3,354 with
  a -20% stop per Rs 5,000, but BH q 0.29, every CI includes 0, negative without the 3 best days (16 Jun 2022,
  12 Sep 2024). Best lead found; only a frozen forward paper test (10-20 days a year) can confirm it.
- R98e-g (pre-registered): 18 new features (gamma exposure / OI walls / strike demand / breadth / cross-index /
  events): GEX adds no predictive value; 8,280-variant search SPA p 0.96, RC 0.34, PBO 0.33, walk-forward 1/4.
  Order flow: no usable pre-move signal (AUC <= 0.57); 13 Oct = quiet -> ignition (volume + OI build) -> squeeze
  (call OI collapse). Ignition entries: SPA p 0.37; best +Rs 998/ticket both halves but -Rs 684 w/o 5 best days.
  Search closed; 4 near-misses frozen in hunt/r98/FORWARD_TEST.md for a forward paper test.
- FT3 in the app (Boss's choice, 21 Oct): re-frozen the app's way before any forward day (Black-76 IV with forward =
  spot, cut-offs 1.0333 / 1.5466, the LIMIT cap and 3-minute window, out at 15:05). Replay 2021-26 from the raw minutes
  (`hunt/r98/ft3_app.py`): 478 tickets, win 16.3%, +Rs 1,805 a ticket, +Rs 293 without the 5 best days, 4/6 years;
  filled a minute later +Rs 1,579 (+Rs 62). The same index-day as the research version on 470 of 474. Signal source:
  the clock and the option chain at the mark (the option's own close and volume: labelled). Its paper record is the test.

## GOLDM intraday option buying, "catch the 100-point moves" (R99, 21-22 Oct 2026) - no rule found
Full study: `HUNT_R99_GOLD_OBUY.md` (code `hunt/r99_gold_obuy/`, pre-registered `PREREG.md` 5396b7d7 and
`PREREG_ROUND2.md` ddcd3497). Signal source: the GOLDM future; the option is only bought (ATM / 1-ITM, 1 lot).
- Two studies carry the name R99 (22 Oct note): this GOLDM one (entries `r99-a-fade`, `r99-b-follow`, `r99-c-range`,
  `r99-2-*`) and the NIFTY "+100 on index options" one below (`HUNT_R99_NIFTY100.md`, entries `r99-a`, `r99-b`,
  `r99-c`, `r99-scan`). No entry id or file name collides, so neither was renamed.
- Gold does move 100+ points many times a day (median 68 legs of >= 100 a day), but a GOLDM option cannot be paid by
  one: on those trades (median hold 4 minutes) the option moved 0.33 x the future, lost ~14 points on the fill and ~37
  points of charges. Break-even needs roughly 150-200 future points in the trade's favour.
- Round 1 (104 rules: fade / follow the 100-300 point impulses, range breaks): SPA p 0.715, 0 past the cheap gates;
  fade and follow lost before costs. Best: the 09:00-10:00 range break, +Rs 40,034 on 124 trades, -Rs 3,847 at 2x.
- Round 2 (44 rules, at most one trade a day, held for hours: ORBs, the day's trend at 17:00 / 19:00, big impulses):
  0 past the cheap gates; rounds 1-2 together SPA p 0.982 (148 rules).
- Near-miss: at 17:00, if the move since 09:00 >= half the 20-session median daily range, buy the ATM option that way,
  stop at half the move, out at the square-off. Design 36 trades, 47% win, +Rs 20,741 (+Rs 12,154 at 2x, 4/4 folds);
  holdout (read once, description only) 15 trades, 53%, +Rs 22,889; but negative without the 3 best trades in both and
  no better than random-timed twins on the holdout (p 0.87). Forward paper test only if Boss asks; nothing in the app.

## Catching the +100 premium move on index options (R99, NIFTY, 21 Oct 2026) - 0 of 3 pass; one lead, a note only
Full study: `HUNT_R99_NIFTY100.md` (code `hunt/r99_nifty100/`, pre-registered `PREREG.md` 543fc366). Not the GOLDM
R99 above. Signal source: the NIFTY index (and Solo 2.0's own index entry); the ATM option is only bought.
- Buying the ATM option at a random moment for +100 (stop -40) loses Rs 161 a trade (42,334 moments, 336 sessions):
  +100 comes 9.2% of the time, the stop 29%, and 62% decay into the square-off. Charges are only Rs 60-75 a lot.
- No single index condition fixes it (0 of 218 significant); a model on all of them lost (R99A: 238 trades,
  -Rs 43,085). A fixed +100 / -40 on Solo 2.0's trades made it worse (R99B: 166 trades, -Rs 12,284).
- Lead (R99C): on Solo 2.0's 166 design trades, letting winners run made +Rs 89,242, +Rs 33,333 more than Solo's own
  exits, but all of the gain came in Jan-Mar 2026 and bootstrap p 0.245. It failed the gates, so it stays a note;
  the final period was not opened.

## Deep learn NIFTY and BANKNIFTY (R102, 22 Oct 2026) - how they move, with numbers; 1 of 70 sign rules confirmed and it is an artefact
Full study: `DEEP_NIFTY_BANKNIFTY.md` (pre-registered `hunt/r102_deep/PREREG.md`, design to 14 Jul 2026, holdout 15 Jul -
19 Oct 2026 read once). Facts for Jarvis with sample sizes: `knowledge/index_facts.yaml` (27 cards). Descriptive, not a
trading rule, unless marked. Signal on the underlying throughout.
- BANKNIFTY is NIFTY times 1.16: daily correlation 0.877, beta 1.157 (5,086 days 2006-2026); 0.842 / 1.094 on 5-minute bars
  (89,575). The big five banks are 62.98% of BANKNIFTY (14 members, capped 19 / 14 / 10% since Dec 2025) and 29.52% of NIFTY 50.
- Daily sd NIFTY 1.316% over 2006-2026 (5,087 days), 0.744% in 2025, 1.051% in 2026 to July; BANKNIFTY 1.734% / 0.789% / 1.356%.
  The drift is overnight: close-to-close mean +0.051% a day, open-to-close -0.035% (NIFTY); +0.065% / -0.039% (BANKNIFTY).
- The day is front-loaded: median day range 0.907% (NIFTY, 2,288 minute days) / 1.131% (BANKNIFTY, 1,185); 61% / 65% of it made by
  10:30, 82% / 87% by 13:00; the 09:15 minute alone spans 0.213% / 0.295%; 09:15-09:30 moves 2.32 / 3.09 bp a minute against
  0.29 / 0.38 at 10:30-13:00. The open is the day's high or low on 41% / 46% of days. Weekday and expiry day change none of it.
- Gaps >= 0.5% on 20% of NIFTY days and 34% of BANKNIFTY days (5,086); a 0.5-1% gap fills by the close 42% / 53%; a 2%-plus
  NIFTY gap closes on its own side 94-96% of the time. Filled gaps fill early: median 52 / 32 minutes after the open.
- VIX is the range dial: low VIX (9-15) median day range 0.75% / 1.01%, high VIX (above 20) 1.72% / 2.37% (1,509 days each).
  VIX over-states NIFTY's next 21 days on 81% of days (median ratio 1.28, 4,507 days) and under-states BANKNIFTY's on 60%.
- The 09:20 ATM straddle is a median 0.96% of spot on NIFTY (1,222 days) and 1.49% on BANKNIFTY (2.78% in 2026 as a monthly);
  the close beats it on 7.1% / 6.5% of non-expiry days and 40.5% / 37.8% of expiry days. Expiry decay clock: 84% of the 09:20
  straddle left at 10:30, 61% at 13:00, 40% at 14:30, 22% at 15:00, 13% at 15:15 (NIFTY, 259 expiries; BANKNIFTY the same).
  Put skew +2.56 / +2.11 IV points on 91% / 90% of days; PCR 0.86; the expiry close lands 0.60% / 0.68% from max pain.
- Participants: FIIs net short index futures by 61% (2025) and 72% (2026) of their book, clients net long 30% / 46%; clients hold
  58% of index-option longs, prop desks write 23-24% of the shorts (3,577 days).
- Predictive (70 pre-registered sign rules, BH over all): 8 passed design, 1 confirmed, and that one (1-minute "momentum",
  +0.11 bps, 853,718 pairs) is the cash index's staleness, below cost. The 2006-2026 daily momentum (+5.8 / +12.4 bps a day)
  reversed in the holdout (-9.4 / -24.6 bps, 65 days); the client-futures-change rule (-5.2 / -8.2 bps in design, q 0.000) had
  a CI through zero on the holdout (63 days); weekday, expiry, US events and gap fades never passed. No simple sign rule exists.
- Public facts verified 22 Oct 2026: lots NIFTY 65 / BANKNIFTY 30 since the Jan 2026 expiries (NSE/FAOP/70616; the repo's lot
  table still says 35), NIFTY weekly on Tuesday since 2 Sep 2025, BANKNIFTY monthly only since 20 Nov 2024, hours 09:15-15:40
  since 3 Aug 2026, STT 0.15% of the sell premium since 1 Apr 2026, one round trip about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY).

## Defined-risk option selling on NIFTY and BANKNIFTY (R103, 22 Oct 2026) - 0 of 24 confirmed; the 6 design passes were stale wing prices
Full study: `HUNT_R103_OPTIONS_SELLING.md` (pre-registered `hunt/r103_sell/PREREG.md`, two dated amendments; design to 14 Jul
2026, holdout 15 Jul - 19 Oct 2026 read once). Plain-English guide: `DEEP_OPTIONS_SELLING.md`. Signal source: none
directional; structures centred on the index's 09:20 ATM; exits on the premium.
- 24 tests (NIFTY / BANKNIFTY x expiry / other days x iron fly / wide fly / condor x stop / stop+lock+target), 1 lot, 09:21
  fills, dated charges (8 orders, Rs 228-256 a trade), liq slippage. Design: 6 BANKNIFTY non-expiry tests passed every gate
  (+Rs 1.9 to +Rs 5.4 lakh, 942-998 trades, 62-70% win, q 0.000); every NIFTY and every expiry-day test failed.
- The passes are an artefact: the lake keeps only ATM +-10 strikes, so a far wing the index moves away from stops printing
  and is marked at a stale (higher) close. 43-63% of those trades had such a leg at exit. Repriced (Black-Scholes at the
  leg's last IV; the model runs 1-5 points high on legs that did print), all six lose: -Rs 2.9 to -Rs 3.3 lakh in design,
  -Rs 18.6k to -Rs 21.0k in the holdout (64 trades). As pre-registered the holdout also rejected them (win 81-86% vs 62-70%).
- Before costs the hedged structures roughly break even; costs make them losers. The BUY mirror loses on all 24.
- A 50%-of-credit stop sat beyond the iron fly's max loss on most normal days (fired 6 of 998): stops on hedged structures
  belong on the max loss.
- Descriptive (whole lake, stale legs repriced): the naked 09:20 short straddle held to 15:10 made +Rs 64k NIFTY (1,272 days,
  62% win) and +Rs 1.51 lakh BANKNIFTY (1,234 days, 65%), worst day -Rs 28.9k / -Rs 29.3k on one lot (4 Jun 2024
  BANKNIFTY -6.46% after 09:20); the same days with 1-straddle wings lost -Rs 3.0 / -Rs 3.8 lakh.
- Warning for other studies: any rule that holds a strike more than about 6 steps from ATM on the rolling lake can be
  marked at stale prices when the index moves away. Check the share of exit legs without a print before trusting it.

## The brief's own selling list, naked and hedged (R103 deep, 22 Oct 2026) - 3 of 480 passed design, none confirmed
Full study: `HUNT_R103_DEEP_SELLING.md` (pre-registered `hunt/r103_selling/PREREG.md`, two dated amendments; Design A 20 Nov
2024 - 14 Jul 2026, holdout 15 Jul - 19 Oct 2026 read once). Summary in `DEEP_OPTIONS_SELLING.md` section 10. A separate
study and BH family from the one above. Signal source: none (time-of-day entry at the index's ATM strike).
- 480 cells: NIFTY / BANKNIFTY x naked straddle / naked strangle / iron condor / iron fly x 09:20 / 09:45 / 10:15 x five
  stops x two targets x all / expiry days; 1 lot, dated charges, 0.16% half-spread (h24) growing away from ATM, doubled on
  stops; event days blacked out. 219 net > 0, 8 of them with q <= 0.10, 3 pass every gate (NIFTY expiry-day strangles at
  09:45 / 10:15). Holdout, 14 expiry days each: -Rs 3,987, +Rs 694, +Rs 1,293 (p 0.53 / 0.85 / 0.83): not confirmed.
- Naked 09:20 straddle, no stop, 394 days: NIFTY +Rs 71.5k (63% win, worst day -Rs 30.7k, 3 months < -Rs 13k, p 0.43),
  BANKNIFTY +Rs 77.1k (61%, p 0.20). The same NIFTY trade lost -Rs 60.3k over Aug 2021 - Nov 2024 (786 days). Positive
  in most cells, significant in none, and not stable across regimes.
- All 120 hedged all-days cells lose, in both periods; all 32 buy mirrors at 09:20 lose. A 2x per-leg stop cuts the
  naked straddle's profit by more than half; a combined stop at 100% of the credit keeps it and cuts the worst day.
- The fixed 09:20 strikes keep a median 83% of their value at 15:05 on NIFTY expiry days (n 87; BANKNIFTY 49%, n 20),
  although the rolling ATM straddle shrinks 87%: the move adds to one leg what time takes from both.
- On the 12 blacked-out RBI / budget days of Design A the naked straddle made money (IV crush); too few days to use.
- The same stale-strike trap as above: a leg outside the lake's window was first valued at its entry price, which
  invented hedged profits. The final model scales the leg's last IV by a printed neighbour's IV change (calibrated 0.98-1.00).
