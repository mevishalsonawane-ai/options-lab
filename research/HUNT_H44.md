# HUNT h44: meta-labelling Liquidity 15+5 (skip the bad trades, double the best)

Written 8 Oct 2026. Option BUYING only. Fixed lots, never compounding. The app's Liquidity 15+5 entries and exits are
unchanged. The question is only which of its trades to skip, and which to take with a second lot.

- Plan, written before any model was fitted: `research/hunt/h44/PREREG.md`.
  - It has one amendment, made before any fit: features are winsorised at the training 1st/99th percentiles.
- Code: `research/hunt/h44/`.
  - `trades.py` builds the trade table.
  - `feats.py` builds the signal-time features.
  - `model.py` runs the nested walk-forward, SPA, importances and the holdout.
- Logs and tables: `scratchpad/hunt/h44/`.
  - Logs: `trades.log`, `feats.log`, `pre.log`, `hold.log`.
  - Tables: `wf_summary.csv`, `wf_variants.csv`, `wf_walk1L.csv`, `holdout.csv`, `hold_walk1L.csv`, `imp_lr.csv`,
    `imp_gb.csv`, `choice.json`.
- The locked holdout (1 Oct 2025 to 5 Oct 2026) was run once, for the configuration chosen beforehand. A flag file
  blocks any rerun.

## Verdict (read this first)

1. **NOT ADOPTED.** The meta-label filter failed its pre-registered bar before the holdout.
   - Over the 15 model × threshold variants: Hansen SPA p = 0.50 and White RC p = 0.72. The bar was SPA ≤ 0.10.
   - The best random-skip BH q was 0.32.
   - Out of sample, the models barely rank trades. Test-year AUC was 0.46-0.51 in 2022-23 and 0.50-0.61 in 2024-25.
   - The models disagree with each other, and the important features change from year to year.
2. **The pre-chosen filter was RIDGE expected-return, skipping the bottom 33%.** It looked good on the
   three-index book in the holdout, but **it made plain BANKNIFTY (the one plan we trade) slightly worse.**

   | 1 lot per index, κ 0.02, net Rs/day | plain Liquidity | filtered | difference |
   |---|---|---|---|
   | BN+FIN+MIDCP, nested walk-forward 2022-01 to 2025-09 | 96 | 127 | **+31** |
   | BN+FIN+MIDCP, **holdout** | 126 | 318 | **+191** (random-skip p 0.017) |
   | BANKNIFTY alone, nested walk-forward | 81 | 109 | +28 |
   | BANKNIFTY alone, **holdout** | **167** | **149** | **-19** |
   | all 5 indices, holdout (flagged) | 100 | 220 | +119 |

   - The three-index holdout gain comes from skipping FINNIFTY and MIDCPNIFTY trades: +210/day on those two together.
     Those are the costly, thin indices.
   - It hurt BANKNIFTY (-19/day) and NIFTY+SENSEX (about -72/day).
   - A model that "learns" to skip trades on the expensive indices is not the same as one that reads the trade, and it
     did nothing reliable for BANKNIFTY.
3. **Does it beat plain Liquidity out of sample? Only on a book we would not trade.**
   - On the three-index book: +Rs 31/day in walk-forward and +Rs 191/day in the holdout. That is 1 lot each of BN, FIN
     and MIDCP, at κ 0.02 and real spreads.
   - On BANKNIFTY 1 lot (h36 Plan A): +28/day in walk-forward and **-19/day in the holdout**.
   - The rule failed SPA, so **this is not an edge we can claim.** At best it is a hint that FIN/MIDCP trades with a
     low expected return should be skipped.
4. **(b) Second lot on top-score trades (RISK-ADDING, needs Boss's approval; not recommended).**
   - Holdout, BN, Rs 1 lakh walk: +215/day against 167 plain.
     - Max drawdown goes from -32k to -50k.
     - Worst day goes from -7.2k to -12.6k.
   - Holdout, three-index book: +384/day against 63 plain in the walk.
     - Max drawdown is **-1.10 lakh**, more than the starting capital measured peak to trough.
     - Worst day is -16k.
   - Part of the gain is not skill. Two lots pay the flat Rs 20-per-order brokerage once, not twice.
   - It rides the same unproven score, so it adds risk without a proven edge.
5. **Rs 5,000/day: still NO.** Lots needed at 1 lot = the Rs/day above:

   | | walk-forward 2022-25 | holdout |
   |---|---|---|
   | BANKNIFTY plain | 62 lots | 30 lots |
   | BANKNIFTY filtered | 46 lots | 34 lots |
   | BN+FIN+MIDCP filtered | 39 sets | 16 sets (38 sets at κ 0.04) |

   Each "set" is 1 lot each of BN, FIN and MIDCP. FIN and MIDCP cannot carry that size (h10, h24), and Rs 1 lakh
   carries one BN lot.

**What to do:** keep h36 Plan A, BANKNIFTY Liquidity 15+5 at 1 lot with the app's exits, unfiltered. If FIN/MIDCP are
ever traded, log this model's score (or simply the trade's expected cost) on paper. Re-test once there is more data.

## 1. What was built

**Trades.** h4's Liquidity 15+5 signals were run through h23's build:
- "prints only" data;
- the h14 limit entry at +0.5%, resting 3 minutes;
- h10/M2 impact;
- app charges;
- one position per book.

They were priced with h24's real flat half-spread: BN 0.162%, MIDCP 0.213%, FIN 0.42% and NIFTY 0.16%. SENSEX 0.16%
is **assumed**, because SENSEX was not in the snapshot. Pricing was done at κ 0.02 and 0.04, at 1 and 2 lots.

- **Validation:** BN and MIDCP at 1 lot reproduce h24 `flat_x1` to the rupee. BN is 106,372 at κ .02 and 96,549 at
  κ .04. MIDCP is 20,778 and -37,980.
- **Trades per index:** BN 998, FIN 626, MIDCP 618, NIFTY 926, SENSEX 498. That is 3,666 in total, of which 3,577 filled.
- **Win rate before the holdout:** 33%.

**Features (49), all known at the close of the signal minute.** Directional features are signed toward the trade.

| group | features |
|---|---|
| time | minute of day, day of week, 15m/5m book, days to expiry, index dummies |
| volatility | VIX (previous close), VIX change today, ATR%, day's range so far / ATR, first-hour range / ATR |
| location | gap, distance to prior-day high and low, position in today's range |
| trend | 15-minute, 60-minute and since-open return / ATR |
| OI and positioning | OI build-up over 15 minutes and since open (h26), put-minus-call OI change, PCR change |
| implied volatility | ATM IV, IV / HAR forecast, IV / realised (h29), risk reversal and its 15-minute change, straddle % |
| cost | option premium % of spot, tick / premium, 5-minute option volume, previous month's Roll spread |
| levels | room to the next liquidity level, stop distance, room to the nearest Brain level (h35) |
| today so far | signals already today, trades closed today, prior outcome today |
| FII | h40 participant-OI features S01-S03 (D-1) |

**Constituent breadth** (stock minutes exist only from 2024) was kept out of the models, as pre-registered. On 404
BN/NIFTY trades in 2024-25 its Spearman correlation with net P&L was 0.04 and -0.01. So: nothing.

**Models**, with fixed hyper-parameters:
- L2 logistic regression (LR);
- depth-2 gradient boosting, classifier (GB) and regressor (GBR);
- depth-4 random forest (RF);
- ridge on return-on-premium (RIDGE).

**Walk-forward.** For each test year Y (2022, 2023, 2024, 2025 Jan-Sep), the model is trained only on years before Y,
with a 5-trading-day embargo. The skip threshold is the 20/33/50% quantile of leave-one-year-out out-of-fold scores
inside the training years. The model is pooled over all 5 indices.

## 2. Nested walk-forward: Rs/day versus plain Liquidity (1 lot per index, κ 0.02)

Plain Liquidity, Rs/day, by test year:

| book | κ | 2022 | 2023 | 2024 | 2025 (to Sep) | all |
|---|---|---|---|---|---|---|
| BN+FIN+MIDCP | 0.02 | -66 | 16 | 288 | 159 | 96 |
| BN+FIN+MIDCP | 0.04 | -72 | -35 | 277 | -83 | 29 |
| BANKNIFTY | 0.02 | -39 | 34 | 166 | 188 | 81 |
| all 5 (flagged) | 0.02 | -185 | -59 | 333 | 202 | 65 |

Excess Rs/day of each variant over plain Liquidity:
- The main columns are for BN+FIN+MIDCP (P1), 2022-01 to 2025-09.
- The yearly columns are P1 at κ .02.
- "p rand" is the p-value against randomly skipping the same number of trades.

| variant | P1 κ.02 | P1 κ.04 | BN κ.02 | 2022 | 2023 | 2024 | 2025 | yrs + | p rand | BH q |
|---|---|---|---|---|---|---|---|---|---|---|
| **RIDGE_s33 (chosen)** | **+31** | +50 | +28 | +16 | +30 | -28 | +131 | 3 | 0.11 | 0.32 |
| GBR_s20 | +26 | +26 | +24 | +50 | -0 | -4 | +69 | 2 | 0.05 | 0.32 |
| RIDGE_s20 | +25 | +35 | +16 | +31 | +56 | -53 | +80 | 3 | 0.13 | 0.32 |
| GBR_s33 | +16 | +26 | +14 | +46 | +10 | +36 | -44 | 3 | 0.12 | 0.32 |
| GB_s20 | +13 | +16 | -14 | -77 | +9 | -34 | +198 | 2 | 0.13 | 0.32 |
| GB_s33 | +12 | +27 | -4 | -46 | +5 | -99 | +244 | 2 | 0.10 | 0.32 |
| RIDGE_s50 | +8 | +39 | -14 | +16 | -108 | -71 | +257 | 2 | 0.15 | 0.32 |
| RF_s20 | +5 | +10 | +2 | -34 | +25 | -102 | +171 | 2 | 0.18 | 0.33 |
| GBR_s50 | -10 | +14 | -14 | +63 | -68 | -75 | +55 | 2 | 0.22 | 0.36 |
| RF_s33 | -14 | -7 | -19 | -20 | +20 | -250 | +263 | 2 | 0.24 | 0.36 |
| GB_s50 | -49 | -8 | -56 | -88 | -6 | -106 | +24 | 1 | 0.39 | 0.54 |
| LR_s20 | -58 | -26 | -11 | -69 | +19 | -281 | +152 | 2 | 0.92 | 0.92 |
| RF_s50 | -61 | -42 | -59 | -4 | +3 | -224 | -3 | 1 | 0.48 | 0.60 |
| LR_s33 | -78 | -46 | -62 | -76 | -28 | -240 | +68 | 1 | 0.88 | 0.92 |
| LR_s50 | -87 | -35 | -74 | -14 | -22 | -262 | -33 | 0 | 0.76 | 0.88 |

- **SPA / White Reality Check** over the 15 skip variants (927 test days, daily excess P&L): **SPA p = 0.50, RC p = 0.72**.
  No variant beats plain Liquidity beyond chance.
- **What the excess comes from.** Skipping cuts gross P&L but saves costs. In the Rs 1 lakh walk on P1, gross fell from
  332 to 290 Rs/day while net rose from 96 to 127. The "edge" is mostly avoided cost on trades with a low expected
  return, not better direction.
- **2024 shows the danger.** Every model skipped some of 2024's big winners. The skipped trades averaged +93 Rs net, so
  2024 lost money to the filter.
- **The skip share drifts far from the target.** In 2022 the model, trained on just 2020-21, skipped 62% of P1 trades
  when it was set to skip 33%.
- **The scores barely rank trades.** Test-year AUC by model:

  | model | 2022 | 2023 | 2024 | 2025 |
  |---|---|---|---|---|
  | GB | 0.47 | 0.50 | 0.59 | 0.61 |
  | RF | 0.46 | 0.49 | 0.57 | 0.60 |
  | LR | 0.50 | 0.47 | 0.51 | 0.56 |
  | RIDGE | 0.51 | 0.46 | 0.50 | 0.51 |
  | GBR | 0.49 | 0.46 | 0.55 | 0.52 |

  The chosen RIDGE score's test-year quintiles were not monotonic. Mean net per trade from lowest to highest quintile
  was -4, +39, -60, +44, +101 Rs.

## 3. Feature-importance stability (descriptive; it chose nothing)

- **LR standardised coefficients.** The rank correlation of |coef| between consecutive training cuts was 0.27, 0.41
  and 0.62. Features that kept the same sign in all 4 cuts:
  - Helped a trade:
    - momentum into the break (15-minute return, return since open);
    - a larger day's range so far;
    - a gap toward the trade;
    - IV above realised vol;
    - OI build-up since the open.
  - Hurt a trade:
    - a far next liquidity level (`room_atr`);
    - a big first-hour range;
    - more signals already today;
    - a win earlier today (`prior_out`);
    - a rising risk reversal toward the trade;
    - later in the day;
    - the 15-minute book.
  - **h26's BU15 came out with the OPPOSITE sign to h26's filter.** It was weak.
- **GB permutation importance**, measured on the inner out-of-fold data, does not agree from one cut to the next. The
  rank correlation between cuts was -0.26, 0.00 and 0.30. Its top-8 lists change almost completely every year. Only
  `room_atr` and `rr_dir` stay near the top in 2024-25.
- **FII positioning (h40 S01-S03), the Brain-level room and the IV/HAR ratio never matter consistently.**

## 4. Locked holdout, run once (RIDGE_s33, trained on all 2,718 pre-holdout filled trades)

Holdout AUC 0.54. Net Rs/day at 1 lot per index:

| book | κ | plain | filtered | excess | gross plain → filtered | skipped (avg net of skipped) | max DD plain → filtered | +2nd lot (b) | max DD (b) |
|---|---|---|---|---|---|---|---|---|---|
| **BN+FIN+MIDCP** | 0.02 | 126 | **318** | **+191** | 759 → 789 | 145 of 619 (-329) | -85k → -67k | 451 | -109k |
| BN+FIN+MIDCP | 0.04 | -116 | 132 | +248 | 639 → 692 | 145 (-427) | -117k → -90k | 159 | -133k |
| **BANKNIFTY** | 0.02 | **167** | **149** | **-19** | 362 → 296 | 52 of 248 (**+90**) | -32k → -36k | 215 | -50k |
| BANKNIFTY | 0.04 | 143 | 132 | -12 | 362 → 296 | 52 (+56) | -35k → -38k | 185 | -54k |
| all 5 (flagged) | 0.02 | 100 | 220 | +119 | 855 → 760 | 251 of 891 (-119) | -105k → -86k | 351 | -130k |
| all 5 (flagged) | 0.04 | -145 | 33 | +178 | 735 → 664 | 251 (-176) | -134k → -114k | 58 | -154k |

- Random-skip p: P1 0.017, BN 0.38.
- On BN the filter skipped trades that were, on average, winners.

**Rs 1 lakh free-cash walk, holdout, κ 0.02:**

| book | plan | Rs/day | max DD | worst day |
|---|---|---|---|---|
| BN | plain | 167 | -32k | -7.2k |
| BN | filtered | 149 | -36k | -7.2k |
| BN | filtered + 2nd lot | 215 | -50k | -12.6k |
| P1 | plain | 63 | -85k | -13.3k |
| P1 | filtered | 318 | -67k | -10.6k |
| P1 | filtered + 2nd lot | 384 | -110k | -16.1k |

- In the plain P1 walk, 4 trades were skipped for lack of cash, and 43 at κ .04.

**Holdout months** (P1, κ .02):
- The filter's excess was positive in 7 of 12 full months.
- Most of it came from **April 2026 (+21.7k)**, January 2026 (+11.1k) and February 2026 (+9.0k).
- **It lost 10.7k in June 2026.**

**Gates:**

| gate | result |
|---|---|
| g1: κ .02 and .04 positive | ✔ |
| g2: at least 3 of 4 years positive | ✔ |
| g3: SPA ≤ 0.10 | ✘ (0.50) |
| g4: BN ≥ 0 before the holdout | ✔ |
| g5: holdout P1 > 0 | ✔ (+191) |

Gate g3 failed, so the result is **NOT ADOPTED**, as pre-registered.

## 5. Honesty notes

- **Variants:** 15 skip variants plus 15 size-up add-ons, so 30 in all. One configuration went to the holdout, and the
  holdout was used once. No hyper-parameter was tuned.
- **SENSEX spread is assumed.** NIFTY and SENSEX are extra training data only, and are flagged.
- **One-day snapshot.** The real spread level comes from one snapshot on one day (h24). The FIN and MIDCP results are
  the most sensitive to it, and that is exactly where the filter "worked".
- **The 2-lot P&L is simulated with h10 impact for the second lot.** The flat brokerage is shared between the two lots,
  which flatters size-up a little.
- **h40 data was still being fetched.** h40's feature file, as it stood at 05:02, was used.
