# R130 + R131: the sweep → MSS → FVG setup and the 15-minute trend both fail (research only; nothing in the app changed)

1. R130, the video setup as stated (signal on the index, ATM option, real costs): 218 design trades, 45% won, -Rs 307 a trade; holdout -Rs 750. Wick to wick: -Rs 26 a trade (485 trades); holdout -Rs 258.
2. R130's improvement search (96 versions + the profit lock) and its 65% search (32 versions, walk-forward) found nothing that passes. The best honest win rate was 52.5% out of sample; the profit lock reached 67% only by losing money.
3. R131 tested the lead R130 left: the 15-minute wick structure on its own (stop-and-reverse), plus R123's network as a filter (Boss's ask).
4. Design (2021 - Mar 2026): the plain trend chose direction better than random (p 0.005) but made only +Rs 6 a trade (6,317 trades; -Rs 16 at 2x slippage).
5. With R123's network: expected-value filter +Rs 267 a trade (1,887 trades), direction filter +Rs 106 (2,750). Both passed every design gate.
6. Holdout (Apr - Oct 2026, read once): the direction filter lost Rs 122 a trade (533); the EV filter made +Rs 88 (167 trades, 34% won, p 0.31 vs random).
7. Untouched NIFTY 2017-2020: the plain trend made +4.0 index points a trade (1,492 trades), short of the ~7.3 points an option needs.
8. Every R131 version wins only 32-43% of the time (a trend-follower: small losses, bigger wins), far below Boss's 65% rule.
9. Even the best drew down -Rs 92k with a -Rs 41k month on 1 lot, beyond the -Rs 40k / -Rs 13k account stop. No paper arm is proposed.
10. Write-ups: research/HUNT_R130_SWEEP_MSS_FVG.md, research/HUNT_R131_TREND15.md. Pre-registrations under research/hunt/r130_sweep_mss_fvg/ and research/hunt/r131_trend15/. Entries: r130-*.yaml, r131-*.yaml.
