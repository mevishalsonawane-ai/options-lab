# research/brain/crypto: the crypto Data Brain

The Indian Data Brain (`research/data/ARCHITECTURE.md`, `research/brain/README.md`) rebuilt for Binance crypto
(13 Oct 2026, Boss: "the same model we created for storing and studying the Indian market, for crypto"). Same layers,
same rules (point-in-time features, gaps marked never filled, labels kept apart, walk-forward with a locked holdout,
real costs, BH across ideas). It **reuses** the Indian code and changes only the market rules. Nothing here trades.

```
L0 Binance bulk archive / Drive crypto lake ──fetch──► L1 <cache>/crypto/raw (the archive's own layout, never in git)
   ──adapters + clean──► L2 (UTC, deduped, ts units fixed, gaps marked, flags)
   ──cube──► L3 <cache>/crypto/cube/<SYM>/<YYYY>/<day>.parquet   one 1440-minute UTC grid per day, 24x7
   ──features──► L4 <cache>/crypto/features/<SYM>/...            64 point-in-time features (FEATURES.md)
   ──labels──►   <cache>/crypto/labels/<SYM>/...                 y_* over the next 60 / 240 / 1440 minutes
   ──study──► L5 results/c1_design.json → research/CRYPTO_C1_PREDICTABILITY.md, FINDINGS.md, the knowledge pack
```

The cache is `$IRAALGO_CACHE` (default `~/.cache/iraalgo`), the same as the Indian brain, under `crypto/`.

## Commands

```bash
python3.11 -m venv .venv && .venv/bin/pip install -r research/brain/requirements.txt pyyaml

# L1: get months of raw data (never a month in the holdout unless --allow-holdout)
python -m research.brain.crypto.fetch sample --symbols BTCUSDT ETHUSDT --from 2023-10 --to 2026-03   # archive direct
python -m research.brain.crypto.fetch drive  --remote gdrive:<crypto-lake-folder> --symbols BTCUSDT --from 2024-01 --to 2024-06
#   (rclone remote `gdrive`, configured by RCLONE_CONFIG_GDRIVE_*; copies only the months asked for)

# L2-L4
python -m research.brain.crypto.cube build --symbols BTCUSDT ETHUSDT          # incremental: only changed months
python -m research.brain.crypto.cube info  --symbols BTCUSDT ETHUSDT          # days, MB, flags, coverage
python -m research.brain.crypto.features build --symbols BTCUSDT ETHUSDT      # [--market crypto-ist] IST-midnight days
python -m research.brain.crypto.labels build --symbols BTCUSDT ETHUSDT
python -m research.brain.crypto.guard --symbols BTCUSDT ETHUSDT               # look-ahead guard on the real cube

# L5: the pre-registered study (design data only) and the knowledge pack
python -m research.brain.crypto.study --symbols BTCUSDT ETHUSDT               # -> results/c1_design.json
python -m research.brain.crypto.window --symbols BTCUSDT ETHUSDT              # C2, Boss's 00:00-09:00 IST window
python3 research/knowledge/build_pack.py && python3 research/knowledge/build_pack.py --check

# checks (what CI runs, plus the crypto catalogue)
python -m pytest research/brain/tests -q
python -m research.brain.features catalog --check
python -m research.brain.crypto.features catalog --check
```

To stay inside the disk budget with the full lake, work a few months at a time: `fetch drive` a span, `cube build`
it, then delete those raw files (a cube day is about 0.25 MB, its features about 0.35 MB, its labels 0.16 MB; the raw
months are not needed again unless CUBE_VERSION changes).

Measured here (one CPU): cube 913 days per symbol in ~3.5 minutes (~0.23 s a day, 0.25 MB a day); features ~6 s a
month; labels ~25 s per symbol; the study ~40 s for two symbols.

## Boss's window: 00:00-09:00 IST (13 Oct 2026)

Boss: "crypto market should only run from morning 12am to morning 9am". `config.WINDOW` = 00:00-09:00 IST
(18:30-03:30 UTC), every day; `markets.window_flags` and the features `in_boss_window` / `min_to_window_end` mark it.
The raw data, cube and features stay 24x7, because the window's own features need the hours before it (a 24-hour
VWAP, a 30-day funding z-score). Every decision, target and trade in `window.py` (study C2,
`research/CRYPTO_C2_WINDOW.md`) is inside the window and flat by 09:00 IST, before NSE opens. Nothing trades crypto.

## What is market-aware (and where)

| Indian brain assumes | crypto | where |
|---|---|---|
| NSE 09:15-15:29 IST, 375 bars, weekdays, holidays | 24x7, UTC, 1440 bars a day, every day; no sessions or holidays | `research/brain/markets.py` (`MARKETS["nse"]`, `["crypto"]`), `config.py` |
| a "day" is the session | the analysis day is UTC midnight, or IST midnight (`--market crypto-ist`, 18:30 UTC) | `markets.Market.analysis_day` |
| windows stop at the session's end | windows run across midnight and weekends | `features/registry.Ctx`, `labels.py` (one continuous "day") |
| expiries, lots, strike steps | perpetuals: no expiry; funding every 8 h (00/08/16 UTC); tick size, quantity step, round-number steps | `config.SPECS`, `config.FUNDING_HOURS` |
| charges in Rs per lot | Binance VIP 0 fees (spot 0.10%, perp 0.02% maker / 0.05% taker), assumed slippage per leg, funding paid or received | `costs.py` |
| time-of-day = minutes since 09:15 | Asia (Tokyo 09:00-17:00), Europe (London 08:00-16:30), US (New York 09:30-16:00) on each city's own clock | `markets.region_flags` |

Reused unchanged: `quality.series_flags` (flags), `cube._on_grid` (grid placement, duplicates), `profile.spread` and
`profile.poc_va` (volume profile), `labels.compute` (targets), `split.walk_forward` and `split.join_forward`, the
look-ahead guard (`guard.check_features`), the feature record, decorator, runner and catalogue renderer
(`features/registry.py`). These took small backwards-compatible options (a registry argument, a perturbation spec, a
horizons argument); the Indian output is byte-identical (FEATURES.md check, the Indian tests).

## L2 clean rules (`clean.py`)

- **Timestamp units.** Binance's archive moved spot klines from milliseconds to **microseconds on 1 Jan 2025**;
  futures stay in ms; funding times carry +15 ms; metrics use date strings. `to_utc` decides the unit per value, so a
  concat of a ms month and a us month lands on the right minutes (tested across 31 Dec 2024 -> 1 Jan 2025).
- Futures files have a header row, spot files none: non-numeric rows are dropped.
- Exact duplicates collapse; conflicting duplicates keep the first row and mark the minute `suspect`.
- A missing minute is NaN with flag `gap` (an outage is a gap, not a flat line). A zero-volume bar is `stale`.
  A 1-minute move above 10% is `suspect`. Features treat gap and suspect spot rows as missing.
- **Slow series** (funding, OI metrics, DVOL) go on the row that contains the instant they become available, so the row
  closes after the value exists, and are carried forward with an age column up to a limit (funding 9 h, metrics 15 min,
  DVOL 2 h); older is NaN. Funding at 08:00:00.015 is on the 08:00 row (known 08:01). An hourly DVOL bar from 07:00 is
  on the 08:00 row. A 5-minute metrics row stamped T describes [T, T+5) (its OI value matches the mark price at
  T+5), so it is on row T+5 (cube v2; v1 put it on row T, up to 4 minutes early). The archive gives no publication
  time, so any extra delay is unknown.

## The cube (`cube.py`)

Columns are listed in the module docstring: spot and perp OHLCV with flags, mark / index / premium-index closes, basis
(perp - spot, USD and bps, only when both bars are ok), mark premium, the last settled funding rate with its age and
interval, minutes to the next settlement (from the clock), OI, OI value, top-trader and all-account long/short ratios,
taker buy/sell ratio, DVOL with its age, and `vol_precision` (`estimate`: volumes are from 1-minute bars).

## Features (`features/`, catalogue `FEATURES.md`)

64 features in seven families: price (returns 1 m - 24 h, ranges, day return and position), vol (realised 1 h / 24 h,
their ratio, ATR on hourly and daily bars, DVOL, DVOL change, DVOL minus realised), volume (USD volume, relative volume
vs the same hour of the last 7 days, taker-buy share, rolling 24-hour VWAP with distance and z, day-anchored VWAP),
profile (rolling 24-hour volume profile: POC, value-area high / low, state; recomputed each hour), levels (previous
day / week high, low, close; nearest small / big round number; today's high / low), derivs (basis and its z / change,
premium 8 h = the live funding estimate, funding in bps / annualised / z over 30 days, minutes to funding, OI change
1 h / 24 h, long/short ratios, taker ratio, perp taker imbalance, two liquidation proxies), calendar (UTC hour, minute
of day, weekday, weekend, Asia / Europe / US hours, Boss's 00:00-09:00 IST window and the minutes left in it).

Adding one: as in the Indian store (`research/brain/README.md`, "Add a feature"), with `features/registry.feature`
from the crypto package, a lookback in minutes (`60m`) or days (`7d`), then
`python -m research.brain.crypto.features catalog`. The tests check every feature automatically.

Not built (no source yet): liquidation prints (the archive has none: `liq_*` are proxies from OI and price), order
book / bid-ask (no history), options chain and IV surface beyond DVOL (the repo's Deribit samples are daily
summaries), ETH DVOL (no file), on-chain and ETF flows (daily files exist in `crypto/data_more`, not wired in).

## Labels, splits and the locked holdout

- `labels.py` = `research/brain/labels.compute` with horizons 60 / 240 / 1440 minutes and no session break.
- **Holdout: every minute from 1 Apr 2026 00:00 UTC on** (`config.HOLDOUT_START`, fixed by date, about the last 6
  months when this was built). `holdout.design()` drops design rows whose label window reaches it;
  `holdout.assert_design()` refuses a frame with holdout rows; `holdout.walk_forward()` is `split.walk_forward` on
  design rows; `holdout.read_holdout(idea)` is the one read per pre-registered idea and records it. The sample fetcher
  does not download holdout months. **No code has read the holdout yet.**

## The look-ahead guard

`guard.py` runs `research/brain/guard.check_features` with the crypto columns: every feature is recomputed with every
row after a cut deleted, and with those rows perturbed (prices scaled, funding / OI / ratios / DVOL replaced, flags
shuffled); values at rows <= the cut must not change. `check_cube` does the same on the builder (raw items that land
after the cut removed or perturbed). Canaries (the next minute's close, the day's final high, a backwards fill) must be
caught. The guard checks the builder against its own placement rules, so a wrong rule (when a value is really known)
needs a data check like the metrics one above. Tests: `research/brain/tests/test_crypto_brain.py`, `test_crypto_features.py`, `test_crypto_study.py`, on
seeded synthetic data (`tests/crypto_synth.py`): the 24x7 grid, funding alignment, the microsecond switch, gaps not
filled, duplicates, costs, the holdout, labels across midnight, warm-up independence, and the guard for all features.

## Data this was built on (13 Oct 2026)

The Drive crypto lake had no crypto files yet, and its branch `claude/crypto-lake` was not pushed, so the sample came
from data.binance.vision: BTCUSDT and ETHUSDT spot, perp, mark, index, premium klines (1 m), funding and 5-minute
metrics, Oct 2023 - Mar 2026 (2,186 files, 416 MB, all checksums matched). Cube: 913 days per symbol, no gap minutes.
DVOL: BTC only (`crypto/data_extra/dvol_1h.csv`). SOL / BNB / XRP have specs in `config.SPECS` and run with the same
commands once fetched.

When the lake is complete: if it keeps the archive's file names, `fetch drive` + `cube build` need no code change. If
it stores another layout (for example Parquet per month), add one rule to `adapters._kind` / `NAME`.
