"""h42 look-ahead audit: rebuild the tables from caches whose data AFTER column 200 (12:35) is randomly perturbed
(index, options, OI, volume, VIX minutes, constituents). Every FEATURE at a decision minute s <= 195 and every
day-table feature known by 12:30 must come out identical; only outcome columns may change.
python3 -I research/hunt/h42/audit.py
"""
from __future__ import annotations

import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tables as TB  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

CUT = 200
SRC = TB.OUT
TST = os.path.join(SRC, "audit")
OUTCOME_MIN = {"f5", "f15", "f30", "f60", "fsq", "frng60", "fstrad60", "jC", "dC", "jP", "dP", "okC", "okP"}
DAY_KNOWN = ["gap", "r5", "r15", "r30", "r60", "rg5", "rg15", "rg30", "rg60", "rg60_rel", "id60", "id120", "id195", "pos60",
             "pos120", "pos195", "hiearly60", "hiearly120", "hiearly195", "loearly60", "loearly120", "loearly195", "vix1015",
             "strad920", "pinside165", "pindist165", "pret", "prng", "nr7", "inside", "pstreak", "vixp", "vixchp", "dte", "dow",
             "bumin", "bdmin", "pumin", "pdmin", "breadth1015", "xr15_NIFTY", "xr15_BANKNIFTY"]


PD = None          # perturbed day rows (calendar days with day-number % 10 == 0: the previous 5 trading days stay clean, so causal z-scores must match)


def perturb(a, rng):
    a = a.astype(np.float64).copy()
    a[PD, CUT:] *= rng.uniform(0.9, 1.1, size=a[PD, CUT:].shape)
    return a.astype(np.float32)


def main():
    rng = np.random.default_rng(7)
    os.makedirs(TST, exist_ok=True)
    for u in TB.UNDS:
        z = np.load(os.path.join(SRC, f"c_{u}.npz"))
        n = len(z["days"])
        keep = slice(n - 150, n - 60)                    # 90 days (pre-holdout-free choice is irrelevant for an audit)
        global PD
        PD = z["days"][keep] % 10 == 0          # common calendar days for every index, VIX and stock
        out = {}
        for k in z.files:
            a = z[k][keep]
            if a.ndim == 2 and a.shape[1] == 375:
                a = perturb(a, rng) if k not in ("maxK",) else a
                if k == "maxK":
                    a = a.copy()
                    a[PD, CUT:] += 100 * rng.integers(-3, 4, size=a[PD, CUT:].shape)
            out[k] = a
        np.savez(os.path.join(TST, f"c_{u}.npz"), **out)
        z2 = {k: (z[k][keep]) for k in z.files}
        np.savez(os.path.join(TST, f"o_{u}.npz"), **z2)
    vm0 = TB.D.market().vix.minutes()
    zN = np.load(os.path.join(SRC, "c_NIFTY.npz"))["days"]
    pdays = set()
    for u in TB.UNDS:
        dd = np.load(os.path.join(TST, f"c_{u}.npz"))["days"]
        pdays |= set(int(x) for x in dd[dd % 10 == 0])
    PDAYS = pdays
    vm_p = {d: (np.where(np.arange(375) >= CUT, v * rng.uniform(0.9, 1.1, 375), v) if (d - TB.EPOCH).days in PDAYS else v)
            for d, v in vm0.items()}
    st0 = TB.load_stocks()
    st_p = {}
    for k, (d, c, o) in st0.items():
        m = np.isin(d, list(PDAYS))[:, None] & (np.arange(375)[None, :] >= CUT)
        st_p[k] = (d, np.where(m, c * 1.05, c), o)
    res = {}
    for tag, vm, st, pref in (("orig", vm0, st0, "o_"), ("pert", vm_p, st_p, "c_")):
        for u in TB.UNDS:
            shutil.copy(os.path.join(TST, f"{pref}{u}.npz"), os.path.join(TST, f"x_{u}.npz"))
        TB.OUT = TST
        TB.ld = lambda u: {k: v for k, v in np.load(os.path.join(TST, f"x_{u}.npz")).items()}  # noqa: E731
        TB.D.market().vix._min = vm
        TB.load_stocks = lambda st=st: st  # noqa: E731
        TB.build_all()
        res[tag] = {u: (pd.read_parquet(os.path.join(TST, f"min_{u}.parquet")), pd.read_parquet(os.path.join(TST, f"day_{u}.parquet")))
                    for u in TB.UNDS}
    bad = []
    for u in TB.UNDS:
        mo, do = res["orig"][u]
        mp, dp = res["pert"][u]
        zd = np.load(os.path.join(TST, f"c_{u}.npz"))["days"]
        mine = set(int(x) for x in zd[zd % 10 == 0])
        a = mo[(mo.s <= 195) & mo.day.isin(mine)].reset_index(drop=True)
        b = mp[(mp.s <= 195) & mp.day.isin(mine)].reset_index(drop=True)
        do = do[do.day.isin(mine)].reset_index(drop=True)
        dp = dp[dp.day.isin(mine)].reset_index(drop=True)
        for c in a.columns:
            if c in OUTCOME_MIN or not pd.api.types.is_numeric_dtype(a[c]):
                continue
            x, y = a[c].values.astype(float), b[c].values.astype(float)
            diff = ~(np.isclose(x, y, equal_nan=True, rtol=1e-5))
            if diff.any():
                bad.append((u, "min", c, int(diff.sum())))
        for c in DAY_KNOWN:
            if c not in do:
                continue
            x, y = do[c].values.astype(float), dp[c].values.astype(float)
            diff = ~(np.isclose(x, y, equal_nan=True, rtol=1e-5))
            if c.endswith("min"):                   # first-crossing minutes: only crossings before the cut are 'known'
                diff &= (x >= 0) & (x < CUT)
            if diff.any():
                bad.append((u, "day", c, int(diff.sum())))
        print(u, "checked", len(a.columns), "min columns x", len(a), "rows,", len(DAY_KNOWN), "day columns x", len(do), "days", flush=True)
    print("LOOK-AHEAD VIOLATIONS:", bad if bad else "none")
    shutil.rmtree(TST)


if __name__ == "__main__":
    main()
