# R91 notes (dated, each before the numbers it affects)

## N1 (20 Oct 2026, after the code and tests, BEFORE any P&L; only design signal COUNTS were looked at)

`study.py counts` (results/counts_design.json): the funnel per cell, no price, option or outcome.

1. **Setups are rare.** Design fills (underlying, before the option print check) per cell: indices 10-43 (B: NIFTY 25,
   BANKNIFTY 27, SENSEX 18, FINNIFTY 29, MIDCPNIFTY 36 in 336 sessions, about 0.1 a day / 0.4 a week); MCX 9-48. About
   120-190 sweeps per index (60-130 MCX); half find no strong 5m candle within 6 bars, 15-30% are retaken before the
   candle (S15: 30-45%). Most cells will be under the 40-trade gate (G1). Nothing changes: the plan, the variants and
   the gates stand as pre-registered.
2. **Implementation choices the PREREG leaves open (fixed in `sweep.py`, tested):**
   - The pre-entry cancel includes the strong candle itself: a candle whose high reaches the stop (sweep high + 1 tick)
     cancels the setup (the stop would already be hit).
   - In one minute that both reaches the entry and the stop, the order counts as filled first (conservative); the
     fill minute's close then decides a stop exit (R89 rule).
   - A minute before the fill window (MCX before 09:15) cannot fill but can cancel the order at the stop.
   - One position at a time: sweeps are processed in time order; a setup whose strong candle closes while a position
     is open is skipped ("busy"), even if its order would have filled after the exit.
   - A sweep needs the strong candle in the SAME session; a sweep on the last bar of a session has no setup.
   - MCX levels: a swing whose fractal window spans a contract roll is dropped; all levels are cleared on the roll.
   - The target snapshot (T-SW) uses the levels intact at the strong candle's close, swings of every side's age within
     5 sessions, today included.
