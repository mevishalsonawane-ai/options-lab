# R177 pre-registration: volume-profile "trap" filters (from Boss's PDF, Cases 1, 2, 5) on Liquidity 15+5, R72 and R51

Written 15 Nov 2026, **before any R177 trade or P&L number was computed**; committed on `claude/r177-vp-trap` before the
code's first run on data. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 15 Nov 2026, shared a PDF ("Volume Profile & Order Flow Trading Setups (Cases 1-6)") and asked: *"Check if this
helps to increase the profit and buying wrong trades"*, then chose option B: a trap filter from Cases 1, 2 and 5 without
delta. (The PDF's footprint / delta / CVD steps need tick data with aggressor side, which the lake does not have for a
year; they are NOT tested here.)

**Signal source: the index** (CLAUDE.md default). The arms' entries are unchanged; the filters read only the traded
index's 1-minute bars (yesterday's profile and today's closes). Value areas are TPO (time-at-price) profiles, because
the lake has no index volume. No rule reads an option's chart.

## 0. Already tested near this (read before writing)

- R9: volume profile / absorption / delta divergence: no direction signal (only day size).
- R23: first-hour TPO value area, trade the close beyond VAH / VAL: 1,174 trades, -Rs 41,670, rejected.
- R62: anchored volume-profile POC / LVN + SMC entry: rejected (final 46 trades, -Rs 34,875).
- R150 D4: yesterday's POC / value-area edges as walls on R51: 316 trades Rs 60,288 -> 277 trades Rs 47,355 (worse).
- R89: previous-day high / low breaks: rejected on 4 of 5 indices.
Expected (said now): R150 D4 suggests value-area edges do not separate good from bad Donchian breaks; R177 may fail.
What is new: the trap condition (a break beyond the edge that CLOSES BACK INSIDE), as an early exit or a skip.

## 1. Levels (fixed now)

For each traded index and day, from the PREVIOUS trading session's 1-minute index bars (09:15-15:29):
- TPO profile: each minute adds one count to every price bin its [low, high] range touches. Bin = the index's strike
  step / 5 (NIFTY, FINNIFTY 10 points; BANKNIFTY, SENSEX 20; MIDCPNIFTY 5).
- POC = the bin with the most counts (a tie: the one nearest the session's mid-price). Value area = start at the POC, add
  the larger of the next bin above / below (ties: above) until >= 70% of all counts. VAH = top of the highest VA bin,
  VAL = bottom of the lowest.
- PDH / PDL = the previous session's high / low.
- No previous session in the stores, or fewer than 300 valid minutes in it: no level, the filter does nothing that day.

## 2. The three rules (fixed now; each only exits earlier or skips; nothing else changes)

`s` = the candidate's signal row (the entry row er - 1; its close is the signal bar's close). Call = long the index,
put = short.
- **X1 VA trap exit (Cases 1 / 2)**: a call whose signal close is ABOVE VAH (a put: BELOW VAL). After the fill, the first
  index 1-minute close back at or below VAH (put: at or above VAL) in rows er .. er + 9 (the first 10 minutes) closes the
  trade at the next minute's open (a forced exit, as the arms' index exits). Other trades are unchanged.
- **X2 day-range trap exit (Case 5)**: the same with PDH for calls and PDL for puts.
- **K1 skip after a failed break (Case 2's trapped buyers / Case 1's trapped sellers)**: skip a call if, in the 30 rows
  before the signal row (s - 30 .. s - 1), the index had a 1-minute close above VAH and a LATER close (still before s)
  back at or below VAH; puts mirrored with VAL. A skipped candidate is removed before the walk (it opens nothing, as a
  filter in the app would).
- Exits combine with the arm's own: a forced row is the earlier of the arm's index exit (Liquidity) and the trap row.

**Tests: 3 rules x 3 arms = 9, at 1 lot.** 2 and 3 lots and R151's pickers (R72 / R51) are reported, not gated.

## 3. Window, engine, costs (R176 Amendment A's, unchanged)

The last year the lake covers: **20 Oct 2025 - 19 Oct 2026** (stores built to 19 Oct). Periods: early (20 Oct 2025 -
31 Mar 2026), final (1 Apr - 13 Oct 2026, read by many studies: weak evidence), last 6 months, whole year. R106's
simulator and walk, R17 fills (stops sold as stops), dated Zerodha charges. Parity: today's rules at 1 lot on the final
must give R172's check (LIQ 357 / Rs 11,155; R72 1,200 / Rs 1,70,336; R51 1,668 / Rs 1,31,546).

## 4. Twins (500 a test, seed 177)

- X1 / X2: as R176: every trade the rule closed earlier than A gets a random exit minute strictly between its fill and
  A's exit (A's exits active before it).
- K1: selection twin: from A's year trades, drop as many trades as K1 removed (A trades minus K1 trades, at least 0),
  chosen at random; p = (1 + twins with net >= K1's) / 501.

## 5. Gates (per arm, 1 lot, vs A)

1. Net > 0 in early, final, last 6 months and the whole year.
2. Net above A's in early, final and the whole year.
3. Twin BH q <= 0.10, **m = 3,988 + 9 = 3,997** (every earlier test, R175's 45 and R176's 18 included).
4. At least 40 final trades.
5. Within the account stop rule (max drawdown <= Rs 40,000 and worst month >= -Rs 13,000) in every period. Note now:
   today's rules (A) already break this at 1 lot on R72 and R51 this year (R176), so this gate alone may fail them; the
   report says which gates a rule passes apart from it.

A passing rule only exits earlier or skips (risk-lowering): paper straight away is allowed by CLAUDE.md; live only in
shadow behind a switch off by default. A failing rule stays a note.

## 6. Tests and the look-ahead guard

pytest on synthetic data: the TPO profile (POC, 70% value area, ties, too few minutes), PDH / PDL, the trap row (first
close back inside within 10 rows; none; NaN minutes; a put mirror), K1 (a failed break in the window; one only before it;
one after the signal: ignored), the earlier-of forced rows, the selection twin. Look-ahead guard: levels and decisions are
unchanged when every bar after the decision row (and every bar of the trade day for the levels) is replaced by noise.

## Amendment A (15 Nov 2026, before any number for it was computed): K1 + Rs 1,000 stop + step trail

Boss, 15 Nov 2026, after reading R177: *"On the top of this add 1k stoploss and as we discussed earlier, on every 1000 add
stoploss of 500, and on 1500 make it 1000"*.

- **Rule K1S**: K1 (section 2, unchanged) AND R176 Amendment B's stop rule on every trade K1 keeps: a -Rs 1,000-a-lot stop
  at entry (rounded up to the tick; only where tighter than -15%); once the best open P&L reaches +Rs 1,000 a lot, the stop
  = entry + (floor(best / 500 L) - 1) x 500 L (so +Rs 500 a lot at +Rs 1,000, +Rs 1,000 at +Rs 1,500, ...); no target;
  every other exit stays. Before charges, per lot; every stop is sold as a stop (R17).
- Also reported (not tested again): **S1k** = the stop rule alone on today's trades (it must reproduce R176 Amendment B's
  year numbers on these stores: an engine check).
- Window, periods, costs, pickers (descriptive), lots 1-3 reported, gates at 1 lot: as R177 section 5.
- **Twin** (500, seed 177): does K1 add to the stop rule beyond chance? From S1k's year trades, drop as many trades as
  K1S has fewer (at least 0), at random; p = (1 + twins with net >= K1S's) / 501.
- **Tests: 3 more** (3 arms); BH **m = 3,997 + 3 = 4,000**.
- Honesty: chosen after seeing R176's and R177's results on the same year (data-led); the final period is read again
  (`results/FINAL_READ_A`). Weaker evidence than a fresh test on new days.

## Amendment B (15 Nov 2026, before any number for it was computed): a Rs 1,000 gap under each step

Boss, 15 Nov 2026, after Amendment A: *"Test with 1000 gap on every step"*.

- **Stop rule G** (replacing the Rs 500 gap): entry stop -Rs 1,000 a lot (as Amendment A; only where tighter than -15%);
  steps unchanged (every Rs 500 a lot of best open P&L, starting at +Rs 1,000 a lot); the stop = entry +
  floor(best / 500 L) x 500 L - 1,000 L, rounded UP to the tick: **cost (the entry price) at +Rs 1,000 a lot, +Rs 500 at
  +Rs 1,500, +Rs 1,000 at +Rs 2,000, ...**. Only moves up; no target; every other exit stays; stops sold as stops.
- Variants: **SG** = rule G alone on today's trades; **K1SG** = K1 + rule G. Both tested.
- Window, periods, costs, pickers (descriptive), lots, gates: as Amendment A. Twins (500, seed 177): SG = R176's random
  exit twin (random exit minute between the fill and A's exit for every trade SG closed earlier); K1SG = Amendment A's
  selection twin against SG.
- **Tests: 6 more** (2 variants x 3 arms); BH **m = 4,000 + 6 = 4,006**.
- Honesty: chosen after seeing R176 / R177 on the same year; the final period is read again (`results/FINAL_READ_B`).
