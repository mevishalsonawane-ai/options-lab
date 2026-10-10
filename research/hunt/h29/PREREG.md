# h29 pre-registration: do option PRICES tell a buyer when options are cheap and which side to buy?

Written 8 Oct 2026, after building the pricing panel (`build.py`: forward, IV, skew; no outcomes, no P&L) and before
any forward return, option P&L or filter result was computed. Option BUYING only. 1 lot. Holdout 2025-10-01 .. end is
locked and used once, only for variants that pass the gates below.

## Features (features.py; everything at minute m uses bars that closed by m)

- `F` synthetic forward = median of K + C - P over strikes within ±2 steps of spot (legs traded <= 5 min ago).
  `basis` = (F/S - 1) in bps.
- `iv` ATM Black-76 IV from the ATM straddle, TRADING time (remaining session minutes / (375*252)).
- `rr` = IV(OTM call at ~F + straddle/2) - IV(OTM put at ~F - straddle/2), about 30-35 delta. Rises when calls get bid
  relative to puts.
- `ivm - iv`: monthly minus weekly ATM IV (term structure) where both exist.
- `dbz` = 5-min change of basis / sd of 5-min basis changes over the prior 20 trading days (same index).
- `drz` = 15-min change of rr / sd of 15-min rr changes over the prior 20 trading days.
- `har`: HAR-RV forecast of today's realised vol (daily RV from 5-min returns; RV_d ~ RV_{d-1}, mean 5, mean 22),
  pooled across indices, refit each calendar year on all earlier days (2020-21 use the 2020-21 fit: warm-up).
- `cheap1` = iv / har.  `cheap2` = iv / realised vol of today so far (1-min returns from 09:16, >= 30 min).
- `rank1` = percentile of cheap1 at a check time within the same index's prior 60 trading days at that check time.

## Descriptive (no selection, not counted as variants)

D1 intraday ATM IV path (open / lunch crush or rise). D2 implied vs realised move to 15:10 by cheap1 quintile.
D3 information coefficients of dbz / drz / basis level / rr level with the next 5/15/30-min index return.

## Test 1: directional buys from skew shifts / synthetic-forward moves (standalone, obuy Lab)

- Scan every minute 09:30-14:30, non-expiry days, all 5 indices. 1-ITM nearest expiry, app fills and dated costs.
- Signals: S1 `basis`: dbz >= k -> call, <= -k -> put. S2 `skew`: drz >= k -> call, <= -k -> put.
  S3 `both`: dbz and drz both beyond k-1 with the same sign.  k in {2, 3}.
- A signal is the minute the z-score CROSSES the threshold (was inside it the minute before), at most 10
  candidates per index-day. One position at a time per index, at most 3 a day per index.
- Exit menu (fixed now, same for all tests):
  - E1 Liquidity arm: -15% premium stop; out after 20 min unless +5%; 15:10 square-off.
  - E2 -15% stop, +30% target, the arm's 20-min time stop.
  - E3 -15% stop + profit-lock ladder (obuy LADDER, R = 15% of entry), 15:10.
  - E4 time exit: out after 30 min regardless, -30% catastrophe stop.
- 3 x 2 x 4 = **24 variants**.

## Test 2a: buy only when cheap, simple direction (standalone, obuy Lab)

- Check at 10:00, 11:30, 13:00 (bar closes), non-expiry days. Cheap if rank1 <= q, q in {0.10, 0.20}.
- Direction: D1 sign of the last 30-min index move; D2 sign of the move since the open.
- Same strike, positions and exit menu. 2 x 2 x 4 = **16 variants**.

## Test 2b: pricing features as filters on Liquidity 15+5 (h4 `trades_real`, liq_bnfin + liq_ext, 1 lot)

Features at the signal minute. Tercile edges per index from pre-holdout trades.
- F1 cheap1, F2 cheap2, F3 iv level: rules "keep lowest tercile" and "drop highest tercile".
- F4 side*dbz, F5 side*drz, F6 side*rr level: rules "keep highest tercile" and "drop lowest tercile".
- 6 x 2 = **12 variants**.
- Adoption: net per trade (real spread) above the unfiltered trades in BOTH pre-holdout halves (to 2023-12, 2024-01 to
  2025-09) AND permutation p (random subsets of the same size, same period) with BH q < 0.05 over the 12.

## Test 3: which strike gives the best convexity per rupee at the Liquidity signal (extends h13 / h16)

Liquidity signals (h13's: BN, FIN, MIDCP), arm exits, app fills; packs for ITM1 / ATM / OTM1 nearest.
- R0 ITM1 always (incumbent). R1 OTM1 when cheap1 is in its lowest tercile, else ITM1.
  R2 the strike with the lowest own IV among ITM1 / ATM / OTM1 at the signal.
- Metric: net return on premium per trade, and Rs/trade at an equal Rs 33,000 premium budget (at least 1 lot).
- Adoption: higher net return on premium than R0 in both halves and paired-bootstrap p < 0.05 (BH over R1, R2).
- **3 variants** (R0 counted).

Total declared: 24 + 16 + 12 + 3 = **55 variants**.

## Costs

App fills (±5 bps; stops -10 bps) and dated charges, PLUS the real half-spread from h24 minus the 5 bps already in the
app fill, on entry and exit: BN 0.16%, NIFTY 0.16%, SENSEX 0.16% (assumed = NIFTY), MIDCP 0.21%, FIN 0.42%.
Stress: 1.5x the half-spread. Gross is also reported.

## Gates for the holdout (standalone tests 1, 2a)

All must hold pre-holdout: (a) net with real spread > 0; (b) beats same-exit random entries, BH q < 0.05 over the
40 standalone variants; (c) Hansen SPA p < 0.10 over the 40 (net); (d) anchored yearly walk-forward net > 0 and
positive in more than half the test years. At most the single best (net) variant per test goes to the holdout.
For 2b and 3, the adoption rules above. Holdout: one run, reported gross / net / net 1.5x spread, Rs/day at 1 lot,
lots for Rs 5,000/day, max drawdown, worst day / month, bootstrap P(losing month).
