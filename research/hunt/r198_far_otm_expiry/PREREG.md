# R198 pre-registration: far-OTM hedged strangle (iron condor) held to expiry, for an 80% win rate (18 Nov 2026)

Written 18 Nov 2026, **before any R198 profit or loss was computed**; hash and time in `results/PREREG_STAMP.txt`.
Owner: Boss (they/them). Boss, after R197: "try far OTM strangle hold to expiry with hedge for 80%". **Research only.
The app stays buy-only.**

## 1. What is new, and the data's limits

R195-R197 were intraday. Here positions are carried **overnight to the weekly expiry**, sold far out of the money and
hedged. The lake keeps only strikes within +-10 steps of the ATM at each minute (NIFTY +-500 points, SENSEX +-1,000),
and every leg must have a real print at entry, so **hedges sit at 10 steps and short strikes at 5 or 7 steps** (NIFTY
about 1.0-1.4% out). A leg that later leaves the window is marked by Black-Scholes at its last print's implied vol
(R195's stale-leg rule). On expiry day near the close the price is mostly intrinsic value, which the index gives
exactly.

**Signal source:** none directional; strikes are set from the index at entry. No stop: the hedge is the defined loss.

## 2. Data, engine, costs, periods

R195's stores and engine (`r195engine`: marks, fills, 'liq' slippage, dated charges, BSE rates for SENSEX, today's
lot, margin model). The expiry cycle = the sessions after one flagged expiry up to and including the next. Design
2 Aug 2021 - 31 Dec 2025; 2026 (1 Jan - 13 Nov) is a second look (R195 read it). The forward window from 16 Nov 2026 is
the clean holdout. A trade belongs to the period of its entry day; a cycle crossing 31 Dec 2025 is dropped.

* **Exit "15:05":** every leg is bought / sold back at 15:06 on the expiry day (the order for the 15:05 decision, the
  app's expiry square-off).
* **Exit "settle":** every leg is settled at intrinsic value at the index's last close of the expiry day. No sale
  order, so no sale STT or brokerage. **STT on exercise: 0.125% of the intrinsic value of each in-the-money LONG leg
  (the buyer pays), 0.15% from 1 Apr 2026.** Short legs settled in the money pay their intrinsic value.
* **Margin:** R195's model. A position carried into its expiry day pays the +2% ELM on the short legs from 20 Nov 2024,
  so a carried condor's margin is taken on the expiry day (its peak).

## 3. The candidates (8, fixed now)

| id | rule |
|---|---|
| **F1-NIFTY** | Entry the first session after an expiry (09:20 decision, 09:21 fill). Iron condor: sell C(K0 + 5), P(K0 - 5); buy C(K0 + 10), P(K0 - 10) steps. Exit 15:05 on the expiry day. |
| **F2-NIFTY** | F1 with the shorts at +-7 steps. |
| **F3-NIFTY** | F1 held to settlement. |
| **F4-NIFTY** | F2 held to settlement. |
| **F5-NIFTY** | Entry on the session BEFORE the expiry day at 15:15 (fill 15:16): F1's strikes from the 15:15 ATM; exit 15:05 on the expiry day. |
| **F6-SENSEX** | F1 on SENSEX (weekly, Thursday; step 100). |
| **F7-SENSEX** | F2 on SENSEX. |
| **F8-NIFTY** | Affordable put side only: entry as F1, sell P(K0 - 5), buy P(K0 - 10); exit at 15:10 on the session BEFORE the expiry day, so no expiry-day ELM (margin about Rs 50k). |

## 4. Gates (as R197, all must hold)

1. Design net > 0 and post-Nov-2024 part > 0. 2. 2026 net > 0. 3. 40+ trades in design and in 2026. 4. Net > 0 without
the 5 best trades, in each. 5. **Twin p < 0.05:** the same structure entered at 09:20 on a random other session of the
same cycle (before the expiry day; F5: 15:15 on a random session), 1,000 twins, seed 195. 6. BH q <= 0.10 with m =
4,877 + 8 = **4,885**, p = max(day-bootstrap p, twin normal-tail p). 7. Account stop over design + 2026 (worst month
>= -Rs 13k, drawdown <= Rs 40k). 8. Design net > 0 at 2x slippage. 9. **Win rate >= 80% in design and in 2026.**
Reported: Wilson 95% intervals, average win and loss, break-even win rate, worst trade, the worst gap days, margin and
"possible at Rs 56k".
