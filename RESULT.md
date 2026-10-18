# R82 result for Boss: MCX gold and silver (GOLDM / SILVERM), 18 Oct 2026

1. **What was tested.** 16 ideas to improve the app's MCX metal strategies:
   - stops and sizing on the 12-month trend;
   - gold+silver agreement;
   - Donchian + Supertrend;
   - the gold/silver ratio;
   - a DXY filter;
   - Liquidity 1h on MCX hours;
   - the overnight COMEX move;
   - the first US hour, including on US data days.
2. **None passed.** The app stays as it is and nothing new goes on paper. Each idea is logged, including the failures.
3. **Trend arm.** Adding a stop made about the same money as today's rule in 2023 - Sep 2026: +Rs 10-11 lakh on
   1 GOLDM + 1 SILVERM lot. Today's rule holds through big drops (-Rs 13.4 lakh open drawdown in 2026).
4. **The safest stop version (T4)** kept 92% of the profit with only a -Rs 1.9 lakh drawdown. It was not proven
   better (16 tries; q 0.33), so it is a note to re-test on real MCX data, not a change.
5. **The best-looking design idea failed for real.** "Trade both metals the way they move in the first US hour" passed
   the design (q 0.032, 564 trades), then lost Rs 5.84 lakh in the unseen final (234 trades).
6. **Lessons that did not carry over to MCX metals:** gold+silver "breadth", Supertrend agreement, and Liquidity 1h all
   lost or added nothing.
7. **Limits.**
   - Prices are proxies (world gold/silver × USDINR × duty), not MCX prints; the data lake was not reachable from this
     session.
   - Options versus futures, roll timing, real yields and silver 15m could not be tested.
8. **Files:** `research/HUNT_R82_MCX_PRECIOUS.md` (full write-up), `research/hunt/r82/` (pre-registration, code,
   tests, results).
