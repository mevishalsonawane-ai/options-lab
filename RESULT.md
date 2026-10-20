# R90 result (20 Oct 2026): "Almazov Trade: MTF Structure & BOS Entries"

**Verdict: it does not pay after costs. 0 of 99 pre-registered cells (11 settings x 9 instruments) passed; the locked
holdout (1 Apr - 19 Oct 2026, read once) confirmed it. Nothing goes to paper; no app change. Useful only as a chart
overlay showing structure (swings, trend, BOS / CHoCH), not as a signal.**

- The indicator's own chart model (no fees, positions held for days) looks positive on most settings; held intraday the
  way Boss trades (flat 15:15), its higher-timeframe target is rarely reached and the bought option loses time value
  (5m chart, five indices: -Rs 45,735 on 76 design trades, -Rs 17,573 on 42 holdout trades).
- No setting beat random entry minutes after the signal (after-trigger twins); 79 of 99 cells had under 40 trades.
- Best design cell MIDCPNIFTY 15m / day HTF +Rs 48,830 (34 trades) lost Rs 25,462 (9) in the holdout. MCX lost everywhere.
- Same busy days as Breadth Donchian / ORB / R89, near-zero correlation, loses on both kinds of day.

Write-up: `research/HUNT_R90_ALMAZOV_BOS.md`. Code and tests: `research/hunt/r90_almazov_bos/`. Signal source of every
rule: the underlying.
