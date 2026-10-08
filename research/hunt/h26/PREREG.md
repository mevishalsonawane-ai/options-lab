# h26 pre-registration: volume and OI, "out of the box" (written before any P&L was computed)

Date: 2026-10-08. Hunter h26. Option BUYING only, 1 lot, NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX.
Not duplicated: candlesticks / moving averages (h25), PCR / daily OI change / max-OI magnet (OBUY_GC OI-01/02/04),
gamma (h18). Futures OI build-up (OI-03) stays untestable: the futures folder has only the 3 live contracts (Oct 2026).

## Data and periods
- Options: Dhan rolling nearest-expiry ATM±10 minute bars with volume and OI (obuy `Options.chain(day, 'near')`).
- Constituents: NSE_EQ minute candles (start 2024-10-07). Static approximate index weights (stated in code; weights
  drift over time - a limitation).
- CHOICE window: everything before 2025-10-01. LOCKED HOLDOUT: 2025-10-01 .. latest, run once at the end.
- Constituent triggers have only ~12 months of choice data (2024-10-07 .. 2025-09-30). Walk-forward for them = train on
  2024 Q4, test 2025 Jan-Sep (one fold). This is weak and will be said so.

## Features (all at minute t from bars <= t; strikes fixed at time t when comparing to t-L)
Option features (near series, ATM from the index close at t):
- BU (build-up score), ATM±2, CE and PE: per strike, dP and dOI over window L. CE long build-up (+,+) and short covering
  (+,-) = bull; CE short build-up (-,+) and long unwinding (-,-) = bear; PE the mirror. BU = sum(sign x |dOI|) / OI.
  L = 15 min ("BU15") or since 09:15 ("BUopen", the shift through the day).
- dOIpc: (dOI_PE - dOI_CE) over 15 min at ATM±2 ("b2") or ATM±5 ("b5"), / total OI there. + = put writing = bull.
- WALL: max-OI CE strike (resistance) and PE strike (support) among ATM±10; shift over 30 min, in strike steps.
  Bull when both walls moved up >= n steps (n = 1 or 2); bear mirror.
- VSPIKE: ATM±2 CE (PE) volume in the last 5 min / that minute's trailing-20-day median (time-of-day norm).
  Bull when CE ratio >= k and the ATM±2 CE premium rose over those 5 min (price up on volume = aggressive buyers) and
  the CE ratio beats the PE ratio; bear mirror. k = 3 or 5.
- VIMB: (Vce - Vpe)/(Vce + Vpe) at ATM±5 over 15 min. + = bull.
- PFLOW: premium flow, sum over ATM±5 of close x volume x sign(1-min premium change) (tick rule), CE minus PE, over
  15 min, / total premium turnover. + = bull (aggressive call buying / put selling).
- BU, dOIpc, VIMB, PFLOW are z-scored with the mean / std of that feature over the previous 60 trading days
  (5-minute samples 09:30-14:30). Thresholds below are on the z.
Constituent features (BANKNIFTY: 12 banks; NIFTY: top-12 weights):
- HVSURGE: heavyweights (BN top 5, NIFTY top 10). Score = sum w_i x sign(5-min return_i) x [5-min volume_i >= 3x its
  trailing-20-day same-minute median] / sum w_i. Trigger |score| >= 0.4 or 0.6.
- VWB (volume-weighted breadth): sum(turnover_i x sign(15-min return_i)) / sum turnover_i over the 12 names.
  Trigger |VWB| >= 0.6 or 0.8.
- VWAP: index-weighted share of the 12 names above their own session VWAP. Bull when it crosses >= 0.75 (or 0.9),
  bear when <= 0.25 (or 0.1).
- LEADLAG: weighted basket 3-min return z (vs the previous 60 days' std of 3-min basket returns) beyond 2 or 3 while
  the index's own 3-min return is < half the basket's (index lagging). Buy in the basket's direction.
- Diagnostic only (not a selection input): lead-lag cross-correlation at lags 1-5 and the 15-min forward-return IC of
  every feature, pre-holdout.

## Triggers -> trades
- Event = the condition turns true (or flips side) at a minute in 09:30-14:30, at most one event per (setting, und)
  per 30 minutes. Entry: 1-ITM nearest expiry (StrikeRule(money=1)), next minute's OPEN, expiry days skipped, one
  position at a time per (setting, und), max 3 trades per day per (setting, und).
- Sign: for BU, dOIpc, WALL, VIMB, PFLOW the conventional sign AND the reversed sign are both run (the "writers vs
  buyers" reading is ambiguous). Both count as variants.
- Signal grid (per underlying): BU {BU15 z2, BUopen z2} x sign 2; dOIpc {b2 z2, b5 z2} x 2; WALL {1, 2 steps} x 2;
  VIMB {z1.5, z2.5} x 2; PFLOW {z1.5, z2.5} x 2; VSPIKE {k3, k5}; HVSURGE {.4, .6}; VWB {.6, .8}; VWAP {.75, .9};
  LEADLAG {z2, z3}. Options: 22 settings x 5 unds; constituents: 8 settings x 2 unds (BN, NIFTY).
- Exit menu, FIXED (7): X0 Liquidity arm (-15% premium, out at 20 min unless +5%, 15:10; no index levels exist for
  these triggers); X1 -20 / +20 premium points; X2 -15% / +30%; X3 -15% / +30% with the profit-lock ladder
  (25% of the way -> BE, 50% -> lock 25%, 75% -> lock 50%); X4/X5/X6 time stop 15 / 30 / 60 min (regardless of gain)
  with a -15% premium stop. All square off 15:10.
- Variants: (22 x 5 + 8 x 2) x 7 = 882. All counted.

## Costs
- Fills: the app's model with the measured real half-spread added (h24): BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY
  0.21%, FINNIFTY 0.42%, SENSEX 0.16% (assumed = NIFTY/BN; not measured). Market orders cross that half-spread, stops 5
  bps more. Charges: Costs('app'). Stress: 1.5x the half-spread (post-hoc adjustment on the same trades).
- Gross (Boss's headline) = bar prints, no spread, no charges. Always shown next to net.

## Statistics and selection (choice window only)
- obuy Lab: same-exit random-entry baseline on the same days (10 alternatives per signal, random minute 09:30-14:30,
  coin-flip side), BH and Holm over all variants, White RC and Hansen SPA over the daily P&L matrix, walk-forward by
  year (options: anchored, 2 training years; constituents: 1 fold as above), DSR, PBO.
- BH is finally applied over the union of both runs (882 p-values).
- A SURVIVOR must pass all of: Lab gates G1-G4 (WF net > 0 at real spread, WF beats random at Holm p < .05, > half of
  WF years positive, drawdown gate), variant BH q < 0.10, AND net > 0 at the 1.5x spread stress.
- Holdout: survivors are run once. If there are none, the single best family by WF net per run is run once in the
  holdout FOR INFORMATION ONLY (not a recommendation).

## Filters on Liquidity 15+5 (h4 Liquidity trades, all 5 indices, gross prints + app charges + real spread)
- 12 features, each read at the signal minute's close: BU15, BUopen, dOIpc b2, dOIpc b5, WALL (30-min shift), VSPIKE
  (side-signed ratio difference), VIMB, PFLOW, HVSURGE, VWB, VWAP share - 0.5, LEADLAG z.
- Each feature is signed toward the trade (positive = agrees with the trade's side). Two rules per feature:
  "skip if opposes" (signed z <= -1; for bounded features signed value <= -0.3; for WALL <= -1 step; VSPIKE: the other
  side's ratio >= 3 with its premium up) and the reverse "skip if agrees" (same threshold, other sign). 24 filters.
- Score: change in net Rs/day vs unfiltered; p vs skipping the same number of trades at random (2,000 draws); BH over 24.
- ADOPT if BH q < 0.10, the change is positive in both choice halves (2021-23, 2024-25.09; constituent features: one
  period only) and positive at 1.5x spread. Adopted filters go to the holdout once. None adopted -> holdout shown for
  the best-by-choice-window filter for information only.

## Report
Rs/day at 1 lot (gross and net, real and 1.5x spread), lots for Rs 5,000/day, the Rs 1 lakh capital view (premium per
lot vs capital), per year, worst day / month, max drawdown, P(losing month) by bootstrap, and the honest variant count.

## AMENDMENT 1 (2026-10-08, before any P&L of these exits was computed)
Boss clarified that "points" mean absolute PREMIUM points (rupees of option price). The fixed exit menu is extended with
point-based exits: targets +15 / +20 / +25 / +30 premium points x stops -10 / -15 / -20 premium points (12 pairs; the
+20/-20 pair is the existing X1, so 11 new exits X7..X17), all squared off 15:10.
- Exit menu is now 18 exits. Variants: (22 x 5 + 8 x 2) x 18 = 2,268. All 6 Lab runs are re-run with the full menu, and
  BH / White RC / SPA / walk-forward use all 2,268 variants.
- Disclosure: at the time of this amendment the pre-holdout CONSTITUENT run (7 exits) had been viewed (all 8 families
  negative walk-forward, SPA p 0.97). The 5 option-trigger runs had finished but their results had NOT been viewed.
  The Liquidity filter pre-holdout table had been viewed (no filter adopted); it is unaffected (it uses Liquidity's own exits).
- Report also Rs per premium point per lot per index (= the lot size) and the approximate Rs per INDEX point (1-ITM
  delta ~0.6 x lot).

## AMENDMENT 2 (2026-10-08, a reporting note; no rule changed)
Boss asked whether targets / stops use the candle close or the wicks. They use the wicks: every premium stop / target
(and ladder lock) is a resting order checked minute by minute on the option's own 1-minute HIGH / LOW (obuy engine,
intrabar=True); if both are touched in the same minute the STOP is assumed to fill first. `ties.py` counts those
same-minute ties for the fixed stop+target exits (X1, X2, X7..X17).
