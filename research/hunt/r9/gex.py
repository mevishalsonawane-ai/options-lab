"""R9 C: dealer gamma (GEX) per index per bhavcopy day from NSE F&O bhavcopy per-strike OI + settle prices.
    python3 -I research/hunt/r9/gex.py -> scratch/hunt/r9/gex.parquet (und, day t [bhavcopy date], features)"""
from __future__ import annotations

import glob
import math
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.special import ndtr  # noqa: E402

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = f"{S}/hunt/r9"
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
GRID = np.round(np.arange(0.94, 1.0601, 0.001), 4)


def b76(F, K, T, sig, call):
    st = sig * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * st * st) / st
    d2 = d1 - st
    c = F * ndtr(d1) - K * ndtr(d2)
    return np.where(call, c, c - (F - K))


def iv(price, F, K, T, call):
    lo = np.full(len(price), 0.03)
    hi = np.full(len(price), 1.5)
    for _ in range(50):
        m = 0.5 * (lo + hi)
        v = b76(F, K, T, m, call)
        up = v > price
        hi = np.where(up, m, hi)
        lo = np.where(up, lo, m)
    m = 0.5 * (lo + hi)
    intr = np.maximum(np.where(call, F - K, K - F), 0)
    bad = (price <= intr + 1e-6) | (price >= np.where(call, F, K)) | ~np.isfinite(price)
    bad |= (m <= 0.031) | (m >= 1.49)
    return np.where(bad, np.nan, m)


def gamma(F, K, T, sig):
    st = sig * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * st * st) / st
    return np.exp(-0.5 * d1 * d1) / math.sqrt(2 * math.pi) / (F * st)


def poi():
    fs = {}
    for f in sorted(glob.glob(f"{S}/hunt/h27/raw/poi/*.csv")) + sorted(glob.glob(f"{S}/hunt/h40/data/poi/*.csv")):
        fs[os.path.basename(f)[:8]] = f
    rows = []
    for tag, f in sorted(fs.items()):
        for line in open(f, errors="replace"):
            p = [x.strip().strip('"') for x in line.strip().split(",")]
            if p and p[0] in ("Client", "DII", "FII", "Pro"):
                try:
                    v = [float(x) for x in p[5:9]]   # call long, put long, call short, put short (index options)
                except ValueError:
                    continue
                rows.append([pd.Timestamp(tag).date(), p[0]] + v)
    x = pd.DataFrame(rows, columns=["day", "who", "cl", "pl", "cs", "ps"])
    w = x.pivot_table(index="day", columns="who", values=["cl", "pl", "cs", "ps"])
    tc = w["cl"].sum(1)
    tp = w["pl"].sum(1)
    out = pd.DataFrame(index=w.index)
    out["cli_c"] = (w["cl"]["Client"] - w["cs"]["Client"]) / tc
    out["cli_p"] = (w["pl"]["Client"] - w["ps"]["Client"]) / tp
    out["pro_c"] = (w["cl"]["Pro"] - w["cs"]["Pro"]) / tc
    out["pro_p"] = (w["pl"]["Pro"] - w["ps"]["Pro"]) / tp
    return out


def main():
    x = pd.read_parquet(f"{S}/hunt/h40/data/fo_idxopt_near.parquet",
                        columns=["day", "sym", "exp", "k", "typ", "settle", "oi", "fmt"])
    for c in ("day", "sym", "exp", "typ"):
        x[c] = x[c].astype(str)
    x = x[x.sym.isin(UNDS)]
    mf = x.groupby("day").fmt.transform("min")
    x = x[x.fmt == mf]
    x["day"] = pd.to_datetime(x.day)
    x["exp"] = pd.to_datetime(x.exp)
    P = poi()
    P.index = pd.to_datetime(P.index)
    rows = []
    for (u, d), g in x.groupby(["sym", "day"], sort=True):
        g = g[(g.exp > d) & (g.settle > 0)]
        if g.empty:
            continue
        exps = sorted(g.exp.unique())[:3]
        parts = []
        for e in exps:
            ge = g[g.exp == e]
            c = ge[ge.typ == "CE"].set_index("k")
            p = ge[ge.typ == "PE"].set_index("k")
            ks = c.index.intersection(p.index)
            if len(ks) < 5:
                continue
            diff = (c.loc[ks, "settle"] - p.loc[ks, "settle"]).abs()
            k0 = diff.idxmin()
            F = float(k0 + c.loc[k0, "settle"] - p.loc[k0, "settle"])
            T = max((e - d).days, 0.5) / 365.0
            ge = ge.copy()
            ge["F"], ge["T"] = F, T
            ge["call"] = ge.typ == "CE"
            K = ge.k.values.astype(float)
            otm = np.where(K >= F, True, False)
            # IV from the OTM option at each strike
            ivs = {}
            for side in ("CE", "PE"):
                s = ge[ge.typ == side]
                v = iv(s.settle.values.astype(float), F, s.k.values.astype(float), T, side == "CE")
                ivs[side] = pd.Series(v, index=s.k.values)
            ivk = pd.Series(np.where(K >= F, ivs["CE"].reindex(K).values, ivs["PE"].reindex(K).values), index=K)
            atm = ivk.iloc[np.argsort(np.abs(K - F))[:4]].dropna()
            atm_iv = float(atm.mean()) if len(atm) else 0.15
            ge["iv"] = ivk.fillna(atm_iv).values
            ge["atm_iv"] = atm_iv
            del otm
            parts.append(ge)
        if not parts:
            continue
        G = pd.concat(parts)
        S0 = float(parts[0]["F"].iloc[0])
        Kv, Fv, Tv, sv = G.k.values.astype(float), G.F.values, G["T"].values, G.iv.values
        oi = G.oi.values.astype(float)
        call = G.call.values
        sgnA = np.where(call, 1.0, -1.0)

        def gex_at(x, w):
            gm = gamma(Fv * x, Kv, Tv, sv)
            return float(np.sum(w * oi * gm) * (S0 * x) ** 2 * 0.01)
        gm0 = gamma(Fv, Kv, Tv, sv)
        tot = float(np.sum(oi * gm0) * S0 ** 2 * 0.01)
        gA = gex_at(1.0, sgnA)
        grid = np.array([gex_at(xx, sgnA) for xx in GRID])
        sg = np.sign(grid)
        ch = np.nonzero(sg[1:] != sg[:-1])[0]
        if len(ch):
            i0 = ch[np.argmin(np.abs(GRID[ch] - 1.0))]
            # linear interpolation of the crossing
            x0, x1, y0, y1 = GRID[i0], GRID[i0 + 1], grid[i0], grid[i0 + 1]
            xf = x0 - y0 * (x1 - x0) / (y1 - y0)
            flip = S0 * xf
        else:
            flip = np.nan
        r = dict(und=u, day=d, S0=S0, tot=tot, gexA=gA, ratioA=gA / tot if tot > 0 else np.nan, flip=flip,
                 flipdist=(S0 / flip - 1) * 100 if np.isfinite(flip) else np.nan,
                 allpos=bool((grid > 0).all()), allneg=bool((grid < 0).all()),
                 atm_iv=float(parts[0]["atm_iv"].iloc[0]), dte=int((exps[0] - d).days), nexp=len(parts))
        if d in P.index:
            q = P.loc[d]
            wC = np.where(call, -q.cli_c, -q.cli_p)          # dealers = minus the clients
            wD = np.where(call, q.pro_c, q.pro_p)
            r["gexC"] = gex_at(1.0, wC)
            r["gexD"] = gex_at(1.0, wD)
            r["ratioC"] = r["gexC"] / tot if tot > 0 else np.nan
            r["ratioD"] = r["gexD"] / tot if tot > 0 else np.nan
        rows.append(r)
    out = pd.DataFrame(rows)
    out.to_parquet(f"{OUT}/gex.parquet")
    print(out.groupby("und").agg(n=("day", "size"), d0=("day", "min"), d1=("day", "max"), posA=("gexA", lambda s: (s > 0).mean()),
                                 posC=("gexC", lambda s: (s > 0).mean()), posD=("gexD", lambda s: (s > 0).mean()),
                                 flipok=("flip", lambda s: s.notna().mean()), iv=("atm_iv", "median")))
    print(out.describe().T)


if __name__ == "__main__":
    main()
