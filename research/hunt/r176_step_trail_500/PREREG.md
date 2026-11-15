# R176 pre-registration: Boss's Rs 500 step trail (per lot) on Liquidity 15+5, R72 and R51, side by side with today's rules

Written 15 Nov 2026, **before any R176 trade or P&L number was computed**. This file is committed on
`claude/r176-step-trail-500` before the code's first run on data. Research only: nothing in `android/**` changes, and
nothing in the app depends on this study (the rule is being built anyway, by Boss's choice). Owner: Boss (they/them).

Numbering: the last finished study is R173. R174 is not in the repository or on origin. R175 (stop to cost after
+Rs 2,000) was pre-registered on `claude/r175-ctc-after-2k` and stopped by Boss before any run. The next free number is R176.

Boss, 15 Nov 2026 (confirmed PER LOT): *"if the profit moves to 1k, make stop loss 500, if the profit moves to 1.5k move
stop loss to 1000, keep a 500 rs gap on every 500 rs gain. And when a trade is bought the stop loss should be only 500 rs."*
It is being built into the app on Liquidity 15+5 (every book / index), R51 Donchian 20 (+ Supertrend) and R72 Breadth
Donchian, paper and live. Boss asked for its backtest side by side with today's rules.

**Signal source for every rule: the index** (CLAUDE.md default). Entries are unchanged:
- Liquidity 15+5: the index's liquidity levels (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5);
- R72 Breadth Donchian: 5-minute Donchian 20 breaks on 4 of the 5 indices;
- R51 Donchian 20: 5-minute Donchian 20 breaks on the index, kept when Supertrend (10, 3) agrees.

Only the bought option's stop changes (a stop on the option premium, which CLAUDE.md allows). No rule reads its signal
from an option's chart.

## 0. What was already tested near this (read before writing this)

| study | rule | result |
|---|---|---|
| R152 | -Rs 2,000 stop for the whole position (+/- a Rs 2,000 target) on R72 / R51 | failed |
| R153 LOCK2K | net +Rs 2,000 locks net +Rs 1,000 | failed |
| R167 G1 | net +Rs 1,500 locks net Rs 0 | failed (stop-sale slippage turns "Rs 0" into small losses) |
| R172 | +Rs 2,000 target / -Rs 1,000 stop, whole position | lost money everywhere (a report, not a test) |
| R175 | +Rs 2,000 -> stop to cost | pre-registered, stopped by Boss, never run |
| **R176** | **-Rs 500 a lot at entry; from +Rs 1,000 a lot, a stop Rs 500 a lot under each Rs 500-a-lot step; no target** | this study |

Expected from R172 (said now, so the reader can weigh it): R172's -Rs 1,000 stop for the whole position was tighter
than -15% on 95-100% of trades and was hit about twice as often as its target. Rs 500 a lot is only about 7-8 premium
points on a lot of 65-75, so this entry stop may be hit on most trades, before the step trail ever starts. Unlike R172 it has no target, so big
winners are not capped, only trailed. This is reported, not hidden.

## 1. Engine, arms, data (R172's / R152's / R106's, unchanged)

- R106's held-contract simulator (R64): next-minute fill; stops on the minute low; a gap through a stop fills at the
  minute's open; limits at their price.
- R17 liquidity fills: slippage from the 5-minute volume per position; a stop sale is x0.999 minus twice the half-spread.
  **Every stop of this rule (the Rs 500 stop and the step trail) is filled as a stop sale, not at its level.**
- Dated Zerodha charges on the whole quantity. qty = lots x the index's lot size on that day; lots 1, 2, 3.
- R106's walk, re-run for every rule (a different exit frees a side at a different minute): one open position a side,
  at most 2 fresh fills a side a day (Donchian arms); R101's units for Liquidity (one position a book, expiry days skipped).
- Today's exits (**A**):
  - Liquidity 15+5: -15% premium stop; 20-minute time stop unless +5%; index exits; 15:10 square-off.
  - R72 and R51 (XL): -15% premium stop; ladder + L25; 30-minute time stop; 15:15 square-off.
- Data: the Drive lake `gdrive:IraAlgo-data-lake` (index_1m, index_daily, options_rolling_1m WEEK), ending 19 Oct 2026.
- Stores:
  - **design**: R51's Amendment A clean stores (built to 13 Oct; `R176_POST=0`), where the published B0 reproduces;
  - **final and the 6-month window**: the stores built to 19 Oct (R106's `clean_post`; `R176_POST=1`), as R172.
- **Parity (asserted before any rule number):**
  - design at 1 lot, today's rules: LIQ 881 / Rs 2,57,249 (R106); R72 3,071 / Rs 2,16,929; R51 4,208 / Rs 50,425 (R152);
  - final at 1 lot, today's rules, on the 19 Oct stores: R172's check, LIQ 357 / Rs 11,155; R72 1,200 / Rs 1,70,336;
    R51 1,668 / Rs 1,31,546 (the published 1,202 / 1,670 differ only by NIFTY's 13 Oct expiry day; R172 explains it);
    R151's pickers on the same stores: R72 651 / Rs 2,20,118; R51 692 / Rs 1,60,101 (R172);
  - the R176 engine with the rule off must price every candidate exactly as R101 / R152 did (asserted at 1 lot).
  - If parity fails, no R176 number is gated; the gap is reported and the study stops.

## 2. The rule (fixed now, exactly as the app implements it)

Common: L = lots; `real` = the trade's real entry fill (R17, slippage included; the fill the app shows);
P&L = (option price - real) x qty, before charges; best = the highest open P&L so far, read from each minute's option
HIGH from the fill minute on.

- **At entry**: a stop at `real - 500 x L / qty`, rounded UP to the 0.05 tick (the safe side: the planned loss before
  charges is at most Rs 500 a lot). It acts only where it is tighter (a higher premium) than the arm's own stop
  (-15%); otherwise the arm's own stop stays. If the level is <= 0 it does not act.
- **Step trail**: once best >= +Rs 1,000 x L, stop = `real + (floor(best / (500 L)) - 1) x 500 L / qty`, rounded UP to
  the tick (the safe side: it locks at least the stated rupees before charges). So +500 L at a best of 1,000 L, +1,000 L
  at 1,500 L, +1,500 L at 2,000 L, and so on.
- The stop only moves up: the simulator uses the highest of the entry stop, the step trail and the ladder / L25 locks.
- A new step level is set at the end of the minute whose high reaches it and acts from the next minute (the simulator
  checks the stop before raising it), so no bar is read twice and nothing looks ahead.
- No fixed target. Every existing exit of the arm stays (-15% stop where it is the tighter, ladders / locks / L25, time
  stops, index exits, square-offs); whichever acts first.

**Tests: 3 arms x 3 lots = 9** (the rule vs today's rules at the same lots). Variants with R151's pickers are reported,
never gated (section 4).

## 3. Periods and windows

- **Design:** 20 Nov 2024 - 31 Mar 2026. Walk-forward window: Apr 2025 - Mar 2026, with its four quarters reported.
  Nothing in R176 is fitted (every number is Boss's), so "walk-forward" here means the later, out-of-time part of
  design, reported on its own.
- **Final:** 1 Apr - 13 Oct 2026, read ONCE by R176 (`results/FINAL_READ` is written before the read; a second read is
  refused). **Honesty: Apr - Oct 2026 has been read by many studies (R51 - R173), including R152's, R153's, R167's and
  R172's rupee stops and locks on these same arms. It is weak evidence, not a clean holdout.**
- **Last 6 months (R172's window):** 20 Apr - 19 Oct 2026 (126 sessions), month by month (Apr and Oct part months). It
  overlaps the final; it is in the "positive in every period" and risk gates below.

## 4. With and without R151's picker (descriptive, never gated)

Today's rules and the rule are also priced on the trades R151's app pickers take on R72 and R51 (`r152picker`: model
numbers `r72_picker_model.json` 0.4656, `r51_picker_model.json` 0.4469; a break scored below is skipped but still holds
its side and counts in the day's caps; a break that cannot be scored is taken). Liquidity has no picker (B = A). The
pickers were fitted on design-period trades (R151), so picker design numbers are in-sample and labelled so.

## 5. What is reported

For every arm x lots, with and without the picker, and for the three arms together (their trades summed; with the picker
= Liquidity A + R72 B + R51 B), in design, walk-forward, final and the 6-month window:
trades; win % (net > 0); net after charges and slippage; average win / average loss; exit mix (**Rs 500 stop** = the
entry stop of this rule; **step trail**; **other**, with the -15% stop shown); worst day; worst month; max drawdown (on
daily net); and the same for today's rules alongside. Month by month for the 6-month window. A short plain-English
summary for Boss first.

## 6. Twins (final period, 500 per test, seed 176; R152's design)

For every trade whose exit row is earlier than the same candidate's exit under A at the same lots (and A's exit is at
least 2 rows after the fill), the twin sells at the open of a uniformly random minute strictly between the fill and A's
exit, with A's exits active before it (R152's `forced_exit_nets`; Liquidity's index exit stays active). Other trades
count as themselves. p = (1 + twins whose total net >= the rule's) / 501.

## 7. Gates (all must hold, per arm and lots, vs A at the same lots)

1. Net > 0 in design, the walk-forward window, the final and the 6-month window.
2. Net above A's in design, the walk-forward window and the final.
3. Twin BH q <= 0.10 with **m = 3,925 + 45 + 9 = 3,979** (3,925 = R173 Amendment B's count of every earlier test;
   R175's 45 pre-registered tests are counted although never run, to be safe; R172 was a report, not a test). q within
   R176 alone is also shown.
4. At least 40 final trades.
5. Within the account stop rule at the tested lots in every period (design, walk-forward, final, 6 months): max
   drawdown <= Rs 40,000 and worst calendar month >= -Rs 13,000. (A's own figures are shown next to it.)

**Live note.** This rule is Boss's choice and is being built anyway; R176 only reports whether it passes. Nothing in the
app changes from R176.

## 8. Tests (pytest) and the look-ahead guard

Pure functions (the step level, rounding, the entry stop, the exit-mix labels, months, worst month, gates, BH count) get
unit tests with edge cases: an empty list, a trade that never reaches +1,000 L, a best of exactly 1,000 L and 1,499 L,
a gap through the stop, an entry stop below the -15% stop (it never loosens), NaN minutes, 1 vs 3 lots, a whole position
worth less than Rs 500 L. Engine tests on synthetic contracts: with the rule off the simulator equals R106's exactly; the
rule never exits later than A; stops fill with stop slippage. **Look-ahead guard:** on synthetic days the exit row and
price are unchanged when every minute after the exit row is replaced by noise.

## Amendment A (15 Nov 2026, before any R176 number was computed): last year of data only

Boss, 15 Nov 2026, while the data was being copied: *"Test on only last year of data."* No R176 trade or P&L number
existed when this was written. Sections 1, 2 (the rule), 4, 6 and 8 are unchanged. What changes:

- **Window: the last 12 months the lake covers, 20 Oct 2025 - 19 Oct 2026**, all on the stores built to 19 Oct
  (`R176_POST=1`). The design period before 20 Oct 2025 is not read.
- **Periods inside it** (replacing section 3):
  - **early**: 20 Oct 2025 - 31 Mar 2026 (out-of-time with respect to the final; inside the years R51's design used, so
    not unseen by earlier studies);
  - **final**: 1 Apr - 13 Oct 2026, read once by R176 (honesty note of section 3 unchanged: read by many studies);
  - **last 6 months**: 20 Apr - 19 Oct 2026, month by month;
  - **the whole year**: month by month (Oct 2025 and Oct 2026 are part months).
- **Parity**: today's rules at 1 lot on 1 Apr - 13 Oct 2026 must give R172's check (LIQ 357 / Rs 11,155; R72 1,200 /
  Rs 1,70,336; R51 1,668 / Rs 1,31,546; pickers R72 651 / Rs 2,20,118, R51 692 / Rs 1,60,101); the rule-off engine must
  equal R101 candidate by candidate. The design parity of section 1 is not run (design is not read).
- **Twins** (section 6) are drawn over the whole year (500 a test, seed 176).
- **Gates** (replacing section 7), per arm and lots, vs A at the same lots:
  1. net > 0 in early, final, the last 6 months and the whole year;
  2. net above A's in early, final and the whole year;
  3. twin BH q <= 0.10, m = 3,979 (unchanged);
  4. at least 40 final trades;
  5. max drawdown <= Rs 40,000 and worst calendar month >= -Rs 13,000 in early, final, the last 6 months and the year.
- The pickers were fitted on trades up to 31 Mar 2026 (R151), so picker numbers in "early" are in-sample (labelled).
- One year is a short sample: every number comes with its trade count, and passing here is weaker evidence than the
  full design / walk-forward / final split would give.
