R96 round 1: LEADS (continue)

0 of 9 pre-registered MCX option-buying rules passed the design gates (5 Aug 2025 - 14 Jul 2026), so the locked holdout
(15 Jul - 19 Oct 2026) was not read and nothing goes to paper. Ideas tried so far: 9.

- Dead: event straddles, event follow, US-session momentum (all lose before costs), IV-below-forecast straddles,
  squeeze direction, wide-range continuation.
- Lead: NR7 breakout on the future -> buy 1-ITM for 3 sessions: +Rs 1.84 lakh on 58 design trades, beats random days
  (p 0.006, BH q 0.054) and beats them on 13.5 years of daily futures; but it fails the fold, -13k month and drawdown
  gates, and its profit rests on 7 model-priced deep-ITM silver exits. Round 2 re-tests it with real-print exits only.
- Weaker leads: pullback in trend (negative over 2012-25), squeeze straddle (6 trades).

Write-up: `research/HUNT_R96_ROUND1.md` (with the pre-registered round-2 list). Code, tests, results, ledger:
`research/hunt/r96_mcx_buy/`. Experiment entries: `research/experiments/entries/r96-1-*.yaml` (9).
