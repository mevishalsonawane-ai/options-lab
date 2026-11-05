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

## Amendment B: support / resistance on the 5-MINUTE timeframe (Boss: "What if we add support and resistance on 5min time frame?")

- **Pre-registration:** commit `52535e0`, before any 5-minute result.
- **Design results:** committed in `090f2af` before the other reads.
- **The deciding holdout:** R51's **unseen two years, Dec 2022 to Nov 2024**, read once (`results/UNSEEN_READ_B`). The
  base reproduced R143 exactly: 4,815 trades, -Rs 4,00,363.
- **Apr - Oct 2026** is a second look, reported but not deciding.
- **Levels:**
  - pivots on 5-minute index bars, held 30 minutes each side;
  - 3+ turns within 0.15%;
  - over the last 5 sessions (L5, about 6-9 levels) or 20 sessions (L20, about 15-19 levels).

Each cell: trades / win rate / net / losing days / max drawdown.

| rule | design Nov 2024 - Mar 2026 | final Apr - Oct 2026 (2nd look) | UNSEEN Dec 2022 - Nov 2024 (read once) | twin p final / unseen | verdict |
|---|---|---|---|---|---|
| R51 today | 4,208 / 44.5% / Rs 50,425 / Rs -1,346,619 / Rs 214,239 | 1,670 / 44.9% / Rs 134,743 / Rs -395,932 / Rs 81,683 | 4,815 / 39.2% / Rs -400,363 / Rs -1,012,120 / Rs 423,510 | | |
| W5: skip when a 3+-turn 5-min level is within 0.25% ahead (lookback 5 sessions) | 3,181 / 45.3% / Rs 63,961 / Rs -1,111,079 / Rs 182,768 | 1,242 / 44.5% / Rs 94,656 / Rs -330,902 / Rs 54,298 | 3,597 / 39.4% / Rs -319,374 / Rs -830,149 / Rs 335,630 | 0.144 / 0.006 (q 0.019) | fail: unseen: worst month worse; unseen: the arm no longer makes money |
| W20: skip when a 3+-turn 5-min level is within 0.25% ahead (lookback 20 sessions) | 2,414 / 45.4% / Rs 26,861 / Rs -841,719 / Rs 164,922 | 980 / 45.2% / Rs 114,026 / Rs -259,662 / Rs 46,534 | 2,717 / 38.9% / Rs -231,720 / Rs -650,446 / Rs 245,762 | 0.011 / 0.000 (q 0.002) | fail: unseen: the arm no longer makes money |
| B5: skip unless the break crossed a 3+-turn 5-min level (lookback 5 sessions) | 1,812 / 44.1% / Rs -25,022 / Rs -676,425 / Rs 132,198 | 726 / 42.0% / Rs 12,494 / Rs -271,257 / Rs 61,033 | 2,074 / 40.6% / Rs -133,995 / Rs -484,619 / Rs 170,122 | 0.963 / 0.731 (q 1.000) | fail: design: the arm no longer makes money; wf: worst month worse; unseen: the arm no longer makes money; twin p 0.731 >= 0.05; BH q 1 > 0.1 |
| B20: skip unless the break crossed a 3+-turn 5-min level (lookback 20 sessions) | 2,357 / 43.6% / Rs -64,194 / Rs -879,418 / Rs 159,129 | 897 / 44.5% / Rs 37,054 / Rs -297,245 / Rs 59,537 | 2,658 / 41.8% / Rs -136,370 / Rs -580,369 / Rs 144,668 | 0.895 / 0.595 (q 1.000) | fail: design: worst month worse; design: the arm no longer makes money; wf: worst month worse; unseen: the arm no longer makes money; twin p 0.595 >= 0.05; BH q 1 > 0.1 |
| FX5: sell when a 5-min bar closes back through the 3+-turn 5-min level the break crossed (fake breakout) (lookback 5 sessions) | 4,219 / 42.7% / Rs 57,932 / Rs -1,314,940 / Rs 211,011 | 1,672 / 43.2% / Rs 134,743 / Rs -387,966 / Rs 87,634 | 4,825 / 37.8% / Rs -403,558 / Rs -1,000,891 / Rs 426,513 | 1.000 / 1.000 (q 1.000) | fail: wf: worst month worse; unseen: drawdown worse; unseen: worst month worse; unseen: the arm no longer makes money; twin p 1 >= 0.05; BH q 1 > 0.1 |
| FX20: sell when a 5-min bar closes back through the 3+-turn 5-min level the break crossed (fake breakout) (lookback 20 sessions) | 4,223 / 42.3% / Rs 68,485 / Rs -1,282,299 / Rs 199,635 | 1,671 / 43.3% / Rs 148,871 / Rs -389,408 / Rs 78,461 | 4,827 / 37.4% / Rs -402,642 / Rs -998,321 / Rs 424,580 | 1.000 / 1.000 (q 1.000) | fail: wf: worst month worse; unseen: drawdown worse; unseen: worst month worse; unseen: the arm no longer makes money; twin p 1 >= 0.05; BH q 1 > 0.1 |

**The finding: W20 is the strongest R51 lead in many studies, but it fails one pre-registered gate.**

W20 means: skip an R51 trade when a 5-minute level (3+ turns in the last 20 sessions) sits within 0.25% ahead of it.
- **On every period it lost much less than R51:**
  - design: losing days -Rs 8.4 lakh vs -Rs 13.5 lakh, drawdown Rs 1.65 lakh vs Rs 2.14 lakh;
  - Apr - Oct 2026: -Rs 2.6 lakh vs -Rs 4.0 lakh, drawdown Rs 46.5k vs Rs 81.7k, net Rs 1.14 lakh vs Rs 1.35 lakh;
  - unseen two years: -Rs 6.5 lakh vs -Rs 10.1 lakh, net -Rs 2.3 lakh vs -Rs 4.0 lakh, drawdown Rs 2.46 lakh vs Rs 4.24 lakh.
- **It skips better than chance:**
  - random skips of the same number of trades did as well only 1.1% of the time (Apr - Oct 2026);
  - never, in 2,000 tries (unseen; BH q 0.002).
- **Win rate barely moves:** 45.4% vs 44.5% (design), 45.2% vs 44.9%, 38.9% vs 39.2%. The gain is in skipping the
  trades that run into a wall, not in winning more often.
- **It fails "the arm still makes money in the unseen years".** That is only because R51 itself lost Rs 4 lakh there.
  This gate is R140 / R143's, and was pre-registered here unchanged, so W20 stays a NOTE.
  - Under CLAUDE.md it can act on paper only as Boss's exception, labelled "failed gates, Boss's exception". It skips
    trades only, so it lowers risk.
  - The cleaner route is a SHADOW log first.
  - Live: shadow only.
- **The other 5-minute rules are like the 15-minute ones:**
  - W5 helps less;
  - "only breaks through a level" (B5 / B20) is no better than random skipping;
  - the fake-breakout exits (FX5 / FX20) still lose. They sell fakes that would have recovered and lower the win rate
    by 1-2 points.
- **Note from the unseen years:** R51 itself lost Rs 4 lakh in Dec 2022 - Nov 2024 (39% wins). It only made money from
  Nov 2024. W20 halves that loss but does not fix it.

## Amendment C: the same 5-minute "wall ahead" skip on R72 Breadth Donchian (Boss: "What we add this in breath donchain")

- **Pre-registration:** commit `2eaca03`.
- **Design results:** committed in `e039701` before the other reads.
- **Deciding holdout:** R72's unseen two years, read once (`results/UNSEEN_READ_C`). The base reproduced R143: 3,395
  trades, -Rs 3,06,931.

Each cell: trades / win rate / net / losing days / max drawdown.

| rule | design Nov 2024 - Mar 2026 | Apr - Oct 2026 (2nd look) | UNSEEN Dec 2022 - Nov 2024 | twin p (2nd look / unseen) | verdict |
|---|---|---|---|---|---|
| R72 today | 3,071 / 46.8% / Rs 2,16,929 / -Rs 11.5 lakh / Rs 1.99 lakh | 1,202 / 45.8% / Rs 1,71,366 / -Rs 3.90 lakh / Rs 61.9k | 3,395 / 39.0% / -Rs 3,06,931 / -Rs 8.87 lakh / Rs 3.38 lakh | | |
| W20-72 (20-session 5-min levels) | 1,569 / 49.0% / Rs 1,66,877 / -Rs 6.15 lakh / Rs 1.14 lakh | 610 / 46.9% / Rs 1,29,781 / -Rs 1.95 lakh / Rs 47.5k | 1,718 / 38.3% / -Rs 1,72,937 / -Rs 5.07 lakh / Rs 1.81 lakh | 0.006 / 0.35 | fail |
| W5-72 (5-session) | 2,216 / 47.5% / Rs 1,99,111 / -Rs 8.59 lakh / Rs 1.80 lakh | 828 / 46.1% / Rs 1,41,389 / -Rs 2.59 lakh / Rs 75.2k | 2,463 / 38.5% / -Rs 2,62,863 / -Rs 6.95 lakh / Rs 2.81 lakh | 0.008 / 0.59 | fail |

**Short answer: on Breadth Donchian the rule does not hold up.**
- **It skips about half of R72's trades and halves its losses, but in the unseen years random skips of the same number
  did as well** (p 0.35). On R72 the gain comes from trading less, not from avoiding the right trades.
- **On R51 it was different:** better than random in the unseen years (p < 0.001).
- **Win rate rises 1-3 points in the recent periods** (49.0% vs 46.8% design; 51.8% vs 49.0% walk-forward), but falls
  in the unseen years (38.3% vs 39.0%).
- **It also fails:** the walk-forward's worst day got worse (-Rs 28,317 vs -Rs 25,837), and R72 itself lost money in
  the unseen years.
- **Nothing changes for R72.**

## Amendment D: pivot points, EMA, VWAP and volume profile ON TOP of R51 + W20 (Boss: "will this help more" / "on top of this")

Pre-registered in `bdfa037`, design committed in `b583cb1` before the other reads. The unseen two years are a SECOND look (already read for W20). Each cell: trades / win rate / net / losing days / max drawdown.

| on top of R51 + W20 | design Nov 2024 - Mar 2026 | Apr - Oct 2026 | Dec 2022 - Nov 2024 | twin p (Apr-Oct / Dec 22-Nov 24) | verdict |
|---|---|---|---|---|---|
| R51 + W20 (the base) | 2,414 / 45.4% / Rs 26,861 / -Rs 841,719 / Rs 164,922 | 980 / 45.2% / Rs 114,026 / -Rs 259,662 / Rs 46,534 | 2,717 / 38.9% / Rs -231,720 / -Rs 650,446 / Rs 245,762 | | |
| D1 pivot points (previous day's P, R1, S1, R2, S2 also count as walls) | 1,648 / 46.2% / Rs 53,453 / -Rs 669,601 / Rs 145,762 | 666 / 43.2% / Rs 41,717 / -Rs 230,320 / Rs 54,996 | 1,756 / 38.8% / Rs -146,061 / -Rs 485,123 / Rs 163,469 | 0.56 / 0.052 | fail: design: worst day worse; wf: worst day worse; wf: worst month worse; unseen: the arm no longer makes money; twin p 0.052 >= 0.05; BH q 0.104 > 0.1 |
| D2 EMA (close on the trade's side of the 20-bar 5-min EMA) | 2,414 / 45.4% / Rs 26,861 / -Rs 841,719 / Rs 164,922 | 980 / 45.2% / Rs 114,026 / -Rs 259,662 / Rs 46,534 | 2,717 / 38.9% / Rs -231,720 / -Rs 650,446 / Rs 245,762 | - | no effect: R51's break already closes at a 20-bar high above its EMA |

Descriptive only (futures minute volume exists for 52 of these sessions, 29 Jul - 13 Oct 2026): R51 + W20 made 316 trades, Rs 60,288. **D3 VWAP** (skip a call below the future's session VWAP, a put above): 295 trades, Rs 37,599. **D4 volume profile** (previous session's point of control and value-area edges as extra walls): 277 trades, Rs 47,355. Both made less.

**Short answer: no, they do not help more.** Pivot points looked good in Nov 2024 - Mar 2026 (net Rs 53k vs Rs 27k) but in Apr - Oct 2026 they cut the net from Rs 1.14 lakh to Rs 42k with a bigger drawdown and a lower win rate (43.2% vs 45.2%); in the old two years they lost less (-Rs 1.46 lakh vs -Rs 2.32 lakh) but only at p 0.052 and the worst day got worse. EMA changes nothing (it never disagrees with a Donchian-20 break that Supertrend confirms). VWAP and the volume profile, on the only 52 sessions with futures volume, both lowered the net. Each of these was also tested ALONE on R51 before (R73, R51-r29 / r41, R105D, R63 / R73 / R74) and failed. The lead stays W20 alone.
