# research/brain: L3 Market Cube (+ levels, profiles) + L4 Feature Store

The data base layer of the Data Brain (`research/data/ARCHITECTURE.md`, L3–L4 and "Additions before building" items 2
and 5). It turns raw minute data into **one aligned 1-minute picture per session** (the cube). It also computes
**point-in-time features** from the cube, keeps **labels separate**, and has an automatic **look-ahead guard**.
Nothing here trades.

```
sources ──adapters──► DayData ──cube.build_day──► <cache>/cube/<UND>/<YYYY>/<day>.{minutes,chain}.parquet + .json
                                                        │
                                                        ├────► tables: <cache>/{levels,profiles}/<UND>/<YYYY>/<day>.parquet
                                   features (PIT) ◄─────┤────► labels (y_*, future)    ── only split.join_forward() joins them
```

All data stays in a local cache **outside git**: `$IRAALGO_CACHE`, default `~/.cache/iraalgo`.

## Quick start

```bash
python3.11 -m venv .venv && .venv/bin/pip install -r research/brain/requirements.txt
python -m research.brain.cube fetch                        # research-data release files -> cache (gh api or GH_TOKEN)
python -m research.brain.cube build --from 2024-02-13 --to 2026-02-23     # incremental: only new/changed days
python -m research.brain.features build                    # incremental, monthly chunks
python -m research.brain.tables build                      # L3 levels + profiles tables, incremental
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
| features (56, cube v1) | ~52 s | 55 MB |
| labels | ~11 s | 24 MB |
| re-run with nothing new | ~2.5 s | |

Not yet re-measured on the full release for cube v2 and 149 features (the release is not downloaded in this
environment). From synthetic 275-session runs: the minutes-only features take ~8 s per monthly chunk (the 252-session
warm-up that IV rank needs is now loaded as minutes only; the chain is loaded for its own 1-session warm-up), so a full
rebuild is about 4 minutes. The `levels` and `profiles` tables take ~3 s and ~5 s per monthly chunk; a day of
`profiles` is ~18 KB (375 developing rows) and of `levels` ~11 KB.

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
| `fut_open fut_high fut_low fut_close fut_volume fut_oi fut_flag` | the near-month future (release: OHLC, no volume or OI) |
| `fut_expiry, fut_roll` | the contract in `fut_*` (NaT for the release's continuous series); True on the roll day (below) |
| `vol_precision` | `estimate` where this minute has futures volume from a 1-minute bar, `none` without volume; `exact` is reserved for values rebuilt from recorded ticks |
| `basis, basis_pct` | future − index, only when both bars are `ok` this minute |
| `vix, vix_flag` | India VIX |
| `atm_strike` | index close rounded to the strike step (BANKNIFTY 100, NIFTY 50); NaN if the index bar is not ok |
| `expiry, dte_days, is_expiry_day, events` | the chain's expiry, calendar days to it, expiry day (scheduled), events.csv kinds today |
| `chain_ok_share` | share of the 42 chain rows that traded this minute |
| `atm_iv` | mean IV of the ATM call and put that traded this minute (from the chain) |
| `oi_max_call_strike, oi_max_put_strike` | strike with the largest last-reported call / put OI over ATM±10 this minute (NaN with < 10 strikes reporting) |

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

### Futures volume and the roll (cube v2)

The index has no volume, so everything volume-based (VWAP, relative volume, the volume profile) uses the **near-month
future**. The lake adapter (`adapters.lake.pick_future`) takes the nearest contract that expires on or after the day,
except on its **expiry day (the roll day)**, when it takes the next month: by then the volume has moved there, and a
session never mixes two contracts. `fut_roll` marks that day. Anything that spans sessions on futures prices (the
anchored VWAPs from the previous day, the previous POC, the week profile) is NaN or restarts across a contract change.
The release's BNFUT is a continuous series with no volume, so on release-built days every volume field is NaN and
`vol_precision` is `none`.

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

## L3 tables beside the cube: `levels` and `profiles`

`python -m research.brain.tables build` writes them from the cube (minutes only), incrementally like the feature store
(rebuilt when the formula version or a cube day they read changes). Both are recomputed from code, never stored raw.

**`profiles`** (`profile.py`): the volume profile ("vertical volume") of the near-month future. Each 1-minute bar's
volume is spread uniformly over its high-low range into 10-point bins (BANKNIFTY; NIFTY 5). Rows per session:

| kind | what | valid_from |
|---|---|---|
| `dev` | the **developing** profile's POC / VAH / VAL after each minute | that minute's row |
| `day` | the final session profile: histogram (`b0`, `bin`, `hist`), POC, VAH / VAL (70% value area), HVN / LVN lists, volume | **15:30**, the close |
| `week` | the ISO week so far through this session's close, same contract (a roll starts a new week profile) | 15:30 |

All carry `precision` (`estimate`) and `formula_version`. **Only the developing profile may feed intraday features;**
a final profile is valid only after the close. `profile.asof(table, ts)` reads the table that way.

**`levels`** (`levels.py`): one row per support/resistance level per session, in index points: `price, type, code,
source, formula_version, valid_from, held, broke, tests, strength, stats_sessions, precision`. Types: previous day /
week / month high, low and close; classic pivots (P, R1-3, S1-3) and CPR (TC, BC); 5-minute swing highs and lows;
round numbers (BANKNIFTY 100 and 500, NIFTY 50 and 100); the 15-minute opening range; the previous session's
POC / VAH / VAL (moved to index points with that session's last basis); the max-OI call (resistance) and put (support)
strikes at the previous close; and the gap-fill level. `held` / `broke` count how levels of the same kind did on the
**previous 60 sessions only** (per type, and per type and price for swings and round numbers); the session itself
never counts towards its own row. The rules (touch within 0.03%, break by a close 0.1% through it) are in the
`levels.py` docstring.

## Features (L4)

The catalogue is [`FEATURES.md`](FEATURES.md), generated from the code. It holds 149 features in ten families (plus 6
breadth fields registered as **pending data**):

- **price**: returns 1/5/15/60 m, session return, gap, 15-m range, ATR, realised vol, distance from the previous day's
  H/L/C and pivot, a typical-price average (equal-weight VWAP proxy, since the index has no volume), position in the
  day's range, and minutes since the day's high/low.
- **structure**: 15/30-minute opening range (width, distances, state), **the app's Liquidity levels**, inside day and
  previous range. The Liquidity levels use the arm's own Liquidity Swings 20 + Pools 2/5/10 from
  `indicator/liquidity.py` on 5-minute bars, continuous across sessions: the distance to the nearest active level
  above and below, the breaks today and the last break's direction.
- **volume** (futures, precision `estimate`): volume per minute and so far today, relative volume (same minute,
  previous 20 sessions), session VWAP (= anchored at the open) with ±1σ / ±2σ bands, distance and z-score, and VWAPs
  anchored at the previous session's high and low.
- **profile**: distance to the developing POC, VAH and VAL, the value-area state and width, the developing week POC,
  and the previous session's POC.
- **levels**: distance to the nearest level above and below (from the `levels` table logic), its type and strength,
  and the number of levels within 0.2%.
- **indicators** (1-minute, across sessions): EMA 9/20/50/200 and the distance from each, Supertrend (10, 3), RSI 14,
  MACD (12, 26, 9), ADX 14 with ±DI, ATR 14, Bollinger (20, 2) %B and width, Donchian 20, and the 15-minute, 1-hour
  and daily trend (EMA slope sign).
- **candles** (5- and 15-minute bars): inside, outside, engulfing, big bar (> 2× ATR), upper/lower wick ratio, run of
  same-colour bars.
- **options**: PCR (±10, ±3) of OI and of volume (day, 15 minutes), OI build-up for calls and puts and their net, ATM
  IV and its 15-minute change, IV rank and percentile (252 previous sessions), skew, smile, Black-Scholes Greeks at ATM
  and ATM±2 (`bs.py`), straddle % and its decay since 09:20, expected move from the straddle and from VIX, max-pain
  distance, and chain quality.
- **market**: VIX level and its 15-minute and day change, futures basis and its change on the day.
- **calendar**: minute, weekday, DTE, expiry day, event day, and days since the previous session.

Feature-store files also carry a `precision` column per row (from the cube's `vol_precision`), and
`split.check_precision` refuses a fold that trains on `estimate` rows and tests on `exact` ones (or the reverse).

**Pending data** (in FEATURES.md, never computed): breadth for BANKNIFTY and NIFTY (advance/decline, share above VWAP,
heavyweight contribution). No adapter loads constituent bars: the release's `bank_stocks_year.parquet` (12 BANKNIFTY
names) is read only by research scripts and has no index weights, and there is no NIFTY 50 constituent data. Also not
built (no source yet): FII/DII, GIFT Nifty, MCX (evening session, a separate grid), order flow (from the live tick
recording) and execution features from L8.

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
- Declare `lookback` in sessions (`"12d"`) when you read earlier days, so the warm-up is loaded. The chain is loaded
  only for the warm-up of features with `chain.*` inputs, so keep long-history features on the minutes table.
- A field with no data source yet is registered with `pending(...)`, never as a column of placeholder values.

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

**What-if labels (R24, 13 Oct 2026).** `whatif_labels.py` turns R24's what-if table into 32 `y_wi_*` labels per decision
minute: what buying a call or put would have done, net of the app's charges and the spread (ITM1 / ATM / OTM1, CE / PE:
net % at +30 min and 15:05, best and worst price, ORB-exit rupees; the best of the 14 options and the share that won).
The table is `research/hunt/r24/whatif.py` (`<cache>/whatif`, on Drive under `IraAlgo-data-lake/derived/r24_whatif`).
`python -m research.brain.whatif_labels build` writes `<cache>/labels_whatif/<UND>/<YYYY>/<day>.parquet`, keyed by `ts`
= the decision minute, so `split.join_forward` puts the features known at t next to what happened after t. No feature
reads them (`tests/test_whatif_labels.py`).

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
   the Liquidity levels' zone age at 10 sessions. `tests/test_dictionary.py` checks each new feature's own declared
   lookback the same way (on 30 sessions made from the fixture); it is why the 1-minute indicators declare 10 sessions
   and Bollinger bands compute each window exactly.
7. **Profiles and levels** (`tests/test_dictionary.py`): volume, profile and level features pass check 1 on the
   lake-format fixture, which has futures volume. A final day profile has `valid_from` = 15:30 and equals the developing
   profile at the last bar; reading the table "as of" an intraday minute never returns that day's final profile; a
   canary feature that uses the same day's final POC **must** be caught. Level counts do not change when later
   sessions are deleted, nor when the session's own bars are scrambled.

The volume fields are exercised on SYNTHETIC futures volume made inside the tests (`conftest.to_lake`, seeded): the
release has none. It never leaves the test's temporary directory.

`python -m research.brain.guard --days 10 --cuts 8` runs checks 1 and 6 on the real cube.

## Known limits of the current data (release)

- Options are the nearest expiry within ±1,000 points of the 09:20 index. Offsets that move outside that range during
  the day are `gap`, which is about 12% of chain rows.
- On expiry days before Feb 2025, BANKNIFTY has no same-day chain in the release. On those days the chain is the next
  expiry, and `is_expiry_day` still comes from the calendar.
- The release's BNFUT is a continuous near-month series with no OI or volume, and its basis jumps at the roll. NIFTY
  has no futures in the release. So **volume, VWAP, the profiles and the profile levels are NaN on release-built
  days**. They fill in from the lake's `fut_1m_live_index`, which has only contracts live on its download day (Dhan
  keeps no expired-futures minutes), so their history starts when the lake starts recording futures.
- IV rank and percentile need 252 earlier sessions and the daily trend 21, so they are NaN for the first year / month
  of any history.
- There is no bid/ask anywhere in history (Dhan has none either). Execution features need the app's own recordings
  (ARCHITECTURE item 1).
