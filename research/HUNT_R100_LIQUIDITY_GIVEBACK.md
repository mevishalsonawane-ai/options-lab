# R100: give-back protection for Liquidity 15+5 ("it went up to 4.5k and settled at -1k")

22 Oct 2026. Pre-registered in `research/hunt/r100_liquidity_giveback/PREREG.md` (f0641bd, pushed before any result).
The code, the tests and the design results were committed (4ba6bb3) before the final period was read once (`results/FINAL_READ`).
Code: `research/hunt/r100_liquidity_giveback/`.

Boss, 22 Oct: *"do something for liquidity strategy, it went up to 4.5k, and settled at -1k, this is not acceptable for me"*.

## Answer

**None of the 7 give-back protections passed, so the Liquidity paper and live exits stay as they are.** Trades like
Boss's are rare: of 881 design trades, 9 reached +20% or more and still closed at a loss (they lost Rs 15,030 together).
In the final period it was 5 of 357 trades (Rs 4,846). Any lock that would save them also cuts some of the runners that
make Liquidity's money.

- **Closest: G1, breakeven once the option is +20%.** It is about neutral, not proven:
  - **Design:** +Rs 3,287 against the own exits, from 16 changed trades out of 881. 9 were saved (Rs 14,800) and 7
    winners were cut short (Rs 11,512).
  - **Walk-forward year:** -Rs 2,641.
  - **Final:** +Rs 1,341, from 8 changed trades out of 357.
  - **Twins:** random early exits did as well (twin p 0.41 / 0.51). Without its 5 best trades it is -Rs 8,114 in design.
  - **Gates:** it failed all four (sample, walk-forward, twins, top 5).
  - This rule is aimed squarely at Boss's complaint: a trade that was +20% can no longer close at a loss (barring a gap).
  - Over 1,238 trades its cost and its saving roughly cancel.
- **Every other rule lost money in design:**
  - breakeven at +15%: -Rs 5,310;
  - step lock: -Rs 30,425;
  - keep half the gain from +20%: -Rs 87,632;
  - index breakeven: -Rs 65,409;
  - time-tightening: -Rs 28,703;
  - keep half the profit from +Rs 3,000 a lot: -Rs 1,10,129.
- **Boss's existing Rs 500 trail switch would be expensive.** On the same trades it made Rs 77,904 in design against
  Rs 2,57,249 for the own exits (-Rs 1.79 lakh, 324 of 881 trades changed). In the final period it made -Rs 34,171
  against +Rs 11,155. Leave it off.
- **For the 26 Oct BANKNIFTY decision this changes nothing:** BANKNIFTY's plain-rules record is untouched.

## The trade (an illustration, never evidence)

From Boss's diagnostics of 22 Oct 09:48:
- **Trade:** Liquidity 5m MIDCPNIFTY put, 1 lot of 120 (1-ITM).
- **Entry:** bought 09:20:02 @ 172.95, on the break of a level by the 09:15 5-minute bar.
- **Exit:** sold 09:40:02 @ 164.00 by the **20-minute time stop** (at 20 minutes the option was under +5%, i.e. 181.60).
- **Result:** -Rs 1,074 before charges, -Rs 1,168 after.
- **Peak:** Boss saw about +Rs 4,500, about +37.5 points a unit, the option near 210 (+21.7%).
- **Not available:** the lake ends 19 Oct and the phone's 22 Oct recording was not readable here, so the exact minutes
  of the peak and the fall are not known.

Exit rules in force: the -15% resting stop (147.00); the index stop 8 points beyond the broken level; the 20-minute time
stop; the next liquidity level; a failed break; new liquidity; 15:10. **No profit lock:** Liquidity has none by design (every
lock tested so far failed). The lab's lock variant was not in force. Boss's Rs 500 trail switch was almost certainly off:
the diagnostics name no trailed trade, and with it on the trade would have been sold near +Rs 4,000 (the stops were
checked every 15 s all morning).

**The time stop did what it is written to do: it checks only whether the trade is +5% at minute 20, not how high it went
before.** None of the exits looks at a peak, so a +21.7% spike that fades inside 20 minutes is sold at whatever it is
then. **This was not a bug.** The audit (below) found two real bugs in the exit path; neither touched this trade.

The same trade under each rule (assuming the price fell smoothly from about 210 and each lock filled at its level; before
charges):

| rule | sells at about | P&L before charges |
|---|---|---|
| own exits (what happened) | 164.00 (time stop) | -Rs 1,074 |
| G1 breakeven once +20% / G2 at +15% / G3 step | breakeven after charges (about 173.5) | about Rs 0 |
| G4 keep half the gain once +20% / G7 keep half once +Rs 3,000 | about 191.7 | about +Rs 2,250 |
| G6 time-tightening (keep a quarter in the first 30 minutes) | about 182.3 | about +Rs 1,125 |
| G5 index breakeven | not known (needs the index minutes) | - |
| Boss's Rs 500 trail switch (from +Rs 1,000 a lot) | about 206.3 | about +Rs 4,000 |

On this one trade every lock looks good. The history shows why one trade is not enough: the same rules sell runners on
their first pullback.

## Results (Liquidity 15+5: BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5; 1 lot; net after charges and slippage)

Every row uses the same trades: 881 in design (20 Nov 2024 - 31 Mar 2026; 657 of them in the walk-forward year, Apr
2025 - Mar 2026) and 357 in the final period (1 Apr - 13 Oct 2026).

| id | rule (signal: the index; locks on the option premium) | design | walk-forward | final | changed d / f | twin p d / f | BH q d / f | verdict |
|---|---|---|---|---|---|---|---|---|
| **B0** | own exits (today) | **Rs 2,57,249** | **Rs 2,38,064** | **Rs 11,155** | | | | keep |
| G1 | breakeven once +20% | Rs 2,60,537 | Rs 2,35,423 | Rs 12,496 | 16 / 8 | 0.41 / 0.51 | 1.0 / 1.0 | fail (closest; about neutral) |
| G2 | breakeven once +15% | Rs 2,51,939 | Rs 2,28,153 | Rs 972 | 29 / 12 | 0.79 / 0.99 | 1.0 / 1.0 | fail |
| G3 | +15% breakeven, +30% keep 40%, +50% keep 60% | Rs 2,26,824 | Rs 1,85,182 | -Rs 18,091 | 47 / 20 | 1.00 / 1.00 | 1.0 / 1.0 | fail |
| G4 | keep 50% of the best gain once +20% | Rs 1,69,617 | Rs 1,39,859 | Rs 13,513 | 57 / 16 | 1.00 / 0.49 | 1.0 / 1.0 | fail |
| G5 | index breakeven once 2 index stops in favour | Rs 1,91,840 | Rs 1,60,429 | Rs 3,425 | 152 / 60 | 1.00 / 0.93 | 1.0 / 1.0 | fail |
| G6 | once +20%: keep 25%, 50% after 30 min, 75% after 60 | Rs 2,28,546 | Rs 2,09,119 | Rs 4,656 | 58 / 24 | 0.99 / 0.90 | 1.0 / 1.0 | fail |
| G7 | once +Rs 3,000 a lot, keep half the best profit | Rs 1,47,120 | Rs 1,42,478 | Rs 6,717 | 88 / 21 | 1.00 / 0.88 | 1.0 / 1.0 | fail |
| R19C | Boss's Rs 500 trail switch (reference, not tested) | Rs 77,904 | Rs 75,171 | -Rs 34,171 | 324 / 107 | | | very costly |

### Both sides of each rule (design, trade by trade against B0)

| id | saved (trades, Rs) | winners cut short (trades, Rs) | net | the 9 give-back trades (B0 -Rs 15,030): kept | final: saved / cut |
|---|---|---|---|---|---|
| G1 | 9, Rs 14,800 | 7, Rs 11,512 | +Rs 3,287 | Rs 14,800 | 5, Rs 4,729 / 3, Rs 3,388 |
| G2 | 17, Rs 23,227 | 12, Rs 28,537 | -Rs 5,310 | Rs 14,800 | 6, Rs 5,553 / 6, Rs 15,736 |
| G3 | 24, Rs 45,944 | 23, Rs 76,369 | -Rs 30,425 | Rs 25,168 | 8, Rs 8,052 / 12, Rs 37,298 |
| G4 | 22, Rs 47,928 | 35, Rs 1,35,560 | -Rs 87,632 | Rs 34,519 | 9, Rs 13,809 / 7, Rs 11,452 |
| G5 | 94, Rs 1,02,914 | 58, Rs 1,68,324 | -Rs 65,409 | Rs 13,342 | 36, Rs 33,117 / 24, Rs 40,847 |
| G6 | 29, Rs 65,684 | 29, Rs 94,388 | -Rs 28,703 | Rs 35,626 | 12, Rs 15,243 / 12, Rs 21,742 |
| G7 | 43, Rs 86,554 | 45, Rs 1,96,683 | -Rs 1,10,129 | Rs 31,354 | 10, Rs 14,722 / 11, Rs 19,160 |
| R19C | 171, Rs 2,58,188 | 153, Rs 4,37,533 | -Rs 1,79,345 | Rs 27,682 | 59, Rs 76,074 / 48, Rs 1,21,400 |

Every rule saves the give-back trades; the question is the price paid on the winners, and for every rule but G1 that
price is far larger than the saving.

### Per index (net)

| id | BANKNIFTY design / final | FINNIFTY design / final | MIDCPNIFTY design / final |
|---|---|---|---|
| B0 | Rs 1,10,094 / Rs 10,978 | Rs 89,651 / Rs 4,852 | Rs 57,504 / -Rs 4,674 |
| G1 | Rs 1,06,936 / Rs 12,824 | Rs 94,239 / Rs 5,447 | Rs 59,362 / -Rs 5,775 |
| G2 | Rs 1,09,306 / Rs 6,799 | Rs 92,635 / -Rs 52 | Rs 49,998 / -Rs 5,775 |
| G3 | Rs 1,00,477 / -Rs 974 | Rs 69,526 / -Rs 2,775 | Rs 56,821 / -Rs 14,341 |
| G4 | Rs 74,281 / Rs 15,331 | Rs 63,663 / Rs 1,227 | Rs 31,673 / -Rs 3,046 |
| G5 | Rs 82,366 / -Rs 6,616 | Rs 54,072 / Rs 6,036 | Rs 55,402 / Rs 4,005 |
| G6 | Rs 74,492 / Rs 16,041 | Rs 67,045 / Rs 1,905 | Rs 87,009 / -Rs 13,290 |
| G7 | Rs 54,619 / Rs 13,919 | Rs 50,406 / -Rs 3,374 | Rs 42,096 / -Rs 3,828 |

No rule is better on every index in both periods. G6 helped MIDCPNIFTY in design (+Rs 29.5k) and was Rs 8.6k worse there in
the final period. Per-index results were not gated (not pre-registered as tests).

### Walk-forward selection (descriptive)

Choosing, at the start of each walk-forward quarter, whichever rule (or B0) had made the most so far gave G3, then G1
three times. Stitched together that made Rs 2,15,318 against B0's Rs 2,38,064 (-Rs 22,746). Picking a give-back rule from
the past did not beat the plain exits.

Win rate, worst day and max drawdown (design / final). B0: 44% / 37%, -Rs 8,416 / -Rs 11,405, Rs 32,537 / Rs 61,062.
G1: 43% / 36%, the same worst days, Rs 28,137 / Rs 61,062. The locks lift the win rate a little and barely move the
worst day.

## What Boss can switch on knowingly (nothing is switched on by this study)

1. **G1, breakeven once +20%**, as a labelled paper-only opt-in switch, off by default. It is not built: ideas that
   failed the gates stay notes (CLAUDE.md). It can be built on Boss's word.
   - **What it costs:** about nothing either way so far. Over 1,238 trades it was +Rs 3,287 in design, -Rs 2,641 in the
     walk-forward year and +Rs 1,341 in the final period.
   - **What it does:** a trade that reached +20% can no longer finish as a loss (barring a gap through the stop). That
     is exactly the 22 Oct case: about Rs 0 instead of -Rs 1,168.
   - **What it gives up:** about 1 trade in 55 is changed. Roughly half of those are runners sold at breakeven.
2. **Leave the existing Rs 500 trail switch off.** On these trades it costs about Rs 1.79 lakh over 17 months.

## Method

- **Signal:** the index (Liquidity's level breaks on 15/30/5-minute index bars), unchanged; the 1-ITM option is bought.
  Locks are stops on the option premium (allowed by CLAUDE.md). G5 is an index rule. No rule reads an option chart for a
  signal.
- **Trades:** exactly as R76 / R81 (`fam_liq.make` on R51's Amendment A clean stores, rebuilt 22 Oct from the Drive
  lake, options to 13 Oct 2026). B0 reproduces R76 to the rupee (Rs 2,57,249 / 881; Rs 11,155 / 357); the study
  asserts it, and asserts R100's engine equals R64's trade by trade. Each trade is priced again under each rule from
  the same entry row and index-exit row on R64's held-contract engine, with one addition: keep rows
  (`r100engine.py`). The other costs, all unchanged:
  - liquidity slippage;
  - stops on the minute low, with gap fills at the worse open;
  - a lock set at a minute's end that acts from the next minute;
  - breakeven = real entry + round-trip charges;
  - dated charges, 1 lot.
- **Twins:** 500 sets of as many random early exits as the rule changed trades (R65 / R76), in design and in final.
  Benjamini-Hochberg runs across the 7 rules in each period.
- **Gates (pre-registered):** at least B0 in all three periods; BH q <= 0.10 in both; the design gain still >= 0
  without its 5 best trades; at least 30 changed design trades.
- **Look-ahead guard and edge cases:** `tests/test_r100.py` (51 tests):
  - engine = R64 line for line with no rule;
  - each rule from the next minute only;
  - gaps, NaN minutes, no fill, a lock exactly at a threshold;
  - the time stop and index exits still acting;
  - bars after the exit never changing it;
  - the index breakeven's arming and firing;
  - the accounting, the decision rule and the one final read.
- **Limits:**
  - The final period had already been read for this arm by R51, R63, R70, R76 and R81, so it is weak evidence.
  - A rule that exits earlier could let a book take a later signal; this is not modelled (at most 16-152 changed
    design trades).
  - 14 - 19 Oct (in the lake, not in the stores) was not used.

## The exit-path audit (22 Oct; app fixes in this change)

A separate reviewer read the whole Liquidity exit path. Neither bug below changed the 22 Oct trade:
- only one Liquidity position was open (MIDCPNIFTY);
- its time stop fired on time;
- no lock or trail was on.

1. **Fixed: one index's failed candle read skipped every Liquidity position's exits.** It escaped the exit loop, so the
   positions after it (other indices too) got no index stop, target, failed break or time stop on that pass. Now a
   failed read stops only that index's index exits. Its positions keep their time stop, the -15% stop and 15:10.
2. **Fixed: after a gap longer than 5 minutes, a lock's or trail's best price ignored the candles.** A gap is a restart
   mid-trade or a stall. The stream keeps only 5 minutes of prices. Now the first regular look after such a gap also
   reads the 1-minute candles' highs, once, so a lock or trail earned in the gap is set.
3. **Reported, not changed:**
   - The Liquidity exits read the index candles over the network inside the arms' lock, so a slow feed delays the other
     position checks.
   - A live position under the lab's lock variant reads no high (only the stream's ticks).
   - The time stop can be decided "held" on an old candle close of a thin option.
   - A disarmed index drops off the stream while its position is open.

## For Jarvis

Boss asked on 22 Oct why a Liquidity trade went from about +Rs 4,500 to -Rs 1,168 (Liquidity 5m MIDCPNIFTY put,
22 Oct, sold by the 20-minute time stop). Answer:
- Liquidity has no profit lock by design. The time stop only checks +5% at minute 20.
- R100 tested 7 give-back locks on 1,238 trades; none passed.
- Breakeven once +20% (G1) was about neutral: +Rs 3.3k design, -Rs 2.6k walk-forward, +Rs 1.3k final. It is Boss's
  choice to switch on, and it is not built yet.
- The Rs 500 trail switch would have cost Rs 1.79 lakh.
- Two exit-path bugs were fixed: a failed index read no longer skips the other positions' exits, and a lock reads the
  candles after a restart.

Files: `research/hunt/r100_liquidity_giveback/` (PREREG.md, r100engine.py, r100study.py, tests/, results/),
`research/experiments/entries/r100-*.yaml`.
