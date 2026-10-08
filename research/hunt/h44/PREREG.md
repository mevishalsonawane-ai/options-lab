# h44 pre-registration: META-LABELLING of Liquidity 15+5 signals

Written 2026-10-08 BEFORE any model was fitted or any filtered P&L was computed. Option BUYING only. Fixed lots.
The app's Liquidity 15+5 entries and exits are NOT changed. The only question: can a model, trained only on earlier
data, tell which Liquidity trades to skip (and, risk-adding, which to double)?

## Trades (the thing being labelled)
- Liquidity 15+5 signals of h4 (`sig_liq_bnfin_0.pkl`, `sig_liq_ext_0.pkl`) on BANKNIFTY, FINNIFTY, MIDCPNIFTY
  (primary) and NIFTY, SENSEX (extra data, flagged). 1-ITM nearest; h14 limit entry +0.5% resting 3 min, no chase;
  app exits unchanged; h10/M2 impact; app charges; one position per book; "prints only" data (h23 build.py reused).
- Spread: h24's REAL half-spread level (2026-10-06 snapshot), flat per index: BANKNIFTY 0.16%, MIDCPNIFTY 0.21%,
  FINNIFTY 0.42%, NIFTY 0.16%, SENSEX 0.16% (**assumed** = NIFTY; SENSEX was not in the snapshot).
- Priced at kappa 0.02 (central) and 0.04 (stress), for n = 1 lot and n = 2 lots (the 2-lot run prices the second
  lot's extra impact exactly, for part (b)). BN/MIDCP 1-lot must reproduce h24 `flat_x1` (validation).
- Label y = 1 if net P&L (1 lot, kappa 0.02) > 0. Unfilled signals (0 lots) are not used for training (P&L 0).

## Features (all known at the CLOSE of the signal minute sig_min; directional ones multiplied by side, +1 CE / -1 PE)
| group | features |
|---|---|
| calendar | index one-hot (5); book (15 vs 5); minute of day; day of week; calendar days to the traded (near) expiry |
| volatility | VIX previous close; VIX change today to sig_min (minute VIX from Oct 2021, else NaN); ATR14 % of price (prior days) ; day's range so far / ATR14; first-hour range (or range so far before 10:15) / ATR14 |
| gap / location | gap (open / prior close - 1) / ATR * side; (close - prior-day high) / ATR * side; (close - prior-day low) / ATR * side; position in today's range so far (towards the trade side) |
| trend | 15-min and 60-min index return / ATR * side; return since open / ATR * side |
| OI (h26 defs) | BU15_z * side; BUopen_z * side; dOIpc5_z * side (15-min put-minus-call OI change, ATM±5); PCR (put/call OI, ATM±5) change since 09:20 * side |
| IV (h29 defs) | ATM straddle IV (Black-76, trading time); IV / HAR-RV forecast (h29 `har.parquet`); IV / realised vol since open; risk reversal rr * side; 15-min rr change * side; straddle % of spot |
| option / cost | 1-ITM option close at sig_min / spot (premium %); log option 5-min volume in lots (v5); tick / premium; previous month's Roll half-spread (h23 `roll.csv`, point-in-time) |
| levels | room to the signal's next liquidity level (idx_target) / ATR; distance to the signal's index stop / ATR; room to the nearest Brain level (prior-day H/L/C, 15-min opening range H/L) in the trade direction / ATR (h35's closest levels) |
| session | number of Liquidity signals of this index earlier today; number of this index's trades closed before sig_min; sign of their summed net (prior outcome today; 0 if none) |
| positioning | h40 participant-OI features S01 (FII fut long ratio z), S02 (chg FII fut net z), S03 (chg FII option net-bullish z), each * side; from D-1 files only |
| breadth | constituent VWAP share and up-since-open share (BANKNIFTY/NIFTY baskets, stock minutes from 2024 only): **computed and described, NOT in the models** (no training data before 2024) |

Missing values: training-median imputation plus one missing-indicator per group (VIX-minute, IV, OI, FII). Linear
models use standardised features (training mean/std).

## Models (simple, regularised; fixed hyper-parameters, no tuning)
1. **LR**: logistic regression, L2, C = 0.05, balanced classes off.
2. **GB**: sklearn HistGradientBoostingClassifier, max_depth 2, 150 iterations, learning rate 0.03, min_samples_leaf 50,
   l2 1.0.
3. **RF**: RandomForestClassifier, 300 trees, max_depth 4, min_samples_leaf 40, max_features sqrt.
4. **RIDGE**: ridge regression (alpha 30, standardised) of the trade's net return on premium (net / premium paid,
   winsorised at the training 1st/99th percentile) = expected P&L score.
5. **GBR**: HistGradientBoostingRegressor, same settings as GB, same target as RIDGE.
Score = P(win) for 1-3, predicted return for 4-5. Pooled over all 5 indices (more data); index one-hot is a feature.

## Strict nested walk-forward by year
- Test years: 2022, 2023, 2024, 2025 (Jan-Sep). For test year Y the model is fitted ONLY on trades of years < Y,
  with an embargo: the last 5 trading days before 1 Jan Y are dropped from training. (Trades are intraday so labels do
  not overlap; the embargo guards trailing-window features.)
- Threshold (inner loop, training data only): leave-one-year-out across the training years (same 5-day embargo either
  side of the left-out year) gives out-of-fold scores for every training trade; the skip threshold for year Y is the
  s-quantile of those OOF scores. Skip fractions s in {0.20, 0.33, 0.50}.
- (b) Top-score second lot: score >= 0.80-quantile of the same OOF scores -> trade 2 lots (priced with the 2-lot run),
  only if Rs 1 lakh free cash allows (h23 walk). FIXED lots, never compounding. **Risk-adding: needs Boss's approval;
  reported only.**
- Holdout model: fitted once on all pre-holdout trades (embargo 5 days before 2025-10-01), threshold from the
  leave-one-year-out OOF over 2020-2025.

## Variants and multiple testing
- Skip variants: 5 models x 3 skip fractions = **15** (the family for SPA / White RC). The (b) size-up add-on is
  evaluated for all 15 as information (15 more, counted: 30 total).
- Benchmark = plain (unfiltered) Liquidity, same trades, same costs. Excess daily P&L = filtered - unfiltered.
- Hansen SPA_c and White RC (obuy.overfit.spa, stationary bootstrap mean block 5 days, B = 2000) on the 15 skip
  variants' daily excess P&L over the 4 nested-WF test years, book P1 (below), kappa 0.02. BH over per-variant
  random-skip p-values.
- Random-skip baseline per variant: skip the same number of trades at random within each test year (2,000 draws);
  p = share of draws with excess >= the model's.

## Books (reporting), 1 lot per index, Rs/day = daily sum / trading days in the window (no-trade days count 0)
- **P1 (primary): BANKNIFTY + FINNIFTY + MIDCPNIFTY.**
- BN: BANKNIFTY alone (h36 Plan A).
- ALL5: all five (flagged; NIFTY/SENSEX included only for more data).
Per year, per book: Rs/day unfiltered vs filtered, kappa 0.02 and 0.04, gross shown too.
Also: Rs 1 lakh free-cash walk (h23 `run.walk`) for the chosen configuration, max drawdown, worst day.

## Pre-chosen configuration and adoption rule (fixed now)
- **Choice:** among the 15 skip variants, the one with the highest pooled nested-WF excess Rs/day on P1 at kappa 0.02
  over 2022-2025(Sep). Ties -> fewer skips.
- **Adoption gates (all must hold):**
  1. nested-WF excess Rs/day > 0 on P1 at kappa 0.02 AND at kappa 0.04;
  2. excess > 0 in at least 3 of the 4 test years (P1, kappa 0.02);
  3. Hansen SPA_c p <= 0.10 over the 15 skip variants;
  4. BN-alone nested-WF excess >= 0 (kappa 0.02);
  5. locked holdout (2025-10-01 .. latest, run ONCE for the chosen configuration): excess > 0 on P1 at kappa 0.02.
- If gates 1-4 fail, the holdout is still run once for the chosen configuration, for information, and the verdict is
  NOT ADOPTED regardless of its holdout number.
- Feature-importance stability: per test-year fit, LR standardised coefficients (sign agreement across years) and GB
  permutation importance on the training OOF folds; Spearman rank correlation of importances between consecutive years.
  Descriptive only - chooses nothing.
- Lots for Rs 5,000/day = 5000 / (holdout and pre-holdout Rs/day per lot-set) - reported for plain and filtered.

## Amendment 1 (2026-10-08, after building the feature table, BEFORE any model fit or filtered P&L)
The feature table showed extreme outliers (e.g. PCR change up to 537 when call OI near ATM is ~0). Every feature is
now winsorised at the TRAINING 1st/99th percentiles before median imputation and standardisation (part of the
preprocessing fitted on training data only). Nothing else changes. Feature coverage noted: iv_rv ~55% (needs 30
minutes of returns), room_atr ~70% (no next level), VIX change missing in 2020; FII (h40 S01-S03) ~100%.
