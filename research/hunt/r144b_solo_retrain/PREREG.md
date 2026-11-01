# R144b-S pre-registration: retrain Solo 2.0 on Dec 2022 - Oct 2026, judged only on months each model never saw

Written 1 Nov 2026, before any R144b-S number. Research only: **nothing in the app or the Solo 2.0 arm changes.**
Boss, 1 Nov: *"Do option 3, retrain Solo 2.0 study"* (after R144b addendum 2: the shipped model picked worse than a
random same-day candidate on 2022-24, p 0.93).

## What is already known, and therefore not fresh

- **The lake ends 14 Oct 2026, so no month is untouched.** R144b already read Solo 2.0's 2022-24 and final months,
  and looked at each setup's net in every period. It saw that S1 made money in all three periods and S4 lost in all
  three.
- **What is honest here:** every number below is from a model that never trained on the month it is judged on (purged
  walk-forward). The "no S4" variant is labelled as **informed by that look**. Its result is weaker evidence than V1's.
- **A passing variant is a candidate for a forward test on new months,** not proof.

## Data and candidates

- **Candidates:** R57's setups unchanged (S1 SoloMidday 12:00, S2 Donchian 20, S3 open outside value, S4 15-minute
  opening range; C4 never). NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY; never on an index's own expiry day. Each
  candidate is priced by R57's engine with its own exits, 1 lot, dated charges.
- **Sources:**
  - Dec 2022 - Nov 2024 from R144b's old R57 store (`r144b_old`);
  - 20 Nov 2024 - 13 Oct 2026 from R57's store.
- **Features:** A2's 29 inputs (`r57model.names_a(False)`), read at the signal bar's close. Label: net > 0.

## The walk-forward (identical for every variant)

- **Monthly refits, expanding window from Dec 2022.** Each test month's model trains on every candidate whose day and
  exit are before the month, minus a 1-session embargo (R57's `train_mask`, `walk`).
- **Threshold:** the 70th percentile of the training scores (R57's rule).
- **The pick:** each day, candidates in time order (same minute: highest score first); the first at or above the
  threshold is taken; then nothing more that day (R57's `select_a`).
- **Test months:** Jul 2023 - Oct 2026. Jun 2023 and earlier are training only, so the first model has >= 6 months.

## Variants (3, all gated)

| id | model | setups |
|---|---|---|
| **V1** | logistic (A2's recipe) | S1-S4 |
| **V2** | logistic | S1-S3 (no S4; informed by R144b's look) |
| **V3** | gradient-boosted trees (R57's A1 recipe) | S1-S3 |

**Reported, not gated:**
- the shipped frozen A2 (today's arm);
- S1 alone, which is Solo (midday)'s setup taken every day it fires;
- a random same-day pick.

## Gates, in EACH of three periods separately

The periods: **P1** Jul 2023 - 19 Nov 2024; **P2** 20 Nov 2024 - 31 Mar 2026; **P3** 1 Apr - 13 Oct 2026.

1. net > 0, after charges;
2. better than a random same-day pick: 2,000 twins pick a uniformly random candidate (of the variant's setups) on each
   day the variant traded; p = share of twins with net >= the variant's; p <= 0.05;
3. >= 40 trades;
4. max drawdown no worse than the frozen A2's in that period;
5. BH q <= 0.10 over all 9 (variant x period) p-values.

A variant **passes** only if it passes every gate in all three periods.

Reported: win rate, net per trade, by index and by setup, by period. If more than one passes, the one with the
smallest sum of losing days over P1-P3 is named.

## If one passes

- It is **proposed, not built**: a replacement model for Solo 2.0, trained on all data to Oct 2026 and exported as R57
  does, first run as a forward paper test that is logged only.
- Boss decides. Live is untouched; Solo 2.0 is paper only.

## Outputs

`r144bs.py`, `test_r144bs.py`, `results/retrain.json`, `results/pvalues.json`, `research/HUNT_R144B_SOLO_RETRAIN.md`,
entry `research/experiments/entries/r144b-solo-retrain.yaml`.
