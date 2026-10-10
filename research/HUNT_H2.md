# HUNT h2: long-only intraday trading in F&O stocks (cash/MIS), Rs 5,000/day?

## Verdict

**NO.** Long-only intraday stock trading does not realistically make Rs 5,000/day.

- The best simple rule does have a real edge **before costs**. On the locked holdout it beat random entries
  (p = 0.003), and it made money in both periods.
- **After costs** the edge is too thin. The Rs-per-day figure stops rising at about Rs 330/day, at Rs 20 lakh per
  trade. At larger sizes the extra slippage (market impact) uses up the edge.
- Making Rs 5,000/day **gross** would need about Rs 42 lakh per trade. That is about Rs 1.25 crore of exposure and
  about Rs 25 lakh of MIS margin. At that size the **net** result on the holdout is about zero, with drawdowns of about
  Rs 6-7 lakh.
- After 372 variants, none of them survives the multiple-testing checks on net P&L. White's Reality Check (p 0.16),
  Hansen's SPA (p 0.91) and BH (min q = 1.0) all find nothing.

All trades are long only (buy, then sell to exit), as the Boss asked.

## The single best simple rule (chosen on in-sample data only, before the holdout was opened)

"Gap-up opening-range breakout on the hottest stocks"

1. At 09:30, look at the F&O stocks that opened at least 2% above yesterday's close.
2. Rank them by relative volume: volume from 09:15 to 09:29 divided by the average of the same window over the last
   10 days. Keep the top 3.
3. For each of the 3, mark the 15-minute range (09:15-09:29 high and low). Buy at the next minute's open after the
   first 1-minute close above the range high. Only take entries up to about 11:00.
4. Put the stop at the middle of the 15-minute range. Put the target at range high + 2 x (range high - stop).
   Otherwise sell at 15:15.
5. Use equal rupees per trade. There are at most 3 positions at once, and on average 0.5 trades a day (many days
   have none).

## Results (Rs; Rs 5 lakh notional per trade; gross = bar prices with no charges, net = slippage + charges)

| period | trades | gross bps/trade | net bps/trade | gross Rs/day | net Rs/day | net Sharpe | net max DD | worst day (net) | worst month (net) | losing months | P(losing month), bootstrap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| In-sample 2024-10-07..2025-09-30 (245 days) | 145 | 39.5 | 22.1 | 1,167 | 652 | 1.29 | 62k | -22k (2025-02-27) | -21k (2025-06) | 5/12 | 37% |
| **HOLDOUT 2025-10-01..2026-10-05 (248 days, run once)** | 119 | 25.1 | 9.2 | **599** | **218** | 0.63 | 59k | -24k (2026-03-20) | -45k (2026-03) | 5/13 | 43% |

- **Holdout by year (net):** 2025 Q4 +7k, 2026 to date +47k. Gross: +30k and +118k.
- **Random-entry baseline:** same days, minutes, trade count, stop % and target, but a random liquid stock. The rule's
  gross per trade beats it in-sample (39.5 vs 0.2 bps, p = 0.003) and on the holdout (25.1 vs -3.4 bps, p = 0.003).
  The stock selection (gap up plus high relative volume) carries the information.
- **Concentration:** take out the single best day and the holdout net falls from 54k to 28k.

### Sizing for Rs 5,000/day

The table below includes a size-dependent impact term of 10 bps x sqrt(order / average 5-minute traded value). The
MIS margin assumes 5x leverage on 3 concurrent positions.

| notional per trade | holdout gross/day | holdout net/day | in-sample net/day | holdout net max DD | MIS margin needed |
|---|---|---|---|---|---|
| 5 L | 599 | 141 | 535 | 61k | 3 L |
| 20 L | 2,404 | 330 | 1,757 | 2.6 L | 12 L |
| 50 L | 6,020 | -28 | 3,065 | 7.0 L | 30 L |
| 1 Cr | 12,045 | -2,048 | 3,066 | 16.5 L | 60 L |

- **Rs 5,000/day gross:** about Rs 42 L per trade, about Rs 25 L margin. Net at that size is about Rs 0/day.
- **Rs 5,000/day net:** not reachable at any size on either the holdout or the in-sample data.
- **Futures instead of cash:** stock futures charge STT of 0.02% of the sell value, rising to 0.05% from Apr 2026,
  against 0.025% for cash intraday. Futures also come in lumpy lots, so they do not fix the cost problem.

## What was tried (372 variants, all long-only, all on 2024-10-07..2025-09-30)

| family | idea | in-sample median net bps/trade | best net Sharpe |
|---|---|---|---|
| ORB (108) | opening-range breakout (5/15/30 min) on the top-K relative-volume stocks, with or without a gap filter, stop at range low or mid, exit at EOD or 2R | -12 | 1.29 (the pick) |
| Gap (24 + 36 capped) | gap-and-go long on gap-ups; long gap-fill on gap-downs whose first 5/15 min is green | +8 | 1.54 uncapped |
| RS top (72) | buy the top-K stocks by strength vs NIFTY at 09:30/09:45/10:15, hold to close | -21 | -0.36 |
| RS bottom (72 + 18) | buy the weakest stocks (catch-up) | -18 | 0.91 |
| VWAP pullback (18) | buy strong stocks on a touch of VWAP after 10:15 | -20 | -4.6 |
| 5-minute crash fade (16) | buy large caps after a 3-6 sigma 5-minute drop, hold 15-120 min | -16 | -1.8 |
| Peer-cluster rotation (8) | sector proxy: k-means clusters on in-sample returns (Dhan has no sector indices); buy the leaders of the strongest cluster | -4 | 1.03 |

Notes on these families:

- **Gap, uncapped:** its money depends on a few market-wide crash days. 2025-04-07 alone is about half of the
  in-sample P&L, and on such days it holds 160+ positions at once. This is why the selection rule requires at most
  10 positions open at once and drops the best day when it scores.
- **Momentum families:** ORB, RS top, VWAP and peer-cluster rotation are all slightly positive or flat gross, but they
  pay about 10-20 bps a round trip in costs. Large-cap reversal (the crash fade) has no gross edge at all.
- **Monthly anchored walk-forward inside the in-sample:** train on at least 3 months, then pick the best variant
  month by month. It loses 124k net from Jan to Sep 2025, so choosing among these families does not carry forward.
- **Overfitting statistics:**
  - BH over all 372 variants: min q = 1.0.
  - White's Reality Check / SPA on net P&L: p 0.16 / 0.91. On gross P&L: p 0.03 / 0.43.
  - DSR of the pick is about 0.
  - PBO is 0.17.
  - The pick's selection score (Sharpe without its best day) was 0.97, and the holdout net Sharpe was 0.63.

## Caveats

- **Survivorship:** the universe is today's 214 F&O names. Stocks that left F&O during 2024-26 are missing, which
  slightly flatters long-only results.
- **Short history:** stock minute data covers only 2024-10-07 to 2026-10-05, about 1 year in-sample and 1 year of
  holdout. There is no multi-year walk-forward. 2026-10-06 was a partial session and is dropped.
- **Slippage model:** the half-spread is 2/3/5/8 bps by 20-day median traded value (at least half a tick), doubled on
  stop fills. The impact term is a modelling assumption. Dhan minute data has no bid/ask.
- **Fills:** a signal on a bar's close fills at the next bar's open. Inside a bar the stop is checked before the
  target. Square-off is at the 15:15 open.

## Code

- `research/hunt/h2/build_cache.py`: dense minute arrays.
- `research/hunt/h2/lib.py`: the simulator, MIS charges and slippage.
- `research/hunt/h2/strategies.py`: the 372-variant grid.
- `research/hunt/h2/search.py`: in-sample search.
- `research/hunt/h2/final.py`: selection, walk-forward, BH/SPA/PBO/DSR, random baseline, the single holdout run and
  sizing.
- Outputs are in `scratchpad/hunt/h2/final.json` and `ins_variants.csv`. The minute cache was deleted after the run.
