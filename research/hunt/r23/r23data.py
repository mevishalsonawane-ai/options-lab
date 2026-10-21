"""R23 sessions: R22's contexts plus what the anatomy needs (previous day, VIX minutes, option flow). 13 Oct 2026.

    sessions(und)  -> [Sess]   every session from the 16th (ATR14), BANKNIFTY / NIFTY with their R17 Day, FINNIFTY
                               index-only. Option flow (volume, call / put OI per minute) comes from the adapter's option
                               rows of the nearest expiry AFTER the day and is cached in <cache>/r23, never in git.
"""
from __future__ import annotations

import os
import pickle
from dataclasses import dataclass
from datetime import date

import numpy as np

import r23cfg as CFG
import r22study as S22

N = 375
OI_MEMBER_ROWS = 5             # a contract counts in the OI totals if it printed OI in rows 0 .. 4


@dataclass
class Sess:
    und: str
    day: date
    ctx: object                     # R22 Ctx (ix, atr, vix, expiry, prev_close, lv, w)
    d17: object | None              # R17 Day (options, lot) for the traded indices; None for FINNIFTY
    prev: tuple                     # previous session (open, high, low, close)
    vix_min: np.ndarray | None      # VIX close per minute (forward-filled) or None
    flow: tuple | None              # (vol, oi_call, oi_put) arrays (375,) or None
    ovol_hist: list                 # the previous 20 sessions' option volume rows 0 .. 44 (and 0 .. 14): [(v45, v15)]

    @property
    def ix(self):
        return self.ctx.ix

    @property
    def atr(self):
        return self.ctx.atr


def _flow_from_options(o, day) -> tuple | None:
    """Per-minute option volume and call / put OI of the nearest expiry after `day` (o: the adapter's option rows)."""
    import pandas as pd
    import data as D17
    if o is None or len(o) == 0:
        return None
    o = o[o.expiry > day]
    if len(o) == 0:
        return None
    o = o[o.expiry == o.expiry.min()]
    vol = np.zeros(N)
    oi = {"C": np.zeros(N), "P": np.zeros(N)}
    for (k, s), g in o.groupby(["strike", "side"]):
        a = D17._grid(g, day, ["volume", "oi"])
        vol += np.nan_to_num(a[:, 0])
        x = a[:, 1]
        if np.isfinite(x[:OI_MEMBER_ROWS]).any() and s in oi:
            oi[s] += pd.Series(x).ffill().fillna(0.0).values
    return vol.astype(np.float32), oi["C"].astype(np.float32), oi["P"].astype(np.float32)


def flows(und: str) -> dict:
    """{day: (vol, oi_call, oi_put)} from the adapter (cached)."""
    p = os.path.join(CFG.OUT, f"flow_{und}.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    ad = CFG.CFG22.K17.adapter()
    ss = ad.sessions(und)
    out = {}
    for dd in ad.load(und, ss[0], ss[-1]):
        f = _flow_from_options(dd.options, dd.day)
        if f is not None:
            out[dd.day] = f
    os.makedirs(CFG.OUT, exist_ok=True)
    with open(p, "wb") as fh:
        pickle.dump(out, fh, protocol=4)
    return out


_CACHE: dict = {}


def sessions(und: str, with_flow: bool = True) -> list[Sess]:
    key = (und, with_flow)
    if key in _CACHE:
        return _CACHE[key]
    days = S22.load_days(und)
    vix_by_day = None
    if und == "FINNIFTY":
        vix_by_day = {d.day: d.vix_prev for d in S22.load_days("NIFTY")}
    ctxs = S22.contexts(und, days, vix_by_day)
    fl = flows(und) if (with_flow and und in CFG.TRADE_UNDS) else {}
    out = []
    v45 = {}
    for d in days:
        f = fl.get(d.day)
        if f is not None:
            v45[d.day] = (float(f[0][:45].sum()), float(f[0][:15].sum()))
    order = [d.day for d in days]
    for c in ctxs:
        pd_ = days[c.i - 1].ix
        prev = (float(pd_[0, 0]), float(np.max(pd_[:, 1])), float(np.min(pd_[:, 2])), float(pd_[-1, 3]))
        d17 = days[c.i] if und in CFG.TRADE_UNDS else None
        vm = getattr(days[c.i], "vix_min", None)
        hist = [v45[x] for x in order[max(0, c.i - 20):c.i] if x in v45]
        out.append(Sess(und, c.day, c, d17, prev, vm, fl.get(c.day), hist))
    _CACHE[key] = out
    return out
