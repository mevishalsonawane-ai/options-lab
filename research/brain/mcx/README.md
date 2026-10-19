# research/brain/mcx: the MCX Data Brain (MB1, 19 Oct 2026)

Boss, 19 Oct: "After commodities are done, save the data in numerical tensors, vectors, or optimized binary/columnar
file formats and save it in neuro network of 5 levels, so that we could use that to find best strategy."

The Indian Data Brain (`research/data/ARCHITECTURE.md`, `research/brain/README.md`) rebuilt for MCX commodity futures,
the same way the crypto brain was (`research/brain/crypto/README.md`): same layers and rules (point-in-time features,
gaps marked never filled, labels kept apart, walk-forward with a locked holdout, real costs, BH), reusing the Indian
code and changing only the market rules. Pre-registration: `PREREG.md` (committed before any result). Write-up:
`research/BRAIN_MCX.md`. **Nothing here trades.**

```
L0 Dhan (Drive lake + fut_1m_live_mcx fetched 19-20 Oct)
L1 raw     options_rolling_1m/opt_<SYM>_MONTH_*.parquet, fut_1m_live_mcx.parquet, fut_daily_continuous.parquet  (zstd, never in git)
L2 clean   clean.py: option spot -> near-future close + ATM IV / straddle / put-call OI / option volume; the continuous
           future (research/data/mcx.py, previous-day volume roll); option expiry calendar
L3 cube    cube.py -> <cache>/mcx/cube/<SYM>/<YYYY>.parquet: one MCX session grid per day (09:00-23:29/23:54)
L4 store   features.py -> <cache>/mcx/features/<SYM>.parquet: 56 point-in-time features (FEATURES.md)
           labels.py: y_* next 15/30/60 minutes (close-only), design days only
           tensors.py -> <cache>/mcx/tensors/: float32 [T, F] .npy (memmap), Arrow IPC, the joint [I, T, F] tensor,
           fixed 120-minute windows and the encoder input [N, 24, 6], manifest with sha256
Brain      levels.py (L1 specialists A/B/C, L2 encoder, L3 committee), session_event.py (L1-D US open, L1-E events),
           l4.py (strategy search, real MCX costs, random twins), l5.py (knowledge cards), study.py (driver)
```

The cache is `$IRAALGO_CACHE` (default `~/.cache/iraalgo`) under `mcx/`; the lake is `$IRAALGO_LAKE`.

## Commands

```bash
python3.11 -m venv .venv && .venv/bin/pip install -r research/brain/requirements.txt requests
# L1: the lake (rclone remote gdrive) and the newer MCX futures minutes from Dhan
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $IRAALGO_LAKE/options_rolling_1m --include "opt_*_MONTH_*"
python -I research/data/lake_fetch.py RAW fut_1m_mcx fut_daily idx_daily
python -I research/data/lake_build.py RAW FUTLAKE fut_1m fut_daily
# L2-L4
python -m research.brain.mcx.cube build --fut-lake FUTLAKE
python -m research.brain.mcx.features build
python -m research.brain.mcx.guard --symbols GOLDM CRUDEOIL NATURALGAS SILVER COPPER   # look-ahead guard, real cube
python -m research.brain.mcx.study tensors
# the brain
python -m research.brain.mcx.study design      # L1-L3 walk-forward, L1-D/E, BH, L4 design gates (design only)
python -m research.brain.mcx.study holdout     # the ONE holdout read (a lock refuses a second)
python -m research.brain.mcx.study cards       # L5
# checks
python -m pytest research/brain/tests/test_mcx_brain.py research/brain/tests/test_mcx_levels.py -q
python -m research.brain.mcx.features catalog --check
```

## What is market-aware (and where)

| India brain assumes | MCX | where |
|---|---|---|
| NSE 09:15-15:29, 375 bars | 09:00 to 23:30 (US summer time) / 23:55 (US winter time), 870 / 895 bars | `cube.grid`, `research/data/mcx.close_min` |
| index spot with OHLC | the near future's CLOSE per minute from the option book (Aug 2025 on); true OHLCV / OI of the continuous future only from mid-2026 | `clean.py`, `cube.py` (`src`, `fo..foi`) |
| one continuous index | contract segments: the option book rolls after each option expiry; no window crosses a segment | `cube.seg`, `features.Ctx` |
| VIX, NSE chain | ATM IV, straddle, put/call OI from the MCX option book (ATM-5..+5) | `clean.spot_frame` |
| RBI / results / expiry | EIA crude (Wed 10:30 ET), EIA gas storage (Thu 10:30 ET), US CPI / NFP (08:30 ET), FOMC (14:00 ET) | `events.py` (R83, R82 calendars) |
| NSE charges | Zerodha MCX futures and options charges, R53 spreads, 0.02% stop slippage | `l4.py` (R53, R83) |

Reused unchanged: `quality.series_flags`, the feature decorator / runner / catalogue renderer
(`features/registry.py`), the look-ahead guard (`guard.check_features`), R53's MCX charges, spreads, BH, twin p and
option Market, R83's EIA and storage calendars, `research/data/mcx.py` (session, continuous future, near_share).

## Rules that keep it honest

- **Point in time.** A day's price source is chosen from the previous day; the continuous future's contract from the
  previous day's volume; the option-expiry calendar is a fixed input (MCX publishes it in advance; inferred once from
  the straddle jump). The guard deletes or perturbs every row after a cut and requires every feature (and the cube
  builder) to be unchanged before it; canaries (next close, the day's final high) are caught. Daily features read only
  earlier days. Tests: `research/brain/tests/test_mcx_brain.py`, `test_mcx_levels.py`.
- **Close-only prices.** Ranges are close-to-close on every source, so the option-spot months and the true-OHLC months
  are measured the same way. Labels need >= 80% of their window's closes (the option spot misses some minutes).
- **Locked holdout** from 1 Jul 2026 (design to 23 Jun, embargo between). `holdout.read_holdout` records the one read in
  `results/holdout_reads.json`.
- **Gaps are gaps.** A missing minute is NaN with flag `gap`; option context is carried at most 15 minutes.

## Known gaps (19-20 Oct 2026)

- MCX option books for CRUDEOILM, NATGASMINI, COPPER, ZINC are still being fetched by another session; ALUMINIUM has
  none. Option minutes end 13 Oct (the Drive lake); 14 Oct has no option minutes (the cube marks it a gap day for the
  six option symbols, since its source was chosen from 13 Oct), 15-16 Oct come from the continuous future.
- 19 Oct futures minutes were fetched but `lake_build.py` keeps MCX days up to the newest NIFTY daily bar (16 Oct at
  build time), so the cube ends 16 Oct.
- Futures minutes exist only for contracts listed on 13 Oct 2026 (Dhan serves live contracts only): the five
  futures-only commodities have 20-37 sessions, all after the design end, so they are built, stored and described but
  not modelled (PREREG: >= 60 design sessions).
- GOLD's option book missed two early roll detections (Aug-Sep 2025: thin options), so its first segment is longer.
