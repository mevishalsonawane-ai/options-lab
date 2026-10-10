"""h29 descriptive checks D1-D3 (PREREG). No trading, nothing selected from these.

    OBUY_CACHE=<scratch>/hunt/h29/cache python3 -I research/hunt/h29/describe.py pre|hold
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
HOLD = pd.Timestamp("2025-10-01")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"]
W = C.W
pd.set_option("display.width", 250)


def load(u, mode):
    F = pd.read_parquet(os.path.join(OUT, f"feat_{u}.parquet"))
    F = F[F.day < HOLD] if mode == "pre" else F[F.day >= HOLD]
    return F.reset_index(drop=True)


def spearman(a, b):
    ok = np.isfinite(a) & np.isfinite(b)
    if ok.sum() < 100:
        return np.nan, 0
    ra = pd.Series(a[ok]).rank().values
    rb = pd.Series(b[ok]).rank().values
    return float(np.corrcoef(ra, rb)[0, 1]), int(ok.sum())


def main(mode):
    times = [560, 575, 600, 660, 720, 750, 780, 840, 900]
    d1 = {}
    d2rows, d3rows, sanity = [], [], []
    for u in UNDS:
        F = load(u, mode)
        F = F[(~F.exp) & F.real]
        n = len(F) // W
        iv = F.iv.values.reshape(n, W).astype(float)
        S = F.S.values.reshape(n, W).astype(float)
        dhan = None
        # D1: median IV path relative to 09:20 (non-expiry days)
        base = iv[:, 5]
        rel = iv / base[:, None]
        d1[u] = {C.hm(m): float(np.nanmedian(rel[:, m - C.OPEN_M])) for m in times}
        # D2: implied vs realised move to 15:10, by cheap1 quintile, at 10:00 / 11:30 / 13:00
        c1 = F.cheap1.values.reshape(n, W).astype(float)
        ts = F.ivm.values.reshape(n, W).astype(float) - iv
        e = 15 * 60 + 10 - C.OPEN_M
        for m in (599, 689, 779):
            j = m - C.OPEN_M
            mins_left = e - j
            imp = S[:, j] * iv[:, j] * np.sqrt(mins_left / (375 * 252)) * np.sqrt(2 / np.pi)
            real = np.abs(S[:, e] - S[:, j])
            rng = np.nanmax(S[:, j:e + 1], axis=1) - np.nanmin(S[:, j:e + 1], axis=1)
            q = pd.qcut(c1[:, j], 5, labels=False, duplicates="drop")
            for k in range(5):
                s = q == k
                d2rows.append(dict(und=u, t=C.hm(m + 1), q=k + 1, n=int(s.sum()), cheap1=np.nanmedian(c1[s, j]),
                                   real_over_imp=np.nanmean(real[s]) / np.nanmean(imp[s]),
                                   range_over_imp=np.nanmean(rng[s]) / np.nanmean(imp[s])))
            ok = np.isfinite(ts[:, j])
            if ok.sum() > 100:
                qt = pd.qcut(ts[ok, j], 3, labels=False, duplicates="drop")
                for k in range(3):
                    s = qt == k
                    d2rows.append(dict(und=u, t=C.hm(m + 1), q=f"term{k + 1}", n=int(s.sum()), cheap1=np.nanmedian(ts[ok, j][s]),
                                       real_over_imp=np.nanmean(real[ok][s]) / np.nanmean(imp[ok][s]),
                                       range_over_imp=np.nanmean(rng[ok][s]) / np.nanmean(imp[ok][s])))
        # D3: ICs with next-h index returns (bps), sampled every 5 min 09:30-14:30
        cols = np.arange(15, 14 * 60 + 30 - C.OPEN_M + 1, 5)
        feats = dict(dbz=F.dbz, drz=F.drz, rr=F.rr, basis_d30=F.basis - F.groupby("day").basis.shift(30))
        for h in (5, 15, 30):
            fwd = np.full_like(S, np.nan)
            fwd[:, :-h] = (S[:, h:] / S[:, :-h] - 1) * 1e4
            fw = fwd[:, cols].ravel()
            past = np.full_like(S, np.nan)
            past[:, 5:] = (S[:, 5:] / S[:, :-5] - 1) * 1e4
            for nm, s in feats.items():
                x = s.values.reshape(n, W).astype(float)[:, cols].ravel()
                ic, nn = spearman(x, fw)
                # partial: the feature's IC after removing the last 5-min index move (a lagging-index artefact check)
                pv = past[:, cols].ravel()
                ok = np.isfinite(x) & np.isfinite(fw) & np.isfinite(pv)
                xr = x[ok] - np.polyval(np.polyfit(pv[ok], x[ok], 1), pv[ok]) if ok.sum() > 100 else x[ok]
                icp, _ = spearman(xr, fw[ok])
                d3rows.append(dict(und=u, feat=nm, h=h, ic=ic, ic_partial=icp, n=nn))
        sanity.append(dict(und=u, days=n, iv_med=np.nanmedian(iv), cheap1_med=np.nanmedian(c1),
                           rr_med=float(np.nanmedian(F.rr)), basis_med=float(np.nanmedian(F.basis))))
    print(f"=== {mode}: sanity ===")
    print(pd.DataFrame(sanity).round(4).to_string())
    print("\n=== D1 median ATM IV relative to 09:20 (non-expiry days) ===")
    print(pd.DataFrame(d1).T.round(3).to_string())
    print("\n=== D2 realised |move| (and range) to 15:10 / implied, by cheap1 quintile (1 = cheapest) ===")
    D2 = pd.DataFrame(d2rows)
    print(D2.round(3).to_string())
    print("\n=== D3 Spearman IC with next-h index return (x100) ===")
    D3 = pd.DataFrame(d3rows)
    D3[["ic", "ic_partial"]] *= 100
    print(D3.pivot_table(index=["feat", "h"], columns="und", values=["ic", "ic_partial"]).round(2).to_string())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "pre")
