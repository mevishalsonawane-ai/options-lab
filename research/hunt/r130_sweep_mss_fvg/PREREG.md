# R130 pre-registration: Boss's sweep, structure-shift and fair-value-gap video setup, as stated

Written 29 Oct 2026, before any swing, setup, trade or statistic was computed on the data. Research only: nothing in
the app changes, nothing trades.

Boss sent a video's setup, 29 Oct. Three ideas:
- **Liquidity sweep:** price takes out the stops below a recent low (or above a recent high).
- **Market structure shift (MSS):** price then breaks the recent high (or low).
- **Fair value gap (FVG):** the gap left by the strong move, used as the entry zone.

Bullish, as stated:
1. On a 5-minute chart, mark the recent low and the recent high.
2. Wait for price to drop below the recent low (the sweep).
3. Wait for price to reverse and break the recent high (the MSS).
4. Find the FVG formed in that strong up-move. Enter when price pulls back into the FVG and forms an engulfing candle.
5. Stop just below the FVG. Target the next liquidity level, such as the upper equal highs.

Bearish: everything reversed (a sweep of the high, then a break of the low).

## 0. What is new here (earlier work)

| study | what it tested | result | how R130 differs |
|---|---|---|---|
| R62 | Volume profile + SMC: POC/OB/LVN pullbacks, FVG as a filter, 1-hour BOS/ChoCH as a trend filter | 0 of 32 | no sweep step; the FVG was only a filter on an LVN zone; entries were POC/LVN retests |
| R62b | EmreKb's "Market Structure Break & Order Block" indicator (zigzag 9, fib 0.273) | 0 of 16 | order-block zones, not FVGs; no sweep step; no engulfing trigger |
| R115 row (R91 SMC sweep) | a sweep of a level plus a strong 5-minute candle (body ≥ 60%) | failed | entered on the sweep candle itself; no MSS, no FVG pullback |
| R127 | the CPR video (template for this write-up and the claims check) | 0 of 3 | different levels |
| R124 | wick-based zigzag swings | 30-point moves are a coin on direction | R130 uses wicks for every level, as R124 did |
| Liquidity arm (engine `LiquidityRules`) | pivot 20 bars each side; a level is "taken" by a **close** beyond it | on paper | R130's sweep is a **wick** beyond the level (the video's "drops below"); a close back inside is a variant (V6) |

No earlier study tested the full sequence sweep → MSS → FVG pullback → engulfing entry with a liquidity target.

## 1. Definitions (every choice the video leaves open is fixed here)

**Signal source: the index spot (the underlying), always.** Every level, sweep, MSS, FVG, pullback, engulfing candle,
stop and target is read on the index's minute bars. The option is only what is bought. No option-chain input is used.

**Bars.** The tf-minute bars of each index (tf = 5, or 1 in V2), aligned to 09:15 inside each session: open = the first
minute's open, high / low = the highest high / lowest low, close = the last minute's close. A bar is used only after its
last minute has closed. Bars run across sessions in order (yesterday's bars come before today's), so levels from the
last 5 sessions can be swept today.

**Swing points (the recent high and low; wicks).** A bar i is a swing high when its high is strictly above the highs of
the k bars before it and at or above the highs of the k bars after it (k = 3; V5: k = 5). Swing lows mirror on lows. A
swing is known only once bar i + k has closed (no look-ahead). Only swings from the current and the previous 4
sessions are used.

**The recent low (bullish) at bar s:** the most recently confirmed swing low that no bar's low has gone below since it
was formed. The recent high is the most recently confirmed swing high known before bar s (whether or not it was touched).

**The sweep (bullish), at a bar s of today's session:** the bar's low is at least one tick (0.05) below the recent low
(low ≤ level - 0.05). V6 also needs the sweep bar to close back above the level.

**The MSS:** the first bar m within N = 12 bars after s (same session) that **closes** above the recent high known at s.
If no such close comes within 12 bars, the setup is dropped. If a new sweep happens before the MSS, the newer sweep
replaces the old one. The MSS leg runs from the bar with the lowest low between s and m to bar m.

**The FVG:** any three bars (j-1, j, j+1) inside the leg (j-1 at or after the leg's low bar, j+1 at or before m) with
low[j+1] - high[j-1] ≥ the minimum size. Zone = [high[j-1], low[j+1]]. Minimum size = the larger of 2 ticks and 0.01%
of the index. When there are several, the **largest** is used (ties: the latest). No FVG: the setup is dropped.

**The stop:** 0.02% below the FVG's bottom edge (its far edge).

**The pullback:** within M = 12 bars after the MSS bar (same session), a bar's low reaches the FVG's top edge. If a
bar's low reaches the stop before the pullback-and-trigger completes, the setup is dropped.

**The engulfing candle (V1, V2, V4-V6):** a bar e at or after the first pullback bar (and within the 12 bars) that
closes above its open, whose body engulfs the previous bar's body (open[e] ≤ min(open, close) of bar e-1 and close[e] ≥
max(open, close) of bar e-1), with the previous bar's body not bullish (close ≤ open), and whose low has not reached
the stop. Bodies are the only use of opens and closes besides the MSS close, as the video defines them.

**Entry:** buy the option at the open of the minute after the trigger bar closes. Entries 09:15-15:00 (the entry minute
at or before 15:00). V3 (no engulfing): the trigger is the first **1-minute** bar after the MSS bar that touches the
FVG's top edge (entry at the next minute's open), within the same 12-bar window; a stop touch before it drops the setup.

**Target (as stated):** the nearest "equal highs" above the entry: two or more confirmed swing highs (last 5
sessions, known at entry) within 0.03% of each other, both above the entry price; the target is the higher of the
pair (the liquidity rests above them). If there are none, the nearest confirmed swing high above the entry. If there is
no swing high above the entry at all, there is no trade (counted). V4: a fixed 2R target, entry ± 2 × (entry - stop),
with the entry price = the index's open in the entry minute.

**Exits (index wicks, minute by minute from the entry minute):**
- stop: a low at or below the stop;
- target: a high at or above the target;
- both in the same minute: the stop (worst case);
- otherwise out at 15:20 (15:05 if the option bought expires that day).

**One trade at a time per index.** All pending setups are cleared at an entry; the search restarts with the bar after
the exit. Bullish and bearish setups run side by side; if both trigger on the same bar, neither is taken.

**Bearish:** the mirror (a sweep above the recent high; a close below the recent low; a bearish FVG high[j+1] <
low[j-1]; a pullback up to its bottom edge; a bearish engulfing candle; stop 0.02% above its top; target the nearest
equal lows / swing low below; buy the ATM put).

**The option (the app's rules: the nearest expiry that does not expire today).**
- ATM (half-up rounding to the strike step) from the index's last close before the entry minute; CE for bullish,
  PE for bearish; 1 lot at today's lots (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20).
- The lake's nearest-expiry series (`opt_<UND>_WEEK`). On the index's own expiry day that contract expires today, so
  the monthly series (`opt_<UND>_MONTH`) is used when it does not expire that day; otherwise there is no trade that day.
  The lake has no next-week series, so on a weekly expiry day the monthly contract is the nearest one available. Expiry
  days come from the data with R25's rule, as R123-R127.
- Lake cleaning (lakeclean steps 1-3, stale=True), as R123-R127. Bought at the entry minute's open, sold at the last
  traded close at or before the exit minute. Liquidity-aware slippage (Fills "liq"), also at 2x; dated charges.

**Index-points version (context only):** the same trades in index points and R multiples, no option, no costs.

## 2. Variants (6, one BH family)

| variant | chart | trigger | target | pivot k | sweep |
|---|---|---|---|---|---|
| **V1** the rule as stated | 5-min | engulfing | equal highs / lows, else the next swing | 3 | wick |
| V2 | **1-min** | engulfing | equal highs / lows | 3 | wick |
| V3 | 5-min | **no engulfing** (the first touch of the FVG) | equal highs / lows | 3 | wick |
| V4 | 5-min | engulfing | **fixed 2R** | 3 | wick |
| V5 | 5-min | engulfing | equal highs / lows | **5** (longer lookback) | wick |
| V6 | 5-min | engulfing | equal highs / lows | 3 | **wick and a close back inside** |

The video claims no trend-day filter, so there is none.

## 3. Data, periods and checks

- **Data:** the Drive lake (rclone remote `gdrive`, from env vars), copied 29 Oct 2026: `index_1m` for NIFTY,
  BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; `options_rolling_1m` WEEK and MONTH files. Aug 2021 (MIDCPNIFTY / SENSEX
  later) to 19 Oct 2026. Nothing raw goes to git; the trade list goes to Drive (`derived/r130/`).
- **Periods (R123's):**
  - design 2021-08-01 .. 2026-03-15, walk-forward folds F1 2023, F2 2024, F3 2025, F4 2026-01-01 .. 03-15;
  - embargo 2026-03-16 .. 03-31 (not used);
  - **holdout 2026-04-01 .. 2026-10-19, locked: read ONCE** for all 6 variants, after the design results are committed,
    with a flag file that refuses a second read.
- **No fitting.** Every setting above is fixed by this file, so each fold is out of sample by construction. The
  walk-forward is the per-fold result of the fixed rule.
- **Random-entry twins (200 per trade):** the same index, day and side; a random entry minute 09:20-15:00 (and before
  the day's exit time); the same stop and target distances in index points; the same exits and the same option rule.
  p = (1 + twin totals ≥ the rule's total) / 201 (a twin with no trade is resampled from that trade's valid twins).
- **The video's claims, checked with n (design and holdout):**
  - C1 "the target is reached more often than the stop": target exits vs stop exits per variant;
  - C2 "price pulls back into the FVG": the share of sweep → MSS → FVG setups that touch the FVG within 12 bars;
  - C3 "the sweep matters": every MSS close (with an FVG in its leg) is split into those with a sweep within the 12
    bars before it and those without. From the MSS bar's close, a 1:1 race on index wicks: does the index go +d before
    -d, with d = the distance from the MSS close to the leg's extreme, by the session's end? Compared with a
    two-proportion test.
  - C4 "the engulfing candle helps": V1 vs V3 target share and average net.
- **Gates.** A variant passes only if all hold:
  - design: ≥ 300 trades; average net > 0 at 1x and 2x slippage; positive in ≥ 3 of the 4 folds; BH q ≤ 0.10 over the
    6 variants vs twins;
  - holdout (read once): ≥ 50 trades; average net > 0; **win rate ≥ 65% (Boss's 65% rule, 28 Oct)**;
  - account (design + holdout, 1 lot): max drawdown above -Rs 40,000 and no calendar month below -Rs 13,000.
- **If one passes,** a paper arm is proposed (not built). Otherwise the write-up says plainly it fails.

## 4. Outputs

- Code and tests: `research/hunt/r130_sweep_mss_fvg/` (`r130.py`, `test_r130.py`, with a look-ahead guard: a setup
  found on full data is found identically when every bar after its entry minute is removed).
- Results: `results/design.json`, then `results/holdout.json` (the one read).
- Write-up: `research/HUNT_R130_SWEEP_MSS_FVG.md`; entries `research/experiments/entries/r130-*.yaml`; RESULT.md.
