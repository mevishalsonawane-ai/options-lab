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
