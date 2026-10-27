# R125: can R123's expiry-day straddle fit inside Boss's limits? (research loop, round 5)

Written 27 Oct 2026, for Boss (*"find the best strategy you can with max winrate"*).

- **Pre-registration:** `research/hunt/r125_risk_overlay/PREREG.md`, commit `92abedc`, pushed before any number.
- **Code and tests:** pushed before the run (`f2ecc4e`).
- **Tables:** `research/hunt/r125_risk_overlay/results/report.md`.
- **Experiment entries:** `research/experiments/entries/r125-z1.yaml`, `r125-z2.yaml` (both failures).
- **Research only.** Post hoc: built on R123's known trades, and said so in advance.

## Verdict for Boss

**0 of 2 pass. Z2 comes closest to Boss's limits of anything in R117-R125, but not close enough, and its rules do no
better than chance.**

- **The starting point:** R123 V2, the best rule so far (sell the ATM straddle at 09:30 on expiry days, close it if
  the index moves 1.5 straddles, else 15:05). It failed Boss's limits because up to five indices were traded on the
  same day and their losses stacked.
- **The two rules tested:**
  - **Z1:** take only one index a day, in a fixed order (NIFTY, SENSEX, BANKNIFTY, FINNIFTY, MIDCPNIFTY).
  - **Z2:** Z1 plus a monthly circuit breaker. Once the month is down Rs 6,500 (half Boss's Rs 13k stop), stop for
    the rest of that month.

| book (1 lot) | trades | win rate | mean after costs (design / wf / final) | final worst day | final max DD | months ≤ −13k (final / 2021-26) | 2021-26 max DD |
|---|---|---|---|---|---|---|---|
| R123 V2 as tested | 889 | 59% | +277 / +668 / +273 | −17,585 | 44,686 | 3 / 7 | 70,677 |
| Z1, one a day | 698 | 59% | +221 / +361 / +106 | −9,036 | 37,626 | 2 / 5 | 75,430 |
| **Z2, plus breaker** | 548 | 59% | **+167 / +172 / +201** | −9,036 | **29,348** | **0 / 1** | 46,834 |

- **Z2 is positive in every period** and wins 58-63% of trades.
- **In 2026 it stays inside both of Boss's limits:** no month at −Rs 13k, and a drawdown of Rs 29k.
- **Over 2021-26 it breaks them once:** one month in 2024-25 reached −Rs 13k, and the drawdown peaked at Rs 46,834.
- **Why it still fails:**
  - **The 2026 profit is in a few days.** Without its 3 best days 2026 is −Rs 18,384.
  - **The rules do not beat chance.** Skipping the same number of trades at random does as well (twin p 0.39 for Z2,
    0.61 for Z1). The breaker cuts risk because it cuts trades, not because it picks the bad ones.
  - **The 2024-25 limit breach** above.
  - **Multiple testing:** q = 1.0 over 9.3 million logged tests.
- **The cost of safety:** the breaker keeps the risk inside Boss's limits by giving up most of the profit. From
  R123's Rs 3.98 lakh to Rs 94,536 over 2021-26, about Rs 18k a year on one lot.

## Multiple testing within this loop (not a gate)

Benjamini-Hochberg over the loop's own 10 tests (R121-R125):

- The lowest q is **R122 U2, 0.05**: the expiry-day opening premium (09:20 → 10:30). It beat random later entry
  times (twin p 0.005), but lost in 2021-23 and 2024-25.
- Every other loop test has q ≥ 0.14.
- Over all 9.3 million logged tests, every q is 1.0. Boss's all-families rule needs p near 1e-8, which no test here
  approaches.

## What it means

- The expiry-day short straddle is the one real, after-cost edge this programme has found.
- At Boss's −Rs 13k / −Rs 40k limits it can only be run so small, or so filtered, that its profit is a few tens of
  thousands of rupees a year. The filtering cannot be shown to be better than luck.
- It stays a note (CLAUDE.md). If Boss wanted to know whether it is real, the honest test is a frozen forward paper
  test of Z2 from today. Boss would have to choose that; it is not proposed as a passer.

## Scoreboard

`research/LOOP_SCOREBOARD.md`.
