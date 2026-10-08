"""Price a Delta-style ATM straddle opened at EVERY hour, for each horizon, and evaluate every pre-registered exit
under three spread scenarios. Prices: Black-76 on Deribit hourly trade IV (see PREREG). Output: one row per
(hour, horizon, exit, scenario) with gross and net returns; the rule layer (rules.py) picks which rows are traded.
Usage: python3 -I sim.py SCRATCH CUR"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crypto")
import json
import numpy as np, pandas as pd
from scipy.special import ndtr
from build import spot5, iv_table

SCR, CUR = sys.argv[1], sys.argv[2]
YRH = 365 * 24.0
GST = 1.18
FEE_N, FEE_CAP = 1e-4, 0.035
SPREAD = json.load(open(f"{SCR}/data/spread_model.json"))  # {"BTC": [a, b], "ETH": [a, b], "deribit_hs": {..}}
EXITS = [(None, None), (0.30, 0.30), (0.30, 0.50), (0.60, 0.30), (0.60, 0.50)]
HZ = {"1": 1, "4": 4, "24": 24, "X": None}  # X = hold to expiry (same expiry as the 24 h trade)
GRID = {"BTC": 0.0025, "ETH": 0.008}
MAXM = 10


def nice_step(x):
    e = 10 ** np.floor(np.log10(x))
    c = np.array([1, 2, 2.5, 5, 10]) * e[:, None]
    return c[np.arange(len(x)), np.abs(c - x[:, None]).argmin(1)]


def b76(F, K, T, iv):
    """call, put per unit; T in years; returns intrinsic where T<=0."""
    T = np.maximum(T, 0)
    sd = iv * np.sqrt(T)
    with np.errstate(divide="ignore", invalid="ignore"):
        d1 = (np.log(F / K) + 0.5 * sd ** 2) / sd
        c = F * ndtr(d1) - K * ndtr(d1 - sd)
    ci = np.maximum(F - K, 0)
    c = np.where(sd > 1e-9, c, ci)
    p = c - (F - K)
    return np.maximum(c, ci), np.maximum(p, np.maximum(K - F, 0))


def surface(hours):
    """Padded per-hour matrices of Deribit expiries (hours to expiry, IV) with near-ATM trades in [H-1h, H)."""
    d = iv_table(CUR)
    d = d[(d.Th >= 2)].sort_values(["H", "Th"])
    d["r"] = hours.get_indexer(d.H)
    d = d[d.r >= 0]
    d["j"] = d.groupby("r").cumcount()
    d = d[d.j < MAXM]
    TH = np.full((len(hours), MAXM), np.nan); IV = np.full((len(hours), MAXM), np.nan)
    TH[d.r.values, d.j.values] = d.Th.values; IV[d.r.values, d.j.values] = d.iv_med.values / 100
    hs = d[(d.Th <= 48) & (d.n_hs >= 3)].hs_med
    return TH, IV, float(hs.median())


def ivq(TH, IV, rows, T):
    """total-variance interpolation in T (hours), flat outside; NaN if the hour has no expiries."""
    th = TH[rows]; iv = IV[rows]
    n = np.sum(~np.isnan(th), 1)
    out = np.full(len(rows), np.nan)
    ok = n > 0
    th, iv, T, n = th[ok], iv[ok], T[ok], n[ok]
    lo = np.nanmin(th, 1)
    last = np.take_along_axis(th, (n - 1)[:, None], 1)[:, 0]
    ivlo = iv[:, 0]; ivhi = np.take_along_axis(iv, (n - 1)[:, None], 1)[:, 0]
    w = iv ** 2 * th
    j = np.sum(th < T[:, None], 1)  # number of expiries strictly shorter
    j1 = np.clip(j, 1, MAXM - 1)
    t0 = np.take_along_axis(th, (j1 - 1)[:, None], 1)[:, 0]; t1 = np.take_along_axis(th, j1[:, None], 1)[:, 0]
    w0 = np.take_along_axis(w, (j1 - 1)[:, None], 1)[:, 0]; w1 = np.take_along_axis(w, j1[:, None], 1)[:, 0]
    with np.errstate(invalid="ignore", divide="ignore"):
        wi = w0 + (w1 - w0) * (T - t0) / (t1 - t0)
        mid = np.sqrt(np.maximum(wi, 1e-8) / np.maximum(T, 1e-6))
    res = np.where(T <= lo, ivlo, np.where(T >= last, ivhi, mid))
    out[ok] = res
    return out


def main():
    f = pd.read_parquet(f"{SCR}/data/{CUR}_feat.parquet")
    hours = f.index
    c5 = spot5(CUR)
    cv = c5.values
    p0 = c5.index.get_indexer(hours)
    TH, IV, deribit_hs = surface(hours)
    # fallback: DVOL x trailing median of (24h-interpolated IV / DVOL)
    iv24 = ivq(TH, IV, np.arange(len(hours)), np.full(len(hours), 24.0))
    ratio = pd.Series(iv24 / (f.dvol.values / 100), index=hours).rolling(720, min_periods=48).median().shift(1).ffill()
    fb = (f.dvol.values / 100) * ratio.fillna(1.0).values
    a, b = SPREAD[CUR]
    scen = {"delta": ("lin", 1.0), "deribit": ("frac", deribit_hs), "delta2x": ("lin", 2.0)}
    out = []
    S0all = f.S.values
    for hz, h in HZ.items():
        expcol = "exp24" if hz == "X" else f"exp{hz}"
        T0all = ((f[expcol] - hours).dt.total_seconds() / 3600).values
        for c0 in range(0, len(hours), 2000):
            idx = np.arange(c0, min(c0 + 2000, len(hours)))
            T0 = T0all[idx]
            Kst = (np.round(T0) if h is None else np.full(len(idx), float(h))) * 12
            Kst = Kst.astype(int)
            okp = p0[idx] + Kst < len(cv)
            idx, T0, Kst = idx[okp], T0[okp], Kst[okp]
            if len(idx) == 0:
                continue
            S0 = S0all[idx]
            step = nice_step(S0 * GRID[CUR])
            K = np.round(S0 / step) * step
            iv0 = ivq(TH, IV, idx, T0)
            src0 = np.where(np.isnan(iv0), 1, 0)
            iv0 = np.where(np.isnan(iv0), fb[idx], iv0)
            Km = Kst.max()
            ks = np.arange(1, Km + 1)
            P = p0[idx][:, None] + ks[None, :]
            valid = ks[None, :] <= Kst[:, None]
            P = np.where(valid, P, p0[idx][:, None])
            S = cv[P]
            Tr = T0[:, None] - ks[None, :] / 12.0
            rows = idx[:, None] + ks[None, :] // 12
            rows = np.minimum(rows, len(hours) - 1)
            ivp = ivq(TH, IV, rows.ravel(), np.maximum(Tr, 1e-3).ravel()).reshape(rows.shape)
            ivp = np.where(np.isnan(ivp), fb[rows], ivp)
            C, Pp = b76(S, K[:, None], Tr / YRH, ivp)
            C0, P00 = b76(S0, K, T0 / YRH, iv0)
            atexp = Tr <= 1e-9
            for sname, (kind, par) in scen.items():
                def hs(mid, Sx):
                    if kind == "lin":
                        return par * 0.5 * (a * Sx + b * mid)
                    return par * mid
                ca0 = C0 + hs(C0, S0); pa0 = P00 + hs(P00, S0)
                fee0 = GST * (np.minimum(FEE_N * S0, FEE_CAP * ca0) + np.minimum(FEE_N * S0, FEE_CAP * pa0))
                cb = np.where(atexp, C, np.maximum(C - hs(C, S), 0)); pb = np.where(atexp, Pp, np.maximum(Pp - hs(Pp, S), 0))
                prem = ca0 + pa0
                r = (cb + pb) / prem[:, None] - 1
                feex = GST * (np.minimum(FEE_N * S, FEE_CAP * cb) + np.minimum(FEE_N * S, FEE_CAP * pb))
                for ei, (tg, sp) in enumerate(EXITS):
                    hit = np.zeros_like(valid)
                    if tg is not None:
                        hit = valid & ((r >= tg) | (r <= -sp))
                    hit[np.arange(len(idx)), Kst - 1] = True
                    ke = hit.argmax(1)
                    ii = np.arange(len(idx))
                    vb = cb[ii, ke] + pb[ii, ke]
                    vm = C[ii, ke] + Pp[ii, ke]
                    net = (vb - feex[ii, ke]) / (prem + fee0) - 1
                    gross = vm / (C0 + P00) - 1
                    out.append(pd.DataFrame(dict(H=hours[idx], hz=hz, ex=ei, sc=sname, kexit=ke + 1,
                                                 gross=gross.astype(np.float32), net=net.astype(np.float32),
                                                 cost=((prem + fee0) / S0).astype(np.float32),
                                                 why=np.where(r[ii, ke] >= (tg or 9), 1, np.where(r[ii, ke] <= -(sp or 9), -1, 0)).astype(np.int8))))
            # descriptive (scenario independent)
            last = Kst - 1
            ii = np.arange(len(idx))
            out.append(pd.DataFrame(dict(H=hours[idx], hz=hz, ex=-1, sc="info", kexit=Kst, gross=np.float32(np.nan),
                                         net=np.float32(np.nan), cost=((C0 + P00) / S0).astype(np.float32), why=src0.astype(np.int8),
                                         iv0=iv0.astype(np.float32), T0=T0.astype(np.float32),
                                         end_mid=((C[ii, last] + Pp[ii, last]) / S0).astype(np.float32))))
            print(CUR, hz, c0, flush=True)
    o = pd.concat(out, ignore_index=True)
    o.to_parquet(f"{SCR}/data/{CUR}_sim.parquet")
    print("rows", len(o), "deribit_hs", deribit_hs)


if __name__ == "__main__":
    main()
