"""R51 family: M4 SILVERM morning call (R42; switched off on paper 14 Oct). MCX: lake data only from 5 Aug 2025.

Signal source: the clock (15-minute grid 09:15-13:45) while 11-20 MCX trading days are left to expiry (M4's own
expiry calendar, rebuilt from the lake by research/hunt/r42/m4_rerun.py into <R51_OUT>/m4work); the strike from the
SILVERM future: the call two listed strikes above ATM (ATM = listed strike nearest the future, the lower on a tie),
the third if the second has no price. Entry at the open of the minute after the grid minute (up to 3 minutes later;
next-bar fill), held for 240 minutes (sold at that minute's close, or the last trade within 30 minutes before; none ->
no trade is counted as missing at the last close), one position at a time: the first grid time of the day, then the
first grid time at or after the previous exit. Filters: 1 lot's premium Rs 500 - 1 lakh; the entry IV within
0.5-2x that contract's median IV so far that day (drops broken prints). 1 lot = 5 units.
Costs: MCX option charges (research/hunt/r44/sim.py mcx_charges) + the liq rule with SILVERM's tick (Rs 0.5).
"""
from __future__ import annotations

from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import common as C
import oldcode
import r51cfg as K

SIM44 = oldcode.load("r44", "sim")
WORK = K.OUT / "m4work" / "raw" / "m4" / "work"
QTY, TICK, STEP = 5.0, 0.5, 250.0
GRID = [9 * 60 + 15 + 15 * i for i in range(19)]          # 09:15 .. 13:45
START = date(2025, 8, 5)
OPEN_M = 9 * 60


@lru_cache(maxsize=1)
def data():
    x = pd.concat([pd.read_parquet(K.LAKE / f"opt_SILVERM_MONTH_{y}.parquet") for y in (2025, 2026)])
    ts = x.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"], x["m"] = ts.dt.date, (ts.dt.hour * 60 + ts.dt.minute).astype(int)
    x["side"] = x.side.astype(str)
    x = x[x.side == "C"].drop_duplicates(["day", "strike", "m"])
    M = pd.read_parquet(WORK / "min_SILVERM.parquet", columns=["F", "day", "tod", "dte"])
    M["day"] = pd.to_datetime(M.day).dt.date
    days = {}
    for d, g in x.groupby("day", sort=True):
        con = {}
        for k, h in g.groupby("strike"):
            a = np.full((6, 24 * 60 - OPEN_M), np.nan)
            r = h.m.values - OPEN_M
            ok = (r >= 0) & (r < a.shape[1])
            for i, c in enumerate(("open", "high", "low", "close", "volume", "iv")):
                a[i, r[ok]] = h[c].values[ok]
            con[float(k)] = a
        days[d] = dict(con=con)
    for d, g in M.groupby("day"):
        if d in days:
            f = np.full(24 * 60 - OPEN_M, np.nan)
            r = g.tod.values.astype(int) - OPEN_M
            ok = (r >= 0) & (r < len(f))
            f[r[ok]] = g.F.values[ok]
            days[d]["F"] = f
            days[d]["dte"] = int(g.dte.iloc[0])
    return days


def _ticks(v_lots):
    return 1.0 if v_lots >= 20 else (2.0 if v_lots > 0 else 4.0)


def _vol5(a, r):
    v = a[4, max(r - 5, 0):r]
    return float(np.nansum(v)) / QTY


def trade(D, g: int, money: int = 2, hold: int = 240):
    """One trade from grid minute g (minutes since midnight). Returns a dict or None (no fill / filtered)."""
    F = D.get("F")
    if F is None:
        return None
    r = g - OPEN_M
    seg = F[max(r - 3, 0):r + 1]
    seg = seg[np.isfinite(seg)]
    if not len(seg):
        return None
    f = float(seg[-1])
    listed = np.array(sorted(D["con"]))
    if not len(listed):
        return None
    i0 = int(np.argmin(np.abs(listed - f) + (listed > f) * 1e-9))
    for extra in ((money, money + 1) if money > 0 else (0,)):
        if i0 + extra >= len(listed):
            continue
        k = float(listed[i0 + extra])
        a = D["con"][k]
        er = next((j for j in range(r + 1, r + 4) if np.isfinite(a[0, j]) and a[0, j] > 0), None)
        if er is None:
            continue
        E = float(a[0, er])
        if not (500 <= E * QTY <= 100000):
            return None
        ivs = a[5, :er + 1]
        ivs = ivs[np.isfinite(ivs) & (ivs > 0)]
        if len(ivs) >= 5 and np.isfinite(a[5, er]) and not (0.5 <= a[5, er] / np.median(ivs) <= 2.0):
            return None
        xt = er + hold - 1
        seg = a[3, max(xt - 29, er):xt + 1]
        ok = np.flatnonzero(np.isfinite(seg))
        if len(ok):
            X, why = float(seg[ok[-1]]), "time"
        else:
            last = np.flatnonzero(np.isfinite(a[3, er:xt + 1]))
            X, why = float(a[3, er + last[-1]]), "missing"
        rE = round(E * 1.0005, 2) + _ticks(_vol5(a, er)) * TICK
        rX = max(round(X * 0.9995, 2) - _ticks(_vol5(a, xt)) * TICK, 0.0)
        ch = float(SIM44.mcx_charges(rE, rX, QTY))
        return dict(und="SILVERM", side="C", strike=k, er=er + OPEN_M, xr=xt + OPEN_M, E=E, X=X, rE=rE, rX=rX,
                    qty=QTY, why=why, net=(rX - rE) * QTY - ch, gross=(X - E) * QTY)
    return None


def in_window(D) -> bool:
    return 11 <= D.get("dte", -1) <= 20


def day_list(lo, hi):
    C.check_range(max(lo, K.START), hi)
    return [d for d in sorted(data()) if lo <= d <= hi]


def make(money: int = 2, hold: int = 240) -> C.Config:
    def run(lo, hi):
        rows = []
        days = data()
        for d in day_list(lo, hi):
            D = days[d]
            if not in_window(D):
                continue
            free = 0
            for g in GRID:
                if g < free:
                    continue
                t = trade(D, g, money, hold)
                if t is not None:
                    t["day"] = d
                    rows.append(t)
                    free = t["xr"]
        return C.frame(rows)

    def twin_fn(T, nt, rng):
        days = data()
        tot = np.zeros(nt)
        for d, g in T.groupby("day"):
            D = days[d]
            mins = np.arange(GRID[0], GRID[-1] + 1)
            vals = {}
            for i in range(nt):
                for m in rng.choice(mins, size=len(g), replace=False):
                    m = int(m)
                    if m not in vals:
                        t = trade(D, m, money, hold)
                        vals[m] = t["net"] if t is not None else 0.0
                    tot[i] += vals[m]
        return tot

    return C.Config(f"m4|otm{money}|h{hold}", run, None, twin_fn)


def sessions(lo, hi):
    return len(day_list(lo, hi))


def variants() -> list:
    ex = dict(unds=("SILVERM",), sessions=sessions, design_start=START)
    grid = [make(2, 240), make(2, 120), make(0, 240), make(0, 120)]
    return [C.Variant("M4-SILVERM", "MCX paper arm", "clock (15-min grid), strike from the future", "orig", grid[:1],
                      "SILVERM call 2 strikes OTM, 09:15-13:45 grid, 11-20 days to expiry, 240-min hold",
                      extra=dict(ex)),
            C.Variant("M4-SILVERM-RT", "MCX paper arm", "clock, strike from the future", "retune", grid,
                      "re-tune: hold {240, 120} min x strike {OTM2, ATM}", extra=dict(ex))]
