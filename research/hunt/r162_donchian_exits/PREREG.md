# R162 pre-registration: trade structure for a higher Donchian win rate (scale-out, breakeven, finer locks, time stops, strike, index stop)

Written 12 Nov 2026, **before any R162 number was computed** (committed on `claude/r162-donchian-exits` before the code's
first run). Research only; nothing in `android/**` changes. Sibling studies run today: R161 (entries) and R163 (picker /
ML); R162 reports its raw p-values so the three can be put through one joint Benjamini-Hochberg step.

Boss, 12 Nov: *"I want to increase my win rate [of the] Donchian family, try your best ... use all resources you can."*
R162's part: **trade structure**, i.e. how the bought option is managed after the arm's own entry. Raise the share of
winning trades without capping the big winners.

Signal source for every rule: **the index** (CLAUDE.md default). The arms' entries are unchanged (R72: 5-min Donchian 20
breaks on 4 of 5 indices; R51: 5-min Donchian 20 kept by Supertrend (10, 3)). Only the option's exit, the strike bought,
or (family F) an index-based stop changes. Stops, locks and targets on the premium are allowed by CLAUDE.md; family F's
stop is read from the index itself.

## 0. Already tried (not repeated here)

- R105C: fixed +5 / +10 / +20% premium targets and a -10% stop (the whole position) - failed: they cap the big winners.
- R105 giveback, R106 small rupee targets, R152 +/- Rs 2,000, R153 stop + lock + day cap + 10:00 cut, R114 close at 10:00.
- What is NOT in those: selling only **part** of a 2-3 lot position early; a **breakeven** stop after a small gain; lock
  steps **below** today's +25% first step; **other time stops** (20 / 40 / 45 min, or one that only fires when not in
  profit); the **strike** (ATM / 2-ITM / delta-targeted vs today's 1-ITM); an **index** stop in place of / besides the
  -15% premium stop.

## 1. Arms, engine, B0 (exactly R152's)

R152's stack (`research/hunt/r152_donchian_2k/`): R51 Amendment A clean stores, R101 / R112 units (R72 Breadth Donchian;
R51 Donchian 20 + Supertrend (10, 3)), signals 09:20-15:10, never on the index's expiry day, R106's walk (one open
position a side, at most 2 fresh fills a side a day), R106 / R64's held-contract simulator (next-minute fill, stops on
the minute low, gaps at the open), R17 fills (slippage from the 5-minute volume per position), dated Zerodha charges on
the whole quantity.

**B0 = today's exits (XL):** -15% premium stop; ladder + L25 (peak +25% locks +5%, +30% locks breakeven (fill +
charges), +60% locks +30%, +100% locks +60%); 30-minute time stop; 15:15 square-off; strike 1-ITM from the minute before
entry. B0 is re-priced at 1, 2 and 3 lots; at 1 lot it must reproduce R152's published numbers (asserted):
R72 design 3,071 / Rs 2,16,929, final 1,202 / Rs 1,71,366; R51 design 4,208 / Rs 50,425, final 1,670 / Rs 1,34,743.

## 2. The rules (fixed now; every % is of the raw premium fill; everything not named stays as B0)

**A. Scale-out (lots 2 and 3 only; at 1 lot there is nothing to split).** One lot carries a resting limit sell at +X%
(a limit fills at its price); the other lot(s) run on B0's exits. The scaled lot's other exits are B0's too (if the trade
stops out first, all lots go together). Charges: one entry order for the whole quantity, one exit order per part.
Win is counted two ways: per trade (the position's total net > 0; this is the gated number) and per round (each exit
order as its own result; reported).
- SO8: X = 8%. SO10: X = 10%. SO15: X = 15%.

**B. Breakeven.** Once the premium's peak has reached +X%, a stop rests at the premium whose stop sale (R17 stop
slippage) nets Rs 0 after the trade's real charges (solved from the real entry fill, so known at the fill). B0's -15%
stop and ladder stay; the higher level acts.
- BE10, BE15, BE20: X = 10 / 15 / 20%.

**C. Finer step locks (added below / inside today's ladder; the highest level acts).**
- LF1 (early steps): peak +10% locks -5%, +15% locks +3%, +20% locks +8%; then today's steps.
- LF2 (tight steps): peak +10% locks +2%, +15% locks +7%, +20% locks +12%, +25% locks +17%; then today's steps.

**D. Time stops.**
- T20, T40, T45: the time stop at 20 / 40 / 45 minutes instead of 30.
- NIP30: at 30 minutes, exit only if the minute's close is below the raw fill ("not in profit"); otherwise the trade
  stays on the ladder, -15% stop and 15:15 square-off.

**E. Strike (B0's exits; only the contract bought changes; the strike is chosen from the minute before entry).**
- ATM (0 steps in the money), ITM2 (2 steps in the money).
- D60 / D70: the strike of the trade's side whose Black-Scholes delta is nearest 0.60 / 0.70, from each strike's own
  close at the minute before entry (implied vol by bisection, rate 6.5%, time to the index's next expiry at 15:30 in
  calendar days / 365; the expiry calendar is public). If no strike gives a delta, the trade takes the 1-ITM strike.
  A strike missing from the store at entry means no trade (as B0).

**F. Index stop (R131 style; read from the index).** The signal's 5-minute bar (the five minutes before entry) gives the
level: its low for a call, its high for a put. If an index 1-minute close goes beyond it against the trade, the option is
sold at the next minute's open.
- IXR: the index stop REPLACES the -15% premium stop (ladder, time stop and square-off stay).
- IXA: the index stop is ADDED; the -15% stop stays.

**G. Next-week expiry on expiry / near-expiry days - not testable, logged as not a test.** The lake holds only the
nearest (WEEK) and the monthly (MONTH) series; there is no next-week contract, and the arms never trade the index's own
expiry day. Recorded as `not-a-test` in the experiment log; no number.

Tests: A 3 x 2 lots + B 3 x 3 + C 2 x 3 + D 4 x 3 + E 4 x 3 + F 2 x 3 = 51 per arm, **102 in all** (2 arms).
No threshold is fitted: 8 / 10 / 15 / 20%, the lock steps, 20 / 40 / 45 minutes, deltas 0.6 / 0.7 and the index level
are fixed now from Boss's request and common practice.

## 3. Periods

As R152: design 20 Nov 2024 - 31 Mar 2026, with the walk-forward window Apr 2025 - Mar 2026 (nothing is fitted, so it is
reported as its own out-of-sample-style period, with its four quarters); final 1 Apr - 13 Oct 2026, read ONCE by R162
(`results/FINAL_READ`). Honesty note: Apr - Oct 2026 has been read by many earlier studies (R51 - R160); it is weak
evidence. The post window (14 - 19 Oct) is not used by R162.

## 4. What is reported (every arm x rule x lots, B0 alongside, per period)

Trades, win %, break-even win % (avg loss / (avg win + avg loss)), net, average win and loss, worst day, max drawdown
(daily), net without the 5 best trades, exit mix, twin p (net and win), BH q; scale-out also per-round win %.

## 5. Twins (final period, 500 per test, seed 162)

- Exit rules (A, B, C, D, F): for every trade whose exit differs from B0's at the same lots, the twin sells at the open
  of a uniformly random minute: if the rule exited earlier, strictly between the fill and B0's exit, B0's exits active
  before it (R106's twin); if later, strictly between B0's exit and the rule's exit, the rule's exits active before it.
  Scale-out: only the scaled lot is twinned (the rest are B0's).
- Strike rules (E): each trade instead buys a uniformly random strike from 0, 1, 2, 3 steps in the money (same entry,
  B0's exits).
- Two p values: on total net and on win rate, p = (1 + twins >= the rule) / 501. **The test's p is the larger of the two**
  (the rule must beat random on both: intersection-union).

## 6. Gates (all must hold)

1. Win rate up **>= 5 points** over B0 at the same lots in the walk-forward window AND the final.
2. Net **not lower** than B0's at the same lots in the walk-forward window AND the final.
3. Net > 0 in design, the walk-forward window and the final.
4. Twin p's BH q <= 0.10 with m = R162's 102 + every earlier test taken as p = 1: R153's m 890 (R153 24 + R152 866) and
   the 357 entries logged since (R154, R155, R160) = **m 1,349**. q within R162 alone is also shown. The raw p-values are
   written to `results/raw_pvalues.csv` for the joint BH with R161 / R163.
5. At least 40 final trades.

**Second look (descriptive, never gated): R151's pickers** on Apr - Oct 2026 (the app's exported model numbers and
thresholds, as R152's `r152picker.py`; a skipped break still holds its side and counts in the caps, as the app does).
Every rule at 1-3 lots on the picker's trades, with "net not lower than the picker's B0" shown.

## 7. Separate item: when a picker-skipped break releases its side (paper data only, descriptive, not gated, not in BH)

R152 noted the app (skipped break holds its side until its would-be exit) takes fewer trades than R151's research walk
(skipped breaks removed first): R72 603 / Rs 1.70 L vs 652 / Rs 2.19 L. On Apr - Oct 2026, B0's exits, 1 lot:
- P-APP: the skipped break holds its side until its would-be exit and counts in the 2-a-side cap (the app today);
- P-N5 / P-N10: it holds its side for 5 / 10 minutes (or until its would-be exit if sooner) and counts in the cap;
- P-IMM: it releases its side at once but still counts in the cap;
- P-R151: it is removed before the walk (no hold, no cap count; R151's research walk; parity with R151's published picks).
Reported: trades, win %, net, worst day, max DD. Labelled second look (the pickers failed their gates; Apr - Oct was read
many times).

## 8. What a pass means (CLAUDE.md)

A passing rule may act on PAPER for that arm straight away (labelled switch, Autopilot timeline, one-tap undo). Live:
earlier exits lower risk, but anything new on live trades starts in shadow behind a switch that is off by default; a
strike change or a later exit (T40 / T45 / NIP30, IXR) is not risk-lowering and needs Boss. Nothing changes in the app
from R162. Every rule is logged in `research/experiments/entries/r162-*.yaml`, failures included.
