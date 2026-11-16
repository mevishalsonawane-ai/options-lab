"""R184 loader (PREREG.md sections 1-2). The recordings are untrusted, read-only and stay OUTSIDE git; run with python -I.

    load_day(dir2, day, cache) -> {"day", "t0", "NIFTY": U, "BANKNIFTY": U, "quality": {...}}
    U = {bars (R181 Bars from the future's 1-second rows), fut (sec, high, low; last folded in), spot (index per minute),
         opts {(right, strike): R181 Series}}

The option roles CE / REC_CE (PE / REC_PE) of one strike are the same token (PREREG s0), so they are merged per
(right, strike); a second recorded twice keeps its last row. Parsed days are cached as pickles outside the repo.
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
import pickle
import re
from datetime import date

import numpy as np
import pandas as pd

import r184lib as L

R1 = L.R1
INDICES = ("NIFTY", "BANKNIFTY")
COLS = ["epoch_sec", "token", "name", "role", "strike", "last", "buy_vol", "sell_vol", "vol", "bid", "ask", "high", "low"]


def days_of(dir2: str) -> list:
    return sorted(m.group(1) for f in os.listdir(dir2) if (m := re.match(r"orderflow-(\d{4}-\d{2}-\d{2})\.csv\.gz$", f)))


def load_day(dir2: str, day: str, cache: str | None = None) -> dict:
    if cache:
        os.makedirs(cache, exist_ok=True)
        p = os.path.join(cache, f"day_{day}.pkl")
        if os.path.exists(p):
            with open(p, "rb") as f:
                return pickle.load(f)
        out = _load(dir2, day)
        with open(p, "wb") as f:
            pickle.dump(out, f)
        return out
    return _load(dir2, day)


def _load(dir2: str, day: str) -> dict:
    t0 = R1.day_epoch_0915(date.fromisoformat(day))
    df = pd.read_csv(os.path.join(dir2, f"orderflow-{day}.csv.gz"), usecols=COLS, low_memory=False)
    df = df[df.name.isin(INDICES) & (df.epoch_sec >= t0) & (df.epoch_sec < t0 + R1.N * 60)]
    out = {"day": day, "t0": t0, "quality": {}}
    for und in INDICES:
        x = df[df.name == und]
        f = x[x.role == "FUTURE"].drop_duplicates("epoch_sec", keep="last").sort_values("epoch_sec", kind="stable")
        fsec = f.epoch_sec.to_numpy(np.int64)
        last = f["last"].to_numpy(float)
        hi_raw, lo_raw = f["high"].to_numpy(float), f["low"].to_numpy(float)
        bars = R1.bars_from_seconds(fsec, last, hi_raw, lo_raw, f.vol.to_numpy(float), f.buy_vol.to_numpy(float),
                                    f.sell_vol.to_numpy(float), t0)
        hi, lo = np.fmax(last, hi_raw), np.fmin(last, lo_raw)
        ix = x[x.role == "INDEX"].sort_values("epoch_sec", kind="stable")
        isec, ilast = ix.epoch_sec.to_numpy(np.int64), ix["last"].to_numpy(float)
        spot = np.full(R1.N, np.nan)
        for i in range(R1.N):
            end = t0 + 60 * (i + 1)
            k = int(np.searchsorted(isec, end, side="left")) - 1
            if k >= 0 and end - isec[k] <= 120 and np.isfinite(ilast[k]):
                spot[i] = ilast[k]
        o = x[x.role.isin(["CE", "PE", "REC_CE", "REC_PE"])].copy()
        o["right"] = o.role.str[-2:]
        opts = {}
        for (right, strike), g in o.groupby(["right", "strike"]):
            g = g.drop_duplicates("epoch_sec", keep="last").sort_values("epoch_sec", kind="stable")
            opts[(right, float(strike))] = R1.Series(g.epoch_sec.to_numpy(np.int64), g["last"].to_numpy(float),
                                                    g.vol.to_numpy(float), g.bid.to_numpy(float), g.ask.to_numpy(float))
        thin = L.thin_mask(bars.rows, bars.c)
        out[und] = dict(bars=bars, fut=(fsec, hi, lo), spot=spot, opts=opts)
        out["quality"][und] = dict(fut_minutes=int(np.isfinite(bars.c).sum()), thin=int(thin.sum()),
                                   spot_minutes=int(np.isfinite(spot).sum()), strikes=len({k[1] for k in opts}),
                                   option_rows=int(len(o)))
    return out
