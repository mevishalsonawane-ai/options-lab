# R145: a neural network over the top-100 crypto coins. What patterns are in the data? (pre-registration)

Written 3 Nov 2026, **before any R145 data was built, any model fitted or any result seen**. Boss (3 Nov 2026):
*"Create a neural network with the same data, and find the patterns of the data"*, where "the same data" is the 1-minute
candles of the top-100 crypto coins just loaded into the Drive crypto lake (`gdrive:IraAlgo-data-crypto`).

Earlier work this builds on (read first, reused where it applies):
- R123-R128 (Indian indices): direction from a network is a coin flip; the size/range head was the network's real skill.
- NN_CRYPTO (8 Oct 2026, BTC/ETH): a 1-D CNN found a real but tiny direction signal (AUC 0.52-0.57), far too small to pay
  taker fees; volatility forecasts were good.
- C1 / C2 (crypto brain, BTC/ETH): size is predictable, direction is not, on design data.

So the prior is: **volatility yes, direction no**. R145 asks whether a network over the whole cross-section of 96 coins
(with market context that two coins cannot give: breadth, dispersion, BTC lead) finds anything more, and says honestly
what is real and what is noise.

## 1. Data, universe, splits

- **Universe**: the 96 USD-M perpetuals in `crypto_top100_2025-11_2026-10_manifest.csv` (the top-100 coins by market
  cap that trade on Binance, stablecoins and fund tokens excluded). TON is TONUSDT; GRAMUSDT (TON's new name from Jul
  2026) is a separate symbol and enters only when it has its own history. A coin's row enters only after it has 14 days
  of bars (its listing warm-up).
  - **Survivorship caveat, said now**: the list is the top 100 *as of Oct 2026*. Coins that fell out before then are
    missing, so any "which coins rise" finding over days or weeks would be biased. R145's horizons are 15-240 minutes
    and its cross-sectional target is demeaned at each hour, which limits but does not remove this bias.
- **Price series (the underlying)**: Binance USD-M perpetual 1-minute klines (OHLCV, quote volume, trade count, taker-buy
  volume) and the perpetual funding-rate history. Months 2025-11 on are read from the Drive lake; earlier months come
  from data.binance.vision, the archive the lake itself was built from (same files, same checks). Raw minutes are never
  committed and are deleted after the 5-minute bars are built. Open interest, long/short ratios and mark/index prices
  exist only for a few coins and are **not** used (the network must work for all 96).
- **5-minute bars** are built from the minutes: open/high/low/close, quote volume, trades, taker-buy quote volume, the
  sum of squared 1-minute log returns (realised variance) and the count of minutes present. A bar with no minutes is
  missing (never filled). Lead-lag statistics are computed from the 1-minute returns while the minutes are in memory.
- **Decision points**: every hour on the hour (UTC). Row (coin, t) uses only bars that **closed by t**; the entry price
  is the close of the bar ending at t.
- **Design period**: rows from 8 Jan 2024 to 31 Mar 2026 (bars from 1 Dec 2023 for the warm-up). Rows whose 240-minute
  label window reaches 1 Apr 2026 are dropped.
- **Locked holdout**: from **1 Apr 2026 00:00 UTC** (`config.HOLDOUT_START`) to the lake's end (12 Oct 2026).
  - Status checked before writing this: the crypto brain's C1/C2 never read it. NN_CRYPTO, STRAD_CRYPTO and RC2 read
    Apr-Oct 2026 **for BTC and ETH** (other ideas). So for BTC/ETH it is "partly seen"; for the other 94 coins and for any
    cross-sectional question it is unread. R145's holdout data is not downloaded until the design results are committed.
  - Read **once**, at the end, only for the forecasts and rules that pass the design gates below.
- **Walk-forward (design)**: six quarterly test folds, 2024-Q4, 2025-Q1, Q2, Q3, Q4 and 2026-Q1. Each fold trains on
  every earlier design row (expanding), with label windows purged (a training row's 240-minute window must end before
  the test fold) and a 1-day embargo. The last 10% (by time) of each training set is the validation slice used for
  early stopping. Models are refitted every fold. Nothing is tuned on test folds.

## 2. Features (point-in-time; about 50; fixed now)

Coin's own (from its bars up to t):
- log returns over 5, 15, 60, 240, 1,440 and 4,320 minutes, each also divided by the coin's trailing 1-day volatility
  scaled to the horizon (so coins are comparable);
- log realised volatility over 60, 240 and 1,440 minutes (from the 1-minute squared returns), and the 60/1,440 ratio;
- position of the price in its 24-hour and 7-day high-low range; distance from the 24-hour VWAP in volatility units;
- log quote volume over the last 60 minutes against its 7-day hourly mean (z), trade count likewise;
- taker-buy share of quote volume over 60 and 240 minutes;
- funding: the last settled rate and its 30-day z-score; minutes to the next settlement;
- beta and correlation to BTC (7 days of 1-hour returns); the residual return (return minus beta x BTC's) over 60 and
  240 minutes;
- days since listing (log, capped at 365);
- cross-sectional ranks at t of: 60-minute return, 1,440-minute return, 1,440-minute volatility, volume z.

Market context (the same for every coin at t):
- BTC returns over 15, 60, 240, 1,440 minutes; ETH returns over 60 and 240; BTC log realised volatility 60 and 1,440;
- breadth: share of coins up over 60, 240 and 1,440 minutes; the equal-weight market return over 60 and 240 minutes;
  cross-sectional dispersion (standard deviation) of 60-minute returns; the market's median volatility z;
- calendar: IST hour (sine/cosine), weekday (sine/cosine), weekend flag.

Sequence input (for the sequence model only): the coin's last 48 five-minute bars (4 hours), six channels: return in
volatility units, log range, volume z, taker-buy share, BTC's return, the equal-weight market's return.

Missing values are set to the training median after standardisation (training statistics only). The look-ahead guard
(section 7) checks every feature.

## 3. Targets (labels) at 15, 60 and 240 minutes (15 in all)

For each coin and decision time t, with the price at t as the entry:
- **direction**: the log return to t + h is > 0;
- **size**: |return| divided by the coin's trailing 1-day volatility scaled to h;
- **volatility**: log realised volatility over (t, t + h] from the 1-minute squared returns;
- **big move**: |return| > 2 x the trailing volatility scaled to h;
- **cross-sectional rank**: the coin's return rank among all coins at t, mapped to a normal score (which coins
  outperform the others).

## 4. Models (fixed now; no tuning)

- **N1, multi-task MLP** on the tabular features: 128 -> 64 hidden units, SiLU, dropout 0.1, AdamW (lr 1e-3, weight decay
  1e-4), batch 4,096, up to 6 epochs with the best epoch chosen on the validation slice; 15 heads (binary cross-entropy
  for direction and big move, squared error for the standardised size, volatility and rank). Average of 3 seeds.
- **N2, sequence model**: a 1-layer GRU (32 units) over the 48-bar sequence, joined with N1's tabular trunk, the same 15
  heads; up to 4 epochs; if a training set has more than 800,000 rows it trains on a random 800,000 (CPU budget). 1 seed.
- **Baselines**:
  - B0 naive: the training base rate (direction, big move), the training mean (size), a HAR line on log realised
    volatility 60/240/1,440 (volatility), zero (rank, i.e. no skill);
  - B1 linear: ridge (alpha 1) / L2 logistic (C 1) on all tabular features;
  - B2 trees: LightGBM per target (31 leaves, learning rate 0.05, up to 300 rounds with early stopping on the
    validation slice, at least 1,000 rows a leaf, 80% features and rows per tree);
  - **twins**: N1 refitted on labels shuffled across rows (the "random twin": any skill it shows is the noise floor),
    and pure random predictions.

## 5. Tests: one Benjamini-Hochberg family, q = 0.05 (all of R145, design walk-forward)

Statistic, unless stated: per week, the mean improvement in loss of the model over its comparator on the out-of-sample
rows (log-loss for direction and big move, squared error for size and volatility; for rank, the week's mean per-hour
Spearman correlation, "IC"); one-sided t-test across the ~78 test weeks.

| id | what | tests |
|---|---|---|
| F1 | N1 beats B0, per target x horizon | 15 |
| F2 | N2 beats B0 | 15 |
| F3 | N1 beats B2 trees | 15 |
| F4 | N2 beats B2 trees | 15 |
| F5 | N1 beats B1 linear | 15 |
| F6 | N1 beats its shuffled-label twin | 15 |
| P1 | by IST hour: N1's 60-minute direction AUC > 0.5 and 60-minute rank IC > 0 within that hour (24 x 2) | 48 |
| P2 | by coin: N1's 60-minute direction AUC > 0.5 (weekly-block bootstrap, 2,000) | 96 |
| P3 | by market state (market volatility tercile x 3, BTC 1-day trend up/down, weekend/weekday): direction-60 AUC and rank-60 IC (7 x 2) | 14 |
| P4 | lead-lag, 1-minute: BTC's return k minutes ago predicts an alt's return now, k = 1, 2, 3, 5, 10 (mean correlation across coin-months > 0, t-test) and on 5-minute bars k = 1 | 6 |
| P5 | coin clusters are stable: adjusted Rand index of clusters fitted on the first and second half of design, against 1,000 label permutations | 1 |

240 tests. **A "real" pattern** needs: BH q < 0.05 **and** the same sign in at least 4 of the 6 folds **and** a minimum
size: AUC +0.005 (binary), R-squared +0.005 (size, volatility), IC >= 0.01 (rank); per-hour and per-coin cells AUC >= 0.52.
Everything else is reported as noise. Feature importance (permutation by feature group, on the last two folds) and the
cluster map are descriptions, not tests.

## 6. Trading test (only for direction or rank forecasts that are "real" above)

Signal on the underlying: the coin's perpetual price; the trade is the perpetual itself (no options).
- **TD-h (direction)**: at each decision hour, long the up-to-3 coins whose N1 P(up over h) is at or above the 95th
  percentile of that fold's validation predictions, short the up-to-3 at or below the 5th.
- **TX-h (rank)**: at each decision hour, long the 3 coins with the highest predicted rank and short the 3 lowest.
- **Hours**: Boss's crypto window (`config.WINDOW`, 00:00-09:00 IST) is the primary version: entries only when the
  whole holding window ends by 09:00 IST. All hours are reported as a secondary variant.
- **Every trade has a stop-loss and a profit lock** (CLAUDE.md crypto rule); "no stop" is never run. With s = the coin's
  trailing 1-day volatility scaled to h: stop at 1.0 s or 2.0 s; lock = **trail** (after +1 s, exit if the gain falls
  below half its peak) or **step** (after +1 s the stop moves to entry plus costs; after +2 s to +1 s). 2 x 2 = 4 exit
  variants, plus the time exit at h. Checked on 5-minute bars; a bar that touches both the stop and the lock level
  counts as the stop (worst case). One position per coin at a time.
- **Costs** (`costs.py`, `config.py`): perpetual taker 0.05% a side; slippage 1 bp a side for BTC/ETH, 2 bp for
  SOL/BNB/XRP (config) and **5 bp for every other coin** (an assumption for small alts, said now); funding at the last
  settled rate for each settlement held. Stressed at 2x slippage. Sized at Rs 1 lakh notional a trade, no leverage,
  no tax (India's 30% VDA tax with no loss set-off would make any result worse; see NN_CRYPTO).
- **Baselines**: random twins (same entry times and count, random coins and sides, the same exits, 1,000 draws);
  buy-and-hold BTC over the same period.
- **Gates (design)**: at least 100 trades; net per trade > 0 at 1x and 2x slippage; beats its random twins with BH
  q < 0.05 across all trading variants tried; positive in at least 4 of 6 folds; max drawdown no deeper than -Rs 40,000.
- **Gates (holdout, read once)**: at least 30 trades, net per trade > 0 at 2x slippage, beats the random twins (p < 0.10).
- Reported: trades, win rate, net per trade (% and Rs), max drawdown, worst month, with sample sizes.

## 7. Look-ahead guard

- Every feature is recomputed on the bar panel with every bar after a cut time t deleted, and again with them perturbed;
  every feature value at decision times <= t must be identical (the same check as `brain.guard`, for a cross-section).
  Run on synthetic data in pytest and on the real design panel.
- Labels must change when the future is perturbed and must not use bars at or before t (tested).
- Walk-forward folds: no training row's label window reaches its test fold (tested).

## 8. What happens with the results

- Everything is research only. Nothing goes to the app, paper or live, without Boss. A rule that passes every gate is
  written up as a NOTE / proposal.
- Every idea tried is logged in `research/experiments/entries/` (family `mcx-crypto` for patterns and trades, `ml` for
  the model tests), failures included.
- Write-up: `research/HUNT_R145_CRYPTO_NN.md`; results JSON in `research/brain/crypto/results/`; big files (bars,
  features, predictions, weights) in `gdrive:IraAlgo-data-crypto/derived/r145/`, never in git.
- Any change to this plan after a result is seen is an amendment, dated, with the reason, and reported as a deviation.

## Amendment 1 (3 Nov 2026, 10:40 UTC): the holdout procedure, fixed before the design results and the holdout

Written after only fold 0 (2024-Q4) of the design walk-forward had finished. No holdout data had been downloaded.

- **What was seen so far (design only):** a sanity check of fold 0's scores, which showed a 60-minute direction AUC
  of about 0.56 for N1 and the linear model (twin 0.50). To rule out a label artifact, that was re-checked without
  flat hours (a 0 return counts as "not up"): about 1% of hours are flat, and the AUC is unchanged. No setting, feature,
  model or gate was changed after this.
- **Final models for the holdout:** each model is refitted once on all design rows, with the same purge, 1-day
  embargo and last-10% validation slice. It is then scored on the holdout rows (decision times 1 Apr 2026 00:00 to
  12 Oct 2026, the lake's end), built from one panel spanning Dec 2023 to Oct 2026 (features are point in time, so the
  design rows of that panel are identical to the design build).
- **A design-"real" forecast or pattern is "confirmed"** if, on the holdout, its statistic has the same sign and a
  one-sided p-value whose BH q (across every holdout test read) is below 0.05. Sample sizes are reported. Patterns
  that were not "real" in design are not read on the holdout.
- **Trading rules:** the section 6 holdout gates.
- **Implementation notes, not changes:**
  - Funding is applied on the 00/08/16 UTC schedule; coins that settle every 4 hours are undercounted, a small effect
    over these holding times.
  - The N1 validation-slice percentiles for TD-h are saved by the fit.
  - Before any fit, a bug was fixed: funding had been looked up at the decision bar's open time (5 minutes early)
    instead of the decision time.

## Amendment 2 (3 Nov 2026, 16:30 UTC): stale (never-traded) rows are left out of every score

Written after a **dry run** of the design evaluation on partial predictions (the GRU had 3 of 6 folds), before the
final design evaluation and before any holdout data. What the dry run showed: the simple HAR line beat every model on
the volatility target (R2 0.92 vs 0.86). The cause was data, not skill: 7,894 of 1,483,142 design rows (0.53%) sit on
a price that did not move at all.
- LITUSDT accounts for 7,098 of them: a frozen price from Oct 2024 to Dec 2025, apparently a dead contract whose symbol
  was later reused.
- PUMPUSDT has 640 rows before its real trading began.
- Every coin is frozen for one exchange-wide hour, 28 Oct 2024 at 20:00 UTC.

Those rows have a realised variance of exactly 0 (log floor -16). The networks' inputs are clipped at +-6 standard
deviations (section 4), so the networks cannot follow these rows; the unclipped HAR line can.

**Rule (as R123's Amendment 2, "zero-volume minutes are never priced"):** a row whose last 60 minutes, or any label
window, has zero realised variance (log realised vol <= -15) is a stale row. It is left out of every test, score,
pattern and trade, for every model alike. Models are not refitted (they were trained with these rows; that can only
hurt them). The unfiltered scores are reported beside the filtered ones. The same rule applies to the holdout.
This is the only change, and it does not depend on any direction or rank result.
