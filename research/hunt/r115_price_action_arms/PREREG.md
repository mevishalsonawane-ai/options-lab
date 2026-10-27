# R115 pre-registration: can price action help our paper arms?

Written 27 Oct 2026, **before any R115 number was computed** (committed and pushed alone on
`claude/r115-price-action-arms` ahead of the code's first run). Boss, 27 Oct: *"Check if price action can help us in any
of our strategies."* Research only; nothing in `android/**` changes.

**Signal source of every rule: the index's own 5-minute (or the Liquidity book's 15 / 5-minute) bars, built from the
lake's index minutes.** No rule reads an option's chart. The arms keep buying their 1-ITM weekly option; stops and locks
stay on the premium as today.

## 1. What price action was already tested (not re-tested here)

Surveyed from the reports, their PREREGs / code and `research/experiments/entries/` (2,126 entries). "Arm" = R72 Breadth
Donchian, R51 Donchian 20 + Supertrend, R52 Open outside value (OOV), Liquidity 15+5 (LIQ). Every row failed its gates
unless it says otherwise.

| study | price action tested | on | n | verdict, key reason |
|---|---|---|---|---|
| R71 chart patterns | inside bar, 15-min NR7/NR4, squeeze, flag, double top/bottom, Heikin-Ashi flip, 3-bar reversal, swing break | standalone, 5 indices | 16 tests (P1 1,075 final trades -Rs 1,23,667; P2 1,341, -Rs 1,68,820) | 0 of 16; daily NR4/NR7, inside day, ORB, PDH/PDL, gaps already rejected before it |
| R85 MCX candles | 11 shapes x 22 contexts (marubozu, hammer, engulfing, inside/outside, first PDH break) + 4 rules | standalone MCX | 7,564 cells; rules 28-80 final trades | 0 of 4; all lose Rs 17k-30k in the final |
| R87 candle anatomy | 23 single shapes, 12 multi-candle (inside, outside, engulfing, harami...), stop-run footprints, 30 contexts | atlas + 3 rules | 4,771 cells | 0 cells pass; rules fail |
| R87B | 30m / 60m / daily shapes, sequences; exits on a rebuilt plain Donchian 20: big wick / marubozu / engulfing AGAINST | atlas; exits on Donchian / ORB | 369 cells; 1,722 Donchian trades | engulfing-against exit -Rs 29,944 (q 0.13); marubozu-against -Rs 14,257 |
| R62, R62B | volume-profile POC retest, order blocks, FVG/LVN pullbacks, BOS/ChoCH, MSB + order block | standalone | 32 + 64 + 16 | 0 pass; coin flip |
| R22 | 09:15-09:59 range as S/R: reject, break, hold, fail-back-inside, **break-and-retest** | standalone | 1,211 touches; retest 295 / 100 trades | 0 of 6; retest -Rs 20,547 design / -Rs 6,095 holdout |
| R20B / R20C / R23 / R89 | PDH/PDL/pivots as range or break confirmation; 1 vs 2 closes; 28 ORB-break features (break strength, earlier tests, first-candle body and colour, inside day, beyond PDH/PDL); 1-min PDH/PDL breaks | ORB / standalone | 7,548 breaks; 1,242 ORB breaks; 180 cells | 0 pass; candle body / colour / inside day AUC 0.48-0.50 |
| R28B | body/range, close location, wick-against of the 30 one-minute bars before the decision | filter on ORB, LIQ BANKNIFTY, others | 378 | 0 of 378 |
| R80 Liquidity entries | old pool, stacked zones, round number, clean pool, **break also beyond PDH/PDL**, first hour, **break bar body >= 1.5x the mean body of 20 bars**, caps | filter on LIQ | 10 | 0 of 10; body V8 final +Rs 3,708 (87 trades), p 0.30 |
| R81 Liquidity exits | stop at the break bar's far side, chandelier, no-progress, re-entry on a 5-min re-close, breakeven after 0.5 ATR past the level | exits on LIQ | 66 | 0 pass |
| R73 trend / wait | EMA, 15m / 1h EMA rising, ADX, **Supertrend (passed, adopted)**, VWAP side, **waits of 2 / 3 / 5 one-minute candles** | filter on R51 (+R72) | 72 | only Supertrend passed; every wait lost in design ("the profit is in the first few minutes") |
| R75 Donchian less loss | loss caps, index drop, time, **skip after a narrow opening range**, **skip the day after a narrow day**, skip big gaps, strike, size | R51 + Supertrend, R72 | 40 | 0 of 40; skipping the day after a narrow day cut R72's final to Rs 71,556 (vs Rs 1,71,366) |
| R101 arms enhance | breadth, VIX, gap within 0.5%, **not the 09:20 break**, entries by 12:00, OOV far-open, 3 indices open outside | all 4 arms | 20 | 0 of 20; dropping 09:20 breaks cost R72 Rs 1.49 lakh in design |
| R104 F3 (books) | 82 bar / chart-pattern rules: hikkake, outside bar, engulfing, inside bar, NR7, ID/NR4, pin, reversal bars, retests | standalone | 82 | none passes (q 0.81-1.0) |
| R104 F11 + N (books on the arms) | 20 overlays (Woods, Trader Tom, Porwal, Aziz, Wyckoff, Carli: swing-low trail, "no new extreme" exit, next-level target, prior 60-min bar trail, gap-window skip, 3-day swing bias...) + absorption on the 09:20 bar | all 4 arms | 26 | 0 pass; wyckoff-08 swing-low trail a lead only |
| R105 Donchian give-back | premium locks; **1-minute index close back through the breakout level (X7)**; X7 armed at +10% | exits on R72, R51 + Supertrend | 20 | 0 of 20; R72 X7 walk-forward Rs 3,12,521 vs 3,35,882, final 1,40,319 vs 1,71,366 |
| R105B direction | open beyond PDH/PDL, weekly / daily / 60-min structure, prior-day trend, 09:15-09:29 candle colour | all 4 arms | 40 | 0 of 40 (09:15 candle colour on R51 q 0.29) |
| R105C-F | small targets, signal-bar futures volume, option liquidity, OI | R72, R51 | 52 | 0 pass |
| R110 / R111 IraThinker | ORB alert, gap fade, mom60 (the SMC panel has no entry rule) | standalone | 23 | 0 pass |
| R113 time of day | entry-time cuts, gaps, day shape | all arms | 11 | 0 of 7 run; **09:15-09:30 entries made Rs 11.6 lakh of Rs 11.8 lakh** |
| R63, R84, R72, R79, R86, R61B, R88, R90, R91, H25, H5, F1, R96 | 5-min Donchian-10 opposite-break exit; positions by break size in ATR; index stop at the channel; 10-candle wait; failed-poke fades; rejection wick; CHoCH/BOS; SMC sweep + strong 5-min candle (body >= 60%); 61 TA-Lib candles; NR4/NR7 features | arms / standalone | - | all failed (R96 MCX crude NR7 a lead only) |

### What is NOT yet tested on the four arms (the gap R115 fills)

- (a) the **break bar's own quality** (body share and where it closes) on R72, R51, R52 and LIQ;
- (b) the break bar being an **outside bar** (on any arm);
- (c) **prior day's high / low already taken out earlier in the session** before a Donchian break;
- (d) **compression before the break**: the previous day an NR4 or inside day, as a *keep-only* rule (R75 tested only the
  opposite "skip after a narrow day", and it hurt: so this rule is **partly data-snooped**; said now);
- (e) a **5-minute-close failed-break exit** on R72 / R51 (R105 X7 used 1-minute closes; Liquidity already exits on a
  failed break today, so it is not tested there).

**Not tested, with the reason:** a **retest entry** (wait for the pullback to the broken level). It is new on the arms in
that exact form, but every way of entering later than the break has already failed on these arms' own breaks: R73's 2 / 3
/ 5-candle waits all lost in design (the same breakouts made Rs 9.55 lakh at the normal entry and -Rs 66,989 five
minutes later), R79's 10-candle wait on Liquidity (Rs 15,268 vs Rs 79,808), R22's break-and-retest, R104's woods-19 /
sadowski-06 / atb-06..11 retests. It would also move almost every 09:20 entry (R113: where the money is). It is left out
so the family stays small and honest; it is reported in the write-up as "not tested, evidence against".

## 2. Data, engine, arms (identical to R101 / R106 / R113)

- Drive lake `gdrive:IraAlgo-data-lake` (index_1m, index_daily, options_rolling_1m WEEK), copied 27 Oct; R51's Amendment A
  clean stores (`R51_CLEAN=1`, to 13 Oct), R101's units (candidates priced once on R64's engine, R17 fills, dated Zerodha
  charges, 1 lot), R106's walk (`run_rule(arm, units, priced, None)` = today's rules).
- Arms: R72 (R101), R51 = R101's Donchian 20 kept only when Supertrend (10, 3) agrees (R73 T7, as on paper; R112's
  `r51_st_signals`), R52 (R101, exits R64 A4 + L25), LIQ (R101, BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5).
- **Parity first (asserted):** today's rules reproduce the published trades and net: R72 design 3,071 / Rs 2,16,929,
  final 1,202 / Rs 1,71,366; R51 + Supertrend 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743; R52 706 / Rs 2,90,573 and
  293 / Rs 48,612; LIQ 881 / Rs 2,57,249 and 357 / Rs 11,155.
- Bars: each index's continuous 5-minute bars `fam_r44.bars5` (what R72 / R51 signal on). A Donchian candidate with entry
  row `er` has signal bar b = er / 5 - 1 of its day; its channel is the 20 bars before it (high / low), the "broken level"
  is that channel's high (call) or low (put).

## 3. The family (12 tests; fixed now, nothing tuned)

Filters drop candidates **before** the arm's own walk (a dropped signal frees its place, as R101). An unknown input
(missing bar, zero range) never confirms: the candidate is dropped.

| id | arm | rule (on the index bars only, nothing after the signal bar's close) |
|---|---|---|
| Q72 | R72 | **Strong break bar**: body >= 60% of the bar's range AND the close in the top 25% of the range for a call (bottom 25% for a put) AND the bar's colour is the trade's way |
| Q51 | R51 | the same |
| O72 | R72 | **No outside-bar break**: drop the signal when the break bar is an outside bar (high above AND low below the previous 5-min bar's) |
| O51 | R51 | the same |
| F72 | R72 | **Fresh break only**: drop a call if, earlier the same session (bars before the signal bar), the index already traded above the previous session's high; a put if it already traded below the previous session's low. A day's first bar is always fresh, so **09:20 entries are never dropped** |
| F51 | R51 | the same |
| N72 | R72 | **Compression first**: keep only signals on days whose previous session was an NR4 (its high-low range the smallest of the last 4 sessions) or an inside day (its high <= and low >= the session before's). Partly snooped (R75 L4-PD) |
| N51 | R51 | the same |
| X72 | R72 | **Failed-break exit (5-min close)**: after the fill, the first 5-minute index bar of the same day that closes back inside the channel (below the broken level for a call, above it for a put) sells the option at the next minute's open (the bar's end row); today's exits stay on and whichever comes first wins |
| X51 | R51 | the same |
| QL | LIQ | **Strong break bar** (as Q72) on the Liquidity book's own breaking bar (the 15- or 5-minute bar that ends at the signal, rebuilt from the index minutes) |
| QO | R52 | **Strong first bar** (as Q72) on the 09:15-09:19 bar that gives OOV its signal, in the OOV side's direction |

Reported, never gated (fixed now): each filter's complement (what the dropped trades did); the two Q rules with the 09:20
break exempt (to show what the filter does after 09:30 alone).

## 4. Periods

Design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design; every rule is fixed, so
"walk-forward" means the out-of-sample year of R101 / R106: no parameter is fitted at any point); final 1 Apr - 13 Oct
2026, read once by R115 (`results/FINAL_READ`), **not fresh** (R51-R114 read it). Paper days after 13 Oct are not in
this session's stores; not read.

## 5. Gates (all must hold; never relaxed)

1. **Net** above today's rules in design AND the walk-forward window AND the final.
2. **Worst day and max drawdown** (daily P&L, 1 lot) not worse than today's rules in design AND walk-forward AND final.
3. **Twin**, final period, 2,000 twins (seed 115), p = (1 + twins >= the rule's net) / 2,001:
   - filters: drop as many of the arm's candidates **at random** as the rule dropped, walk the arm again (R101);
   - exits: every trade the rule sold earlier than today's exit is sold instead at the open of a uniformly random minute
     strictly after its fill and before today's exit (R106 / R112 / R114); the rest of the trades as they are.
   When no twin reaches the rule (p at its floor 1/2,001), p is taken from the normal tail of the twins (mean, standard
   deviation) instead, so a very strong rule can show a p below the Monte-Carlo floor (a normal approximation; said as a
   limit).
4. **Benjamini-Hochberg q <= 0.10** with **m = 9,300,034 earlier tests + R115's 12 = 9,300,046**: every family logged in
   `research/experiments/entries/` up to 26 Oct 2026 (R1-R114, the H-hunts, B / F / book / crypto / MCX families...),
   each counted as the larger of its entry count and its summed `variants_tried`, the earlier tests taken as p = 1 (none
   passed). This is Boss's "count ALL families" rule; it means a passer needs p of about 1e-8. **Also reported, not a
   gate:** q with the repo's usual R64+ count (5,841 + 12).
5. At least 40 final trades in the rule's book and at least 30 final trades that differ from today's.

**Every rule's effect on the 09:15-09:30 entries is reported separately** (trades, win rate, net vs today's), in design
and final, so no filter can quietly remove the opening breaks.

Reported for every rule and period: trades, win rate, net, average win / loss, worst day, max drawdown, months at or
below -Rs 13,000.

## 6. What a pass means (CLAUDE.md)

A pass may go on PAPER for its arm only: a labelled switch, logged in the Autopilot change timeline, undoable in one tap,
shown to Boss, the plain-rules record kept separable. R115 changes no app code; that would be a separate change. Nothing
goes live from R115. Every test, pass or fail, gets an entry in `research/experiments/entries/r115-*.yaml`.
