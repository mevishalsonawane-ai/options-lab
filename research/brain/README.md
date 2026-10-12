# research/brain: L3 Market Cube + L4 Feature Store

The data base layer of the Data Brain (`research/data/ARCHITECTURE.md`, L3–L4 and "Additions before building" items 2
and 5). It turns raw minute data into **one aligned 1-minute picture per session** (the cube). It also computes
**point-in-time features** from the cube, keeps **labels separate**, and has an automatic **look-ahead guard**.
Nothing here trades.

```
sources ──adapters──► DayData ──cube.build_day──► <cache>/cube/<UND>/<YYYY>/<day>.{minutes,chain}.parquet + .json
                                                        │
                                   features (PIT) ◄─────┤────► labels (y_*, future)    ── only split.join_forward() joins them
```

All data stays in a local cache **outside git**: `$IRAALGO_CACHE`, default `~/.cache/iraalgo`.

## Quick start

```bash
python3.11 -m venv .venv && .venv/bin/pip install -r research/brain/requirements.txt
python -m research.brain.cube fetch                        # research-data release files -> cache (gh api or GH_TOKEN)
python -m research.brain.cube build --from 2024-02-13 --to 2026-02-23     # incremental: only new/changed days
python -m research.brain.features build                    # incremental, monthly chunks
python -m research.brain.labels build
python -m research.brain.guard --days 10                   # look-ahead guard on the real cube
python -m research.brain.cube info                         # days, MB, flag counts
python -m research.brain.features list                     # every feature, one line each
python -m pytest research/brain/tests -q                   # what CI runs (fixture data, ~10 s)
```

Options: `--und BANKNIFTY|NIFTY`, `--source auto|release|lake`, `--cache DIR`, `--force`.

Measured on the full release (BANKNIFTY, 498 sessions, 13 Feb 2024 – 23 Feb 2026), one CPU:

| step | time | size |
|---|---|---|
| cube build | ~100 s (0.2 s/day) | **~0.99 MB/day** (minutes ≈ 32 KB + chain ≈ 950 KB), 491 MB total |
| features (56) | ~52 s | 55 MB |
| labels | ~11 s | 24 MB |
| re-run with nothing new | ~2.5 s | |

## Sources (adapters)

An adapter turns a source into `DayData` (index / options / futures / VIX frames of one session, naive IST, bar starts,
values exactly as the source has them). `adapters.pick()` uses the **lake first** for every day it has, and the
**release** for the rest. Each cube day records which source built it and that source's fingerprint, so when the lake
arrives `cube build` rebuilds those days with the better source.

| adapter | source | span | notes |
|---|---|---|---|
| `release` | GitHub release `research-data` (what `research/hunt/r15` used) | BANKNIFTY 13 Feb 2024 – 23 Feb 2026; NIFTY via `nifty_prev/nifty_year` | index + nearest-expiry options ±1,000 pts with OI (`*_wide`), the expiring contract on expiry days (`expiry_days.parquet`, BANKNIFTY from Feb 2025), VIX + BANKNIFTY near-month future (`extra_series.parquet`) |
| `lake` | Dhan data lake: `research/data/CATALOG.json` + files fetched with `research/data/fetch_release.py --dest $IRAALGO_LAKE` (default `<cache>/lake`) | 2017/2021 → 2026 (per README there) | coded against the planned schema: `index_1m_<SYM>_<YEAR>`, `opt_<UND>_WEEK_<YEAR>` (rolling ATM offset, `iv`, `oi`, `spot`), `fut_1m_live_index`. ts tz-aware → naive IST. If the catalog or files are absent, `available()` is False and the build **skips it cleanly** |

**How the data lake plugs in:** once the data-lake session publishes `CATALOG.json` and the release, run
`python research/data/fetch_release.py --match index_1m_BANKNIFTY --match index_1m_INDIAVIX --match opt_BANKNIFTY_WEEK --dest ~/.cache/iraalgo/lake`
and then `python -m research.brain.cube build` (`--source auto`). No code change is needed. Things to know:

- Dhan option rows carry no expiry date. The adapter takes it from `calendar.py`, using contracts seen in the data
  first and NSE weekday rules otherwise (`source=rule` in the day manifest). Check the rules in `calendar.RULES`
  against VALIDATION.md when the lake lands.
- Dhan's own `iv` is kept as `iv_vendor` next to our Black-Scholes `iv`, so the two can be compared.
- Index `volume` from Dhan is 0. It is stored as NaN ("no volume"), not as zero volume.
- Expired futures have no 1-minute history in Dhan (only live contracts), so `basis` exists only where a future minute
  exists.

`tests/test_lake_adapter.py` converts the release fixture into the lake's schema. It checks that the lake path gives
the same index, VIX, expiry and ATM-chain prices as the release path.

## The cube

### `<day>.minutes.parquet`: 375 rows (09:15 … 15:29, bar starts; row t is known at t + 1 min)

| column | meaning |
|---|---|
| `ts, day, minute` | naive IST bar start; session date; 0 … 374 |
| `open high low close volume` | index bar (volume NaN: indices have none) |
| `flag` | index quality flag (below) |
| `fut_close fut_volume fut_oi fut_flag` | near-month future (release: close only) |
| `basis, basis_pct` | future − index, only when both bars are `ok` this minute |
| `vix, vix_flag` | India VIX |
| `atm_strike` | index close rounded to the strike step (BANKNIFTY 100, NIFTY 50); NaN if the index bar is not ok |
| `expiry, dte_days, is_expiry_day, events` | the chain's expiry, calendar days to it, expiry day (scheduled), events.csv kinds today |
| `chain_ok_share` | share of the 42 chain rows that traded this minute |

### `<day>.chain.parquet`: 375 × 21 offsets (ATM−10 … ATM+10) × 2 sides = 15,750 rows

| column | meaning |
|---|---|
| `ts, offset, side, strike, expiry` | strike = this minute's ATM + offset × step; nearest expiry (the expiring contract on expiry day when the source has it) |
| `open high low close volume` | that contract's bar this minute (NaN if none) |
| `ltp, ltp_age` | **last real trade at or before t** (that contract, same day), and how many minutes old it is |
| `oi, oi_chg_1m, oi_chg_day` | last OI the exchange reported at or before t; change vs the previous minute; change vs the contract's first OI today |
| `iv, delta, gamma, theta, vega` | Black-Scholes on rows that traded this minute (below) |
| `iv_vendor` | the source's IV if it has one (Dhan); NaN for the release |
| `flag` | quality flag |

### Quality flags: never invented values

| flag | index / futures / VIX | option chain row |
|---|---|---|
| `ok` | a real bar that passed the checks | the contract traded this minute |
| `stale` | the 3rd+ consecutive flat bar repeating the previous close (flagged from that bar on, never backwards) | no trade this minute; `ltp` is the last real trade (`ltp_age` > 0). The release archive pads untraded minutes with volume-0 flat copies, and those count as **not traded** |
| `gap` | no bar: values NaN | no trade yet today or the strike is outside the source's range: `ltp` NaN |
| `suspect` | OHLC inconsistent, ≤ 0, or a 1-minute move > 3% (VIX > 20%) | price ≤ 0, high < low, or below intrinsic by > max(0.2% of spot, 5) |

Missing minutes are never interpolated. Off-grid bars (e.g. index bars stamped after 15:29) are dropped and counted.
Exact duplicates are collapsed. Conflicting duplicates keep the first row, the minute is marked `suspect`, and the
count goes in the manifest. Features treat `gap` and `suspect` index rows as missing.

### IV and Greeks (assumptions, `config.py`)

- European Black-Scholes on **spot** (the index close of the same minute), **r = 6.5%** flat, **q = 0**.
- Expiry at **15:30 IST** on the expiry date. T is the calendar time from the **end** of bar t, divided by 365 days.
- IV is solved only on `ok` rows priced ≥ ₹0.10 (two ticks). It is NaN, never clipped, when the price is outside
  the no-arbitrage band for IV in [1%, 300%]. It is also NaN when IV is not identifiable: a 1-IV-point change moves
  the price by less than one tick (deep ITM near expiry).
- Theta is per calendar day; vega is per 1 IV point.
- Effect of the assumptions: with a few days to expiry, a 1-point error in r or ignoring the ~1% dividend yield moves
  IV by < 0.1 point, which is far below the bid-ask noise. Using the futures-implied forward is a possible later
  refinement (it needs futures with the option's own expiry).

### Calendar (`calendar.py`, `calendar_data/`)

- `expiries.csv`: expiry dates of the contracts actually present in the release (`python -m research.brain.cube
  expiries`). The archive lists the Jan-2025 BANKNIFTY monthly under 29 Jan for contracts listed before NSE's switch
  to Thursday expiries. `release.EXPIRY_MOVED` maps it to the real 30 Jan. Outside the data's span the NSE weekday
  rules in `calendar.RULES` apply, with a holiday moving the expiry to the previous session.
- Holidays: `calendar.holidays_inferred(sessions)` lists weekdays without a session. This is "holiday or missing data",
  and the lake's VALIDATION.md tells the two apart.
- **Events:** `calendar_data/events.csv` (`date, und, kind, name, source`) holds Union Budget days and RBI MPC
  decision days for 2024–2025. To add RBI 2026, results, US CPI or anything else **scheduled in advance**, add rows
  there. Each row is one line, and `is_event_day` and the cube's `events` column pick it up. Verify new rows against
  the official source before relying on them.

### Incremental builds and the compute budget

A cube day is rebuilt only when its manifest is missing, the `CUBE_VERSION` changed, or the source's fingerprint
changed. A feature day is recomputed only when the feature signature (names + versions + cube version) changed, or the
sha256 of any cube day it reads changed (the day + its warm-up sessions). A nightly top-up therefore touches only the
new days. The whole history rebuilds in under 3 minutes on one CPU.

**Bump `CUBE_VERSION` (config.py) when cube rules change, and a feature's `version` when its definition changes.**

## Features (L4)

The catalogue is [`FEATURES.md`](FEATURES.md), generated from the code. It holds 56 features in five families:

- **price**: returns 1/5/15/60 m, session return, gap, 15-m range, ATR, realised vol, distance from the previous day's
  H/L/C and pivot, a typical-price average (equal-weight VWAP proxy, since the index has no volume), position in the
  day's range, and minutes since the day's high/low.
- **structure**: 15/30-minute opening range (width, distances, state), **the app's Liquidity levels**, inside day and
  previous range. The Liquidity levels use the arm's own Liquidity Swings 20 + Pools 2/5/10 from
  `indicator/liquidity.py` on 5-minute bars, continuous across sessions: the distance to the nearest active level
  above and below, the breaks today and the last break's direction.
- **options**: PCR (±10, ±3), OI build-up for calls and puts and their net, ATM IV and its 15-minute change, skew,
  smile, straddle % and its decay since 09:20, max-pain distance, and chain quality.
- **market**: VIX level and its 15-minute and day change, futures basis and its change on the day.
- **calendar**: minute, weekday, DTE, expiry day, event day, and days since the previous session.

Not yet built (no source yet): breadth (bank constituents: `bank_stocks_year.parquet` exists in the release),
FII/DII, GIFT Nifty, MCX (evening session, a separate grid), and execution features from L8.

### Add a feature

Put it in the family module (`features/price.py`, …):

```python
@feature("ret_30m", "price", 1, ["minutes.close"], "30m", "Index log return over the last 30 minutes, %.")
def ret_30m(ctx):
    return np.log(ctx.c / ctx.shift_in_day(ctx.c, 30)) * 100      # one value per cube minute
```

Rules:
- Return one value per cube row (`ctx.n`).
- Use only rows ≤ t. Use the helpers for this: `shift_in_day`, `groupby_day(...).cummax()`, `rolling` within the day,
  `day_first` (a session value known from the row it appears on), `prev_session(day_complete_agg(...))` (the
  previous session's value), and `chain_arr(col, side)` (an n × 21 array).
- Never use a whole-day or whole-sample statistic (for example a day's final high, or a quantile of the sample).
- Declare `lookback` in sessions (`"12d"`) when you read earlier days, so the warm-up is loaded.

Then run `python -m research.brain.features catalog` (it rewrites FEATURES.md, and CI fails if you forget) and
`pytest research/brain/tests`. The guard checks the new feature automatically.

## Labels and training

`labels.py` writes `y_ret_{15,30,60}m`, `y_range_*`, `y_mfe_*` and `y_mae_*`. These are moves over t+1 … t+h of the
**same** session; a window that runs past 15:29 gives NaN. Labels are stored under `<cache>/labels/`, never with the
features.

`split.join_forward(features, labels)` is the only place the two meet. It refuses a feature named `y_*`, a label not
named `y_*`, or any shared name. `split.walk_forward` and `split.purged_kfold` build folds by session. Each fold
**purges** training rows whose label window `[t, t+horizon]` reaches the test block (default horizon 60 minutes) and
**embargoes** training rows in the day after the test block (k-fold).

## The look-ahead guard (item 2)

`guard.py`, run by `tests/test_lookahead.py` and `tests/test_cube.py` in CI (`.github/workflows/brain.yml`):

1. **Every feature:** at 10 cut minutes (session open, end of the opening range, closes, random minutes), all
   features are recomputed twice. Once with **every row after t deleted**, and once with those rows **perturbed**
   (prices scaled at random, VIX/basis/OI/IV scrambled, flags shuffled). Each value at every row ≤ t must be
   identical.
2. **The cube builder itself:** the same check on raw source rows. A cube row at t may not depend on raw data after
   t. This check caught two rules in this package's own first draft: a "stale" flag marked backwards, and an expiry
   choice that counted the whole day's rows.
3. **Canary:** two deliberately leaky features (the next minute's close, and the day's final high) **must** be caught.
   This proves the guard can fail.
4. **Labels never in features:** no `y_` feature names, no feature module imports `labels` or `split`, and
   `join_forward` refuses name clashes.
5. **Splits:** no training row's label window touches the test block, no training row sits in the embargo, and
   walk-forward trains strictly before the test block.
6. **Warm-up independence** (`python -m research.brain.guard`): features computed with the declared warm-up equal
   those computed with 25 more sessions. An incremental build therefore equals a full rebuild. This is what capped
   the Liquidity levels' zone age at 10 sessions.

`python -m research.brain.guard --days 10 --cuts 8` runs checks 1 and 6 on the real cube.

## Known limits of the current data (release)

- Options are the nearest expiry within ±1,000 points of the 09:20 index. Offsets that move outside that range during
  the day are `gap`, which is about 12% of chain rows.
- On expiry days before Feb 2025, BANKNIFTY has no same-day chain in the release. On those days the chain is the next
  expiry, and `is_expiry_day` still comes from the calendar.
- The release's BNFUT is a continuous near-month series with no OI or volume, and its basis jumps at the roll. NIFTY
  has no futures in the release.
- There is no bid/ask anywhere in history (Dhan has none either). Execution features need the app's own recordings
  (ARCHITECTURE item 1).
