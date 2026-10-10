"""h13 stage 1: one data pass -> path packs for Liquidity 15+5 (BANKNIFTY / FINNIFTY / MIDCPNIFTY; h4's cached, validated
signals, rules unchanged) for each OPTION CHOICE (money x series), 'real' execution, 5 random-entry alternatives per
signal; then the elasticity pass (lambda = delta / premium at the signal minute) for the 'scaled' exits. See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h13/cache flock <scratch>/obuy.lock python3 -I research/hunt/h13/build.py
"""
import os
import pickle
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many  # noqa: E402

H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
OUT = os.path.join(C.CACHE, "h13")
MONEY = (-1, 0, 1, 2)
SERIES = ("near", "month")
EXE = Execution(expiry="skip", fills=Fills(mode="liq"), costs=Costs())
WINDOW = (9 * 60 + 19, 14 * 60 - 1)


def signals():
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    b = b[b.und == "MIDCPNIFTY"]
    sig = pd.concat([a, b], ignore_index=True)
    sig["strike"] = np.nan          # the StrikeRule picks the strike (money=1 gives the arm's own strike)
    return sig


def _last(row, c):
    x = row[max(c - 4, 0):c + 1]
    ok = ~np.isnan(x)
    return x[ok][-1] if ok.any() else np.nan


def lam(ch, right, k, c, step):
    """delta / premium of contract (right, k) at column c, central finite difference over k +- step; nan if missing."""
    i0, im, ip = ch.kpos(k), ch.kpos(k - step), ch.kpos(k + step)
    if min(i0, im, ip) < 0:
        return np.nan
    cl = ch.c[right]
    p0, pm, pp = _last(cl[i0], c), _last(cl[im], c), _last(cl[ip], c)
    if not (np.isfinite(p0) and np.isfinite(pm) and np.isfinite(pp)) or p0 <= 0:
        return np.nan
    d = (pm - pp) / (2 * step) if right == "C" else (pp - pm) / (2 * step)
    d = min(max(d, 0.01), 1.0)
    return d / p0


def elasticity(metas):
    """metas: {(money, series, kind): meta DataFrame}. Adds column f = lambda_k / lambda_ref (ref = ITM1 near)."""
    mk = market()
    rows = []
    for key, m in metas.items():
        if len(m):
            x = m[["und", "day", "sig_min", "side", "strike", "ref"]].copy()
            x["key"] = [key] * len(x)
            x["i"] = np.arange(len(m))
            rows.append(x)
    X = pd.concat(rows, ignore_index=True)
    f = np.full(len(X), np.nan)
    for und, g in X.groupby("und", sort=False):
        opts = mk.options(und)
        step = C.STEP[und]
        for d, gd in g.groupby("day", sort=True):
            chs = {s: opts.chain(d, s) for s in SERIES}
            for r in gd.itertuples():
                c = int(r.sig_min) - C.OPEN_M
                right = "C" if r.side > 0 else "P"
                ch, chn = chs[r.key[1]], chs["near"]
                if ch is None or chn is None:
                    continue
                kref = int(np.floor(r.ref / step + 0.5) * step - r.side * step)
                lk, lr = lam(ch, right, int(r.strike), c, step), lam(chn, right, kref, c, step)
                if np.isfinite(lk) and np.isfinite(lr) and lr > 0:
                    f[r.Index] = lk / lr
        mk.release(und)
    X["f"] = f
    for key, m in metas.items():
        if len(m):
            m["f"] = X[X.key.apply(lambda z: z == key)].sort_values("i").f.values
    return metas


def main():
    os.makedirs(OUT, exist_ok=True)
    sig = signals()
    print("signals", sig.groupby("und").size().to_dict(), flush=True)
    jobs, keys = [], []
    for s in SERIES:
        for mo in MONEY:
            jobs.append((sig, StrikeRule(money=mo, series=s), EXE, 5, WINDOW, False))
            keys.append((mo, s))
    t0 = time.time()
    packs = prepare_many(jobs)
    print("pass done", round(time.time() - t0), flush=True)
    store = packs[0][0].store.arr
    metas = {}
    for k, (pk, pool) in zip(keys, packs):
        metas[k + ("real",)] = pk.meta
        metas[k + ("pool",)] = pool.meta if pool is not None else pd.DataFrame()
    metas = elasticity(metas)
    print("elasticity done", round(time.time() - t0), flush=True)
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((metas, store, sig), f, protocol=4)
    print("saved", {k: v.shape for k, v in store.items()}, flush=True)


if __name__ == "__main__":
    main()
