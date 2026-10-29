# R130-R132: the sweep/MSS/FVG video, the 15-minute trend and the liquidity PDFs' full playbook all fail (research only; nothing in the app changed)

1. R130, the video setup as stated (signal on the index, ATM option, real costs): 218 design trades, 45% won, -Rs 307 a trade; holdout -Rs 750. Wick to wick: -Rs 26 a trade (485); holdout -Rs 258.
2. R130's improvement search (96 versions + the profit lock) and 65% search (32 versions, walk-forward): nothing passes; best honest win rate 52.5% out of sample; the lock reached 67% only by losing money.
3. R131, the 15-minute trend on its own (+ R123's network): passed the design gates with the network (+Rs 267 / +Rs 106 a trade) but failed the holdout (-Rs 122; +Rs 88 on 167 trades, p 0.31) and NIFTY 2017-2020 (+4.0 pts vs ~7.3 needed).
4. R132, Boss's two liquidity PDFs: added what we were missing (previous-week and round-number sweeps, sudden wick + reclaim, stop beyond the pool, target at the opposite liquidity, option volume) and fixed R130's too-close target.
5. R132 result: all 8 versions lose. The playbook (P1): 595 design trades, 38% won, -Rs 275 a trade; holdout 57 trades, 30% won, -Rs 340.
6. The PDFs are right that a reclaimed sweep reverses more than a run (56.0% vs 49.4%, n 10,410 / 4,161, p < 0.001), but the edge is a few index points.
7. The PDFs are wrong on: waiting for the BOS (it lost more than entering on the reclaim), round numbers (worse than placebo levels), and confluence (16 trades in 4.5 years, all-in -Rs 1,049 a trade).
8. Every R130-R132 version wins 21-57%, far below Boss's 65% rule, and breaks the -Rs 40k / -Rs 13k account stop.
9. The common wall: index edges of 0-4 points a trade against ~7 points an ATM option needs. The open questions are the instrument (futures cost less) and order-book data (none in the lake), not more tuning. No paper arm.
10. Write-ups: research/HUNT_R130_SWEEP_MSS_FVG.md, HUNT_R131_TREND15.md, HUNT_R132_LIQUIDITY_PLAYBOOK.md. Pre-registrations under research/hunt/r130_*, r131_*, r132_*. Entries: r130-*, r131-*, r132-*.yaml.
