"""R182 loaders (PREREG.md section 2). The recordings and the diagnostics are untrusted, read-only and stay OUTSIDE git;
run with python -I. Parsed days are cached as pickles in a folder outside the repo.

    load_flow_day(dir2, day)  -> per index near future: its recorded seconds (1-second file) and its footprint minutes
    load_min_day(dir1, day)   -> the first export's futures minute rows (orderflow_1m, the TickZone recorder): the cross-check
    load_orders(diag_dir)     -> every day's paper orders of R51, R72, Liquidity and the 15:15 auto square-off

Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import glob
import gzip
import json
import os
import pickle
from datetime import date

import numpy as np
import pandas as pd

import r182lib as LB

COLS = ["epoch_sec", "name", "role", "last", "buy_vol", "sell_vol", "vol", "q_buy_vol", "q_sell_vol", "depth_imb",
        "tbq", "tsq", "updates", "stale", "gap_s", "prints"]


def _cached(cache, key, fn):
    if not cache:
        return fn()
    os.makedirs(cache, exist_ok=True)
    p = os.path.join(cache, key + ".pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    out = fn()
    with open(p, "wb") as f:
        pickle.dump(out, f)
    return out


def flow_days(dir2: str) -> list[str]:
    out = []
    for p in sorted(glob.glob(os.path.join(dir2, "orderflow-20*.csv.gz"))):
        out.append(os.path.basename(p)[len("orderflow-"):len("orderflow-") + 10])
    return out


def load_flow_day(dir2: str, day: str, cache: str | None = None) -> dict:
    return _cached(cache, f"f_{day}", lambda: _load_flow_day(dir2, day))


def _load_flow_day(dir2: str, day: str) -> dict:
    t0 = LB.day_epoch_0915(date.fromisoformat(day))
    df = pd.read_csv(os.path.join(dir2, f"orderflow-{day}.csv.gz"), usecols=COLS, low_memory=False)
    df = df[(df.role == "FUTURE") & df.name.isin(LB.INDICES)]
    df = df.drop_duplicates(["name", "epoch_sec"], keep="last").sort_values(["name", "epoch_sec"], kind="stable")
    fp_all = pd.read_csv(os.path.join(dir2, f"orderflow-footprint-{day}.csv.gz"))
    out = {"day": day, "t0": t0}
    for und in LB.INDICES:
        f = df[df.name == und]
        sec = {c: f[c].to_numpy(float) for c in COLS if c not in ("name", "role")}
        sec["epoch_sec"] = f.epoch_sec.to_numpy(np.int64)
        fp = {}
        for me, g in fp_all[fp_all.name == und].groupby("minute_epoch"):
            fp[int(me)] = (g.price.to_numpy(float), g.buy_vol.to_numpy(float), g.sell_vol.to_numpy(float))
        out[und] = dict(sec=sec, fp=fp)
    return out


def load_min_day(dir1: str, day: str, cache: str | None = None) -> dict | None:
    p = os.path.join(dir1, day, "orderflow_1m.jsonl.gz")
    if not os.path.exists(p):
        return None
    return _cached(cache, f"m_{day}", lambda: _load_min_day(p, day))


def _load_min_day(p: str, day: str) -> dict:
    t0 = LB.day_epoch_0915(date.fromisoformat(day))
    out = {u: dict(d=np.full(LB.N, np.nan), vol=np.full(LB.N, np.nan), c=np.full(LB.N, np.nan), secs=np.zeros(LB.N),
                   obi=np.full(LB.N, np.nan), absorb=np.zeros(LB.N, bool)) for u in ("NIFTY", "BANKNIFTY")}
    with gzip.open(p, "rt") as f:
        for line in f:
            r = json.loads(line)
            if r.get("t") != "A" or r.get("kind") != "FUT" or r.get("und") not in out:
                continue
            i = int((r["s"] - t0) // 60)
            if 0 <= i < LB.N:
                u = out[r["und"]]
                u["d"][i] = r.get("delta", np.nan)
                u["vol"][i] = r.get("vol", np.nan)
                u["c"][i] = r.get("c", np.nan) if r.get("c") is not None else np.nan
                u["secs"][i] = r.get("secs", 0)
                u["obi"][i] = r.get("obi", np.nan) if r.get("obi") is not None else np.nan
                u["absorb"][i] = bool(r.get("absorb", False))
    return out


def load_orders(diag_dir: str) -> list[dict]:
    """The paper orders of the three arms and of the 15:15 auto square-off, each with its day. Live orders are skipped."""
    out = []
    for p in sorted(glob.glob(os.path.join(diag_dir, "diag-*.json.gz"))):
        with gzip.open(p, "rt") as f:
            d = json.load(f)
        day = d.get("day") or os.path.basename(p)[5:15]
        for o in d.get("orders") or []:
            if o.get("account") != "paper":
                continue
            if LB.arm_of(o.get("source", "")) is None and not LB.is_squareoff(o.get("source", "")):
                continue
            out.append(dict(day=day, at=o.get("at"), source=o.get("source"), symbol=o.get("symbol"), side=o.get("side"),
                            qty=int(o.get("qty") or 0), filled=int(o.get("filled") or 0), status=o.get("status"),
                            type=o.get("type"), avg=float(o.get("avg") or 0.0)))
    return out
