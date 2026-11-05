# R150 pre-registration: 3-month support / resistance levels, charted, and tested on R51 Donchian

Written 5 Nov 2026, BEFORE any R150 level or result was computed. Boss (5 Nov), on enhancing R51 Donchian: "I want you
to mark all the support and resistance from last 3 month at once on the same chart (from wherever the data is up or
down from more then 2 spots), and check if that is useful".

Research only; nothing under `android/` changes.

## 1. What a level is (fixed now)

**Reading of Boss's words.** "Wherever the data is up or down from more than 2 spots" is read as: a price where the
index **turned (up from a low, or down from a high) at more than 2 places**, i.e. at least 3 turning points. Levels
with exactly 2 turning points are drawn too (fainter) and tested as a variant, in case "2 spots" meant 2.

**Bars.** The index's 15-minute bars (09:15-anchored), from 1-minute index data.

**Turning point (pivot).**
- A pivot high is a bar whose high is the highest of the 4 bars before it and the 4 after it (ties allowed). A pivot
  low is the mirror.
- A pivot is known only once the 4 later bars have closed, i.e. 1 hour after it. Before that it does not exist for any
  decision.

**Window.** Pivots confirmed in the last 63 trading sessions (about 3 months), up to the decision.

**Level.**
- Every pivot's price, highs and lows together, is sorted and grouped. A price joins the group when it is within
  0.15% of the group's running mean.
- The level is the group's mean price; its touches are the number of pivots in it.
- **S/R levels:** touches >= 3 (main), and touches = 2 (variant).
- A level above the price is resistance, one below is support. A level can be both over time.

## 2. The chart (descriptive)

- **Content:** for each of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, the last 3 months to today, with every
  level drawn at once as a horizontal line:
  - 3+ touches: solid, labelled with the touch count;
  - 2 touches: faint.
- **Data:** the lake's 1-minute index data to its end (16 Oct 2026), then Yahoo 15-minute index bars for 17 Oct to
  5 Nov 2026 (15-minute bars only).
- **Output:** a PNG per index in `results/` and one page with all five.

## 3. The usefulness test on R51 Donchian

**The trades.** R51 Donchian 20 exactly as the app runs it today, from R115's engine with parity checked against the
published arm (as R140):
- the Donchian-20 break on 5-minute index bars with Supertrend agreeing (R73 T7);
- the 1-ITM option;
- the app's exits (-15% stop, the 30-60-100 lock with L25, 30-minute cap, 15:15);
- every index;
- R51's clean lake stores.

**Filters (skip rules; each only removes trades).** The decision price is the index's close at the minute before the
fill (the decision minute). Levels are those known then (pivots confirmed before that minute).

| id | skip the trade when |
|---|---|
| W3 | a level with >= 3 touches lies AHEAD of the trade within 0.25% of the decision price (resistance just above a call; support just below a put): little room to run |
| W2 | the same with >= 2-touch levels |
| B3 | the break did NOT cross a >= 3-touch level. Kept: a call whose decision price is above a >= 3-touch level that the index close 5 minutes earlier was at or below (mirror for puts) |

## 4. Periods and gates (R140's, unchanged)

- **Periods:** design 20 Nov 2024 to 31 Mar 2026 (with R115's walk-forward part); final 1 Apr to 13 Oct 2026, read
  ONCE (`results/FINAL_READ`).
  - R51's own trades in the final period were read before (R51, R73, R140, R141, R144a).
  - These level filters were never computed on them.
- **Gates (R140 section 5, "lose less").** In design, walk-forward and final, all of:
  - the sum of losing days is smaller;
  - the drawdown, worst day and worst month are no worse;
  - the arm still makes money.

  Also:
  - a random-skip twin p < 0.05 on the final sum of losing days;
  - BH q <= 0.10 over the 3 filters;
  - at least 40 final trades;
  - at least 30 final trades changed.

## 5. Verdict rules (CLAUDE.md)

- A passing filter is a PAPER candidate for R51: risk-lowering, logged, one-tap undo. Live, it is shadow first, behind
  a switch that is off by default.
- Failing filters stay notes.
- Experiment entries are `r150-*.yaml`.
- **Signal source:** the index; the levels are read from the index's own bars.

## 6. Tests

Pytest tests cover:
- pivot confirmation (no pivot before its 4 later bars close);
- the grouping;
- the 63-session window;
- the look-ahead guard (scrambling bars after the decision minute changes nothing);
- each filter's rule.

## Amendment A1, 5 Nov 2026: before any R51 result (only the level counts had been seen)

- **What the levels looked like.** Section 1's levels (pivots with 4 bars each side) are too dense to mean much. On
  5 Nov there were 26-31 levels with 3+ turns per index over 3 months, about one every 0.3%. A level was therefore
  within 0.25% of almost any price. No R51 trade, P&L or filter result had been computed when this was seen.
- **Added: "major" levels.** These are the same, but a pivot needs **12 bars (3 hours) each side**. That gives 11-13
  levels with 3+ turns per index over the same 3 months.
- **Two filters are added:**
  - **W3M:** W3 on major levels.
  - **B3M:** B3 on major levels.
- **BH runs over all 5 filters** (W3, W2, B3, W3M, B3M). Everything else is unchanged.
- **Charts.** They draw the major levels (solid: 3+ turns, labelled with the count; dashed: 2). A second "dense" chart
  per index draws section 1's levels.

## Amendment A2, 5 Nov 2026: fake breakouts (Boss: "While studying also take care of fake break outs"); before any R51 result

**Two exits added (each can only sell earlier; R51's own exits stay on, and the earlier one wins):**

| id | exit |
|---|---|
| FX | for an R51 trade whose break crossed a 3+-turn level (dense levels), sell at the next minute's open after the first 5-minute index bar (09:15-anchored, closing at or after the fill) that CLOSES back on the other side of that level. A call crossed above it: a close below it; mirror for puts. That is a fake breakout. Trades that crossed no level are unchanged |
| FXM | the same with major levels |

- **Pricing:** R115 / R106's forced-exit engine, as R140's exit rules.
- **Twin:** R140's exit twin. Each trade the rule sold earlier is sold at a uniformly random minute between the fill
  and today's exit instead.
- **Gates:** R140's, as for the filters.
- **BH** now runs over the 7 tests: W3, W2, B3, W3M, B3M, FX, FXM.

**Descriptive (design only, no gate):** for R51's signals, the share whose break crossed a level, and of those the
share that closed back through it within 30 minutes (fake). This is compared with how often signals that crossed no
level went back below their own break bar's close within 30 minutes.

## Amendment B, 5 Nov 2026: support / resistance on the 5-MINUTE timeframe (Boss: "What if we add support and resistance on 5min time frame?"); before any 5-minute result

**What was seen before this was written.**
- R150's own results, with no level filter passing.
- Level COUNTS for 5-minute pivots on NIFTY / BANKNIFTY to 16 Oct (no trade result). Over 63 sessions they are again
  too dense: 22-35 levels with 3+ turns.

**5-minute levels:**
- **Pivots:** 5-minute index bars (09:15-anchored); a pivot needs **6 bars (30 minutes) each side**. It is known 30
  minutes after its bar.
- **Grouping:** the same rule, 0.15%.
- **Two lookbacks:**
  - **L5:** the last 5 sessions (about 6-9 levels with 3+ turns);
  - **L20:** the last 20 sessions (about 15-19).

**Tests (on R51 exactly as R150):**

| id | rule |
|---|---|
| W5 / W20 | skip when a 3+-turn level is within 0.25% ahead |
| B5 / B20 | skip unless the break crossed a 3+-turn level |
| FX5 / FX20 | the fake-breakout exit at the crossed 3+-turn level (A2's rule) |

**BH** runs over these 6. R150's 7 are also reported together with them (13).

**Periods:**
- **DESIGN:** 20 Nov 2024 to 31 Mar 2026 (with R115's walk-forward part).
- **FINAL:** Apr to Oct 2026. Already read by R150 for 15-minute levels, so this is a second look, reported but not
  the deciding read.
- **UNSEEN: 1 Dec 2022 to 19 Nov 2024.** R143's old store (R51's trades there were read by R143 / R144a; these level
  rules never were). This is the deciding holdout, read ONCE (`results/UNSEEN_READ_B`).

**Gates (R140's lose-less gates):**
- in DESIGN, WF and UNSEEN: losing days smaller, drawdown / worst day / worst month no worse, still in profit;
- twin p < 0.05 in UNSEEN;
- BH q <= 0.10 over the 6;
- at least 40 UNSEEN trades and 30 changed.

## Amendment C, 5 Nov 2026: the same 5-minute "wall ahead" skips on R72 Breadth Donchian (Boss: "What we add this in breath donchain"); before any R72 result

**What was seen before this was written.** Amendment B's R51 results: W20 was the lead. No R72 number with these
rules had been computed.

**The arm.** R72 Breadth Donchian exactly as the app runs it, from R115's engine with parity against the published
arm (as R140 / R143).

**Tests:**
- **W20-72:** skip an R72 trade when a 3+-turn 5-minute level (last 20 sessions) is within 0.25% ahead of it, on the
  traded index. This is the primary test.
- **W5-72:** the same over the last 5 sessions.
- **BH** runs over these 2.

**Periods, gates and holdout:**
- As Amendment B.
- **Deciding holdout:** R72 on the unseen two years (1 Dec 2022 to 19 Nov 2024; R143's base 3,395 trades,
  -Rs 3,06,931), read once (`results/UNSEEN_READ_C`).
- **Apr to Oct 2026:** a second look.
- **Gates:** R140's lose-less gates in design, walk-forward and unseen, twin p < 0.05, BH q <= 0.10, at least 40
  unseen trades and 30 changed.

## Amendment D, 5 Nov 2026: EMA, pivot points, VWAP and volume profile ON TOP of R51 + W20 (Boss: "What we add vertical volumn and pilot point, and ema and vwap to this, will this help more" / "I am asking on top of this"); before any result

**The base: R51 with Amendment B's W20 skip** (R51 + W20), as the app would run it.
- **Each layer** below is an EXTRA skip on that base, tested against it.
- **Seen before:**
  - each layer ALONE on R51 was tested and rejected before: R73 (EMA), R51-r29 / R51-r41 (R1 / S1 pivots), R105D
    (volume), R63 / R73 / R74 (VWAP);
  - R51 + W20's own results.

**Layers testable on all periods (index prices only):**
- **D1 PIV:** previous session's floor pivots, P = (H + L + C) / 3, R1 = 2P - L, S1 = 2P - H, R2 = P + (H - L),
  S2 = P - (H - L), from the index's previous session. Skip when any of them lies within 0.25% ahead of the trade (as
  W20's wall rule).
- **D2 EMA:** skip unless the last closed 5-minute index close is on the trade's side of the 20-bar EMA of 5-minute
  closes (continuous across sessions). A call needs the close at or above the EMA; mirror for puts.

**Layers with data on only 57 sessions** (index futures minutes, 29 Jul to 19 Oct 2026; the R51 store ends 13 Oct).
These are DESCRIPTIVE only, with no gate:
- **D3 VWAP:** skip a call when the near-month future's session VWAP (from 09:15) is above the future's price at the
  decision (mirror for puts).
- **D4 volume profile ("vertical volume"):** the previous session's point of control and value area (70% of volume)
  from the future's minute volume at price (0.05% bins). They are added to the levels for the wall-ahead rule.

**Periods and gates (D1, D2):**
- design and walk-forward;
- Apr - Oct 2026 (second look);
- the unseen two years. **These were already read for W20, so this is a SECOND look there too** (disclosed).
- R140's lose-less gates vs R51 + W20 in design, walk-forward and unseen;
- a random-skip twin (dropping the same number of R51 + W20 trades at random) p < 0.05 in unseen;
- BH q <= 0.10 over the 2.
- **Even a pass needs the forward shadow test** before any paper use, because no unread data is left.
