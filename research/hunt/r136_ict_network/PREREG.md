# R136 pre-registration: the ICT / SMC books' concepts inside R123's neural network

Written 29 Oct 2026, before any R136 feature value or result. Research only. Step 2 of `research/ICT_BOOKS_STUDY.md`
section 4; Boss: *"add all the knowledge to neuro network"*, then *"Ok"* to the plan.

## The question

Does R123's single network (N1) forecast better when it is also given the books' concepts? And does its EV filter
(R132 Amendment A) then pick better trades?

## The new inputs (all from index minutes up to and including the decision minute t; wicks; per index)

1. **Liquidity tiers (12):**
   - signed distance of the price from PDH, PDL, PWH, PWL, PMH and PML, in % of price;
   - six flags: has today's high (low) so far gone ≥ 1 tick beyond that level, after an open on the near side.
2. **Opening range (4):** after 09:44, the distance from the 09:15-09:44 high and low (%), and whether each has been
   swept today. Before 09:45: missing.
3. **Day position (2):** the distance from the day's open (%), and the place in the day's range so far (0-1).
4. **15-minute structure (pivots 3 bars each side, known only once confirmed; today and 4 sessions):**
   - distance to the last swing high and the last swing low (%);
   - whether each is **strong** (it swept an earlier untaken swing and price later closed beyond the opposite swing,
     both before t);
   - the direction of the last break of structure (a 15-minute close beyond the last swing): +1 / -1;
   - whether the swing it broke was strong;
   - the place of the price in the leg between the last swing low and high (0-1, premium/discount).
   - (7 inputs)
5. **Sweep and displacement (2):** in the last 6 completed 5-minute bars, a sweep of the recent untaken 5-minute swing
   low (+1) or high (-1); and whether that sweep bar began a three-bar gap against it (the "algo candle").
6. **Open fair value gaps (4):** 15-minute FVGs formed before t and not yet half filled. The distance to the nearest
   one above and below (%), and how many are open above and below.
7. **Daily bias (2):** the distance from the 200-session average of daily closes (%), and the 20-session return, both
   up to yesterday.
8. **TDI (2):** RSI(50) of 5-minute closes, minus its 50-bar band middle, divided by the band's SD; and whether it
   crossed the middle within the last 3 bars (+1 up / -1 down / 0).

Total: 35 inputs. A look-ahead test checks that each input is unchanged when every minute after t is removed.

## The comparison

- **Two networks, trained the same way, here, with the same seeds:**
  - **N1-base**: R123's inputs;
  - **N1-ICT**: R123's inputs plus the 35.
- **Training:** R123's walk-forward (folds F1 2023, F2 2024, F3 2025, F4 2026-01-01 .. 03-15). Train on everything
  before each fold minus a 10-day embargo, then predict the fold. R123's code, seeds, epochs and early stopping,
  unchanged.
- **Data:** R123's design rows (`rows_design.parquet`, 333,955 decision rows) and sequences.
  - The holdout is not used: it was already read for these entries (R131-R133).
- **Tests (BH over the 3):**
  - **T1 direction:** the log-loss of P(up over 30 minutes) against `y_dir_30`, N1-ICT vs N1-base, on the same rows.
    The p-value comes from a paired bootstrap over sessions (2,000 draws).
  - **T2 the trade cells:** the mean log-loss of the 16 cell-sides' 3-way outcome (`cls_j`), the same paired
    bootstrap.
  - **T3 the filter:** R133's primary rule (S3-V2-E0, CISD with R131's exits, 2023 - 15 Mar 2026 trades), filtered
    by each network's mean EV of the trade's side (> 0). The test is the kept trades' total, N1-ICT minus N1-base,
    against 2,000 random relabellings of which network's filter kept each trade (sign-flip of the paired
    difference).
- **Reported, not tested:**
  - the same filter on R133's other five passers and on all 39 R131 entries with R131's exits;
  - the shuffle importance of the new groups.
- **"Better" means** q ≤ 0.10 and the right sign. If N1-ICT is better on T3, it becomes the network for the R137
  strategy step. Otherwise R123's N1 stays.

## Outputs

- `research/hunt/r136_ict_network/` (`ict_features.py`, `r136.py`, tests); `results/compare.json`.
- `research/HUNT_R136_ICT_NETWORK.md`; entry `research/experiments/entries/r136-ict-network.yaml`.
- Big files (features, predictions, weights) go to `gdrive:IraAlgo-data-lake/derived/r136/`.
