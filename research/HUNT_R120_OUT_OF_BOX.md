# R120: five out-of-the-box expiry-day tests: size, futures, skew, twin indices, the morning after

Written 27 Oct 2026, for Boss: *"Do more deep research, think out of the box."*

- **Pre-registration:** `research/hunt/r120_out_of_box/PREREG.md`, commit `90e7068`, pushed before any number.
- **Code and tests:** pushed before the run (`87fe05e`).
- **Code, tests and tables:** `research/hunt/r120_out_of_box/`. `results/report.md` has every period and the lot frontier.
- **Experiment entries:** `research/experiments/entries/r120-q1..q5.yaml` (5, all failures).
- **Research only.** Nothing in the app changes; nothing goes to paper or live.
- **Signal source:** every rule reads the underlying (the index; the strikes from the index and its straddle).

## Verdict for Boss

**0 of 5 pass.** One of them answers the biggest open question of R117-R119.

### 1. The expiry-day option-selling edge is real. It is a payment for tail risk, and no size fits Boss's limits.

- R119 left a clue: the 09:30 iron fly made money before costs and lost it to the flat Rs 20 per order. Brokerage is
  per order, not per lot, so more lots should keep more of the edge. R120 tested that, fitting the lot count on
  2021-23 only.
- **It works as far as it goes.** At 2 lots per leg (chosen on design), the iron fly is the first rule in R117-R120
  that makes money in every period:
  - +Rs 52 a trade in design (375 trades);
  - +Rs 504 in walk-forward (380);
  - +Rs 171 in the final test (105);
  - still positive at 2x costs (+Rs 121).
- **But the risk grows exactly as fast as the profit.** The lot frontier, final period (105 trades):

  | lots per leg | mean / trade | worst day | max drawdown | months ≤ −Rs 13k |
  |---|---|---|---|---|
  | 1 | −Rs 1 | −Rs 16,848 | Rs 41,695 | 3 |
  | 2 | +Rs 171 | −Rs 33,036 | Rs 81,289 | 3 |
  | 5 | +Rs 687 | −Rs 81,599 | Rs 2.0 lakh | 3 |
  | 10 | +Rs 1,370 | −Rs 1.63 lakh | Rs 4.0 lakh | 3 |

- **Every size breaks Boss's account stop.** Even 1 lot loses more than Rs 13k in 3 months of 2026, and its drawdown
  passes Rs 40k.
  - At 2 lots over 2021-26 it makes about Rs 45k a year (Rs 267 a trade, about 170 trades a year).
  - Against that: a worst day of −Rs 47,503, a drawdown of Rs 2.39 lakh, and 16 months at or below −Rs 13k.
- **The timing is not special.** Random entry times earn about as much (twin p 0.17). The final period's profit is in
  its 3 best days (without them: −Rs 68,188).
- **Plain-English reading:** selling the expiry-day straddle is paid a small, steady premium for insuring the market
  against the rare big move. The premium is real. So are the big moves, and at any size that beats the brokerage, one
  bad day takes months of premium. This is why it fails here, and why it should stay off Boss's account under the
  −Rs 13k / −Rs 40k rules.

### 2. Futures do not rescue the "chop"; the chop is too fast to trade

- **Q2:** fading sharp 15-minute index moves with the index future instead of an option. It loses −Rs 366 / −Rs 460 /
  −Rs 1,332 a trade (813 trades).
- **The opposite trade (going with the move) also loses.** Before costs both directions are about zero (+Rs 8, −Rs 94,
  +Rs 234 a trade). At a 15-minute horizon a big expiry-day move neither reverts nor continues.
- R118's mean reversion is real at the 1-minute scale (it is measured on 1-minute returns). Nothing tradeable is left by
  15 minutes.
- **Futures got expensive:** a 1-lot index-futures round trip cost about Rs 360 in 2021-23. In 2026 it costs about
  Rs 1,050-1,100, after STT on futures rose to 0.05% in April 2026. Any intraday futures idea now needs a much bigger
  edge.

### 3. Skew does not pay as a risk reversal

- **Q3:** sell the dear OTM put, buy the OTM call that runs further, and buy a put wing for the tail. It loses
  −Rs 190 / −Rs 246 / −Rs 806 a trade (869 trades), and wins 18% of the time.
- The call's extra upside and the put's extra premium are real in the data (R118), but smaller than three legs'
  spreads and charges.

### 4. The expiring index against its twin: no

- **Q4:** the expiring index's 15-minute move relative to a non-expiring index (NIFTY, or SENSEX for NIFTY), faded with
  its future.
- Never measured before R120. It loses −Rs 493 / −Rs 511 / −Rs 1,440 a trade (1,450 trades). Before costs it is about
  zero (+Rs 128, Rs 0, +Rs 389 going with the move).
- The expiring index does not wander away from its twin and come back in any way a trade can catch.

### 5. The morning after: no

- **Q5:** fade a big last-30-minute expiry move at the next morning's open (out at 10:15). Never measured before R120.
- It loses −Rs 140 / −Rs 273 / −Rs 919 a trade (176 trades in all). Going with the move is worse.
- The big expiry-day finish does not reliably give itself back overnight.

## The five tests in one table

Mean is net rupees per trade; 1 lot unless stated; periods are design 2021-23, walk-forward 2024-25, final 2026 to
19 Oct.

| id | idea | design (n) | walk-fwd (n) | final (n) | final worst day | final months ≤ −13k | twin p | passes? |
|---|---|---|---|---|---|---|---|---|
| Q1 | R119's 09:30 iron fly at **2 lots** (lots fitted on design) | +52 (375) | +504 (380) | +171 (105) | −33,036 | 3 | 0.17 | no: tail risk, twins, best days |
| Q2 | fade 15-min moves with the index future | −366 (344) | −460 (372) | −1,332 (97) | −15,604 | 5 | 0.86 | no |
| Q3 | skew risk reversal at 12:00 (put sold, call bought, put wing) | −190 (385) | −246 (376) | −806 (108) | −14,693 | 5 | 0.78 | no |
| Q4 | fade the expiring index against its twin index, future | −493 (605) | −511 (657) | −1,440 (188) | −18,149 | 9 | 0.998 | no |
| Q5 | the morning after: fade the big expiry finish, 09:15-10:15 | −140 (53) | −273 (86) | −919 (37) | −18,763 | 1 | 0.44 | no |

Every q is 1.0 at m = 9,300,059 tests.

## What was tested

Rules exactly as pre-registered:
- **Q1:** R119's P1 (sell the ATM call and put on the 09:30 bar's close, buy wings 2 straddles out, out at 15:05) at N
  lots per leg.
  - Only the brokerage stays per order.
  - Orders pay 1 extra tick for every 5 lots beyond 5.
  - N = the smallest size with a positive design mean and the fewest design months at the stop. That came out at 2.
- **Q2:** R119's P5 signal (a 15-minute move of at least 1.5x its expected size, at 11:00-14:30), traded with 1 lot of
  the index future against the move for 15 minutes.
- **Q3:** at 12:00, sell P(K0 − 1 straddle), buy C(K0 + 1 straddle), buy P(K0 − 2 straddles); out at 15:05.
- **Q4:** the expiring index's 15-minute % move minus its twin's. A move of at least 1.5x its own standard deviation
  at that clock time over the previous 20 expiries (point-in-time) is faded with the future for 15 minutes.
- **Q5:** the last-30-minute move of the expiry, if in the top 20% of the previous 40 expiries (point-in-time), is
  faded at the next session's 09:15 open and closed at the 10:15 open.
- **Costs:**
  - Options: R119's engine (volume-based slippage, late-minute widening, dated charges, stale legs repriced).
  - Futures: R104's dated index-futures charges, a half-spread of max(Rs 0.05, 0.5 bp), plus 0.5 bp slippage per fill.
- **Gates:**
  - R119's gates;
  - plus Boss's limits at the tested size: no final month at or below −Rs 13k, and a final drawdown under Rs 40k.

## Honest notes

- **Q1 is post hoc.** It exists because R119's P1 results were seen, and its lot count was fitted (on design only).
  Q2 and Q3 re-use patterns R118 had already seen. Q4 and Q5 are measurements nobody had made before.
- **The index stands in for the futures price.** On these short holds the basis barely moves, and the 2x-cost stress
  covers part of it. Overnight (Q5) the proxy is weaker.
- **SENSEX futures are priced with the NSE futures cost model** (BSE's rates differ slightly).
- **A correction to the pre-registration's wording:** it said NIFTY's twin falls back to "NIFTY where SENSEX has no
  data", which makes no sense for NIFTY itself. Such a day would simply be skipped. None was: SENSEX minutes exist for
  every NIFTY expiry day in the lake.

## What this means

- **Nothing changes.** No R120 rule goes to paper or live.
- **The deeper answer, after R117-R120:** on expiry days, buying options loses to their price, and selling them earns
  a real premium that is the price of rare large losses. Index futures cost too much to scalp. Nothing in the
  intraday shape survives costs.
- **What could still be worth doing is outside the Rs 13k-a-month frame:**
  - a capital-sized, separately-funded short-volatility book with its own risk limits;
  - or a lower-cost broker / cost structure, which changes every break-even here.
  - Both are Boss's business decisions, not research findings. Neither is proposed here.
