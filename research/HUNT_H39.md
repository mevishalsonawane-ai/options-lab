# HUNT h39: do the heavyweights' STOCK OPTIONS lead the index enough to pay an index option buyer?

Written 8 Oct 2026. Option BUYING only. 1 lot, 1-ITM, nearest expiry. Rs 1 lakh capital.

- Plan, written before any data or P&L: `research/hunt/h39/PREREG.md`. It has 3 recorded amendments.
- Code: `research/hunt/h39/`
  - `fetch_stockopt.py`: the Dhan fetcher.
  - `proxy_diag.py`: Stage A, a stock-futures proxy.
  - `stage_c_leadlag.py`: stock-option features and the lead-lag gate.
  - `liqfilter.py`: the features as filters on Liquidity 15+5.
- Logs: `scratchpad/hunt/h39/*.log`. Data: `scratchpad/hunt/h39/data/`.

## Verdict

**NO. The heavyweights' option activity does not lead the index by enough to pay an index option buyer.
Rs 5,000/day: NO. h39 adds nothing to the Liquidity 15+5 plan.**

1. **We fetched the data ourselves. It is real, and complete except for RELIANCE.**
   - Stock options for 11 index heavyweights, 1-minute, Jan 2021 to Oct 2026.
   - Each stock covers the nearest monthly expiry, ATM-3..ATM+3, both calls and puts.
   - Every bar has close, volume, OI, IV, strike and the stock price.
   - The data comes to 75.5 M rows and 386 MB.
   - RELIANCE is patchy on Dhan's side: 11 windows came back empty and many others sparse. It holds 8.5 of
     NIFTY's 56.5 heavyweight weight.
2. **Heavyweight option activity moves WITH the index, not before it.**
   - In the same minute, the synthetic forward, IV and OI changes are strongly tied to the index move.
     The ICs are -0.10 to -0.35, with |t| up to 200.
   - From 2 minutes ahead and later, every IC is at most 0.012 in size.
3. **A few leads are statistically real but far too small to trade.**
   - BANKNIFTY: IV-skew jumps (IVJ5) and 5-minute synthetic-forward moves (SYNF5) lead by 2-3 minutes.
     The IC is +0.011 to +0.012, with t of 3.5-5.0.
   - NIFTY: the 1-minute synthetic-forward jump (SYNF1) is followed by a small reversal. The IC is -0.025 at
     1 minute (t -14.6), -0.012 at 2 minutes (t -5.0) and -0.009 at 3 minutes (t -3.1).
   - The trouble is size. At the most extreme 1% of minutes, the index moves in the signal's direction by only
     at most 58% of what a 1-ITM option round trip costs (BANKNIFTY), and at most 46% (NIFTY).
     - A BANKNIFTY round trip costs about 10 index points: 6 premium points at delta 0.6.
     - A NIFTY round trip costs about 2.6 index points: 1.6 premium points.
4. **Buying the actual index option on these signals loses money. Every signal loses at every holding time.**
   - The trade buys the 1-ITM nearest option at the next minute's open and holds 5, 10 or 15 minutes, after the
     real spread, app fills and charges.
   - The best signal is call-vs-put volume imbalance (VIMB5).
     - BANKNIFTY: -4.0 premium points per trade, against -6.1 for random minutes.
     - NIFTY: -0.9 points, against -1.6 for random minutes.
   - So VIMB5 leans the right way, but it is still a loser. This is the same "real but too small" story as h26's
     VSPIKE and h29's synthetic forward.
5. **No feature passed the pre-registered gate.**
   - The gate required |t| ≥ 3 at 2 minutes or more, AND an extreme-minute move at least equal to the round-trip cost.
   - So, as pre-registered, the standalone trigger P&L (premium-point exits, Liquidity-arm exits, ladder, time stops)
     was NOT run, and the holdout was not touched for any trigger.
6. **As filters on Liquidity 15+5, nothing passed the adoption rule.** 0 of 12 filters were adopted.
   - The best was "skip when the 1-minute synthetic-forward jump opposes the trade by 2σ" (SYNF1:2).
     - +Rs 8/day. It skipped 15 of 1,462 trades.
     - Raw p 0.021. It was positive in both halves and at 1.5× spread.
     - It failed only on multiple testing: BH q 0.26.
     - It is a candidate to log on paper at most, the same status as h26's BU15.
   - Skipping when OI build-up, IV jumps or the synthetic forward oppose the trade by 1σ HURT, by Rs -22 to -42/day.
     The trades it skipped were winners, averaging +Rs 144 to +418.
7. **A stock-futures proxy, run first, shows the same thing.** This is Stage A, 48 sessions in Jul-Oct 2026.
   - Heavyweight futures returns, basis changes and OI build-up are tied to the index in the same minute (IC 0.6-0.9).
   - From 2 minutes ahead, every |t| is below 2.5.
   - Even the index's own future does not lead the index by a tradeable amount.

**Rs/day at 1 lot / lots for Rs 5,000/day:**
- There is no h39 rule to size. Every candidate signal loses net per trade, so no number of lots reaches Rs 5,000/day.
- The plan stays Liquidity 15+5 (h10/h13/h14/h24). Over the same pre-holdout period, BN+NIFTY at 1 lot each made
  Rs 25/day net and Rs 145/day gross under h26's cost model.

**Honest count:** 6 lead-lag features × 2 indices (12 tests) are diagnostics only. 12 filters ran through BH. 0 trigger variants
were run, because the gate blocked them. The holdout was not used.

## 1. What data we got, and how

Dhan v2 `POST /charts/rollingoption` with these settings:
- `instrument OPTSTK`, `expiryFlag MONTH`, `expiryCode 1` (the nearest monthly on each date).
- `strike ATM-3..ATM+3`, `drvOptionType CALL/PUT`, `interval 1`.
- 30-day windows from 2021-01-01, newest first.

The fetch had a false start:
- The first token had expired (JWT exp 2026-10-07 04:38 UTC).
  - Both a stock-option request and an index-option control returned HTTP 401 DH-901: "Client ID or user generated
    access token is invalid or expired."
  - Stage A was built in the meantime from what was on disk.
- The coordinator then supplied a fresh token, and the endpoint served stock options normally.
  - Each 30-day window returns about 7,500 one-minute bars per strike and side.
  - HDFCBANK is served back to at least Sep 2020.

The fetch itself:
- About 11 × 14 × 71 = 10.9k calls at ≤ 3 requests/s, with 4-6 concurrent threads, because each call takes about 4.6 s.
- During market hours Dhan returned bursts of HTTP 504 (gateway timeout) and one 429. These cost one restart; the
  run resumed from the per-window log.

Storage:
- Responses were parsed in memory. Nothing raw was kept.
- Files are zstd parquet, one per stock-year: `scratchpad/hunt/h39/data/<SYM>/<YEAR>.parquet`.
- Columns:
  - ts: int32 epoch minutes.
  - off: -3..3.
  - side: ±1.
  - c, strike, spot, v, oi: float32.
  - iv: int16 = IV × 20.
- Open/high/low were not fetched, to save disk. The features use closes, and every exit is on the INDEX option.

| stock | first .. last bar | trading days | rows | size | note |
|---|---|---|---|---|---|
| HDFCBANK | 2021-01-01 .. 2026-10-08 | 1416 | 7.4 M | 41 MB | |
| ICICIBANK | 2021-01-01 .. 2026-10-08 | 1415 | 7.4 M | 38 MB | |
| SBIN | 2021-01-01 .. 2026-10-08 | 1415 | 7.4 M | 39 MB | |
| KOTAKBANK | 2021-01-01 .. 2026-10-08 | 1415 | 7.3 M | 38 MB | |
| AXISBANK | 2021-01-01 .. 2026-10-08 | 1415 | 7.4 M | 36 MB | |
| RELIANCE | 2021-02-01 .. 2026-10-08 | 1194 | 2.9 M | 16 MB | Dhan served 11 windows empty and others sparse (Dhan-side gaps) |
| INFY | 2021-01-01 .. 2026-10-08 | 1415 | 7.4 M | 39 MB | |
| TCS | 2021-01-01 .. 2026-10-08 | 1414 | 7.0 M | 38 MB | |
| BHARTIARTL | 2021-01-01 .. 2026-10-08 | 1414 | 7.2 M | 35 MB | |
| ITC | 2021-01-01 .. 2026-10-08 | 1415 | 7.1 M | 32 MB | |
| LT | 2021-01-01 .. 2026-10-08 | 1414 | 7.0 M | 34 MB | |
| **total** | | | **75.5 M** | **386 MB** | |

The credentials were read at runtime by `scratchpad/dhan/fetch.py`'s `Creds`. They were never printed or logged, and
every error passes through `redact()`.

## 2. Features

All features are built per minute, from the stock's ATM±3 nearest monthly options.
- **PFLOW5:** call premium×volume minus put premium×volume, over total, across the last 5 minutes.
- **VIMB5:** call volume minus put volume, over total, across the last 5 minutes.
- **OIB15:** 15-minute put OI build minus call OI build, over total OI. Put writing counts as bullish (h26 convention).
- **IVJ5:** 5-minute change in ATM call IV minus ATM put IV, i.e. a skew jump.
- **SYNF1 / SYNF5:** 1- and 5-minute change of the synthetic forward premium.
  - The forward is the median over ATM-1..ATM+1 of K + C - P.
  - The premium is the forward vs the stock price, in bps.

Each feature is z-scored against the previous 20 days at the same minute of day (median/MAD), so nothing looks ahead.
z is clipped to ±5 (amendment 2: a MAD of 0 had produced infinities). Features are then averaged with h26's static
index weights:
- BANKNIFTY: HDFCBANK 27, ICICIBANK 24.5, SBIN 9, KOTAKBANK 8.5, AXISBANK 8.5.
- NIFTY: HDFCBANK 13, ICICIBANK 9, RELIANCE 8.5, INFY 5, BHARTIARTL 4.5, LT 4, ITC 3.5, TCS 3, SBIN 3, AXISBANK 3.

## 3. Lead-lag, pre-holdout (Jan 2021 - Sep 2025)

IC = the correlation of the feature at minute t with the index return over the next k minutes. k = 0 means the same
minute, t-1 → t. The t-stat is deflated for overlap (n/k).

**BANKNIFTY** (IC, t)

| feat | k0 | t0 | k1 | t1 | k2 | t2 | k3 | t3 | k5 | t5 | k10 | t10 | k15 | t15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PFLOW5 | 0.008 | 4.781 | -0.001 | -0.693 | -0.001 | -0.373 | -0.001 | -0.188 | 0.001 | 0.251 | 0.004 | 0.700 | 0.005 | 0.720 |
| VIMB5 | 0.002 | 1.312 | -0.002 | -1.119 | -0.001 | -0.518 | -0.000 | -0.160 | 0.001 | 0.162 | 0.002 | 0.415 | 0.004 | 0.533 |
| OIB15 | -0.107 | -61.513 | 0.002 | 1.398 | 0.004 | 1.533 | 0.004 | 1.218 | 0.003 | 0.839 | 0.001 | 0.152 | 0.001 | 0.186 |
| IVJ5 | -0.224 | -131.426 | 0.006 | 3.276 | 0.012 | 5.037 | 0.011 | 3.875 | 0.008 | 2.100 | 0.010 | 1.797 | 0.010 | 1.564 |
| SYNF1 | -0.346 | -202.971 | -0.011 | -6.729 | -0.000 | -0.132 | 0.002 | 0.740 | 0.002 | 0.482 | 0.002 | 0.460 | 0.003 | 0.394 |
| SYNF5 | -0.278 | -162.942 | 0.002 | 1.181 | 0.011 | 4.453 | 0.010 | 3.455 | 0.008 | 2.034 | 0.010 | 1.846 | 0.011 | 1.601 |

**NIFTY** (IC, t)

| feat | k0 | t0 | k1 | t1 | k2 | t2 | k3 | t3 | k5 | t5 | k10 | t10 | k15 | t15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PFLOW5 | 0.005 | 3.178 | -0.002 | -1.313 | -0.002 | -0.888 | -0.002 | -0.623 | -0.001 | -0.205 | 0.001 | 0.116 | 0.001 | 0.173 |
| VIMB5 | -0.001 | -0.704 | -0.004 | -2.345 | -0.004 | -1.591 | -0.003 | -1.111 | -0.003 | -0.760 | -0.003 | -0.533 | -0.002 | -0.371 |
| OIB15 | -0.103 | -59.044 | -0.001 | -0.380 | 0.001 | 0.290 | 0.000 | 0.063 | 0.000 | 0.038 | -0.001 | -0.266 | 0.001 | 0.175 |
| IVJ5 | -0.204 | -119.657 | -0.013 | -7.517 | -0.005 | -2.116 | -0.004 | -1.241 | -0.004 | -1.170 | 0.000 | 0.002 | 0.003 | 0.479 |
| SYNF1 | -0.311 | -182.481 | -0.025 | -14.585 | -0.012 | -5.012 | -0.009 | -3.133 | -0.009 | -2.295 | -0.004 | -0.822 | -0.004 | -0.590 |
| SYNF5 | -0.262 | -153.943 | -0.018 | -10.386 | -0.008 | -3.351 | -0.007 | -2.381 | -0.007 | -1.850 | -0.000 | -0.035 | 0.003 | 0.446 |

**Extreme minutes:** the top 1% |feature| minutes, i.e. about 3,800 per feature.

**BANKNIFTY**: index points in the signal direction, top 1% minutes

| feat | n | pts1 | pts5 | pts10 | pts15 | breakeven_pts | best/BE |
|---|---|---|---|---|---|---|---|
| PFLOW5 | 3863 | -0.36 | 0.80 | 0.82 | -0.26 | 10.06 | 0.08 |
| VIMB5 | 3863 | 0.37 | 3.28 | 3.79 | 4.56 | 10.06 | 0.45 |
| OIB15 | 3729 | 1.04 | 2.57 | 4.76 | 5.84 | 10.06 | 0.58 |
| IVJ5 | 3873 | -0.07 | -0.44 | 0.22 | 0.38 | 10.06 | 0.04 |
| SYNF1 | 3871 | -0.64 | 0.17 | -0.11 | 1.38 | 10.06 | 0.14 |
| SYNF5 | 3873 | 0.10 | -0.16 | 2.10 | 4.05 | 10.06 | 0.40 |

**BANKNIFTY**: 1-ITM nearest BANKNIFTY option, premium pts NET of 6.04 round-trip cost (next-minute open -> close t+k):

| feat | n | net5 | net10 | net15 |
|---|---|---|---|---|
| PFLOW5 | 3559 | -5.83 | -6.54 | -6.87 |
| VIMB5 | 3527 | -4.01 | -4.28 | -4.27 |
| OIB15 | 3298 | -5.81 | -5.63 | -5.60 |
| IVJ5 | 3455 | -6.92 | -7.11 | -7.58 |
| SYNF1 | 3353 | -5.88 | -6.60 | -6.17 |
| SYNF5 | 3289 | -6.56 | -6.16 | -6.31 |
| baseline (every 15th minute) | 23114 | -6.05 | -6.09 | -6.56 |

**NIFTY**: index points in the signal direction, top 1% minutes

| feat | n | pts1 | pts5 | pts10 | pts15 | breakeven_pts | best/BE |
|---|---|---|---|---|---|---|---|
| PFLOW5 | 3863 | -0.29 | -0.01 | -0.25 | -0.55 | 2.64 | -0.00 |
| VIMB5 | 3863 | 0.15 | 1.21 | 1.18 | 0.85 | 2.64 | 0.46 |
| OIB15 | 3729 | 0.14 | -0.36 | -0.25 | 0.63 | 2.64 | 0.24 |
| IVJ5 | 3873 | -0.06 | 0.03 | 0.12 | 0.89 | 2.64 | 0.34 |
| SYNF1 | 3872 | -0.46 | -0.30 | -0.19 | -0.37 | 2.64 | -0.07 |
| SYNF5 | 3873 | -0.38 | -0.95 | -0.48 | -0.53 | 2.64 | -0.18 |

**NIFTY**: 1-ITM nearest NIFTY option, premium pts NET of 1.58 round-trip cost (next-minute open -> close t+k):

| feat | n | net5 | net10 | net15 |
|---|---|---|---|---|
| PFLOW5 | 3862 | -1.47 | -1.48 | -1.53 |
| VIMB5 | 3858 | -0.92 | -0.89 | -1.15 |
| OIB15 | 3711 | -1.71 | -1.57 | -1.20 |
| IVJ5 | 3870 | -1.64 | -2.00 | -1.76 |
| SYNF1 | 3844 | -1.51 | -1.65 | -1.86 |
| SYNF5 | 3843 | -1.99 | -2.10 | -2.35 |
| baseline (every 15th minute) | 26072 | -1.55 | -1.62 | -1.82 |

The **real option test** in the tables above:
- On those same minutes, buy the 1-ITM nearest index option on the signal side at the next minute's open.
- Mark it at the close 5, 10 or 15 minutes later. Results are in premium points, net of the round-trip cost.
- The "baseline" row does the same at every 15th minute, with no signal.

Reading the results:
- Every signal is net negative.
- VIMB5 loses about 2 points less than random on BANKNIFTY, and about 0.7 less on NIFTY. That is consistent, but
  much less than the cost.

## 4. As filters on Liquidity 15+5 (pre-holdout, BANKNIFTY + NIFTY, 1 lot)

These are h4's Liquidity trades with h26's cost model: app charges plus h24's real half-spread on entry and exit.
"Skip" means skip the trade when the weighted feature, signed toward the trade, is ≤ -1 or ≤ -2.

Liquidity trades pre-holdout (BN+NIFTY): 1542 {'BANKNIFTY': 750, 'NIFTY': 792}; unfiltered net Rs/day 25, gross 145.

| filter | n | skipped | base Rs/day | Δ net Rs/day | Δ at 1.5× spread | Δ gross | avg skipped trade | p | BH q | Δ <2024 | Δ 2024-Sep25 | adopt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PFLOW5:1 | 1462 | 4 | 35.634 | 0.822 | 0.857 | 0.539 | -240.907 | 0.453 | 1.000 | -1.148 | 4.167 | False |
| PFLOW5:2 | 1462 | 0 | 35.634 | -0.000 | -0.000 | -0.000 | NaN | 1.000 | 1.000 | -0.000 | -0.000 | False |
| VIMB5:1 | 1462 | 3 | 35.634 | 0.861 | 0.920 | 0.534 | -336.184 | 0.387 | 1.000 | 1.324 | 0.078 | False |
| VIMB5:2 | 1462 | 0 | 35.634 | -0.000 | -0.000 | -0.000 | NaN | 1.000 | 1.000 | -0.000 | -0.000 | False |
| OIB15:1 | 1462 | 319 | 35.634 | -39.183 | -35.153 | -66.109 | 143.957 | 0.912 | 1.000 | -51.215 | -18.959 | False |
| OIB15:2 | 1462 | 136 | 35.634 | -30.337 | -28.482 | -42.308 | 261.438 | 0.941 | 1.000 | -30.025 | -31.007 | False |
| IVJ5:1 | 1462 | 120 | 35.634 | -21.782 | -20.289 | -31.837 | 212.741 | 0.883 | 1.000 | -5.933 | -48.761 | False |
| IVJ5:2 | 1462 | 24 | 35.634 | -1.326 | -1.072 | -3.179 | 64.754 | 0.589 | 1.000 | -0.257 | -3.146 | False |
| SYNF1:1 | 1462 | 118 | 35.634 | -42.133 | -40.412 | -52.918 | 418.476 | 0.988 | 1.000 | -25.992 | -69.701 | False |
| SYNF1:2 | 1462 | 15 | 35.634 | 8.378 | 8.592 | 7.036 | -654.635 | 0.021 | 0.258 | 4.269 | 15.387 | False |
| SYNF5:1 | 1462 | 115 | 35.634 | -32.312 | -30.727 | -42.494 | 329.304 | 0.965 | 1.000 | -28.307 | -39.253 | False |
| SYNF5:2 | 1462 | 29 | 35.634 | -25.623 | -25.212 | -28.244 | 1035.534 | 0.992 | 1.000 | -11.051 | -50.454 | False |

0 of 12 filters were adopted. The rule needed BH q ≤ 0.10, a positive result in both halves, and a positive result at
1.5× spread. So the holdout was not run.

## 5. Stage A: the stock-futures proxy (Jul 29 - Oct 6 2026, inside the holdout, descriptive only)

Stage A uses the nearest futures of the same heavyweights, the stock minutes and the index minutes.

Same-minute ICs:
- Stock returns: 0.87 (BANKNIFTY) / 0.90 (NIFTY).
- Futures returns: 0.59 / 0.61.
- Basis changes: -0.39 / -0.41.

At 2-15 minutes every |t| is below 2.5, for the basis, OI build-up, signed volume share, and even the index's own
future. On the extreme minutes, the best ratio of move to the cost of a round trip was 0.65 (BANKNIFTY BU5). For
NIFTY FVI5 it was 1.14, but that rests on 159 minutes with t < 2. Full tables: `scratchpad/hunt/h39/proxy_diag.log`.

## 6. Why this fails, in one paragraph

What the heavyweights' options do is already in the index in the same minute. The index is computed from those same
stocks, and index-option and futures arbitrage closes any gap within seconds. The small lead that is left lasts 1-3
minutes. It is either a reversal of noisy option prints (NIFTY SYNF1) or a slight catch-up (BANKNIFTY IVJ5/SYNF5).
Either way it is worth a fraction of an index point to a few points. A 1-ITM index option costs about 10 BANKNIFTY
points or 2.6 NIFTY points to get in and out of, after the real spread and the app's charges. This agrees with h26
(stock volume moves in the same minute as the index) and h29 (index options lead by too little).

## 7. Files

- `research/hunt/h39/PREREG.md`: the plan, plus amendments 1-3.
- `research/hunt/h39/fetch_stockopt.py`:
  - The fetcher. It is resumable, stops on 401/403, backs off on 504 bursts and has a 700 MB cap.
  - `--plan` shows the request count; `--dry-run` runs against a mock server.
- `research/hunt/h39/proxy_diag.py`: Stage A.
- `research/hunt/h39/stage_c_leadlag.py`: the features, lead-lag, extreme-minute moves, the option test and the gate.
- `research/hunt/h39/liqfilter.py`: the 12 filters on Liquidity 15+5. It reuses h26's trade and cost loader.
- Logs in `scratchpad/hunt/h39/`:
  - `fetch.log`, `proxy_diag.log`
  - `stage_c_BN_v1.log` (the run before the overflow fix), `stage_c_BN.log`, `stage_c_NF.log`
  - `liqfilter_pre.log`, `liqfilter_pre.csv`, `proxy_ic.csv`, `proxy_tail.csv`.
