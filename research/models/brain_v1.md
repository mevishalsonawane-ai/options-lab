# Model card: India Data Brain v1 range nowcast (B1)

Written 14 Oct 2026. Source: `research/BRAIN_V1.md`, `research/brain/models/metrics_v1.json`, pre-registration
`research/brain/models/PREREG.md`. Status: design walk-forward passed; the locked holdout (from 27 Apr 2026) is not read
yet. It only informs and ranks: nothing trades on it.

## What it does

At a minute close between 09:45 and 14:45 it forecasts the size (high - low, % of the price) of the next 30 or the next
60 minutes, for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and BANKEX. It does not say which way. It is a
gradient-boosted tree model on the brain's 154 point-in-time features; the main inputs are the usual range at that
clock time, the last 30 and 60 minutes' range and VIX.

## How good it is (design walk-forward, 8 test folds per index)

- Next 30 minutes: error 16.5-22.6% smaller than the best plain baseline (time of day, last window or VIX), q 0.001 on
  every index (NIFTY 37,150 forecasts on 1775 sessions; the others 17,148-19,041 on 822-918 sessions).
- Next 60 minutes: 10.6-21.1% smaller, q 0.001 on every index.
- It forecasts the median move: per forecast decile the median of actual / forecast is 0.87-1.05, the 10th percentile
  0.52-0.68 and the 90th 1.56-1.87.

## What it cannot do

- Trend day vs range day at 09:45: not predictable (AUC 0.483-0.510).
- Busy vs quiet rest of day at 09:45: the boosted model ranks days (AUC 0.549-0.689) but is over-confident and fails
  its pre-registered test.
- Direction: not modelled (R16: not predictable).

## Files

Model files: Google Drive `gdrive:IraAlgo-data-lake/brain_models/v1/` (24 files, one per index and target), never in
git. Refresh: `python -m research.brain.refresh --source lake --upload --pack`.
