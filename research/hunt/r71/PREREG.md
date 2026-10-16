# R71 pre-registration: chart patterns on the index, buy the option (a new arm next to Donchian?)

Written 16 Oct 2026, **before any R71 profit or loss was computed**. This file is committed first; results come after.
Research only: nothing here changes the app (no `android/**`, no `.github/**`). Paper or not is Boss's call.

Boss, 16 Oct: *"Now find something new like Donchian, find chart patterns, and provide me a good strategy back."*

## 0. What was already tried (not re-tested here)

From `research/experiments/entries/` and the `HUNT_*.md` write-ups. Every one of these was **rejected** on index options
unless marked, so R71 does not test them again:

| idea | where | result |
|---|---|---|
| Opening-range breakout, every range length 15-75 min, 5/15/30-min closes, value-area ranges, adaptive ranges, retests, OI-wall ranges, GEX filters | h19-h22, R3, R20, R20b, R22, R23, R28, R49, R56, R47 U1 | rejected (R14 re-entry cap adopted as a trade-management rule only) |
| Previous-day high / low and pivot (R1/S1) breaks, turtle soup on PDH/PDL | R20brk, R20C-3, R47 U8, R52 R1-7, i12 level map | rejected |
| NR4 / NR7 **daily** next-day breakout, NR7 / inside-day multi-day | OBUY GA-11, SWING_DEEP-6 (R52) | rejected (the intraday 15-min NR bar is new, P2 below) |
| EMA ribbon squeeze / fan-out, Bollinger(20,2) states, enhanced Bollinger | R29, R51 R29-BOSS, R52 H43-2/5 | rejected (the Bollinger-inside-Keltner squeeze is new, P3) |
| Supertrend(10,3)+EMA50, dual Supertrend, trend stack EMA9/21 + Supertrend + VWAP | R52 ST-1, R35, F1-C1 | rejected |
| VWAP reject, VWAP / TWAP trend filters, option-volume VWAP | H46, R52 H46-2, R10, R52 TREND_FILTER-1 | rejected (the index has no volume, so no VWAP rule is tested) |
| Gaps: gap-and-go / gap-fade, gap-down bounce, gap-stretched opening range, overnight gap models | R23 gap-or30, R52 H42-9, R47 U2, R41 Q4, R58 (stocks) | rejected (not re-tested) |
| Point & Figure double-top break, Fibonacci 78.6% pullback, Gann 2-bar swing, previous-day Fibonacci | R52 H37-9, H37-2, R4-8, R4-2 | rejected |
| Market-structure break + order-block pullback, volume profile + SMC, ICT fair value gap | R62, R62b, R52 R1-6 | rejected |
| Doji follow, candle-pattern mining before hero moves, Larry Williams volatility breakout | R52 H25-10, R40, R52 R1-2 | rejected |
| EMA cross + ADX, RSI fade / momentum (R44 sweep), Donchian 20 with ATM / -25% / +50% | R44, R51 | rejected |
| **Donchian 20 break, 5-min index, 1-ITM, -15% / +30% / 30 min** (R51 R44-donchian20-RT, Amendment A) | R51, R64-R66 | **passed; paper arm** |

## 1. The eight new rules (signal source: the INDEX for every rule)

All signals are read from 1-minute **index** bars (the underlying), grouped into 5-minute bars (09:15-09:19, ...) and
15-minute bars (09:15-09:29, ...), on the continuous series from 1 Sep 2024 (warm-up only; no trade before
20 Nov 2024), exactly as R51's `fam_r44.bars5`. No option-chain data is used (no OI, PCR, IV filters), and no rule reads
the option's own chart. ATR14 = Wilder ATR of the 5-minute index bars (continuous series). A signal on a 5-minute bar's
close fills at the next 5-minute row (the bar's close minute + 1, R51's next-bar fill); a 15-minute signal fills at the
next row after the 15-minute bar closes.

| id | rule (call side; the put side is the mirror) | index stop (`inval`) | original | re-tune grid |
|---|---|---|---|---|
| **P1 Inside bar** | 15-min bars: bar I lies inside the previous bar M (high_I <= high_M, low_I >= low_M). After I closes, the first 5-min close above high_M within the next 30 minutes -> call | low_M | 15-min bars | bar size {15, 30} min |
| **P2 NR-N bar** | the 15-min bar whose range is the smallest of the last N 15-min bars (today's bars only, so from the N-th bar of the day); the first 5-min close above its high within the next 30 minutes -> call | its low | N = 7 | N {7, 4} |
| **P3 Squeeze release** | 5-min: Bollinger(20, 2 sd) inside Keltner(EMA20, 1.5 x ATR20) for at least S bars in a row ("squeeze on"); the first bar after that with the squeeze off and close > upper Bollinger -> call | the 20-bar mean (BB middle) at the signal | S = 6 | S {6, 12} |
| **P4 Flag** | pole: the 3 5-min bars ending at bar p move close_p - open_(p-2) >= K x ATR14 (all three closes rising); flag: the next 3-10 bars never trade above the pole high and never retrace more than half the pole; the first close above the pole high after at least 3 flag bars -> call (a setup dies after 10 flag bars or a >50% retrace) | the flag's low | K = 2.0 | K {2.0, 1.5} |
| **P5 Double bottom** | 5-min swing lows (a low below the 2 bars each side, known 2 bars later), both today, the two most recent ones at least 4 bars apart, within T x ATR14 of each other, with a high between them (the neckline) at least 1 x ATR14 above the higher low; the first close above the neckline after the second low is confirmed and before any low below the lower one -> call (double top -> put) | the lower low | T = 0.25 | T {0.25, 0.5} |
| **P6 Heikin-Ashi flip in trend** | 15-min Heikin-Ashi candles (continuous): the bar turns green with no lower wick (HA low = HA open) after at least 2 red HA bars, while the 15-min close is above the EMA(L) of 15-min closes -> call | the lowest real low of the last 3 15-min bars | L = 20 | L {20, 50} |
| **P7 Three-bar reversal at the day's extreme** | 5-min: bar 1 makes a new low of the day (from the 7th bar of the day, 09:45, on), bar 2 makes no lower low, bar 3 closes above bar 1's high -> call (new day high -> put) | bar 1's low | first bar 1 at 09:45 | start {09:45, 10:30} |
| **P8 Swing structure (higher high + higher low)** | 15-min swing points (a high / low beyond the W bars each side, known W bars later; continuous series): the last two swing highs rise and the last two swing lows rise, the last low after the last high (a higher low); the first 5-min close above the last swing high -> call | the last swing low | W = 2 | W {2, 3} |

Trades for every rule are taken **exactly as Donchian takes them** (R51 / `DonchianArm`): entries 09:20-15:10, the
**1-ITM** option of the side (from the ATM strike of the minute before entry), one open position a side, at most 2
entries a side a day, never on the index's expiry day, NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX (R51's clean
stores, Amendment A).

## 2. Exits (three sets; the option premium and the index)

| id | stop | profit lock | target | time exit | square-off |
|---|---|---|---|---|---|
| **XB** | -15% premium | none | +30% | 30 minutes | 15:15 |
| **XL** | -15% premium | R64 ladder (breakeven + charges at +30%, +30% at +60%, +60% at +100%) **plus R66 L25** (+5% once +25%) | none | 30 minutes | 15:15 |
| **XI** | **index stop**: a 1-minute index close beyond `inval` sells at the next minute's open; plus a -25% premium safety stop | XL's ladder + L25 | none | 60 minutes | 15:15 |

XB is Donchian's research exit; XL is Donchian with today's A4 ladder and Boss's L25 step; XI exits on the chart pattern
being broken on the index. Engine: R64's (`hunt/r64/r64engine.py`, R51's simulator plus the breakeven step).

**Original** of each rule = its original parameter with **XL**. **Re-tune** = the 2 parameters x 3 exits = 6 grid
points, chosen walk-forward exactly as R51 section 4.

## 3. Measurement and periods (identical to Donchian's R51 / R64-R66 studies)

- One HELD contract, next-minute fill (up to 2 minutes later), R17 "liq" fills, stops on the minute low with gap fills
  at the worse open, locks from the next minute, **dated charges** (STT 0.15% from 1 Apr 2026), lots in force, 1 lot.
- **Design 20 Nov 2024 - 31 Mar 2026; walk-forward folds Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026**
  (each fold traded with the grid point that had the best net on every design day before it, at least 20 trades,
  else the original); an original's walk-forward = its design. The re-tune's final setting = the grid point with the
  best whole-design net (at least 30 trades).
- **Final 1 Apr - 13 Oct 2026, read ONCE** by `r71study.py final` (guard `results/FINAL_READ`; R51's period guard
  refuses any final day before that). Honest note: Donchian's arms were chosen on this window and it has been read by
  R51-R70, so it is not pristine; it is fresh for these eight rules, but the market it covers is known.
- Only paper trading from 14 Oct 2026 is truly unseen.

## 4. Gates (as R51, never relaxed) and the decision

Per tested rule (8 originals + 8 re-tunes = **16 tests**) on the final period: net > 0; random-entry twin p < 0.05
(500 twins, seed 71: every trade replaced by one on the same day, index and side, entry minute uniform in 09:20-15:10,
the 1-ITM strike, the same exits; for XI the twin's index stop sits the same number of index points from its entry as
the real trade's); **Benjamini-Hochberg q <= 0.10 across the 16**; >= 40 trades; the top 5 trades < the net; design
net > 0 and walk-forward net > 0.

**Paper decision:** a rule is proposed for paper only if it passes every gate AND its final net is still > 0 at twice
the spread and slippage (R51 Amendment A's check). If several pass, the one with the best final net per trade is the
"best new strategy". Paper only, never live, behind Boss's switch, logged as every paper change.

## 5. Compared with Donchian on the same days (information, not gated)

Donchian 20 (R51 signals, 1-ITM) priced by the same code with XB (its research exits) and XL, on the same design and
final days. For each rule and Donchian: trades, net, win rate, average win / loss, worst day, max drawdown, % green days,
trades a month. **Overlap**: the Pearson correlation of the daily nets (all sessions, 0 on no-trade days) with Donchian
XB, and the share of a rule's trades that have a Donchian trade on the same index, day and side within 15 minutes of
entry. A rule with a daily correlation >= 0.6 is labelled a **duplicate** of Donchian (it would add risk, not a new
edge). Also the combined book (Donchian XB + the rule) against Donchian alone: net, max drawdown, worst day.
Random-entry twin means are reported as the "no skill" baseline for every rule.

## 6. Logging

Every one of the 16 tests gets an entry in `research/experiments/entries/` (family `patterns-indicators`), failures
included, with sample sizes. Tests in `research/hunt/r71/test_r71.py` (synthetic data, no lake), including the
look-ahead guard: a rule's signals up to bar b never change when every bar after b is changed.
