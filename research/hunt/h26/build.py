"""h26 feature panels (see PREREG.md). Pure features, no P&L.

    OBUY_CACHE=<scratch>/hunt/h26/cache flock <scratch>/obuy.lock python3 -I research/hunt/h26/build.py opt [UND ...]
    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/build.py stk

opt: per underlying, per day, per minute (375 cols) option-chain features from the NEAREST series (ATM from the index
     close at that minute; when comparing with t-L the strike set is the one fixed at t):
       spot, BU15, BUopen, dOIpc2, dOIpc5, wallC, wallP, vc5, vp5, dpc5, dpp5, vc15, vp15, pf15
     -> <cache>/h26/opt_<U>.npz  (days int (days since 1970), X float32 [ndays, 14, 375])
stk: constituent minute close / volume / session VWAP for the BANKNIFTY and NIFTY baskets
     -> <cache>/h26/stk.npz
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market, dnum  # noqa: E402

OUT = os.path.join(C.CACHE, "h26")
FEATS = ["spot", "BU15", "BUopen", "dOIpc2", "dOIpc5", "wallC", "wallP", "vc5", "vp5", "dpc5", "dpp5", "vc15", "vp15", "pf15"]
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]

# static approximate index weights (%), 2025; they drift - a stated limitation
W_BN = {"HDFCBANK": 27.0, "ICICIBANK": 24.5, "SBIN": 9.0, "KOTAKBANK": 8.5, "AXISBANK": 8.5, "INDUSINDBK": 2.5,
        "FEDERALBNK": 2.6, "IDFCFIRSTB": 2.4, "BANKBARODA": 2.5, "AUBANK": 2.3, "PNB": 2.2, "CANBK": 2.4}
W_NF = {"HDFCBANK": 13.0, "ICICIBANK": 9.0, "RELIANCE": 8.5, "INFY": 5.0, "BHARTIARTL": 4.5, "LT": 4.0, "ITC": 3.5,
        "TCS": 3.0, "SBIN": 3.0, "AXISBANK": 3.0, "KOTAKBANK": 2.8, "M_M": 2.6}
HEAVY = {"BANKNIFTY": ["HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK"],
         "NIFTY": ["HDFCBANK", "ICICIBANK", "RELIANCE", "INFY", "BHARTIARTL", "LT", "ITC", "TCS", "SBIN", "AXISBANK"]}


def ffill(a):
    """forward-fill NaN along axis 1 of a 2-D array (leading NaN stay NaN)."""
    return pd.DataFrame(np.asarray(a, dtype=np.float64).T).ffill().values.T


def day_feats(ch, spot, step):
    W = C.W
    T = np.arange(W)
    K = ch.K
    cC, cP = ffill(ch.c["C"]), ffill(ch.c["P"])
    oC, oP = ffill(ch.oi["C"]), ffill(ch.oi["P"])
    vC, vP = np.nan_to_num(ch.v["C"]), np.nan_to_num(ch.v["P"])
    atm = np.round(spot / step) * step
    offs = np.arange(-10, 11)
    want = atm[None, :] + offs[:, None] * step                      # [21, W]
    pos = np.searchsorted(K, np.nan_to_num(want, nan=-1))
    pos = np.clip(pos, 0, len(K) - 1)
    ok = (K[pos] == want) & ~np.isnan(want)
    ki = np.where(ok, pos, -1)                                      # [21, W]

    def g(A, lag=0, cols=None):
        tc = np.clip(T - lag, 0, W - 1) if cols is None else cols
        x = A[np.clip(ki, 0, None), np.broadcast_to(tc, ki.shape)]
        return np.where(ki >= 0, x, np.nan)

    def band(b):
        return slice(10 - b, 10 + b + 1)

    out = {"spot": spot}
    # build-up score, ATM+-2
    for nm, cols in (("BU15", np.clip(T - 15, 0, W - 1)), ("BUopen", np.zeros(W, int))):
        dPc, dPp = g(cC) - g(cC, cols=cols), g(cP) - g(cP, cols=cols)
        dOc, dOp = g(oC) - g(oC, cols=cols), g(oP) - g(oP, cols=cols)
        sl = band(2)
        num = np.nansum(np.sign(dPc[sl]) * np.abs(dOc[sl]) - np.sign(dPp[sl]) * np.abs(dOp[sl]), axis=0)
        den = np.nansum(g(oC)[sl] + g(oP)[sl], axis=0)
        out[nm] = np.where(den > 0, num / np.where(den > 0, den, 1), np.nan)
    # put minus call OI change, 15 min
    dOc, dOp = g(oC) - g(oC, 15), g(oP) - g(oP, 15)
    for b in (2, 5):
        sl = band(b)
        num = np.nansum(dOp[sl] - dOc[sl], axis=0)
        den = np.nansum(g(oC)[sl] + g(oP)[sl], axis=0)
        out[f"dOIpc{b}"] = np.where(den > 0, num / np.where(den > 0, den, 1), np.nan)
    # OI walls among ATM+-10 at t
    inwin = np.abs(K[:, None] - atm[None, :]) <= 10 * step
    for nm, A in (("wallC", oC), ("wallP", oP)):
        x = np.where(inwin & ~np.isnan(A), A, -1.0)
        am = np.argmax(x, axis=0)
        out[nm] = np.where(x.max(axis=0) > 0, K[am].astype(float), np.nan)
    # volumes: window sums via cumsum, strikes fixed at t
    CSc, CSp = np.cumsum(vC, axis=1), np.cumsum(vP, axis=1)

    def wsum(CS, L, b):
        sl = band(b)
        hi = g(CS)[sl]
        lo = np.where(T - L >= 0, g(CS, cols=np.clip(T - L, 0, W - 1))[sl], 0.0)
        return np.nansum(hi - lo, axis=0)

    out["vc5"], out["vp5"] = wsum(CSc, 5, 2), wsum(CSp, 5, 2)
    out["vc15"], out["vp15"] = wsum(CSc, 15, 5), wsum(CSp, 15, 5)
    for nm, A in (("dpc5", cC), ("dpp5", cP)):
        a, b = g(A)[band(2)], g(A, 5)[band(2)]
        with np.errstate(invalid="ignore", divide="ignore"):
            out[nm] = np.nanmean(np.where(b > 0, a / b - 1, np.nan), axis=0)
    # premium flow (tick rule), ATM+-5, 15 min
    def flow(c, v):
        dc = np.diff(np.nan_to_num(c), axis=1, prepend=np.nan_to_num(c[:, :1]))
        return np.cumsum(np.nan_to_num(c) * v * np.sign(dc), axis=1), np.cumsum(np.nan_to_num(c) * v, axis=1)
    fC, tC = flow(cC, vC)
    fP, tP = flow(cP, vP)
    num = wsum(fC, 15, 5) - wsum(fP, 15, 5)
    den = wsum(tC, 15, 5) + wsum(tP, 15, 5)
    out["pf15"] = np.where(den > 0, num / np.where(den > 0, den, 1), np.nan)
    return np.stack([np.asarray(out[f], dtype=np.float32) for f in FEATS])


def build_opt(u):
    mk = market()
    ix = mk.index(u)
    M = ix.mat()
    op = mk.options(u)
    step = C.STEP[u]
    days, X = [], []
    t0 = time.time()
    for i, d in enumerate(ix.days):
        if d.year < 2020:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        sp = M["c"][i].copy()
        if np.isnan(sp).all():
            sp = ch.spot.copy()
        sp = ffill(sp[None, :])[0].copy()
        if np.isnan(sp[0]):
            first = np.flatnonzero(~np.isnan(sp))
            if not len(first):
                continue
            sp[:first[0]] = sp[first[0]]
        with np.errstate(invalid="ignore", divide="ignore"):
            X.append(day_feats(ch, sp, step))
        days.append(dnum(d))
        if len(days) % 200 == 0:
            print(u, d, len(days), f"{time.time() - t0:.0f}s", flush=True)
    os.makedirs(OUT, exist_ok=True)
    np.savez_compressed(os.path.join(OUT, f"opt_{u}.npz"), days=np.array(days), X=np.stack(X))
    mk.release()
    print(u, "done", len(days), f"{time.time() - t0:.0f}s", flush=True)


def build_stk():
    names = sorted(set(W_BN) | set(W_NF))
    frames = {}
    for s in names:
        fs = [os.path.join(C.DATA, "candles", "minute", "NSE_EQ", s, f"{y}.parquet") for y in (2024, 2025, 2026)]
        x = pd.concat([pd.read_parquet(f) for f in fs if os.path.exists(f)])
        x["ts"] = x.ts.dt.tz_localize(None)
        x["day"] = (x.ts.values.astype("datetime64[D]").astype(np.int64))
        x["col"] = x.ts.dt.hour * 60 + x.ts.dt.minute - C.OPEN_M
        x = x[(x.col >= 0) & (x.col < C.W)].drop_duplicates(["day", "col"])
        frames[s] = x
    days = sorted(set.intersection(*[set(f.day.unique()) for f in frames.values()]))
    dpos = {d: i for i, d in enumerate(days)}
    nd = len(days)
    Cl = np.full((len(names), nd, C.W), np.nan, np.float32)
    V = np.zeros((len(names), nd, C.W), np.float32)
    VW = np.full((len(names), nd, C.W), np.nan, np.float32)
    for k, s in enumerate(names):
        x = frames[s]
        x = x[x.day.isin(dpos)]
        di = x.day.map(dpos).values
        Cl[k, di, x.col.values] = x.close.values
        V[k, di, x.col.values] = x.volume.values
        tp = np.zeros((nd, C.W))
        tp[di, x.col.values] = ((x.high + x.low + x.close) / 3.0).values * x.volume.values
        cv = np.cumsum(V[k].astype(np.float64), axis=1)
        with np.errstate(invalid="ignore", divide="ignore"):
            VW[k] = np.where(cv > 0, np.cumsum(tp, axis=1) / cv, np.nan)
        Cl[k] = ffill(Cl[k].astype(np.float64)).astype(np.float32)
    os.makedirs(OUT, exist_ok=True)
    np.savez_compressed(os.path.join(OUT, "stk.npz"), names=np.array(names), days=np.array(days), Cl=Cl, V=V, VW=VW)
    print("stk", len(names), nd, "days", pd.to_datetime(days[0], unit="D").date(), pd.to_datetime(days[-1], unit="D").date())


if __name__ == "__main__":
    if sys.argv[1] == "opt":
        for u in (sys.argv[2:] or UNDS):
            build_opt(u)
    else:
        build_stk()
