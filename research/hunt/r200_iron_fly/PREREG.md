# R200 pre-registration: the iron fly, the versions not yet tested (side study, 18 Nov 2026)

Written 18 Nov 2026, **before any R200 profit or loss was computed**; hash and time in `results/PREREG_STAMP.txt`.
Owner: Boss (they/them). Boss: "Check iron fly strategy". **Research only. The app stays buy-only.**

## 1. What is already known about the iron fly here

* **R103** (22 Oct): NIFTY / BANKNIFTY 09:21 iron fly, wings 1x and 1.5x the ATM straddle, stop at 50% of the credit
  (beyond the fly's own max loss, so it almost never fired) with or without a lock and target, expiry and other days:
  0 of 24 passed. Repriced for stale wings, every non-expiry fly lost -Rs 2.1 to -Rs 3.3 lakh.
* **R195:** S4 (straddle + wings 6 steps, 30% stop per side) lost -Rs 1.27 lakh in design and -Rs 35.8k in 2026; E1 /
  E3 (expiry-day 09:20 fly, wings 4 steps, 1.5x premium stop) lost; E2 (13:30) made +Rs 34k in design but lost after Nov
  2024. P1 (overnight fly) lost -Rs 1.70 lakh.
* **Not yet tested:** a stop set INSIDE the fly's maximum loss with a profit target, and a fly carried from the start of
  the weekly cycle to expiry with an index stop.

## 2. Data, engine, costs, periods

R195's stores, marks (stale legs by Black-Scholes), fills, dated charges, today's lot and margin model; R199's
`run_carry` for the carried fly (an `after` entry is added: 09:20 on the first session after an expiry; nothing else in
R199 changes). New `r200study.fly_day`: a 4-leg intraday fly walked minute by minute with a stop at a fraction of max
loss and a target at a fraction of credit, whichever comes first, filled at the next minute's open; else square-off
15:10 (15:05 on expiry day). Design 2 Aug 2021 - 31 Dec 2025; 2026 a second look (R195 read it); the forward window from
16 Nov 2026 is the clean holdout. **Signal source:** none directional (K0 and wings from the index and the 09:20
straddle); the carried fly's stop reads the index.

## 3. Candidates (8, fixed now)

Fly = sell C(K0), P(K0); buy C(K0 + w), P(K0 - w). Intraday wing w = the 09:20 straddle S rounded to steps, 2-10 steps.

| id | rule |
|---|---|
| **IF1-NIFTY** | Non-expiry days, 09:20 fly, stop when loss >= 50% of max loss (wing x lot - credit). |
| **IF2-NIFTY** | IF1 + target: exit when profit >= 25% of the credit. |
| **IF3-NIFTY** | NIFTY expiry days, 09:20 fly, stop at 50% of max loss, square-off 15:05. |
| **IF4-NIFTY** | IF3 + target 25% of credit. |
| **IF5-BANKNIFTY** | IF2 on BANKNIFTY non-expiry days. |
| **IF6-SENSEX** | IF2 on SENSEX non-expiry days. |
| **IF7-NIFTY** | **Carried weekly fly:** 09:20 on the first session after an expiry, wings 8 steps (400 points), exit 15:05 on the expiry day, index stop: the index closes more than 4 steps from K0. |
| **IF8-SENSEX** | IF7 on SENSEX (wings 8 steps = 800 points, stop at 4 steps). |

## 4. Gates (R196's 1-8; win rate reported, not gated)

1 design net > 0 and post-Nov-2024 part > 0; 2 2026 net > 0; 3 40+ trades in each; 4 > 0 without the 5 best in each;
5 twin p < 0.05 (intraday: random decision minute 09:20-13:00 on a 5-minute grid; carried: random other session of the
cycle before the expiry day); 6 BH q <= 0.10, m = 4,897 + 8 = **4,905**; 7 worst month >= -Rs 13k and drawdown <= Rs 40k
over design + 2026; 8 design > 0 at 2x slippage. Margin and "possible at Rs 56k" reported (a fly has two short legs:
about Rs 1.3-1.5 lakh expected).
