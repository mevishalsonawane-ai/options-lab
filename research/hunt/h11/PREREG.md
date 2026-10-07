# h11 pre-registration (written 2026-10-07 BEFORE any h11 signal, trade or P&L was computed)

Question: NIFTY / SENSEX index options are the most liquid in India (weekly expiries survived Nov 2024), so CAPACITY
is not their problem; h4 found Liquidity 15+5 flat there with the index stop fixed a priori at ~0.065% of the index.
Do a SMALL grid of plausible NIFTY/SENSEX-specific settings rescue it? Option BUYING only. Everything else of the
arm is UNCHANGED (pool-on-swing break, lookback 20, confirm 10, entries 09:20-14:00 bar close, -15% premium stop,
20-min/+5% time stop, index stop beyond the level, next-level target, failed break, new level, 15:10 square-off,
expiry days skipped, one position per book). Code: h4's port (research/hunt/h4/comps.py) generalised.

Already known before writing this (from HUNT_H4.md): the baseline cell (15+5, 1x stop, room 1, 1-ITM, near) is
flat / negative net (NIFTY pre -26.9k, SENSEX +5.8k per lot). That is why the grid exists; it is counted as a variant.

## Grid: 24 variants per index (NIFTY, SENSEX separately -> 48 daily series)
- Core 16, series = 'near' (nearest weekly; it is always a weekly for NIFTY/SENSEX):
  books {15m+5m, 30m+5m} x index stop {1x = NIFTY 15 / SENSEX 50 pts, 2x = 30 / 100 pts}
  x room filter {>= 1, >= 2 index stops to the next level} x strike {ATM, 1-ITM}.
- Monthly 8 (stands in for "next weekly": Dhan's data has no 2nd-weekly contract), series = 'month' (nearest
  monthly), room >= 1: books x stop x strike.
- The room filter is measured in the variant's own index stops.

## Fill / P&L model (fixed; h10's cap.py unchanged)
- 'real' fills (app bps + 1-4 tick half-spread from 5-min volume) + app SandboxCosts; participation cap 15% of the
  last-5-min traded lots; square-root impact kappa = 0.02 (report 0.01 / 0.04); exits worked over minutes if capped.
- GROSS = same trades and quantities at bar prints, no costs (reported side by side).
- Selection uses 1 lot per trade (lot in force on the date).

## Choice (data before 2025-10-01 only)
- Per index, the chosen variant = highest pre-holdout net per trading day at 1 lot (all data before 2025-10-01).
- Anchored walk-forward by year: test years 2023, 2024, 2025 (Jan-Sep) (SENSEX from 2024: its weekly data starts
  May 2023); each test year uses the variant best on all earlier data; the concatenated out-of-sample P&L is reported.
- Random entries: 5 (min 3) random alternatives per signal, same day/book/exits/strike rule/series, random side,
  window 09:19-13:59; p = P(random mean net/trade >= real). BH over the 48 pre-holdout p's.
- White RC / Hansen SPA over the 48 pre-holdout daily net series (1 lot) vs not trading.
- PASS (pre-holdout) for an index = (a) walk-forward out-of-sample net > 0 AND (b) chosen variant's BH q <= 0.05
  AND (c) chosen variant pre-holdout net > 0 in at least 2 of the 3 years 2023/2024/2025-Sep with trades.
- HOLDOUT (2025-10-01 .. latest), run ONCE on the chosen variant(s): PASS confirmed if holdout net > 0 and holdout
  random-entry p <= 0.05. SPA p is reported as the strength of evidence (h4/h10 standard: SPA p <= 0.10 would be
  'significant after the grid').

## Capacity and size (only for an index that passes pre-holdout; else reported for information)
- Capacity N = floor(0.15 x P25 of the exit-minute 5-min traded lots) on 2024-12-01..2025-09-30 (h10's definition).
- Lots = the value in {1,2,3,5,8,10,15,20,30,50,75,100,150,200,300,500} with N as ceiling that maximises
  2023-06-01..2025-09-30 net/day at kappa 0.02. Report Rs/day added, gross, max DD, P(losing month).
- Combination with the BANKNIFTY plan (h10 flat BN 26 / FIN 3 / MIDCP 11, kappa 0.02, no limits and both limits):
  daily correlation, combined net/day, max DD, worst month, bootstrap P(losing month), pre and holdout.
