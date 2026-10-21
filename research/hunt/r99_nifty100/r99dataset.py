"""R99 dataset: one row per (signal row, side) on a 5-minute grid (09:20-14:30) of every NIFTY session in a period,
with causal index features and the outcome of buying the ATM weekly option at the next minute (R51 engine, real
prints and fills, dated charges and lots):

    label (T, S): limit target +T premium points, resting stop -S points, else the square-off (15:10; 15:05 expiry)

    python research/hunt/r99_nifty100/r99dataset.py design
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

import r99cfg as K
import r51cfg as K51
import engine as E51
import r99data as DA
from research.hunt.r97_gold_reverse import features as FE
from research.hunt.r98_catch100 import candles as CA


def label(D, side: str, r: int, T: float, S: float):
    """Buy the ATM `side` option chosen at signal row r, entry at row r + 1. Returns (net_rs, gross_pts, why, E) or
    None (no contract / no fill)."""
    k = E51.strike_for(D, K.UND, side, r)
    if k is None:
        return None
    C = D.contract(side, k)
    if C is None:
        return None
    e = r + 1
    sq = E51.sq_row(D, E51.Exits())
    op = C[0, e:min(e + 3, sq)]
    ok = np.flatnonzero(np.isfinite(op) & (op > 0))
    if not len(ok):
        return None
    Ein = float(op[ok[0]])
    qty = float(K51.lot(K.UND, D.d))
    out = E51.simulate(C.astype(np.float64), e, sq, -1, S / Ein, T / Ein, -1.0, 0.0, 0.0, E51.NOLOCK, -1, -1.0, -1.0,
                       K51.TICK, qty, *E51.SLIP)
    rE, rX, why = out[2], out[3], int(out[6])
    if why == E51.R_NOFILL:
        return None
    net = (rX - rE) * qty - float(E51.charges(K.UND, rE, rX, qty, D.d)[0])
    return float(net), float(out[1] - out[0]), E51.REASONS[why], Ein


def build(period, name: str) -> pd.DataFrame:
    days = DA.days()
    vix = DA.vix_minutes()
    dte = DA.sessions_to_expiry(days)
    ds = sorted(days)
    rows = []
    for n, d in enumerate(ds):
        if not (period[0] <= d <= period[1]) or days[d].atm is None:
            continue
        D = days[d]
        P = days[ds[n - 1]] if n > 0 else None
        prev = None
        if P is not None and np.isfinite(P.idx[3]).any():
            prev = dict(high=float(np.nanmax(P.idx[1])), low=float(np.nanmin(P.idx[2])),
                        close=float(P.idx[3][np.flatnonzero(np.isfinite(P.idx[3]))[-1]]))
        ok = np.flatnonzero(np.isfinite(D.idx[3]))
        if len(ok) < 300:
            continue
        ts = pd.DatetimeIndex([pd.Timestamp(d) + pd.Timedelta(minutes=K51.OPEN_MIN + int(r)) for r in ok])
        F, tod = D.idx[3][ok].astype(float), (K51.OPEN_MIN + ok).astype(int)
        fe = FE.day_features(ts, F, tod, prev)
        cf = CA.session_candles(ts, F, tod)
        vx = vix.get(d)
        for j, r in enumerate(ok):
            if not (K.FIRST_ROW <= r <= K.LAST_ROW) or (r - K.FIRST_ROW) % K.SAMPLE_EVERY:
                continue
            for d_, side in ((1, "C"), (-1, "P")):
                x = FE.directional(fe, j, d_)
                x.update(CA.signed(cf.iloc[[j]], d_).iloc[0].to_dict())
                x.update(day=pd.Timestamp(d), row=int(r), tod=int(tod[j]), side=d_, idx=float(F[j]),
                         dte=int(dte[d]), expiry=int(D.expiry),
                         vix=float(vx[r]) if vx is not None else np.nan,
                         vix15=float(vx[r] - vx[max(r - 15, 0)]) if vx is not None else np.nan)
                C = D.contract(side, E51.strike_for(D, K.UND, side, int(r)) or -1.0)
                x["prem_pct"] = float(C[3, r] / F[j] * 100) if C is not None and np.isfinite(C[3, r]) else np.nan
                for T, S in K.LABELS:
                    lb = label(D, side, int(r), T, S)
                    if lb is not None:
                        x[f"net_{int(T)}"], x[f"pts_{int(T)}"], x[f"why_{int(T)}"], x["E"] = lb
                rows.append(x)
    df = pd.DataFrame(rows)
    K.CACHE.mkdir(parents=True, exist_ok=True)
    df.to_parquet(K.CACHE / f"dataset_{name}.parquet", index=False)
    return df


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "design"
    if which != "design":
        raise SystemExit("only the design dataset is built before the pre-registration")
    df = build((K.START, K.DESIGN_END), "design")
    print(df.shape, df.day.nunique())
