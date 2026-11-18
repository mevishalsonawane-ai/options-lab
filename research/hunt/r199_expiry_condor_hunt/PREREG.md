# R199 pre-registration: hunting a high-win-rate expiry condor that also keeps its losses small (18 Nov 2026)

Written 18 Nov 2026, **before any R199 profit or loss was computed**; hash and time in `results/PREREG_STAMP.txt`.
Owner: Boss (they/them). Boss: "Hunt. More" (after R198). **Research only. The app stays buy-only.**

## 1. Why these candidates

R198's F5 (NIFTY condor, shorts 5 / hedges 10 steps out, sold 15:15 the session before expiry, closed 15:05 on expiry
day) won 81.8% of 225 design weeks, but its average loss was 3.8x its average win (Rs 3,023 vs Rs 804, break-even
79%), and 2026 fell to 73.3% / +Rs 2,030. A rule needs the average loss below 4x the win at 80%. **R199 attacks the
loss side** (index and premium stops, wider strikes), the entry (calm and VIX filters, an intraday expiry-day version
with no overnight gap) and the breadth (BANKNIFTY, SENSEX), plus one structure Rs 56k can carry.

**Signal source:** none directional for the structures (strikes from the index); the index for the index stops and the
calm filter; India VIX for the VIX filter. No rule reads an option's chart.

## 2. Data, engine, costs, periods

R195's stores and engine, and R198's cycles and exit fills (`exit_fill`: never below intrinsic). **New:**
`r199engine.run_carry` walks every minute from the entry fill to the exit, across sessions. It checks the index stop
on each minute's index close and the premium stop on the position's marked loss (R195's stale-leg marks). A trigger
fills at the next minute's open; a trigger on a session's last minute fills at the next session's first minute.
Charges are dated, slippage is 'liq' with stop ticks doubled, at today's lot. The margin is R195's model on the exit
day (ELM if it is the expiry day). Design 2 Aug 2021 - 31 Dec 2025; 2026 is a second look (R195 read it); the
forward window from 16 Nov 2026 is the clean holdout.

## 3. The candidates (12, fixed now)

"Condor a / w" = sell C(K0 + a), P(K0 - a), buy C(K0 + w), P(K0 - w) steps. "Eve" = decision 15:15 on the session
before the expiry day, fill 15:16. Exit 15:05 on the expiry day (fill 15:06) unless a stop fires.

| id | rule |
|---|---|
| **H1-NIFTY** | Eve condor 5 / 10 + **index stop at the short strikes**: exit all when the index closes above K0 + 5 steps or below K0 - 5 steps. |
| **H2-NIFTY** | Eve condor 5 / 10 + **index stop at 3 steps** (60% of the way to a short strike). |
| **H3-NIFTY** | Eve condor **7 / 10** (no stop). |
| **H4-NIFTY** | Eve condor 5 / 10 on days India VIX at 15:15 >= the median of its 15:15 closes over the previous 250 sessions (at least 120 known). |
| **H5-NIFTY** | Eve condor 5 / 10 on **calm eves**: the index's move from the eve's 09:15 open to its 15:15 close is within +-0.5%. |
| **H6-NIFTY** | **Expiry-day intraday** condor 5 / 10: decision 09:20, fill 09:21 on the expiry day, out 15:05. No overnight gap. |
| **H7-NIFTY** | H6 + index stop at the short strikes. |
| **H8-NIFTY** | Expiry-day condor **4 / 10 at 12:00** (fill 12:01) + index stop at the short strikes, out 15:05. |
| **H9-BANKNIFTY** | H1 on BANKNIFTY (its expiry cycles: weekly to Nov 2024, then monthly). |
| **H10-SENSEX** | H1 on SENSEX. |
| **H11-NIFTY** | Eve condor 5 / 10 + **premium stop**: exit when the position's marked loss >= 1.5x the credit. |
| **H12-NIFTY** | **Fits Rs 56k:** bull put spread 5 / 10 sold at 15:15 two sessions before the expiry day, closed at 15:10 on the eve (no expiry-day ELM), index stop at the short strike. |

## 4. Gates (R198's, all must hold)

1 design net > 0 and post-Nov-2024 part > 0; 2 2026 net > 0; 3 40+ trades in design and in 2026; 4 > 0 without the 5
best trades, in each; 5 twin p < 0.05 (carried rules: the same rule entered at the same minute on a random other session
of the cycle before the expiry day; intraday H6-H8: a random decision minute on the expiry day on a 5-minute grid,
09:20 - 13:00 for H6 / H7 and 11:00 - 14:00 for H8; 1,000 twins); 6 BH q <= 0.10, m = 4,885 + 12 = **4,897**, p =
max(bootstrap p, twin normal-tail p); 7 worst month >= -Rs 13k and max drawdown <= Rs 40k over design + 2026; 8 design >
0 at 2x slippage; 9 win rate >= 80% in design and in 2026. Reported: Wilson 95% intervals, average win and loss, the
break-even win rate, the worst trade, margin and "possible at Rs 56k".
