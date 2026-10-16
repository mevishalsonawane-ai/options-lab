# Gold lab pack: XAUUSD 1-minute bid / ask, features, labels and an NN dataset

*Built 15 Oct 2026 for Boss ("Find a graphical way, like a neural network or any better way, to save the data for
finding strategies"). The data lives on Google Drive only (`gdrive:IraAlgo-data-lake/xauusd_1m/`). Git holds the code
(this folder), its tests (`research/data/tests/test_xau_lab.py`), the event calendar and this card, never the data.*

## Source and licence

- **Source:** Dukascopy Bank's free historical datafeed, XAUUSD 1-minute BID and ASK candles, one file per UTC day and
  side (`fetch_duka_xau_bidask.py` documents the URL and the 24-byte record format). Spot gold against the US dollar,
  USD an ounce, quoted by one ECN broker: not COMEX futures, not MCX GOLD.
- **Licence:** Dukascopy's free data, for research use. Do not redistribute it outside IraAlgo.
- **Window:** 15 Oct 2025 to 15 Oct 2026 (UTC days). Exact first / last minute, row counts and gaps are in `qa.json`
  on Drive and in the table below.

The numbers of the built pack (rows, first / last minute, gaps, flag counts, spread by hour, shard sizes) are in
`qa.json`; the Drive copy of this card has them filled in below this line by `xau_build.py`.

<!-- STATS -->

## Definitions

- **Time:** `ts_utc` is the minute's OPEN time (UTC). Every value on a row is known at its CLOSE (`ts_utc + 1 min`).
  `ts_ist` is the same instant in IST.
- **Bid / ask / mid / spread:** `*_bid` and `*_ask` are Dukascopy's own candles per side. Mid open / high / low /
  close = (bid + ask) / 2 field by field (the mid high and low average the two sides' extremes, which can differ a
  little from the true highest mid when the sides peaked at different seconds). `spread = close_ask - close_bid` (USD
  an ounce, at the close), `spread_open` the same at the open.
- **Tick volume:** Dukascopy's per-side volume field; `volume = volume_bid + volume_ask`. It is one broker's activity,
  not exchange volume. Minutes where both sides have zero volume (market closed) were dropped by the fetch, so the
  clean table has no rows inside the weekend closure.
- **Trading day:** the New York date of `ts + 7 h` (the day rolls at 17:00 New York, the usual metals day; a Sunday
  evening open belongs to Monday). Daily bars and "previous day" levels use it.
- **Sessions** (local wall clocks, so daylight-saving shifts are handled): `asia` 00:00 UTC to the London open;
  `london` 08:00 London to the New York open; `overlap` 08:00 New York to 16:30 London; `ny` 16:30 London to 17:00
  New York; `off` 17:00 New York to 00:00 UTC.
- **US events:** `us_events.csv` lists every CPI, Employment Situation (NFP) and FOMC statement release from Sep 2025
  to Nov 2026 with its official source (BLS release archive and October 2026 schedule, Federal Reserve FOMC
  calendar). CPI / NFP at 08:30 New York, FOMC statements at 14:00 New York. The calendar is the only source: nothing
  is inferred from price moves. The 2025 federal funding lapse cancelled the October 2025 CPI and jobs reports and
  delayed others; the file holds the actual release dates.
- **Quality flags** (rows are marked, never deleted): `flag_zero_vol_side`, `flag_flat`, `flag_stale` (5+ minutes with
  an unchanged mid close), `flag_crossed` (spread <= 0 or high < low), `flag_out_of_hours` (weekend closure or the
  17:00-18:00 New York break), `flag_wide_spread` (spread > 5x the median of the previous 1440 rows) and
  `flag_bad_tick`, the `research/data/spikes.py` idea adapted to gold: the mid close sits more than J away from BOTH
  neighbours on the same side and the next close is back within J of the previous one, or a wick is longer than 2 J,
  where J = max(12 x the median absolute 1-minute mid change of the previous 60 rows, 1.00 USD). It needs the NEXT
  minute, so the feature store only carries `bad_tick_lag1`.

## Files on Drive (`gdrive:IraAlgo-data-lake/xauusd_1m/`)

| file | what |
|---|---|
| `raw/xauusd_1m_bid_ask_2025-10-15_2026-10-15.parquet` | the fetch output as downloaded |
| `xauusd_1m_clean.parquet` | the clean base: raw columns + mid OHLC, spread, clock, session, event clock, flags |
| `bars/xauusd_{5m,15m,1h,4h,1d}.parquet` | multi-timeframe bars (below) |
| `xauusd_1m_features.parquet` | the feature store, one row per clean minute |
| `xauusd_1m_labels.parquet` | forward labels, SEPARATE file, same rows and order as the features |
| `nn/` | the NN dataset: `splits.json`, `norm_stats.json`, `dataset.json`, `{split}_{YYYY-MM}.npz`, `gaf64_*.npz` |
| `overview.html` | Boss's one-page visual summary (also in git when small) |
| `qa.json`, `MANIFEST.txt`, `README.md`, `us_events.csv` | checks, sha256 of every file, this card, the calendar |

### Bars (`xau_bars.py`)

5m / 15m / 1h / 4h are UTC clock buckets; 1d is the trading day. Columns: `ts_utc` (bar open), `ts_end` (when it is
complete), mid `open high low close`, `open_bid open_ask close_bid close_ask`, `spread_mean spread_min spread_max
spread_last` (of the per-minute close spreads), `volume_bid volume_ask volume`, `n_minutes`, `n_flagged`; 1d adds
`trading_day`.

### Feature store (`xau_features.py`)

Every value is computed on XAUUSD itself (signal on the underlying) and is known at the row's close; the test suite
truncates the data after each test row and checks every column is unchanged (look-ahead guard), and the build repeats
that check on the real year. Higher-timeframe values come from the last COMPLETED bar only. "ATR units" = divided by
`atr14_15m`.

| column(s) | meaning |
|---|---|
| `ret_{1,5,15,60,240}` | log return of the mid close against the last row at or before t - h minutes (clock) |
| `atr14_{1m,5m,15m,1h,4h,1d}` | Wilder ATR(14) of mid bars; 1d = the previous trading day's value |
| `rv_30`, `rv_240` | realised volatility: sqrt of the summed squared 1-min log returns over 30 / 240 rows |
| `day_pos`, `sess_pos` | (close - low so far) / (high so far - low so far) in this trading day / session (0.5 when flat) |
| `vwap_sess`, `dist_vwap_sess` | session VWAP of the typical mid price weighted by tick volume; distance in ATR units |
| `pdh pdl pdc`, `dist_pd*` | previous trading day high / low / close; distance in ATR units |
| `asia_high asia_low asia_done`, `dist_asia_*` | today's Asia range (still forming while `asia_done` = 0) |
| `swing_hi_{15m,1h}`, `swing_lo_*`, `dist_swing_*` | last confirmed fractal swing (3 bars each side, confirmed 3 bars later) |
| `trend_{15m,1h}`, `event_{15m,1h}`, `bars_since_break_*` | structure state; event +1 BOS up, +2 CHoCH up, -1 / -2 down |
| `poc_prev vah_prev val_prev`, `dist_*` | previous session's volume profile (0.50 USD buckets, 70% value area) |
| `spread`, `spread_bp`, `spread_z` | spread in USD, in basis points of the mid, z-score vs the last 1440 rows |
| `vol_log`, `vol_z` | log(1 + tick volume) and its z-score vs the last 1440 rows |
| `session`, `ist_minute`, `utc_minute`, `dow_utc`, `dow_trading`, `gap_min`, `weekend_open` | clock |
| `min_to_event`, `min_since_event`, `next_event` | minutes to / since the nearest listed CPI / NFP / FOMC release |
| `flag_*` (except bad tick), `bad_tick_lag1` | quality flags known at the close |

BOS / CHoCH follow `research/hunt/r52/fam_misc.py` `matrix_v22` (the same as `research/matrix_v22.py`): a bar is a
swing high when its high is the highest of the 3 bars before and the 3 after (confirmed at the 3rd bar after); a close
above the last unbroken swing high is a break up, CHoCH when the trend was down, else BOS, and that level is used up.
(`research/hunt/r62` was not on main when this was built, so the r52 definition is the one used.)

### Labels (`xau_labels.py`, separate file)

| column(s) | meaning |
|---|---|
| `fwd_ret_{5,15,60,240}` | log(mid close at the last row at or before t + h min / mid close at t) |
| `fwd_cover_{h}` | rows in (t, t + h] / h; below 1 means the horizon crosses missing minutes or a closure |
| `{long,short}_mfe_{60,240}`, `_mae_` | max favourable / adverse excursion in USD over minutes t+1 .. t+H. Long enters at the next row's ASK open and is measured on the BID; short enters at the next row's BID open and is measured on the ASK. No entry (NaN) when the next row starts more than 5 minutes later |
| `{long,short}_hit_{60,240}` | +1 = +1R target touched before the -1R stop within H minutes, -1 = stop first (a minute touching both counts as the stop), 0 = neither |
| `{long,short}_tth_{60,240}` | minutes from t to the first touch |
| `R_atr15` | R = `atr14_15m` at t |

Labels are net of the spread (entry at one side, exit at the other) but NOT of broker fees: subtract the fees of the
broker you would use (for example $7 a 100 oz lot round trip, as in `research/liquidity_gold.py`) per trade.

### NN dataset (`xau_nn.py`, `nn/`)

Each sample is anchored at the close of a minute t, every 5 minutes (UTC minute divisible by 5):

| array | shape | dtype | what |
|---|---|---|---|
| `x1` | (n, 120, 12) | float16 | the last 120 one-minute rows: `ret_1 hl_range body spread_bp spread_z vol_log vol_z gap_log rv_30 sess_pos day_pos dist_vwap_sess` |
| `x15` | (n, 48, 7) | float16 | the last 48 completed 15-minute bars: `ret hl_range body close_pos spread_bp vol_log fill` |
| `ctx` | (n, 42) | float16 | context at t (returns, ATR / price per timeframe, distances to levels, structure, session one-hot, time of day / week, event clock); names in `dataset.json` |
| `y` | (n, 17) | float32 | `fwd_ret_{5,15,60,240}`, `{long,short}_{hit,mfe,mae}_{60,240}`, `R_atr15` (NaN where undefined) |
| `ts`, `win_start`, `h15_start`, `label_end` | (n,) | int64 | ns UTC: anchor minute open, first 1-minute row, first 15-minute bar, end of the 240-minute label horizon |
| `gaf` (in `gaf64_*`) | (n, 64, 64) | uint8 | EXPERIMENTAL Gramian Angular Summation Field of the last 64 mid closes, every 15 minutes; G = byte / 127.5 - 1 |

Inputs are normalised as (x - mean) / std with statistics fitted on the TRAIN span only (`norm_stats.json`), clipped
to +-8, NaN set to 0. Session one-hot, trend, break events and `asia_done` are not scaled.

**Splits** (`splits.json`): `final_test` = the last 3 months, `validation` = the 3 months before, `train` = the rest.
A sample belongs to the part holding its anchor and is dropped when its 120-minute window, its 48-bar 15-minute window
or its 240-minute label horizon reaches outside that part, so nothing crosses a boundary (tested). Shards are
`{split}_{YYYY-MM}.npz`, so the final test sits in its own files. `walk_forward_folds` gives expanding-window folds
(train from the start, validate one month) inside the validation span; filter a fold's samples with
`xau_nn.in_span(z, start, end)` so they also stay clear of the fold's own boundaries.

**About the images (experimental).** The Gramian Angular Field turns a price window into a picture a CNN can read, the
"graphical" encoding Boss asked about. It is a way to explore, not a promise: our earlier neural-network studies
(R41, R46 v2, the R57 direction model) found price-only models close to a coin flip after costs. Treat any image model
like any other idea: pre-register it, tune on train / validation only, and read the final test once.

## Loading

```python
import json, numpy as np, pandas as pd
feat = pd.read_parquet("xauusd_1m_features.parquet")
lab = pd.read_parquet("xauusd_1m_labels.parquet")          # join on ts_utc ONLY to score a rule
m15 = pd.read_parquet("bars/xauusd_15m.parquet")

z = np.load("nn/train_2026-01.npz")
x1, x15, ctx, y = z["x1"], z["x15"], z["ctx"], z["y"]    # (n,120,12) (n,48,7) (n,42) (n,17)
names = json.load(open("nn/dataset.json"))["names"]
hit = y[:, names["labels"].index("long_hit_240")]

import glob, torch
from torch.utils.data import TensorDataset, DataLoader
def load(split):
    zs = [np.load(f) for f in sorted(glob.glob(f"nn/{split}_*.npz"))]
    cat = lambda k: torch.from_numpy(np.concatenate([z[k] for z in zs]).astype("float32"))
    return TensorDataset(cat("x1"), cat("x15"), cat("ctx"), cat("y"))
train = DataLoader(load("train"), batch_size=256, shuffle=True)
val = DataLoader(load("validation"), batch_size=1024)
# final_test: load ONCE, after every choice is frozen.
```

## Honesty rules (CLAUDE.md)

- Pre-register an idea and log it in `research/experiments/entries/`, failures included; count every variant.
- Walk-forward only: tune on train / validation; the final test (last 3 months) is read once.
- Costs = the spread (already in the labels) + broker fees and slippage on top. Give a sample size with every number;
  apply Benjamini-Hochberg across the ideas tried.
- Any gold (or crypto) strategy that comes out of this pack is tested and run with a stop-loss AND a profit lock.
- Signals are computed on XAUUSD itself; the feature store has no option-price inputs.

## Rebuild

```
python -I research/data/xau/fetch_duka_xau_bidask.py RAW.parquet          # ~2 files a minute when throttled
```

The fetch is resumable (16 Oct 2026): every downloaded day and side is kept in `~/.cache/iraalgo/xau_duka/` (outside
git; `--cache DIR` to move it), so after a container restart just run the same command again and it downloads only
the days it does not have yet. Days that failed on the network are not cached and are tried again on the rerun; a
throttled server (HTTP 429) is waited out (30 s, 60 s, ... up to 5 minutes a step). Run it detached with a log:

```
setsid nohup python -I -u research/data/xau/fetch_duka_xau_bidask.py RAW.parquet > fetch.log 2>&1 < /dev/null &
# once fetch.log ends with "rows ... missed [] 0":
python research/data/xau/xau_build.py RAW.parquet OUT_DIR --upload         # clean, bars, features, labels, nn, html
python -m pytest research/data/tests/test_xau_lab.py -q
```
