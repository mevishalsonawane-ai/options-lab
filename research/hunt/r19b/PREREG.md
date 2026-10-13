# R19b pre-registration: Boss's rupee give-back trail for Liquidity ("down Rs 500 from its highest point, sell it")

Written 13 Oct 2026, BEFORE any result of these tests was computed. This file is committed on its own before the first
run; its commit hash is quoted in the write-up (`research/HUNT_R19B_LIQUIDITY_TRAIL.md`). Nothing below may change
after that commit. A change needs a new entry, a new pre-registration and a fresh holdout.

R19b is separate from R19 (`claude/liquidity-lock`, target-relative profit locks). Neither reads the other's results.

## Boss's rule (13 Oct 2026)

> "For Liquidity: if the trade is down Rs 500 from its highest point, sell it."

Today's example (illustration only, never a test): Liquidity 5m MIDCPNIFTY PE, entry 13:20 @ 195.45, lot 120; it peaked at
about +Rs 2,500 and exited at 13:29 @ 188.25 on the index stop for -Rs 864.

## Definitions (fixed)

- **Open P&L per lot** = (option price - entry fill) x the lot in force that day. Gross: charges are not in it (as the
  open P&L Boss watches). Per lot, so the number of lots never changes the rule.
- **Highest point (peak)** = the entry fill, then the highest 1-minute HIGH the option has traded since the entry. A
  minute with no trade adds nothing (a missing peak keeps the last one). A peak earned in a minute counts from the NEXT
  minute (as `ProfitLock.nextPeak`: the resting stop is raised after the look that saw the new high).
- **Give-back G** (rupees per lot) is turned into option points with that day's lot: `G / lot`
  (BANKNIFTY: lot 15 to 27 Nov 2024, 30 from 28 Nov 2024, 35 from 27 Jun 2025, the NSE schedule in `obuy/config.py`;
  so Rs 500 = 33.3 points at lot 15, 16.7 at lot 30, 14.3 at lot 35).
- **Trail trigger** = `peak - G / lot`, put on the 0.05 tick rounded UP (as `ProfitLock.onTick`), raised only, never
  lowered. It only ADDS an earlier exit: the -15% premium stop and every other exit of the arm stay exactly as they are.

## Variants (fixed in advance; 7 trail variants + the control)

| id | rule |
|---|---|
| CTRL | (d) today's rules, unchanged: the control |
| A1 | (a) **Boss's rule as primary**: once the open P&L per lot has reached at least +Rs 500 (peak - entry >= 500 / lot), exit when the price is Rs 500 per lot below the peak |
| A2 | (a) the other reading of "from the highest": trail active from the entry, exit when Rs 500 per lot below the peak (the first trigger is entry - 500 / lot, a tighter stop than -15% on most trades) |
| B300 | (b) A1 with a Rs 300 give-back (activation +Rs 300) |
| B750 | (b) A1 with Rs 750 (activation +Rs 750) |
| B1000 | (b) A1 with Rs 1,000 (activation +Rs 1,000) |
| P3 | (c) the same idea in premium percent: once the peak is >= 3% above the entry fill, exit when the price is 3% of the entry fill below the peak. 3% is fixed a priori: Rs 500 is ~3.3% of a ~Rs 500 BANKNIFTY premium at lot 30 and ~2.1% of today's MIDCPNIFTY 195.45 at lot 120 |

A1 is the primary hypothesis. All seven are tested and all count in the correction.

## Books and data

- Books: Liquidity BANKNIFTY 15m (`liquidity15`) and 5m (`liquidity5`), one position per book.
- **MIDCPNIFTY 15m / 5m: NOT TESTED.** No MIDCPNIFTY option minutes are reachable without the Dhan API: the
  `research-data` release has BANKNIFTY and NIFTY option minutes only, and h4's MIDCPNIFTY option cache lived in an
  earlier session's scratchpad (it is not in git or any release). Calling the Dhan API is out of scope. So nothing in
  this study is evidence for or against the trail on MIDCPNIFTY, and no verdict below applies to the MIDCPNIFTY books.
- FINNIFTY is parked: not tested.
- Data: the `research-data` release, BANKNIFTY 13 Feb 2024 - 23 Feb 2026: index minutes plus the NEAREST expiry after the
  day at strikes within 1,000 points of the 09:20 index (read through `research/brain/adapters/release.py`).

## Entries and exits: engine `LiquidityRules` exactly

A port of `research/liquidity_exits_3060.py` (the line-by-line port of the Kotlin replay, trade-for-trade equal to the
app), on the release data:

- Charts: 15- and 5-minute bars folded from 09:15, continuous over the sessions of the last 10 calendar days.
- Levels: swing (lookback 20, full range) + pool (2 contacts, 5 bars apart, 10 confirmation bars, max age 300);
  entry when a completed bar's close takes a POOL that overlaps an active SWING zone of the same side; CE above, PE below;
  one strike in the money; bar ends 09:20-14:00; room filter (the next level ahead >= 1 index stop = 30 points);
  expiry days skipped (the arm's research port; the release has no same-day chain on most of them).
- Entry fill: the option's first traded minute at or after the deciding bar's end (at most 2 minutes late), at its open.
- Exits, minute by minute (minute t-1's bar is complete at t), in this order:
  1. the -15% premium stop (resting SL-M at the fill x 0.85 floored to the tick; filled at min(trigger, that minute's
     open));
  2. **the trail (variants only)**: the trigger from the peak BEFORE this minute; filled at min(trigger, that minute's
     open) - a resting stop, so a gap through it fills at the open;
  3. the peak is updated with this minute's high;
  4. 15:10 square-off; the index stop (index low/high 30 points back through the broken level); the time stop (20
     minutes, +5% on the latest option price, decided once); the next-level target; the failed break; new liquidity -
     each sold at the next minute's open.
- A book is flat the minute after its exit, so a variant that exits earlier can take a later break (the app does too).

## Fills and charges (conservative, the same for the control and every variant)

- Market orders (entry, structural exits): the app's paper fills (+-5 bps) plus a 1-tick half-spread.
- Resting stops (the -15% stop AND the trail): the app's SL-M fill (-10 bps) plus a 2-tick half-spread, at the
  breached trigger or the open if it gapped through. This is the stated fill for the trail (the app raises the resting
  SL-M, as `ProfitLock.raise` does for the ORB). Information only, not a gate: the trail filled at the NEXT minute's
  open instead.
- Charges: the app's model at today's rates (Rs 20 an order, STT 0.15% of the sell, exchange 0.03553%, SEBI, stamp 0.003%
  of the buy, GST 18%). 1 lot a trade.

## Periods (dates fixed now)

- **Design: 13 Feb 2024 - 28 Feb 2025** (all sessions in the release before 1 Mar 2025).
- **Locked holdout: 3 Mar 2025 - 22 Aug 2025**, read ONCE for all variants together, guarded by a flag file in the cache.
  - Why: every Liquidity / BANKNIFTY holdout read so far starts on or after 25 Aug 2025 (R16 / R17: 25 Aug 2025 - 23 Feb
    2026; R12, R14, h4, h42 and the others: 1 Oct 2025 onward). Mar - Aug 2025 has never been read as a holdout.
  - Honest caveat, stated before the result: the arm's own exits (index stop, time stop, room, 1-ITM) were chosen on
    data that includes this window (LIQUIDITY_REVERSAL.md, liq2 walk-forward). The CONTROL is therefore in-sample
    here. The TRAIL is not: no rupee give-back trail has been run on any window. Earlier percentage trails
    (LIQUIDITY_TRAIL.md, 2024-26, keep-a-share-of-the-best) were run on BANKNIFTY 15+5 and lost; that is prior
    evidence against, written down here before the run.
- **Not used: 25 Aug 2025 - 23 Feb 2026** (already read as holdouts by other hunts). Not reported.

## Tests and gates (all must pass, per variant x book; 7 x 2 = 14 hypotheses)

Delta = the variant's net minus the control's net, each book re-run on its own (so earlier exits may add later trades).

1. **Walk-forward on design.** No parameter is fitted (every variant is fixed above), so the walk-forward is a stability
   check: the design sessions are cut into 3 consecutive folds of equal sessions. Pass: Delta > 0 in at least 2 of 3
   folds AND design Delta > 0.
2. **Holdout.** Delta > 0, with a one-sided sign-flip permutation p on the daily Delta (20,000 flips, seed 19), then
   Benjamini-Hochberg across all 14 hypotheses: pass at q <= 0.10. At least 30 holdout trades in the book, else
   "inconclusive".
3. **Random twin (holdout).** On the control's own trade list (same entries), the variant's exits change k trades
   (cut early). A twin cuts k random control trades of the same book at a uniformly random minute between the entry
   and the control's exit, filled at the next minute's open with market fills. Pass: the variant's fixed-entry Delta
   beats at least 95% of 2,000 twins (seed 19).
4. **Risk.** Holdout worst day and maximum drawdown (daily, 1 lot) no worse than the control's.

PASS = all four. A variant that fails any gate stays a note.

## Reported for every variant x book x period (with n everywhere)

Trades, win rate, net and net per trade, Delta vs control, share of MFE captured (on the control's entries:
sum of exit - entry over sum of the control trade's MFE, the best high - entry before its own exit), how many big
winners were cut short (control trades netting >= +Rs 2,000 per lot that the variant exits earlier) and the rupees they
lost, worst day, maximum drawdown, exit reasons.

## App consequence (fixed now)

- If A1 or a pre-registered sibling PASSES on a BANKNIFTY book: it acts on that PAPER book at once (CLAUDE.md, paper
  rule), logged in the Autopilot change timeline, undoable in one tap, each trade labelled "plain rules" or "with Rs
  500 trail". Live unchanged. MIDCPNIFTY not covered (untested).
- If nothing passes: not added. An opt-in "Boss's choice" paper switch, OFF by default, labelled "failed research,
  your choice", built and tested the same way, so Boss can decide after reading the numbers.

## Look-ahead guard

The trail at minute t reads only the peak of minutes before t; the test `test_r19b.py` checks that changing any option
price at or after the exit minute leaves a trail exit's decision unchanged, and that a trail never fires above a
price that has already traded.
