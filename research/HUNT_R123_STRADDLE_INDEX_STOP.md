# R123: the expiry-day short straddle with a stop on the index (research loop, round 3)

Written 27 Oct 2026, for Boss (*"find the best strategy you can with max winrate"*).

- **Pre-registration:** `research/hunt/r123_straddle_index_stop/PREREG.md`, commit `fbf66c0`, pushed before any
  number.
- **Code and tests:** pushed before the run (`2dd53dc`).
- **Tables:** `research/hunt/r123_straddle_index_stop/results/report.md`.
- **Experiment entries:** `research/experiments/entries/r123-v1.yaml`, `r123-v2.yaml`.
- **Research only.**
- **Signal source:** the stop reads the underlying (the index's minute closes, the default); the entry is the clock;
  the strike is the index's ATM strike.

## Verdict for Boss

**0 of 2 pass the pre-registered gates. But this is the best result of R117-R123:** the first rule that makes money
after real costs in all three periods.

- **The idea:** R119-R122 showed that the expiry-day short premium is real but eaten by the cost of 8 orders (4 legs
  in, 4 out).
  - So drop the wings. Sell only the ATM call and put at 09:30.
  - Cap the risk with a stop on the INDEX: if it moves k straddles from the entry, close both legs at the next minute's
    open; otherwise buy back at 15:05.
  - That is 4 orders (about Rs 105 a trade), not 8.
- **V2, stop at 1.5 straddles (889 trades, five indices, 1 lot):**

  | period | trades | win rate | stopped | before costs / trade | after costs / trade | worst day | months ≤ −Rs 13k | max drawdown |
  |---|---|---|---|---|---|---|---|---|
  | design 2021-23 | 392 | 57% | 32% | +Rs 379 | **+Rs 277** | −Rs 11,636 | 1 | Rs 50,561 |
  | walk-forward 2024-25 | 389 | 61% | 32% | +Rs 774 | **+Rs 668** | −Rs 14,429 | 3 | Rs 70,677 |
  | final 2026 | 108 | 61% | 30% | +Rs 386 | **+Rs 273** | −Rs 17,585 | 3 | Rs 44,686 |

  - In all: +Rs 3.98 lakh, Rs 447 a trade.
  - Still positive at 2x slippage (final +Rs 258 a trade).
- **V1, stop at 1.0 straddle:** +Rs 323 / +Rs 353 / +Rs 211 a trade. It wins less often (42-46%), because 59% of days
  hit the stop. Its loss pattern is milder (design drawdown Rs 23,685), but 3 months of 2026 still reached −Rs 13k.
- **Why it fails the gates:**
  1. **The 2026 profit sits in a few days.** Without its 3 best days, the final period is −Rs 11,291 (V1: −Rs 16,880).
  2. **The timing is not special.** The same rule entered at a random time between 09:30 and 14:30 does about as well
     (twin p 0.20 / 0.34). The edge is the short-premium structure with an index stop, not 09:30.
  3. **Boss's limits, at 1 lot on all five indices together:** 3 months of 2026 at or below −Rs 13k, and a final
     drawdown of Rs 44.7k (V2), above the Rs 40k limit. This is the five indices stacking up in the same month,
     not one bad trade.
  4. **Multiple testing:** q = 1.0 over 9.3 million logged tests (it would need p near 1e-8).
- **What it means:** the short expiry-day straddle, with its risk cut by a stop on the index, is the strongest
  edge this programme has found, after costs and in every period. It is still a premium for carrying tail risk. Its
  record leans on a few good days in 2026, and pooled across five indices it breaks the monthly stop.
- **Rules:** CLAUDE.md says a rule that fails its gates stays a note: it does not go on paper on its own. The honest
  next steps are:
  - an out-of-sample check of the same mechanism on data it has never touched (round 4: normal, non-expiry days);
  - or a frozen forward paper test from today, if Boss chooses one (as FT3 was).

## Seen, not claimed

- **By index (V2):** MIDCPNIFTY and FINNIFTY did best and BANKNIFTY worst in 2026, but on 7-9 trades each.
- **Margin:** at R103's estimate a 2026 trade ties up about Rs 1.4 lakh; real naked-straddle margin is higher.
  Rs 447 a trade is about 0.3% of that per trade.

## What was tested

- **R98's 911 expiry days** (NIFTY 273, BANKNIFTY 195, FINNIFTY 167, MIDCPNIFTY 100, SENSEX 176).
- **Entry:** on the 09:30 bar's close, sell 1 lot C(K0) and P(K0); fill at the 09:31 open.
- **Stop:** the first 1-minute index close from 09:31 that is at least k straddles from the 09:30 close. Both legs are
  bought back at the next open, and a leg without a print is priced by Black-76.
- **Otherwise:** out at the 15:05 open.
- **Costs:** R119's engine (volume-based slippage with late widening, dated charges per order).
- **Gates:** R122's (all periods positive, best days, 2x slippage, random-entry twin, BH, 40+ final trades, Boss's
  limits).

## Scoreboard

`research/LOOP_SCOREBOARD.md`.
