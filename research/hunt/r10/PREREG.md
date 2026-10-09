# r10 pre-registration: VWAP family and Market-Profile day structure as REGIME FILTERS

Written 9 Oct 2026 BEFORE any P&L of these filters was looked at. Holdout 2025-10-01 .. 2026-10-06 is opened once by
`eval.py hold` (flag file `scratchpad/hunt/r10/HOLD_OPENED`), after `eval.py pre` has written its results.

## Data
- Index minutes (obuy Index caches); VWAP weight = nearest-expiry option volume per minute, CE+PE, ATM+-10
  (`build.py`). Reason: minute futures volume for expired contracts does not exist (h38). The proxy was checked
  against the real near-month futures VWAP on the 42-47 days where futures minutes exist (`validate.py`, done before
  this file): sign of price-minus-VWAP agrees 89% (BANKNIFTY, NIFTY), 80-82% (MIDCP, SENSEX, FIN). TWAP is a
  robustness row only.
- Trades (no re-simulation; filters only choose rows):
  - h19 `trades.parquet` arms `orb`, `orb_fresh`, `orb_sweep`, `range_fade` (BANKNIFTY, 1 lot). Net = h19 net minus
    h24 spread hs*(entry+exit)*lot, hs = 0.0016.
  - h44 `trades44.parquet` Liquidity 15+5, 1 lot, `net1_0.02` (already includes the h24 spread):
    arm `liq_bn` (BANKNIFTY) and arm `liq_oth` (NIFTY, FINNIFTY, MIDCPNIFTY, SENSEX pooled).
- Feature time: column t = entry_min - 1 - 555 (last closed bar before the entry). Nothing later is used.

## Filters (keep rule; s = +1 CE, -1 PE; z = (close - VWAP)/SD of typical price around VWAP)
Trend arms = orb, orb_fresh, orb_sweep, liq_bn, liq_oth. Fade arm = range_fade.

| id | trend arms keep if | range_fade keep if |
|---|---|---|
| V1 side of VWAP | s*(c - VWAP) > 0 | s*(c - VWAP) < 0 (fading back toward VWAP) |
| V2 not stretched | s*z < 2 | abs(z) <= 1 |
| V3 inside 2 SD | abs(z) < 2 | abs(z) < 2 |
| V4 VWAP slope (15 min) | s*(VWAP_t - VWAP_t-15) > 0 | not applied |
| V5 prior-day VWAP | s*(c - prior-day session VWAP) > 0 | s*(c - pdVWAP) < 0 |
| V6 VWAP anchored at prior day 15:00 (NSE closing window) | s*(c - AVWAP) > 0 | s*(c - AVWAP) < 0 |
| V7 V1 and V2 | both | not applied |
| T1 opening type not against | skip if open-type direction = -s (t >= 30) | same |
| T2 conviction open | skip if open-auction (OA) day (t >= 30) | keep only OA days (t >= 30) |
| T3 narrow IB | keep if IB/20d-avg IB < 1.0 (t >= 60) | keep if ratio >= 1.0 (t >= 60) |
| T4 wide IB | skip if ratio > 1.3 (t >= 60) | not applied |
| T5 IB extension | skip if IB extended against s by t (t >= 60) | keep only if no IB extension yet (t >= 60) |
| T6 narrow 30-min range | keep if first-30-min range / 20d avg < 1.0 (t >= 30) | keep if >= 1.0 |

Trades before the stated t (e.g. before 10:15 for IB rules) are always kept (the rule cannot be known yet).
Opening types (first 30 minutes, range R30, open O, close C30): OD up = O within 10% of R30 from the low and C30 in
the top 30%; ORR up = first 15 min fell >= 0.5 R30 below O, then C30 >= O + 0.2 R30; OTD up = C30 in the top 30% and
above O (not OD/ORR); mirrors for down; else OA. IB = 09:15-10:14.

## Statistics
- Per arm and filter: kept vs skipped trades, mean net per trade, Delta/day = -(sum of skipped net) / trading days
  of that arm's period (gain of filtering at 1 lot), random twin p = share of 5,000 random skips of the same count
  with Delta >= observed (one-sided). BH over the 5x13 + 10 = 75 design tests (TWAP rows excluded).
- White reality check: max over all filters of the standardised Delta, against permutations of net within each arm.
- PASS = design Delta > 0, BH q <= 0.10 AND holdout Delta > 0 with random-twin p <= 0.10. A pass becomes a
  paper-only filter.

## Day-type description (no P&L)
Trend day = day range >= 2x IB and close in the top/bottom 20% of the day range; range day = day range < 1.3x IB.
Rates by IB-ratio tercile and opening type (all 5 indices pooled and BN alone), chi-square p, design vs holdout;
direction hit rate of OD/OTD/ORR direction vs sign(close 15:29 - close 10:14).
