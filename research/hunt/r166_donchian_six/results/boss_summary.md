## For Boss: short answer (12 Nov 2026, Nov 2024 onward only)

**I checked all six, one by one: none passed every gate, so nothing changes in the app. One is a real lead: skipping
breaks when the option itself is not being traded (V1).** 24 tests (12 checks x R72 and R51, 1 lot, today's exits).

Today, for comparison (1 lot): R72 walk-forward year (Apr 2025 - Mar 2026) 2,238 trades, 49.0% won, Rs 3,35,882; final
(Apr - Oct 2026) 1,202 trades, 45.8%, Rs 1,71,366. R51: 3,044 / 45.9% / Rs 2,13,748 and 1,670 / 44.9% / Rs 1,34,743.

1. **Delta.** R165 (a sibling study) covers delta in depth. I added two: delta 0.60 but at most 2-ITM, and delta 0.60 on
   NIFTY / SENSEX only. Neither beat picking a strike at random (walk-forward p 0.42-0.71). The capped one made more in the
   final (R72 Rs 2,09,565, R51 Rs 2,01,715) but less in the walk-forward year, and that final was already seen when I
   picked 0.60, so it proves nothing.
2. **Support / resistance (previous day's high / low).** "Skip when the level is just ahead" made less everywhere.
   "Only trade beyond yesterday's high / low" looked great in design (R51 Rs 2,26,478 vs Rs 50,425) and then lost in the
   final (R72 Rs 9,247, R51 -Rs 6,280, about 550-680 trades).
3. **Fake breakout (sell if the index has not gone past the signal candle within 3 / 5 minutes).** Clearly worse: the win
   rate fell 5-7 points and most of the profit went. Many good breaks pause before they run.
4. **Fake volume.**
   - **V1: skip when the bought option's volume in the signal candle is below its usual for the day.** It made more on
     both arms in all three periods:
     - R72: design Rs 2,77,103 vs Rs 2,16,929; walk-forward Rs 3,59,030 vs Rs 3,35,882; final Rs 2,03,445 vs
       Rs 1,71,366 (1,115 trades), drawdown no worse;
     - R51: Rs 1,08,408 vs Rs 50,425; Rs 2,37,672 vs Rs 2,13,748; Rs 1,63,243 vs Rs 1,34,743 (1,461 trades);
     - it beat random skips of the same number (R72 p 0.006, R51 p 0.038).
     - **Why it still fails:** after correcting for all 2,290 tests run so far, the evidence is not strong enough
       (q 1.0; within R166 alone 0.07 for R72), and the final months were seen by many studies. The win rate barely
       moves (-0.1 to +0.8 points): it skips poor trades, not losers in particular.
   - V2 (skip a volume climax) made less in design and walk-forward.
5. **Fake buyer / seller.** Skipping when the option's implied vol fell (E1) raised the walk-forward win rate (51.2% vs
   49.0% on R72) but made less money; skipping when the other side's option got dearer (E2) changed nothing.
6. **Traps.** Skipping after a break the other way within 30 minutes (F1) changed nothing. Skipping an outside "sweep"
   candle (F2) did well in the final (R72 Rs 2,04,415 vs Rs 1,71,366, p 0.002) but made less in the walk-forward year,
   so it is not consistent.

**My suggestion:** V1 is the one worth watching. If you want, I can pre-register it as a **shadow label** (the app only
logs "low option volume" on each R72 / R51 break; it changes no trade) and judge it on the new days from today.
Honesty notes: Apr - Oct 2026 has been read by many studies, so it is weak evidence. As you asked, nothing before Nov
2024 was used.
