# HUNT h32: working backwards from big option jumps

Code is in `research/hunt/h32/`:
- `build.py`: features and labels.
- `es.py`: the event study.
- `sel.py`: rule choice, on discovery data only.
- `fwd.py`: the forward test.
- `ana.py`: the statistics.
- `hold_ana.py`: the holdout check.
- `PREREG.md`: written before any forward P&L.

Logs and CSVs are in `scratchpad/hunt/h32/` (`es.log`, `sel.log`, `fwd.log`, `ana.log`, `hold.log`, `*.csv`). The feature/label rows (~340 MB) and the random-entry pools were deleted to save disk; `build.py` rebuilds the rows in about 8 minutes.

Option buying only. Points are absolute **premium rupees**. Every big-move check and every premium target/stop uses
the option's own 1-minute **HIGH/LOW (the wicks), not the close**. When target and stop fall in the same minute, the
stop is counted first. Ties are rare: +20/-15 within 30 min had 6,552 of 1.93 M resolved rows (0.23% of all rows);
+15/-10 had 17,393 (0.62%).

## Verdict

**NO. You cannot see a 20-point jump coming early enough, or reliably enough, to pay for it.**

1. **Jumps are common, but drops are more common.**
   - Bought at a random minute, an ATM or 1-ITM nearest-expiry option reaches +20 before -15 within 15 minutes:
     - BANKNIFTY 35% of the time, SENSEX 35%.
     - FINNIFTY 16%, NIFTY 12%, MIDCPNIFTY 9%.
   - It reaches -15 first even more often.
   - Of the trades that resolve, the jump comes first only 38-40% of the time. Break-even is 43%.
   - So the average entry loses, even before costs.
2. **Looking backwards gives a false picture.**
   - Just before a jump "starts", the option usually had been **falling** for 5-15 minutes and the index had moved
     against it.
   - That is an artefact of how a start is defined. The same look-back finds the mirror image before drops.
   - It is the classic survivorship / look-ahead trap. It is not a signal.
3. **Looking forward, the honest question, every precursor is weak.**
   - Any precursor that makes a jump more likely makes a drop almost equally more likely.
   - Momentum, volatility, OI and premium features all behave this way.
   - The best rules raise P(jump) by 1.2-1.5x. They also raise P(drop) by 1.15-1.3x.
   - The share of jumps among resolved trades moves from 39.4% to 40.6-42.5%. That is still below the 43% break-even
     before costs.
4. **Forward test: 9 rules x 10 exits = 90 variants. All 90 lose money net.**
   - The test period is 2024 to Sep 2025, which was never used for choosing.
   - Best variant net+spread: **-Rs 725/day** for the basket at 1 lot per index.
   - Gross: the best variant made only **+Rs 383/day**. Hansen SPA on gross p = 0.87, so this is noise.
   - Random-entry baseline: the rules are no better than random (p = 1.0 for every variant).
   - BH q = 1, SPA net p = 1.0.
   - Walk-forward by year: -Rs 7.65 lakh net+spread over 2022-2025.
5. **Holdout** (Oct 2025-Oct 2026), run once.
   - No rule passed the gates, so this is a descriptive check of the single best pre-holdout variant.
   - Gross: -Rs 123/day.
   - Net: -Rs 387/day.
   - Net+spread: **-Rs 546/day**. Max drawdown -Rs 2.1 lakh.
6. **Rs 5,000/day:** not reachable. Every variant has a negative expectation, so more lots only lose faster.

## What typically happens just before an option jumps 20 points (the plain picture for Boss)

| what you would see in the 5-60 min before | how it looks |
|---|---|
| **The clock** | Jumps cluster at the open (09:15-10:00: 22-26% of entry minutes) and after 13:30 (19-21%). They are rarest at 11:00-12:30 (15-17%). Drops cluster at the same times, even more strongly (24-38%). These are simply the busy times. |
| **Expiry day** | Slightly more jumps (20% vs 17-18%), and also more drops (30% vs 28%). |
| **Index speed** | The index's 5-15-minute range runs about 15% above normal before a jump (median 1.0x vs 0.86x normal). Drops show the same. Fast markets move options both ways. |
| **Option chart, at the "start" of the move** | Down about 5% over the last 5 minutes and down about 7% over 15 minutes. The opposite option is up about 6%. Before drops it is the mirror image: up about 5%, opposite down about 8%. This is the look-back artefact, not a signal. |
| **Option chart, at every minute that is followed by a jump** | It looks almost normal. It is slightly more likely to be already rising (top fifth of 15-minute gain) and already well off its day low. Those same minutes are followed by drops almost as often. |
| **OI** | Before CE jumps, a little more put writing near ATM, and for any option, a little more writing on the opposite side. Weak (lift 1.3-1.5) and matched by drops (lift 1.2-1.3). |
| **Premium level** | Expensive options (BANKNIFTY / SENSEX at Rs 300-400) reach +20 easily. That is just size, and the -15 comes just as easily. |
| **Speed of the move** | The median time to +20 is 6 minutes. BANKNIFTY takes 4 (62% within 5 minutes). NIFTY / MIDCP take 9. From +15 to +20 takes a median of 1 minute. |

**Can it be seen in time?** No.
- The moves are fast. A quarter reach +20 within 3 minutes.
- When a jump is obvious on the chart, most of it is gone.
- What you can see beforehand (a busy market, a fast index, a rising option, OI building) appears just as often
  before the option falls 15.

## 1. Base rates: how often an option jumps (all pre-holdout data, 2020 to Sep 2025)

These are entries at the next open after a decision every 2 minutes, 09:20-14:50. Contracts are ATM and 1-ITM, CE and
PE, nearest expiry. The table shows +U before -15 within 15 minutes (P jump / P drop first).

| target | BANKNIFTY | SENSEX | FINNIFTY | NIFTY | MIDCPNIFTY | break-even share |
|---|---|---|---|---|---|---|
| +15 / -15 | 43.4 / 48.3 | 42.9 / 49.4 | 23.2 / 23.9 | 19.3 / 19.4 | 14.7 / 13.3 | 50% |
| +20 / -15 | 34.8 / 52.8 | 35.2 / 53.9 | 15.8 / 24.6 | 12.1 / 19.9 | 9.0 / 13.6 | 43% |
| +25 / -15 | 28.2 / 55.6 | 29.1 / 56.9 | 11.2 / 25.0 | 7.9 / 20.1 | 5.8 / 13.8 | 37.5% |
| +30 / -15 | 23.0 / 57.5 | 24.4 / 58.9 | 8.1 / 25.2 | 5.3 / 20.2 | 3.8 / 13.8 | 33% |
| jump share of resolved, +20/-15 | 39.8% | 39.5% | 39.0% | 37.8% | 39.8% | 43% |

- All 24 definitions (+15/20/25/30 x 5/15/30 min x stop 10/15) per index are in `es_base.csv`. Every one has a jump
  share below break-even.
- The median premium is BANKNIFTY Rs 270-355, SENSEX Rs 304-404, FINNIFTY Rs 110-154, NIFTY Rs 84-122 and
  MIDCP Rs 85-109.
- Rupees per premium point per lot (today's lots):

  | index | lot | Rs per point per lot |
  |---|---|---|
  | NIFTY | 65 | Rs 65 |
  | BANKNIFTY | 35 | Rs 35 |
  | FINNIFTY | 60 | Rs 60 |
  | MIDCPNIFTY | 120 | Rs 120 |
  | SENSEX | 20 | Rs 20 |

  A 20-point jump is worth Rs 400 (SENSEX) to Rs 2,400 (MIDCP) per lot.

## 2. Event study (discovery 2020-2023 only)

- The primary event is +20 before -15 within 15 minutes:
  - 248,127 entry rows had an event;
  - 40,923 jump onsets (no event in the previous 10 minutes);
  - 44,703 drop onsets.
- 32 precursor features (index chart, the option's own chart, the opposite option, OI on both sides, VIX, straddle,
  clock, days to expiry).
- Controls:
  - time-matched: same index, same contract type, same 30-minute slot, same quarter;
  - day-matched: same index, day and contract type.
- The score is the mean percentile at the event: 0.5 = no information.

| feature | before jump ONSETS | before drop ONSETS | before ALL jump minutes | before ALL drop minutes |
|---|---|---|---|---|
| option change 5 min | **0.27** | **0.73** | 0.506 | 0.504 |
| index move 5 min in favour | 0.28 | 0.72 | 0.506 | 0.504 |
| opposite option change 15 min | 0.67 | 0.33 | 0.484 | 0.496 |
| index range speed 15 min | 0.55 | 0.55 | 0.548 | 0.540 |
| index range speed 5 min | 0.56 | 0.57 | 0.543 | 0.534 |
| 30-min box height | 0.53 | 0.52 | 0.543 | 0.537 |
| option % above its day low | 0.42 | 0.60 | 0.540 | 0.522 |
| put-minus-call OI added, in favour | 0.47 | 0.55 | 0.526 | 0.502 |
| VIX | 0.53 | 0.52 | 0.531 | 0.532 |

- The onset columns look dramatic, but they mirror each other. That is the selection artefact.
- The "all minutes" columns are the honest forward view. Nothing is above 0.55, and the drop column tracks the jump
  column.
- By year the ranking is stable from 2021 to 2025 (`es_years.csv`), and so is its weakness.

## 3. Forward rules (chosen on 2020-2023, written into PREREG.md before any P&L)

- 65 single conditions were screened: 32 features x top/bottom fifth, plus expiry.
- 15 pairs were then screened.
- 9 rules were picked by (lift of jumps - lift of drops).
- Precision and recall on 2024 + Jan-Sep 2025 (never used for choosing):

| rule | condition (in the option's favour) | precision P(jump) | P(drop) | recall | lift jump | lift drop | jump share (base 39.4%, BE 42.9%) |
|---|---|---|---|---|---|---|---|
| R1 | put-minus-call OI added, top fifth | 24.5% | 33.9% | 17% | 1.29 | 1.16 | 41.9% |
| R2 | option far above its day low | 28.8% | 42.1% | 19% | 1.17 | 1.13 | 40.6% |
| R3 | option up most over 15 min | 28.9% | 41.6% | 20% | 1.23 | 1.16 | 40.9% |
| R4 | index up most over 60 min | 28.9% | 41.5% | 26% | 1.25 | 1.17 | 41.1% |
| R5 | opposite option's OI rising most | 26.2% | 37.5% | 18% | 1.36 | 1.27 | 41.1% |
| R6 | index up most over 30 min | 28.9% | 41.8% | 26% | 1.25 | 1.18 | 40.8% |
| R7 | R2 & R6 | 32.0% | 46.4% | 9% | 1.29 | 1.24 | 40.8% |
| R8 | R2 & R5 | 29.3% | 39.7% | 5% | 1.48 | 1.32 | 42.5% |
| R9 | R2 & R3 | 31.0% | 45.4% | 8% | 1.26 | 1.21 | 40.6% |

Base rates in the forward period: P(jump) 23.5%, P(drop) 36.1%.

Every rule raises jumps a little and drops nearly as much. None gets the jump share above the gross break-even.

Rules R1, R5 and R8 skip SENSEX: its options have no usable OI in the data.

## 4. Forward P&L (2024 to Sep 2025, 435 trading days)

The basket is all 5 indices, 1 lot each, one position per index, max 3 entries per index per day.
- Fills: obuy app fills (±5 bps, stops -10 bps).
- Charges: Costs('app').
- Spread: plus the h24 real half-spreads (BN/NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%).
- Stress: 1.5x spread.

| variant (best by net+spread) | trades | Rs/day gross | Rs/day net app | Rs/day net+spread | Rs/day stress | random-entry mean/trade vs rule | BH q |
|---|---|---|---|---|---|---|---|
| R8 + time stop 30 min | 2,573 | -204 | -568 | **-725** | -803 | -105 vs -123 | 1.0 |
| R8 + target 30 / stop 15 | 2,779 | -170 | -566 | -741 | -829 | -118 vs -116 | 1.0 |
| R8 + 20/20 points | 2,785 | -181 | -578 | -753 | -841 | -141 vs -118 | 1.0 |
| best GROSS: R3 + time stop 15 min | 6,351 | **+383** | -631 | -1,281 | -1,606 | -135 vs -88 | 1.0 |
| R1 + profit-lock ladder | 4,497 | +318 | -404 | -873 | -1,107 | -194 vs -84 | 1.0 |

All 90 variants are in `ana_variants.csv`. All 90 are negative net and net+spread, in both forward years.

| test | result |
|---|---|
| Random-entry baseline (same exits, same days) | p = 1.0 for all 90 |
| BH q / Holm | 1.0 |
| Hansen SPA / White RC, net+spread | 1.0 / 1.0 |
| Hansen SPA / White RC, gross | 0.87 / 0.76 |
| Walk-forward by year | 2022 -89k, 2023 -93k, 2024 -161k, 2025 (to Sep) -421k; total -7.65 lakh net+spread (gross +4.6k) |
| Gates passed | 0 of 90 |

Risk numbers, forward period, net+spread:

| variant | max drawdown | worst day | worst month | P(losing month), bootstrap |
|---|---|---|---|---|
| R8 + T30 | -3.4 lakh | -22k | -77k | 78% |
| R3 + T15 | -5.9 lakh | | | 79% |

Per index, the best variant (R8 + T30) lost net+spread on every index:
- BANKNIFTY -62k
- FINNIFTY -55k
- MIDCP -87k
- NIFTY -111k

## 5. Holdout (1 Oct 2025 - 8 Oct 2026, 250 days), run once

This is a descriptive check of the single best pre-holdout variant, R8 + 30-min time stop. Nothing passed the gates.

| | gross | net app | net + spread | stress 1.5x |
|---|---|---|---|---|
| Total, 914 trades | -30,859 | -96,769 | -136,472 | -156,323 |
| Rs/day | -123 | -387 | **-546** | -625 |
| Rs/trade | -34 | -106 | -149 | -171 |

- Win rate: 44%.
- Max drawdown: -Rs 2.1 lakh. Worst day: -Rs 34.7k.
- 7 of 13 months lost.
- R8 label check in the holdout: lift of jumps 1.38 and lift of drops 1.38. Exactly cancelling.

## 6. Rs 5,000/day?

- At 1 lot per index, the best rule loses about Rs 550-725/day after costs.
- No number of lots turns a negative expectation positive.
- Capital and margin figures are not meaningful for a losing rule.
- **NO.**

## Honest count

| what | count |
|---|---|
| Event definitions | 24 (base rates, per index) |
| Precursor features | 32 |
| Single conditions screened | 65 |
| Pair conditions screened | 15 |
| Rules tested forward | 9 |
| Exits | 10 |
| Forward variants | 90 |
| Holdout runs | 1 |
| Post-hoc changes | none |

Implementation notes:
- Signals are spaced at least 10 minutes apart, with at most 8 a day per index before the position filter. This
  approximates "take the next signal once flat".
- Discovery onsets are approximate at 2-minute resolution.
