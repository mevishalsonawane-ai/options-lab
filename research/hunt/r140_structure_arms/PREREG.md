# R140 pre-registration: can higher-high / lower-low structure make our paper arms LOSE LESS?

Written 31 Oct 2026, before any R140 number. Research only: nothing in `android/**` changes.

Boss, 31 Oct:
- *"Will the higher high and lower low, and equity shift work for our existing strategies"*;
- *"yes run it as R140"*;
- *"profit is not important, losing money is important, we should always check how could we lose less"*.

**So R140's goal is fewer losses, not more profit.** Signal source: the index for every rule (15- and 5-minute
wick swings). The arms keep buying their own options with their own stops and locks.

## 1. What was already tested, and what is left

- **R105B:** daily, 60-minute and weekly structure as direction filters on all four arms: 0 of 40 passed.
- **R115:** price-action filters; **R101:** VIX, breadth and others; **R104:** a Wyckoff swing-low trail, a lead
  only; **R105 X7:** a 1-minute failed-break exit. All failed.
- **R139:** market-structure rules lost on their own; a 60-minute trend filter hurt S3.
- **Left, and tested here:**
  1. the **15-minute and 5-minute** Dow trend as a skip-if-against filter;
  2. the **structure-shift exit**: sell when the index closes beyond the last swing point against the trade.

## 2. Data, engine, arms (R115's, unchanged; parity asserted first)

- **Arms:**
  - R72 Breadth Donchian;
  - R51 Donchian 20 + Supertrend;
  - R52 Open outside value;
  - Liquidity 15+5 (LIQ).
- **Engine:** R101's units, R106's walk with today's rules, and R106's engine for forced exits. Today's rules must
  reproduce the published trades and net to the rupee: R72 design 3,071 / Rs 2,16,929 (checked 31 Oct: exact for all
  four arms in design).
- **Data:** R51's clean stores (R51_CLEAN=1) built from the Drive lake, copied 31 Oct.
- **Periods (R115's):**
  - design: 20 Nov 2024 - 31 Mar 2026;
  - walk-forward window: 1 Apr 2025 - 31 Mar 2026, inside design (no parameter is fitted);
  - final: 1 Apr - 13 Oct 2026, **not fresh** (earlier studies read it), read once by R140 (`results/FINAL_READ`).

## 3. Structure (R139's, unchanged)

- **Bars:** 15- and 5-minute index bars from 09:15, continuous across sessions (warm-up from Sep 2024).
- **Swings:** wick pivots of 3 bars each side, known only at the close of the 3rd bar after; today and the previous 4
  sessions.
- **Dow trend at a bar's close:**
  - UP = the last two swing highs AND the last two swing lows rise;
  - DOWN = both fall;
  - otherwise none.
- **Decisions read only bars closed by the minute before the fill.**

## 4. The family (12 tests; fixed now)

| id | arms | rule |
|---|---|---|
| T15-72, T15-51, T15-52, T15-L | R72, R51, R52, LIQ | **skip the trade when the 15-min trend is against it**: DOWN for a call, UP for a put, at the last 15-min bar closed before the fill. None or unknown: kept |
| T5-72, T5-51, T5-52, T5-L | the same | the same on 5-min bars |
| M15-72, M15-51 | R72, R51 | **structure-shift exit:** after the fill, the first 15-min bar of the same day that closes below the last 15-min swing low known at the previous bar's close (call), or above the last swing high (put). The option is sold at the next minute's open. Today's exits stay on; whichever comes first wins |
| M5-72, M5-51 | R72, R51 | the same on 5-min bars |

- Filters drop candidates before the arm's walk, so a dropped signal frees the book (R101 / R115).
- Exits are tested on R72 and R51 only. They share R106's exit engine; R52's and LIQ's own exits are left as they are.

## 5. Gates: losing less (all must hold)

**In each of design, the walk-forward window and the final:**
1. **The sum of losing days is smaller** than today's (losing days add up to less money).
2. **Max drawdown** (daily, 1 lot) not larger; **worst day** not worse; **worst month** not worse.
3. **The arm still makes money:** net > 0. A higher net is **not** required (Boss: profit is not the point). The net
   change is reported.

**Across the family:**
4. **Better than random (final):** any skip rule cuts losses just by trading less, so the rule must cut more than
   chance. 2,000 twins (seed 140); p = (1 + twins whose sum of losing days is at least as good) / 2,001 (R115's
   normal-tail p when no twin reaches it), p < 0.05.
   - **filters:** drop as many of the arm's candidates at random, then walk again;
   - **exits:** each trade the rule sold earlier is sold at a uniformly random minute between its fill and today's
     exit instead.
5. **BH q <= 0.10** over R140's 12. Also reported, not gated: q counting every earlier family (R115's m, about 9.3
   million).
6. **>= 40 final trades**, and **>= 30 final trades that differ** from today's.

**Reported for every test:**
- trades, win rate, net and its change;
- average win and loss, worst day, drawdown, worst month;
- the sum of losing days and of losing trades, losing days;
- the effect on the 09:15-09:30 entries.

## 6. What a pass means (CLAUDE.md)

- **Paper:** a passing rule may go on paper for its arm, behind a labelled switch, logged in the Autopilot timeline,
  undoable in one tap.
- **Live:** a passing rule is risk-lowering (it skips trades or exits earlier), so it may only start in **shadow**
  (logged only), behind a switch that is off by default.
- **R140 changes no app code.** Every test gets an entry `research/experiments/entries/r140-*.yaml`.
