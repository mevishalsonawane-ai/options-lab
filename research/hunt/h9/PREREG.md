# h9 pre-registration (written 2026-10-07 BEFORE any h9 result was computed)

Question: does running the app's Liquidity 15+5 logic, rules UNCHANGED, on extra level timeframes ("books") add
independent profit, or just duplicate the existing books?

Existing 6 books (from HUNT_H4): BANKNIFTY 15m+5m, FINNIFTY 30m+5m (validated port), MIDCPNIFTY 15m+5m (h4 re-impl).

Candidate grid (index x level timeframe), 12 extra books, nothing else varied:
- BANKNIFTY : 3, 10, 30, 60 min
- FINNIFTY  : 3, 10, 15, 60 min
- MIDCPNIFTY: 3, 10, 30, 60 min
Same everything else: lookback 20, 2 contacts, gap 5, confirm 10, max age 300 bars, pool-on-swing break, 10-day
history, entries 09:20-14:00 (bar end), room >= 1 index stop, idx stop BN 30 / FIN 15 / MIDCP 8, 1 ITM, -15% premium
stop, 20-min time stop unless +5%, next-level target, failed break, new level, 15:10, expiry days skipped,
one position per book. Executions: gross / app / real (h4 definitions).

Selection (uses ONLY trades before 2025-10-01, real execution):
 keep an extra book iff ALL of
  K1 pre-holdout net > 0;
  K2 Benjamini-Hochberg q < 0.10 over the 12 candidates' same-exit random-entry p-values (pre-holdout trades);
  K3 net > 0 on its "non-duplicate" trades (trades NOT entered at the same minute and side as an existing book's trade
     on the same index).
Walk-forward: for test years 2023, 2024, 2025(Jan-Sep) the same rule is applied to data before 1 Jan of the test year
(>= 30 trades needed); the selected extras are traded in that year. The chosen set (rule applied to all pre-holdout
data) is then tested ONCE on 2025-10-01 .. latest.
Sizing to Rs 5,000/day: k = 5000 / (mean real net per day, 1 lot per book) over 2023-06-01 .. 2025-09-30 (all three
indices live), fixed before the holdout.
Trials counted: 12 candidate books + 6 existing books + 2 portfolio sets (+ walk-forward set) in SPA/RC.
