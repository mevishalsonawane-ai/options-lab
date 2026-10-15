#!/usr/bin/env python3
"""R24 Step 1: the what-if table (PREREG.md). Research only, 13 Oct 2026.

For every session, index, side (CE / PE), strike offset ATM-3 .. ATM+3 (from the ATM of the decision minute) and entry
minute 09:20 .. 14:45: what buying 1 lot at the ask would have done, net of the app's charges and the spread by
moneyness. Outcomes: fixed exits (+15 / +30 / +60 minutes, 15:05, 15:20), the best and worst price after the fill
(MFE / MAE in % and Rs a lot, the time to them) and the app's exit families (orb, sweep, liq; android/engine arm rules).

    python3 research/hunt/r24/whatif.py build [--und NIFTY] [--from 2021-08-01] [--to 2026-12-31] [--jobs 4]
                                              [--series WEEK,MONTH]   (MONTH: only years whose lake file exists)
    python3 research/hunt/r24/whatif.py catalog          # <cache>/whatif/CATALOG.json (rows, days, sha256, columns)
    python3 research/hunt/r24/whatif.py upload           # rclone copy to Drive + rclone check

Input: the brain cube (<cache>/cube/<UND>/<YYYY>/<day>.{minutes,chain}.parquet), built from the lake with
`python -m research.brain.cube build --source lake`. Output: <cache>/whatif/<UND>/<YYYY>.parquet (never in git).

Grid: row r is the bar starting 09:15 + r; it is known at its close. The decision for entry minute k is on row k-1's
close (its ATM, its features); the fill is the first traded open in rows k .. k+2. Only traded bars (cube flag `ok`)
are prices; nothing is filled in.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor
from datetime import date

import numpy as np
import pandas as pd
from numba import njit

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import r24cfg as K  # noqa: E402
from research.brain import cube  # noqa: E402
from research.obuy.config import LOT_SCHEDULE  # noqa: E402

N = 375
TICK = K.TICK
STOP, LOCK, TARGET, SQUARE, TIME = 1, 2, 3, 4, 5
WHY = {STOP: "stop", LOCK: "lock", TARGET: "target", SQUARE: "square_off", TIME: "time_stop"}
# kernel output columns
COLS = ["fill_row", "px", "e", "vol5_lots",
        "net_rs_15", "net_rs_30", "net_rs_60", "net_rs_1505", "net_rs_1520",
        "mfe_pct", "mae_pct", "mfe_rs", "mae_rs", "t_best", "t_worst", "mfe_pct_1505",
        "orb_net", "orb_row", "orb_why", "sweep_net", "sweep_row", "sweep_why", "liq_net", "liq_row", "liq_why"]
NC = len(COLS)
X2 = ["net_rs_1505", "orb_net", "sweep_net", "liq_net"]          # also stored at twice the half-spread (stress)
# (stop points, target points) of the fixed-target families; the ladder's rungs sit on the target
FAM_PTS = {"orb": (40.0, 40.0), "sweep": (40.0, 80.0)}
LIQ_STOP, LIQ_MIN, LIQ_GAIN = 0.15, 20, 0.05


def lot_of(und: str, d: date) -> int:
    out = 0
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            out = n
    return out


# ------------------------------------------------------------------------------------------------ prices and costs
@njit(cache=True)
def r2(x):
    return math.floor(x * 100.0 + 0.5) / 100.0


@njit(cache=True)
def floor_tick(x):
    return math.floor(x / 0.05 + 1e-9) * 0.05


@njit(cache=True)
def ceil_tick(x):
    return math.ceil(x / 0.05 - 1e-9) * 0.05


@njit(cache=True)
def charge(buy, price, qty):
    """One order's charges, the app's model at today's rates (research/obuy/costs.py Costs('app'))."""
    v = price * qty
    if not v > 0:
        return 0.0
    b = v if buy else 0.0
    s = 0.0 if buy else v
    txn = (b + s) * 0.0003553
    sebi = (b + s) * 1e-6
    return r2(20.0 + s * 0.0015 + txn + sebi + b * 0.00003 + (20.0 + txn + sebi) * 0.18)


@njit(cache=True)
def rt_per_unit(e, qty):
    """ProfitLock.roundTripPerUnit: a buy at e and a sell at its breakeven, per unit."""
    first = (charge(True, e, qty) + charge(False, e, qty)) / qty
    return (charge(True, e, qty) + charge(False, e + first, qty)) / qty


@njit(cache=True)
def vol5(V, row, lot):
    s = 0.0
    for r in range(max(0, row - 5), row):
        if np.isfinite(V[r]):
            s += V[r]
    return s / lot if lot > 0 else 0.0


@njit(cache=True)
def half_spread(px, hs_pct, v5, stop, mult):
    t = 1.0 if v5 >= 20.0 else (2.0 if v5 > 0.0 else 4.0)
    if stop:
        t *= 2.0
    return max(hs_pct * px, t * 0.05) * mult


@njit(cache=True)
def buy_fill(px, hs_pct, v5, mult):
    return r2(r2(px * 1.0005) + half_spread(px, hs_pct, v5, False, mult))


@njit(cache=True)
def sell_fill(px, hs_pct, v5, stop, mult):
    x = r2(px * (0.999 if stop else 0.9995))
    return max(r2(x - half_spread(px, hs_pct, v5, stop, mult)), 0.0)


@njit(cache=True)
def next_open(O, C, r, cap, lo):
    """(price, row) of the first traded open in rows r .. cap; if none, the last traded close in lo .. cap (the LTP) at
    row cap; (nan, cap) if nothing traded at all."""
    for m in range(r, cap + 1):
        if np.isfinite(O[m]):
            return O[m], m
    for m in range(cap, lo - 1, -1):
        if np.isfinite(C[m]):
            return C[m], cap
    return np.nan, cap


@njit(cache=True)
def _sell_net(O, C, V, r, cap, f, e, lot, hs_pct, mult):
    p, m = next_open(O, C, r, cap, f)
    if not np.isfinite(p):
        return np.nan
    x = sell_fill(p, hs_pct, vol5(V, m, lot), False, mult)
    return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot)


# ------------------------------------------------------------------------------------------------ exit families
@njit(cache=True)
def fixed_family(O, H, L, C, V, f, e, lot, hs_pct, sq, stop_pts, tgt, mult):
    """orb / sweep: resting stop at e - stop_pts (none when e <= stop_pts, as OrbRules.stopTrigger), target on a minute's
    high sold at the next open, the ProfitLock ladder (25/50/75% of the target -> breakeven after charges / 25% / 50%,
    resting, from the next minute, never at or above the best price), out at sq. Returns (net, row, why)."""
    trig = floor_tick(e - stop_pts) if e - stop_pts > 0.05 else -1.0
    be = e + rt_per_unit(e, lot)
    peak = e
    for r in range(f, sq):
        if not np.isfinite(C[r]):
            continue
        o = O[r] if np.isfinite(O[r]) else C[r]
        lock = -1.0
        for j in range(3):
            reach = 0.25 * (j + 1)
            keep = 0.25 * j
            if peak >= e + reach * tgt - 1e-9:
                lv = ceil_tick(max(e + keep * tgt, be))
                if lv < peak:
                    lock = max(lock, lv)
        rest = max(trig, lock)
        if rest > 0.05 and L[r] <= rest:
            is_stop = trig >= lock
            px = min(rest, o)
            x = sell_fill(px, hs_pct, vol5(V, r, lot), True, mult)
            return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), r, (STOP if is_stop else LOCK)
        if r <= sq - 2 and H[r] >= e + tgt:
            p, m = next_open(O, C, r + 1, sq, f)
            x = sell_fill(p, hs_pct, vol5(V, m, lot), False, mult)
            return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), m, TARGET
        peak = max(peak, H[r])
    p, m = next_open(O, C, sq, sq, f)
    x = sell_fill(p, hs_pct, vol5(V, m, lot), False, mult)
    return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), m, SQUARE


@njit(cache=True)
def liq_family(O, H, L, C, V, f, e, lot, hs_pct, sq, mult):
    """Liquidity 15+5's own exits without its index-level target: resting stop at -15% of the fill (on the tick), the
    time stop (the first traded minute from 20 minutes after the fill: a close below +5% sells at the next open), out
    at sq. Returns (net, row, why)."""
    trig = floor_tick(e * (1.0 - LIQ_STOP))
    timed = False
    for r in range(f, sq):
        if not np.isfinite(C[r]):
            continue
        o = O[r] if np.isfinite(O[r]) else C[r]
        if trig > 0.05 and L[r] <= trig:
            x = sell_fill(min(trig, o), hs_pct, vol5(V, r, lot), True, mult)
            return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), r, STOP
        if not timed and r >= f + LIQ_MIN:
            timed = True
            if C[r] < e * (1.0 + LIQ_GAIN) - 1e-9:
                p, m = next_open(O, C, r + 1, sq, f)
                x = sell_fill(p, hs_pct, vol5(V, m, lot), False, mult)
                return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), m, TIME
    p, m = next_open(O, C, sq, sq, f)
    x = sell_fill(p, hs_pct, vol5(V, m, lot), False, mult)
    return (x - e) * lot - charge(True, e, lot) - charge(False, x, lot), m, SQUARE


@njit(cache=True)
def contract_outcomes(O, H, L, C, V, rows, lot, hs_pct, sq, mult):
    """Every outcome of buying this contract at each entry minute in `rows`. O..V: (375,) arrays, NaN where the
    contract did not trade. Returns (len(rows), NC); a row without a fill is all NaN except fill_row = -1."""
    out = np.full((len(rows), NC), np.nan)
    for i in range(len(rows)):
        k = rows[i]
        f = -1
        for m in range(k, min(k + 3, N)):
            if np.isfinite(O[m]):
                f = m
                break
        out[i, 0] = f
        if f < 0 or f >= K_ROW_1505:
            continue
        px = O[f]
        v5 = vol5(V, f, lot)
        e = buy_fill(px, hs_pct, v5, mult)
        out[i, 1] = px
        out[i, 2] = e
        out[i, 3] = v5
        for j in range(3):
            t = min(f + HZ[j], K_ROW_1520)
            out[i, 4 + j] = _sell_net(O, C, V, t, K_ROW_1520, f, e, lot, hs_pct, mult)
        out[i, 7] = _sell_net(O, C, V, K_ROW_1505, K_ROW_1520, f, e, lot, hs_pct, mult)
        out[i, 8] = _sell_net(O, C, V, K_ROW_1520, K_ROW_1520, f, e, lot, hs_pct, mult)
        hi, lo, tb, tw, hi5 = -1.0, 1e18, -1, -1, -1.0
        for r in range(f, K_ROW_1520):
            if np.isfinite(H[r]) and H[r] > hi:
                hi, tb = H[r], r
            if np.isfinite(L[r]) and L[r] < lo:
                lo, tw = L[r], r
            if r < K_ROW_1505 and np.isfinite(H[r]) and H[r] > hi5:
                hi5 = H[r]
        if tb >= 0:
            out[i, 9] = (hi - e) / e * 100.0
            out[i, 11] = (hi - e) * lot
            out[i, 13] = tb - f
        if tw >= 0:
            out[i, 10] = (lo - e) / e * 100.0
            out[i, 12] = (lo - e) * lot
            out[i, 14] = tw - f
        if hi5 > 0:
            out[i, 15] = (hi5 - e) / e * 100.0
        for j in range(2):
            sp = 40.0
            tg = 40.0 if j == 0 else 80.0
            a, b, c = fixed_family(O, H, L, C, V, f, e, lot, hs_pct, sq, sp, tg, mult)
            out[i, 16 + 3 * j] = a
            out[i, 17 + 3 * j] = b
            out[i, 18 + 3 * j] = c
        a, b, c = liq_family(O, H, L, C, V, f, e, lot, hs_pct, sq, mult)
        out[i, 22] = a
        out[i, 23] = b
        out[i, 24] = c
    return out


K_ROW_1505, K_ROW_1510, K_ROW_1520 = K.ROW_1505, K.ROW_1510, K.ROW_1520
HZ = np.array(K.HORIZONS, dtype=np.int64)


# ------------------------------------------------------------------------------------------------ one session
def contracts(chain: pd.DataFrame) -> dict:
    """(strike, side +1/-1) -> (O, H, L, C, V) arrays (375,) from the cube chain; only traded (`ok`) bars."""
    ch = chain[chain.flag == "ok"]
    if ch.empty:
        return {}
    ts0 = chain.ts.min().normalize() + pd.Timedelta(hours=9, minutes=15)
    row = ((ch.ts - ts0).dt.total_seconds() // 60).astype(int).values
    out = {}
    keys = list(zip(ch.strike.values.astype(float), np.where(ch.side.values == "C", 1, -1)))
    df = pd.DataFrame({"k": keys, "row": row, "o": ch.open.values, "h": ch.high.values, "l": ch.low.values,
                       "c": ch.close.values, "v": ch.volume.values})
    for key, g in df.groupby("k", sort=False):
        a = np.full((5, N), np.nan)
        r = g.row.values
        ok = (r >= 0) & (r < N)
        for j, c in enumerate("ohlcv"):
            a[j, r[ok]] = g[c].values[ok].astype(float)
        out[key] = a
    return out


def day_table(und: str, d: date, M: pd.DataFrame, chain: pd.DataFrame, mult: float = 1.0,
              expiry: date | None = None) -> pd.DataFrame:
    """The what-if rows of one session (also the rows without a fill, flagged). expiry: the contract's expiry when it is
    not the cube's nearest one (the MONTH series)."""
    M = M.sort_values("ts").reset_index(drop=True)
    if len(M) != N or chain is None or chain.empty:
        return pd.DataFrame()
    lot = lot_of(und, d)
    step = K.STEP[und]
    exp = expiry if expiry is not None else M.expiry.iloc[0]
    exp = pd.Timestamp(exp).date() if pd.notna(exp) else None
    is_exp = bool(exp == d)
    sq = K.ROW_1505 if is_exp else K.ROW_1510
    dte = float((exp - d).days) if exp else np.nan
    cons = contracts(chain)
    atm = M.atm_strike.values.astype(float)
    ts = pd.DatetimeIndex(M.ts)
    by_key: dict = {}
    recs = []
    for k in range(K.ENTRY_FIRST, K.ENTRY_LAST + 1):
        a = atm[k - 1]
        if not np.isfinite(a):
            continue
        for s in K.SIDES:
            for o in K.OFFSETS:
                key = (a + o * step, s)
                by_key.setdefault(key, []).append(len(recs))
                recs.append((k, s, o, a + o * step))
    if not recs:
        return pd.DataFrame()
    res = np.full((len(recs), NC), np.nan)
    res[:, 0] = -1
    for key, idx in by_key.items():
        arr = cons.get(key)
        if arr is None:
            continue
        rows = np.array([recs[i][0] for i in idx], dtype=np.int64)
        mny = K.mny_of(key[1], recs[idx[0]][2])
        res[idx] = contract_outcomes(arr[0], arr[1], arr[2], arr[3], arr[4], rows, lot, K.HS_BY_MNY[mny], sq, mult)
    rk = np.array(recs, dtype=float)
    df = pd.DataFrame({
        "day": np.full(len(recs), np.datetime64(d)), "und": und,
        "ts_decision": ts[rk[:, 0].astype(int) - 1], "entry_row": rk[:, 0].astype(np.int16),
        "side": rk[:, 1].astype(np.int8), "offset": rk[:, 2].astype(np.int8),
        "mny": np.array([K.mny_of(int(s), int(o)) for s, o in zip(rk[:, 1], rk[:, 2])], dtype=np.int8),
        "strike": rk[:, 3], "expiry": np.datetime64(exp) if exp else np.datetime64("NaT"),
        "dte": np.float32(dte), "is_expiry": is_exp, "lot": np.int16(lot), "sq_row": np.int16(sq),
    })
    for j, c in enumerate(COLS):
        df[c] = res[:, j].astype(np.float32) if c not in ("fill_row",) else res[:, j].astype(np.int16)
    df["filled"] = df.fill_row >= 0
    for c in ("net_rs_15", "net_rs_30", "net_rs_60", "net_rs_1505", "net_rs_1520", "orb_net", "sweep_net", "liq_net"):
        df[c.replace("net_rs", "net_pct") if c.startswith("net_rs") else c + "_pct"] = \
            (df[c] / (df.e * df.lot) * 100).astype(np.float32)
    return df


def monthly_expiry(und: str, d: date, sessions: set | None = None) -> date | None:
    """The nearest MONTHLY expiry on or after d: the last scheduled expiry of its month (brain calendar: contracts seen in
    the data, else the NSE weekday rules). Scheduled weeks ahead, so not look-ahead."""
    from research.brain import calendar
    e = calendar.expiries(und, d.replace(day=1), d + pd.Timedelta(days=70), sessions)
    if e.empty:
        return None
    last = e.groupby([x.year * 100 + x.month for x in e.date]).date.max()
    after = [x for x in last.values if x >= d]
    return min(after) if after else None


def month_chain(raw: pd.DataFrame) -> pd.DataFrame:
    """Lake MONTH rows of one session (ts tz-aware or naive IST, strike, side, OHLCV) -> the cube chain's layout. A row
    counts as traded (`ok`) only with volume > 0 and a positive close, as the cube rules."""
    if raw is None or raw.empty:
        return pd.DataFrame()
    ts = pd.to_datetime(raw.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    vol = raw.volume.astype(float).values
    ok = (vol > 0) & (raw.close.astype(float).values > 0)
    return pd.DataFrame(dict(ts=ts.values, strike=raw.strike.astype(float).values,
                             side=raw.side.astype(str).str[0].str.upper().values, open=raw.open.astype(float).values,
                             high=raw.high.astype(float).values, low=raw.low.astype(float).values,
                             close=raw.close.astype(float).values, volume=vol,
                             flag=np.where(ok, "ok", "stale"))).drop_duplicates(["ts", "strike", "side"])


def _tables(und, d, M, C, expiry=None) -> pd.DataFrame:
    t1 = day_table(und, d, M, C, 1.0, expiry)
    if t1.empty:
        return t1
    t2 = day_table(und, d, M, C, 2.0, expiry)
    for c in X2:
        t1[c + "_x2"] = t2[c].values
    return t1


def _year_job(args) -> tuple[str, int, int]:
    und, year, frm, to, series = args
    root = K.CACHE.root
    days = [d for d in cube.built_days(root, und) if d.year == year and frm <= d <= to]
    parts = []
    raw = None
    if series == "MONTH":
        p = K.CACHE.lake / f"opt_{und}_MONTH_{year}.parquet"
        if not p.exists():
            return und + "_MONTH", year, 0
        raw = pd.read_parquet(p)
        rts = pd.to_datetime(raw.ts)
        raw["d"] = (rts.dt.tz_convert("Asia/Kolkata") if rts.dt.tz is not None else rts).dt.date
        raw = dict(tuple(raw.groupby("d")))
    sess = set(cube.built_days(root, und))
    for d in days:
        if series == "MONTH":
            M, _ = cube.load(root, und, d, d, chain=False)
            C = month_chain(raw.get(d))
            t = _tables(und, d, M, C, monthly_expiry(und, d, sess)) if len(C) else pd.DataFrame()
        else:
            M, C = cube.load(root, und, d, d)
            t = _tables(und, d, M, C) if C is not None and not C.empty else pd.DataFrame()
        if len(t):
            parts.append(t)
    name = und if series == "WEEK" else f"{und}_MONTH"
    if not parts:
        return name, year, 0
    out = pd.concat(parts, ignore_index=True)
    p = K.WHATIF / name / f"{year}.parquet"
    p.parent.mkdir(parents=True, exist_ok=True)
    out.to_parquet(p, compression="zstd", index=False)
    return name, year, len(out)


def build(unds, frm: date, to: date, jobs: int = 4, series=("WEEK", "MONTH")):
    """WEEK = the cube's nearest expiry (the lake's opt_<UND>_WEEK); MONTH = opt_<UND>_MONTH_<YEAR> in the lake folder,
    built only for the years whose file exists."""
    root = K.CACHE.root
    work = []
    for u in unds:
        years = sorted({d.year for d in cube.built_days(root, u) if frm <= d <= to})
        for s in series:
            if s == "MONTH":
                years_s = [y for y in years if (K.CACHE.lake / f"opt_{u}_MONTH_{y}.parquet").exists()]
            else:
                years_s = years
            work += [(u, y, frm, to, s) for y in years_s]
    with ProcessPoolExecutor(jobs) as ex:
        for name, y, n in ex.map(_year_job, work):
            print(f"{name} {y}: {n:,} rows", flush=True)


# ------------------------------------------------------------------------------------------------ catalog and Drive
def catalog() -> dict:
    files = []
    for p in sorted(K.WHATIF.glob("*/*.parquet")):
        df = pd.read_parquet(p, columns=["day", "filled"])
        files.append(dict(path=f"{p.parent.name}/{p.name}", rows=int(len(df)), filled=int(df.filled.sum()),
                          days=int(df.day.nunique()), first=str(df.day.min().date()), last=str(df.day.max().date()),
                          bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    cols = list(pd.read_parquet(sorted(K.WHATIF.glob("*/*.parquet"))[0]).columns) if files else []
    cat = dict(study="R24 what-if table (research/hunt/r24/PREREG.md, research/HUNT_R24_WHATIF_PATTERNS.md)",
               built=pd.Timestamp.now(tz="UTC").isoformat(timespec="seconds"),
               source="Dhan lake on Drive (opt_<UND>_WEEK = <UND>/, opt_<UND>_MONTH = <UND>_MONTH/ where present, "
                      "index_1m) via research/brain cube v2",
               grain="session x index x side x strike offset (ATM-3..+3 at the decision minute) x entry minute 09:20-14:45",
               costs="buy adverse 5 bps + half-spread max(h(moneyness) x price, 1/2/4 ticks by 5-min volume); sells -5 bps "
                     "(stops -10 bps, 2x ticks) - half-spread; app charges (STT 0.15%); *_x2 = twice the half-spread",
               columns=cols, files=files)
    (K.WHATIF / "CATALOG.json").write_text(json.dumps(cat, indent=1))
    return cat


def upload():
    subprocess.run(["rclone", "copy", str(K.WHATIF), K.DRIVE, "--checksum", "--transfers", "4"], check=True)
    subprocess.run(["rclone", "check", str(K.WHATIF), K.DRIVE, "--one-way"], check=True)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["build", "catalog", "upload"])
    ap.add_argument("--und", default=None)
    ap.add_argument("--from", dest="frm", default="2021-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--series", default="WEEK,MONTH", help="WEEK, MONTH or both (MONTH only where its lake file exists)")
    a = ap.parse_args(argv)
    if a.cmd == "build":
        build([a.und] if a.und else list(K.UNDS), date.fromisoformat(a.frm), date.fromisoformat(a.to), a.jobs,
              tuple(a.series.split(",")))
        catalog()
    elif a.cmd == "catalog":
        print(json.dumps(catalog(), indent=1)[:2000])
    else:
        upload()


if __name__ == "__main__":
    main()
