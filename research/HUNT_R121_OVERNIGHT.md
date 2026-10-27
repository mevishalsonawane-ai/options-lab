# R121: does selling options overnight pay? (research loop, round 1)

Written 27 Oct 2026, for Boss: *"Do all the research you can on loop ... find the best strategy you can with max
winrate."*

- **Pre-registration:** `research/hunt/r121_overnight/PREREG.md`, commit `c4e292b`, pushed before any number.
- **Code and tests:** pushed before the run (`baa8134`).
- **Code, tests and tables:** `research/hunt/r121_overnight/`. `results/report.md` has every period, night type and
  index.
- **Experiment entries:** `research/experiments/entries/r121-t1.yaml`, `r121-t2.yaml` (both failures).
- **Research only.**
- **Signal source:** none directional (the clock). Strikes come from the index's ATM strike and straddle.

## Verdict for Boss

**0 of 2 pass. Overnight option selling does not pay in Indian index options, with one partial exception: the
night before expiry.**

- **The idea:** published research on US options (Muravyev & Ni 2020) finds that option sellers earn their premium
  overnight. Here that would mean: sell an ATM iron fly at 15:15, buy it back at 09:20 the next morning (same expiry).
  The wings are 2 straddles out and cap the loss.
- **T1, every night (3,983 nights, five indices):** it loses everywhere.
  - Win rate 36%; mean −Rs 378 a trade after costs.
  - In 2024-26 it loses even before costs (−Rs 256 and −Rs 251 a trade).
  - Weekend and holiday nights are the worst (−Rs 742 and −Rs 809 a trade before costs). The extra calendar days do not
    turn into extra decay.
- **Why it fails here:** Indian index options are most expensive at the open.
  - R118 found the ATM IV highest at 09:30 (NIFTY 21.8%) and lower by 10:30 (19.8%).
  - Buying back at 09:20 pays for that morning re-inflation, and it swallows the overnight decay.
  - R103 / R119's intraday sellers did the opposite (they sold into the morning premium), which is why they at least
    made money before costs.
- **T2, only the night before expiry (851 nights):** the one slice with a real pre-cost edge in every period.

  | period | nights | win rate | before costs / trade | after costs / trade | worst night | months ≤ −Rs 13k |
  |---|---|---|---|---|---|---|
  | design 2021-23 | 373 | 55% | +Rs 180 | −Rs 17 | −Rs 14,065 | 2 |
  | walk-forward 2024-25 | 378 | 55% | +Rs 103 | −Rs 96 | −Rs 16,607 | 0 |
  | final 2026 | 100 | 68% | +Rs 554 | +Rs 344 | −Rs 11,524 | 1 |

  - It fails the first gate: negative in design and walk-forward after about Rs 200 of costs a trade (eight orders).
  - Its final-period profit has a bootstrap p of 0.12, and 2026 had a month at or below −Rs 13k.
- **Seen, not claimed (chosen after looking, so not a result):**
  - Weekday nights before expiry did better than weekend ones (2026: +Rs 514 vs −Rs 1,183 a trade).
  - NIFTY did better than SENSEX.
  - Testing either now would be fitting to these numbers. A future round could test it only on new data (paper from
    today).

## What was tested

- **The night:** every pair (session d, next session n) where d is not an expiry day, so both days trade the same
  contract.
  - NIFTY and BANKNIFTY from Aug 2021, FINNIFTY from Nov 2021, MIDCPNIFTY from Jun 2023, SENSEX from May 2023.
  - 4,530 nights in all; 547 skipped because a leg had no print at entry (mostly thin early FINNIFTY and MIDCPNIFTY).
- **Entry:** on the 15:15 bar's close, sell the ATM call and put, and buy the wings 2 straddles out; fill at 15:16.
- **Exit:** the 09:20 open on n.
- **Stale legs:** a leg with no morning print is repriced by Black-76 at its last evening IV, the index at the 09:20
  open, and calendar time to expiry. 2,430 legs were repriced across T1's 3,983 trades. That is mostly the far wings,
  which leave the stored ATM±10 band overnight.
- **Costs:** R119's: volume-based slippage, late-minute widening at 15:16, dated charges per order.
- **Gates:**
  - positive in every period;
  - positive without the 3 best days, and at 2x slippage;
  - bootstrap p < 0.05;
  - BH q ≤ 0.10 over 9.3 million logged tests;
  - at least 40 final trades;
  - Boss's limits at 1 lot (no month at or below −Rs 13k, drawdown under Rs 40k).

## Limits

- **Margin:** the reported margin is R103's intraday estimate. Real overnight SPAN margin for a short iron fly is higher,
  and would make the return on capital even smaller.
- **09:20 is an early exit, chosen in advance.** A later exit (after the morning IV settles) would be a different
  strategy. It is also an intraday seller's trade, which R103 and R119 already tested.

## What it adds to the loop

- **Lesson for the next rounds:** in this market the premium sits in the morning, not overnight. Buying back at the
  open is the most expensive time to buy.
- **Scoreboard:** `research/LOOP_SCOREBOARD.md`.
