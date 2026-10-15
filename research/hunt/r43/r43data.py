"""R43 data: expiry days (R25's Day records) for 5 indices, and the 5-minute INDEX bars every signal is read from.

    python3 research/hunt/r43/r43data.py build [UND ...]    # <OUT>/days_<UND>.pkl and <OUT>/bars5_<UND>.pkl

Expiry days come from the data, exactly as R25 (`r25data.expiry_days`: ATM time value at 15:15 below 0.6% of spot and
the next session's more than twice it). Only the WEEK files (the nearest expiry) are read: since 20 Nov 2024 BANKNIFTY,
FINNIFTY and MIDCPNIFTY have only monthlies and their WEEK and MONTH files are the same file, so reading only WEEK never
counts an expiry twice. Unlike R25, BANKNIFTY monthly expiries after its last weekly are kept: they are its own expiry
days now.

5-minute bars: bar j of a session covers 1-minute rows 5j .. 5j+4 (09:15 + 5j to 09:15 + 5j + 5) and is known at the
close of row 5j + 4. O = the first minute's open, H / L = the max / min, C = the last minute's close (minutes with no bar
are skipped; a bucket with no minute is no bar). Bars of EVERY session (not only expiry days) are kept so indicators warm
up on earlier days.
"""
from __future__ import annotations

import pickle
import sys

import numpy as np
import pandas as pd

import r43cfg as K
import r25data as D
from r25data import Day, N  # noqa: F401

BARS = N // 5     # 75 five-minute bars a session


def bars5_from_minutes(m: pd.DataFrame) -> pd.DataFrame:
    """m: 1-minute rows with columns d, m (grid row), open, high, low, close. Returns one row per (d, j) with o h l c,
    sorted in time."""
    x = m.dropna(subset=["close"]).sort_values(["d", "m"]).copy()
    x["j"] = x.m // 5
    g = x.groupby(["d", "j"], sort=True)
    b = pd.DataFrame({"o": g.open.first(), "h": g.high.max(), "l": g.low.min(), "c": g.close.last()}).reset_index()
    return b


def build_days(und: str) -> list[Day]:
    ix = D._index_frames(und)
    vx = D._index_frames("INDIAVIX")
    ixd = dict(tuple(ix.groupby("d")))
    vxd = dict(tuple(vx.groupby("d"))) if len(vx) else {}
    sess = sorted(ixd)
    files = sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet"))
    tvs, frames = [], {}
    for p in files:
        o = D._read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= D.STRIKE_SLACK * K.STEP[und]]
        tvs.append(D._tv_1515(o))
        frames[p] = o
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = set(D.expiry_days(tv))
    out = []
    seen = set()
    for p, o in frames.items():
        o = o[o.d.isin(exp)]
        for d, g in o.groupby("d", sort=True):
            if d in seen or d not in ixd:
                continue
            seen.add(d)
            i = sess.index(d)
            ixa = D._grid(ixd[d], ["open", "high", "low", "close"])
            prev = ixd[sess[i - 1]] if i > 0 else None
            pc = float(prev.close.iloc[-1]) if prev is not None and len(prev) else np.nan
            pr = float(prev.high.max() - prev.low.min()) if prev is not None and len(prev) else np.nan
            vprev = float(vxd[sess[i - 1]].close.iloc[-1]) if i > 0 and sess[i - 1] in vxd else np.nan
            va = D._grid(vxd[d], ["close"])[:, 0] if d in vxd else np.full(N, np.nan)
            ixc = pd.Series(ixa[:, 3])
            g = g[(g.strike - ixc.reindex(g.m.values).values).abs() <= D.STRIKE_SLACK * K.STEP[und]]
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            opts = {}
            for (k, s), h in g.groupby(["strike", "side"]):
                opts[(float(k), s)] = D._grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
            edge = np.full((N, 2), np.nan)
            ec = g[(g.side == "C") & (g.offset == 10)]
            ep = g[(g.side == "P") & (g.offset == -10)]
            edge[ec.m.values, 0] = ec.close.values
            edge[ep.m.values, 1] = ep.close.values
            out.append(Day(d, und, ixa, opts, va, pc, vprev, K.lot(und, d), edge, pr))
    out.sort(key=lambda x: x.day)
    return out


def days_path(und: str):
    return K.OUT / f"days_{und}.pkl"


def bars_path(und: str):
    return K.OUT / f"bars5_{und}.pkl"


def load_days(und: str, part: str) -> list[Day]:
    """part: 'design' (before HOLDOUT_START), 'holdout' (only study.py holdout asks) or 'all'. Bad prints are removed
    with R25's same-minute rule."""
    days = pickle.loads(days_path(und).read_bytes())
    if part == "design":
        days = [d for d in days if not K.is_holdout(d.day)]
    elif part == "holdout":
        days = [d for d in days if K.is_holdout(d.day)]
    elif part != "all":
        raise ValueError(part)
    for d in days:
        D.clean(d)
    return days


def calendar(und: str) -> list:
    """Every expiry day's date (no prices): safe to read for counts in any period."""
    return [d.day for d in pickle.loads(days_path(und).read_bytes())]


def load_bars(und: str) -> pd.DataFrame:
    return pickle.loads(bars_path(und).read_bytes())


if __name__ == "__main__":
    import r43data as _self          # pickle Day under r25data's module name, not __main__
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            b = _self.bars5_from_minutes(D._index_frames(u))
            bars_path(u).write_bytes(pickle.dumps(b))
            ds = _self.build_days(u)
            days_path(u).write_bytes(pickle.dumps(ds))
            print(u, "bars", len(b), "days", len(ds), ds[0].day if ds else "-", ds[-1].day if ds else "-",
                  "holdout", sum(K.is_holdout(d.day) for d in ds), flush=True)
