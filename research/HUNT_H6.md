# HUNT h6: relative value across indices with bought options only. Can it make Rs 5,000/day?

## Verdict

**NO.** Relative-value and lead-lag ideas built only from bought options do not realistically make Rs 5,000/day.
They do not make even Rs 50/day at 1 lot after costs.

- **Pairs of bought options are a structural loser.** The idea is to buy a call on the index that is leading and a put
  on the index that is lagging, both sized to equal notional (delta-balanced).
  - Each pair pays two premiums' worth of time decay plus two sets of charges and spread.
  - The spread between the indices that it bets on is tiny. At NIFTY vs BANKNIFTY the next 60 minutes' spread moves
    about 13 bps on average, and NIFTY vs SENSEX about 2-3 bps. Divergence after the first 30-60 minutes neither
    persists nor reverts in any usable way: the correlations are within +-0.1.
  - Random pairs (same days, same indices, same lots, random minute and orientation) lose **Rs 140-400 a pair
    net**. All 24 divergence-pair variants lost money net, in both the momentum and the mean-reversion version.
- **Catch-up (buy the laggard in the leader's direction)** is not better than chance. The 12 variants range from
  -Rs 70k to +Rs 4.7k in sample, and the walk-forward lost Rs 73k (2022-Sep 2025).
- **Heavyweight / breadth lead-lag (214 F&O stocks, Oct 2024 onward)** looked good in sample, but on the holdout it
  shrank to about zero after costs.
  - The best in-sample rule made **+Rs 41k** net in about 10 months.
  - The locked holdout year made **+Rs 7.7k gross and +Rs 1.4k net**, which is **Rs 31/day gross and Rs 5.5/day net**
    at 1 lot.
  - It beat random entries with the same exits (random lost Rs 267/trade; p = 0.13), but it did not beat the costs.
- **Multiple testing over all 84 variants.** SPA p = 0.83 and White RC p = 0.96. The best BH q is 0.08 and Holm is
  0.08, so no variant passes at 5%. Neighbouring variants flip sign between the in-sample period and the holdout (for
  example, basket T15 k1.0 square-off: -Rs 54k in sample, +Rs 75k on the holdout). That is noise.

## The single best SIMPLE rule (chosen on in-sample data only; holdout run once)

"Heavyweights lead the index", HEAVY_basket_T15_k1.5_follow, 60-minute exit:
1. At 09:30 (after the first 15 one-minute bars), compute the open-to-09:29 return of an equal-weight heavyweight
   basket, minus the index's own open-to-09:29 return:
   - for NIFTY: HDFCBANK, ICICIBANK, RELIANCE, INFY, BHARTIARTL, LT, ITC, TCS, AXISBANK, KOTAKBANK and SBIN;
   - for BANKNIFTY: HDFCBANK, ICICIBANK, SBIN, AXISBANK and KOTAKBANK.
2. Divide by the standard deviation of that same number over the previous 60 days. If |z| > 1.5, buy the index's ATM
   option in the sign of z (heavyweights stronger: call; weaker: put). Skip expiry days.
3. Exit 60 minutes after entry, or at 15:10. Use no stop. Take 1 lot, with at most one trade per index a day
   (about 0.25 trades/day).

| period | trades | gross Rs | net Rs (real) | gross Rs/day | net Rs/day | worst day | worst month | max DD | losing months | P(losing month), bootstrap |
|---|---|---|---|---|---|---|---|---|---|---|
| in-sample Dec 2024-Sep 2025 | 48 | +46,277 | +41,320 | ~190 | ~169 | n/a | -4.3k | 9.7k | 3/10 | n/a |
| **HOLDOUT 1 Oct 2025-5 Oct 2026 (249 days)** | 63 | **+7,658** | **+1,380** | **31** | **5.5** | -7.1k | -7.9k | 18.0k | 4/13 | 48% |

- **Net.** "Real" net uses `liq` fills (app ±bps plus a 1-4 tick half-spread from volume) and the app's SandboxCosts
  at today's rates (STT 0.15%). The app's own fills give +Rs 1,688.
- **Concentration.** In sample, 3 of the 48 trades made most of the profit, and the median trade was -Rs 83. The gains
  came from Jan-May 2025.

### Size needed for Rs 5,000/day (on the holdout)

- **Gross, 162 lots** (5,000 / 31):
  - The premium outlay is about Rs 23 lakh per trade, at Rs 14k a lot. Buying needs the full premium; there is no
    leverage.
  - The max drawdown scales to about Rs 29 lakh, and the worst day to about -Rs 11.6 lakh.
- **Net, about 900 lots:**
  - The outlay is about Rs 1.3 crore a trade, and the drawdown about Rs 1.6 crore.
  - That is 67,500 NIFTY units, far above the freeze limits and the ATM liquidity in one minute, so impact would
    erase the rest.
- **Rs 5,000/day realistically: NO.**

## What was tested (84 variants plus controls, all declared up front; `research/hunt/h6/`)

| family | idea | grid | in-sample best net (Rs) | holdout (that variant) |
|---|---|---|---|---|
| DIV pairs | the most divergent of the 6 index pairs at T = 30 / 60 min with \|z\| > 1.5 / 2; momentum (call the leader + put the laggard) or reversion; lots sized to equal notional | 2T x 2k x 2 modes x 3 exits = 24 | +769 (mom T30 k2, 60 min) | -14.9k |
| RPAIR control | the same day, indices and lots; random minute 09:30-13:30 and random orientation; 5 draws per pair | n/a | -Rs 140 to -403 per pair | n/a |
| CATCH | buy the laggard's ATM option in the direction of the leader (the leader's own move \|z\| > 1) | 2T x 2k x 3 exits = 12 | +4,743 | n/a |
| HEAVY | a heavyweight basket or breadth (214 stocks) residual vs the index at T = 5 / 15 min, \|z\| > 1 / 1.5, follow or fade | 2 features x 2T x 2k x 2 x 3 exits = 48 | +41,320 | +1,380 net |

- **Exits for every family:** a 60-minute time exit, square-off at 15:10, or a 30% premium stop.
- **Executions:** gross (print fills, zero charges), app, and real (liq fills plus app charges).
- **Strikes:** ATM, near expiry, expiry days skipped.
- **Walk-forward** (anchored, by year, in sample only):
  - index families -Rs 73k over 2022-2025;
  - HEAVY (only Oct-Dec 2024 to train) -Rs 20.7k in 2025.

## Files

- **Code** (`research/hunt/h6/`): `common.py`, `signals.py`, `heavy.py`, `stocks.py`, `run.py` (engine, one data
  pass, 3 executions) and `analyze.py` (pairs, baselines, BH/Holm, SPA, walk-forward, holdout).
- **Outputs** (`scratchpad/hunt/h6/`): `insample.txt`, `holdout.txt` and `trades_*.parquet`.

**Caveat:** stock minute data starts in Oct 2024, so HEAVY has only about 10 months in sample.
