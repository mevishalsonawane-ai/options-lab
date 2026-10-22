# R98c pre-registration: trading the continuation after a 1.5-straddle move (ATM / ITM / OTM, index exits)

Written 21 Oct 2026, before any number below was computed. Boss: "Enhance it more".

## Basis, and what is not clean

- R98b found one stable pattern (D3): once the index has moved 1.5 straddles from its 13:00 level (EM13 = the ATM
  straddle at 13:00), it goes on to 2.5 straddles the same way by 15:20 on 30% of expiries (design 28%, holdout 32%).
  The unconditional rate is 6.9%.
- That continuation rate was seen on the 2024-26 holdout. So the holdout below is **semi-clean**: the trigger's
  statistic is known there, but no option or index P&L of these rules has been computed on any year.
- A pass here earns a forward paper test, not a place in the app.

## 1. Trigger (fixed)

- On every expiry of the five indices: the first minute from 13:15 to 14:45 at which the index close is ≥ k × EM13 from
  its 13:00 close, with k ∈ {1.5, 2.0}.
- The direction d is the side it crossed. One trigger per index-day.

## 2. Instruments (fixed)

- **Options:** chosen at the trigger minute t from spot S_t, in direction d (calls for up, puts for down).
  - **ATM:** the strike nearest S_t.
  - **ITM1:** one strike in the money.
  - **OTM1:** one strike out.
  - **R5:** the OTM option nearest Rs 5 within Rs 2-10 (R98b's U-C ticket).
  - Each must have printed in t-2..t and have a bar at t+1. Entry is the next open + 1 tick, on a Rs 5,000 ticket with
    R98's charges.
- **Index proxy (a diagnostic, not a tradable claim):**
  - in at the index open of t+1, out at the index close of the exit minute;
  - P&L in EM13 units, and in % of spot after an assumed 0.05% round-trip cost;
  - futures minutes for expired contracts are not in the lake.

## 3. Exits (fixed; decided on the index close of each minute; the option is sold at that minute's close)

All levels are measured from the trigger level L_t = S13 + d·k·EM13:

- **X1:** hold to 15:20.
- **X2:** stop when the index retraces to L_t − d·0.5·EM13.
- **X3:** stop when the index retraces to L_t − d·1.0·EM13.
- **X4:** X2, plus a target at L_t + d·1.0·EM13.
- **X5:** X3, plus a target at L_t + d·1.0·EM13.
- **X6:** a trail: out when the index retraces 0.5·EM13 from its best close since the entry.

The option sale fills at the option's close in that minute (the next printed close if none), minus 1 tick. Anything
not out by 15:20 sells at the last close (worthless under Rs 0.10).

## 4. Selection and test (fixed)

- **Selection:** on DESIGN (expiries 2021-23), the (k, option instrument, exit) with the highest mean net per ticket,
  among the 48 option rules.
- **Holdout (2024-26), read once for that rule:**
  - mean, win rate;
  - bootstrap 95% by day (2,000, seed 98);
  - mean without the 5 best days;
  - years > 0.
- **Pass:** mean > 0, bootstrap lower bound > 0, > 0 without the 5 best days, and all 3 holdout years > 0.
- **Also reported:**
  - the holdout of all 48 option rules and the 12 index-proxy rows (with how many are > 0, to judge the selection
    against chance);
  - the 13 Oct trigger, if any.
- **Power warning, stated up front:** k = 1.5 fires on about 75 expiries per half, and k = 2.0 on fewer. A real edge of
  a few hundred rupees a ticket cannot be told apart from zero at this size. A fail is weak evidence of no edge; a pass
  must still survive the forward test.
