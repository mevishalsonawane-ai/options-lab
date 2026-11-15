# R175 pre-registration: after +Rs 2,000 open profit, move the stop to the entry price (cost-to-cost), on three arms

Written 15 Nov 2026, **before any R175 trade or P&L number was computed**. This file is committed on
`claude/r175-ctc-after-2k` before the code's first run on data. Research only: nothing in `android/**` changes.
Owner: Boss (they/them).

Numbering: the last study in the repository is R173. No R174 is in the repository or on any branch on origin; the task
named R175, so R174 is left free (it may be in use in a sibling session).

Boss, 15 Nov 2026, after R172 showed that a fixed +Rs 2,000 target / -Rs 1,000 stop loses money: *"Instead of selling
it, what if we add a profit lock at 2k and move stop loss to the start of the trade, means CTC. If we took the trade at
10 rs, make the stop-loss 10 rs."*

**Signal source for every rule: the index** (CLAUDE.md default). The entries are unchanged:
- Liquidity 15+5: the index's liquidity levels (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5);
- R72 Breadth Donchian: 5-minute Donchian 20 breaks on 4 of the 5 indices;
- R51 Donchian 20: 5-minute Donchian 20 breaks on the index, kept when Supertrend (10, 3) agrees.

Only the bought option's stop changes (a stop on the option premium, which CLAUDE.md allows). No rule reads its entry or
direction from an option's chart.

## 0. How R175 differs from what was already tested (read before writing this)

| study | trigger | where the stop goes | arms | result |
|---|---|---|---|---|
| R162 BE10 | premium +10% | breakeven | R72, R51 | win +3-4 pts, a little less net; failed the 5-point gate |
| R153 LOCK2K | net +Rs 2,000 | net +Rs 1,000 | R72, R51 (with a -Rs 2,000 stop) | failed |
| R167 G1 | net +Rs 1,500 (after charges) | the premium whose sale nets Rs 0 after charges | R72, R51 | win DOWN (stop-sale slippage turns "Rs 0" into small losses), less net; failed |
| R172 | (a target, not a lock) | +Rs 2,000 target / -Rs 1,000 stop | LIQ, R72, R51 | lost money everywhere (a report, not a test) |
| **R175 V1** | **gross +Rs 2,000** ((price - fill) x qty, before charges) | **the fill price itself** (Boss's "10 rs -> 10 rs") | **LIQ, R72, R51** | this study |

R175 differs from G1 by: (1) the trigger (+Rs 2,000 before charges, not +Rs 1,500 after); (2) the lock level (the paid
price, Boss's exact words, not net Rs 0; V2 is the after-charges version); (3) Liquidity 15+5 is added; (4) a per-lot
trigger (V4) and a trail (V5). V2 is close to G1 with a higher trigger; it is kept because Boss asked for "true CTC".

Expected from R167 G1 (said now, so the reader can weigh it): a lock at the paid price sold by a stop order nets a small
loss (charges + stop slippage), so it may LOWER the win rate, as G1 did. This is reported, not hidden.

## 1. Engine, arms, data (R172's / R152's / R106's, unchanged)

- R106's held-contract simulator (R64): next-minute fill; stops on the minute low; a gap through a stop fills at the
  minute's open; limits at their price.
- R17 liquidity fills: slippage from the 5-minute volume per position; a stop sale is x0.999 minus twice the half-spread.
- Dated Zerodha charges on the whole quantity. qty = lots x the index's lot size on that day; lots 1, 2, 3.
- R106's walk, re-run for every rule (a different exit frees a side at a different minute): one open position a side,
  at most 2 fresh fills a side a day (Donchian arms); R101's units for Liquidity (one position a book, expiry days skipped).
- Today's exits (**A**):
  - Liquidity 15+5: -15% premium stop; 20-minute time stop unless +5%; index exits; 15:10 square-off.
  - R72 and R51 (XL): -15% premium stop; ladder + L25 (peak +25% locks +5%, +30% locks breakeven, +60% locks +30%,
    +100% locks +60%); 30-minute time stop; 15:15 square-off.
- Data: the Drive lake `gdrive:IraAlgo-data-lake` (index_1m, index_daily, options_rolling_1m WEEK), copied 15 Nov 2026,
  ending 19 Oct 2026. R51 Amendment A clean stores built to 19 Oct (R106's `clean_post`, `R152_POST=1`), as R172.
- **Parity (asserted before any rule number):**
  - design at 1 lot must reproduce the published B0 exactly: LIQ 881 / Rs 2,57,249 (R106); R72 3,071 / Rs 2,16,929;
    R51 4,208 / Rs 50,425 (R152);
  - final at 1 lot must give R172's check on these stores: LIQ 357 / Rs 11,155; R72 1,200 / Rs 1,70,336; R51 1,668 /
    Rs 1,31,546 (the published 1,202 / 1,670 differ only by NIFTY's 13 Oct expiry day, which the 13 Oct stores could not
    see; R172 explains it);
  - the R175 engine with no rule must price every candidate exactly as R101 / R152 did.
  - If parity fails, no R175 number is gated; the gap is reported and the study stops.

## 2. The rules (fixed now; all are tighten-only)

Common: `real` = the trade's real entry fill (R17, slippage included; this is the price the app shows as the fill). The
trigger is checked on each minute's option HIGH. The new stop is raised at the end of the minute that triggers and acts
from the next minute (the simulator checks the stop before raising it), so no bar is read twice and nothing looks ahead.
The stop is sold like every stop here (R17 stop fill; a gap through it fills at the minute's open). Every existing exit
stays (-15% stop before the trigger, ladder / L25 locks, time stops, index exits, square-offs); whichever acts first.
The stop only moves up: the simulator keeps the higher of the new level, the ladder's locks and the -15% stop.

- **V1 (primary, Boss's words)**: trigger when (high - real) x qty >= Rs 2,000 for the whole position; the stop moves to
  `real` rounded UP to the 0.05 tick (never below the price paid).
- **V2 (true CTC after costs)**: same trigger; the stop moves to the premium whose sale nets Rs 0 after the trade's
  charges (R106 `target_price(real, qty, 0, charges)`, rounded up to the tick).
- **V3 (lock +Rs 500)**: same trigger; the stop moves to `real + 500 / qty`, rounded up to the tick.
- **V4 (per lot)**: trigger when (high - real) x qty >= Rs 2,000 x lots (3 lots: +Rs 6,000); the stop moves to `real`
  rounded up to the tick.
- **V5 (lock then trail)**: trigger as V1; the stop moves to `real` (rounded up), then trails Rs 1,000 for the whole
  position under the highest option high since the fill: stop = max(real, peak - 1,000 / qty), raised only.

At 1 lot V4 = V1 (same trigger); it is still counted as a test (it is the same p, so it does not change BH beyond m).

**Tests: 3 arms x 5 variants x 3 lots = 45.**

## 3. Periods and windows

- **Design:** 20 Nov 2024 - 31 Mar 2026. Walk-forward window: Apr 2025 - Mar 2026, with its four quarters reported.
  Nothing in R175 is fitted (every number is Boss's or fixed above), so "walk-forward" here means the later, out-of-time
  part of design, reported on its own.
- **Final:** 1 Apr - 13 Oct 2026, read ONCE by R175 (`results/FINAL_READ` is written before the read; a second read is
  refused). **Honesty: Apr - Oct 2026 has been read by many studies (R51 - R173), including R152's, R162's, R167's and
  R172's rupee exits on these same arms. It is weak evidence, not a clean holdout.**
- **Last 6 months (R172's window):** 20 Apr - 19 Oct 2026, month by month (Apr and Oct part months). It overlaps the
  final; it is reported for Boss, never gated on its own (it is in the "positive in all periods" gate below).

## 4. With and without R151's picker (descriptive, never gated)

Every variant is also priced on the trades R151's app pickers take on R72 and R51 (`r152picker`: model numbers
`r72_picker_model.json` 0.4656, `r51_picker_model.json` 0.4469; a break scored below is skipped but still holds its side
and counts in the day's caps; a break that cannot be scored is taken). Liquidity has no picker (B = A). On design the
picker's model was trained on those same design trades, so picker design numbers are in-sample and labelled so.

## 5. What is reported (every arm x variant x lots, A alongside, every period / window, with and without the picker)

trades; win % (net > 0); net after charges and slippage; average win / average loss; exit mix (**CTC stop** = a trade
the new stop closed earlier than today's rules would have; the -15% stop; other); worst day; worst month; max drawdown
(on daily net); how many trades reached the trigger; month by month for the 6-month window; the three arms together.

## 6. Twins (final period, 500 per test, seed 175; R152 / R167's design)

For every trade whose exit row is earlier than the same candidate's exit under A at the same lots (and A's exit is at
least 2 rows after the fill), the twin sells at the open of a uniformly random minute strictly between the fill and A's
exit, with A's exits active before it (R152's `forced_exit_nets`; Liquidity's index exit stays active). Other trades
count as themselves.
- p_net = (1 + twins whose total net >= the rule's) / 501; p_win the same on the win rate.
- **The test's p = max(p_net, p_win)** (it must beat random exits on both; intersection-union, as R167).

## 7. Gates (all must hold, at the same lots, vs A)

1. Net > 0 in design, the walk-forward window, the final and the 6-month window.
2. Better than A in design, the walk-forward window AND the final: net above A's, OR win rate >= 5 points above A's
   with net not lower.
3. Twin BH q <= 0.10 with **m = 3,925 + 45 = 3,970** (3,925 = R173 Amendment B's count, which includes every earlier
   study; R172 was a report, not a test). q within R175 alone is also shown. Raw p-values go to `results/raw_pvalues.csv`.
4. At least 40 final trades.

Worst day and max drawdown are reported; lower is a plus, not a gate.

**Live note.** Every R175 variant only raises a stop, so it LOWERS risk. A passing variant would be
"live-eligible after a shadow phase" (logged only, behind a switch that is off by default, CLAUDE.md). It may act on paper
arms straight away only if it passes. A failing variant stays a note. Nothing changes in the app from R175.

## 8. Tests (pytest) and the look-ahead guard

Pure functions (lock levels, rounding, the exit-mix label, month table, gates, BH count) get unit tests with edge cases:
an empty day, a trade that never triggers, a trigger on the last minute before the square-off, a gap through the new
stop, a stop that would sit below the -15% stop (it never lowers it), NaN minutes, 1 vs 3 lots, a whole position worth
less than the trigger. Engine tests on synthetic contracts: the rule never exits later than A; with no trigger the exit
equals A's exactly. **Look-ahead guard:** on synthetic days the exit row and price are unchanged when every minute after
the exit row is replaced by noise.
