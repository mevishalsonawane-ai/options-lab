# R140 Amendment A pre-registration: deep analysis of the arms' trades, and fake-breakout filters

Written 31 Oct 2026, before any Amendment A number. Before writing it, only R140's own design totals had been seen
(per test: trades, net, sum of losing days, drawdown), nothing per feature. Research only.

Boss, 31 Oct: *"If possible deep analysis the trades, and try to filter fake Breakout"*, with *"losing money is
important, we should always check how could we lose less"*.

**Arms:** the three breakout arms: R72 Breadth Donchian, R51 Donchian 20 + Supertrend, Liquidity 15+5 (LIQ). R52
(Open outside value) is not a breakout rule, so it is left out.

**A fake breakout** here is a trade that loses money (net < 0 after costs). The share stopped out is reported too.

## 1. The deep analysis (descriptive, design only: 20 Nov 2024 - 31 Mar 2026)

Each arm's trades under today's rules, with these features. All are read at the decision: bars closed before the
fill minute, earlier sessions, or R101's own candidate features. **Signal source: the index**, with VIX and breadth as
context.

| id | feature (signed in the trade's direction) |
|---|---|
| A1 | push into the entry: the last closed 5-min close minus the close 3 bars before, / ATR14 (5-min) |
| A2 | the day's range so far / the average daily range of the previous 20 sessions (ADR) |
| A3 | stretch: the last close minus today's open / ADR |
| A4 | beyond yesterday's extreme: the last close minus yesterday's high (call) or yesterday's low minus it (put), / ADR |
| A5 | entry minute |
| A6 | the last closed 5-min bar's close location, 0 = against end, 1 = the trade's end |
| A7 | the last closed 5-min bar's range / ATR14 |
| A8 | the 20-bar 5-min box (high - low before the last bar) / ATR14 |
| A9 | the 15-min Dow trend: with (+1), none (0), against (-1) |
| A10 | yesterday's range / ADR |
| A11 | fake pokes earlier today: 5-min bars earlier today that made a new day extreme the trade's way and closed back in their own half against it |
| C1 | R101's gap (signed the trade's way) |
| C2 | R101's realised volatility (f_rv) |
| C3 | R101's VIX 30-min change (f_vixchg) |
| C4 | R101's breadth: the number of the five indices on the trade's side (f_breadth) |
| C5 | R101's VIX regime (f_vixreg, its categories) |
| C6 | weekday (f_dow) |

For every arm and feature, the report shows:
- **slices:** quintiles of the design trades (the edges are fixed from these trades), or the categories for C5 and C6;
- **per slice:** trades, win rate, average net, the sum of losing trades, the stopped-out share;
- **the same per half:** H1 = 20 Nov 2024 - 31 Mar 2025, H2 = 1 Apr 2025 - 31 Mar 2026.

## 2. Choosing the fake-breakout filters (the rule is fixed now; the data picks within it)

For each arm and feature, the **bad slice** is the lowest or highest quintile (or a category for C5/C6) with the
lowest design average net. It becomes a **candidate filter** (drop the arm's candidates in that slice) only if, **in
both halves separately:**
- it has >= 30 trades;
- its average net is below 0;
- its average net is below the arm's own average.

Among the candidates of an arm, keep the **2** whose dropping cuts the design sum of losing days the most (walked with
today's rules).
- Each kept filter is one test.
- Plus one **combined** test per arm: drop the candidate if it is in either of the arm's kept bad slices.
- At most 9 tests. If an arm has no candidate, it has no test, and the report says so.

## 3. Gates (R140's lose-less gates, unchanged), and what is honest

- R140 PREREG section 5:
  - the sum of losing days smaller, drawdown, worst day and worst month no worse, and net > 0, in design, the
    walk-forward window and the final;
  - 2,000 random-drop twins in the final, p < 0.05;
  - BH q <= 0.10 over the Amendment A tests;
  - >= 40 final trades and >= 30 that differ.
- **The design and walk-forward gates are in-sample here,** because the slices were chosen on that data. They are
  necessary, not evidence. **The final (1 Apr - 13 Oct 2026) is the real test.** It is read once for Amendment A
  (`results/FINAL_READ_A`), after the design report and the chosen filters are pushed. It is not fresh: earlier
  studies (and R140) read it, never with these filters.
- A pass goes to Boss as a paper candidate (labelled, logged, one-tap undo). For live it could only start in shadow,
  behind a switch that is off by default. Nothing in the app changes here.
