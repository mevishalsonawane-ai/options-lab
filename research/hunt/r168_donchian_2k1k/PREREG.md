# R168 pre-registration: the Donchian arms with a +Rs 2,000 target and a -Rs 1,000 stop (1-3 lots)

Written 13 Nov 2026, **before any R168 number was computed** (committed on `claude/r167-donchian-smart-exits` before the
code's first run). Research only: nothing in `android/**` changes.

Boss, 13 Nov: *"Research 2k profit and 1k loss, what will be the winrate and profit of the year."*

**Signal source: the index.** The arms' entries are unchanged:
- R72: 5-minute Donchian 20 breaks on 4 of 5 indices;
- R51: 5-minute Donchian 20 breaks kept by Supertrend (10, 3).

Only the option's exit changes. The target and stop are on the premium, which CLAUDE.md allows.

## 0. What is already known

- R152 (7 Nov): +Rs 2,000 target with a -Rs 2,000 stop (1 : 1) lost money at every lot size: it cut the big winners.
- R106: 1 : 1 targets of Rs 500 - 1,500 failed. R112: +Rs 1,500 / -Rs 1,000 failed on net.
- R167 (12 Nov): G2 (a lock after +Rs 1,500) raised the win rate but made less money.
- Never tested: **+Rs 2,000 with -Rs 1,000 (2 : 1).**

## 1. The rule (fixed now)

**T2S1** (the whole position, net of the trade's real charges; R152's engine, `r152engine.price(tgt=2000, stop=1000)`):
- a resting limit sell at the premium whose sale nets **+Rs 2,000**;
- a stop at the premium whose stop sale (R17 stop slippage) nets **-Rs 1,000**. The nearer of it and the arm's own -15%
  stop acts (R152's rule).
- Both levels are solved from the trade's own real fill, so nothing looks ahead.
- The arm's other exits stay: ladder + L25, 30-minute time stop, 15:15 square-off.

B0 = today's exits. At 1 lot, B0 must reproduce R152's published numbers (asserted): R72 design 3,071 / Rs 2,16,929,
final 1,202 / Rs 1,71,366; R51 design 4,208 / Rs 50,425, final 1,670 / Rs 1,34,743.

**Tests:** 2 arms x 3 lots = **6**.

## 2. Periods and "the year"

- Design: 20 Nov 2024 - 31 Mar 2026. **"The year" = the walk-forward year, Apr 2025 - Mar 2026** (12 months, nothing
  fitted), with its four quarters. Calendar 2025 is also reported.
- Final: 1 Apr - 13 Oct 2026. **Honesty note:** this period has already been read by many studies, including R152's
  +/- Rs 2,000 and R167 on these same arms. It is a second look, weak evidence. It is read once by R168
  (`results/FINAL_READ`).

## 3. Reported (every arm x lots, B0 alongside, per period)

Trades, win rate, net, average win and loss, the break-even win rate, worst day, max drawdown (daily), exit mix (target /
Rs 1,000 stop / -15% stop / other).

## 4. Gates (the same as R162 / R167, Boss's success test)

1. Win rate up >= 5 points over B0 at the same lots, in the walk-forward year AND the final.
2. Net not lower than B0's at the same lots, in the walk-forward year AND the final.
3. Net > 0 in design, the walk-forward year and the final.
4. Random-exit twins (final, 500 per test, seed 168; R152 / R106's twin: each trade the rule sold before B0's exit is sold
   at a uniformly random minute strictly between its fill and B0's exit, B0's exits active before it). Two p values, on
   net and on win rate; the test's p is the larger. **BH q <= 0.10** with m = R168's 6 + 2,668 earlier (R167's m 2,664 and
   its 4 pre-registered G2S tests) = **m 2,674**, earlier tests at p = 1. Raw p-values go to `results/raw_pvalues.csv`.
5. At least 40 final trades.

**Second look (descriptive, never gated):** the rule on R151's picker trades (what the app trades), Apr - Oct 2026, via
R152's `r152picker.py`.

A pass would be tighten-only? **No.** The -Rs 1,000 stop is tighter than -15% for most trades (an earlier exit), but the
+Rs 2,000 target can sell a winner that today's exits would hold, so it is not purely risk-lowering. Even a pass would
start in shadow on live, behind an off-by-default switch; nothing changes in the app from R168.
