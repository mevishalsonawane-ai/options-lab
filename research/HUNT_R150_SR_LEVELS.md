# R150: every 3-month support / resistance level on one chart, and is it useful for R51 Donchian? (+ fake breakouts)

Written 5 Nov 2026 for Boss:
- "I want you to mark all the support and resistance from last 3 month at once on the same chart (from wherever the
  data is up or down from more then 2 spots), and check if that is useful."
- Then: "While studying also take care of fake break outs."

**Files:**
- Pre-registration: `research/hunt/r150_sr_levels/PREREG.md`, commit `7cba733`.
- Amendments, both before any R51 result: A1 (major levels, `f8697aa`) and A2 (fake breakouts, `a9bddf1`).
- Design results: committed in `650e429` before the final read, which was read once (`results/FINAL_READ`).
- Code and 7 tests: `research/hunt/r150_sr_levels/`.
- Experiment entries: `r150-*.yaml`.

**Research only: nothing in the app changes.**

## The charts

- `results/levels_<INDEX>.png` (one per index) and `results/levels_all.png` (all five). Each shows the last 3 months,
  6 Aug to 5 Nov 2026, with every major level at once:
  - solid lines with the turn count: 3 or more turns;
  - dashed lines: 2 turns;
  - orange: resistance, above today's price;
  - blue: support, below it.
- `results/levels_dense_<INDEX>.png` shows the denser version. `results/levels_now.json` lists every level with its
  turn count.

**How a level is found:**
- A turning point is a 15-minute bar whose high (or low) is the highest (lowest) of the bars around it.
  - **Dense:** 4 bars, 1 hour, each side.
  - **Major:** 12 bars, 3 hours, each side.
- Turning points within 0.15% of each other are one level. "More than 2 spots" = 3 or more turns.
- **Today (5 Nov), every index is at or near its 3-month high,** so almost every level is support below the price.
  BANKNIFTY's one resistance is 58,025 (5 turns).
- **The dense definition finds 26-31 levels per index** in 3 months, one about every 0.3%. Price is always near "a
  level", which is part of why levels predict little.

## Short answer for Boss

**Support / resistance levels did not make R51 Donchian better in a way that holds up. Nothing changes in the app.**

R51 was tested exactly as the app runs it (Donchian 20 + Supertrend, 1-ITM, -15% stop, the 30-60-100 lock with L25, 30
minutes). The base reproduced the published numbers exactly: 4,208 design trades, +Rs 50,425; 1,670 final trades,
+Rs 1,34,743.

| test (each only skips trades or sells earlier) | design Nov 2024 - Mar 2026 | final Apr - Oct 2026 (read once) | verdict |
|---|---|---|---|
| R51 today | 4,208 trades, +Rs 50,425, drawdown Rs 2.14 lakh | 1,670 trades, +Rs 1,34,743, drawdown Rs 81,683, worst month -Rs 54,587 | |
| B3: only breaks THROUGH a 3+-turn level | 2,190 trades, +Rs 1,21,489, drawdown Rs 1.14 lakh | 863 trades, +Rs 69,368, drawdown Rs 41,328, worst month -Rs 4,439 | fail: half the trades, half the losses, but random skips of the same number do as well (p 0.76) |
| W3: skip when a 3+-turn level is within 0.25% ahead | -Rs 55,994 | +Rs 1,04,558, losses -Rs 2.64 lakh vs -Rs 3.96 lakh, better than random skips (p 0.02) | fail: loses money in design; BH q 0.107 |
| W2: the same with 2+-turn levels | +Rs 19,910 | +Rs 82,199 (p 0.03) | fail: design worst month worse; BH q 0.107 (the closest to passing) |
| W3M / B3M: the same on major levels | -Rs 92,122 / +Rs 43,021 | +Rs 1,20,649 / -Rs 11,409 | fail |
| FX: fake-breakout exit (sell when the index closes back through the level it broke) | -Rs 27,915 | +Rs 1,41,034 (p 1.0) | fail: it sells trades that would have recovered |
| FXM: the same on major levels | +Rs 21,182 | +Rs 1,50,447 (p 0.78) | fail |

**Fake breakouts (design, 12,483 R51 signals):**
- 2,950 signals (24%) broke through a dense 3+-turn level; 52% of those closed back through it within 30 minutes.
  Major levels: 1,497 signals, 52%.
- Signals that broke no level fell back below their own break price within 30 minutes 77% of the time.
- So **a break through a real level fakes out less often (52% vs 77%), but still about half the time.**
- **Selling at the first close back through the level loses more than it saves.** Many fakes come back within R51's
  30 minutes, and R51's own -15% stop already handles the rest.

**The one lead:** "skip a trade that runs straight into a nearby level" (W2 / W3) cut the final period's losing days
by Rs 1.3-1.5 lakh, better than random skipping. It failed in the design period, so it is a NOTE.
- If Boss wants it, the honest route is a SHADOW log in the app (record which R51 trades would have been skipped), and
  a judgement after about 300 new trades.

## Honesty notes

- **Earlier work agrees.** R20c (2-candle breaks of S/R: a coin toss; random levels did as well) and R139 / R140 (market
  structure: 0 passes) found the same.
- **Amendment A1 was made after seeing how dense the levels were,** but before any trade result. A2 was Boss's addition,
  also before any result.
- **The final period (Apr - Oct 2026) had been read for R51 itself** by R51, R73, R140, R141 and R144a. These level
  rules were never computed on it before.
- **Gates (R140's "lose less").** In design, walk-forward and final, all must hold: losing days smaller, drawdown /
  worst day / worst month no worse, still in profit. Also: a random twin p < 0.05, BH q <= 0.10 over 7, at least 40
  final trades.
- **Levels are point-in-time:** a turning point counts only after the bars that confirm it have closed. The look-ahead
  test scrambles later bars.
- **Chart data:** the lake (to 16 Oct) and Yahoo 15-minute bars (17 Oct - 5 Nov). Only index prices are committed; no
  option data.
