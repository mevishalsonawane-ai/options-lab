# R131 pre-registration: ten ICT / SMC video strategies, each tested as stated

Written 29 Oct 2026, before any swing, setup, trade or statistic was computed on the data. Research only: nothing in
the app changes, nothing trades.

Boss sent ten strategies from an ICT / SMC video (29 Oct):

1. **Silver Bullet:** 15-minute chart. Mark the high and low, wait for a liquidity sweep, enter after a Change of
   Character (CHoCH) confirms the reversal.
2. **Cameron's Model:** the daily high and low. A fake move (a sweep) in the trend's direction, then a reversal signal
   such as an FVG; enter the opposite way.
3. **CISD (Change in State of Delivery):** in a downtrend, a rapid drop, then a close above the high of the last green
   candle: a bullish reversal (mirror for bearish).
4. **CRT (Candle Range Theory):** a 4-hour or daily candle's high and low. On 15 minutes, a close outside that range,
   then a reversal; enter on a CHoCH.
5. **AMD (Accumulation, Manipulation, Distribution):** a consolidation, a false breakout that sweeps liquidity, then a
   strong move the other way, confirmed by a CISD.
6. **IFVG (Inverse FVG):** an FVG that is invalidated (a close through it) then acts as support or resistance; enter
   the new way on the retest.
7. **Turtle Soup:** a recent high or low on any timeframe; a sweep beyond it, then a reversal confirmed by a CISD.
8. **Order Block:** the last opposite candle before a strong impulsive move; expect a reversal when price returns to it.
9. **Breaker Block:** a failed order block (price closes through it) becomes a reversal level.
10. **OTE (Optimal Trade Entry):** the 0.618-0.786 Fibonacci retracement of a swing, as the entry within a trend.

## 0. What is new here (earlier work)

| study | what it tested | result | what R131 adds |
|---|---|---|---|
| R62 | volume profile + SMC: POC / order-block / LVN pullbacks, FVG as a filter, 1-hour BOS / ChoCH as a trend filter | 0 of 32 | the order block as its **own** entry (not a POC filter); FVGs that are **invalidated and retested** (IFVG) |
| R62b | EmreKb's "Market Structure Break & Order Block" indicator (zigzag 9, fib 0.273, MSB + OB zones) | 0 of 16 | a fixed-pivot order block with a **displacement** rule; breakers (failed OBs) were never traded |
| R115 table (R90, R91, R61B, H25, R1 N7, R52) | failed-poke fades, CHoCH / BOS (R90), SMC sweep + strong 5-min candle (R91), traps / turtle soup (R1 N7, R52 re-test) | all failed | **CISD** as the confirmation (never tested), the CHoCH after a sweep on 15 minutes, the AMD box, CRT on the daily / 4-hour candle, OTE fib entries |
| R61b | trap / fake-breakout flags as filters on Solo | no help | here they are stand-alone entries with their own stops and targets |
| R124 / R125 | wick-based zigzag swings and sweeps inside a network | coin flip on direction | R131 uses the same wick-based swings, as rules not features |
| R127 | the CPR video (template for this write-up) | 0 of 3 | different rules |
| **R130** (sibling, 29 Oct, `claude/r130-sweep-mss-fvg`) | sweep → MSS → FVG pullback → engulfing | design: every variant loses | R131 **reuses R130's swing, sweep, MSS (= CHoCH) and FVG definitions** and its option / cost / twin code; it does not re-test R130's FVG-pullback-engulfing setup |
| Liquidity arm (engine `LiquidityRules`, paper) | pivot 20, pool + swing zone, a **close** beyond the level, trades the break | on paper | R131's sweeps are **wick** pokes that are faded (the opposite of the arm's break trade) |

**Already tested in some form (so R131 is a re-test as the video states it):** the sweep-and-reverse idea (Turtle Soup,
R1 N7 / R52 / R91), CHoCH / BOS (R90, R62), order blocks (R62, R62b).
**Never tested before:** CISD, the AMD box, CRT on the previous daily / first 4-hour candle, IFVG, breaker blocks, OTE,
the Silver Bullet (15-minute and the Indian one-hour window), Cameron's daily-level sweep + FVG.

## 1. Common definitions (every strategy uses these unless its section says otherwise)

**Signal source: the index spot (the underlying), always.** Every level, sweep, CHoCH, FVG, CISD, block, fib level, stop
and target is read on the index's minute bars. The option is only what is bought. No option-chain input is used.

**Bars.** tf-minute bars aligned to 09:15 in each session (R130's `make_bars`): open = the first minute's open,
high / low = the highest high / lowest low, close = the last minute's close. A bar is used only after its last minute
has closed. Bars run across sessions in order. The chart is **5 minutes** unless a strategy names another.

**Green / red.** A bar is green when close > open, red when close < open (a doji is neither). Opens and closes are used
only for: green / red, CHoCH / CISD / invalidation closes, and the daily trend (Cameron). Every level is a wick.

**ATR.** ATR(t) = the mean high - low of the 14 completed bars before bar t, on the same chart (across sessions).

**Swing points (R130).** Bar i is a swing high when its high is strictly above the highs of the k = 3 bars before it
and at or above the highs of the k bars after it; swing lows mirror on lows. A swing is known only from the bar after
bar i + k has closed (no look-ahead). Only swings from today and the previous 4 sessions are used.

**The recent high / low at bar t (R130).** The recent low is the most recently confirmed swing low that no low has
gone below (by one tick, 0.05) since it formed. The recent high is the most recently confirmed swing high.

**Sweep (R130).** A bar's low at least one tick below the level (low ≤ level - 0.05). Bearish: a high ≥ level + 0.05.

**CHoCH (= R130's MSS).** After a sweep of the recent low at bar s, the first bar m within 12 bars after s (same
session) that **closes** above the recent high known at s. A newer sweep before the CHoCH replaces the older one.

**FVG (R130's three-bar gap).** Bullish: bars j-1, j, j+1 with low[j+1] - high[j-1] ≥ max(2 ticks, 0.01% of price);
zone [high[j-1], low[j+1]]. Bearish: high[j+1] < low[j-1] by the same size; zone [high[j+1], low[j-1]]. Known when bar
j+1 closes.

**CISD.** Two readings, both pre-registered:
- *As stated (Boss's text):* a bullish CISD is a close above the **high of the last green candle** before the drop.
- *ICT's reading:* a close above the **open of the first candle** of the run of consecutive red candles that made the
  drop (the "delivery" open).

The **drop run** for a CISD: the maximal run of consecutive red bars (same session) ending at the last red bar at or
before the lowest-low bar of the move; the "last green candle" is the most recent green bar before that run, at most 5
bars before it (none: no CISD). Bearish mirrors (a rally of green bars; a close below the low of the last red candle,
or below the open of the run's first green bar).

**Strong impulsive move (displacement).** At bar t, take the consecutive green bars ending at t (at most the last 3).
It is an impulse when close[t] - open[first of them] ≥ 2.0 × ATR(first). Bearish mirrors.

**Order block.** For a bullish impulse, the last red bar among the 3 bars before the impulse's first bar (same
session); none → no order block. Zone = that bar's full range [low, high] (wicks). Active from the close of the
impulse's last bar, for 36 bars (same session). One zone per order-block bar.

**Breaker.** A bullish order block that a later bar (within its 36 active bars) **closes below** the zone's low
becomes a bearish breaker over the same zone [low, high]. Bearish order blocks mirror into bullish breakers.

**Consolidation (AMD box).** The 12 bars b-11 .. b (one hour on 5 minutes, same session) whose high - low range is
≤ 2.5 × ATR(b-11) (the ATR of the 14 bars before the box).

**Fibonacci (OTE).** For a leg from swing low SL to swing high SH: level(f) = SH - f × (SH - SL); the OTE zone is
f = 0.618 .. 0.786 (0.705 is ICT's "sweet spot").

**Daily levels (Cameron, CRT, Turtle Soup V2).** The previous session's highest high (PDH), lowest low (PDL) and last
minute's close, from completed sessions only. The 4-hour candle (CRT V2) is today's 09:15-13:14 (NSE's first 4-hour
candle), used only from 13:15, after it has closed.

**Default stop (where the video gives none):** 0.02% beyond the extreme of the sweep or zone (the far side), as R130.
**Default target (where the video gives none):**
- *S (structural, "the next opposite liquidity"):* the nearest swing high above the entry price that is confirmed by
  the entry minute and has not been traded above since it formed (today and the previous 4 sessions). No such swing →
  no trade (counted).
- *2R:* entry ± 2 × (entry - stop), the entry price = the index open in the entry minute.

**Entry.** At the open of the minute after the trigger (a bar's close, or a 1-minute touch). The entry minute must be
09:15-15:00. If the index open of the entry minute is already beyond the stop, there is no trade (counted). For a
1-minute touch trigger, if the touch minute's own low (bullish) reaches the stop, the setup is dropped.

**Exits (index wicks, minute by minute from the entry minute):** stop when a low reaches the stop; target when a high
reaches the target; both in one minute → the stop; otherwise out at 15:20. The option bought never expires that day
(below), so the 15:05 expiry-day square-off never applies to it.

**One trade at a time per index per variant.** Setups are found independently; candidate entries are taken in time
order, and a candidate whose entry minute falls while a trade is open is skipped (counted). If a bullish and a
bearish candidate enter in the same minute, neither is taken. Bullish and bearish setups run side by side.

**The option (R130's rule, the app's: the nearest expiry that does not expire today).** ATM (half-up rounding) from
the index's last close before the entry minute; CE for bullish, PE for bearish; 1 lot at today's lots (NIFTY 65,
BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20). The lake's nearest-expiry series (`opt_<UND>_WEEK`); on the
index's own expiry day the monthly series if it does not expire that day, else no trade that day. Lake cleaning
(lakeclean steps 1-3, stale=True). Bought at the entry minute's open, sold at the last traded close at or before the
exit minute. Liquidity-aware slippage (Fills "liq"), also at 2x; dated charges.

**Index-points view:** the same trades in index points and R multiples, no option, no costs.

## 2. The ten strategies and their 30 variants (one BH family)

All bullish rules are given; bearish rules are the exact mirror (built by running the bullish code on the mirrored
index, high → -low, low → -high, open → -open, close → -close, as R130).

**S1 Silver Bullet** (as stated: 15-minute chart).
- Setup: a sweep of the recent low, then a CHoCH (close above the recent high) within 12 bars. Trigger: the CHoCH
  bar's close. Stop: 0.02% below the lowest low between the sweep and the CHoCH. Target: S.
- **S1-V1** 15-minute, all day, target S. **S1-V2** the Indian window: 5-minute chart, the sweep bar and the CHoCH bar
  both inside 09:45-10:45 (the hour that starts 30 minutes after the open, as ICT's 10-11 New York window does after
  its 09:30 open), target S. **S1-V3** = V1 with the 2R target.

**S2 Cameron's Model** (5-minute chart, daily levels).
- Trend: down when yesterday's close < the close 5 sessions earlier (up when >; equal: no trade).
- Setup (bullish, in a downtrend): a fresh poke of PDL (a bar's low ≤ PDL - 0.05 while the previous bar's low was
  above that, or the day's first bar). Within 12 bars of it (same session), the first bullish FVG whose first bar is
  at or after the poke and whose third bar closes above PDL. Trigger: that third bar's close. Stop: 0.02% below the
  lowest low since the poke. A newer poke replaces the setup.
- **S2-V1** target S. **S2-V2** 2R. **S2-V3** = V1 without the trend filter (both sides every day).

**S3 CISD** (5-minute chart).
- Setup: a run of ≥ 2 consecutive red bars whose drop, from the run's first open to its lowest low, is ≥ 2.0 × ATR
  (the "rapid drop"); the run ends when a bar is not red. Trigger: the first bar within 12 bars after the run's last
  bar (same session) that closes above the CISD level. Stop: 0.02% below the lowest low from the run's start to the
  trigger. A newer qualifying run replaces the setup.
- **S3-V1** as stated: level = the high of the last green candle before the run; target S. **S3-V2** ICT's: level =
  the open of the run's first bar; target S. **S3-V3** = V1 with 2R.

**S4 CRT** (15-minute chart).
- Setup (bullish): a fresh outside close below the range low L (close < L, while the previous bar closed ≥ L or it is
  the first bar after the range is known). Trigger: within 12 bars (same session), the first bar that closes above
  the recent high known at the outside close **and** above L (back inside). Stop: 0.02% below the lowest low since the
  outside close. A newer outside close replaces the setup.
- **S4-V1** the previous day's candle (PDH / PDL); target S. **S4-V2** today's first 4-hour candle (09:15-13:14),
  setups from 13:15; target S. **S4-V3** = V1 with 2R.

**S5 AMD** (5-minute chart).
- Setup (bullish): an AMD box ending at bar b; within 6 bars after b, the first bar whose low ≤ box low - 0.05 (the
  false breakout), with no high above the box high before it. Then, within 12 bars of that bar (same session), the
  CISD: the first close above the level from the drop run of the move's lowest low (a new low re-anchors it). Stop:
  0.02% below the lowest low since the false breakout. The box used is the latest qualifying one.
- **S5-V1** ICT's CISD level (the run's first open); target S. **S5-V2** = V1 with 2R. **S5-V3** the CISD as stated
  (the high of the last green candle); target S.

**S6 IFVG** (5-minute chart).
- Setup (bullish): a bearish FVG (zone [bottom, top]) that a bar closes **above** its top within 24 bars of forming
  (same session); it is now support. The retest window is the 12 bars after the invalidation bar.
- **S6-V1** trigger: the first 1-minute low ≤ the zone's top; stop 0.02% below the zone's bottom; target S.
  **S6-V2** trigger: after that touch, the first 5-minute bar that closes back above the top (no low at the stop
  before it); same stop; target S. **S6-V3** = V1 with 2R.

**S7 Turtle Soup** (5-minute chart).
- Setup (bullish): a sweep of the level; then, within 12 bars (same session), the CISD (ICT's level from the drop run of
  the lowest low since the sweep; a new low re-anchors it). Stop: 0.02% below the lowest low since the sweep. A newer
  sweep replaces the setup.
- **S7-V1** level = the recent low (5-minute swings); target S. **S7-V2** level = PDL ("any timeframe": the daily),
  the fresh poke of S2; target S. **S7-V3** = V1 with 2R.

**S8 Order Block** (5-minute chart).
- Setup: a bullish order block (above). Trigger: the first 1-minute low ≤ the zone's high while it is active. Stop:
  0.02% below the zone's low.
- **S8-V1** target S. **S8-V2** 2R. **S8-V3** = V1, and the impulse must also close above the recent high known at its
  last bar (a break of structure, the usual SMC condition).

**S9 Breaker Block** (5-minute chart).
- Setup (bullish): a bearish order block that a bar closes above (its high) while active becomes a bullish breaker.
  The retest window is the 24 bars after that close (same session).
- **S9-V1** trigger: the first 1-minute low ≤ the zone's high; stop 0.02% below the zone's low; target S.
  **S9-V2** = V1 with 2R. **S9-V3** trigger: after that touch, the first 5-minute bar that closes back above the zone's
  high (no low at the stop before it); target S.

**S10 OTE** (5-minute chart).
- Uptrend: the latest confirmed swing high SH (pivot today) is above the swing high before it, and the latest swing
  low SL before SH's pivot is above the swing low before it (higher high, higher low). Leg = SL → SH, and SH - SL ≥
  2.0 × ATR. Watch the 24 bars after SH is known (same session); a high above SH first kills the setup.
- **S10-V1** trigger: the first 1-minute low ≤ level(0.618); stop 0.02% below SL; target S. **S10-V2** trigger at
  level(0.705); target S. **S10-V3** = V1 with 2R.

## 3. Data, periods and checks

- **Data:** the Drive lake (rclone remote `gdrive`, from env vars), copied 29 Oct 2026: `index_1m` for NIFTY,
  BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; `options_rolling_1m` WEEK and MONTH files. Aug 2021 (MIDCPNIFTY / SENSEX
  later) to 19 Oct 2026. Nothing raw goes to git; the trade lists go to Drive (`derived/r131/`).
- **Periods (R123's, as R130):** design 2021-08-01 .. 2026-03-15, walk-forward folds F1 2023, F2 2024, F3 2025, F4
  2026-01-01 .. 03-15; embargo 2026-03-16 .. 03-31 (not used); **holdout 2026-04-01 .. 2026-10-19, locked: read ONCE**
  for all 30 variants, after the design results are committed, with a flag file that refuses a second read.
- **No fitting.** Every setting above is fixed here, so each fold is out of sample by construction; the walk-forward
  is the per-fold result of the fixed rule.
- **Random-entry twins (200 per trade, R130):** the same index, day and side; a random entry minute 09:20-15:00; the
  same stop and target distances in index points; the same exits and option rule. p = (1 + twin totals ≥ the rule's
  total) / 201 (a twin with no trade is resampled from that trade's valid twins).
- **BH across all 30 variants of the ten strategies together** (design p vs twins).
- **Gates (R130's).** A variant passes only if all hold:
  - design: ≥ 300 trades; average net > 0 at 1x and 2x slippage; positive in ≥ 3 of the 4 folds; BH q ≤ 0.10 over
    the 30 variants;
  - holdout (read once): ≥ 50 trades; average net > 0; **win rate ≥ 65% (Boss's 65% rule, 28 Oct)**;
  - account (design + holdout, 1 lot): max drawdown above -Rs 40,000 and no calendar month below -Rs 13,000.
- **Reported for every variant:** trades, win rate (vs 65%), average win and loss, net per trade, total on 1 lot, max
  drawdown, years positive (2022-2026, design + holdout), p vs twins, q; the index-points view; per index and per year
  for the best few.
- **If one passes,** a paper arm is proposed (not built). Otherwise the write-up says plainly it fails.

## 4. Outputs

- Code and tests: `research/hunt/r131_ict_ten/` (`r131.py`, `test_r131.py`, with a look-ahead guard: every strategy's
  candidates found on full data are found identically when every minute after the entry minute is removed).
- Results: `results/design.json`, then `results/holdout.json` (the one read).
- Write-up: `research/HUNT_R131_ICT_TEN.md`; entries `research/experiments/entries/r131-*.yaml`; RESULT.md.

## N1 (29 Oct 2026, after the code, BEFORE any run on the data): choices the text above leaves open, fixed in `r131.py`

1. **The drop run of a CISD (S5, S7):** the run ends at the last red bar at most 3 bars at or before the move's
   lowest-low bar (the first such bar when lows tie). No red bar there: no CISD yet (the setup keeps waiting).
2. **The sweep / false-break bar itself can confirm (S5, S7):** the CISD close may come on the sweep bar (a hammer that
   closes above the level); S1, S2, S4 and S3 need a later bar (as R130's MSS).
3. **OTE (S10):** skipped when the bars between SH's pivot and its confirmation had already gone below the 0.786 level
   (the retracement passed the zone before it could be known). A high above SH in the touch minute itself also kills
   the setup (the order of the two is unknown inside a minute: the worse reading).
4. **A 1-minute touch (S6, S8, S9, S10):** the touch window starts at the minute after the bar that makes the zone
   known, and ends with the last bar of the window; entry at the next minute's open.
5. **The structural target** counts a swing high as taken once a later high is one tick or more above it.
6. **One trade at a time:** a setup that began during an open trade may still enter after that trade's exit (setups
   are found independently; only the entry minute is checked against the open trade).
7. **AMD (S5):** a bar that breaks both sides of the box counts as the false break below for buys (and above for
   sells); both candidates then meet in the sequencer.
8. **Silver Bullet V2 window:** a 5-minute bar is inside 09:45-10:45 when it starts at or after 09:45 and ends by
   10:44; the entry can be 10:45 at the latest.

## N2 (29 Oct 2026, after the design run, BEFORE the holdout read): a supplementary twin check (not a gate)

The design run showed that the pre-registered twins (a random entry 09:20-15:00 on the same day and side, as R127 and
R130) can enter **before** the rule's signal. For a reversal setup the side is chosen by a move that has already
happened, so a twin entering earlier can ride the very move that made the signal (S1-V1: twins' mean total
+Rs 5.87 lakh against the rule's -Rs 1.53 lakh on 920 trades). The gates are NOT changed: the pre-registered twin p and
BH q stay the gate. In addition, every trade gets 200 **after-signal twins** (a random entry minute from the rule's own
entry minute to 15:00, a separate random stream, the same distances, exits and option rule), reported as
`p_twins_after` with its own BH q over the 30 variants, in the design (re-run, the primary numbers are unchanged) and
in the one holdout read. It is descriptive only.

## N3 (29 Oct 2026, after the holdout read): the 65% rule does not apply here

Boss, 29 Oct: "65% is only for new strategies created by Jarvis". These ten are Boss's own video strategies, so the
holdout win-rate gate (≥ 65%) is dropped for R131; win rates are still reported. Every other gate stands. No verdict
changes: every variant already fails the design gates (trades, net at 1x / 2x slippage, folds or BH q).

## N4 (29 Oct 2026, after the holdout read, BEFORE any wick-only code or number): wick-only variants (Boss)

Boss, 29 Oct: "use wick to wick candles, not only opening and closing candles". Every level, sweep, gap, zone, stop,
target and exit above is already a wick. What still used a close (or an open) is replaced here by a wick, in one new
variant per strategy, built on its as-stated V1 (S10 OTE uses no close at all, so it has no W variant):

| W variant | what changes from V1 |
|---|---|
| S1-W | the CHoCH is the first **minute high ≥ the recent high + 1 tick** (not a 15-min close above it) |
| S2-W | the FVG's third bar must have its **low** above PDL (not its close) |
| S3-W | the drop is measured from the run's first **high** to its lowest low; the CISD is the first minute high ≥ the last green high + 1 tick. The run is armed after each completed red bar (so the trigger never needs the colour of the bar it fires in) |
| S4-W | the "outside close" is a fresh **wick** below the previous day's low; the CHoCH is a minute high ≥ the recent 15-min high + 1 tick |
| S5-W | the CISD is the first minute high ≥ ICT's level + 1 tick (the level from completed bars only) |
| S6-W | the FVG is invalidated by a **high ≥ its top + 1 tick** (not a close above it) |
| S7-W | the CISD is the first minute high ≥ ICT's level + 1 tick (the level from completed bars only) |
| S8-W | the impulse is measured wick to wick: the run's last high minus its first low ≥ 2 ATR |
| S9-W | the order block fails on a **high ≥ its high + 1 tick** (bearish: a low below its low), not a close |

- Candle colour (green / red) is kept where the concept itself is a colour ("the last green candle", "the last
  opposite candle", a run of red candles); it is never used to time an entry.
- A minute trigger: entry at the next minute's open; the stop is the lowest low from the sweep / run start up to and
  including the trigger minute (minus 0.02%). The sweep bar itself cannot confirm (the order inside a bar is unknown).
- **Gates:** the design gates of section 3 (no 65% rule, N3), with BH q over all **39** variants (the 30 plus these
  9). The holdout period was already read once for V1-V3, so it is **not unseen** to this study: the W variants get
  one read of it (their own lock), reported with that label. A W variant that passes design and that read would still
  need a fresh forward test on paper before any arm, never live.
