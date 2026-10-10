# HUNT h26: volume and open interest, tested properly (option buying only)

Files:
- Code: `research/hunt/h26/`
  - `PREREG.md`: the plan, written before any P&L. It has two recorded amendments: premium-point exits, and the wick rule.
  - `build.py`: the option and stock feature panels.
  - `feats.py`: the triggers.
  - `run.py`: runs every rule through the obuy Lab.
  - `post.py`: BH, SPA and survivor checks over all runs combined.
  - `liqfilter.py`: the features as filters on Liquidity 15+5.
  - `diag.py`: IC and lead-lag checks.
  - `ties.py`: counts minutes where the stop and the target were both hit.
- Logs: `scratchpad/hunt/h26/`
  - `diag.log`, `post_pre.log`, `post_hold.log`, `liqfilter_pre.log`, `liqfilter_hold.log`, `ties.log`, `pre_*.log`, `hold.log`.
  - CSVs: `variants_all.csv`, `families_all.csv`.
  - Lab run folders: `cache/runs/`.

Rules followed:
- Every trade buys an option: 1 lot, 1-ITM, nearest expiry.
- Entry is at the next minute's open, paying the measured half-spread from h24. Charges use the app's cost model.
- Every rule was chosen on data before 2025-10-01. The locked holdout (2025-10-01 to 2026-10-06) was run once at the end.

## Verdict

**NO. Volume and OI do not give a rule that makes money after costs. Rs 5,000/day: NO.**

1. **I tested 2,268 trigger variants.**
   - That is 126 trigger settings × 18 fixed exits.
   - Only 87 made money before the holdout, after the real spread.
   - None survives the multiple-testing correction. The lowest BH q is 0.23.
   - Union White Reality Check p = 0.97. Hansen SPA p = 0.96. As a group, these rules do no better than not trading.
2. **No survivors went to the holdout.** As the plan says, the best family from each run was still run once in the
   holdout, for information only. I added one more, the best in-sample variant, also for information only.
   - 6 of the 7 lost money net.
   - The one exception is **"unusual option volume with the premium rising" (VSPIKE) on BANKNIFTY**: +Rs 102/day net at
     1 lot (gross +197). It beat random entries (p 0.018).
   - But it lost in 7 of 12 months. Before the holdout it made only Rs 22/day, it lost in 2021, and it did not pass
     the corrections.
   - This is a weak hint, not a rule.
3. **The same VSPIKE idea makes money GROSS on BANKNIFTY, FINNIFTY and NIFTY, then the spread eats it.**
   - FINNIFTY in the holdout: +Rs 200/day gross, but -Rs 134/day net. Its half-spread is 0.42%. It beat random at
     p 0.001.
   - So the signal is real, but too small for an option buyer to keep.
4. **Put-minus-call OI change at ATM±2/±5 (DOIPC) has the clearest forward correlation.**
   - The 15-minute IC is about 0.03 (t ≈ 4.5) on NIFTY and BANKNIFTY.
   - The best in-sample variant made BANKNIFTY +Rs 110/day before the holdout, at p_rand 0.000.
   - But the walk-forward lost Rs 31k, because the best exit changed every year.
   - In the holdout it made -Rs 45/day net (+64 gross).
5. **Constituent volume (heavyweight surges, volume-weighted breadth, VWAP share, lead-lag) is all negative.**
   - Every family lost in the walk-forward.
   - **Lead-lag does not exist at 1-5 minutes.**
     - The heavyweight basket and the index move in the same minute (correlation 0.99 for BANKNIFTY, 0.95 for NIFTY).
     - At 1 minute or more the correlation is ≤ 0.02.
     - So the pre-registered lead-lag trigger almost never fired (8 signals in a year).
6. **As filters on Liquidity 15+5, nothing passed the pre-registered adoption rule.**
   - The closest was "skip the trade when the 15-minute OI build-up opposes it" (BU15).
     - Before the holdout: +Rs 29/day, positive in both halves, raw p 0.010, but BH q 0.13 (the bar was 0.10).
     - In the holdout, run once for information: **+Rs 81/day** (Liquidity's holdout 324 → 405/day net, 1 lot per index).
       It skipped 44 of 920 trades, which averaged -Rs 458 each. p = 0.035.
   - It is a candidate to log on paper in the app. It is not adopted.
7. **The futures OI build-up idea (OI-03) still cannot be tested.** The futures data holds only the 3 contracts live
   in October 2026.

## The numbers that matter (1 lot, per trading day)

Holdout, 2025-10-01 to 2026-10-06 (249 days). None of these is a recommendation.

| rule (pick from data before Oct 2025) | trades | gross Rs/day | net Rs/day (real spread) | net at 1.5× spread | max DD | losing months | beats random (p) |
|---|---|---|---|---|---|---|---|
| VSPIKE BANKNIFTY, k=5, Liquidity-arm exits | 169 | +197 | **+102** | +84 | -21,030 | 7/12 | 0.018 |
| VSPIKE FINNIFTY, k=5, -10/+30 premium pts | 346 | +200 | -134 | -238 | -57,931 | 7/13 | 0.001 |
| VSPIKE SENSEX, k=5, arm exits | 123 | +26 | -16 | -21 | -27,147 | 6/12 | 0.57 |
| DOIPC BANKNIFTY b5, -15%/+30% (extra, in-sample best) | 177 | +64 | -45 | -66 | -52,771 | 8/13 | 0.44 |
| VIMB reversed, NIFTY | 51 | -21 | -45 | -49 | -14,751 | 8/11 | 0.87 |
| OI build-up reversed, MIDCPNIFTY | 84 | -22 | -90 | -106 | -26,738 | 8/13 | 0.82 |
| Heavyweight volume surge, BANKNIFTY | 203 | -44 | -191 | -222 | -53,026 | 9/13 | 0.46 |
| Liquidity 15+5 + "skip if BU15 opposes" filter (all 5 indices) | 876 | — | +405 (vs 324 unfiltered) | +81 better at 1.5× too | — | — | 0.035 |

**VSPIKE BANKNIFTY at size (the only positive one):**
- Rs 5,000/day would need about 49 lots.
  - That is about Rs 7.8 lakh of premium. One lot costs about Rs 530 × 30 = Rs 16k.
  - At that size the impact on a thin order book is not modelled.
- With Rs 1 lakh, about 6 lots fit by premium.
  - That is about Rs 600/day if the holdout repeated, which the pre-holdout data does not support.
  - The drawdown would be about -Rs 1.26 lakh (6 × 21k). **That is more than the whole capital.**
- Bootstrap P(losing month) = 46%.

## Rs per point, 1 lot (Boss asked)

| index | lot (in the data, Oct 2026) | Rs per 1 premium point | ≈ Rs per 1 index point (1-ITM, delta ~0.6) |
|---|---|---|---|
| NIFTY | 65 | 65 | ~39 |
| BANKNIFTY | 30 (the exchange schedule in config says 35 from Jun 2025; the data's OI moves say 30) | 30 | ~18 |
| FINNIFTY | 60 | 60 | ~36 |
| MIDCPNIFTY | 120 | 120 | ~72 |
| SENSEX | 20 | 20 | ~12 |

A 20-point premium target on BANKNIFTY is Rs 600 a lot. On MIDCPNIFTY it is Rs 2,400.

The premium-point exits did no better than the percent ones:
- The mean across all triggers was -Rs 106 to -119/day for every exit.
- Point exits made money in only 1-4% of variants. The best percent and time exits managed 6-10%.

## How stops and targets were checked

- **On the wicks.** Every premium stop and target (and ladder lock) is a resting order. It is checked minute by minute
  on the option's own 1-minute HIGH and LOW.
- If both were touched in the same minute, **the stop is assumed to fill first** (the cautious choice).
- Same-minute ties were 5,323 of 1,077,367 stop/target exits (0.49%):
  - BANKNIFTY 0.94% and SENSEX 2.1% (big premiums, tight 10-20 point brackets);
  - NIFTY, FINNIFTY and MIDCP 0.02-0.07%.
- So the tie rule barely moves the results.

## What was tested

Features at minute t use only bars up to t. When comparing to t-L, the strike set is the one fixed at t.

**Option chain (nearest series, ATM±10 minute bars with volume and OI):**
- **BU, OI build-up per strike, ATM±2.**
  - CE long build-up and short covering count as bull. CE short build-up and long unwinding count as bear. PE is the
    mirror.
  - Each is weighted by |ΔOI|. It is measured over 15 minutes and since the open (the shift through the day).
- **DOIPC:** put OI change minus call OI change at ATM±2 and ±5, over 15 minutes.
- **WALL:** the max-OI call strike and put strike both move up (or down) by ≥ 1 or 2 strikes in 30 minutes.
- **VSPIKE:** ATM±2 call (or put) volume in the last 5 minutes against that minute's 20-day median, at ≥ 3× or ≥ 5×.
  It also needs that side's premium up (price up on volume = aggressive buyers) and the other side quieter.
- **VIMB:** call-vs-put volume imbalance at ATM±5, 15 minutes.
- **PFLOW:** premium × volume × sign of the tick (a buyer/seller proxy), calls minus puts.
- BU, DOIPC, WALL, VIMB and PFLOW were each run with the usual sign AND the reversed sign. Writer-vs-buyer readings
  are ambiguous, so both count as variants.

**Constituents** (NSE stock minutes, only from 2024-10-07; BANKNIFTY 12 banks, NIFTY top 12; static approximate weights):
- HVSURGE: heavyweight volume surge with direction.
- VWB: volume-weighted advance/decline.
- VWAP: index-weighted share of stocks above their own VWAP.
- LEADLAG: the basket moves while the index lags.
- These have only ~1 year of choice data, with a single walk-forward fold (train 2024 Q4, test 2025). That is weak,
  but they all lost anyway.

**Triggers:**
- A trigger fires when the condition turns on, between 09:30 and 14:30.
- There is a 30-minute gap between events, and at most 3 trades per day per index per rule.
- Expiry days are skipped.

**Exits**, fixed before any results (18):
- the Liquidity arm's exits;
- -15% / +30%;
- -15% / +30% with the profit-lock ladder;
- 15 / 30 / 60-minute time stops with a -15% stop;
- premium points: targets +15 / +20 / +25 / +30 × stops -10 / -15 / -20 (amendment 1).

**Controls:**
- A same-exit random-entry baseline: 10 random minutes and coin-flip sides on the same days.
- Walk-forward by year.
- BH and Holm over all 2,268 variants; White RC and SPA over the union of runs.
- DSR and PBO.
- A 1.5× spread stress test.

**Costs:**
- h24's measured half-spreads: BANKNIFTY and NIFTY 0.16%, MIDCP 0.21%, FINNIFTY 0.42%.
- SENSEX was not measured; I assumed 0.16%.
- On top of these, app charges. Gross means bar prints with no spread and no charges.

## Diagnostics (before the holdout, no P&L)

15-minute forward-return IC, sign = the usual bull reading:

| feature | NIFTY | BANKNIFTY | FINNIFTY | MIDCP | SENSEX |
|---|---|---|---|---|---|
| DOIPC ±2 | +0.028 (t 4.5) | +0.032 (t 4.5) | +0.007 | +0.004 | +0.023 |
| BU since open | +0.023 (t 3.7) | +0.019 (t 2.8) | +0.011 | +0.027 (t 2.9) | +0.021 |
| PFLOW | +0.011 | +0.009 | +0.010 | +0.018 | +0.021 |
| VIMB | +0.003 | +0.008 | +0.005 | +0.019 | +0.001 |
| VWAP share (constituents) | +0.022 | +0.011 | | | |

Reading:
- Put writing and the day's OI build-up lean slightly in the market's direction over the next 15 minutes.
- But an IC of 0.03 is about 1-2 basis points. That is far less than the 0.3-0.8% round-trip spread plus charges that
  an option buyer pays.
- That is why the IC is significant and the P&L is not.

## Limits

- Only the nearest expiry is in the data, at ATM±10. There is no bid/ask history: the spread is h24's one-day snapshot.
- Constituent weights are static approximations.
- The futures OI is not historical.
- The 2,268 variants are counted honestly. Only variants that made money were tested against random entries; the
  others count as p = 1. 34 had p < 0.05, but after BH none had q < 0.10.
- **Process note:**
  - The 5 option runs ran without the shared lock: the queue was stuck behind other hunters and memory was fine
    (≤ 3 GB used).
  - The constituent run's 7-exit results had been seen before amendment 1. The option runs had not.
