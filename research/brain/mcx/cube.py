"""L3 market cube (MB1): one MCX session grid per symbol and day, every source on it, values never invented.

Grid: 09:00 .. close - 1 minute (23:29 in US summer time, 23:54 in US winter time; research/data/mcx.close_min),
Monday to Friday. Row t holds the bar that closes at t + 1 minute.

Columns
  ts, day, src            src = where `close` comes from that day: "spot" (the option book's near future) or "fut"
                          (the continuous future). Chosen from the PREVIOUS day (spot if the option book had rows the
                          day before, else the future), so the choice is known at the open
  seg, roll               contract segment: "S<n>" on spot days (n = option expiries before the day: the option book
                          rolls the session after an expiry), "F<expiry>" on future days (the contract held that day,
                          chosen from the previous day's volume). roll = first row of a new segment. No feature or label
                          window crosses a segment
  close, flag             close-only price (PREREG section 1); flag from research/brain/quality.series_flags per segment
  fo fh fl fc fvol foi    the continuous future's true 1-minute bar, volume and OI where the lake has it (any src)
  fexp, fdte              its contract expiry and calendar days to it
  atm_iv, straddle, pcr_oi, opt_vol, opt_age
                          option context placed on its own minute, carried forward within the day at most
                          config.OPT_MAX_AGE minutes (opt_age = minutes since it was printed); NaN otherwise
  dte, expiry_day         calendar days to the next option expiry (the fixed calendar from clean.option_expiries)
  ev_eia, ev_storage, ev_cpi, ev_nfp, ev_fomc
                          minutes from the bar's end to that day's release (events.py); NaN on other days

    python -m research.brain.mcx.cube build --symbols GOLDM ...   (reads $IRAALGO_LAKE; writes <cache>/mcx/cube)
    python -m research.brain.mcx.cube info
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from research.data import mcx as lake_mcx

from .. import quality
from . import clean, config, events

OPT_COLS = ["atm_iv", "straddle", "pcr_oi", "opt_vol"]
FUT_COLS = ["fo", "fh", "fl", "fc", "fvol", "foi"]


def grid(days) -> pd.DatetimeIndex:
    parts = []
    for d in days:
        start = pd.Timestamp(d) + pd.Timedelta(minutes=config.OPEN_MIN)
        parts.append(pd.date_range(start, periods=lake_mcx.close_min(d) - config.OPEN_MIN, freq="min"))
    return pd.DatetimeIndex(np.concatenate([p.values for p in parts]) if parts else [], name="ts")


def _carry(df: pd.DataFrame, cols, day: np.ndarray, max_age: int) -> pd.DataFrame:
    """Forward-fill cols within the day, at most max_age minutes; adds opt_age (minutes since the last print)."""
    have = df[cols].notna().any(axis=1).to_numpy()
    idx = np.where(have, np.arange(len(df)), np.nan)
    last = pd.Series(idx).groupby(day).ffill().to_numpy()
    age = np.arange(len(df)) - last
    ok = np.isfinite(age) & (age <= max_age)
    for c in cols:
        v = df[c].groupby(day).ffill().to_numpy(float)
        df[c] = np.where(ok, v, np.nan)
    df["opt_age"] = np.where(ok, age, np.nan)
    return df


def build_frame(spot: pd.DataFrame | None, fut: pd.DataFrame | None, sym: str, expiries, cal: dict | None = None,
                days=None) -> pd.DataFrame:
    """The cube of one symbol. spot = clean.spot_frame, fut = clean.fut_frame (either may be None/empty),
    expiries = option expiry dates (fixed calendar), cal = events.calendar(...). days: the session days to build
    (default: every weekday with a row in either source)."""
    spot = spot if spot is not None and len(spot) else pd.DataFrame(columns=["ts", "close"] + OPT_COLS)
    fut = fut if fut is not None and len(fut) else pd.DataFrame(columns=["ts"] + FUT_COLS + ["fexp"])
    sdays = set(pd.DatetimeIndex(spot.ts).date) if len(spot) else set()
    fdays = set(pd.DatetimeIndex(fut.ts).date) if len(fut) else set()
    if days is None:
        days = sorted(d for d in sdays | fdays if d.weekday() < 5)
    days = list(days)
    g = grid(days)
    m = pd.DataFrame({"ts": g})
    if not len(m):
        return m
    m = m.merge(spot.rename(columns={"close": "spot_close"}), on="ts", how="left")
    m = m.merge(fut, on="ts", how="left")
    m["fexp"] = pd.to_datetime(m["fexp"])
    for c in FUT_COLS + OPT_COLS:
        m[c] = m[c].astype(float)
    m["day"] = m.ts.dt.date
    # day source: decided from the previous day (known at the open)
    has_opt = config.SPECS[sym].options if sym in config.SPECS else bool(sdays)
    src, prev = {}, None
    for d in days:
        if prev is None:
            src[d] = "spot" if has_opt else "fut"
        else:
            src[d] = "spot" if prev in sdays else ("fut" if prev in fdays else ("spot" if has_opt else "fut"))
        prev = d
    m["src"] = m.day.map(src)
    # segment: spot days by option expiries before the day; future days by the held contract
    ex = np.array(sorted(pd.Timestamp(e) for e in (expiries or [])), dtype="datetime64[ns]")
    dd = pd.to_datetime(m.day).values
    n_before = np.searchsorted(ex, dd, side="left")
    first_exp = m.groupby("day").fexp.transform("first")
    fseg = "F" + pd.to_datetime(first_exp).dt.strftime("%Y-%m-%d").fillna("none")
    m["seg"] = np.where(m.src == "spot", "S" + pd.Series(n_before).astype(str), fseg)
    m["close"] = np.where(m.src == "spot", m.spot_close, m.fc).astype(float)
    m = m.drop(columns=["spot_close"])
    seg = m.seg.to_numpy()
    m["roll"] = np.r_[False, seg[1:] != seg[:-1]]
    flag = np.empty(len(m), dtype=object)
    for _, ix in m.groupby("seg", sort=False).indices.items():
        c = m.close.to_numpy(float)[ix]
        flag[ix] = quality.series_flags(c, c, c, c, jump=config.SUSPECT_JUMP)
    m["flag"] = flag
    m = _carry(m, OPT_COLS, pd.factorize(m.day)[0], config.OPT_MAX_AGE)
    fx = pd.to_datetime(m.fexp)
    m["fdte"] = (fx - pd.to_datetime(m.day)).dt.days.astype(float)
    nxt = np.searchsorted(ex, dd, side="left")
    dte = np.full(len(m), np.nan)
    okx = nxt < len(ex)
    dte[okx] = (ex[nxt[okx]] - dd[okx]) / np.timedelta64(1, "D")
    m["dte"] = dte
    m["expiry_day"] = (m.dte == 0).astype(np.int8)
    cal = cal if cal is not None else events.calendar(min(days), max(days))
    for k, v in events.minutes_to_event(pd.DatetimeIndex(m.ts), cal).items():
        m[f"ev_{k}"] = v.to_numpy(float)
    m["sym"] = sym
    return m


# --------------------------------------------------------------------------------------------------- sources and I/O
def load_sources(lake, sym: str, fut_lake=None) -> dict:
    """Read one symbol's raw sources: the option book (if any), the continuous future from the far-month cut-off on
    (mcx.near_month_from), and the daily near-month series. Returns dict(spot, fut, expiries, daily, qa)."""
    qa = {}
    opt = lake_mcx.load_options(str(lake), sym) if config.SPECS[sym].options else pd.DataFrame()
    sf, qa["spot"] = clean.spot_frame(opt)
    expiries = clean.option_expiries(sf)
    fl = str(fut_lake or lake)
    daily = None
    dfs = lake_mcx._find(fl, "fut_daily_continuous.parquet") or lake_mcx._find(str(lake), "fut_daily_continuous.parquet")
    if dfs:
        daily = pd.read_parquet(dfs[0])
        daily = daily[(daily.symbol.astype(str) == sym) & (daily.get("expiry_code", 0) == 0)]
    try:
        fut = lake_mcx.load_futures(fl, sym, session_only=True)
    except FileNotFoundError:
        fut = pd.DataFrame()
    ff = pd.DataFrame()
    if len(fut):
        cm, rolls = lake_mcx.continuous(fut, sym)
        cm["near_share"] = lake_mcx.near_share(cm, daily)
        nf = lake_mcx.near_month_from(cm)
        qa["fut_near_month_from"] = str(nf) if nf else None
        if nf is not None:
            cm = cm[cm.ts.dt.tz_convert(clean.IST).dt.date >= nf]
            ff = clean.fut_frame(cm)
        qa["fut_rows"] = int(len(ff))
    return dict(spot=sf, fut=ff, expiries=expiries, daily=daily, qa=qa)


def write(root, sym: str, m: pd.DataFrame) -> list[str]:
    base = config.paths(root).cube / sym
    base.mkdir(parents=True, exist_ok=True)
    out = []
    for y, g in m.groupby(m.ts.dt.year):
        p = base / f"{y}.parquet"
        g.to_parquet(p, compression="zstd", index=False)
        out.append(str(p))
    return out


def load(root, sym: str) -> pd.DataFrame:
    base = config.paths(root).cube / sym
    fs = sorted(base.glob("*.parquet"))
    if not fs:
        return pd.DataFrame()
    m = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    m["day"] = m.ts.dt.date
    return m


def info(m: pd.DataFrame) -> dict:
    if not len(m):
        return {"days": 0}
    days = sorted(set(m.day))
    part = pd.Series([config.part(d) for d in days])
    return {"days": len(days), "first": str(days[0]), "last": str(days[-1]),
            "design_days": int((part == "design").sum()), "embargo_days": int((part == "embargo").sum()),
            "holdout_days": int((part == "holdout").sum()), "rows": int(len(m)),
            "src_days": {k: int(v) for k, v in m.groupby("day").src.first().value_counts().items()},
            "segments": int(m.seg.nunique()), "flags": quality.counts(m.flag),
            "close_present": float(np.isfinite(m.close).mean()),
            "fut_rows": int(m.fc.notna().sum()), "iv_rows": int(m.atm_iv.notna().sum())}


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.mcx.cube")
    ap.add_argument("cmd", choices=["build", "info"])
    ap.add_argument("--symbols", nargs="+", default=list(config.SPECS))
    ap.add_argument("--cache", default=None)
    ap.add_argument("--fut-lake", default=None, help="folder holding the newer fut_1m_live_mcx.parquet")
    a = ap.parse_args(argv)
    P = config.paths(a.cache)
    allinfo = {}
    for s in a.symbols:
        if a.cmd == "build":
            src = load_sources(P.lake, s, a.fut_lake)
            m = build_frame(src["spot"], src["fut"], s, src["expiries"])
            write(P.root, s, m)
            P.study.mkdir(parents=True, exist_ok=True)
            (P.study / f"expiries_{s}.json").write_text(json.dumps([str(x) for x in src["expiries"]]))
            if src["daily"] is not None:
                (P.base / "daily").mkdir(parents=True, exist_ok=True)
                src["daily"].to_parquet(P.base / "daily" / f"{s}.parquet", index=False)
            allinfo[s] = {**info(m), "qa": src["qa"]}
        else:
            allinfo[s] = info(load(P.root, s))
        print(s, json.dumps(allinfo[s], default=str))
    if a.cmd == "build":
        (P.study / "cube_info.json").write_text(json.dumps(allinfo, indent=1, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
