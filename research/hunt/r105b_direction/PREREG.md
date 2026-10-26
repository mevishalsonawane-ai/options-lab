# R105B pre-registration: a direction bias from the books, on Breadth Donchian and the other paper arms

Written 26 Oct 2026, committed and pushed BEFORE any R105B rule is computed on any trade. Run before this file:
- the four arms' design baselines rebuilt with R101's code, each reproducing its published figure to the rupee:
  - Liquidity 15+5: 881 trades / Rs 2,57,249;
  - R72: 3,071 / Rs 2,16,929;
  - R51 plain: 4,498 / Rs 9,998;
  - R52: 706 / Rs 2,90,573;
- a read-only survey of the book notes and earlier studies (which direction filters were already run on the arms).

Research only: nothing in `android/**` or `.github/**`.

Boss, 26 Oct: *"By all the pdf knowledge, try to find the direction and use it in breath donchain and other
stratergies."*

## 0. Disclosure and signal source

- **Signal source.** Every bias below is computed on the underlying index:
  - the index's daily bars, weekly bars built from them, 60-minute and 15-minute bars from its 1-minute bars;
  - today's open against yesterday's bars.

  The arm's own signal is unchanged (also on the index). The bias only removes the arm's calls on a down bias and its
  puts on an up bias. The option is only what is bought. No option chart is read.
- **Already tested on these arms, so not repeated:**
  - R73 T1-T8 on Donchian: 5-minute EMA 20/50, 15-minute and 1-hour EMA slope, ADX, Supertrend, day VWAP;
  - R79 on Liquidity: Supertrend, 10-candle waits, other indices;
  - R101: VIX regime, gap size, opening bar, AM only, breadth, VIX direction on Liquidity;
  - R104 F11 / new: Trader Tom daily 3-bar swing, SMA50 + DMI(8), 52-week high/low, Carli "skip trades in the gap's
    direction", Wyckoff streaks and secondary bursts;
  - R74 V2: VIX-confirmed Donchian.

  The only direction filter that ever passed on these arms is R73 T7 (Supertrend 10, 3), already in R51's paper rules.
- **D1 and D10 are post-hoc.** R104's holdout showed that the arms' trades in the direction of the opening gap made
  money in design and holdout (the inverse of `book-carli-04`). D1 and D10 turn that observation into a rule. Their
  design and final results are therefore NOT independent evidence. They are run, reported and counted in BH like the
  others, but they can only be confirmed on unseen days (14-19 Oct here, then paper).
- **The final window (1 Apr - 13 Oct 2026) has been read by about 25 studies.** It is weak evidence for every rule.
- **R104's holdout also showed with-daily-trend trades losing on these arms.** So the daily-trend biases (D2-D8) start
  with a poor prior. They are tested because Boss asked for the books' direction rules, and the books stress them most.

## 1. Arms (R101's code; R51 with its paper Supertrend filter)

| arm | baseline | design (published) | final (published) |
|---|---|---|---|
| R72 | Breadth Donchian C1 (4 of 5), exits XL (-15%, L25, ladder, 30 min, 15:15) | 3,071 / Rs 2,16,929 | 1,202 / Rs 1,71,366 |
| R51T | Donchian 20 + R73 T7 Supertrend (10, 3) agreement (as paper runs it), exits XL | 4,208 / Rs 50,425 (R75 B) | 1,670 / Rs 1,34,743 |
| LIQ | Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5), its own exits | 881 / Rs 2,57,249 | 357 / Rs 11,155 |
| R52 | Open outside value, OOV + L25 exits | 706 / Rs 2,90,573 | Rs 48,612 |

The study refuses to run if a baseline does not reproduce. R51T is R101's R51 candidates with T7 applied as a keep
rule before the walk, which is the same as R75's B. A bias filter stacks on T7 (both must keep the signal).

Signals that a bias removes free the arm's book, so a later signal can be taken (R101's walk). Periods:
- design: 20 Nov 2024 - 31 Mar 2026;
- walk-forward (WF): the 4 design quarters Apr 2025 - Mar 2026;
- final: 1 Apr - 13 Oct 2026, read once by `r105bstudy.py final`, guarded by `results/FINAL_READ`;
- OOS: 14-19 Oct 2026 (4 sessions), on an extended store copy, read once after the final, never gated.

## 2. The 10 biases (40 tests: each on R72, R51T, LIQ, R52)

"Calls only" = drop the arm's put signals; "puts only" = drop its call signals; "both" = no change. Every input is
known before the entry minute: daily and weekly bars end at the previous session; today's open is known at 09:15; an
intraday bar is used only once it has closed before the entry minute.

| id | book | rule |
|---|---|---|
| D1 GAPHL (post-hoc) | Larry Swing gap rule `book-lswing-06`, inverse of Carli `book-carli-04` | today's open > yesterday's high: calls only all day; open < yesterday's low: puts only; else both |
| D2 WK3 | Trader Tom `book-tom-03`, weekly variant | weekly 3-bar swing state from completed weeks (Mon-Fri). It turns up when a completed week's high exceeds the highest high of the 3 completed weeks before it, and down when its low is below their lowest low. It persists; before the first state, both. Up: calls only; down: puts only |
| D3 WK89 | Trader Tom ch. 9 (weekly 89-period MA) | last completed week: its low > SMA89 of weekly closes: calls only; its high < SMA89: puts only; else (straddling) both |
| D4 DDOW | Dow structure (Trader Tom ch. 3, Douglas, Heitkoetter) | daily 3-bar fractal swings (a swing high is a high above the bars on each side, confirmed when the next bar closes), using sessions up to yesterday. The last 2 swing highs AND the last 2 swing lows both rising: calls only; both falling: puts only; else both |
| D5 H60 | Woods "market bias first" (60-minute structure) | the same rule as D4 on the index's 60-minute bars (09:15-10:14, 10:15-11:14, ...; the 15:15 bar is partial), across days, using only bars closed before the entry minute |
| D6 PDAY | Porwal `book-porwal-06` (prior-day trend part) | yesterday's close > yesterday's open AND > the close before it: calls only; both below: puts only; else both |
| D7 SMA200 | Fabozzi `book-fmfabozzi-02`; Aziz "the daily 200 SMA" | yesterday's close > 1.01 x SMA200 of daily closes: calls only; < 0.99 x SMA200: puts only; else both |
| D8 ADX30 | Larry Swing (daily ADX > 30 with DI) | Wilder ADX(20) on daily bars at yesterday's close > 30: +DI > -DI gives calls only, else puts only; ADX <= 30: both |
| D9 F15 | Heitkoetter `book-heitkoetter-10` (bias part) | entries at 09:30 or later: the 09:15-09:29 candle closed above its open: calls only; below: puts only; equal: both. Entries before 09:30 are not filtered (R101: the opening-bar breaks are the arms' best trades) |
| D10 GAP25 (post-hoc) | Larry Swing gap rule, the open-vs-close version | today's open >= previous close x 1.0025: calls only; <= x 0.9975: puts only; else both |

Daily bars: the lake's `index_daily` (open, high, low, close). Today's open for D1 / D10 is that file's open (as
R101's gap).

## 3. Gate (all must hold; never relaxed)

a. Net above the baseline in design, in the WF window, and in the final; better in at least 3 of the 4 WF quarters.
b. Random-skip twin (R101's): drop as many of the arm's candidate signals at random as the bias dropped, then walk
   again, 500 times. p < 0.05 in design AND in the final.
c. BH q <= 0.10 with p = max(design p, final p), over the FULL family. The family is:
   - these 40 tests;
   - every earlier entry-filter / direction test on these arms in `research/experiments/entries/` (ids r72-*, r73-*,
     r74-*, r75-*, r79-*, r80-*, r101-*, r104-*, r105-*), at the largest p each entry states, else at p = 1;
   - plus R104's 26 arm tests at p = 1 if they are not logged one by one.
d. At least 40 final trades.
e. Still above the baseline without its 5 best trades (design and final).
f. Max drawdown no worse than the baseline's by more than 10% (final).

A bias that passes may go on **paper** for that arm (the app session applies it later, logged and undoable, never
live). Because of section 0, a pass of D1 or D10 is reported as "post-hoc, judge on paper" and needs Boss's switch.

## 4. Also reported (not gated)

- **Win rate next to net,** and the bias's own hit rate: the share of the arm's dropped signals that lost.
- **A combined view:** the trades each bias keeps vs drops, in every period.
- **The OOS window.**
- **Today's paper day (26 Oct),** where the diagnostics give the bias inputs.

## 5. Tests

`tests/test_r105b.py` covers:
- each bias on hand-made bars: up, down and neutral, ties, missing data, the warm-up;
- the look-ahead guard: changing today's bars after the entry minute, or any later session, never changes a bias;
- the weekly build (partial weeks, holidays);
- the 60-minute bars;
- the stacking with T7;
- the gate and BH with the prior family;
- the one-time final and OOS reads.
