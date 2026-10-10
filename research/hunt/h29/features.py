"""h29 stage 2: derived pricing features from the panel (see PREREG.md). No outcomes.

    OBUY_CACHE=<scratch>/hunt/h29/cache python3 -I research/hunt/h29/features.py
Output: <OBUY_CACHE>/h29/feat_<UND>.parquet (per minute) and har.parquet (per und-day).
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h29")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"]
W = C.W


def daily_rv(P):
    """per day: RV from 5-min log returns of S (09:15 .. 15:25 grid)."""
    out = []
    for d, g in P.groupby("day", sort=True):
        s = g.S.values.astype(float)
        if len(s) != W or np.isnan(s).any():
            continue
        x = np.log(s[::5])
        out.append((d, float(np.sum(np.diff(x) ** 2))))
    return pd.DataFrame(out, columns=["day", "rv"])


def har_all(rvs):
    """pooled log-HAR; coefficients refit each calendar year on all earlier days (first two years: their own fit)."""
    rows = []
    for und, r in rvs.items():
        r = r.sort_values("day").reset_index(drop=True)
        lr = np.log(np.maximum(r.rv.values, 1e-10))
        s = pd.Series(r.rv.values)
        x1 = np.log(np.maximum(s.shift(1), 1e-10))
        x5 = np.log(np.maximum(s.shift(1).rolling(5).mean(), 1e-10))
        x22 = np.log(np.maximum(s.shift(1).rolling(22).mean(), 1e-10))
        rows.append(pd.DataFrame(dict(und=und, day=r.day, y=lr, x1=x1, x5=x5, x22=x22)))
    H = pd.concat(rows, ignore_index=True).dropna()
    H["year"] = H.day.dt.year
    years = sorted(H.year.unique())
    pred = pd.Series(np.nan, index=H.index)
    first = years[0]
    for y in years:
        tr = H[H.year < y]
        if tr.year.nunique() < 2:
            tr = H[H.year <= first + 1]
        X = np.c_[np.ones(len(tr)), tr[["x1", "x5", "x22"]].values]
        b, *_ = np.linalg.lstsq(X, tr.y.values, rcond=None)
        s2 = np.var(tr.y.values - X @ b)
        te = H.year == y
        Xt = np.c_[np.ones(te.sum()), H.loc[te, ["x1", "x5", "x22"]].values]
        pred[te] = Xt @ b + 0.5 * s2
        print("HAR", y, "fit on", len(tr), "coef", np.round(b, 3), flush=True)
    H["har"] = np.sqrt(252 * np.exp(pred))
    H["rv_ann"] = np.sqrt(252 * np.exp(H.y))
    return H[["und", "day", "har", "rv_ann"]]


def prior_sd(P, col, lag, lo=15, hi=365):
    """per day: sd of the lag-minute change of col over minutes [lo, hi); then mean over the prior 20 days."""
    v = P[col].values.astype(float).reshape(-1, W)
    dv = v[:, lag:] - v[:, :-lag]
    seg = dv[:, max(lo - lag, 0):hi - lag]
    with np.errstate(all="ignore"):
        sd = np.nanstd(seg, axis=1)
    sd = pd.Series(sd).rolling(20, min_periods=10).mean().shift(1).values
    ch = np.full_like(v, np.nan)
    ch[:, lag:] = dv
    return (ch / sd[:, None]).ravel()


def main():
    rvs, Ps = {}, {}
    for u in UNDS:
        P = pd.read_parquet(os.path.join(OUT, f"panel_{u}.parquet"))
        P = P.sort_values(["day", "m"]).reset_index(drop=True)
        assert len(P) % W == 0
        Ps[u] = P
        rvs[u] = daily_rv(P)
    H = har_all(rvs)
    H.to_parquet(os.path.join(OUT, "har.parquet"))
    for u in UNDS:
        P = Ps[u]
        P["dbz"] = prior_sd(P, "basis", 5).astype(np.float32)
        P["drz"] = prior_sd(P, "rr", 15).astype(np.float32)
        S = P.S.values.astype(float).reshape(-1, W)
        r = np.diff(np.log(S), axis=1)
        r = np.c_[np.full(len(S), np.nan), r]
        r[:, :2] = np.nan                                   # from 09:17's return
        cs = np.nancumsum(r ** 2, axis=1)
        n = np.cumsum(~np.isnan(r), axis=1)
        rvt = np.sqrt(cs / np.maximum(n, 1) * 375 * 252)
        rvt[n < 30] = np.nan
        P["rvt"] = rvt.ravel().astype(np.float32)
        P = P.merge(H[H.und == u][["day", "har"]], on="day", how="left")
        P["cheap1"] = (P.iv / P.har).astype(np.float32)
        P["cheap2"] = (P.iv / P.rvt).astype(np.float32)
        P["S30"] = P.groupby("day").S.shift(30)
        P["open"] = P.groupby("day").S.transform("first")
        keep = ["day", "m", "S", "basis", "iv", "rr", "ivm", "dbz", "drz", "har", "rvt", "cheap1", "cheap2", "S30",
                "open", "strad", "tmin", "dte", "exp", "real", "series", "F", "K0"]
        P[keep].to_parquet(os.path.join(OUT, f"feat_{u}.parquet"))
        print(u, len(P), P[["dbz", "drz", "cheap1", "cheap2"]].describe().round(3).to_string(), flush=True)


if __name__ == "__main__":
    main()
