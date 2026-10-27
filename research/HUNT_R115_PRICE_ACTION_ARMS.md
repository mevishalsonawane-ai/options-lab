# R115: can price action help our paper arms?

27 Oct 2026. Boss: *"Check if price action can help us in any of our strategies."* Pre-registration:
`research/hunt/r115_price_action_arms/PREREG.md` (pushed in `1a4f8a4` before any number). Research only; nothing in the
app changes. **Signal source of every rule: the index's own bars** (5-minute bars for the Donchian arms, the Liquidity
book's own 15 / 5-minute bar, the 09:15-09:19 index bar for OOV). No rule reads an option's chart; the arms still buy
their 1-ITM weekly option with today's premium stops.

## The answer first

**No. None of the 12 new price-action rules passes, so nothing goes on paper and nothing goes live.** Price action had
already been tested very widely here (about 30 studies, table below). R115 tried the gaps that were left on our four
paper arms (R72 Breadth Donchian, R51 Donchian 20 + Supertrend, R52 Open outside value, Liquidity 15+5):

- **Strong break bar** (big body, close near the high for a call): **hurts.** It throws out about two thirds of the
  09:20 opening breaks, where the arms make their money (R72: 594 opening trades became 215 and their design net fell
  from Rs 1,78,533 to Rs 58,987). R72's final fell from Rs 1,71,366 to Rs 1,28,969 (856 vs 1,202 trades).
- **No outside-bar break** on R72: **the closest lead, but not proven.** Ahead in design (Rs 2,42,560 vs Rs 2,16,929),
  level in the walk-forward year (Rs 3,36,753 vs Rs 3,35,882: only Rs 871 more) and ahead in the final (Rs 1,98,540
  vs Rs 1,71,366, 1,175 vs 1,202 trades), worst day and drawdown no worse. It touches few trades (8% of R72's trades in design, 4% in the final; the
  54 final trades it dropped lost Rs 20,138 under today's rules). Random twin p 0.026: even counting R115's 12 tests alone that is q 0.13 (gate
  0.10); counting all earlier tests, q = 1. On R51 the same rule lost in the walk-forward year.
- **Fresh break only** (yesterday's high / low not already taken earlier today): ahead in design and final on both
  Donchian arms, but **behind in the walk-forward year** on both (R72 Rs 2,85,866 vs Rs 3,35,882). Fails.
- **Compression first** (yesterday an NR4 or inside day): **hurts** on both arms (R72 final Rs 50,926 vs
  Rs 1,71,366). The days it skips made money.
- **Failed-break exit** (sell when a 5-minute index bar closes back inside the channel): R51 made more in all three
  periods (+Rs 41,867 design, +Rs 2,454 walk-forward, +Rs 8,781 final), but selling those same trades **at a random minute** made far more (twin mean Rs 3.29 lakh vs the
  rule's Rs 1.44 lakh in the final), so the "failed break" moment carries no information. R72 made less in the
  walk-forward year and the final. Fails.
- **Liquidity and OOV strong bar**: both lose in design and walk-forward. Fails.

**What price action does NOT do here:** it does not pick better breaks. The arms' edge is the 09:20 opening break
itself (R113), and every bar-shape filter either leaves those trades alone (and then changes little) or removes them
(and then loses money).

**One lead to watch (reported, not a test):** the strong-bar filter **with the 09:20 break exempt** (only later breaks
need a strong bar) was ahead in all three periods on both Donchian arms (R72: design Rs 3,26,477 vs Rs 2,16,929,
walk-forward Rs 3,73,707 vs Rs 3,35,882, final Rs 2,11,182 vs Rs 1,71,366). But its final worst day (-Rs 27,945 vs
-Rs 22,571) and drawdown (Rs 74,938 vs Rs 61,884) were worse, so it would have failed the gate anyway. It was written
down before the run but not gated, so it can only be a new pre-registered test on fresh paper days, never a change now.

**The bar was very high, on purpose.** Boss asked to count every earlier test (R1-R114 and every other family): 9,300,034
logged tests, so a rule needed a twin p of about 1 in 100 million. No rule here would pass even counting R115's 12
tests alone (best q 0.13). The final window (1 Apr - 13 Oct 2026) has been read by many studies before, so it is weak
evidence anyway.

## What was already tested (and failed), so not re-tested

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


Two price-action ideas were deliberately **not** run:
- **Retest entry** (wait for the pullback to the broken level). Every way of entering later than the break has already
  lost on these arms' own breaks: R73's 2 / 3 / 5-minute waits, R79's 10-candle wait on Liquidity, R22's break-and-retest,
  R104's retest rules. It would also move almost every 09:20 entry.
- **Failed-break exit on Liquidity.** It is already in today's Liquidity rules (a book bar closing back through the level).

## How it was tested

- The arms' own backtests, unchanged: R101's candidates (R51 with its Supertrend filter, as on paper), R64 / R106 engine,
  real 1-minute option prints from the Drive lake (copied 27 Oct), R17 fills and slippage, dated Zerodha charges, 1 lot.
  Today's rules reproduce every published figure exactly (R72 3,071 / Rs 2,16,929 design, 1,202 / Rs 1,71,366 final;
  R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743; R52 706 / Rs 2,90,573 and 293 / Rs 48,612; Liquidity 881 /
  Rs 2,57,249 and 357 / Rs 11,155).
- Periods: design 20 Nov 2024 - 31 Mar 2026; walk-forward year 1 Apr 2025 - 31 Mar 2026 (no rule has a parameter to
  fit; every threshold was fixed in the pre-registration); final 1 Apr - 13 Oct 2026, read once (`results/FINAL_READ`).
- Filters drop a signal before the arm's own walk, so a dropped signal frees its place exactly as on the phone.
- Twins (2,000 each, final period): filters vs dropping the same number of signals at random; the exit vs selling the
  same trades at a random minute between the fill and today's exit.
- Gates (all needed): more net in design, walk-forward and final; worst day and drawdown not worse; twin BH q <= 0.10
  over 9,300,046 tests; >= 40 final trades and >= 30 that differ.
- Look-ahead guard (`test_r115.py`): every filter is re-checked after changing all later bars; the exit can never sell
  before the next 5-minute bar has closed.

## The new tests vs today's rules

n = trades; "win" = share of trades that made money; nets after charges, 1 lot.

### Main table: each rule vs today's rules (trades / win rate / net, 1 lot, after charges)

| rule | design | walk-forward | final | final worst day | final max DD | twin p | q (all 9.3M) | q (R64+ count) | verdict |
|---|---|---|---|---:|---:|---:|---:|---:|---|
| Q72 R72 Breadth Donchian: strong break bar (body >= 60% of range, close in the top / bottom 25% the trade's way) | 2,326 / 47% / Rs 190,456 | 1,663 / 49% / Rs 269,148 | 856 / 45% / Rs 128,969 | -Rs 27,945 | Rs 76,194 | 0.475 | 1 | 1 | fail |
| Q51 R51 Donchian 20 + Supertrend: strong break bar (body >= 60% of range, close in the top / bottom 25% the trade's way) | 3,530 / 45% / Rs 211,192 | 2,521 / 46% / Rs 270,141 | 1,346 / 44% / Rs 91,917 | -Rs 18,795 | Rs 76,621 | 0.454 | 1 | 1 | fail |
| O72 R72 Breadth Donchian: no outside-bar break | 2,941 / 47% / Rs 242,560 | 2,157 / 49% / Rs 336,753 | 1,175 / 46% / Rs 198,540 | -Rs 22,571 | Rs 47,472 | 0.0265 | 1 | 1 | fail |
| O51 R51 Donchian 20 + Supertrend: no outside-bar break | 4,115 / 44% / Rs 52,181 | 2,979 / 46% / Rs 190,282 | 1,641 / 45% / Rs 153,428 | -Rs 21,920 | Rs 72,365 | 0.109 | 1 | 1 | fail |
| F72 R72 Breadth Donchian: fresh break only (previous session's high / low not already taken earlier today) | 2,027 / 48% / Rs 225,219 | 1,508 / 50% / Rs 285,866 | 838 / 50% / Rs 190,945 | -Rs 20,218 | Rs 41,821 | 0.033 | 1 | 1 | fail |
| F51 R51 Donchian 20 + Supertrend: fresh break only (previous session's high / low not already taken earlier today) | 3,019 / 45% / Rs 112,715 | 2,194 / 47% / Rs 195,587 | 1,212 / 47% / Rs 162,540 | -Rs 16,355 | Rs 48,026 | 0.0235 | 1 | 1 | fail |
| N72 R72 Breadth Donchian: previous session an NR4 or inside day (keep only) | 900 / 47% / Rs 136,369 | 638 / 50% / Rs 152,979 | 392 / 46% / Rs 50,926 | -Rs 20,728 | Rs 33,094 | 0.822 | 1 | 1 | fail |
| N51 R51 Donchian 20 + Supertrend: previous session an NR4 or inside day (keep only) | 1,281 / 45% / Rs 65,952 | 890 / 47% / Rs 110,898 | 529 / 45% / Rs 35,735 | -Rs 21,578 | Rs 40,074 | 0.609 | 1 | 1 | fail |
| X72 R72 Breadth Donchian: exit when a 5-min index bar closes back inside the channel | 3,089 / 40% / Rs 256,465 | 2,250 / 42% / Rs 332,132 | 1,211 / 40% / Rs 161,741 | -Rs 21,629 | Rs 57,887 | 1 | 1 | 1 | fail |
| X51 R51 Donchian 20 + Supertrend: exit when a 5-min index bar closes back inside the channel | 4,245 / 37% / Rs 92,292 | 3,071 / 38% / Rs 216,202 | 1,687 / 38% / Rs 143,524 | -Rs 18,094 | Rs 52,494 | 1 | 1 | 1 | fail |
| QL Liquidity 15+5: strong break bar (body >= 60% of range, close in the top / bottom 25% the trade's way) | 520 / 47% / Rs 184,095 | 378 / 49% / Rs 181,997 | 189 / 41% / Rs 30,194 | -Rs 5,054 | Rs 23,773 | 0.126 | 1 | 1 | fail |
| QO R52 Open outside value: strong break bar (body >= 60% of range, close in the top / bottom 25% the trade's way) | 117 / 60% / Rs 86,160 | 89 / 61% / Rs 97,497 | 39 / 56% / Rs 14,501 | -Rs 13,726 | Rs 15,649 | 0.306 | 1 | 1 | fail |

Today's rules, for comparison:

| arm | design | walk-forward | final | final worst day | final max DD |
|---|---|---|---|---:|---:|
| R72 Breadth Donchian | 3,071 / 47% / Rs 216,929 | 2,238 / 49% / Rs 335,882 | 1,202 / 46% / Rs 171,366 | -Rs 22,571 | Rs 61,884 |
| R51 Donchian 20 + Supertrend | 4,208 / 44% / Rs 50,425 | 3,044 / 46% / Rs 213,748 | 1,670 / 45% / Rs 134,743 | -Rs 21,578 | Rs 81,683 |
| R52 Open outside value | 706 / 56% / Rs 290,573 | 529 / 57% / Rs 247,057 | 293 / 52% / Rs 48,612 | -Rs 16,554 | Rs 47,194 |
| Liquidity 15+5 | 881 / 44% / Rs 257,249 | 657 / 44% / Rs 238,064 | 357 / 37% / Rs 11,155 | -Rs 11,405 | Rs 61,062 |

### The 09:15-09:30 entries under each rule (trades / win / net) vs today's

| rule | design: rule | design: today's | final: rule | final: today's |
|---|---|---|---|---|
| Q72 | 215 / 52% / Rs 58,987 | 594 / 54% / Rs 178,533 | 89 / 63% / Rs 70,555 | 314 / 56% / Rs 156,832 |
| Q51 | 361 / 53% / Rs 133,975 | 873 / 51% / Rs 194,141 | 130 / 59% / Rs 77,571 | 400 / 54% / Rs 163,005 |
| O72 | 551 / 56% / Rs 205,708 | 594 / 54% / Rs 178,533 | 298 / 56% / Rs 141,968 | 314 / 56% / Rs 156,832 |
| O51 | 786 / 53% / Rs 225,194 | 873 / 51% / Rs 194,141 | 373 / 54% / Rs 163,469 | 400 / 54% / Rs 163,005 |
| F72 | 587 / 54% / Rs 186,015 | 594 / 54% / Rs 178,533 | 311 / 56% / Rs 158,510 | 314 / 56% / Rs 156,832 |
| F51 | 865 / 51% / Rs 197,213 | 873 / 51% / Rs 194,141 | 393 / 54% / Rs 168,673 | 400 / 54% / Rs 163,005 |
| N72 | 171 / 58% / Rs 95,034 | 594 / 54% / Rs 178,533 | 110 / 57% / Rs 41,474 | 314 / 56% / Rs 156,832 |
| N51 | 268 / 53% / Rs 87,957 | 873 / 51% / Rs 194,141 | 143 / 54% / Rs 29,537 | 400 / 54% / Rs 163,005 |
| X72 | 594 / 51% / Rs 168,044 | 594 / 54% / Rs 178,533 | 314 / 55% / Rs 144,002 | 314 / 56% / Rs 156,832 |
| X51 | 873 / 46% / Rs 163,492 | 873 / 51% / Rs 194,141 | 400 / 52% / Rs 162,541 | 400 / 54% / Rs 163,005 |
| QL | 80 / 57% / Rs 53,754 | 180 / 53% / Rs 130,838 | 29 / 55% / Rs 16,241 | 81 / 46% / Rs 1,017 |
| QO | 117 / 60% / Rs 86,160 | 706 / 56% / Rs 290,573 | 39 / 56% / Rs 14,501 | 293 / 52% / Rs 48,612 |
| Q72e | 588 / 54% / Rs 171,175 | 594 / 54% / Rs 178,533 | 313 / 56% / Rs 155,535 | 314 / 56% / Rs 156,832 |
| Q51e | 853 / 51% / Rs 196,403 | 873 / 51% / Rs 194,141 | 394 / 54% / Rs 171,687 | 400 / 54% / Rs 163,005 |

### What each filter's dropped signals did under today's rules

| rule | design | final |
|---|---|---|
| Q72 | 1,176 / 46% / Rs 11,974 | 505 / 45% / Rs 49,755 |
| Q51 | 1,605 / 43% / -Rs 102,594 | 712 / 43% / Rs 41,077 |
| O72 | 245 / 40% / -Rs 18,323 | 54 / 31% / -Rs 20,138 |
| O51 | 391 / 42% / -Rs 27,681 | 122 / 39% / -Rs 18,601 |
| F72 | 1,044 / 45% / -Rs 8,289 | 364 / 37% / -Rs 19,579 |
| F51 | 1,189 / 42% / -Rs 62,290 | 458 / 40% / -Rs 27,797 |
| N72 | 2,171 / 47% / Rs 80,561 | 810 / 46% / Rs 120,439 |
| N51 | 2,927 / 44% / -Rs 15,526 | 1,141 / 45% / Rs 99,008 |
| QL | 364 / 38% / Rs 86,730 | 168 / 33% / -Rs 19,039 |
| QO | 589 / 55% / Rs 204,413 | 254 / 51% / Rs 34,111 |
| Q72e | 784 / 42% / -Rs 117,103 | 277 / 38% / -Rs 34,784 |
| Q51e | 1,053 / 39% / -Rs 206,320 | 428 / 39% / -Rs 51,476 |

### Reported only (not gated): the strong-bar rule with the 09:20 break exempt

| rule | design | walk-forward | final |
|---|---|---|---|
| Q72e R72 Breadth Donchian: strong break bar, the 09:20 break exempt | 2,585 / 48% / Rs 326,477 | 1,866 / 50% / Rs 373,707 | 1,029 / 48% / Rs 211,182 |
| Q51e R51 Donchian 20 + Supertrend: strong break bar, the 09:20 break exempt | 3,760 / 46% / Rs 229,485 | 2,692 / 47% / Rs 294,548 | 1,484 / 45% / Rs 154,490 |

### Gate failures

- **Q72**: design: net not above today's; wf: net not above today's; wf: worst day worse; wf: drawdown worse; final: net not above today's; final: worst day worse; final: drawdown worse; BH q 1 > 0.1
- **Q51**: final: net not above today's; BH q 1 > 0.1
- **O72**: BH q 1 > 0.1
- **O51**: wf: net not above today's; wf: worst day worse; wf: drawdown worse; final: worst day worse; BH q 1 > 0.1
- **F72**: wf: net not above today's; BH q 1 > 0.1
- **F51**: wf: net not above today's; wf: drawdown worse; BH q 1 > 0.1
- **N72**: design: net not above today's; wf: net not above today's; final: net not above today's; BH q 1 > 0.1
- **N51**: wf: net not above today's; final: net not above today's; BH q 1 > 0.1
- **X72**: wf: net not above today's; final: net not above today's; BH q 1 > 0.1
- **X51**: BH q 1 > 0.1
- **QL**: design: net not above today's; wf: net not above today's; BH q 1 > 0.1
- **QO**: design: net not above today's; wf: net not above today's; final: net not above today's; BH q 1 > 0.1; < 40 final trades


## What can go on paper

**Nothing.** No rule passed, so per CLAUDE.md every result stays a note (`research/experiments/entries/r115-*.yaml`,
all "rejected"). Nothing goes live from R115.

If Boss wants to keep watching price action, the only honest next step is a **new pre-registered forward read** on paper
days after 13 Oct (not in this session's lake) of two leads: "no outside-bar break" on R72, and "strong bar required only
after 09:30" on R72 / R51. Both are leads, not results.

## Limits

- The final window has been read by R51-R114 already; only paper days after 13 Oct are fresh.
- The exit twin keeps the rest of the book as it was (it does not re-walk the knock-on effect of an earlier exit freeing a
  side), as in R106 / R112 / R114.
- The normal-tail p (used only when no twin beats a rule) never came into play: every rule had twins at or above it.
- Each arm is tested alone (its own walk), not inside the phone's shared cap of 2 positions a direction (R108's book).

## Files

- `research/hunt/r115_price_action_arms/PREREG.md` (with the full inventory), `r115cfg.py`, `r115lib.py` (pure),
  `r115study.py` (design / final / decide), `r115report.py` (these tables and the entries), `test_r115.py`.
- `results/design.json`, `results/final.json`, `results/decision.json`, `results/FINAL_READ`.
- Re-run: `R51_CLEAN=1 python research/hunt/r51/r51data.py build`, then `python
  research/hunt/r115_price_action_arms/r115study.py design | final | decide` (the final refuses a second read).
