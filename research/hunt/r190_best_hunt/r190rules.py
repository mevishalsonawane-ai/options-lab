"""R190 candidates 3, 4 and 6 (PREREG.md section 3) on NIFTY: end-of-day momentum (M1, M2), the noise-area breakout over
TWAP (V1) and the trend / VIX overlay (O1, O2, O3).

    python3 -I research/hunt/r190_best_hunt/r190rules.py build     # ~/.cache/iraalgo/r190/rules_NIFTY.pkl

Signals read the NIFTY index minutes (the lake, 2017 on) and daily closes; options are priced on R51's clean NIFTY store
(the held contract, R17 "liq" fills, dated charges). For every eligible day the table holds the call AND the put net of
every possible entry, so the rule's trades and the random twins come from the same prices.

The table is built for every era, but nothing here summarises a trade: the era cut is in r190study.select_era.
"""
from __future__ import annotations

import bisect
import os
import pickle
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r190cfg as K  # noqa: E402

os.environ["IRAALGO_LAKE"] = str(K.LAKE)

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import r190lib as G  # noqa: E402
from research.obuy.costs import Costs, adverse_bps  # noqa: E402

DATED = Costs("dated")
STORE = K.OUT / "r51" / "clean" / "day_NIFTY.pkl"
TABLE = K.OUT / "rules_NIFTY.pkl"
QLOTS = [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)]


def lot(d: date) -> int:
    out = 0
    for s, n in QLOTS:
        if d >= date.fromisoformat(s):
            out = n
    return out


def _dn(d: date) -> int:
    return (d - date(1970, 1, 1)).days


def ticks(v_lots: float) -> float:
    return 1.0 if v_lots >= 20.0 else (2.0 if v_lots > 0 else 4.0)


def buy_fill(p: float, v_lots: float, mult: float = 1.0) -> float:
    return round(float(adverse_bps(np.array([p]), int(round(5 * mult)), True)[0]) + mult * ticks(v_lots) * K.TICK, 2)


def sell_fill(p: float, v_lots: float, mult: float = 1.0) -> float:
    return max(round(float(adverse_bps(np.array([p]), int(round(5 * mult)), False)[0]) - mult * ticks(v_lots) * K.TICK, 2), 0.0)


def charges(ef: float, xf: float, q: int, d: date) -> float:
    dn = np.array([_dn(d)])
    return float(DATED.charge(True, np.array([ef]), np.array([q]), dn)[0] + DATED.charge(False, np.array([xf]), np.array([q]), dn)[0])


def price(con, er: int, xr: int, qty: int, d: date) -> dict | None:
    """One held contract bought at row er (or up to 2 minutes later) and sold at row xr (or the next print up to 10
    minutes later; else its last print before xr). None: no entry print."""
    if con is None:
        return None
    o, c, v = con[0], con[3], con[4]
    eref = next((r for r in range(er, min(er + K.FILL_SLACK, K.N)) if np.isfinite(o[r]) and o[r] > 0), None)
    if eref is None or eref >= xr:
        return None
    xref = next((r for r in range(xr, min(xr + K.EXIT_SLACK, K.N)) if np.isfinite(o[r]) and o[r] > 0), None)
    if xref is not None:
        x, how = float(o[xref]), "open"
    else:
        seg = np.flatnonzero(np.isfinite(c[eref:xr]))
        if not seg.size:
            return None
        x, how = float(c[eref + seg[-1]]), "last_print"
    e = float(o[eref])
    ve = float(np.nansum(v[max(0, eref - 5):eref])) / qty
    vx = float(np.nansum(v[max(0, xr - 5):xr])) / qty
    ef, xf = buy_fill(e, ve), sell_fill(x, vx)
    ef2, xf2 = buy_fill(e, ve, 2.0), sell_fill(x, vx, 2.0)
    net = (xf - ef) * qty - charges(ef, xf, qty, d)
    stress = (xf2 - ef2) * qty - charges(ef2, xf2, qty, d)
    L = G.fit_lots(ef, qty)
    fit = (xf - ef) * qty * L - charges(ef, xf, qty * L, d) if L > 0 else None
    return dict(net=float(net), stress=float(stress), fit=None if fit is None else float(fit), lots=L, prem=ef * qty,
                exit_how=how)


def strike(D, row: int, close: float) -> float | None:
    k = D.atm[row] if D.atm is not None and 0 <= row < K.N else np.nan
    if not np.isfinite(k):
        if not np.isfinite(close):
            return None
        k = round(close / K.STEP["NIFTY"]) * K.STEP["NIFTY"]
    return float(k)


def daily_series() -> tuple:
    d = pd.read_parquet(K.LAKE / "index_daily.parquet")
    d["date"] = pd.to_datetime(d.date).dt.date
    nifty = d[d.symbol == "NIFTY"].set_index("date").close.sort_index()
    vix = d[d.symbol == "INDIAVIX"].set_index("date").close.sort_index()
    return nifty, vix


def build():
    from research.hunt.r147_night_plus import data as D7
    ixs = D7.index_days("NIFTY")                       # {day: (375, 4)} 2017 on
    sess = sorted(d for d in ixs if np.isfinite(G.day_last(ixs[d])))
    with open(STORE, "rb") as f:
        sys.path.insert(0, str(K.HUNT / "r51"))
        store = pickle.load(f)
    nifty_d, vix_d = daily_series()
    nd_dates, nd_close = list(nifty_d.index), nifty_d.to_numpy(float)
    vx_dates, vx_close = list(vix_d.index), vix_d.to_numpy(float)
    hist_sum, hist_r2, rows = [], [], []
    first = K.ERAS["A"][0]
    for i in range(1, len(sess)):
        d, pd_ = sess[i], sess[i - 1]
        a = ixs[d]
        pc = G.day_last(ixs[pd_])
        r1, r2 = G.mom_returns(a, pc)
        m1 = G.mom_side(r1, r2, hist_sum, hist_r2, "sum")
        m2 = G.mom_side(r1, r2, hist_sum, hist_r2, "r2")
        hist_sum.append(abs(r1 + r2) if np.isfinite(r1) and np.isfinite(r2) else np.nan)
        hist_r2.append(abs(r2) if np.isfinite(r2) else np.nan)
        if d < first or d not in store or store[d].atm is None:
            continue
        D = store[d]
        q = lot(d)
        # overlay inputs: closes strictly before d
        sma = G.sma_prev(list(nd_close[:bisect.bisect_left(nd_dates, d)]))
        vhigh = G.vix_high(list(vx_close[:bisect.bisect_left(vx_dates, d)]))
        rec = dict(day=d, era=K.era_of(d), expiry=bool(D.expiry), qty=q, sma=sma, vix_high=vhigh, m1=m1, m2=m2,
                   r1=r1, r2=r2, mom={}, noise={}, walk={})
        # 3. momentum: both legs 14:50 -> 15:20 (not on the weekly expiry day)
        if not D.expiry:
            px = G.last_close(a, K.R1450)
            k = strike(D, K.R1450 - 1, px)
            rec["mom_px"] = px
            if k is not None:
                for s, nm in ((1, "C"), (-1, "P")):
                    rec["mom"][s] = price(D.con.get((nm, k)), K.R1450, K.R1520, q, d)
        # 4. noise area: every entry mark x side, exited by V1's own rule
        sig = G.sigma_marks([ixs[x] for x in sess[max(0, i - K.NOISE_DAYS):i]])
        c, hi, lo = G.noise_levels(a, sig)
        sq = K.R1505 if D.expiry else K.R1520
        last_mark = max(k for k, t in enumerate(K.MARKS) if t < sq)
        n_entry = sum(1 for t in K.MARKS if t <= K.LAST_ENTRY_MARK)
        for k in range(n_entry):
            if not np.isfinite(c[k]):
                continue
            ks = strike(D, K.MARKS[k] - 1, c[k])
            if ks is None:
                continue
            for s, nm in ((1, "C"), (-1, "P")):
                xk = G.noise_exit(c, hi, lo, k, s, last_mark)
                xr = K.MARKS[xk] if xk >= 0 else sq
                rec["noise"][(k, s)] = price(D.con.get((nm, ks)), K.MARKS[k], xr, q, d)
        rec["walk"]["V1"] = G.noise_walk(c, hi, lo, n_entry, last_mark)
        rec["walk"]["O3"] = G.noise_walk(c, hi, lo, n_entry, last_mark,
                                         allow=lambda kk, s, c=c: G.overlay_ok(s, c[kk], sma, vhigh))
        rows.append(rec)
    TABLE.write_bytes(pickle.dumps(rows))
    print("rules table", len(rows), rows[0]["day"], rows[-1]["day"], flush=True)


if __name__ == "__main__":
    {"build": build}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
