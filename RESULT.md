# R141 result (31 Oct 2026): every losing trade of the four paper arms, and the patterns in them (descriptive)
1. All of R72, R51, R52 and Liquidity's trades (exact rebuild) were checked. About half lose on every arm (R72 53-54%, R51 55%, R52 44-49%, Liquidity 56-63%); winners being bigger is what pays.
2. The biggest losing group: trades that never worked (the option never rose 5%). They are 44-50% of the money lost on R72 and R51; 92-98% of them lose, and fast (R72: 85-91% of trades ending within 15 min lose).
3. About a quarter of the money lost: trades up a median +28-32% that then lost. R105 already tested 20 exits for these: 0 passed (they sell winners early).
4. Losses bunch: on Liquidity two-thirds of losers fall on days when every trade lost; R72's longest losing streak was 29 (design), 15 (final).
5. Weekday, index, call/put and hour-of-day patterns are NOT steady: they flip between periods.
6. Steady at entry (held in 2024-25, 2025-26 and Apr-Oct 2026): R72 weak push into the break, and after a very wide day (R140's filter: passed, proposed for paper).
7. R51 steady: an earlier fake poke today, a weak push, a quiet signal bar, 10:30-12:00 entries, a very wide previous day. R52: none found. Liquidity: a gap against the trade (R140: failed).
8. Leads to test next, each pre-registered with lose-less gates: an R51 "bad-break" filter, and a "no progress" exit on R72/R51 (never tested there).
9. Nothing changes in the app. 4 descriptive entries (research/experiments/entries/r141-*.yaml).
10. Write-up: research/HUNT_R141_LOSING_TRADES.md. R140 (structure on the arms, fake breakouts) and R139 (market structure) are in research/HUNT_R140_STRUCTURE_ARMS.md and HUNT_R139_MARKET_STRUCTURE.md.
