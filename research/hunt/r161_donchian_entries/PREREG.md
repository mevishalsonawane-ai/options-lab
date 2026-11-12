# R161 pre-registration: which Donchian breaks to take (entry quality), to raise the win rate without losing money

Written 12 Nov 2026, **before any R161 number was computed** (committed on `claude/r161-donchian-entries` before the
code's first run). Research only; nothing in `android/**` changes.

Boss, 12 Nov: *"I want to increase my win rate [of the] Donchian family, try your best ... use all resources you can."*
Two sibling studies run today in parallel: R162 (exits / strikes) and R163 (picker / ML). R161 is the **entry quality**
family only: which of the arms' breaks to take, read on the index.

Signal source for every rule: **the index** (the arms' own 5-minute index bars, the other four indices' 5-minute bars,
and India VIX minutes as context). The option bought, its strike rule and its exits are today's. No rule reads an
option's own price chart.

## 0. What is already known (so R161 does not repeat it)

Read before writing this: R105C (fixed +5/+10/+20% targets: higher win, money lost), R105B (book direction rules:
weekly / daily / 60-min Dow, prior-day trend, 200-day SMA, daily ADX, first 15-min candle, gap side: all hurt), R105D
(futures volume), R105E (option liquidity), R105F (OI), R112 / R114 (entries until 10:00), R73 (EMA20/50, 15-min and
60-min EMA20 rising, ADX, Supertrend, TWAP, 1-minute waits), R75 (time cuts before 09:30 / 09:45 / after 14:10, VIX
lowest 30%, opening-range and prior-day-range chop, any-index expiry day, gap > 0.5 ATR), R101 (VIX regime vs 60-day
median, quiet gap), R142 (fake poke, weak push, quiet bar in the lowest fifth, 10:30-12:00), R150 (W20 levels), R151
(the logistic pickers, live now), R152 (+/-Rs 2,000), R153 (lose-less combinations), R106 (small targets).

So R161 does **not** re-test any of those rules. Where an idea in Boss's list was already tested (gap side, expiry-day
skip, VIX regime, 15/60-min EMA rising, daily trend), it is left out and named in the report. The rules below are new
forms of the same families (confirmation, strength, squeeze, higher-timeframe Donchian, volatility, breadth, time, day
context).

## 1. Arms, engine, B0

Exactly as R152 / R153 (`research/hunt/r152_donchian_2k/`):
- **R72 Breadth Donchian** (4 of the 5 indices close beyond their 20-bar channel on the same 5-minute bar; R101's units).
- **R51 Donchian 20 + Supertrend (10, 3)** (R112 / R115 units).
- 1-ITM weekly option from the minute before the entry, entries 09:20-15:10, never on the index's own expiry day.
- Today's exits (R101 XL): -15% premium stop, 30/60/100 ladder + L25, 30-minute time stop, 15:15 square-off.
- R106's walk: one open trade a side per index, at most 2 fresh fills a side a day.
- R152's engine for N lots: dated Zerodha charges on the whole quantity, R17 slippage from the order's size.
- **B0** = today's rules at the same lots. At 1 lot it must reproduce R72 3,071 / Rs 2,16,929 (design) and
  1,202 / Rs 1,71,366 (final); R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743 (asserted, as R152).

Lots: 1, 2 and 3.

## 2. The entry filters (fixed now; no threshold is fitted)

Every filter reads the **signal bar** (the 5-minute index bar whose close gives the break; it closes at the minute
before the entry row) and only data closed at or before it. `ATR` = ATR 14 of the index's continuous 5-minute bars at
the signal bar; `chi` / `clo` = the high / low of the 20 bars before the signal bar (the channel the break crossed).
A filter that cannot be computed for a break (not enough history, a missing VIX minute) **takes the break**, as today.
A filtered-out break is removed **before** the walk, so it frees its side (a filter in front of the arm, as R101 / R153
AM10).

| id | family | take the break only when ... |
|---|---|---|
| E1 STRONGCLOSE | confirmation | the signal bar closes in its outer 30%: a call needs (close - low) / (high - low) >= 0.70, a put <= 0.30 |
| E2 HOLD1 | confirmation (wait one bar) | the NEXT 5-minute bar also closes beyond the broken level (call: close > chi; put: close < clo); the entry moves to the minute after that bar (row + 5), strike re-chosen from the minute before; dropped if past 15:10 or if the same side already has a candidate at that minute |
| E3 BIGBAR | break strength | the signal bar's range (high - low) >= 1.0 x ATR |
| E4 FAR | break strength | the close is beyond the channel by >= 0.20 x ATR (call: close - chi; put: clo - close) |
| E5 SQUEEZE | channel width before the break | the channel width (chi - clo) is at or below its own median over the previous 100 bars (needs 100) |
| E6 HTF15 | higher timeframe | the last fully closed 15-minute bar (09:15-anchored, continuous across days) closes on the trade's side of the midline of its 20-bar 15-minute Donchian channel (bars k-19 .. k) |
| E7 HTF60 | higher timeframe | the same on 60-minute bars (09:15-anchored; the day's last bar 15:15-15:29 is short) |
| E8 PDR | day context | the signal bar's close is beyond the previous session's high (call) / low (put) |
| E9 ATRHOT | volatility regime | ATR is at or above the median of this index's ATR at the same bar of the day over the previous 20 sessions (needs 10) |
| E10 VIXWAY | volatility (India VIX) | VIX at the minute before the entry vs the day's 09:15 VIX open: a call needs VIX down (< 0), a put needs VIX up (> 0) |
| E11 BREADTH5 | market breadth | at least 4 of the 5 indices' same 5-minute bars close on the trade's side of their own day open (beyond R72's 4-of-5 channel break: the day's direction, not the break) |
| E12 NOLUNCH | time of day | the entry minute is NOT from 11:30 to 13:29 (a window R112 / R75 / R142 did not cover) |
| E13 PASTHOUR | day context (first-hour range) | for entries from 10:15 on, the close is beyond the first hour's (09:15-10:14) high (call) / low (put); entries before 10:15 are untouched |
| C1 COMBO | combination (max 2, chosen on design only) | the AND of the two singles chosen by the rule below |

**C1's choice (walk-forward).** On a training window, among E1 and E3-E13 (E2 is excluded: it moves the entry), at
1 lot on B0's trades in that window: a single qualifies when its win rate is above B0's and its net is not below B0's.
The two qualifying singles with the largest win-rate gain are ANDed; with one, C1 is that single; with none, C1 is B0
(no change, and it fails). Each walk-forward quarter (Apr-Jun 2025, ..., Jan-Mar 2026) uses the choice made on every
design day before it; C1's walk-forward figure is those four out-of-sample quarters. The final uses the choice made on the
whole design period. C1's full-design figure is in-sample and only reported.

## 3. Periods

As R152 / R153: design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window Apr 2025 - Mar 2026 (four quarters);
final 1 Apr - 13 Oct 2026, read **once** by R161 (`results/FINAL_READ`). **Honest note:** Apr - Oct 2026 has already
been read by many studies on these arms (R51-R160), so a pass there is weak evidence. The post window (14-19 Oct) is not
used.

## 4. Two looks

- **First look:** each filter on today's rules (B0) at 1, 2 and 3 lots. Reference = B0 at the same lots.
- **Second look (on top of R151's live pickers):** the filter first (it removes breaks before the walk), then the app's
  picker rule (a break scored below 0.4656 on R72 / 0.4469 on R51 is skipped but still holds its side and counts in the
  day's caps, as R152). Reference = the picker alone at the same lots.
  - Final: scores from the app's exported model numbers (R151 `results/*_picker_model.json`), as R152.
  - Walk-forward window: R151's out-of-fold logistic scores (M2, trained on every design trade before each quarter)
    with the app's thresholds. The thresholds were chosen by R151 on these scores, so this reference is in-sample for
    the threshold: disclosed.
  - Design before Apr 2025 has no out-of-fold score; the second look's "design" figure is the walk-forward window.
  - C1 on the second look is the same choice as on the first look.

Tests: 2 arms x 14 rules x 3 lots x 2 looks = **168**.

## 5. Success criterion and gates (all must hold)

Against the reference at the same lots:
1. **Win rate up by >= 5.0 points** in the walk-forward window AND in the final.
2. **Net not lower** than the reference in the walk-forward window AND in the final.
3. **Net > 0** in design (second look: the walk-forward window), the walk-forward window and the final.
4. **Better than random skips (final):** 1,000 twins (seed 161), each removing at random as many of the arm's
   final-period breaks as the filter removed, then walking again (second look: then the picker rule). Two p-values,
   p_win (twins' win rate >= the rule's) and p_net (twins' net >= the rule's), each (1 + hits) / 1,001, with R115's
   normal-tail p when no twin reaches it. **The test's p = max(p_win, p_net)** (the rule must beat random on both).
   E2's twins remove at random as many breaks as E2 dropped (they do not delay entries).
5. **BH q <= 0.10** with m counting all earlier tests at p = 1: R160's tally 2,164 + R161's 168 = 2,332. R162 and R163
   run in parallel today; **raw p-values of all 168 tests are written to `results/raw_pvalues.csv`** so the coordinator
   can run one joint BH over R161 + R162 + R163. R161's own q (m = 2,332) is also reported.
6. **>= 40 final trades.**

Reported for every arm x rule x lots x look and period: trades, win %, break-even win % (average loss / (average win +
average loss)), net, average win, average loss, worst day, max drawdown (daily), net without the 5 best trades,
breaks removed, and the twin p-values.

## 6. Tests and look-ahead

pytest tests for every feature and filter (edge cases: short history, missing bars, a flat bar, missing VIX, last bar
of the day, the first hour not finished) and a **look-ahead guard**: scrambling every index bar after the signal bar
(and every VIX minute from the entry row on) changes no filter decision.

## 7. What a pass means (CLAUDE.md)

PAPER: a passing filter may act on paper for that arm straight away, behind a labelled switch, logged in the Autopilot
timeline, undoable in one tap. LIVE: a filter only skips entries (lowers risk), but anything new that could skip live
trades starts in shadow behind a switch that is off by default. Nothing goes live from R161. Failed rules stay notes in
the report and in `research/experiments/entries/r161-*.yaml`.
