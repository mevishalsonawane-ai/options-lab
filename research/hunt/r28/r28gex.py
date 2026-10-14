#!/usr/bin/env python3
"""R28 Step 1 on the lake: per-minute GEX for every index-day, nearest expiry (WEEK) and nearest monthly (MONTH).

    python3 research/hunt/r28/r28gex.py build [UND ...]     # -> <OUT>/gex_<UND>.parquet (incremental by month)
    python3 research/hunt/r28/r28gex.py upload               # copy the tables to Drive derived/r28_gex/

The core is research/brain/gex.py (the same as the Data Brain's L4 features). Each lake file is read one month at a
time. The WEEK series is the nearest expiry; the MONTH series the nearest monthly. Expiry days come from the data
(r25data.expiry_days: the ATM time value at 15:15), per series. "all" = WEEK + MONTH when they are different contracts
that day (by expiry date), else WEEK only.

Columns: und, day, minute, spot, near_exp, month_exp, dte (calendar days to the near expiry), near_<c> for every
gex.OUT_COLS column, month_net_cr / month_gross_cr / month_s2_cr, all_net_cr, all_gross_cr, near_share; then
near_norm (gex.rolling_norm of near_net_cr over the previous 20 sessions) and s2_norm.
"""
from __future__ import annotations

import subprocess
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd

import r28cfg as K
from research.brain import calendar as CAL
from research.brain import gex

N = 375


def _read(path, frm: date, to: date) -> pd.DataFrame:
    lo = pd.Timestamp(frm).tz_localize("Asia/Kolkata")
    hi = (pd.Timestamp(to) + pd.Timedelta(days=1)).tz_localize("Asia/Kolkata")
    df = pd.read_parquet(path, filters=[("ts", ">=", lo), ("ts", "<", hi)])
    if df.empty:
        return df
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["minute"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.minute >= 0) & (df.minute < N)]


def files(und: str, kind: str) -> dict:
    """{year: path} of the lake's opt_<UND>_<kind>_<Y> files, or of index_1m_<NAME>_<Y> when kind is 'index_1m_<NAME>'."""
    pat = f"{kind}_*.parquet" if kind.startswith("index_1m_") else f"opt_{und}_{kind}_*.parquet"
    out = {}
    for p in sorted(K.LAKE.rglob(pat)):
        y = p.stem.split("_")[-1]
        if y.isdigit() and p.stem == pat.replace("*.parquet", y):
            out[int(y)] = p
    return out


def months(years) -> list[tuple[date, date]]:
    out = []
    for y in sorted(years):
        for m in range(1, 13):
            a = date(y, m, 1)
            b = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
            out.append((a, b))
    return out


def tv_1515(o: pd.DataFrame) -> pd.Series:
    """ATM time value at 15:15 (CE + PE - intrinsic), % of spot, per session (r25data._tv_1515 on our columns)."""
    x = o[(o.offset == 0) & (o.minute == 15 * 60 + 15 - 555)]
    if x.empty:
        return pd.Series(dtype=float)
    p = x.pivot_table(index="d", columns="side", values="close", aggfunc="first", observed=True)
    s = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
    j = p.join(s)
    if "C" not in j or "P" not in j:
        return pd.Series(dtype=float)
    return (j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100


def expiry_days(tv: pd.Series) -> list[date]:
    """r25data.expiry_days: tv < 0.6% and the next session's tv > 2x it; the last session is never an expiry."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < 0.6) & (nxt > 2.0 * tv.clip(lower=1e-6)) & nxt.notna()
    return [d for d, v in ok.items() if v]


def series_expiries(und: str, kind: str) -> list[date]:
    """Every expiry date of a series, found from the data (one pass over its files, 15:15 rows only)."""
    tvs = []
    for y, p in files(und, kind).items():
        lo = pd.Timestamp(date(y, 1, 1)).tz_localize("Asia/Kolkata")
        df = pd.read_parquet(p, columns=["ts", "offset", "side", "close", "strike", "spot"],
                             filters=[("offset", "==", 0), ("ts", ">=", lo)])
        ts = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df["d"], df["minute"] = ts.dt.date, ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - 555
        df["side"] = df.side.astype(str).str[0].str.upper()
        tvs.append(tv_1515(df))
    if not tvs:
        return []
    tv = pd.concat(tvs).sort_index()
    return sorted(expiry_days(tv[~tv.index.duplicated()]))


def next_on_or_after(exps: list[date], d: date, und: str) -> date | None:
    i = np.searchsorted(np.array(exps, dtype="datetime64[D]"), np.datetime64(d))
    if i < len(exps):
        return exps[i]
    e, _ = CAL.next_expiry(und, d)                     # after the last detected expiry: the calendar's rule
    return e


def clean(o: pd.DataFrame, und: str, spot: np.ndarray) -> pd.DataFrame:
    o = o.copy()
    o["side"] = o.side.astype(str).str[0].str.upper()
    s = spot[o.minute.values]
    keep = np.abs(o.strike.values - np.where(np.isfinite(s), s, o.spot.values)) <= K.STRIKE_SLACK * K.STEP[und]
    o = o[keep].drop_duplicates(["minute", "offset", "side"], keep="first")
    return o


def day_series(o: pd.DataFrame, spot: np.ndarray, d: date, exp: date | None, flip: bool) -> pd.DataFrame:
    if o is None or o.empty or exp is None:
        r = pd.DataFrame({c: np.full(N, np.nan) for c in gex.OUT_COLS})
        r["minute"] = np.arange(N)
        return r
    df = gex.last_trade(o[["minute", "offset", "side", "strike", "close", "volume", "oi"]])
    df["n"] = gex.contract_flow(df)
    return gex.session(df, spot, d, exp, flip)


def build_und(und: str, verbose: bool = True) -> pd.DataFrame:
    K.OUT.mkdir(parents=True, exist_ok=True)
    out_p = K.OUT / f"gex_{und}.parquet"
    wk, mo, ix = files(und, "WEEK"), files(und, "MONTH"), files(und, f"index_1m_{und}")
    if not wk:
        print(und, "no WEEK option files")
        return pd.DataFrame()
    e_w, e_m = series_expiries(und, "WEEK"), series_expiries(und, "MONTH")
    if verbose:
        print(und, "expiries WEEK", len(e_w), "MONTH", len(e_m), flush=True)
    done = pd.read_parquet(out_p) if out_p.exists() else pd.DataFrame()
    have = set(done.day.unique()) if len(done) else set()
    parts = [done] if len(done) else []
    for a, b in months(wk):
        if a > K.LAKE_END:
            break
        w = _read(wk[a.year], a, b)
        if w.empty:
            continue
        if have and all(d in have for d in w.d.unique()):
            continue
        m = _read(mo[a.year], a, b) if a.year in mo else pd.DataFrame()
        i = _read(ix[a.year], a, b) if a.year in ix else pd.DataFrame()
        ib = dict(tuple(i.groupby("d"))) if len(i) else {}
        mb = dict(tuple(m.groupby("d"))) if len(m) else {}
        rows = []
        for d, g in w.groupby("d", sort=True):
            if d in have or d not in ib:
                continue
            spot = np.full(N, np.nan)
            ii = ib[d]
            spot[ii.minute.values] = ii.close.values.astype(float)
            ew, em = next_on_or_after(e_w, d, und), (next_on_or_after(e_m, d, und) if e_m else None)
            near = day_series(clean(g, und, spot), spot, d, ew, True)
            same = em is None or em == ew or d not in mb
            mon = day_series(None if same else clean(mb[d], und, spot), spot, d, None if same else em, False)
            r = pd.DataFrame(dict(und=und, day=d, minute=np.arange(N), spot=spot, near_exp=ew,
                                  month_exp=em if not same else ew, dte=(ew - d).days if ew else np.nan))
            for c in gex.OUT_COLS:
                r["near_" + c] = near[c].values
            for c in ("net_cr", "gross_cr", "s2_cr"):
                r["month_" + c] = mon[c].values if not same else 0.0
            r["all_net_cr"] = r.near_net_cr + r.month_net_cr
            r["all_gross_cr"] = r.near_gross_cr + r.month_gross_cr
            with np.errstate(invalid="ignore", divide="ignore"):
                r["near_share"] = r.near_net_cr.abs() / (r.near_net_cr.abs() + r.month_net_cr.abs())
            rows.append(r)
        if rows:
            parts.append(pd.concat(rows, ignore_index=True))
            if verbose:
                print(und, a.strftime("%Y-%m"), len(rows), "days", flush=True)
            pd.concat(parts, ignore_index=True).to_parquet(out_p)      # checkpoint each month
    df = pd.concat(parts, ignore_index=True).sort_values(["day", "minute"]).reset_index(drop=True)
    df = add_norms(df)
    df.to_parquet(out_p)
    return df


def add_norms(df: pd.DataFrame) -> pd.DataFrame:
    """near_norm and s2_norm: each minute's value / the median |value| at that minute over the previous 20 sessions."""
    days = sorted(df.day.unique())
    di = {d: i for i, d in enumerate(days)}
    r = df.day.map(di).values
    for src, dst in (("near_net_cr", "near_norm"), ("s2", "s2_norm")):
        col = "near_s2_cr" if src == "s2" else src
        a = np.full((len(days), N), np.nan)
        a[r, df.minute.values] = df[col].values
        df[dst] = gex.rolling_norm(a, 20, 10)[r, df.minute.values]
    return df


def load(und: str) -> pd.DataFrame:
    return pd.read_parquet(K.OUT / f"gex_{und}.parquet")


def at(df: pd.DataFrame, row: int, cols=None) -> pd.DataFrame:
    """The GEX table's rows at one grid minute, one per session, indexed by day."""
    x = df[df.minute == row].set_index("day")
    return x if cols is None else x[cols]


def upload():
    subprocess.run(["rclone", "copy", str(K.OUT), K.DRIVE, "--include", "gex_*.parquet", "-q"], check=True)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "build":
        unds = sys.argv[2:] or list(K.UNDS)
        for u in unds:
            build_und(u)
    elif cmd == "upload":
        upload()
