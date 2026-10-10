# STRAD-INDEX: buy NIFTY / BANKNIFTY straddles when a big move is forecast and the straddle is cheap

Written 8 Oct 2026. Option BUYING only. ATM straddle or 1-strike-out strangle, nearest expiry (NIFTY weekly; BANKNIFTY
weekly until Nov 2024, then monthly). 1 lot per leg at today's lot (NIFTY 65, BANKNIFTY 30). Capital Rs 1 lakh.
App charges plus the real spread (half-spread 0.16% ATM, 0.20% strangle legs; 2x shown as stress).

- Plan, written before any P&L: `research/hunt/strad_index/PREREG.md`.
- Code: `research/hunt/strad_index/` (`build.py`, `models.py`, `rules.py`).
- Logs and CSVs: `scratchpad/hunt/strad_index/` (`design.log`, `holdout.log`, `gap_*.log`, `design_variants.csv`,
  `design_yearly.csv`, `design_wf.csv`, `holdout_results.csv`, `holdout_monthly.csv`, `gap_*.csv`, `model_skill*.csv`).
  About 35 MB in total.

## Verdict

**NO. Forecasting the SIZE of the move works. Buying straddles on that forecast does not pay.**

1. **The size forecast is good.** Out of sample it explains 60-80% of the variation in log realised volatility.
   Rank correlation is 0.78-0.89. It held in the holdout too.
2. **The options already know it.** On average the index moved only **0.69x** what the straddle priced
   (both indices, every horizon, 2022 to Sep 2025). In the holdout it was **0.61-0.72x**.
   The forecast agrees with the price almost exactly: forecast / implied was 0.65-0.77x, and realised / forecast was
   about 1.0. So "big forecast" days are big-premium days.
3. **0 of 2,520 variants made money net before the holdout.** Only 261 (10%) were positive even gross.
   The best lost Rs 3/day net. Costs are Rs 158 (NIFTY) and Rs 203 (BANKNIFTY) per round trip.
4. **The filters do pick better times than random.** 335 variants beat random times at p < 0.05, and 158 at BH q < 0.10.
   "Size AND cheap" was the best family. But "less bad" is not "good": every one of them still lost money net.
5. **Walk-forward lost money:** -Rs 71k (NIFTY) and -Rs 69k (BANKNIFTY), 2022 to Sep 2025, with stand-aside allowed.
   If always forced to trade: -Rs 2.22 L and -Rs 2.43 L.
6. **Hansen SPA p = 1.00 and White RC p = 1.00.** No variant beats not trading.
7. **Holdout (1 Oct 2025 - 29 Sep 2026, run once):**
   - The NIFTY pick made **+Rs 23/day** (+Rs 106 a trade, 50 trades, random p 0.045).
   - The BANKNIFTY pick lost **-Rs 73/day**.
   - Both together lost **-Rs 51/day**.
   - The NIFTY result fails three gates: walk-forward < 0, BH q = 1.0, and only 4 of 7 months green.
     On its own it is one lucky January.

**Rs 5,000/day: NO. Not at 1 lot, and not at any size, because the expected value per trade is negative.**

## What was tested

| piece | what |
|---|---|
| Decision times | every 15 min, 09:20 to 14:50 (23 a day); entry at the next minute's open |
| Horizons | 15, 30, 60, 120 min, and rest of day (exit 15:10) |
| Exits | time only, +20%, +40%, -25%, -40%, +20%/-25%, +40%/-40% on the combined 1-min premium; the time exit always applies |
| Size forecast | ensemble of HAR-X ridge + gradient boosting + MLP (sklearn; torch not installed), target = log realised vol over the horizon |
| Features | RV over the last 15/30/60 min, since open, yesterday, 5 days; range so far; prior-day range; gap; prior-day return and a 1%-down flag; time of day; weekday; days to expiry; expiry day; VIX level and change today; h18 gamma concentration (B_lvl, A_share); RBI, Budget, election, post-US-CPI, post-FOMC flags |
| Cheapness | R = forecast RV / straddle-implied RV over the same horizon |
| Filters | ALWAYS; SIZE top 30/20/10%; CHEAP top 30/20/10%; SIZE AND CHEAP (9 pairs); R >= 1; R >= 1 and SIZE top 20% (thresholds = trailing 60 days, same clock slot) |
| Variants | 2 indices x 5 horizons x 2 structures x 7 exits x 18 filters = **2,520**, all counted |
| Walk-forward | anchored by year. Models refitted each year. Test years 2022, 2023, 2024, 2025 (Jan-Sep) |
| Look-ahead check | a perturbation audit scrambled all bars after the decision minute; 0 features changed |

## 1. The size forecast (out of sample)

| horizon | R^2 HAR | R^2 GBM | R^2 MLP | R^2 ensemble | Spearman ensemble | holdout R^2 ens. | holdout Spearman |
|---|---|---|---|---|---|---|---|
| 15 min | 0.62 | 0.61 | 0.33 | 0.59 | 0.77 | 0.62 | 0.79 |
| 30 min | 0.67 | 0.66 | 0.40 | 0.65 | 0.80 | 0.68 | 0.82 |
| 60 min | 0.68 | 0.67 | 0.30 | 0.65 | 0.80 | 0.71 | 0.84 |
| 120 min | 0.67 | 0.64 | 0.02 | 0.59 | 0.78 | 0.72 | 0.84 |
| rest of day | 0.78 | 0.76 | 0.37 | 0.74 | 0.86 | 0.80 | 0.89 |

- Design columns are the mean over the test years 2022-2025.
- Plain HAR is as good as boosting. The small neural net is the weakest.
- The pre-registered ensemble was kept, even though HAR alone was a little better. No model was chosen by score.

## 2. Is the straddle cheap? Implied vs realised

"Move / implied" = mean realised |index move| over the horizon ÷ mean move priced by the ATM straddle.
The straddle's variance is spread over trading minutes only. So the overnight risk it carries makes it look dearer.
Below 1.0 means the buyer overpaid.

**By index and horizon**

| horizon | BN design | NIFTY design | BN holdout | NIFTY holdout |
|---|---|---|---|---|
| 15 min | 0.70 | 0.70 | 0.62 | 0.64 |
| 30 min | 0.69 | 0.69 | 0.61 | 0.63 |
| 60 min | 0.69 | 0.69 | 0.61 | 0.62 |
| 120 min | 0.69 | 0.69 | 0.61 | 0.63 |
| rest of day | 0.72 | 0.76 | 0.67 | 0.72 |

- Realised vol vs Dhan's ATM IV (both annualised) was 0.47-0.56 before the holdout and 0.54-0.66 in it.
- This matches h29's ~0.73x.

**By time of day (30-minute horizon)**

| decision time | BN design | NIFTY design | BN holdout | NIFTY holdout |
|---|---|---|---|---|
| 09:20 | **1.06** | **1.03** | 0.90 | 0.91 |
| 09:35 | 0.97 | 0.96 | 0.72 | 0.81 |
| 10:05 | 0.78 | 0.77 | 0.70 | 0.72 |
| 11:05 | 0.61 | 0.61 | 0.59 | 0.63 |
| 12:05 | 0.62 | 0.62 | 0.60 | 0.62 |
| 13:05 | 0.60 | 0.60 | 0.62 | 0.60 |
| 14:05 | 0.67 | 0.66 | 0.58 | 0.59 |

- Only the first 15-20 minutes moved as much as priced (h42: the open is the busiest time). Even there it was about 1.0,
  not enough to pay the spread and charges.
- That edge shrank in the holdout, to 0.90.
- Every always-buy 15-minute variant lost money.

**By event type (60-minute horizon)**

| event | BN design | NIFTY design | BN holdout | NIFTY holdout | rows design (BN) |
|---|---|---|---|---|---|
| normal day | 0.63 | 0.63 | 0.58 | 0.58 | 12,220 |
| expiry day | 0.85 | 0.86 | 0.67 | 0.77 | 3,220 |
| after a >= 1% down day | 0.78 | 0.76 | 0.67 | 0.62 | 1,620 |
| RBI policy day | 0.77 | 0.74 | 0.70 | 0.65 | 360 |
| morning after US CPI | 0.65 | 0.66 | 0.65 | 0.60 | 640 |
| morning after FOMC | 0.56 | 0.68 | 0.64 | 0.59 | 280 |
| Budget day | 1.03 | 1.02 | 1.48 | 1.22 | 100 (5 days) |
| election result day | 1.06 | 1.31 | 0.53 | 0.57 | 40 (2 days) |

- Expiry days and days after a 1% fall are the least overpriced. That fits h42 ("the range nearly doubles after a 1%
  down day"). But the price already rises to match.
- Budget days were the only events where the move beat the price. That is one day a year, 4-5 days in all, so it is
  anecdote, not a rule. India CPI dates were not in the calendar.

## 3. Rules before the holdout (OOS years 2022 to Sep 2025)

**Always buy (straddle, time exit only) - the baseline**

| index | horizon | trades | hit | gross / trade | net / trade | net / day |
|---|---|---|---|---|---|---|
| NIFTY | 15 min | 11,100 | 20% | -29 | -193 | -2,311 |
| NIFTY | 60 min | 3,700 | 27% | -53 | -219 | -875 |
| NIFTY | rest of day | 925 | 33% | -213 | -383 | -383 |
| BANKNIFTY | 15 min | 11,088 | 20% | -40 | -248 | -2,974 |
| BANKNIFTY | 60 min | 3,696 | 25% | -60 | -270 | -1,078 |
| BANKNIFTY | rest of day | 924 | 29% | -383 | -597 | -597 |

**Median over each family (all horizons, exits and structures)**

| family | NIFTY gross / trade | NIFTY net / trade | BN gross / trade | BN net / trade |
|---|---|---|---|---|
| A always | -60 | -219 | -61 | -270 |
| B size only | -168 | -340 | -89 | -313 |
| C cheap only | -76 | -216 | -54 | -233 |
| D size AND cheap | -123 | -287 | -17 | -223 |
| E forecast >= priced | -184 | -327 | -54 | -239 |

- "Size only" is worse than always buying. The model picks volatile moments, and the straddle is dearest exactly then.
- "Cheap" helps a little. "Size AND cheap" helps most on BANKNIFTY. Gross gets close to zero, but costs remain.

**Best variants by net Rs/day** (these are also the frozen holdout picks)

| variant | trades | hit | gross / trade | net / trade | net / day | max DD | green months | random p | BH q (t) |
|---|---|---|---|---|---|---|---|---|---|
| BN straddle, rest of day, +20%/-25%, SIZE top 10% AND CHEAP top 20% | 260 | 47% | +173 | -11 | -3 | -45k | 42% | 0.000 | 1.0 |
| BN straddle, rest of day, +20%, same filter | 208 | 51% | +154 | -43 | -10 | -53k | 55% | 0.003 | 1.0 |
| NIFTY straddle, 120 min, +20%/-25%, SIZE top 10% AND CHEAP top 10% | 151 | 36% | -56 | -221 | -36 | -36k | 20% | 0.44 | 1.0 |

- The best variant per index loses so little per day only because it trades rarely: about one trade a week.
- Multiple testing over all 2,520:
  - Not one variant is net-positive. The smallest t-test p is 0.53, and BH q = 1.0 everywhere.
  - SPA p = 1.00 and RC p = 1.00.
  - At 2x spread, 0 variants are positive.

**Walk-forward (pick on earlier OOS years, trade the next year)**

| index | 2022 | 2023 | 2024 | 2025 (Jan-Sep) | total (stand-aside allowed) | total (forced) |
|---|---|---|---|---|---|---|
| NIFTY | stand aside (forced: -111k) | -16.9k | -54.1k | stand aside (forced: -40.1k) | **-71.0k** | -222.2k |
| BANKNIFTY | stand aside (forced: -173.8k) | +2.5k | -3.9k | -67.4k | **-68.8k** | -242.6k |

The 2022 picks could only come from family A (always buy), scored on 2021, as pre-registered.

## 4. Locked holdout (1 Oct 2025 - 29 Sep 2026), run once

The data ends at the last expiry in the feature build (29 Sep 2026). NIFTY had 236 days and BANKNIFTY 245.

| rule (frozen) | trades | hit | gross / trade | net / trade | gross / day | net / day | net 2x / day | max DD | green months | random p |
|---|---|---|---|---|---|---|---|---|---|---|
| **NIFTY primary** (120 min, +20/-25, SIZE90 AND CHEAP90) | 50 | 50% | +270 | +106 | +57 | **+23** | +14 | -6.8k | 4/7 (57%) | 0.045 |
| **BANKNIFTY primary** (rest of day, +20/-25, SIZE90 AND CHEAP80) | 59 | 39% | -59 | -301 | -14 | **-73** | -93 | -34.4k | 2/7 (29%) | 0.18 |
| Both primaries, Rs 1 L cap | 109 | 44% | +92 | -114 | +41 | **-51** | -79 | -34.8k | 3/8 | - |
| NIFTY best always-buy (rest of day) | 236 | 34% | -181 | -363 | -181 | -363 | -414 | -110k | 25% | 0.68 |
| BN best always-buy (strangle, rest of day) | 245 | 30% | -225 | -554 | -225 | -554 | -701 | -141k | 17% | 0.69 |
| NIFTY best size-only | 70 | 37% | -420 | -617 | -124 | -183 | | -61k | 22% | 0.88 |
| BN best size-only | 71 | 32% | -398 | -707 | -115 | -205 | | -50k | 38% | 0.80 |
| NIFTY best cheap-only | 88 | 35% | -14 | -165 | -5 | -61 | | -26k | 42% | 0.25 |
| BN best cheap-only | 104 | 26% | -103 | -382 | -44 | -162 | | -52k | 36% | 0.48 |
| BN best "forecast >= priced" + size | 16 | 63% | +1,058 | +833 | +69 | +54 | | -8.9k | 67% | 0.006 |

- NIFTY primary by month (net): Oct +1.3k, Dec +0.8k, **Jan +7.4k**, Feb -3.3k, Mar +2.6k, Jul -1.0k, Sep -2.5k.
  Drop January and it is about -2k.
- BANKNIFTY's 16-trade "forecast >= priced" row is not the frozen rule. It is shown for comparison only. With 16 trades
  it proves nothing.

**Gates** (all must pass):

| gate | NIFTY | BANKNIFTY |
|---|---|---|
| 1. walk-forward net > 0 | fail (-71k) | fail (-69k) |
| 2. holdout net / trade > 0 | pass (+106) | fail (-301) |
| 3. holdout random p < 0.05 | pass (0.045) | fail (0.18) |
| 4. design BH q < 0.10 and SPA p < 0.10 | fail (q 1.0, SPA 1.0) | fail |
| 5. >= 60% green months in the holdout | fail (57%) | fail (29%) |

## Why it fails, in one paragraph

Index option sellers price the move well. The straddle's price already rises when our model says "big": on high-forecast
days and after down days, implied rises about as much as realised. Over 2022-2025 the index moved about 0.69x what
the straddle priced. The buyer also pays about Rs 160-200 per round trip in charges and spread. That is 1-2% of the
premium on a 1-hour trade, while the typical 1-hour gross result is a 0.5-1% loss. The filters do find moments that are
less overpriced than average (they beat random times). But nothing found moments that are underpriced by enough to
pay the costs.

## Honesty notes

- Everything was pre-registered before any P&L. Nothing was changed after seeing results.
  - One fix before the design run: random-baseline draws with replacement (n is much smaller than the pool), and a
    normal approximation for n > 500.
- The 2022 thresholds were seeded with 2021 in-sample forecasts (60 days). All later thresholds use forecasts as they
  were made.
- The spread is one snapshot (h24, 6 Oct 2026). Minute data has no bid/ask, so exits on 1-minute closes use mid prices.
- Today's lot is used for all years.
- The holdout was opened once, for the 12 frozen rules (2 primaries + the best of each family per index) and the
  descriptive gap tables.
- `research/NN_CRUDE.md`, `NN_CRYPTO.md`, `X3_AUDIT.md` and `STRAD_CRUDE/CRYPTO.md` did not exist on this machine,
  so I reused the sister studies' PREREG designs instead.
