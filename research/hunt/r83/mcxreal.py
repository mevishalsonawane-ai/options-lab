"""R83: replay frozen rules on REAL MCX minutes (5 Aug 2025 - 13 Oct 2026), as futures AND as the 1-ITM near-month
option (PREREG section 3, "options versus futures"). Called only from a final read.

Data: R53's MCX market (research/hunt/r53/mcx/data.py): near future = per-minute median of the lake option rows' spot
(CLOSE-only), printed option legs with R42's F1 filter, expiry days from the straddle jump. Fills and exits use R53's
sim_option / sim_fut (option bought at the first print within 2 minutes after the signal minute; option stop on its
low), but every cost is re-priced with R83's strict costs: futures common.fut_net (NG 0.06% half-spread); options
Zerodha charges + half-spread CRUDEOIL 0.30% / 0.60%, NATURALGAS 0.45% / 0.90% (from / before 17:00 IST).
Option trades skip expiry days (R53 / M3 rule). Lake path: IRAALGO_LAKE (default /dev/shm/lake).
"""
from __future__ import annotations

import os

import numpy as np
import pandas as pd

os.environ.setdefault("IRAALGO_LAKE", str(__import__("pathlib").Path(os.environ.get("R83_DATA", "/dev/shm")) / "lake"))
os.environ.setdefault("IRAALGO_CACHE", "/dev/shm/r83cache")

from research.hunt.r83 import common as C  # noqa: E402

OPT_HALF = {"CRUDE": (0.0030, 0.0060), "NG": (0.0045, 0.0090)}
SYM = {"CRUDE": "CRUDEOIL", "NG": "NATURALGAS"}
OPT_MULT = {"CRUDE": 100.0, "NG": 1250.0}
FUT_UNITS = {"CRUDE": 10.0, "NG": 250.0}        # CRUDEOILM, NATGASMINI


def opt_half(mkt, tod):
    a, b = OPT_HALF[mkt]
    return a if tod >= 17 * 60 else b


def reprice_opt(mkt, t, m):
    buy, sell = t["fill"] * OPT_MULT[mkt], t["exit"] * OPT_MULT[mkt]
    tin, tout = int(m.tod[t["entry_i"]]), int(m.tod[t["exit_i"]])
    cost = C.mcx_opt_charges(buy, sell) + opt_half(mkt, tin) * buy + opt_half(mkt, tout) * sell
    t["cost"], t["net"] = cost, sell - buy - cost
    t["gross"] = sell - buy
    return t


def reprice_fut(mkt, t):
    gross, net = C.fut_net(mkt, t["side"], t["fill"], t["exit"], t["fill"] * FUT_UNITS[mkt], stop=t["why"] == "fut_stop")
    t["gross"], t["net"] = gross, net
    return t


def market(mkt):
    from research.hunt.r53.mcx import data as D
    return D.load_market(SYM[mkt])


def _days(m):
    return [pd.Timestamp(d).date() for d in m.days if C.MCX_REAL[0] <= pd.Timestamp(d).date() <= C.MCX_REAL[1]]


def _summ(tr):
    s = C.summary([dict(t, entry_ts=pd.Timestamp(t["entry_ts"])) for t in tr])
    s["gross"] = float(sum(t["gross"] for t in tr))
    return s


def eve_replay(pct: pd.Series, variants=("calm", "eve_all")):
    """EVE as the app runs it on real MCX NATURALGAS: NATGASMINI future and the 1-ITM NATURALGAS option (option -15% stop
    on its low, future mid stop / edge + width target on closes, 120 min, flat 23:15). Regime from the proxy ATR pct."""
    from research.hunt.r53.mcx import rules as R
    from research.hunt.r53.mcx import sim as S
    from research.hunt.r83 import eve as E
    m = market("NG")
    fut, opt = [], []
    for day in _days(m):
        d = np.datetime64(pd.Timestamp(day), "ns")
        r = R.range_break(m, d, 17 * 60, 19 * 60, 22 * 60)
        if r is None:
            continue
        j, side, hi, lo = r
        cut = min(m.day_cut[d], m.at_or_before(d, R.CUT_2315))
        e = j + 1
        if e >= cut:
            continue
        exit_i = int(min(cut, e + 120))
        edge = hi if side > 0 else lo
        stopF, tgtF = (hi + lo) / 2, edge + side * (hi - lo)
        p = float(pct.get(day, np.nan))
        tf = S.sim_fut(m, e, side, exit_i=exit_i, fam="natgas", units=250, tick=0.1, stopF=stopF, tgtF=tgtF)
        if tf is not None:
            fut.append(dict(reprice_fut("NG", tf), pct=p))
        if not m.is_exp[m.day_first[d]]:
            to = S.sim_option(m, j, side, exit_i=exit_i, fam="natgas", mult=1250, strike="itm1", wait=2, stop_pct=0.15,
                              stopF=stopF, tgtF=tgtF)
            if to is not None:
                opt.append(dict(reprice_opt("NG", to, m), pct=p))
    out = {"period": [str(x) for x in C.MCX_REAL], "note": "Henry Hub-proxy ATR percentile used for the regime"}
    for v in variants:
        f = [t for t in fut if E.regime_ok(v, t["pct"])]
        o = [t for t in opt if E.regime_ok(v, t["pct"])]
        out[v] = {"futures_NATGASMINI": _summ(f), "option_NATURALGAS_1ITM": _summ(o)}
    return out


def liq_replay(tf: int):
    """The Liquidity book on real MCX CRUDEOIL minutes (close-only: o = h = l = c), as CRUDEOILM futures and as the 1-ITM
    CRUDEOIL option bought at the same signal and sold at the futures trade's exit minute."""
    from research.hunt.r53.mcx import sim as S
    from research.hunt.r83 import liq as L
    m = market("CRUDE")
    df = pd.DataFrame({"ts": pd.DatetimeIndex(m.ts), "c": m.F})
    df["o"] = df["h"] = df["l"] = df["c"]
    df["day"] = df.ts.dt.date
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute
    df = C.mcx_hours(df[df.c > 0])
    days = L.sessions(df)
    fut = L.book(days, tf, notional_fn=lambda px, day: px * FUT_UNITS["CRUDE"])
    fut = [t for t in fut if C.MCX_REAL[0] <= t["entry_ts"].date() <= C.MCX_REAL[1]]
    idx = pd.Series(np.arange(len(m.ts)), index=pd.DatetimeIndex(m.ts))
    opt = []
    for t in fut:
        if t["entry_ts"] not in idx.index or t["exit_ts"] not in idx.index:
            continue
        e, x = int(idx[t["entry_ts"]]), int(idx[t["exit_ts"]])
        if m.is_exp[e] or x <= e:
            continue
        to = S.sim_option(m, e - 1, t["side"], exit_i=x, fam="crude", mult=100, strike="itm1", wait=2)
        if to is not None:
            opt.append(reprice_opt("CRUDE", to, m))
    return {"period": [str(x) for x in C.MCX_REAL], "tf": tf,
            "futures_CRUDEOILM": _summ(fut), "option_CRUDEOIL_1ITM": _summ(opt),
            "note": "close-only MCX series: liquidity zones built from minute closes (o = h = l = c)"}
