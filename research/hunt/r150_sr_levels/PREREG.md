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
