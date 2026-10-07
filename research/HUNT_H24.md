# HUNT h24: real bid/ask vs the spread models, and what it means for the MIDCPNIFTY leg

Code: `research/hunt/h24/`
- `snap.py`: real half-spreads from the option-chain snapshot. It also computes Roll on the same contracts, and Roll
  against volatility and around entries.
- `sim.py build` / `sim.py an`: re-prices h17/h23's base plan under several spread models. It reuses h23's
  `build.py` and `run.py` unchanged.

Logs and CSVs are in `scratchpad/hunt/h24/`: `snap.log`, `snap_rows.csv`, `snap_summary.csv`, `roll_fresh.csv`,
`roll_vol.csv`, `roll_signal.csv`, `build.log`, `an.log`, `results.csv`, `hold_months.csv`, `calib.csv` and
`trades24.parquet`.

**Part 2 is a POST-HOC SENSITIVITY.** It uses holdout data to calibrate a cost model. No rule was changed or chosen
here. All trades are option buying.

## Verdict

1. **The real spread sits between the two models.** At about 11:03 IST on 2026-10-06, the real half-spread of
   1-ITM/ATM nearest-monthly contracts was:

   | index | real half-spread |
   |---|---|
   | BANKNIFTY | 0.16% |
   | MIDCPNIFTY | 0.21% |
   | FINNIFTY | 0.42% (0.13-0.64%) |
   | NIFTY | 0.16% |

   - **h17's model is too cheap by about 3×.** It assumes about 0.06-0.07%.
   - **h23's Roll is about right for BANKNIFTY on average** (0.18% over the holdout trades).
   - **For MIDCP, h23's Roll is about 1.5× too high on average** (0.33%).
   - Roll is also very noisy. Month to month it jumps between 0% and 0.5%. In its high months it blocks fills that
     the real book would have given.
   - **The evidence is one moment of one day.** The snapshot was effectively a single moment (11:02-11:06 IST), and
     BANKNIFTY was very calm that day.
2. **With the real spread level (post-hoc), the base plan BN1+M1 makes money in the holdout.**
   - +Rs 198/day net at κ 0.02 and +Rs 56/day at κ 0.04. Gross is Rs 682/day.
   - For comparison: h17 +292/day, h23 -223/day.
   - Pre-holdout: +Rs 79/day at κ 0.02 and +Rs 47/day at κ 0.04.
3. **Almost all of that profit comes from BANKNIFTY. MIDCP adds about zero on average and doubles the drawdown.**

   | | Rs/day, real spread, κ .02 | Rs/day, real spread, κ .04 |
   |---|---|---|
   | BN 1 lot alone, holdout | +167 | +143 |
   | BN 1 lot alone, pre-holdout | +65 | +62 |
   | MIDCP alone, holdout | +29 | -93 |
   | MIDCP alone, pre-holdout | +14 | -15 |

   - Max drawdown: BN alone -32k; BN1+M1 -83k.
4. **MIDCP's result is a coin-flip on 3-5 runaway trades per year.**
   - Holdout (h17 pricing): the top 5 MIDCP trades made +75k, and the other 225 lost -56k.
   - Those winners fill only if the half-spread at the signal is about ≤ 0.3%. At 1.5× the real spread (0.32%) they
     still fill. At 2× (0.43%) they all miss, and MIDCP loses -Rs 328/day.
5. **BANKNIFTY is robust to the spread.**
   - At 2× the real spread, it still makes +Rs 96/day in the holdout and +Rs 39/day pre-holdout (κ 0.02), with no
     missed fills.
6. **Recommendation:**
   - Trade **BANKNIFTY 1 lot alone** with real money.
   - Keep MIDCPNIFTY **on paper only**. Have the app log the bid and ask at every signal.
   - After about 30 MIDCP signals, decide with the pre-committed rule below.
   - Rs 5,000/day at Rs 1 lakh: still **NO**.

## 1. Real half-spreads (Dhan option chain, 2026-10-06)

About the snapshot:
- There is one snapshot per expiry, all taken between 11:02 and 11:06 IST. The "6 / 3 / 3 / 18 snapshots" are
  different expiries, not different times.
- **So the spread cannot be measured across times of day.** There is one time on one day.
- Half-spread means (ask - bid) / 2 / mid.

Nearest monthly (27 Oct 2026), half-spread in % of mid:

| index | time IST | 1-ITM (CE, PE) | ATM (CE, PE) | 1-ITM+ATM median | ITM2..OTM2 range | premium Rs | spread in ticks (median) | top-of-book qty | h17 model |
|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 11:02 | 0.15, 0.17 | 0.15, 0.17 | **0.16** | 0.11-0.17 | 713-984 | 54 | 1-5 lots | 0.056 |
| MIDCPNIFTY | 11:04 | 0.19, 0.27 | 0.22, 0.21 | **0.21** | 0.19-0.36 | 194-275 | 22 | 1-4 lots | 0.072 |
| FINNIFTY | 11:03 | 0.64, 0.48 | 0.35, 0.13 | **0.42** | 0.13-1.26 | 337-481 | 67 | 1-7 lots | 0.062 |
| NIFTY | 11:06 | 0.16, 0.19 | 0.08, 0.16 | **0.16** | 0.05-0.24 | 250-363 | 18 | 1-11 lots | 0.067 |

Notes:
- Next-month contracts are wider. The 1-ITM+ATM median is 0.37% for BANKNIFTY and 0.25% for NIFTY.
- The NIFTY next weekly is 0.13%.
- How calm was the day? The index range up to the data's end was:

  | index | day's range | holdout percentile |
  |---|---|---|
  | BANKNIFTY | 0.38% | 3rd (very calm) |
  | MIDCP | 1.18% | 60th (normal) |
  | FIN | 0.74% | 28th |

### The same contracts under each model (half-spread, % of premium)

| | BANKNIFTY | MIDCPNIFTY | FINNIFTY |
|---|---|---|---|
| **Real (snapshot)** | **0.16** | **0.21** | **0.42** |
| h17 model (5 bps + 1 tick) | 0.06 | 0.07 | 0.06 |
| h23 Roll used point-in-time on 2026-10-06 (Sep-26 estimate) | 0.00 (falls back to h17) | 0.21 | 0.79 |
| h23 Roll, Oct-26 month | 0.00 | 0.00 | 0.42 |
| h23 Roll, mean over holdout trades | 0.18 | 0.33 | n/a |
| h23 Roll, monthly mean, holdout / pre-holdout | 0.14 / 0.16 | 0.29 / 0.45 | 0.46 / 0.32 |
| Fresh Roll, these 4 contracts, 2026-10-06 whole day | 0.37 | 0.48 | 0.66 |
| Fresh Roll, these contracts, 10:30-11:40 | 0.47 | 0.57 | 0.65 |
| Fresh Roll, all 1-ITM/ATM strikes, Sep 2026 | 0.00 | 1.54 | 0.00 |

Reading:
- **h17 understates every index by about 0.1-0.35 percentage points.**
- **h23's Roll gets the average level right for BANKNIFTY and is about 1.5× high for MIDCP.**
- Single-day or single-month Roll values range from 0 to 1.5%. **Roll on minute bars is a noisy level estimate, not a
  per-month measurement.**

### Does the spread widen at signal time? (Roll can't say)

- **Roll by 15-minute index-move quintile** (holdout, 1-ITM/ATM monthly strikes):
  - BANKNIFTY: 0.77, 0.49, 0.55, 0, 0 %.
  - MIDCP: 0.95, 0.78, 0.96, 0.49, 1.31 %.
  - FIN: 1.58, 1.56, 0, 0.99, 0 %.
  - There is no pattern. In fast windows, option returns trend, which pushes the Roll covariance positive (to 0).
  - **Roll cannot measure widening at breakouts. It is biased toward zero exactly then.**
- **Around h23's own holdout entries** (entry -5 to +15 minutes, the 1-ITM contract), compared with the same contract's
  whole day:

  | index | around entry | whole day |
  |---|---|---|
  | MIDCP | 0.31% | 0.33% |
  | FIN | 0.36% | 0.38% |
  | BN | 0% | 0.15% |

  - So there is no sign of widening, but for the reason above this does not bound it.
- **Conclusion: the signal-time spread is unknown.** It may be up to about 2× the calm level, and the data here can
  neither confirm nor rule that out. Hence the 1.5× and 2× stress below.

## 2. Re-run with a calibrated spread (POST-HOC sensitivity)

Setup:
- The rules, data and execution are exactly h23's:
  - "prints only" data;
  - h14 limit at +0.5% resting 3 minutes;
  - h10/M2 impact;
  - the "no marketable fill if hs > 0.5%" rule;
  - app charges;
  - Rs 1 lakh, fixed 1 lot, free-cash check.
- Only the half-spread changes. It is added to every aggressive buy and sell.
- Validated: `sim.py` reproduces h23's `trades.parquet` (Roll) and `trades_hs0.parquet` (h17 model) to the rupee:
  Rs 6,145 and Rs 182,536 over 1,616 trades.

The spread models:

| model | half-spread used |
|---|---|
| h17 | 0: 5 bps + 1-4 ticks only |
| h23roll | previous month's Roll |
| **flat ×1** | the snapshot level, constant: BN 0.162%, MIDCP 0.213% |
| flat ×1.5 / ×2 | breakout-widening stress: BN 0.24 / 0.32%, MIDCP 0.32 / 0.43% |
| rollk ×1 | Roll × k, with k scaled so that the holdout-trade mean equals the snapshot: k = 0.90 for BN, 0.66 for MIDCP |
| rollk ×1.5 / ×2 | the same, stressed |

How to read the tables:
- "missed" means signals where the limit did not fill.
- "skip" means signals skipped because there was not enough free cash.
- "green" means green months.

### HOLDOUT (2025-10-01 .. 2026-10-05, 249 days)

| model | κ | book | net Rs/day | gross Rs/day | max DD | worst day | green | missed / signals | skip | end cap |
|---|---|---|---|---|---|---|---|---|---|---|
| h17 | .02 | BN1+M1 | 292 | 701 | -76.7k | -10.2k | 7/13 | 3/478 | 1 | 1.73 L |
| h23roll | .02 | BN1+M1 | -223 | 213 | -106.5k | -7.8k | 4/13 | 29/478 | 24 | 0.44 L |
| **flat ×1** | .02 | **BN1+M1** | **198** | 682 | -82.6k | -10.6k | 6/13 | 10/478 | 1 | 1.49 L |
| **flat ×1** | .04 | BN1+M1 | **56** | 588 | -91.9k | -11.2k | 5/13 | 28/478 | 2 | 1.14 L |
| flat ×1.5 | .02 | BN1+M1 | 140 | 651 | -86.0k | -10.8k | 5/13 | 23/478 | 1 | 1.35 L |
| flat ×1.5 | .04 | BN1+M1 | -162 | 376 | -112.8k | -10.4k | 4/13 | 44/478 | 6 | 0.60 L |
| flat ×2 | .02 | BN1+M1 | -294 | 145 | -110.1k | -7.5k | 2/13 | 55/478 | 37 | 0.27 L |
| flat ×2 | .04 | BN1+M1 | -295 | 102 | -106.9k | -7.2k | 3/13 | 59/478 | 111 | 0.26 L |
| rollk ×1 | .02 | BN1+M1 | 192 | 677 | -83.3k | -10.7k | 6/13 | 10/478 | 1 | 1.48 L |
| rollk ×1 | .04 | BN1+M1 | -8 | 509 | -108.1k | -10.2k | 4/13 | 32/478 | 4 | 0.98 L |
| rollk ×1.5 | .02 | BN1+M1 | -241 | 169 | -91.0k | -6.9k | 4/13 | 35/478 | 47 | 0.40 L |
| rollk ×2 | .02 | BN1+M1 | -276 | 113 | -82.4k | -7.1k | 3/13 | 55/478 | 58 | 0.31 L |
| h17 | .02 | BN1 | 214 | 362 | -27.9k | -7.1k | 9/13 | 0/248 | 0 | 1.53 L |
| h23roll | .02 | BN1 | 156 | 359 | -31.4k | -7.1k | 8/13 | 1/248 | 0 | 1.39 L |
| **flat ×1** | .02 | **BN1** | **167** | 362 | **-32.5k** | -7.2k | 8/13 | 0/248 | 0 | 1.42 L |
| **flat ×1** | .04 | BN1 | **143** | 362 | -35.0k | -7.3k | 8/13 | 0/248 | 0 | 1.36 L |
| flat ×1.5 | .02 / .04 | BN1 | 132 / 108 | 362 / 360 | -35.9k / -38.4k | -7.3k / -7.4k | 8/13 | 0/248 | 0 | 1.33 / 1.27 L |
| flat ×2 | .02 / .04 | BN1 | 96 / 73 | 362 / 356 | -39.4k / -41.7k | -7.4k / -7.5k | 8 / 7 of 13 | 0/248 | 0 | 1.24 / 1.18 L |
| rollk ×1 | .02 / .04 | BN1 | 164 / 139 | 362 / 358 | -30.7k / -33.5k | -7.1k / -7.2k | 9 / 8 of 13 | 0-1/248 | 0 | 1.41 / 1.35 L |
| rollk ×2 | .02 / .04 | BN1 | -13 / -36 | 186 / 171 | -36.9k / -39.4k | -7.1k / -7.2k | 6/13 | 18-22/248 | 0 | 0.97 / 0.91 L |
| flat ×1 | .02 / .04 | M1 alone | 29 / -93 | 320 / 223 | -61.7k / -80.7k | -7.1k / -6.2k | 6 / 5 of 13 | 10 / 28 of 230 | 0 | 1.07 / 0.77 L |
| flat ×1.5 | .02 / .04 | M1 alone | 6 / -255 | 289 / 49 | -64.2k / -92.0k | -6.0k / -6.3k | 6 / 4 of 13 | 23 / 44 of 230 | 0-1 | 1.02 / 0.36 L |
| flat ×2 | .02 / .04 | M1 alone | -328 / -334 | -117 / -133 | -85.1k / -86.3k | -6.1k / -6.5k | 3 / 2 of 13 | 55 / 59 of 230 | 12-40 | 0.18 / 0.17 L |

Notes:
- The "rollk ×2" BN loss comes from Roll's noisy months. Doubled, they exceed the 0.5% limit buffer and block 18-22
  fills. For BN the flat stress is the more realistic one, because the real BN book is steady and liquid.
- Holdout BN1+M1 monthly net at flat ×1, κ .02, in Rs:

  | month | net |
  |---|---|
  | Oct | +0.3k |
  | Nov | -6.6k |
  | Dec | -0.6k |
  | Jan | +34.0k |
  | Feb | +42.2k |
  | Mar | +7.0k |
  | Apr | -44.1k |
  | May | -17.9k |
  | Jun | -3.2k |
  | Jul | +18.0k |
  | Aug | -3.9k |
  | Sep | +27.3k |
  | Oct | -3.1k |

- The Feb 2026 line shows how binary the model choice is. It is +42k under flat ×1, -10.7k under h23roll and -8.8k
  under flat ×2. The difference is whether MIDCP's 1 Feb and 13 Feb runners filled.

### Pre-holdout (2021-10-01 .. 2025-09-30)

| model | κ | BN1+M1 net / gross Rs/day | BN1+M1 max DD | BN1+M1 green | BN1+M1 missed | BN1 net / gross | BN1 max DD | BN1 green | M1 alone net |
|---|---|---|---|---|---|---|---|---|---|
| h17 | .02 | 111 / 239 | -39.9k | 23/48 | 2 | 82 / 152 | -35.5k | 26/48 | 29 |
| h23roll | .02 | 44 / 190 | -52.4k | 21/48 | 68 | 53 / 137 | -40.7k | 23/48 | -9 |
| **flat ×1** | .02 | **79 / 236** | -47.8k | 23/48 | 4 | **65 / 152** | -39.9k | 25/48 | 14 |
| **flat ×1** | .04 | **47 / 209** | -48.1k | 22/48 | 19 | **62 / 152** | -40.4k | 25/48 | -15 |
| flat ×1.5 | .02 / .04 | 52 / 17 | -53.7k / -54.3k | 22 / 21 | 9 / 31 | 52 / 48 | -43.5k / -44.1k | 25 / 24 | 0 / -32 |
| flat ×2 | .02 / .04 | -32 / -47 | -67.0k / -67.5k | 20 / 19 | 52 / 71 | 39 / 35 | -47.1k / -47.7k | 24 / 23 | -65 / -71 |
| rollk ×1 | .02 / .04 | 69 / 57 | -47.3k / -47.7k | 22 | 30 / 37 | 68 / 65 | -40.0k / -40.6k | 24 / 23 | 1 / -8 |
| rollk ×2 | .02 / .04 | -22 / -34 | -82.7k / -83.9k | 19 | 98 / 101 | 15 / 11 | -62.8k / -63.4k | 18 | -34 / -40 |

Notes:
- BN alone has no missed fills under any flat model.
- Even before the holdout, MIDCP's whole contribution rests on a few runners. The top 5 trades made +59k; the total
  was +29k under h17.

## 3. Plain recommendation

- **MIDCP leg: do not trade it with real money now. Keep it on paper only.**
  - At the real calm spread it adds roughly +Rs 15-30/day at κ 0.02 and loses at κ 0.04.
  - It doubles the drawdown: -83k against -32k for BN alone.
  - It turns negative if the spread at the signal is about 0.3% or more.
  - Its P&L is 3-5 runaway trades a year that fill or don't. That depends on exactly the number we cannot see in the
    data: the bid and ask at the breakout.
- **Real money: BANKNIFTY 1 lot alone** (Liquidity 15+5, 1-ITM nearest monthly, limit at +0.5% for 3 minutes, no
  chase).
  - Under the real spread: +Rs 167/day in the holdout and +Rs 65/day pre-holdout (κ 0.02), max drawdown about Rs 32-40k.
  - At 2× the spread it is still +96 / +39.
  - This remains a post-hoc choice: h17 pre-registered BN1+M1.
- **What the app must log live, for every signal of every index (paper or real):**
  1. **The chosen contract:** symbol, strike, expiry and lot size.
  2. **Signal moment:** timestamp to the millisecond; best bid, best ask, bid and ask quantity (better: 5-level depth);
     LTP and last-trade time; index LTP.
  3. **Order placement:** the same quote again, plus the limit price.
  4. **Every fill:** price, quantity and time. Also whether the order filled within the 3 minutes and how much. For a
     missed order, how far the ask ran.
  5. **The exit order:** the same fields (quote at the exit decision, fill price and time). Stop exits matter most.
  6. **A calm baseline:** the same contract's bid and ask every 15 minutes through the day, to measure the widening
     at signals directly.
- **Pre-committed decision rule for MIDCP, after ≥ 30 MIDCP signals (about 1.5-2 months):**
  - **Keep MIDCP (1 lot) only if both hold:**
    - the median signal-time half-spread is ≤ 0.25%;
    - the +0.5% limit filled on ≥ 90% of signals, including the ones where price ran.
  - **Drop it for good if either holds:**
    - the median is ≥ 0.30%;
    - the fill rate is < 85%.
  - In between: keep paper-trading.
  - Apply the same check to BANKNIFTY. Expect about 0.15-0.2%. Worry above 0.3%.

## Honesty notes

- **One day, one moment.** All real spread numbers come from a single snapshot (11:02-11:06 IST, 2026-10-06), with
  one or two contracts per moneyness. BANKNIFTY was unusually calm that day. Spreads in 2021-24 were likely wider than
  in 2026, so the flat model may flatter the pre-holdout years. The rollk model keeps Roll's era shape.
- **Calibration uses holdout information** (k is matched to the holdout-trade mean). Everything in Part 2 is a
  post-hoc sensitivity. It is not a new test.
- **The impact model is inherited** (h10, κ). It adds sqrt impact on top of the spread even for 1 lot, while the real
  book showed 1-4 lots at the touch. This is why MIDCP's runners miss once the spread is about 0.3% or more, while a
  1-lot order hitting the ask would still fill below 0.5%. Read the MIDCP stress rows as pessimistic on fills,
  optimistic on nothing.
- 8 spread models × 2 κ × 3 books × 2 windows were run, and all are shown (`results.csv`).
- Rs 5,000/day at Rs 1 lakh: **NO**. The realistic figure is about Rs 100-200/day with BN 1 lot.
