# OBUY_SEARCH - computational search for an intraday index option-BUYING edge

## 0. Holdout lock (written before any search was run)

- **Holdout = every trading day on or after 2025-10-01** (2025-10-01 .. 2026-10-06, the last ~12 months of data).
- **In-sample = every trading day before 2025-10-01** (NIFTY from Aug 2020, BANKNIFTY / FINNIFTY from Aug 2021).
- The build step writes in-sample and holdout trade tables to separate files; the search, walk-forward,
  multiple-testing and permutation scripts read only the in-sample file. The holdout file is read exactly once,
  by `holdout.py`, for the (at most 10) rules that survive correction. Its results are reported as they come out.

(Results below were filled in after the search.)

## 0b. Pre-registered survivor rule (written before the search was run)

A strategy = entry rule (1-3 condition atoms from different families x direction x entry window x index) + strike
(ATM / 1-ITM) + exit (52-exit menu). Every combination generated is counted.

- **Survivor** = all of: (1) Benjamini-Hochberg FDR q <= 0.10 over all evaluated strategies (one-sided p from the
  t-stat of the daily P&L series); (2) Deflated Sharpe Ratio >= 0.95 with N = number of evaluated strategies;
  (3) beats random entries with the same index, side, window, strike, exit and trade count, permutation p <= 0.01
  (2000 draws). One exit/strike per entry rule (its best t), at most 10, ranked by in-sample t.
- Family-level tests reported alongside: White's Reality Check and Hansen's SPA (stationary bootstrap of calendar
  days, mean block 5, B = 400) over every evaluated strategy.
- **If nothing survives**, the 10 best in-sample rules (one per entry rule) are still run once on the holdout, labelled
  as NON-survivors, purely to show how much of an in-sample "edge" carries forward.

## 1. Plain-language result

- **Rules tried:** 22,534 condition combinations (1-3 of 54 atoms, different families) x 2 sides x 4 entry windows x
  3 indices = **540,816 entry rules**; x 2 strikes x 52 exits = **56,244,864 strategies generated**. 251,010 entry
  rules (**25,900,004 strategies**) had >= 40 in-sample trades and were evaluated; the rest were too rare to test.
- **Survived correction: 0.** The best in-sample t-stat was **4.01**; Benjamini-Hochberg at 10% FDR needed **5.77**,
  the SPA / Reality-Check 90% critical value was **6.34** (RC p = 1.00, SPA p = 1.00), and the Deflated-Sharpe hurdle
  was t = **6.35**. Every top rule's DSR was ~0.00 (needed 0.95), even though they all beat random entries
  (permutation p <= 0.001) - beating random entries is easy when the search picks from 26 million tries.
- **The average strategy loses money:** mean -146 Rs per trade (1 lot, after costs); only 19% of the 25.9M
  strategies had a positive in-sample mean. Option buying intraday pays theta + spread + charges, and simple rules
  do not recover that.
- **Walk-forward inside the in-sample** (pick the 20 best rules on all prior years, trade them the next year; sum of the 20, 1 lot each):
  2022 -85451 Rs, 2023 +2849 Rs, 2024 -7832 Rs, 2025 (Jan-Sep) -132295 Rs - the "best" rules lose next year in 3 of 4 folds.
- **Holdout (Oct 2025 - Oct 2026, looked at once):** since nothing survived, the 10 best in-sample rules (NOT
  survivors) were run as a demonstration: **1 of 10 made money; together they lost Rs 105,964** on 1 lot each. Their
  in-sample Monte Carlo had promised P(profit in a year) of 93-99%; on the holdout it was 2-71%.
- **Worth paper trading? No.** No computationally found rule is distinguishable from the luck of the best of 26
  million tries, and the in-sample winners failed out of sample. Do not paper-trade any rule from this search as an
  "edge"; at most, track the list below as a control group of what overfitting looks like.

## 2. Setup

- **Data:** `<scratchpad>/maxloss/prep/{NIFTY,BANKNIFTY,FINNIFTY}.gz` (Dhan expired-option minutes: index 1-min OHLC
  plus ATM+-10 option minutes with volume and OI, nearest listed expiry), India VIX daily + minute candles. Partial
  days (index data starting after 09:19) dropped. In-sample days: NIFTY 1,279 (Aug 2020-), BANKNIFTY 1,003 (Aug 2021-),
  FINNIFTY 938 (Oct 2021-); union calendar D = 1279 days. Holdout: 248 / 248 / 247 days.
- **Decision grid:** every 5 minutes 09:20-14:30 (63 slots). Conditions use index bars closed by the slot and OI up
  to the previous minute; the option is bought at the next minute's open. One trade per strategy per day (the first
  slot in the window where all conditions hold). Windows: 09:20-10:15, 10:15-12:00, 12:00-14:30, 09:20-14:30.
- **Option:** ATM or 1-ITM on the spot at decision, nearest expiry (on expiry day that is the same-day contract),
  1 lot of that day's size, skipped below Rs 10 premium or with no trades in the strike over the prior 6 minutes.
- **Fills / costs:** buy = open x 1.003 + 0.05; market sell = x 0.997 - 0.05; stop = min(stop, open) x 0.994 - 0.05;
  target = limit at the target. Charges per leg: Rs 20 brokerage, STT 0.15% of sell premium, NSE txn 0.03553%, SEBI,
  stamp 0.003%, 18% GST (the app's SandboxCosts as float). Within a minute: time exit, then stop/lock, then target.
- **Exit menu (52):** premium stop 15/25/35% x target 30/50/100%/none x time 30/60/120 min/15:10, plus 30-pt stop /
  +60 target / profit-lock ladder (+15 -> breakeven+charges, +30 -> lock 15, +45 -> lock 30) x the 4 time exits.
- **Condition atoms (54 in 17 families; "with" = in the trade's direction, mirrored for puts):**
  ORB (5/15/30-min break with, 15-min break against, inside 15-min range); gap (big/small with, flat, small/big
  against); VWAP (session TWAP of typical price - the index has no volume: with, >0.3% with, >0.3% against); EMA
  9>21>50 on 1/5/15-min (with) and 5-min against; RSI-14 5-min (>60, >70, extreme against, 40-60); range (NR4, NR7,
  prior day >1.5 ATR, first hour >1.3x / <0.7x 20-day avg, today's range >0.8 ATR); prior-day levels (beyond PDH/PDL
  with / against, PDC side, inside prior range); CPR (narrow / wide vs 60-day percentiles, price beyond CPR with);
  VIX level (<13, 13-18, >18) and VIX now vs prior close (>+3%, <-3%); PCR at ATM+-2 (with / against, 1.2 / 0.83);
  net OI writing at ATM+-2 since the open (with); ATM straddle since open (>= +3%, <= -10%); expiry day /
  day before / other; prior day / 3-day trend with, prior day >1% against; big 5-min candle (>2x avg) with /
  against; move since open >0.3% with / against.

## 3. Multiple-testing results (in-sample only)

| quantity | value |
|---|---|
| strategies generated / evaluated | 56,244,864 / 25,900,004 |
| median / 99th pct / 99.9th pct / max t-stat | -1.00 / 1.44 / 2.13 / 4.01 |
| strategies with t > 2 / > 3 / > 4 | 41,538 / 543 / 1 |
| t needed for BH-FDR 10% (one-sided) | 5.77 -> **0 pass** |
| White Reality Check p (best rule) / SPA p | 1.00 / 1.00 |
| RC = SPA 90% critical t (stationary bootstrap, block 5, B=400) | 6.34 -> **0 pass** |
| Deflated-Sharpe hurdle (expected max of 25,900,004 trials), as a t | 6.35 |
| survivors (BH + DSR >= 0.95 + permutation p <= 0.01) | **0** |

The bootstrap max is high (6.3) because most strategies trade on few days, so their daily P&L is very fat-tailed;
that is exactly the case in which a single lucky streak produces a t of 3-4.

**Walk-forward inside the in-sample** (each year: the top 20 strategies by t on all earlier years, then that year):

| test year | train t of the picks | trades | total Rs (1 lot each) | Rs / trade | picks positive |
|---|---|---|---|---|---|
| 2022 | 3.54-3.97 | 565 | -85,451 | -151 | 6/20 |
| 2023 | 3.53-3.90 | 401 | +2,849 | +7 | 13/20 |
| 2024 | 3.55-3.95 | 504 | -7,832 | -16 | 9/20 |
| 2025 | 3.77-4.50 | 449 | -132,295 | -295 | 6/20 |

## 4. Holdout - the one look (Oct 2025 - Oct 2026)

Nothing survived, so per the pre-registration the 10 best in-sample rules (best exit/strike per entry rule) were run
once on the holdout as **NON-survivors**. Rs are net of costs, 1 lot. "Random" = mean of every entry slot in the same
window with the same exit (the random-entry baseline on the holdout).

| # | rule (index side conditions window strike exit) | IS n | IS t | IS Rs/trade | HO n | HO Rs/trade | HO total | HO win% | HO random Rs/trade |
|---|---|---|---|---|---|---|---|---|---|
| 1 | NIFTY PE ema5_with+vix_down+prev_with 1200-1430 ITM1 SL25%,TG30%,1510 | 48 | 4.01 | +815 | 12 | -269 | -3,228 | 33 | -113 |
| 2 | NIFTY PE cpr_with+vix_down+prev_with 1200-1430 ITM1 SL25%,TG30%,1510 | 50 | 3.83 | +823 | 10 | -686 | -6,862 | 30 | -113 |
| 3 | FINNIFTY CE vwap_far_with+ema5_with+expiry_day 1200-1430 ITM1 SL35%,TG50%,120m | 41 | 3.81 | +836 | 0 | +0 | +0 | 0 | -557 |
| 4 | BANKNIFTY CE ema15_with+fh_wide+prev_with 1200-1430 ITM1 SL35%,TG30%,1510 | 49 | 3.72 | +966 | 13 | -550 | -7,152 | 31 | -323 |
| 5 | BANKNIFTY CE fh_wide+cpr_with+prev_with 1200-1430 ITM1 SL35%,TG30%,1510 | 49 | 3.68 | +1045 | 14 | -1551 | -21,709 | 29 | -323 |
| 6 | BANKNIFTY CE fh_wide+pdc_with+prev_with 1200-1430 ITM1 SL35%,TG30%,1510 | 43 | 3.61 | +1071 | 13 | -1681 | -21,858 | 31 | -323 |
| 7 | NIFTY CE rsi_mid+vix_high+pcr_with 0920-1015 ATM SL35%,TG30%,30m | 61 | 3.59 | +714 | 10 | +453 | +4,534 | 60 | -140 |
| 8 | BANKNIFTY PE orb15_inside+pcr_with+oi_with 0920-1015 ITM1 SL25%,TG30%,120m | 258 | 3.59 | +390 | 23 | -304 | -6,982 | 39 | -387 |
| 9 | NIFTY PE pcr_with+expiry_day+prev_with 0920-1430 ITM1 SL35%,TG100%,1510 | 132 | 3.54 | +973 | 20 | -507 | -10,140 | 30 | +41 |
| 10 | NIFTY PE inside_prev+pcr_with+expiry_day 0920-1015 ITM1 SL35%,TG100%,1510 | 154 | 3.49 | +907 | 29 | -1123 | -32,566 | 17 | +4 |

**Holdout: 1/10 positive, combined -105,964 Rs.** (Rule 3 never fired: FINNIFTY lost its weekly expiry in Nov 2024, so the holdout has only ~12 monthly FINNIFTY expiry days and the three conditions never lined up.)

## 5. Probabilities (Rs 5 lakh, 1 lot, 250-day stationary bootstrap, 5,000 paths)

The same 10 rules. IS = bootstrapped from the in-sample daily P&L (scaled to the latest in-sample lot); HO = from
the holdout daily P&L. P(DD > 20% / 50%) was 0 everywhere - 1 lot of an index option is small next to Rs 5 lakh, so the
risk here is not ruin but a steady bleed.

| # | IS P(profit) | IS EV Rs [95% CI] | HO P(profit) | HO EV Rs [95% CI] |
|---|---|---|---|---|
| 1 | 97% | +11,015 [-669, +24,561] | 32% | -3,287 [-16,917, +10,392] |
| 2 | 94% | +10,663 [-2,517, +26,639] | 7% | -6,975 [-17,857, +1,692] |
| 3 | 99% | +15,000 [+1,144, +30,938] | no trades | - |
| 4 | 98% | +20,695 [+1,396, +43,674] | 32% | -7,356 [-40,130, +32,003] |
| 5 | 99% | +22,493 [+2,874, +44,536] | 14% | -22,149 [-60,635, +20,878] |
| 6 | 98% | +20,725 [+1,998, +42,373] | 14% | -22,276 [-62,038, +19,935] |
| 7 | 94% | +10,081 [-2,765, +24,684] | 71% | +4,574 [-7,685, +20,319] |
| 8 | 97% | +40,350 [-1,492, +85,393] | 27% | -6,968 [-30,774, +17,196] |
| 9 | 93% | +31,487 [-9,150, +74,433] | 22% | -10,374 [-36,119, +20,052] |
| 10 | 95% | +37,751 [-6,868, +84,484] | 2% | -32,867 [-61,537, -1,710] |

The in-sample probabilities (93-99% chance of a profitable year) are what a naive backtest of the "best" rule would
show; they are an artefact of picking the best of 26 million. Do not quote them.

## 6. Caveats

- Costs: STT at 0.15% of sell premium (the current rate in the app) is applied to all years; it was 0.0625% / 0.1%
  earlier, so early-year costs are slightly overstated (a few Rs per trade - far less than the 146 Rs average loss or
  the gap between t = 4.0 and the 5.8-6.3 hurdles). Slippage (0.3% + 0.05 per side, double on stops) is an assumption.
- The "VWAP" is a time-weighted average of the index's typical price (the index has no volume).
- OI updates every few minutes in the data; OI features use only values printed before the decision minute.
- Some early days use Dhan's per-minute spot print as the index bar (o=h=l=c); expiry-day flags come from the prep
  step's straddle test. BANKNIFTY / FINNIFTY lost weekly expiries in Nov 2024, so their "expiry_day" atom changes
  meaning (monthly only) inside the sample.
- Trades per strategy are one per day; strategies are not combined into portfolios.

## 7. Reproduce

```
SP=<scratchpad>
for u in NIFTY BANKNIFTY FINNIFTY; do python3 -I research/obuy_search/build.py $SP $u; done   # ~7 min, writes obs/is_*.npz, obs/ho_*.npz
python3 -I research/obuy_search/search.py $SP      # ~6 min, in-sample only -> obs/search.npz
python3 -I research/obuy_search/analyze.py $SP     # BH / RC / SPA / DSR / permutation / walk-forward -> obs/analysis.json
python3 -I research/obuy_search/holdout.py $SP     # the one holdout look -> obs/holdout.json
```
