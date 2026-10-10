# HUNT h1: machine learning for intraday index option buying (NIFTY, BANKNIFTY, FINNIFTY)

Written 7 Oct 2026. Code: `research/hunt/h1/` (`build.py`, `model.py`, `report.py`). Data: real Dhan option minute
bars with OI (nearest expiry, ATM±10), index minutes, India VIX. Costs: `obuy.costs` (app fills ±5 bps, stops −10 bps,
`Costs('app')` charges at today's rates, STT 0.15%). Size: 1 lot at today's lot (NIFTY 65, BANKNIFTY 35, FINNIFTY 60).

## Verdict (plain language)

**NO. Machine learning does not give a realistic Rs 5,000 a day from buying options.**

- **The model that looked best before the holdout** was a random forest. It predicts each option's 30-minute return
  and trades only the top 1% of predictions, at most 5 a day and one at a time. On the locked holdout
  (1 Oct 2025 – 5 Oct 2026) it made money:
  - Gross: **+Rs 49,040 a lot**.
  - Net of the app's costs: **+Rs 35,603**.
  - Net with a 1-point spread on top: **+Rs 28,183**.
  - That is 129 trades over 248 days: **Rs 198 a day gross, Rs 144 a day net, Rs 114 a day net with spread, at 1 lot.**
- **Why that is not proof:**
  - It trades only 0.5 times a day.
  - The 5 best trades made 71% of the net profit.
  - 60 of the 129 trades fell in March and April 2026.
  - The 95% bootstrap range of the net result is −Rs 10,700 to +Rs 83,000, so it includes zero.
  - The same model lost money (net) in 2022 and in 2023.
  - Across all 90 ML variants, Hansen's SPA test finds nothing (net p = 0.75; gross p = 0.07).
  - Choosing the variant each year from earlier years lost money in every test year (−Rs 3.6k, −45.5k and −41.4k
    net in 2023, 2024 and 2025).
- **Reaching Rs 5,000 a day would take about 35 lots at the holdout's rate.**
  - The capital at risk in premium would be Rs 17–23 lakh.
  - Using the walk-forward's worst drawdown, the drawdown would be about Rs 16.5 lakh.
  - Fills would be on thin FINNIFTY and BANKNIFTY monthly options, where a 2,100-qty order would move the price.
- **No simple rule found.** The model's picks are mostly about *option pricing* (premium relative to spot, expiry day,
  straddle level, liquidity), not about direction. Every few-line proxy I tried lost money net before the holdout.

## The single best rule, as close to simple as it gets

There is no short rule a person can follow. The best candidate is a model:

1. Every 5 minutes, from 09:19 to 14:54, score every ATM and 1-ITM CE/PE of NIFTY, BANKNIFTY and FINNIFTY (nearest
   expiry) with the random forest. It has 43 features: returns, realised volatility, range position, gap, VIX, ATM
   straddle, IV, PCR/OI changes, premium and volume.
2. If the best score is above the top-1% threshold and you are flat, buy that option at the next minute's open.
3. Sell it 30 minutes later (or at 15:10). Take at most 5 trades a day.

The closest simple proxies, checked on 2022 to Sep 2025 only, all lost net in 3–4 of 4 years:
- expiry day, fade the day's extreme, ATM or 1-ITM, 30-minute or +20/−10 exit;
- expiry day, follow the last 15 minutes;
- expiry day, any ATM.

Their best was "expiry day, follow 15-minute momentum, hold 30 minutes": +Rs 19.8 a trade gross, −Rs 38.4 net.

## Holdout result of the chosen variant (run once)

| | Gross | Net (app) | Net + 1-pt spread |
|---|---|---|---|
| Total, 1 lot, 248 days, 129 trades | +49,040 | +35,603 | +28,183 |
| Rs / trade | 380 | 276 | 218 |
| Rs / day | 198 | 144 | 114 |
| Win rate | 54% | 53% | 52% |
| Max drawdown (1 lot) | −7,746 | −8,772 | −9,527 |
| Worst day / worst month | −5,025 / −4,745 | −5,101 / −5,515 (Aug 2026) | −5,166 / −6,115 |
| P(losing month), bootstrap | 27% | 33% | 37% |
| Bootstrap 95% range of the total | +1.9k .. +96.5k | −10.7k .. +83.0k | −19.1k .. +75.0k |
| Random entries, same days / count / exit (mean per trade) | −35 | −147 | — |
| p (random beats real) | 0.018 | 0.016 | — |
| Lots for Rs 5,000/day | 26 | 35 | 44 |

**Split of the holdout:**
- By index (net): FINNIFTY +29.3k, NIFTY +7.1k, BANKNIFTY −0.7k.
- By side: calls +32.9k, puts +2.7k.
- By period: Oct–Dec 2025 −3.3k net; 2026 +38.9k.

The model's test AUC on the holdout (for "30-minute return > 0") was 0.504, which is no better than a coin flip. The
profit came from a few big moves, not from a reliable signal.

**At 35 lots (Rs 5,000/day net at the holdout's rate):**
- **Holdout risk:** a drawdown of about Rs 3.1 lakh, a worst day of −Rs 1.8 lakh and a worst month of −Rs 1.9 lakh.
- **Margin.** You only pay the premium, one position at a time:
  - Rs 17.4 lakh at the 95th percentile.
  - Rs 22.9 lakh at the maximum (FINNIFTY / BANKNIFTY monthly premiums of Rs 300–1,800).
- **Risk on the walk-forward's record:** a drawdown of 35 × Rs 47,275 = about Rs 16.5 lakh, and a worst day of about
  Rs 4.4 lakh.
- **Capital needed:** about Rs 40 lakh.
- **Fills:** 2,100 qty on FINNIFTY monthly is far beyond the 1-pt spread assumed here.

## Walk-forward before the holdout (2022 – Sep 2025, 925 trading days)

The chosen variant, rf | REG30 | top 1% | max 5, per lot:

| year | gross | net | net + spread |
|---|---|---|---|
| 2022 | +29,729 | −7,669 | −37,924 |
| 2023 | +6,526 | −6,183 | −17,263 |
| 2024 | +141,237 | +125,473 | +113,998 |
| 2025 (Jan–Sep) | +57,124 | +49,151 | +43,131 |
| total (1,069 trades) | +234,615 (Rs 254/day) | +160,772 (Rs 174/day) | +101,942 (Rs 110/day) |

- **Drawdown and bad days (net):** max drawdown −47,275, worst day −12,556, worst month −21,314 (Mar 2022).
- **Losing months:** 22 of 45 net. The bootstrap P(losing month) is 45%.
- **Where the profit came from:**
  - FINNIFTY made +125.7k of the 160.8k net.
  - Expiry-day trades (DTE 0–1) made +180.9k. All other DTEs together lost −20.1k.
  - It is one regime (2024 FINNIFTY expiry days), not a stable edge.

## Multiple testing and overfitting controls

- **Variants:** 3 models × 5 labels × 3 slices × 2 caps = **90 ML variants**, plus 8 simple-proxy checks, so **98 tried**.
  - Models: logistic / ridge, HistGradientBoosting, RandomForest.
  - Labels:
    - +20%/−10% in 30 minutes;
    - +30/−15 in 60;
    - +15/−15 in 30;
    - +40/−20 in 60;
    - the signed 30-minute forward return.
  - Slices: top 5%, 2% and 1% of predicted edge.
  - Caps: at most 2 or 5 trades a day.
- **Training:** anchored walk-forward, retrained each year (test 2022, 2023, 2024, Jan–Sep 2025), with a 2-trading-day
  embargo between train and test.
  - The top-x% thresholds come only from out-of-sample scores on a purged, embargoed validation block. This block is
    the last 25% of each training window, scored by a model trained on the earlier 75%.
  - For speed on the shared box, this was a single forward split, not k-fold.
- **How many made money:** 57 of 90 variants were positive gross and 20 of 90 net.
  - Median by label (net, total): only REG30 was positive (+49k). Each +T/−S label was negative (−16k to −84k).
- **Random-entry baseline:** same days, same trade count and same exits.
  - 22 variants beat random at BH q < 0.05 (net), but this only means they **lose less than random**. Random entries
    lose Rs 68–147 a trade.
- **White's Reality Check / Hansen's SPA** over the (925 days × 90 variants) daily P&L, against not trading:
  - gross: RC p 0.061, SPA p 0.067;
  - net: 0.336 / 0.747;
  - net + spread: 0.706 / 0.870.
  - So **no variant is significant after costs.**
- **PBO (CSCV, 16 blocks):** 0.24 gross, 0.17 net.
- **Meta walk-forward:** picking the best variant on earlier years always chose lr | REG30 | top 1% | max 5. Net result:
  −3.6k (2023), −45.5k (2024), −41.4k (2025).

## What the models learned (feature importance stability)

- **Hit-probability labels (e.g. +20%/−10%):**
  - AUCs are high: 0.64–0.78 out of sample, rank IC 0.20–0.31.
  - The top features are stable. The rank correlation of importances between consecutive years is 0.79–0.82 for LR and
    0.90–0.95 for RF. They are always premium as % of spot, days to expiry, ATM straddle, and realised vol.
  - This is option arithmetic: cheap, near-expiry, high-gamma options hit +20% more often, but they hit −10% more often
    too. Trading the top slice lost net in the median variant.
- **Signed 30-minute return (the only label with any profit):**
  - Out-of-sample AUC is 0.50–0.53 and IC 0.02–0.09, almost no skill.
  - RF importances are stable (0.90–0.95), again on pricing and liquidity features (premium, straddle, log volume, time
    of day).
  - HGB importances are unstable (rank correlation 0.20–0.39).
  - Directional features (returns, VWAP distance, PCR / OI change, gap, VIX change) never made a stable top 6.

## Setup details

- **Decision points.** The close of every 5-minute bar from 09:19 to 14:54. The fill is the option's open on the next
  minute.
  - Candidates are CE/PE × ATM / 1-ITM per index, which gives 1,012,152 candidate rows over 3,912 index-days.
  - Rows are kept only when premium ≥ Rs 5 and the contract traded in the last 5 minutes.
  - Every feature uses data up to the decision minute only.
- **Exits:**
  - Premium target / stop on the option's own high / low.
  - If the stop and the target fall in the same minute, the stop is assumed to come first.
  - A stop that gaps fills at the bar's open.
  - Time exit at the close of bar L. Square-off at 15:10.
- **Positions.** One position at a time across all three indices. At each decision minute, take the best-scored
  candidate above the threshold.
- **Net + spread.** This also subtracts 1 point a round trip. A target still fills on a touch, so this column is
  slightly optimistic for the T/S labels.
- **Sizing.** "Rs 5,000/day" assumes P&L scales linearly with lots. It ignores market impact, which is not true at
  20–45 lots on monthly FINNIFTY / BANKNIFTY.
- **Outputs (scratchpad `hunt/h1/`):** `variants_scored.csv`, `select.json`, `holdout.json`, `holdout_trades.csv`,
  `report.json`. The row caches (`rows_*.parquet`, `wf.pkl`) were deleted to save disk. Rebuild them with
  `build.py` and `model.py wf`.
