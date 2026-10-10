# HUNT R9: the order-flow trader's 5 concepts (volume profile, absorption, delta divergence, auction theory, gamma) on Indian index data

Written 9 Oct 2026 for Boss. Frame: option BUYING only, Rs 1 lakh, 1 lot fixed, exits on the option's 1-minute wicks,
real costs including the h24 half-spreads, random/placebo twins, BH + White's reality check, and the locked holdout
(1 Oct 2025 - 6 Oct 2026) opened once (`HOLDOUT_OPENED` 2026-10-09 20:26 UTC) after `research/hunt/r9/PREREG.md`.

- Code: `research/hunt/r9/` (`PREREG.md`, `lib9.py`, `build.py`, `check.py`, `placebo_vp.py`, `auction.py`, `gex.py`,
  `gamma_test.py`, `flowproxy.py`, `filt9.py`, `holdout.py`).
- Outputs: `scratchpad/hunt/r9/` (13 MB). `A_placebo_*.csv`, `B_predict_*.csv`, `B_filter_*.csv`, `C_predict_*.csv`,
  `C_filter_*.csv`, `D_*.csv`, `gex.parquet`, `proxy_check.csv`, and the logs.
- Nothing was committed.

## Verdict in plain English

**None of the five concepts gives us a rule to put in the app. The video's claims do not hold on NIFTY, BANKNIFTY,
FINNIFTY or MIDCPNIFTY.** We ran 224 pre-registered design tests. The only ones that passed BH measure how BIG the next day is
(dealer-gamma signs vs range, realised vol and VWAP crosses), never whether it trends or chops. The main one reversed in
the holdout. Of 84 regime filters on the arms, none passed, and none made an arm better in both periods.

| # | concept (video's claim) | what we could test | result |
|---|---|---|---|
| 1 | **Volume profile**: HVNs are "real support", LVNs get sliced through | the prior day's POC, value-area edges, HVNs and LVNs vs the same levels shifted at random (R4's placebo test) | **No.** Price turns at the claimed levels no more often than at random shifted ones. 0 of 32 design tests pass BH (best q 0.26). In the holdout, POC/HVN did *worse* than placebo on FINNIFTY (-10 to -24 points). Real futures volume (48 days) gives scattered results in both directions. |
| 2 | **Absorption**: big passive buying absorbs sellers, then price reverses | proxy only: a heavy-volume, narrow-range 5-minute bar after a fall or a rise | **No** (proxy). NIFTY design +2-6 bp vs control, but q 0.29 and only 52 events. Holdout: +2 bp at 15 min, then negative. BANKNIFTY: nothing (60-min reversal -8 bp in the holdout). Even the best mean is below what it costs to trade. |
| 3 | **Delta divergence**: price up with negative delta means smart money is selling | proxy only: option-complex signed volume (calls bought minus puts bought, tick rule) disagreeing with a 0.15% 30-minute move | **No** (proxy). The reversal after a divergence is -2.6 to +0.6 bp in design and -1.7 to +1.8 bp in the holdout. The 48-day real-futures delta proxy shows BANKNIFTY +3 bp at 15 minutes (p 0.02) only, too small to trade. **The real test needs the app's shadow log (section 6).** |
| 4 | **Auction theory**: open outside value and accepted means a trend day; inside value means a range day | the prior day's value area (volume and TPO); open class; acceptance = two 30-minute closes outside | **No.** "Accepted outside" days do not trend more for the rest of the day (trend ratio 0.46-0.50 on both kinds). Continuation after acceptance is 47-52% (a coin flip). 0 of 56 design tests pass BH. **As a regime filter on the arms: 0 of 56 pass.** For Liquidity BANKNIFTY (our one real edge) the "trend-only" filter **hurt** it in design (-Rs 25 to -69/day). |
| 5 | **Gamma**: positive dealer gamma means a choppy day, negative means a trending day; check the zero-gamma level every morning | GEX from the NSE bhavcopy per-strike OI (prior evening), under four conventions, plus the zero-gamma flip | **No usable regime.** With the US convention, BANKNIFTY's sign **flipped between the periods**: "positive GEX" days were wider (range 1.32% vs 1.01%) in design and narrower (1.09% vs 1.37%) in the holdout. NIFTY: no effect. The one stable pattern is "Pros net long option gamma means a quieter next day". That is a volatility-size effect, not chop vs trend, and it did not help any arm. **As a filter: 0 of 28 pass.** |

**Why this is not surprising:**
- h37 already showed that Market Profile (TPO) levels trade like random entries.
- R4 showed that "special" levels attract price no more than shifted levels do.
- R7 and R8 showed that order flow explains the same seconds but forecasts little beyond a minute.
- The video's examples come from ES futures, which have a real centralised book and a real dealer short-gamma
  structure (customers sell calls and buy puts). Indian index options are dominated by prop/HNI/FII sellers and retail
  buyers, so "dealer gamma" has no agreed sign here. Our data shows that sign changing between the two periods.

**What to do:** keep Liquidity 15+5 BANKNIFTY unfiltered (HUNT_FINAL). Put nothing from R9 in the app as a filter.
The one useful action is to let the shadow log you already built run for 3-6 months, then run the tests in section 6.

## 1. Data and what exists

- **Index minutes have no volume.** So the long-history volume profile uses a proxy, **OPTV**: the summed volume of
  the nearest option series, ATM±10 strikes, CE+PE, for each minute. It is placed at the index minute's high-low range.
  Data: Dhan option minutes, NIFTY 2020-08 onwards, BANKNIFTY/FINNIFTY 2021-08, MIDCPNIFTY 2022.
- **Real futures minute volume (FUTV) exists only from 2026-07-29 to 2026-10-06.**
  - That is 48 sessions, all inside the holdout. It is the 2026-10 contract; Dhan returns 0 bars for expired futures
    (h38).
  - FINNIFTY has 11 sessions.
  - It is used to check the proxy and to repeat the tests for information only.
- **Proxy check** (`proxy_check.csv`, pre-registered criterion: POC agreement ≥ 50% and value-area overlap ≥ 0.6):

| index | FUTV days | minute rank-corr OPTV vs FUTV | prior-day POC within 2 bins: OPTV / TPO | value-area overlap: OPTV / TPO | proxy usable |
|---|---|---|---|---|---|
| NIFTY | 47 | 0.39 | 76% / 72% | 0.77 / 0.70 | yes |
| BANKNIFTY | 47 | 0.43 | 67% / 65% | 0.77 / 0.72 | yes |
| MIDCPNIFTY | 48 | 0.27 | 54% / 38% | 0.61 / 0.56 | yes (just) |
| FINNIFTY | 10 | 0.04 | 50% / 50% | 0.74 / 0.64 | 4 days only |

  The profile *shape* (POC, value area) from option volume matches the futures profile on 2 of 3 days. Minute-by-minute
  volume matches poorly, so treat OPTV as a reasonable stand-in for levels and a poor one for flow.

- **Bhavcopy** (h40): per-strike settle and OI for the nearest 3 expiries within ±8% of the future, 2020-2026, NSE
  indices only (no SENSEX). OI units were checked across the July 2024 format change. Participant-wise OI comes from
  h27/h40.
- **Arm trade lists** (no re-simulation):
  - ORB, ORB Fresh, ORB Sweep, Range Fade and Liquidity FINNIFTY are from `h19/trades.parquet`: app fills and charges,
    minus the real half-spread on both legs (BANKNIFTY 0.16%, FINNIFTY 0.42%).
  - Liquidity BANKNIFTY and MIDCPNIFTY are from `h24/trades24.parquet`, flat×1, κ 0.02.
  - Check: Liquidity BANKNIFTY makes **Rs +168/day** in the holdout here, the same as HUNT_FINAL's Rs 167.

## 2. Test A: are prior-day volume levels "real support"? (placebo test, R4's method)

- **Levels:** the prior day's OPTV profile with bins of 0.05% of price. The levels are:
  - POC;
  - the value-area edges (70% value area);
  - HVNs: local peaks of the smoothed profile, at or above its mean;
  - LVNs: local troughs between HVNs, at or below 0.6× the mean.
- **Placebo:** the same level set shifted by ±0.15-0.60% of price, 20 draws a day.
- **Event:** the first touch after 09:20.
- **Reversal:** price comes back d before going d through, within 60 minutes. d = 0.10% or 0.25%.
- **Statistic:** the claimed rate minus the placebo rate, matched on time of day (pp = percentage points). p is a
  one-sided day-cluster bootstrap.
- **Claim direction:** POC, value-area edges and HVNs should reverse **more** than placebo. LVNs should reverse
  **less**.

| family | index | d | design claimed vs placebo | diff pp | p | BH q | holdout claimed vs placebo | diff pp | p | 48 days real futures volume: diff pp (p) |
|---|---|---|---|---|---|---|---|---|---|---|
| HVN | NIFTY | 0.10% | 48.5 vs 47.6% | +1.0 | 0.28 | 0.85 | 51.2 vs 52.7% | -1.1 | 0.63 | +6.2 (0.30) |
| HVN | NIFTY | 0.25% | 51.2 vs 48.8% | +2.3 | 0.13 | 0.85 | 46.2 vs 57.5% | -11.4 | 0.96 | +2.5 (0.43) |
| HVN | BANKNIFTY | 0.10% | 48.2 vs 48.5% | -0.4 | 0.57 | 0.85 | 47.0 vs 48.0% | -1.0 | 0.62 | +23.4 (0.008) |
| HVN | BANKNIFTY | 0.25% | 48.4 vs 49.8% | -1.4 | 0.74 | 0.85 | 47.7 vs 50.8% | -3.6 | 0.78 | +6.3 (0.31) |
| HVN | FINNIFTY | 0.10% | 44.7 vs 45.8% | -1.1 | 0.73 | 0.85 | 37.7 vs 48.6% | -10.5 | 0.99 | - |
| HVN | MIDCPNIFTY | 0.10% | 41.3 vs 42.6% | -1.2 | 0.71 | 0.85 | 43.6 vs 45.3% | -1.7 | 0.67 | -9.9 (0.85) |
| POC | NIFTY | 0.10% | 47.3 vs 47.7% | -0.4 | 0.57 | 0.85 | 51.6 vs 50.4% | +1.6 | 0.39 | +9.8 (0.25) |
| POC | BANKNIFTY | 0.10% | 51.1 vs 47.9% | +3.2 | 0.10 | 0.85 | 48.7 vs 47.1% | +1.5 | 0.38 | +6.7 (0.28) |
| POC | BANKNIFTY | 0.25% | 50.5 vs 47.7% | +2.7 | 0.18 | 0.85 | 44.9 vs 49.9% | -5.4 | 0.81 | -3.4 (0.59) |
| POC | FINNIFTY | 0.25% | 45.4 vs 44.8% | +0.5 | 0.41 | 0.85 | 29.0 vs 52.5% | -24.0 | 1.00 | - |
| VA edges | NIFTY | 0.10% | 49.3 vs 48.6% | +0.7 | 0.35 | 0.85 | 37.2 vs 49.3% | -12.1 | 1.00 | +5.2 (0.30) |
| VA edges | BANKNIFTY | 0.10% | 48.1 vs 47.9% | +0.2 | 0.48 | 0.85 | 47.9 vs 48.7% | -1.0 | 0.65 | -17.2 (0.98) |
| VA edges | MIDCPNIFTY | 0.10% | 43.2 vs 44.2% | -1.2 | 0.72 | 0.85 | 46.3 vs 45.5% | +0.8 | 0.41 | +23.5 (0.017) |
| LVN (claim: fewer reversals) | BANKNIFTY | 0.10% | 37.3 vs 48.7% | -11.3 | 0.016 | 0.26 | 61.9 vs 48.0% | **+14.1 (wrong way)** | 0.86 | -4.2 (0.42) |
| LVN (claim: fewer reversals) | FINNIFTY | 0.10% | 36.0 vs 48.4% | -12.6 | 0.008 | 0.26 | 65.0 vs 46.1% | **+17.7 (wrong way)** | 0.90 | - |

(All 32 design rows, all holdout rows and the FUTV repeats are in `A_placebo_design.csv`, `A_placebo_hold.csv` and
`A_placebo_fut.csv`. The 18 rows not shown also fail.)

- 0 of 32 design tests pass BH. The two LVN "hits" (BANKNIFTY, FINNIFTY, d 0.10%) come from 75-89 touches and
  **reversed sign in the holdout**.
- On real futures volume (48 days, about 20-45 touches per test), a few cells look good: BANKNIFTY HVN +23 pp, NIFTY
  VA edge at 0.25% +25 pp, MIDCP VA edge +23 pp. Just as many look bad: BANKNIFTY VA edge -17 pp, MIDCP POC -31 pp.
  With 24 cells and 6-46 touches each, this is what noise looks like.
- **Not repeated here (h37):** POC/VA levels as trades, and "open outside value and go". They lost after costs.

## 3. Test B: auction regime (prior-day value area) - predictive, then as a filter on the arms

### 3a. Does "accepted outside value" predict a trending rest of day?

Rest of day is 10:15 → 15:15.
- **ACC:** both 30-minute closes (09:45 and 10:15) are outside the prior value area on the same side.
- **IN2:** both are inside.
- **TR:** |move| / range. 1 means a clean trend; low means a range.
- **X:** crosses of the VWAP proxy.
- **Continuation:** the share of ACC days that finish on the acceptance side.

| index | VA | period | TR ACC vs IN2 | VWAP crosses ACC vs IN2 | zigzag 0.2% ACC vs IN2 | continuation on ACC days | best p (of 7) | BH q |
|---|---|---|---|---|---|---|---|---|
| NIFTY | volume | design | 0.49 vs 0.49 | 4.5 vs 4.9 | 0.48 vs 0.28 | 52.4% | 0.038 | 0.53 |
| NIFTY | volume | holdout | 0.50 vs 0.42 | 4.0 vs 5.1 | 0.10 vs 0.00 | 50.3% | 0.048 | - |
| BANKNIFTY | volume | design | 0.46 vs 0.48 | 5.5 vs 5.8 | 1.31 vs 1.49 | 47.4% | 0.20 | 0.69 |
| BANKNIFTY | volume | holdout | 0.46 vs 0.47 | 4.5 vs 5.3 | 0.42 vs 0.00 | 53.3% | 0.12 | - |
| FINNIFTY | volume | design | 0.47 vs 0.45 | 5.3 vs 6.0 | 0.91 vs 0.62 | 50.7% | 0.020 | 0.47 |
| MIDCPNIFTY | volume | design | 0.50 vs 0.50 | 4.7 vs 5.7 | 1.05 vs 0.34 | 49.6% | 0.025 | 0.47 |
| NIFTY | TPO | design | 0.49 vs 0.49 | 4.4 vs 5.0 | 0.46 vs 0.27 | 51.0% | 0.013 | 0.47 |
| BANKNIFTY | TPO | design | 0.46 vs 0.46 | 5.4 vs 6.1 | 1.40 vs 1.46 | 48.4% | 0.085 | 0.61 |

- **0 of 56 design tests pass BH** (lowest q 0.47).
- The only consistent hint is slightly **fewer VWAP crosses** on accepted days (about 0.5 fewer a day).
- The **trend ratio does not differ**, and **continuation is a coin flip** (47-52%). "Accepted above value" does not
  tell us the day will keep going up.
- "Open outside vs inside value" gives the same nothing (`B_predict_*.csv`).

### 3b. As a regime filter on the arms

**The filter rules:**
- **TREND arms** (ORB, ORB Fresh, Liquidity BN/FIN/MIDCP):
  - F1: keep a trade only if the day opened outside the prior value area.
  - F2: keep it only if the last two completed 30-minute closes are outside the value area on the same side.
  - F3: as F2, and the trade's side matches the acceptance side.
- **RANGE arms** (Range Fade; ORB Sweep):
  - G1: keep a trade only if the day opened inside value.
  - G2: keep it only if the last 30-minute close is inside value.
- **Secondary:** the opposite rule type for the three Liquidity arms and for ORB Sweep. 56 filters in all.

**How to read the table:**
- Columns are net rupees per trade (1 lot, real spread) and rupees per day over all sessions.
- p = one-sided day-cluster bootstrap, kept vs skipped per trade.
- **The ORB family and Range Fade lose money on every subset.** Skipping any of their trades "improves" Rs/day, so
  only kept vs skipped *per trade* is a fair test for them. The same is why the reality-check p is 0.000 for those
  arms: it is meaningless there.

The best 6 in design (by p), then every Liquidity BANKNIFTY row:

| filter | period | trades | kept | win% kept / skipped | Rs/trade all | Rs/trade kept | Rs/trade skipped | Rs/day all | Rs/day kept | p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB Fresh, F1, TPO VA | design | 1,980 | 1,331 | 41.5 / 38.1 | -139 | -117 | -182 | -275 | -157 | 0.016 | 0.39 |
| | holdout | 474 | 318 | 41.2 / 40.4 | -170 | -160 | -191 | -325 | -205 | 0.36 | |
| Liq MIDCP, F1, TPO VA | design | 384 | 260 | 37.7 / 29.0 | +35 | +181 | -270 | +23 | +82 | 0.020 | 0.39 |
| | holdout | 220 | 151 | 29.8 / 42.0 | +33 | **-95** | +313 | +30 | **-58** | 0.76 | |
| Liq MIDCP, F1, volume VA | design | 384 | 252 | 37.3 / 30.3 | +35 | +196 | -272 | +23 | +86 | 0.021 | 0.39 |
| | holdout | 220 | 143 | 31.5 / 37.7 | +33 | **-44** | +177 | +30 | **-25** | 0.67 | |
| ORB Fresh, F1, volume VA | design | 1,980 | 1,322 | 41.1 / 39.1 | -139 | -120 | -175 | -275 | -160 | 0.029 | 0.41 |
| | holdout | 474 | 298 | 42.3 / 38.6 | -170 | -143 | -216 | -325 | -172 | 0.19 | |
| ORB, F1, volume VA | design | 7,768 | 5,187 | 41.4 / 39.5 | -133 | -123 | -152 | -1,035 | -642 | 0.040 | 0.44 |
| | holdout | 1,902 | 1,243 | 40.7 / 37.8 | -205 | -188 | -237 | -1,570 | -942 | 0.09 | |
| ORB, F1, TPO VA | design | 7,768 | 5,299 | 41.3 / 39.5 | -133 | -126 | -146 | -1,035 | -672 | 0.11 | 0.93 |
| **Liq BN, F1, volume VA** | design | 750 | 517 | 32.7 / 36.5 | +86 | +78 | +104 | +66 | +41 | 0.56 | 0.98 |
| | holdout | 248 | 152 | 38.8 / 31.2 | +168 | +393 | -189 | +168 | +241 | 0.033 | |
| **Liq BN, F2, volume VA** | design | 750 | 481 | 31.2 / 38.7 | +86 | **+6** | **+230** | +66 | **+3** | 0.91 | 0.98 |
| | holdout | 248 | 155 | 36.1 / 35.5 | +168 | +315 | -76 | +168 | +197 | 0.095 | |
| **Liq BN, F3, volume VA** | design | 750 | 451 | 31.7 / 37.1 | +86 | +30 | +171 | +66 | +14 | 0.79 | 0.98 |
| | holdout | 248 | 143 | 35.0 / 37.1 | +168 | +298 | -9 | +168 | +172 | 0.17 | |
| Liq BN, G1 (inside only), volume VA | design | 750 | 233 | 36.5 / 32.7 | +86 | +104 | +78 | +66 | +25 | 0.44 | 0.93 |
| | holdout | 248 | 96 | 31.2 / 38.8 | +168 | -189 | +393 | +168 | -73 | 0.97 | |

- **0 of 56 filters passed the gate** (BH q < 0.05, ≥ 100 kept, kept Rs/day above the base, more than half the years
  better). So none can be "confirmed".
- **Liquidity BANKNIFTY flips sign between periods.**
  - In design, the trend filter (F2) left it at **Rs +3/day instead of +66**: its best trades were on days *inside*
    value.
  - In the holdout, F1/F2 *helped* it (Rs +197-241/day vs +168).
  - A filter whose sign flips with the period is noise.
  - Its POST-HOC holdout p of 0.03 for F1 is one look among 56 filters, and it contradicts the design result.
- **Liquidity MIDCP flips the other way.** In design, F1 was the best rule (Rs +82-86/day vs +23). In the holdout it
  turned the arm negative (Rs -25 to -58/day vs +30).
- **ORB family:** keeping only outside-value days loses less per trade in both periods (F1: about Rs 20-50/trade less
  bad). Every subset still loses Rs 120-190 per trade, so this does not make ORB tradeable. The h19 verdict stands:
  paper only.
- **Stress at 1.5× spread** is in the CSVs (`kept_day15`). Nothing passed, so nothing needed it.

## 4. Test C: dealer gamma from the NSE bhavcopy

### Method (`gex.py`)

- **Inputs:** each evening t, for each index, the nearest 3 expiries.
  - Forward = K + C - P at the strike where C ≈ P.
  - IV per strike from the out-of-the-money option's settle price (Black-76). If that fails, use the expiry's ATM IV.
  - Gamma × OI (units) × S² × 1% gives Rs per 1% move.
- **Conventions:**
  - **A, US standard** (dealers long calls, short puts): GEX_A = Σ call gamma - Σ put gamma.
  - **B** (dealers short calls, long puts) = -A. So a two-sided test on A covers both A and B.
  - **C, customer net:** dealers = everyone but Clients, scaled by the Clients' net share of index calls and puts in the
    participant OI.
  - **D, Pro = dealer:** scaled by the Pros' net share.
- **Zero-gamma flip:** GEX_A recomputed over spot ±6%, and the crossing nearest spot taken.
  - Flip found on 99.6% of NIFTY/BANKNIFTY days.
  - Spot sits a median 0.27% (NIFTY) and 0.57% (BANKNIFTY) above it.
  - "Spot above the flip" turned out identical to "GEX_A > 0 at spot", so those two rows are one test.
- **Who is "long gamma":**
  - GEX_A is positive on 91-93% of design days and 79-82% of holdout days.
  - Customer-net (C) is positive on 91% of design days and **100%** of holdout days.
  - Pro-dealer (D) is positive on **19% of design days but 81-82% of holdout days**. The Pros' net option position
    turned over in late 2024-25.
  - These signs are mostly a measure of *who holds what*, and that changes with regulation and participants.

### Next-session character

Session 09:15 → 15:25: TR = |close - open| / range; VWAP-proxy crosses; RV = realised vol of 1-minute returns (%);
range %.

| index | split | period | days + / - | TR + vs - | crosses + vs - | RV % + vs - | range % + vs - | lowest p | 
|---|---|---|---|---|---|---|---|---|
| NIFTY | A sign (GEX_A>0 vs <0) | design | 1,188 / 86 | 0.48 vs 0.52 | 8.0 vs 7.4 | 0.61 vs 0.62 | 1.04 vs 1.06 | 0.12 |
| NIFTY | A sign | holdout | 195 / 44 | 0.49 vs 0.48 | 7.3 vs 7.5 | 0.53 vs 0.55 | 0.91 vs 0.98 | 0.48 |
| BANKNIFTY | A sign | design | 929 / 66 | 0.46 vs 0.39 | 9.5 vs 11.4 | 0.80 vs 0.71 | **1.32 vs 1.01** | 0.0001 (q 0.0007) |
| BANKNIFTY | A sign | holdout | 195 / 53 | 0.48 vs 0.44 | 7.8 vs 9.6 | **0.62 vs 0.77** | **1.09 vs 1.37** | 0.003 |
| BANKNIFTY | A ratio, partial (controls: prior RV, VIX, days to expiry): t on RV / range | design | 990 | | | t **+2.6** | t **+3.6** | q 0.025 / 0.001 |
| BANKNIFTY | same | holdout | 247 | | | t **-3.0** | t -1.6 | 0.003 |
| NIFTY | D sign (Pro long gamma) | design | 268 / 1,005 | 0.50 vs 0.48 | 7.1 vs 8.2 | 0.54 vs 0.63 | 0.92 vs 1.08 | <0.0001 |
| NIFTY | D sign | holdout | 194 / 45 | 0.49 vs 0.47 | 7.1 vs 8.1 | 0.50 vs 0.69 | 0.86 vs 1.21 | <0.0001 |
| BANKNIFTY | D sign | design | 258 / 736 | 0.46 vs 0.46 | 8.2 vs 10.1 | 0.68 vs 0.83 | 1.11 vs 1.36 | <0.0001 |
| BANKNIFTY | D sign | holdout | 203 / 45 | 0.47 vs 0.48 | 8.4 vs 7.4 | 0.64 vs 0.73 | 1.10 vs 1.33 | 0.04 |
| NIFTY | C sign (customer net) | design | 1,176 / 97 | 0.48 vs 0.48 | 7.8 vs 9.2 | 0.61 vs 0.73 | 1.03 vs 1.21 | 0.0001 |
| either | C sign | holdout | all positive | | | | | untestable |

**What this says:**
- **The video's rule (positive gamma → chop, negative → trend) is not in the data.**
  - Trend ratio and VWAP crosses do not separate the days consistently under any convention.
  - The big BANKNIFTY effect is on **size** (range, RV), and its **sign reversed** between design and holdout, even
    after controlling for prior volatility, VIX and days to expiry.
  - A morning "zero-gamma" check would have told us the opposite thing in the two periods.
- **The one stable pattern:** on evenings when the Pros are net long option gamma (D > 0), the next session is quieter.
  - NIFTY RV 0.50 vs 0.69, range 0.86% vs 1.21% in the holdout. The same direction held in design.
  - This is a size effect only: no direction, no chop-vs-trend.
  - It is not controlled for volatility persistence. The Pros' positioning tracks the vol regime.
  - As a filter it did nothing (below).

### As a filter (28 filters: TREND arms keep "negative-gamma" days under A / B / C / D; RANGE arms keep the opposite)

| filter | period | trades | kept | Rs/trade all | kept | skipped | Rs/day all | kept | p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|
| ORB Fresh, B (keep GEX_A > 0) | design | 1,980 | 1,865 | -139 | -133 | -230 | -275 | -249 | 0.058 | 0.80 |
| | holdout | 474 | 378 | -170 | -141 | -285 | -325 | -215 | 0.09 | |
| ORB, D (keep Pro short gamma) | design | 7,768 | 5,797 | -133 | -126 | -153 | -1,035 | -732 | 0.061 | 0.80 |
| | holdout | 1,902 | 438 | -205 | -159 | -218 | -1,570 | -281 | 0.08 | |
| **Liq BN, B** | design | 750 | 711 | +86 | +99 | -153 | +66 | +72 | 0.12 | 0.80 |
| | holdout | 248 | 201 | +168 | +99 | **+462** | +168 | **+81** | 0.76 | |
| **Liq BN, A** (keep GEX_A < 0) | design | 750 | 39 | +86 | -153 | +99 | +66 | -6 | 0.88 | 0.99 |
| | holdout | 248 | 47 | +168 | +462 | +99 | +168 | +88 | 0.24 | |
| **Liq BN, D** | design | 750 | 498 | +86 | +35 | +188 | +66 | +17 | 0.83 | 0.99 |
| | holdout | 248 | 55 | +168 | +155 | +172 | +168 | +34 | 0.49 | |
| Liq MIDCP, D | design | 384 | 227 | +35 | +98 | -56 | +23 | +39 | 0.30 | 0.80 |
| | holdout | 220 | 43 | +33 | +729 | -136 | +30 | +127 | 0.17 | |

- 0 of 28 passed. Liquidity BANKNIFTY flips again.
  - Design: its few "negative US-gamma" days (39 trades) lost money.
  - Holdout: the same kind of days (47 trades) made Rs +462 a trade, and skipping them cost half the arm's profit.

## 5. Test D: delta divergence and absorption - proxies only

There is no historical tick, bid/ask or aggressor data (R7). The proxies are:
- **D1 "delta":** per minute, signed volume of the nearest option series, ATM±5: calls with a rising price count as
  buys, puts likewise, and call buys minus put buys is the delta. Divergence = a 30-minute index move of 0.15% or more
  while this delta points the other way. Control = the same move with delta agreeing.
- **D2 "absorption":** a 5-minute bar with volume ≥ 2× its 20-day time-of-day median and range ≤ 0.5× its median,
  after a 0.15% move. Control = the same move on normal volume.
- **Outcome:** the index move in the *reversal* direction over the next 15/30/60 minutes, in basis points.
- **48-day FUTV repeat:** delta = sign(futures close - open) × futures volume.

| claim | index | period | events | 15 min: event vs control (bp) | 30 min | 60 min | best p vs control | BH q |
|---|---|---|---|---|---|---|---|---|
| divergence → reversal | NIFTY | design | 404 | +0.5 vs -0.3 | -0.9 vs -0.4 | -2.6 vs -1.0 | 0.22 | 0.66 |
| | | holdout | 62 | +1.0 vs 0.0 | +1.5 vs 0.0 | -1.7 vs -0.8 | 0.29 | |
| | | futures 48 d | 26 | -0.3 vs -0.5 | +1.8 vs -1.3 | +2.3 vs -2.2 | 0.15 | |
| divergence → reversal | BANKNIFTY | design | 571 | +0.6 vs +0.2 | 0.0 vs 0.0 | -0.8 vs -0.2 | 0.33 | 0.68 |
| | | holdout | 94 | +1.8 vs -0.4 | -1.0 vs -1.0 | -1.2 vs -1.5 | 0.07 | |
| | | futures 48 d | 59 | +2.3 vs -0.7 | -0.1 vs -1.4 | -3.7 vs -1.2 | 0.02 | |
| absorption → reversal | NIFTY | design | 52 | +2.2 vs -0.3 | +2.7 vs -0.5 | +4.7 vs -0.9 | 0.034 | 0.29 |
| | | holdout | 18 | +2.2 vs +0.2 | -1.2 vs 0.0 | -2.7 vs -0.3 | 0.15 | |
| absorption → reversal | BANKNIFTY | design | 84 | 0.0 vs +0.1 | +0.1 vs -0.2 | +0.4 vs +0.1 | 0.43 | 0.68 |
| | | holdout | 18 | +0.8 vs +0.1 | 0.0 vs -0.3 | **-8.4** vs -0.7 | 0.41 | |

- 0 of 12 design tests pass. The best is NIFTY absorption, with 52 events and +2-5 bp; the holdout fades it.
- Every mean is a few basis points. An option buyer needs more than about 2 bp just for the spread (R7), plus theta,
  so even a real effect of this size would not pay.
- "Delta never lies" has no support here: delta disagreeing with price does not make the next 15-60 minutes reverse.
- These are crude proxies:
  - option-complex signed volume, not futures aggressor volume;
  - 1-minute bars, not ticks;
  - no hidden-order (iceberg) information at all.
- **This is NOT the real test.** That needs the app's recordings (section 6).

## 6. What goes in the app, and what to measure later

**Paper filter rules to add: none.** No R9 rule passed its own design gate. The ones that looked best in one period
flipped in the other (Liquidity BANKNIFTY and MIDCP under both the auction and the gamma filters). Showing "value
area" or "zero-gamma" on a card would invite Boss to trade on it, so we recommend not adding even a display.

**What to measure from the shadow log** (FlowShadow `S|...` lines at every strategy signal, `V/O/R/M` lines after;
OrderFlow 60/300-s CVD, OFI, 5-level depth; TrapGuard flags `ABSORPTION_BUY/SELL`, `EXHAUSTION_*`). After 2-4 weeks,
run checks only, no verdicts: pipe check, counts, and the same-10-s correlation of CVD with the future's move ≥ 0.5,
as in R7. The decision tests below need about 300 Liquidity signals, which is 3-6 months. Pre-register before
looking.

1. **Delta divergence (concept 3), the real version.**
   - At each signal, divergence = sign(future's 300-s move) ≠ sign(cvd300) with |z(cvd300)| ≥ 1.
   - Compare the `M` line (the future's 15/30-minute move in the trade's direction) and `R` net for these four groups:
     - divergence against the trade;
     - divergence with the trade;
     - no divergence;
     - all signals.
   - Pass only if "divergence against the trade" has a 30-minute move below the others, with a day-cluster bootstrap
     p < 0.05, in both halves of the record.
2. **Absorption (concept 2).**
   - Group signals by TrapGuard's `ABSORPTION_*` flags. ABSORPTION_SELL = a hidden buyer met the sellers, which is
     bullish.
   - Measure the hit rate and `M` 15/30-minute move in the absorption's direction versus signals without the flag.
   - Also measure the rate at which an absorption level breaks within 30 s (`EXHAUSTION_*`). R8 found that walls mostly
     live about 1 second.
   - Pass only if the move beats 2 bp (BANKNIFTY) and the flag adds to the arm's per-trade net.
3. **Volume profile on real futures volume (concept 1).**
   - The recorder's per-minute futures volume (MarketRecorder QUOTE lines) gives a true futures profile.
   - After about 120 sessions, rerun `placebo_vp.py` with `src="futv"`. The code takes it as is: write the minutes to
     `fut_<U>.npz`.
   - Today's 48 days are too few, and all sit in the used holdout.
4. **Auction acceptance with real volume (concept 4).** The same `auction.py` on the recorded futures profile, as a
   filter on the paper arms. Keep in mind it failed here with both the volume proxy and TPO.
5. **Gamma (concept 5).**
   - Nothing new to record: the bhavcopy is free each evening.
   - If wanted, append each evening's GEX_A, GEX_D, the flip and the Pros' net to the shadow log as context. Recheck
     them in 6 months against the next session's range only. Do not use them as a trade filter.
   - The convention problem (who is the dealer in India) cannot be solved with public data.

## Honest count of tries

| test | design tests | holdout looks | information-only |
|---|---|---|---|
| A placebo levels | 32 | 32 | 24 (futures volume, 48 days) |
| B regime predictive | 56 | 56 | - |
| B filters | 56 | 56 (all shown; none eligible to confirm) | - |
| C gamma predictive | 40 | 40 | - |
| C filters | 28 | 28 (all shown; none eligible) | - |
| D proxies | 12 | 12 | 12 (futures volume) |
| **total** | **224** | one run (`holdout.py`, refuses a second) | 36 |

Design passes after BH: A 0/32, B 0/56 + 0/56, C predictive 15/40 (all on range, RV or VWAP crosses, none on the trend ratio:
the C and D signs on both indices, BANKNIFTY GEX_A sign/ratio; the BANKNIFTY GEX_A effect reversed in the holdout), C filters 0/28, D 0/12.

## Limits

- **The volume profile is a proxy built from option volume.** It matches the real futures profile's POC on about 2 of 3
  days. Real futures volume exists for only 48 days.
- **No tick, bid/ask or aggressor data**, so D is a proxy test.
- **The bhavcopy has only the nearest 3 expiries within ±8%.** Far strikes are missing, but their gamma is small. IVs
  come from settle prices, and the r = 0 forward comes from put-call parity.
- **Participant OI is pooled over all indices**, so C and D are approximate per index.
- **The arm trade lists are historic replays** (h19, h24). The filters only choose which trades are kept; entries and
  exits were not re-simulated.
- **Unknown regime** (no prior profile or no bhavcopy) counts as "kept". This affected few days.
