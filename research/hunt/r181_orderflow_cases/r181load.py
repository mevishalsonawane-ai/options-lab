"""R181 loaders (PREREG.md section 2). The recordings are untrusted, read-only and stay OUTSIDE git; run with python -I.

    load_day2(dir2, day)  -> the 1-second export's day: per index futures seconds, minute bars, spot, options, footprint
    load_day1(dir1, day)  -> the first export's day: chain rows per minute (A+) and futures minute deltas (cross-check)

Parsed days are cached as pickles in a cache folder outside the repo. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import gzip
import json
import os
import pickle
from datetime import date

import numpy as np
import pandas as pd

import r181lib as LB

INDICES = ("NIFTY", "BANKNIFTY")
COLS = ["epoch_sec", "token", "name", "role", "strike", "last", "buy_vol", "sell_vol", "vol", "bid", "ask", "high", "low",
        "gap_s", "stale"]


def _cached(cache, key, fn):
    if cache:
        os.makedirs(cache, exist_ok=True)
        p = os.path.join(cache, key + ".pkl")
        if os.path.exists(p):
            with open(p, "rb") as f:
                return pickle.load(f)
        out = fn()
        with open(p, "wb") as f:
            pickle.dump(out, f)
        return out
    return fn()


def load_day2(dir2: str, day: str, cache: str | None = None) -> dict:
    return _cached(cache, f"d2_{day}", lambda: _load_day2(dir2, day))


def _load_day2(dir2: str, day: str) -> dict:
    d = date.fromisoformat(day)
    t0 = LB.day_epoch_0915(d)
    t1 = t0 + LB.N * 60
    df = pd.read_csv(os.path.join(dir2, f"orderflow-{day}.csv.gz"), usecols=COLS, low_memory=False)
    df = df[df.name.isin(INDICES) & (df.epoch_sec >= t0) & (df.epoch_sec < t1)]
    df = df.sort_values(["token", "epoch_sec"], kind="stable")
    fp_all = pd.read_csv(os.path.join(dir2, f"orderflow-footprint-{day}.csv.gz"))
    out = {"day": day, "t0": t0}
    for und in INDICES:
        tk = df[(df.name == und) & (df.role == "FUTURE")].token.unique()
        out[und + "_fut_token"] = int(tk[0]) if len(tk) == 1 else None
    for und in INDICES:
        x = df[df.name == und]
        f = x[x.role == "FUTURE"]
        fsec = f.epoch_sec.to_numpy(np.int64)
        last = f["last"].to_numpy(float)
        hi = np.fmax(last, f["high"].to_numpy(float))
        lo = np.fmin(last, f["low"].to_numpy(float))
        bars = LB.bars_from_seconds(fsec, last, f["high"].to_numpy(float), f["low"].to_numpy(float),
                                    f.vol.to_numpy(float), f.buy_vol.to_numpy(float), f.sell_vol.to_numpy(float), t0)
        ix = x[x.role == "INDEX"]
        spot = np.full(LB.N, np.nan)
        isec, ilast = ix.epoch_sec.to_numpy(np.int64), ix["last"].to_numpy(float)
        for i in range(LB.N):
            end = t0 + 60 * (i + 1)
            k = int(np.searchsorted(isec, end, side="left")) - 1
            if k >= 0 and end - isec[k] <= 120 and np.isfinite(ilast[k]):
                spot[i] = ilast[k]
        opts = {}
        o = x[x.role.isin(["CE", "PE", "REC_CE", "REC_PE"])]
        for (role, strike), g in o.groupby(["role", "strike"]):
            g = g.sort_values("epoch_sec", kind="stable")
            opts[(role[-2:], float(strike))] = LB.Series(g.epoch_sec.to_numpy(np.int64), g["last"].to_numpy(float),
                                                        g.vol.to_numpy(float), g.bid.to_numpy(float),
                                                        g.ask.to_numpy(float))
        fp = {}
        fu = fp_all[fp_all.name == und]
        for me, g in fu.groupby("minute_epoch"):
            i = int((me - t0) // 60)
            if 0 <= i < LB.N:
                fp[i] = (g.price.to_numpy(float), g.buy_vol.to_numpy(float), g.sell_vol.to_numpy(float))
        gaps = int((f.gap_s.fillna(0) >= 5).sum())
        stale = int((f.stale.fillna(0) != 0).sum())
        out[und] = dict(bars=bars, fut=(fsec, hi, lo), spot=spot, opts=opts, fp=fp,
                        q=dict(fut_minutes=int(np.isfinite(bars.c).sum()), thin=int((bars.rows < LB.THIN_ROWS).sum()),
                               gap5=gaps, stale=stale, fp_minutes=len(fp), spot_minutes=int(np.isfinite(spot).sum()),
                               strikes=len({k[1] for k in opts})))
    return out


def load_day1(dir1: str, day: str, cache: str | None = None) -> dict | None:
    p = os.path.join(dir1, day)
    if not os.path.isdir(p):
        return None
    return _cached(cache, f"d1_{day}", lambda: _load_day1(p, day))


def _hm_idx(m: str) -> int:
    h, mm = m.split(":")
    return int(h) * 60 + int(mm) - (9 * 60 + 15)


def _load_day1(p: str, day: str) -> dict:
    out = {u: dict(chain={}, d=np.zeros(LB.N), secs=np.zeros(LB.N), seen=np.zeros(LB.N, bool)) for u in INDICES}
    with gzip.open(os.path.join(p, "market.jsonl.gz"), "rt") as f:
        for line in f:
            r = json.loads(line)
            if r.get("t") == "M" and r.get("ix") in INDICES and r.get("chain"):
                i = _hm_idx(r["m"])
                if 0 <= i < LB.N:
                    out[r["ix"]]["chain"][i] = r["chain"]
    with gzip.open(os.path.join(p, "orderflow_1m.jsonl.gz"), "rt") as f:
        for line in f:
            r = json.loads(line)
            if r.get("t") == "A" and r.get("kind") == "FUT" and r.get("und") in INDICES:
                i = _hm_idx(r["m"])
                if 0 <= i < LB.N:
                    u = out[r["und"]]
                    u["d"][i], u["secs"][i], u["seen"][i] = r.get("delta", 0), r.get("secs", 0), True
    return out


def chain_at(day1: dict | None, und: str, i: int):
    """The chain of minute i, else the latest within the 5 minutes before."""
    if not day1:
        return None
    ch = day1[und]["chain"]
    for k in range(i, i - 6, -1):
        if k in ch:
            return ch[k]
    return None
