#!/usr/bin/env python3
"""Build the release Parquet files (zstd) from raw Dhan chunks written by lake_fetch.py.

Usage: python -I lake_build.py RAW_DIR OUT_DIR GROUP [GROUP ...]
Groups: idx_daily idx_1m fut_daily fut_1m eq_daily opt (all option series found under RAW/opt)
        eq_1m stkfut_daily stkfut_1m stkopt

Nothing is filled or interpolated: a missing minute stays missing. Identical duplicate rows are dropped;
rows sharing a key but with different values are all removed (left as a gap) and counted; every file gets QA counts in OUT/_qa/<file>.json for VALIDATION.md.
"""
import os, sys, glob, json, hashlib
import numpy as np, pandas as pd
import pyarrow as pa, pyarrow.parquet as pq

IST = "Asia/Kolkata"
MAX_BYTES = 1_900_000_000


def read_chunks(pattern):
    fs = sorted(glob.glob(pattern))
    return pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True) if fs else pd.DataFrame()


def to_ts(sec):
    return pd.to_datetime(sec.astype("int64"), unit="s", utc=True).dt.tz_convert(IST)


def qa(df, keys, intraday, session=None):
    q = {"rows": int(len(df))}
    if not len(df):
        return q
    px = [c for c in ("open", "high", "low", "close") if c in df]
    if px:
        q["nonpositive_price_rows"] = int((df[px] <= 0).any(axis=1).sum())
        q["nan_price_rows"] = int(df[px].isna().any(axis=1).sum())
    if {"open", "high", "low", "close"} <= set(df):
        q["high_lt_low"] = int((df.high < df.low).sum())
        q["close_outside_hl"] = int(((df.close > df.high + 1e-9) | (df.close < df.low - 1e-9)).sum())
        q["open_outside_hl"] = int(((df.open > df.high + 1e-9) | (df.open < df.low - 1e-9)).sum())
    t = df["ts"]
    q["first"], q["last"] = str(t.min()), str(t.max())
    if intraday:
        hm = t.dt.hour * 60 + t.dt.minute
        if session:
            q["outside_session_rows"] = int(((hm < session[0]) | (hm > session[1])).sum())
        q["weekend_rows"] = int((t.dt.dayofweek >= 5).sum())
        q["nonzero_seconds"] = int((t.dt.second != 0).sum())
        days = t.dt.date
        g = df.assign(_d=days).groupby([*keys, "_d"]).size() if keys else df.assign(_d=days).groupby("_d").size()
        q["trading_days"] = int(pd.Series(days).nunique())
        q["bars_per_day_median"] = float(g.median())
        q["series_days_lt_50pct_median"] = int((g < 0.5 * g.median()).sum())
        q["series_days_total"] = int(len(g))
    else:
        q["weekend_rows"] = int((pd.to_datetime(t).dt.dayofweek >= 5).sum())
    return q


_CAL = {}


def calendar(raw, sym):
    """Trading dates of an index per Dhan daily candles (includes special sessions such as Muhurat)."""
    if sym not in _CAL:
        df = read_chunks(f"{raw}/idx_daily/{sym}/*.parquet")
        _CAL[sym] = set(to_ts(df["timestamp"]).dt.date)
    return _CAL[sym]


def clean(df, cal, session):
    """Drop rows that are not real trading minutes; return (df, counts of what was dropped)."""
    t = df["ts"]
    drop = {}
    px = [c for c in ("open", "high", "low", "close") if c in df]
    bad = (df[px] <= 0).any(axis=1) | df[px].isna().any(axis=1)
    if session:
        hm = t.dt.hour * 60 + t.dt.minute
        out = (hm < session[0]) | (hm > session[1])
        drop["outside_session"] = int(out.sum())
    else:
        out = pd.Series(False, index=df.index)
    drop["nonpositive_or_missing_price"] = int((bad & ~out).sum())
    out = out | bad
    if cal is not None:
        d = t.dt.date
        nocal = ~d.isin(cal) & ~out
        drop["date_not_a_trading_day_or_after_last_daily_close"] = int(nocal.sum())
        out = out | nocal
    else:
        wk = (t.dt.dayofweek >= 5) & ~out
        drop["weekend"] = int(wk.sum())
        out = out | wk
    return df[~out].reset_index(drop=True), drop


def dedupe(df, keys):
    """Identical duplicate rows: keep one. Same key but different values: keep none (counted, left as a gap)."""
    n = len(df)
    df = df.drop_duplicates()
    k = [*keys, "ts"]
    conflict = df.duplicated(k, keep=False)
    df = df[~conflict].sort_values(k).reset_index(drop=True)
    return df, {"identical": int(n - len(df) - conflict.sum()), "conflicting_removed": int(conflict.sum())}


def write(df, out, name, meta):
    os.makedirs(f"{out}/_qa", exist_ok=True)
    fn = f"{out}/{name}"
    tbl = pa.Table.from_pandas(df, preserve_index=False)
    pq.write_table(tbl, fn + ".tmp", compression="zstd", compression_level=9, row_group_size=500_000)
    os.replace(fn + ".tmp", fn)
    size = os.path.getsize(fn)
    assert size < MAX_BYTES, f"{name} too big: {size}"
    h = hashlib.sha256(open(fn, "rb").read()).hexdigest()
    meta = dict(meta, file=name, bytes=size, sha256=h, columns=list(df.columns))
    json.dump(meta, open(f"{out}/_qa/{name}.json", "w"), indent=1, default=str)
    print(name, meta["qa"]["rows"], f"{size/1e6:.1f}MB", flush=True)


def daily_frame(df):
    df = df.rename(columns={"open_interest": "oi"})
    df["ts"] = to_ts(df.pop("timestamp")).dt.normalize().dt.tz_localize(None)
    return df


def build_idx_daily(raw, out):
    parts = []
    for d in sorted(glob.glob(f"{raw}/idx_daily/*")):
        df = read_chunks(f"{d}/*.parquet")
        if len(df):
            df = daily_frame(df)
            df.insert(0, "symbol", os.path.basename(d))
            parts.append(df)
    df, dup = dedupe(pd.concat(parts, ignore_index=True), ["symbol"])
    df = df.rename(columns={"ts": "date"})
    q = qa(df.rename(columns={"date": "ts"}), ["symbol"], False)
    q["duplicates_dropped"] = dup
    q["per_symbol"] = {s: dict(rows=int(len(g)), first=str(g.date.min().date()), last=str(g.date.max().date()))
                       for s, g in df.groupby("symbol")}
    write(df, out, "index_daily.parquet", dict(dataset="index_daily", source="Dhan /v2/charts/historical", qa=q))


def build_daily_group(raw, out, group, name, keycol):
    parts = []
    for f in sorted(glob.glob(f"{raw}/{group}/*/*.parquet")):
        if keycol and not os.path.basename(f).startswith("c0"):
            continue  # Dhan returns the same near-month series for expiryCode 1 and 2 as for 0 (checked: 100% equal)
        df = daily_frame(pd.read_parquet(f))
        df.insert(0, "symbol", f.split("/")[-2])
        if keycol:
            df.insert(1, keycol, int(os.path.basename(f)[1:-8]))
        parts.append(df)
    keys = ["symbol"] + ([keycol] if keycol else [])
    df = pd.concat(parts, ignore_index=True)
    px = ["open", "high", "low", "close"]
    bad = (df[px] <= 0).any(axis=1) | df[px].isna().any(axis=1)
    nbad = int(bad.sum())
    df, dup = dedupe(df[~bad], keys)
    q = qa(df, keys, False)
    q["duplicates_dropped"] = dup
    q["rows_dropped"] = {"nonpositive_or_missing_price": nbad}
    q["per_symbol"] = {str(s): dict(rows=int(len(g)), first=str(g.ts.min().date()), last=str(g.ts.max().date()))
                       for s, g in df.groupby(keys)}
    df = df.rename(columns={"ts": "date"})
    write(df, out, name, dict(dataset=name[:-8], source="Dhan /v2/charts/historical", qa=q))


def build_idx_1m(raw, out):
    for d in sorted(glob.glob(f"{raw}/idx_1m/*")):
        sym = os.path.basename(d)
        df = read_chunks(f"{d}/*.parquet")
        if not len(df):
            continue
        df["ts"] = to_ts(df.pop("timestamp"))
        df = df.drop(columns=[c for c in ("open_interest",) if c in df])
        df, dropped = clean(df, calendar(raw, sym), (9 * 60 + 15, 15 * 60 + 29))
        df, dup = dedupe(df, [])
        for y, g in df.groupby(df.ts.dt.year):
            g = g.reset_index(drop=True)
            q = qa(g, [], True, (9 * 60 + 15, 15 * 60 + 29))
            q["duplicates_dropped_symbol_total"] = dup
            q["rows_dropped_symbol_total"] = dropped
            write(g, out, f"index_1m_{sym}_{y}.parquet",
                  dict(dataset="index_1m", symbol=sym, year=int(y), source="Dhan /v2/charts/intraday", qa=q))


def build_fut_1m(raw, out, sub="fut_1m"):
    parts = []
    for d in sorted(glob.glob(f"{raw}/{sub}/*/*")):
        df = read_chunks(f"{d}/*.parquet")
        if not len(df):
            continue
        e, sid = os.path.basename(d).split("_")
        df["ts"] = to_ts(df.pop("timestamp"))
        df = df.rename(columns={"open_interest": "oi"})
        df.insert(0, "symbol", d.split("/")[-2]); df.insert(1, "expiry", e); df.insert(2, "security_id", int(sid))
        parts.append(df)
    df = pd.concat(parts, ignore_index=True)
    mcxs = ["CRUDEOIL", "NATURALGAS", "GOLD", "GOLDM", "SILVER", "SILVERM"]
    a, da = clean(df[~df.symbol.isin(mcxs)], calendar(raw, "NIFTY"), (9 * 60 + 15, 15 * 60 + 29))
    b, db = clean(df[df.symbol.isin(mcxs)], None, None)
    b = b[b.ts.dt.date <= max(calendar(raw, "NIFTY"))]
    df, dup = dedupe(pd.concat([a, b], ignore_index=True), ["symbol", "expiry"])
    kinds = ((("index", ~df.symbol.isin(mcxs)), ("mcx", df.symbol.isin(mcxs))) if sub == "fut_1m"
             else (("stock", df.symbol.notna()),))
    for kind, sel in kinds:
        g = df[sel].reset_index(drop=True)
        if not len(g):
            continue
        q = qa(g, ["symbol", "expiry"], True)
        q["duplicates_dropped"] = dup
        q["rows_dropped"] = da if kind == "index" else db
        q["per_contract"] = {f"{s} {e}": dict(rows=int(len(x)), first=str(x.ts.min()), last=str(x.ts.max()))
                             for (s, e), x in g.groupby(["symbol", "expiry"])}
        write(g, out, f"fut_1m_live_{kind}.parquet",
              dict(dataset=f"fut_1m_live_{kind}", source="Dhan /v2/charts/intraday (live contracts only)", qa=q))


def build_opt(raw, out, sub="opt"):
    """Index/MCX options: one file per underlying, flag and year. Stock options (sub='stkopt'): one file per stock."""
    for d in sorted(glob.glob(f"{raw}/{sub}/*/*/c1")):
        und, flag = d.split("/")[-3], d.split("/")[-2]
        parts = []
        for sd in sorted(glob.glob(f"{d}/*")):
            df = read_chunks(f"{sd}/*.parquet")
            if not len(df):
                continue
            tag = os.path.basename(sd)
            df["ts"] = to_ts(df.pop("timestamp"))
            df.insert(0, "offset", np.int8(int(tag[:-1])))
            df.insert(1, "side", tag[-1])
            parts.append(df)
        if not parts:
            continue
        df = pd.concat(parts, ignore_index=True)
        df["side"] = df["side"].astype("category")
        for c in ("open", "high", "low", "close", "iv", "strike", "spot"):
            if c in df:
                df[c] = df[c].astype("float32") if c == "iv" else df[c].astype("float64")
        for c in ("volume", "oi"):
            if c in df:
                df[c] = df[c].astype("int64")
        mcx = und in ("CRUDEOIL", "NATURALGAS", "GOLD", "GOLDM", "SILVER", "SILVERM")
        if mcx:
            last = max(calendar(raw, "NIFTY"))
            df, dropped = clean(df[df.ts.dt.date <= last], None, None)
        else:
            df, dropped = clean(df, calendar(raw, "SENSEX" if und in ("SENSEX", "BANKEX") else "NIFTY"),
                                (9 * 60 + 15, 15 * 60 + 29))
        df, dup = dedupe(df, ["offset", "side"])
        groups = df.groupby(df.ts.dt.year) if sub == "opt" else [(None, df)]
        for y, g in groups:
            g = g.reset_index(drop=True)
            q = qa(g, ["offset", "side"], True, None if mcx else (9 * 60 + 15, 15 * 60 + 29))
            q["duplicates_dropped_series_total"] = dup
            q["rows_dropped_series_total"] = dropped
            q["iv_null_or_zero"] = int((g.iv.isna() | (g.iv <= 0)).sum()) if "iv" in g else None
            meta = dict(dataset="options_rolling_1m" if sub == "opt" else "stock_options_rolling_1m", underlying=und,
                        expiry_flag=flag, expiry_code=1,
                        offsets="ATM-5..ATM+5" if (mcx or sub != "opt") else "ATM-10..ATM+10",
                        source="Dhan /v2/charts/rollingoption", qa=q)
            if y is None:
                write(g, out, f"stkopt_{und}_{flag}.parquet", meta)
            else:
                write(g, out, f"opt_{und}_{flag}_{y}.parquet", dict(meta, year=int(y)))


def build_eq_1m(raw, out):
    """All constituents in one file per year: symbol, ts, OHLCV."""
    cal = calendar(raw, "NIFTY")
    syms = sorted(os.path.basename(d) for d in glob.glob(f"{raw}/eq_1m/*"))
    years = sorted({int(os.path.basename(f)[:4]) for f in glob.glob(f"{raw}/eq_1m/*/*.parquet")})
    for y in years + [max(years) + 1] if years else []:
        parts, dropped, dups = [], {}, {}
        for sym in syms:
            fs = [f for f in glob.glob(f"{raw}/eq_1m/{sym}/*.parquet") if int(os.path.basename(f)[:4]) in (y - 1, y)]
            if not fs:
                continue
            df = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
            df["ts"] = to_ts(df.pop("timestamp"))
            df = df[df.ts.dt.year == y].drop(columns=[c for c in ("open_interest",) if c in df])
            if not len(df):
                continue
            df, dr = clean(df, cal, (9 * 60 + 15, 15 * 60 + 29))
            df, dp = dedupe(df, [])
            df.insert(0, "symbol", sym)
            dropped[sym], dups[sym] = dr, dp
            parts.append(df)
        if not parts:
            continue
        g = pd.concat(parts, ignore_index=True)
        g["symbol"] = g["symbol"].astype("category")
        q = qa(g, ["symbol"], True, (9 * 60 + 15, 15 * 60 + 29))
        q["rows_dropped"] = {k: sum(v[k] for v in dropped.values()) for k in next(iter(dropped.values()))}
        q["duplicates_dropped"] = {k: sum(v[k] for v in dups.values()) for k in next(iter(dups.values()))}
        q["per_symbol"] = {s: dict(rows=int(len(x)), first=str(x.ts.min()), last=str(x.ts.max()))
                           for s, x in g.groupby("symbol", observed=True)}
        write(g, out, f"equity_1m_{y}.parquet", dict(dataset="equity_1m", year=y, source="Dhan /v2/charts/intraday", qa=q))


if __name__ == "__main__":
    raw, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for g in sys.argv[3:]:
        {"idx_daily": lambda: build_idx_daily(raw, out),
         "idx_1m": lambda: build_idx_1m(raw, out),
         "fut_daily": lambda: build_daily_group(raw, out, "fut_daily", "fut_daily_continuous.parquet", "expiry_code"),
         "eq_daily": lambda: build_daily_group(raw, out, "eq_daily", "equity_daily.parquet", None),
         "fut_1m": lambda: build_fut_1m(raw, out),
         "opt": lambda: build_opt(raw, out),
         "eq_1m": lambda: build_eq_1m(raw, out),
         "stkfut_daily": lambda: build_daily_group(raw, out, "stkfut_daily", "stock_fut_daily_continuous.parquet",
                                                   "expiry_code"),
         "stkfut_1m": lambda: build_fut_1m(raw, out, "stkfut_1m"),
         "stkopt": lambda: build_opt(raw, out, "stkopt")}[g]()
