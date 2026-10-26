# R105 pre-registration: Donchian giveback (trades that go +Rs 1,000-2,000 and end in a loss)

Written 26 Oct 2026, committed and pushed BEFORE any R105 rule is priced and before any cohort or bootstrap number is
computed. The only thing run before this file: the baseline reproduction check (B and C reproduce R75's published
numbers to the rupee: C design 3,071 trades / Rs 2,16,929, final 1,202 / Rs 1,71,366; B design 4,208 / Rs 50,425,
final 1,670 / Rs 1,34,743), and a read of the phone's redacted diagnostics 19-24 Oct (counts, refusals and the 21 Oct
R72 orders; no backtest number). Research only: nothing in `android/**` or `.github/**`.

Boss, 26 Oct: *"I saw too many Donchian [losses] from Thursday, Friday and today. Should we make any change in Breadth
Donchian? Most of the time it goes to 1,000 to 2,000 profit and then we end up losing money. Please check and validate,
and use all the PDF/book studies we have done, and check if anything helps us recover the loss without affecting the
profit."*

**Disclosure first.** R75 (16 Oct) recommended freezing Donchian and judging it on paper, because the final window
(1 Apr - 13 Oct 2026) has been read by R51-R75, R101 and R104. R105 is a further read of that same window, at Boss's
request. So: (1) the final window is weak evidence and is disclosed as such; (2) the family is small and aimed at one
cohort; (3) BH counts every earlier Donchian test; (4) the only unseen data are 14-19 Oct (lake) and paper from 19 Oct,
reported apart with their tiny sample sizes.

## 0. Signal source

Every rule keeps the signal on the **index**: Donchian 20 on the index's 5-minute bars (B also needs R73's Supertrend
(10, 3) agreement; C needs 4 of the 5 indices to break on the same bar). The 1-ITM option is bought. Exits X1-X6 and
X9 are stops / locks / a partial target on the **option premium** (allowed by CLAUDE.md). X7, X8 and X10 read the
**index** (the breakout level, the index's 10-minute bars). No rule reads an option's chart for a signal.

## 1. Arms and baseline exits (exactly as paper today; R75's code, R64 engine, R51 Amendment A clean stores)

- **C = R72 Breadth Donchian** (C1, 4 of 5). The arm Boss asked about. Gated.
- **B = R51 Donchian 20 + Supertrend (10, 3) agreement** (R73 T7, what the R51 paper arm runs). Gated.
- Exits P (both arms): stop -15% on the premium; +5% locked once +25% (L25); breakeven (fill + round-trip charges) once
  +30%; +30% once +60%; +60% once +100%; out 30 minutes after the fill; square-off 15:15; 1-ITM; entries 09:20-15:10;
  one open a side per index, at most 2 a side a day; never on the index's own expiry day; 1 lot (lots in force).
- Engine: R64's held-contract simulator (next-minute fills at the open within 3 minutes, R17 liquidity slippage, stops
  on the minute low with gap fills at the worse open, a lock set at a minute's end acts from the next minute, dated
  charges). R105's engine adds only the new exit mechanics below; with none switched on it must equal R64's trade by
  trade (asserted by the study and the tests).

Periods: design 20 Nov 2024 - 31 Mar 2026; walk-forward (WF) = the 4 design quarters Apr-Jun 2025 ... Jan-Mar 2026;
final 1 Apr - 13 Oct 2026, read once by `r105study.py final` (guarded by `results/FINAL_READ`); extra out-of-sample
(OOS) = every session in the lake after 13 Oct (14, 15, 16, 19 Oct 2026; 4 sessions), read once by `r105study.py oos`
after the final read, reported apart, never gated (too small).

## 2. The rules (10 per arm, 20 tests). Each is ADDED to exits P; whichever exit comes first wins.

| id | rule | source |
|---|---|---|
| X1 BE10 | stop to breakeven (fill + round-trip charges) once the premium has traded +10% above the fill | premium |
| X2 BE15 | same, once +15% | premium |
| X3 L3AT15 | stop to +3% once +15% | premium |
| X4 RS1K | stop to breakeven once the open profit (best premium high - fill) x quantity reaches Rs 1,000 (1 lot) | premium (Boss's rupees) |
| X5 TT15 | from 15 minutes after the fill, if the premium has traded +8% at any time, stop to breakeven | premium + time |
| X6 GB50 | once the premium has traded +12%, the stop sits at fill + 50% of the best gain so far (rises with each new high) | premium |
| X7 IDX0 | index breakout-level stop from the fill: a 1-minute index close back through the broken channel level (below the prior-20-bar high for a call, above the prior-20-bar low for a put) sells at the next minute's open | index |
| X8 IDX10 | X7, armed only once the premium has traded +10% | index (armed by premium) |
| X9 PART15 | book scale-out (Carli `book-carli-01` "close half at T1 and move the stop to entry"; Douglas / Trader Tom `book-tom-20`): with 2 lots, lot 1 is sold at +15% (limit); once +15%, lot 2's stop goes to breakeven; lot 2 otherwise on exits P. Reported per lot (the 2-lot net / 2, each leg charged as its own 1-lot orders) | premium |
| X10 TOM05 | book Trader Tom `book-tom-05`: once the premium is +15% (+1R, R = the 15% stop), stop to breakeven, and trail on the index: sell at the next minute's open when a 1-minute index low (call) / high (put) goes through the low / high of the last COMPLETED 10-minute index bar (09:15-09:24, 09:25-09:34, ...); the trail only tightens | premium + index |

Not repeated (already tested on Donchian): BE at +10% with a 10-point trail and the step trails (R65 S5/S75/S10), -10%
once +15/20/25 (R67), +5% once +20/25/30 (R66; L25 is now in exits P), the staircases (R68), the 1-point trail (R69),
the -10% stop and hold-to-15:15 (R64), the trend-turn index exits EMA20 / Supertrend flip / VWAP / Donchian-10 opposite
break (R63 T1-T4), R72's C1 re-tune with an index stop at the channel mid, and R104 F11's book overlays (Wyckoff swing-
low trail, cost-cover trail, no-progress exit, last-15-minute tighten, Aziz levels, Heitkoetter, Porwal, breadth-turn
exit). X7 is the nearest to R72's mid-channel stop and to R63 T4; it is new because the level is the breakout level
itself.

## 3. Gate (all must hold; never relaxed)

a. Net (after charges and slippage) above the baseline's in design, in the WF window, and in the final; and better in at
   least 3 of the 4 WF quarters.
b. Worst day and max drawdown no worse than the baseline's by more than 2%, in design and in final.
c. Exit-twin: beats 500 random-minute twins (p < 0.05) in design and in final. A twin picks as many of the baseline's
   trades as the rule changed (held >= 2 minutes) and sells each at the open of a uniform random minute in (entry,
   baseline exit] (X9: lot 1 only); p = (1 + #twins >= rule) / 501. Rule and twin are both compared without re-walking
   the book (pure exit timing).
d. BH q <= 0.10 with p = max(design p, final p), over the FULL family: these 20 tests plus every earlier Donchian test
   logged in `research/experiments/entries/` (ids r63-*, r64-*, r65-*, r66-*, r67-*, r68-*, r69-*, r72-*, r73-*,
   r75-*, r101-*, r104-* whose text names Donchian / Breadth / R51 / R72), each at the twin / random p its entry states
   (the largest p it states), else at p = 1. R104 F11's 20 tests are added at p = 1 if not logged one by one.
e. At least 30 changed trades in design.
f. The design gain is still >= 0 without the 5 largest per-trade gains.
g. Still better in the final at twice the slippage.

The headline nets (a, b) re-walk the book under the rule (an earlier exit can free the side for a later signal, as
the app would). A rule that passes may go on **paper** for that arm (applied later by the app session, logged in the
Autopilot timeline, undoable, never live). A rule that fails stays a note.

## 4. The giveback cohort (Boss's question), measured on the baselines, all periods and OOS

For each baseline trade: peak = the best premium high from the fill minute to the exit minute; peak Rs = (peak - fill)
x quantity; peak % = peak / fill - 1. Report, per arm and period, with counts:
- trades whose peak Rs >= 1,000 and whose net < 0 ("gave back"); split by peak Rs 1,000-2,000 and >= 2,000;
- trades whose peak % is in [+8%, +25%) and whose net < 0; split +8-15% and +15-25%;
- their total net (what they cost) and the same trades' net under each rule X1-X10;
- the cost to the other trades (the winners each rule cut).
The peak uses minute highs, so it is an upper bound of what could have been sold.

## 5. Validation of the paper record (task 1)

- Paper (Boss's diagnostics, 26 Oct 12:20): R72 11 real trades -Rs 3,583 (+15 would-be refused by the same-direction
  cap, -Rs 4,313); R51 19 real -Rs 11,997; R51 with its Supertrend filter 14 -Rs 16,188 vs the same 14 on plain rules
  -Rs 5,591.
- Bootstrap (seed 105, 20,000 draws): from C's (B's) final trades and from its WF trades, (i) iid samples of 11 (19, 14)
  trades and (ii) every run of 11 (19, 14) consecutive trades in time order; and (iii) every run of 6 consecutive
  sessions (19-26 Oct = 6 sessions). Report the percentile of the paper net and P(net <= paper).
- Fills and exits: check every paper order visible in the diagnostics against the research rules (entry minute, fill
  delay, the 30-minute exit, the lock / stop prices, refusals), list each mismatch, and say which are app behaviour
  that the backtest does not model (same-direction cap, daily loss stop) and which look like bugs.
- The lake ends 19 Oct and the phone's live recordings are sealed (no passphrase here), so 20-26 Oct paper trades
  cannot be replayed minute by minute; this is a limit, disclosed.

## 6. Tests

`tests/test_r105.py`: engine = R64 with no rule (synthetic); each rule on hand-made paths (arming at the threshold, from
the next minute, gaps, NaN minutes); the look-ahead guard (changing any bar after a decision minute never changes the
decision at or before it; for X7 / X8 / X10 the index level used at minute m is known by m-1); the partial leg's
accounting; twins, BH (with prior-family p's), gate, bootstrap percentiles; the one-time final / OOS read.
