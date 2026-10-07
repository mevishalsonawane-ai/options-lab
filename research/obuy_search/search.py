"""OBUY search - the in-sample search over every rule permutation, with the multiple-testing bookkeeping done in the
same pass (reads ONLY <scratchpad>/obs/is_*.npz - the holdout file is never opened here).

    python3 -I research/obuy_search/search.py <scratchpad>

Strategy = entry rule (1-3 atoms from different families, AND-ed) x side (CE / PE) x entry window x index
           x strike (ATM / 1-ITM) x exit (52-exit menu).
Entry: the first 5-minute decision slot inside the window where all atoms hold; one trade per strategy per day.
Daily P&L series: net Rs of 1 lot on the trade day, 0 on days without a trade, over the union in-sample calendar.
Per strategy: mean, sd, t = mean / (sd / sqrt(D)); stationary-bootstrap (mean block 5, B=400) recentred max for
White's Reality Check and Hansen's SPA; per-year sums for the walk-forward.
Strategies with fewer than MIN_N in-sample trades are generated and counted but not evaluated.
Output: <scratchpad>/obs/search.npz (+ search_log.txt).
"""
from __future__ import annotations

import itertools
import os
import sys
import time

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402

SP = sys.argv[1]
OBS = os.path.join(SP, "obs")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY"]
MIN_N = 40
B = 400
BLOCK = 5
SEED = 20251001
FAMILY = {
    "orb5_with": "ORB", "orb15_with": "ORB", "orb30_with": "ORB", "orb15_against": "ORB", "orb15_inside": "ORB",
    "gap_with_big": "GAP", "gap_with_small": "GAP", "gap_flat": "GAP", "gap_against_small": "GAP", "gap_against_big": "GAP",
    "vwap_with": "VWAP", "vwap_far_with": "VWAP", "vwap_far_against": "VWAP",
    "ema1_with": "EMA", "ema5_with": "EMA", "ema15_with": "EMA", "ema5_against": "EMA",
    "rsi_mom_with": "RSI", "rsi_ext_with": "RSI", "rsi_ext_against": "RSI", "rsi_mid": "RSI",
    "nr4": "RANGE", "nr7": "RANGE", "prev_wide": "RANGE", "fh_wide": "RANGE", "fh_narrow": "RANGE", "day_range_big": "RANGE",
    "pdhl_with": "PREV", "pdc_with": "PREV", "pdhl_against": "PREV", "inside_prev": "PREV",
    "cpr_narrow": "CPR", "cpr_wide": "CPR", "cpr_with": "CPR",
    "vix_low": "VIX", "vix_mid": "VIX", "vix_high": "VIX", "vix_up": "VIXCHG", "vix_down": "VIXCHG",
    "pcr_with": "OI", "pcr_against": "OI", "oi_with": "OICHG",
    "strad_up": "STRAD", "strad_down": "STRAD",
    "expiry_day": "DTE", "pre_expiry": "DTE", "far_expiry": "DTE",
    "prev_with": "TREND", "prev3_with": "TREND", "prev_big_against": "TREND",
    "bigbar_with": "BIGBAR", "bigbar_against": "BIGBAR",
    "move_with": "INTRA", "move_against": "INTRA",
}
# windows over the 63 slots (09:20 + 5k)
WINDOWS = {"0920-1015": (0, 11), "1015-1200": (11, 32), "1200-1430": (32, 63), "0920-1430": (0, 63)}
WNAMES = list(WINDOWS)
YEARS = [2020, 2021, 2022, 2023, 2024, 2025]
FOLDS = [2022, 2023, 2024, 2025]          # walk-forward test years (train = all in-sample years before)
TOPK = 20


def lowbit_index(x):
    lb = x & (~x + np.uint64(1))
    return np.log2(lb.astype(np.float64)).astype(np.int64)


def main():
    t_start = time.time()
    data = {u: np.load(os.path.join(OBS, f"is_{u}.npz")) for u in UNDS}
    atoms = list(data["NIFTY"]["atoms"])
    exits = list(data["NIFTY"]["exits"])
    NE = len(exits)
    NC = 2 * NE                                      # strike x exit columns
    cal = np.unique(np.concatenate([data[u]["days"] for u in UNDS]))
    D = len(cal)
    rng = np.random.default_rng(SEED)
    # stationary bootstrap index draws -> count matrix W (D x B)
    W = np.zeros((D, B), dtype=np.float32)
    for b in range(B):
        idx = np.empty(D, dtype=np.int64)
        i = rng.integers(D)
        for k in range(D):
            if k > 0:
                i = rng.integers(D) if rng.random() < 1.0 / BLOCK else (i + 1) % D
            idx[k] = i
        W[:, b] = np.bincount(idx, minlength=D)
    c_spa = np.sqrt(2 * np.log(np.log(D)))
    cal_year = np.array([int(str(x)[:4]) for x in cal])
    days_in_year = np.array([(cal_year == y).sum() for y in YEARS])

    # combos of atoms from distinct families
    fam = [FAMILY[a] for a in atoms]
    combos = []
    for r in (1, 2, 3):
        for c in itertools.combinations(range(len(atoms)), r):
            if len({fam[i] for i in c}) == r:
                combos.append(c)
    n_generated = len(combos) * 2 * len(WINDOWS) * len(UNDS) * NC
    print("atoms", len(atoms), "combos", len(combos), "generated strategies", n_generated, flush=True)

    rc_max = np.full(B, -np.inf)
    spa_max = np.zeros(B)
    T_all, MU_all, N_all, RID_all = [], [], [], []
    rules = []                                       # (und, dir, combo, window)
    wf = {y: [] for y in FOLDS}                      # (train t, test sum, test n, strategy id)
    wf_thr = {y: -np.inf for y in FOLDS}
    n_eval_rules = 0
    for u in UNDS:
        z = data[u]
        feat, pnl = z["feat"], z["pnl"]
        nd = feat.shape[0]
        bits = (np.uint64(1) << np.arange(63, dtype=np.uint64))
        masks = np.zeros((2, len(atoms), nd), dtype=np.uint64)
        for dr in (0, 1):
            for a in range(len(atoms)):
                masks[dr, a] = (feat[:, :, dr, a].astype(np.uint64) * bits[None, :]).sum(axis=1)
        wmask = {w: np.uint64(sum(1 << s for s in range(a, b))) for w, (a, b) in WINDOWS.items()}
        calpos = np.searchsorted(cal, z["days"])
        dyear = cal_year[calpos]
        P = [np.nan_to_num(pnl[:, :, dr].reshape(nd * 63, NC)).astype(np.float32) for dr in (0, 1)]
        V = [(~np.isnan(pnl[:, :, dr, :, 0])).reshape(nd * 63, 2) for dr in (0, 1)]
        for dr in (0, 1):
            Pd, Vd = P[dr], V[dr]
            for ci, c in enumerate(combos):
                cm = masks[dr, c[0]]
                for a in c[1:]:
                    cm = cm & masks[dr, a]
                for w in WNAMES:
                    m = cm & wmask[w]
                    dd = np.nonzero(m)[0]
                    rid = len(rules)
                    rules.append((u, dr, ci, w))
                    if len(dd) < MIN_N:
                        continue
                    rows = dd * 63 + lowbit_index(m[dd])
                    ntr = Vd[rows].sum(axis=0)                    # trades per strike
                    if ntr.max() < MIN_N:
                        continue
                    n_eval_rules += 1
                    X = Pd[rows]                                   # n x NC
                    S = X.sum(0, dtype=np.float64)
                    SS = (X.astype(np.float64) ** 2).sum(0)
                    mu = S / D
                    var = np.maximum(SS / D - mu ** 2, 1e-12)
                    se = np.sqrt(var / D)
                    t = mu / se
                    ncol = np.repeat(ntr, NE)
                    t = np.where(ncol >= MIN_N, t, np.nan)
                    # bootstrap
                    bm = (W[calpos[dd]].T @ X) / D                 # B x NC
                    ok = ~np.isnan(t)
                    if ok.any():
                        Ts = (bm[:, ok] - mu[ok]) / se[ok]
                        rc_max = np.maximum(rc_max, Ts.max(1))
                        cen = np.where(t[ok] >= -c_spa, mu[ok], 0.0)
                        Zs = (bm[:, ok] - cen) / se[ok]
                        spa_max = np.maximum(spa_max, np.maximum(Zs.max(1), 0))
                    T_all.append(t.astype(np.float32))
                    MU_all.append((S / np.maximum(ncol, 1)).astype(np.float32))     # mean per trade
                    N_all.append(ncol.astype(np.int16))
                    RID_all.append(rid)
                    # walk-forward: per-year sums
                    yi = np.searchsorted(YEARS, dyear[dd])
                    bnd = np.r_[0, np.nonzero(np.diff(yi))[0] + 1]
                    YS = np.zeros((len(YEARS), NC)); YSS = np.zeros((len(YEARS), NC)); YN = np.zeros((len(YEARS), 2))
                    YS[yi[bnd]] = np.add.reduceat(X, bnd, axis=0)
                    YSS[yi[bnd]] = np.add.reduceat(X.astype(np.float64) ** 2, bnd, axis=0)
                    YN[yi[bnd]] = np.add.reduceat(Vd[rows].astype(np.float64), bnd, axis=0)
                    for y in FOLDS:
                        k = YEARS.index(y)
                        Dtr = days_in_year[:k].sum()
                        s_ = YS[:k].sum(0); ss_ = YSS[:k].sum(0); n_ = np.repeat(YN[:k].sum(0), NE)
                        m_ = s_ / Dtr
                        tt = m_ / np.sqrt(np.maximum(ss_ / Dtr - m_ ** 2, 1e-12) / Dtr)
                        tt = np.where(n_ >= 30, tt, -np.inf)
                        best = np.nonzero(tt > wf_thr[y])[0]
                        for col in best:
                            wf[y].append((tt[col], YS[k, col], np.repeat(YN[k], NE)[col], rid * NC + col))
                        if len(wf[y]) > 4 * TOPK:
                            wf[y].sort(key=lambda r: -r[0])
                            wf[y] = wf[y][:TOPK]
                            wf_thr[y] = wf[y][-1][0]
            print(u, "dir", dr, "done; evaluated rules so far", n_eval_rules, f"{time.time() - t_start:.0f}s", flush=True)
    T = np.concatenate(T_all); MU = np.concatenate(MU_all); N = np.concatenate(N_all)
    RID = np.array(RID_all)
    for y in FOLDS:
        wf[y].sort(key=lambda r: -r[0]); wf[y] = wf[y][:TOPK]
    np.savez(os.path.join(OBS, "search.npz"), T=T, MU=MU, N=N, RID=RID,
             rules=np.array([(u, dr, ci, w) for u, dr, ci, w in rules], dtype=object),
             combos=np.array([",".join(atoms[i] for i in c) for c in combos]), exits=np.array(exits),
             rc_max=rc_max, spa_max=spa_max, D=D, n_generated=n_generated, n_rules=len(rules),
             n_eval_rules=n_eval_rules, NC=NC, NE=NE,
             wf=np.array([(y,) + r for y in FOLDS for r in wf[y]], dtype=np.float64),
             days_in_year=days_in_year)
    print("done", f"{time.time() - t_start:.0f}s", "evaluated strategies", int(np.isfinite(T).sum()), flush=True)


if __name__ == "__main__":
    main()
